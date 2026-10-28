package de.gregoire.teamrowingremote.spotify

import de.gregoire.teamrowingremote.net.HttpResponse
import de.gregoire.teamrowingremote.net.httpGet
import de.gregoire.teamrowingremote.net.httpRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

data class Track(
    val uri: String,
    val title: String,
    val artists: String,
    val durationMs: Long,
    val isrc: String? = null,
) {
    /** The Spotify track ID, or null for local files and podcast episodes. */
    val id: String?
        get() = uri.removePrefix(TRACK_URI_PREFIX).takeIf { uri.startsWith(TRACK_URI_PREFIX) }

    private companion object {
        const val TRACK_URI_PREFIX = "spotify:track:"
    }
}

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int,
    /** Changes with every change of the playlist, to reuse its tracks loaded before; null if unknown. */
    val snapshotId: String? = null,
) {
    val uri: String
        get() = PLAYLIST_URI_PREFIX + id
}

data class PlaybackQueue(
    val currentlyPlaying: Track?,
    val upcoming: List<Track>,
)

const val PLAYLIST_URI_PREFIX = "spotify:playlist:"

class SpotifyApiException(
    val status: Int,
    message: String,
) : IOException(message)

/**
 * Access to the Spotify Web API for the logged-in user: reading playlists and the queue, and changing the
 * user's own playlists while preparing sessions. Playback is controlled with [SpotifyRemote].
 */
class SpotifyApi(private val auth: SpotifyAuth) {

    private var userId: String? = null

    /** The user's own playlists, the training sessions. */
    suspend fun getPlaylists(): List<Playlist> {
        val me = userId ?: requireNotNull(get("/me")).getString("id").also { userId = it }
        return getAllPages("/me/playlists?limit=50")
            .filter { it.optJSONObject("owner")?.optString("id") == me }
            .map {
                Playlist(
                    id = it.getString("id"),
                    name = it.getString("name"),
                    trackCount = it.optJSONObject("items")?.optInt("total") ?: 0,
                    snapshotId = it.optString("snapshot_id").takeIf { id -> id.isNotEmpty() },
                )
            }
    }

    /** Creates a private playlist for the user, e.g. a new training session. */
    suspend fun createPlaylist(name: String): Playlist {
        val json = requireNotNull(send("POST", "/me/playlists", JSONObject().put("name", name).put("public", false)))
        return Playlist(
            id = json.getString("id"),
            name = json.getString("name"),
            trackCount = 0,
            snapshotId = json.optString("snapshot_id").takeIf { it.isNotEmpty() },
        )
    }

    /** Adds tracks to a playlist, at [position] or at its end. */
    suspend fun addToPlaylist(playlistId: String, uris: List<String>, position: Int? = null) {
        val body = JSONObject().put("uris", JSONArray(uris))
        if (position != null) body.put("position", position)
        send("POST", "/playlists/$playlistId/items", body)
    }

    /** Moves the track at [from] so that it ends up at [to], counted in the playlist before the move. */
    suspend fun moveInPlaylist(playlistId: String, from: Int, to: Int) {
        // Spotify inserts before the given position, which is counted with the moved track still in place
        val insertBefore = if (to > from) to + 1 else to
        send("PUT", "/playlists/$playlistId/items", JSONObject().put("range_start", from).put("insert_before", insertBefore))
    }

    /** Removes all occurrences of the track [uri] from a playlist; Spotify's call takes tracks, not positions. */
    suspend fun removeFromPlaylist(playlistId: String, uri: String) {
        val items = JSONArray().put(JSONObject().put("uri", uri))
        send("DELETE", "/playlists/$playlistId/items", JSONObject().put("items", items))
    }

    /** The current track and the upcoming tracks, which the App Remote SDK doesn't provide. */
    suspend fun getQueue(): PlaybackQueue {
        val json = get("/me/player/queue") ?: return PlaybackQueue(null, emptyList())
        return PlaybackQueue(
            currentlyPlaying = json.optJSONObject("currently_playing")?.toTrack(),
            upcoming = json.optJSONArray("queue")?.objects()?.map { it.toTrack() }.orEmpty(),
        )
    }

    suspend fun getPlaylistTracks(playlistId: String): List<Track> =
        getAllPages("/playlists/$playlistId/items?limit=50&additional_types=track")
            .mapNotNull { it.optJSONObject("item")?.toTrack() }

    /** Follows the `next` links of a paged response and returns the items of all pages. */
    private suspend fun getAllPages(path: String): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var next: String? = path
        while (next != null) {
            val page = get(next) ?: break
            items += page.optJSONArray("items")?.objects().orEmpty()
            next = if (page.isNull("next")) null else page.getString("next")
        }
        return items
    }

    /**
     * GET request for a path or a full URL, returning the JSON body, or null for 204 No Content.
     * Retries once with a fresh token on 401.
     */
    private suspend fun get(pathOrUrl: String): JSONObject? {
        val url = if (pathOrUrl.startsWith("https://")) pathOrUrl else BASE_URL + pathOrUrl
        return parse(pathOrUrl, withFreshTokenOnUnauthorized { httpGet(url, it) })
    }

    /** Request with [method] and a JSON body, e.g. POST; otherwise like [get]. */
    private suspend fun send(method: String, path: String, body: JSONObject): JSONObject? =
        parse(path, withFreshTokenOnUnauthorized { httpRequest(method, BASE_URL + path, it, body.toString()) })

    private suspend fun withFreshTokenOnUnauthorized(request: suspend (Map<String, String>) -> HttpResponse): HttpResponse {
        val response = request(authorization(auth.accessToken()))
        if (response.status != HttpURLConnection.HTTP_UNAUTHORIZED) return response
        return request(authorization(auth.accessToken(forceRefresh = true)))
    }

    private fun parse(pathOrUrl: String, response: HttpResponse): JSONObject? {
        return when (response.status) {
            HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED -> JSONObject(response.body)
            HttpURLConnection.HTTP_NO_CONTENT -> null
            else -> throw SpotifyApiException(
                response.status,
                "Spotify API $pathOrUrl failed (${response.status}): ${response.body}",
            )
        }
    }

    private fun authorization(accessToken: String) = mapOf("Authorization" to "Bearer $accessToken")

    private companion object {
        const val BASE_URL = "https://api.spotify.com/v1"
    }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/** Maps a track object, or a podcast episode object (with the show name in place of the artists). */
private fun JSONObject.toTrack(): Track {
    val artists = optJSONArray("artists")
        ?.objects()
        ?.joinToString(", ") { it.getString("name") }
        ?: optJSONObject("show")?.optString("name").orEmpty()
    return Track(
        uri = getString("uri"),
        title = getString("name"),
        artists = artists,
        durationMs = optLong("duration_ms"),
        isrc = optJSONObject("external_ids")?.optString("isrc")?.takeIf { it.isNotEmpty() },
    )
}

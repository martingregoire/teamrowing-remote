package de.gregoire.teamrowingremote.spotify

import de.gregoire.teamrowingremote.net.httpGet
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
)

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int,
) {
    val uri: String
        get() = PLAYLIST_URI_PREFIX + id
}

const val PLAYLIST_URI_PREFIX = "spotify:playlist:"

class SpotifyApiException(
    val status: Int,
    message: String,
) : IOException(message)

/** Read-only access to the Spotify Web API for the logged-in user; playback is controlled with [SpotifyRemote]. */
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
                )
            }
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
        var response = httpGet(url, authorization(auth.accessToken()))
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            response = httpGet(url, authorization(auth.accessToken(forceRefresh = true)))
        }
        return when (response.status) {
            HttpURLConnection.HTTP_OK -> JSONObject(response.body)
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

package de.gregoire.teamrowingremote.bpm

import android.util.Log
import de.gregoire.teamrowingremote.net.httpGet
import de.gregoire.teamrowingremote.spotify.Track
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

// Copied from teamrowing-display, which looks up the tracks of the sessions it plays; keep both the same.

data class DetectedBpm(val bpm: Double, val source: BpmSource)

class BpmLookupResult(
    /** Detected BPM by track URI. */
    val found: Map<String, DetectedBpm>,
    /** False if a service failed, so tracks missing from [found] may still be known. */
    val complete: Boolean,
)

/** Looks up BPM with ReccoBeats by Spotify ID, then with Deezer by ISRC for the tracks ReccoBeats doesn't know. */
class BpmLookup {

    suspend fun lookUp(tracks: List<Track>): BpmLookupResult {
        val found = mutableMapOf<String, DetectedBpm>()
        var complete = true

        try {
            found += lookUpReccoBeats(tracks)
        } catch (e: IOException) {
            Log.w(TAG, "ReccoBeats lookup failed", e)
            complete = false
        } catch (e: JSONException) {
            Log.w(TAG, "ReccoBeats lookup failed", e)
            complete = false
        }

        for (track in tracks) {
            val isrc = track.isrc ?: continue
            if (track.uri in found) continue
            try {
                lookUpDeezer(isrc)?.let { found[track.uri] = DetectedBpm(it, BpmSource.DEEZER) }
            } catch (e: IOException) {
                Log.w(TAG, "Deezer lookup failed for $isrc", e)
                complete = false
            } catch (e: JSONException) {
                Log.w(TAG, "Deezer lookup failed for $isrc", e)
                complete = false
            }
            // Deezer allows 50 requests per 5 seconds
            delay(DEEZER_REQUEST_INTERVAL_MS)
        }

        return BpmLookupResult(found, complete)
    }

    private suspend fun lookUpReccoBeats(tracks: List<Track>): Map<String, DetectedBpm> {
        val urisById = tracks.mapNotNull { track -> track.id?.let { it to track.uri } }.toMap()
        val found = mutableMapOf<String, DetectedBpm>()
        for (ids in urisById.keys.chunked(RECCOBEATS_MAX_IDS)) {
            val response = httpGet("$RECCOBEATS_URL/audio-features?ids=${ids.joinToString(",")}")
            if (response.status != 200) throw IOException("ReccoBeats failed (${response.status}): ${response.body}")

            // Unknown tracks are missing from the content array
            val content = JSONObject(response.body).getJSONArray("content")
            for (i in 0 until content.length()) {
                val features = content.getJSONObject(i)
                val id = features.getString("href").substringAfterLast("/")
                val uri = urisById[id] ?: continue
                val tempo = features.optDouble("tempo")
                if (tempo > 0) found[uri] = DetectedBpm(tempo, BpmSource.RECCOBEATS)
            }
        }
        return found
    }

    /** Returns the BPM, or null if Deezer doesn't know the track or has no BPM for it. */
    private suspend fun lookUpDeezer(isrc: String): Double? {
        val response = httpGet("$DEEZER_URL/track/isrc:$isrc")
        if (response.status != 200) throw IOException("Deezer failed (${response.status}): ${response.body}")

        // Deezer reports errors with status 200 and an error object
        val json = JSONObject(response.body)
        json.optJSONObject("error")?.let { error ->
            if (error.optInt("code") == DEEZER_ERROR_NO_DATA) return null
            throw IOException("Deezer failed: ${error.optString("message")}")
        }
        // Deezer uses 0 for unknown BPM
        return json.optDouble("bpm").takeIf { it > 0 }
    }

    private companion object {
        const val TAG = "BpmLookup"
        const val RECCOBEATS_URL = "https://api.reccobeats.com/v1"
        const val RECCOBEATS_MAX_IDS = 40
        const val DEEZER_URL = "https://api.deezer.com"
        const val DEEZER_ERROR_NO_DATA = 800
        const val DEEZER_REQUEST_INTERVAL_MS = 120L
    }
}

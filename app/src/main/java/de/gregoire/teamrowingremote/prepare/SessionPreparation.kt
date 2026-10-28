package de.gregoire.teamrowingremote.prepare

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import de.gregoire.teamrowingremote.bpm.BpmStore
import de.gregoire.teamrowingremote.spotify.Playlist
import de.gregoire.teamrowingremote.spotify.SpotifyApi
import de.gregoire.teamrowingremote.spotify.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONException
import java.io.IOException

/** A track of the fundus, the playlists sessions are put together from. */
data class FundusTrack(
    val track: Track,
    /** The names of the fundus playlists with this track. */
    val playlists: List<String>,
)

/**
 * The data for preparing sessions: the template they are checked against, the past sessions, and the
 * fundus playlists chosen by the trainer, with their tracks. Changes to the session itself are made by
 * the view model, which owns it.
 */
class SessionPreparation(
    context: Context,
    private val api: SpotifyApi,
    private val bpmStore: BpmStore,
    private val scope: CoroutineScope,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var template by mutableStateOf(loadTemplate())
        private set

    /** The IDs of the fundus playlists chosen by the trainer. */
    var fundusIds by mutableStateOf(prefs.getStringSet(KEY_FUNDUS_IDS, null).orEmpty())
        private set

    /** All sessions, or null while loading. */
    var history by mutableStateOf<SessionHistory?>(null)
        private set

    /** The tracks of the chosen fundus playlists, each once, or null while loading. */
    var fundusTracks by mutableStateOf<List<FundusTrack>?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Tracks by playlist ID, with the snapshot they were loaded at, so unchanged playlists aren't loaded again. */
    private val loadedTracks = mutableMapOf<String, Pair<String?, List<Track>>>()
    private var playlists: List<Playlist> = emptyList()
    private var loadJob: Job? = null

    /** Loads the sessions among the user's [playlists], and the chosen fundus playlists. */
    fun load(playlists: List<Playlist>) {
        this.playlists = playlists
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val sessions = playlists.mapNotNull { playlist -> sessionDate(playlist.name)?.let { playlist to it } }
                history = SessionHistory(
                    loadTracks(sessions.map { it.first }).zip(sessions) { tracks, (playlist, date) ->
                        PastSession(playlist, date, tracks.map { it.uri }.toSet())
                    },
                )
                loadFundus()
                error = null
            } catch (e: IOException) {
                error = e.message
            } catch (e: JSONException) {
                error = e.message
            }
        }
    }

    fun setFundus(ids: Set<String>) {
        fundusIds = ids
        prefs.edit { putStringSet(KEY_FUNDUS_IDS, ids) }
        fundusTracks = null
        load(playlists)
    }

    fun updateTemplate(template: SessionTemplate) {
        this.template = template
        prefs.edit {
            putInt(KEY_LENGTH, template.lengthMinutes)
            putInt(KEY_TOLERANCE, template.toleranceMinutes)
            putInt(KEY_WARM_UP, template.warmUpMinutes)
            putInt(KEY_COOL_DOWN, template.coolDownMinutes)
            putInt(KEY_EASY_FROM, template.easyFrom)
            putInt(KEY_HARD_FROM, template.hardFrom)
            putInt(KEY_MAX_STEP, template.maxStep)
        }
    }

    /** Looks up the BPM of tracks nobody has looked up yet, as the display app does for sessions it plays. */
    fun lookUpMissing(tracks: List<Track>) {
        scope.launch { bpmStore.lookUpMissing(tracks) }
    }

    private suspend fun loadFundus() {
        val fundus = playlists.filter { it.id in fundusIds }
        val tracks = loadTracks(fundus).zip(fundus) { tracks, playlist -> tracks.map { it to playlist.name } }.flatten()
        fundusTracks = tracks.groupBy({ it.first.uri }, { it })
            .map { (_, sameTrack) -> FundusTrack(sameTrack.first().first, sameTrack.map { it.second }.distinct()) }
        lookUpMissing(fundusTracks.orEmpty().map { it.track })
    }

    /** The tracks of each playlist, loading a few at a time. */
    private suspend fun loadTracks(playlists: List<Playlist>): List<List<Track>> = coroutineScope {
        val semaphore = Semaphore(PARALLEL_LOADS)
        playlists.map { playlist ->
            async {
                val loaded = loadedTracks[playlist.id]
                if (loaded != null && playlist.snapshotId != null && loaded.first == playlist.snapshotId) {
                    loaded.second
                } else {
                    semaphore.withPermit { api.getPlaylistTracks(playlist.id) }
                        .also { loadedTracks[playlist.id] = playlist.snapshotId to it }
                }
            }
        }.awaitAll()
    }

    private fun loadTemplate(): SessionTemplate {
        val defaults = SessionTemplate()
        return SessionTemplate(
            lengthMinutes = prefs.getInt(KEY_LENGTH, defaults.lengthMinutes),
            toleranceMinutes = prefs.getInt(KEY_TOLERANCE, defaults.toleranceMinutes),
            warmUpMinutes = prefs.getInt(KEY_WARM_UP, defaults.warmUpMinutes),
            coolDownMinutes = prefs.getInt(KEY_COOL_DOWN, defaults.coolDownMinutes),
            easyFrom = prefs.getInt(KEY_EASY_FROM, defaults.easyFrom),
            hardFrom = prefs.getInt(KEY_HARD_FROM, defaults.hardFrom),
            maxStep = prefs.getInt(KEY_MAX_STEP, defaults.maxStep),
        )
    }

    private companion object {
        const val PREFS_NAME = "prepare"
        const val KEY_FUNDUS_IDS = "fundus_ids"
        const val KEY_LENGTH = "length_minutes"
        const val KEY_TOLERANCE = "tolerance_minutes"
        const val KEY_WARM_UP = "warm_up_minutes"
        const val KEY_COOL_DOWN = "cool_down_minutes"
        const val KEY_EASY_FROM = "easy_from"
        const val KEY_HARD_FROM = "hard_from"
        const val KEY_MAX_STEP = "max_step"
        const val PARALLEL_LOADS = 4
    }
}

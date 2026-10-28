package de.gregoire.teamrowingremote

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.bpm.BpmStore
import de.gregoire.teamrowingremote.spotify.PLAYLIST_URI_PREFIX
import de.gregoire.teamrowingremote.spotify.Playlist
import de.gregoire.teamrowingremote.spotify.SpotifyApi
import de.gregoire.teamrowingremote.spotify.SpotifyAuth
import de.gregoire.teamrowingremote.spotify.SpotifyRemote
import de.gregoire.teamrowingremote.spotify.Track
import de.gregoire.teamrowingremote.sync.BluetoothSyncClient
import de.gregoire.teamrowingremote.sync.PlaybackSyncPeer
import de.gregoire.teamrowingremote.sync.PlaybackUpdate
import de.gregoire.teamrowingremote.sync.QueueUpdate
import de.gregoire.teamrowingremote.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class NowPlaying(
    val uri: String,
    val title: String,
    val artists: String,
    val durationMs: Long,
)

data class RemoteState(
    val loggedIn: Boolean = false,
    val loginError: String? = null,
    val connection: ConnectionState = ConnectionState.Connecting,
    val nowPlaying: NowPlaying? = null,
    val isPaused: Boolean = true,
    val positionMs: Long = 0,
    /** [SystemClock.elapsedRealtime] when [positionMs] was reported, to advance it while playing. */
    val positionUpdatedAt: Long = 0,
    /** What Spotify is playing, e.g. `spotify:playlist:…`. */
    val contextUri: String? = null,
    /** The user's playlists, or null while loading. */
    val playlists: List<Playlist>? = null,
    /** The selected training session. */
    val session: Playlist? = null,
    /** The session's tracks, or null while loading. */
    val sessionTracks: List<Track>? = null,
    val error: String? = null,
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = SpotifyAuth(application)
    private val api = SpotifyApi(auth)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bpmStore = BpmStore(application)

    /** The playback state for the display app, which shows track changes instantly with it. */
    private val playbackUpdates = MutableStateFlow<PlaybackUpdate?>(null)

    /** Spotify's queue for the display app, which may not have internet access to fetch it. */
    private val queueUpdates = MutableStateFlow<QueueUpdate?>(null)
    private val playbackPeer = object : PlaybackSyncPeer {
        override val outgoing: Flow<PlaybackUpdate> = playbackUpdates.filterNotNull()

        override val outgoingQueues: Flow<QueueUpdate> = queueUpdates.filterNotNull()

        override fun received(update: PlaybackUpdate) = Unit

        override fun receivedQueue(update: QueueUpdate) = Unit
    }
    private val syncClient = BluetoothSyncClient(application, bpmStore, playbackPeer)

    /** Tempo per track URI, synced from the display app. */
    val bpmEntries: StateFlow<Map<String, BpmEntry>> = bpmStore.entries
    val syncStatus: StateFlow<SyncStatus> = syncClient.status

    var state by mutableStateOf(RemoteState(loggedIn = auth.isLoggedIn, session = loadSession()))
        private set

    private var remote: SpotifyRemote? = null
    private var connectJob: Job? = null
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch { bpmStore.load() }
        if (state.loggedIn) loadPlaylistsAndSession()
    }

    /**
     * Syncs the tempos with the display app over Bluetooth until cancelled, e.g. while the app is visible,
     * and sends it the playback state and Spotify's queue.
     */
    suspend fun runSync() = coroutineScope {
        launch { pollQueue() }
        syncClient.run()
    }

    fun createAuthorizeUri(): Uri = auth.createAuthorizeUri()

    /** Completes the login and connects to the Spotify app, which the login has authorized. */
    fun handleRedirect(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                auth.handleRedirect(uri)
                state = state.copy(loggedIn = true, loginError = null)
                loadPlaylistsAndSession()
                connect(context)
            } catch (e: IOException) {
                state = state.copy(loginError = e.message)
            }
        }
    }

    /** Connects to the Spotify app and follows its player state; the context is only used while connecting. */
    fun connect(context: Context) {
        if (!state.loggedIn || remote?.isConnected == true || connectJob?.isActive == true) return
        state = state.copy(connection = ConnectionState.Connecting)
        connectJob = viewModelScope.launch {
            try {
                val connected = SpotifyRemote.connect(context, onConnectionLost = ::onConnectionLost)
                remote = connected
                state = state.copy(connection = ConnectionState.Connected)
                launch { connected.playerContextUris().collect(::onContextChanged) }
                connected.playerStates().collect { playerState ->
                    val track = playerState.track
                    state = state.copy(
                        nowPlaying = track?.let {
                            NowPlaying(
                                uri = it.uri,
                                title = it.name,
                                artists = it.artists.joinToString(", ") { artist -> artist.name },
                                durationMs = it.duration,
                            )
                        },
                        isPaused = playerState.isPaused,
                        positionMs = playerState.playbackPosition,
                        positionUpdatedAt = SystemClock.elapsedRealtime(),
                    )
                    publishPlayback()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onConnectionLost(e)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        remote?.disconnect()
        remote = null
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            try {
                // Fetch first: state.copy(playlists = api.getPlaylists()) would copy the state from before
                // the request and undo changes made meanwhile, such as the loaded session tracks
                val playlists = api.getPlaylists()
                state = state.copy(playlists = playlists, error = null)
                // A playlist may have been started in Spotify before the playlists were loaded
                state.contextUri?.let(::onContextChanged)
            } catch (e: IOException) {
                state = state.copy(error = e.message)
            } catch (e: JSONException) {
                state = state.copy(error = e.message)
            }
        }
    }

    /** Selects a playlist as the training session and loads its tracks; playback starts with play or a track. */
    fun selectSession(playlist: Playlist) {
        if (playlist.id == state.session?.id && state.sessionTracks != null) return
        prefs.edit {
            putString(KEY_SESSION_ID, playlist.id)
            putString(KEY_SESSION_NAME, playlist.name)
        }
        state = state.copy(session = playlist, sessionTracks = null)
        loadSessionTracks(playlist)
    }

    /** Plays the session from the track at [index], in playlist order. */
    fun playSessionTrack(index: Int) {
        val remote = remote ?: return
        val session = state.session ?: return
        // The order of a training session matters
        remote.setShuffle(false)
        remote.skipToIndex(session.uri, index)
    }

    /** Resumes, or starts the session from its beginning if something else was playing. */
    fun togglePlayPause() {
        val remote = remote ?: return
        when {
            !state.isPaused -> remote.pause()
            state.session != null && state.contextUri != state.session?.uri -> playSessionTrack(0)
            else -> remote.resume()
        }
    }

    /** Saves the trainer's tempo correction, which is synced to the display app. */
    fun saveTempo(uri: String, manualBpm: Double?, hidden: Boolean) {
        viewModelScope.launch { bpmStore.saveEdit(uri, manualBpm, hidden) }
    }

    fun seekTo(positionMs: Long) {
        remote?.seekTo(positionMs)
    }

    fun skipNext() {
        remote?.skipNext()
    }

    fun skipPrevious() {
        remote?.skipPrevious()
    }

    private fun loadPlaylistsAndSession() {
        loadPlaylists()
        state.session?.let(::loadSessionTracks)
    }

    private fun loadSessionTracks(playlist: Playlist) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            try {
                val tracks = api.getPlaylistTracks(playlist.id)
                state = state.copy(sessionTracks = tracks, error = null)
            } catch (e: IOException) {
                state = state.copy(error = e.message)
            } catch (e: JSONException) {
                state = state.copy(error = e.message)
            }
        }
    }

    private fun publishPlayback() {
        val nowPlaying = state.nowPlaying
        playbackUpdates.value = PlaybackUpdate(
            trackUri = nowPlaying?.uri,
            title = nowPlaying?.title.orEmpty(),
            artists = nowPlaying?.artists.orEmpty(),
            durationMs = nowPlaying?.durationMs ?: 0,
            positionMs = state.positionMs,
            isPaused = state.isPaused,
            contextUri = state.contextUri,
            positionAt = state.positionUpdatedAt,
        )
    }

    /**
     * Fetches Spotify's queue while the display app is connected: when the track, what is being played or
     * the session's tracks change, and every [QUEUE_REFRESH_MS] to catch manual changes.
     */
    private suspend fun pollQueue() {
        val playing = snapshotFlow { Triple(state.nowPlaying?.uri, state.contextUri, state.sessionTracks) }
        combine(syncClient.status, playing) { status, current -> current.takeIf { status == SyncStatus.CONNECTED } }
            .distinctUntilChanged()
            .collectLatest { playing ->
                val trackUri = playing?.first ?: return@collectLatest
                var staleAttempts = 0
                while (true) {
                    val delayMs = try {
                        val queue = api.getQueue()
                        // Right after a track change, Spotify may still report the queue of the previous track
                        if (queue.currentlyPlaying?.uri == trackUri || ++staleAttempts > MAX_STALE_QUEUE_ATTEMPTS) {
                            queueUpdates.value = QueueUpdate(trackUri, withoutAutoplay(queue.upcoming))
                            staleAttempts = 0
                            QUEUE_REFRESH_MS
                        } else {
                            STALE_QUEUE_RETRY_MS
                        }
                    } catch (e: IOException) {
                        QUEUE_RETRY_MS
                    } catch (e: JSONException) {
                        QUEUE_RETRY_MS
                    }
                    delay(delayMs)
                }
            }
    }

    /**
     * The upcoming tracks up to the end of the session playlist, without Spotify's autoplay tracks after it.
     * Autoplay may report no or a different context, so only playing something else ends the session.
     */
    private fun withoutAutoplay(upcoming: List<Track>): List<Track> {
        val session = state.session ?: return upcoming
        val sessionTrackUris = state.sessionTracks?.map { it.uri }?.toSet() ?: return upcoming
        val contextUri = state.contextUri
        val playsOther = contextUri != null && contextUri != session.uri &&
            (OTHER_CONTEXT_PREFIXES.any { contextUri.startsWith(it) } || contextUri.endsWith(":collection"))
        return if (playsOther) upcoming else upcoming.takeWhile { it.uri in sessionTrackUris }
    }

    /** Follows a playlist started in the Spotify app, if it's one of the user's playlists. */
    private fun onContextChanged(contextUri: String) {
        state = state.copy(contextUri = contextUri.takeIf { it.isNotEmpty() })
        publishPlayback()
        if (!contextUri.startsWith(PLAYLIST_URI_PREFIX) || contextUri == state.session?.uri) return
        state.playlists?.firstOrNull { it.uri == contextUri }?.let(::selectSession)
    }

    private fun onConnectionLost(error: Throwable) {
        remote = null
        // The SDK's exception types (e.g. CouldNotFindSpotifyApp) are more telling than their messages
        val message = listOfNotNull(error.javaClass.simpleName, error.message).joinToString(": ")
        state = state.copy(connection = ConnectionState.Failed(message))
    }

    private fun loadSession(): Playlist? {
        val id = prefs.getString(KEY_SESSION_ID, null) ?: return null
        return Playlist(id, prefs.getString(KEY_SESSION_NAME, null).orEmpty(), trackCount = 0)
    }

    private companion object {
        const val PREFS_NAME = "remote"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_SESSION_NAME = "session_name"
        const val QUEUE_REFRESH_MS = 15_000L
        const val STALE_QUEUE_RETRY_MS = 500L
        const val MAX_STALE_QUEUE_ATTEMPTS = 6
        const val QUEUE_RETRY_MS = 5_000L
        val OTHER_CONTEXT_PREFIXES = listOf(PLAYLIST_URI_PREFIX, "spotify:album:", "spotify:artist:", "spotify:show:")
    }
}

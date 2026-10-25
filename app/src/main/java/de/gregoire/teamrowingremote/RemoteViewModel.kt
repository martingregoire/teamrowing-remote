package de.gregoire.teamrowingremote

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gregoire.teamrowingremote.spotify.PLAYLIST_URI_PREFIX
import de.gregoire.teamrowingremote.spotify.Playlist
import de.gregoire.teamrowingremote.spotify.SpotifyApi
import de.gregoire.teamrowingremote.spotify.SpotifyAuth
import de.gregoire.teamrowingremote.spotify.SpotifyRemote
import de.gregoire.teamrowingremote.spotify.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    var state by mutableStateOf(RemoteState(loggedIn = auth.isLoggedIn, session = loadSession()))
        private set

    private var remote: SpotifyRemote? = null
    private var connectJob: Job? = null
    private var sessionJob: Job? = null

    init {
        if (state.loggedIn) loadPlaylistsAndSession()
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
                state = state.copy(playlists = api.getPlaylists(), error = null)
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
                state = state.copy(sessionTracks = api.getPlaylistTracks(playlist.id), error = null)
            } catch (e: IOException) {
                state = state.copy(error = e.message)
            } catch (e: JSONException) {
                state = state.copy(error = e.message)
            }
        }
    }

    /** Follows a playlist started in the Spotify app, if it's one of the user's playlists. */
    private fun onContextChanged(contextUri: String) {
        state = state.copy(contextUri = contextUri.takeIf { it.isNotEmpty() })
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
    }
}

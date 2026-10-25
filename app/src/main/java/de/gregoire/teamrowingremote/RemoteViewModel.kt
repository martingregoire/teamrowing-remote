package de.gregoire.teamrowingremote

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gregoire.teamrowingremote.spotify.SpotifyAuth
import de.gregoire.teamrowingremote.spotify.SpotifyRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = SpotifyAuth(application)

    var state by mutableStateOf(RemoteState(loggedIn = auth.isLoggedIn))
        private set

    private var remote: SpotifyRemote? = null
    private var connectJob: Job? = null

    fun createAuthorizeUri(): Uri = auth.createAuthorizeUri()

    /** Completes the login and connects to the Spotify app, which the login has authorized. */
    fun handleRedirect(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                auth.handleRedirect(uri)
                state = state.copy(loggedIn = true, loginError = null)
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

    fun togglePlayPause() {
        val remote = remote ?: return
        if (state.isPaused) remote.resume() else remote.pause()
    }

    fun skipNext() {
        remote?.skipNext()
    }

    fun skipPrevious() {
        remote?.skipPrevious()
    }

    private fun onConnectionLost(error: Throwable) {
        remote = null
        // The SDK's exception types (e.g. CouldNotFindSpotifyApp) are more telling than their messages
        val message = listOfNotNull(error.javaClass.simpleName, error.message).joinToString(": ")
        state = state.copy(connection = ConnectionState.Failed(message))
    }
}

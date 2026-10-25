package de.gregoire.teamrowingremote.spotify

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import de.gregoire.teamrowingremote.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connection to the Spotify app on this phone via the App Remote SDK.
 * On the first connection, the Spotify app asks the user to allow access.
 */
class SpotifyRemote private constructor(private val appRemote: SpotifyAppRemote) {

    val isConnected: Boolean
        get() = appRemote.isConnected

    /** Player state updates, pushed by the Spotify app on every change. */
    fun playerStates(): Flow<PlayerState> = callbackFlow {
        val subscription = appRemote.playerApi.subscribeToPlayerState()
            .setEventCallback { trySend(it) }
            .setErrorCallback { close(it) }
        awaitClose { subscription.cancel() }
    }

    /** What is being played, e.g. `spotify:playlist:…`, pushed by the Spotify app on every change. */
    fun playerContextUris(): Flow<String> = callbackFlow {
        val subscription = appRemote.playerApi.subscribeToPlayerContext()
            .setEventCallback { trySend(it.uri) }
            .setErrorCallback { close(it) }
        awaitClose { subscription.cancel() }
    }

    /** Plays the playlist or album [uri] from the track at [index]. */
    fun skipToIndex(uri: String, index: Int) {
        appRemote.playerApi.skipToIndex(uri, index)
    }

    fun setShuffle(enabled: Boolean) {
        appRemote.playerApi.setShuffle(enabled)
    }

    fun pause() {
        appRemote.playerApi.pause()
    }

    fun resume() {
        appRemote.playerApi.resume()
    }

    fun seekTo(positionMs: Long) {
        appRemote.playerApi.seekTo(positionMs)
    }

    fun skipNext() {
        appRemote.playerApi.skipNext()
    }

    fun skipPrevious() {
        appRemote.playerApi.skipPrevious()
    }

    fun disconnect() {
        SpotifyAppRemote.disconnect(appRemote)
    }

    companion object {
        /**
         * Connects to the Spotify app; throws the SDK's exception (e.g. Spotify not installed) on failure.
         * [onConnectionLost] is called if an established connection breaks later.
         */
        suspend fun connect(
            context: Context,
            onConnectionLost: (Throwable) -> Unit,
        ): SpotifyRemote = suspendCancellableCoroutine { continuation ->
            val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
                .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
                .showAuthView(true)
                .build()
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    if (continuation.isActive) continuation.resume(SpotifyRemote(appRemote))
                    else SpotifyAppRemote.disconnect(appRemote)
                }

                override fun onFailure(error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                    else onConnectionLost(error)
                }
            })
        }
    }
}

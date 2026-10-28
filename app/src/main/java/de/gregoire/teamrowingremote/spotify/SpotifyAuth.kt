package de.gregoire.teamrowingremote.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import de.gregoire.teamrowingremote.BuildConfig
import de.gregoire.teamrowingremote.net.TIMEOUT_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class SpotifyAuthException(message: String) : IOException(message)

/**
 * Spotify login using the Authorization Code flow with PKCE, so no client secret is needed.
 * Tokens are stored in app-private shared preferences and refreshed automatically.
 */
class SpotifyAuth(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val refreshMutex = Mutex()

    /** True if logged in with all scopes the app needs; a login from an older app version may lack some. */
    val isLoggedIn: Boolean
        get() {
            if (prefs.getString(KEY_REFRESH_TOKEN, null) == null) return false
            val granted = prefs.getString(KEY_SCOPE, null).orEmpty().split(" ").toSet()
            return granted.containsAll(SCOPES.split(" "))
        }

    /** Builds the authorize URL and remembers the PKCE verifier and state until the redirect arrives. */
    fun createAuthorizeUri(): Uri {
        val verifier = randomUrlSafeString(48)
        val state = randomUrlSafeString(16)
        prefs.edit(commit = true) {
            putString(KEY_CODE_VERIFIER, verifier)
            putString(KEY_STATE, state)
        }
        return AUTHORIZE_URL.toUri().buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build()
    }

    /** Exchanges the authorization code from the redirect URI for access and refresh tokens. */
    suspend fun handleRedirect(uri: Uri) {
        uri.getQueryParameter("error")?.let { throw SpotifyAuthException("Login failed: $it") }
        val code = uri.getQueryParameter("code")
            ?: throw SpotifyAuthException("Login failed: no authorization code")
        if (uri.getQueryParameter("state") != prefs.getString(KEY_STATE, null)) {
            throw SpotifyAuthException("Login failed: state mismatch")
        }
        val verifier = prefs.getString(KEY_CODE_VERIFIER, null)
            ?: throw SpotifyAuthException("Login failed: no pending login")

        requestToken(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to BuildConfig.SPOTIFY_REDIRECT_URI,
                "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                "code_verifier" to verifier,
            )
        )
        prefs.edit {
            remove(KEY_CODE_VERIFIER)
            remove(KEY_STATE)
        }
    }

    /** Returns a valid access token, refreshing it shortly before it expires or when forced. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        if (!forceRefresh && token != null && System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MS) {
            return token
        }
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw SpotifyAuthException("Not logged in")
        requestToken(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
            )
        )
        prefs.getString(KEY_ACCESS_TOKEN, null) ?: throw SpotifyAuthException("Token refresh failed")
    }

    fun logout() {
        prefs.edit { clear() }
    }

    private suspend fun requestToken(params: Map<String, String>) = withContext(Dispatchers.IO) {
        val body = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // A revoked or expired refresh token can't be recovered, the user has to log in again.
                if (params["grant_type"] == "refresh_token" && "invalid_grant" in error) logout()
                throw SpotifyAuthException("Token request failed ($status): $error")
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            prefs.edit {
                putString(KEY_ACCESS_TOKEN, json.getString("access_token"))
                putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + json.getLong("expires_in") * 1000)
                // Spotify may rotate the refresh token; keep the old one if none is returned.
                json.optString("refresh_token").takeIf { it.isNotEmpty() }
                    ?.let { putString(KEY_REFRESH_TOKEN, it) }
                json.optString("scope").takeIf { it.isNotEmpty() }
                    ?.let { putString(KEY_SCOPE, it) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    private fun codeChallenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private companion object {
        // Must match the excludes in res/xml/backup_rules.xml and data_extraction_rules.xml
        const val PREFS_NAME = "spotify_auth"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_SCOPE = "scope"
        const val KEY_CODE_VERIFIER = "code_verifier"
        const val KEY_STATE = "state"

        const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        // app-remote-control grants the App Remote SDK access up front: Android 15 blocks the SDK's own
        // permission dialog, which the Spotify app tries to open from the background
        const val SCOPES = "app-remote-control user-read-playback-state user-modify-playback-state " +
            "playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public"
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

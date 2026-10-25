package de.gregoire.teamrowingremote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color.Black
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFB3B3B3)
private val Accent = Color(0xFFFFC107)
private val ErrorColor = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {

    private val viewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on first creation, so a configuration change doesn't replay the login redirect
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val state = viewModel.state
            if (!state.loggedIn) {
                LoginScreen(error = state.loginError, onLogin = ::startLogin)
                return@setContent
            }
            RemoteScreen(
                state = state,
                onRetry = { viewModel.connect(this) },
                onPrevious = viewModel::skipPrevious,
                onPlayPause = viewModel::togglePlayPause,
                onNext = viewModel::skipNext,
            )
        }
    }

    // The SDK recommends connecting only while the app is visible
    override fun onStart() {
        super.onStart()
        viewModel.connect(this)
    }

    override fun onStop() {
        super.onStop()
        viewModel.disconnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.toString().startsWith(BuildConfig.SPOTIFY_REDIRECT_URI)) {
            viewModel.handleRedirect(uri, this)
        }
    }

    private fun startLogin() {
        CustomTabsIntent.Builder().build().launchUrl(this, viewModel.createAuthorizeUri())
    }
}

@Composable
fun LoginScreen(error: String?, onLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.login_hint), color = PrimaryText, fontSize = 20.sp)
        Button(onClick = onLogin, modifier = Modifier.height(64.dp)) {
            Text(stringResource(R.string.login_button), fontSize = 18.sp)
        }
        error?.let { Text(it, color = ErrorColor) }
    }
}

/** First version: connection state, the current track and basic controls, to try the App Remote SDK. */
@Composable
fun RemoteScreen(
    state: RemoteState,
    onRetry: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val connection = state.connection) {
            ConnectionState.Connecting -> Text(stringResource(R.string.connecting), color = SecondaryText)
            ConnectionState.Connected -> Text(stringResource(R.string.connected), color = SecondaryText)
            is ConnectionState.Failed -> {
                Text(stringResource(R.string.connection_failed, connection.message), color = ErrorColor)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }

        val nowPlaying = state.nowPlaying
        if (nowPlaying != null) {
            Text(nowPlaying.title, color = PrimaryText, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            Text(nowPlaying.artists, color = SecondaryText, fontSize = 24.sp)
            if (state.isPaused) Text(stringResource(R.string.paused).uppercase(), color = Accent, fontSize = 20.sp)
        } else {
            Text(stringResource(R.string.nothing_playing), color = SecondaryText, fontSize = 24.sp)
        }

        val enabled = state.connection == ConnectionState.Connected
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(stringResource(R.string.previous), enabled, onPrevious, Modifier.weight(1f))
            ControlButton(
                stringResource(if (state.isPaused) R.string.play else R.string.pause),
                enabled,
                onPlayPause,
                Modifier.weight(1f),
            )
            ControlButton(stringResource(R.string.next), enabled, onNext, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ControlButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(72.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryText),
    ) {
        Text(label, fontSize = 18.sp)
    }
}

@Preview(widthDp = 400, heightDp = 800)
@Composable
fun RemoteScreenPreview() {
    RemoteScreen(
        state = RemoteState(
            connection = ConnectionState.Connected,
            nowPlaying = NowPlaying("spotify:track:1", "Black Velvet", "Alannah Myles", 287_000),
            isPaused = false,
        ),
        onRetry = {},
        onPrevious = {},
        onPlayPause = {},
        onNext = {},
    )
}

package de.gregoire.teamrowingremote

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.bpm.formatBpm
import de.gregoire.teamrowingremote.spotify.Playlist
import de.gregoire.teamrowingremote.spotify.Track
import de.gregoire.teamrowingremote.sync.SyncStatus
import de.gregoire.teamrowingremote.sync.hasBluetoothPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Background = Color.Black
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFB3B3B3)
private val Accent = Color(0xFFFFC107)
private val Highlight = Color(0xFF2A2A2A)
private val ErrorColor = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {

    private val viewModel: RemoteViewModel by viewModels()

    // The sync client checks the permission itself and starts once it's granted
    private val requestBluetoothPermission = registerForActivityResult(RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system bar icons on the black app, whatever the device's theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // The trainer keeps the remote open during the whole training session
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Only on first creation, so a configuration change doesn't replay the login redirect
        if (savedInstanceState == null) handleIntent(intent)
        startBluetoothSync(askForPermission = savedInstanceState == null)
        setContent {
            val state = viewModel.state
            if (!state.loggedIn) {
                LoginScreen(error = state.loginError, onLogin = ::startLogin)
                return@setContent
            }
            var choosingPlaylist by rememberSaveable { mutableStateOf(false) }
            if (choosingPlaylist || state.session == null) {
                BackHandler(enabled = state.session != null) { choosingPlaylist = false }
                LaunchedEffect(Unit) { viewModel.loadPlaylists() }
                PlaylistsScreen(
                    playlists = state.playlists,
                    error = state.error,
                    onSelect = {
                        viewModel.selectSession(it)
                        choosingPlaylist = false
                    },
                    onBack = if (state.session != null) ({ choosingPlaylist = false }) else null,
                )
            } else {
                val bpmEntries by viewModel.bpmEntries.collectAsStateWithLifecycle()
                val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
                RemoteScreen(
                    state = state,
                    bpmEntries = bpmEntries,
                    syncStatus = syncStatus,
                    onRetry = { viewModel.connect(this) },
                    onPrevious = viewModel::skipPrevious,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::skipNext,
                    onPlayTrack = viewModel::playSessionTrack,
                    onSeek = viewModel::seekTo,
                    onChangeSession = { choosingPlaylist = true },
                    onSaveTempo = viewModel::saveTempo,
                )
            }
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

    /** Syncs the tempos with the display app over Bluetooth while the app is visible. */
    private fun startBluetoothSync(askForPermission: Boolean) {
        if (askForPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission(this)) {
            requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.runSync() }
        }
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

/** The current track and controls, followed by the session's tracks; tapping a track plays it. */
@Composable
fun RemoteScreen(
    state: RemoteState,
    bpmEntries: Map<String, BpmEntry>,
    syncStatus: SyncStatus,
    onRetry: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onChangeSession: () -> Unit,
    onSaveTempo: (uri: String, manualBpm: Double?, hidden: Boolean) -> Unit,
) {
    var editing by remember { mutableStateOf<Track?>(null) }
    editing?.let { track ->
        TempoEditorSheet(
            track = track,
            entry = bpmEntries[track.uri],
            onSave = { manualBpm, hidden ->
                onSaveTempo(track.uri, manualBpm, hidden)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val connection = state.connection) {
            ConnectionState.Connecting -> Text(stringResource(R.string.connecting), color = SecondaryText)
            ConnectionState.Connected -> Unit
            is ConnectionState.Failed -> {
                Text(stringResource(R.string.connection_failed, connection.message), color = ErrorColor)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }

        val nowPlaying = state.nowPlaying
        if (nowPlaying != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        nowPlaying.title,
                        color = PrimaryText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(nowPlaying.artists, color = SecondaryText, fontSize = 22.sp, maxLines = 1)
                }
                // Tapping the tempo edits it, also for "No tempo" tracks, where it's empty
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
                        .clickable { editing = Track(nowPlaying.uri, nowPlaying.title, nowPlaying.artists, nowPlaying.durationMs) },
                    contentAlignment = Alignment.Center,
                ) {
                    tempoText(bpmEntries[nowPlaying.uri])?.let {
                        Text(it, color = Accent, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Text(stringResource(R.string.nothing_playing), color = SecondaryText, fontSize = 24.sp)
        }

        val enabled = state.connection == ConnectionState.Connected
        if (nowPlaying != null) PlaybackSlider(state, nowPlaying, enabled, onSeek)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ControlButton(MediaIcons.SkipPrevious, stringResource(R.string.previous), enabled, onPrevious, Modifier.weight(1f))
            ControlButton(
                if (state.isPaused) Icons.Filled.PlayArrow else MediaIcons.Pause,
                stringResource(if (state.isPaused) R.string.play else R.string.pause),
                enabled,
                onPlayPause,
                Modifier.weight(1f),
            )
            ControlButton(MediaIcons.SkipNext, stringResource(R.string.next), enabled, onNext, Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(
                state.session?.name.orEmpty(),
                color = PrimaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChangeSession) {
                Text(stringResource(R.string.change_session), color = Accent, fontSize = 18.sp)
            }
        }
        Text(stringResource(syncStatus.label), color = SecondaryText, fontSize = 14.sp)
        state.error?.let { Text(it, color = ErrorColor) }

        val tracks = state.sessionTracks
        if (tracks == null) {
            if (state.error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Highlight)
        } else {
            val currentIndex = if (state.contextUri == state.session?.uri) {
                tracks.indexOfFirst { it.uri == state.nowPlaying?.uri }
            } else {
                -1
            }
            val listState = rememberLazyListState()
            // Keeps the current track second in the list, after the previous one, so most of the list shows
            // the following tracks: when it changes, and a while after the list was scrolled by hand
            LaunchedEffect(currentIndex, tracks) {
                if (currentIndex >= 0) listState.scrollToCurrent(currentIndex)
            }
            // Only dragging counts: the automatic scroll is a scroll too, and must not restart this effect,
            // which would cancel the scroll itself
            val isDragged by listState.interactionSource.collectIsDraggedAsState()
            val latestIndex by rememberUpdatedState(currentIndex)
            LaunchedEffect(isDragged) {
                if (!isDragged) {
                    delay(RECENTER_DELAY_MS)
                    if (latestIndex >= 0) listState.scrollToCurrent(latestIndex)
                }
            }
            LazyColumn(state = listState) {
                itemsIndexed(tracks) { index, track ->
                    TrackRow(
                        number = index + 1,
                        track = track,
                        tempo = tempoText(bpmEntries[track.uri]),
                        isCurrent = index == currentIndex,
                        onClick = { onPlayTrack(index) },
                        onTempoClick = { editing = track },
                    )
                }
            }
        }
    }
}

/**
 * Position of the current track; dragging seeks. The position advances locally between the
 * Spotify app's state updates, which only arrive on changes.
 */
// The customized slider track is still experimental in Material 3
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSlider(state: RemoteState, nowPlaying: NowPlaying, enabled: Boolean, onSeek: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.positionUpdatedAt, state.isPaused) {
        while (!state.isPaused) {
            now = SystemClock.elapsedRealtime()
            delay(PROGRESS_TICK_MS)
        }
    }
    val elapsed = if (state.isPaused) 0 else (now - state.positionUpdatedAt).coerceAtLeast(0)
    val position = (state.positionMs + elapsed).coerceAtMost(nowPlaying.durationMs)

    // Holds the dragged position, also after seeking until the Spotify app reports the new one
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.positionUpdatedAt) { seekPosition = null }
    val shown = seekPosition ?: position.toFloat()

    val colors = SliderDefaults.colors(
        thumbColor = Accent,
        activeTrackColor = Accent,
        inactiveTrackColor = Highlight,
    )
    val interactionSource = remember { MutableInteractionSource() }
    Column {
        // A thinner track and thumb than the defaults; the touch area keeps its size
        Slider(
            value = shown,
            onValueChange = { seekPosition = it },
            onValueChangeFinished = { seekPosition?.let { onSeek(it.toLong()) } },
            valueRange = 0f..nowPlaying.durationMs.coerceAtLeast(1).toFloat(),
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    enabled = enabled,
                    thumbSize = DpSize(4.dp, 24.dp),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(8.dp),
                    colors = colors,
                    enabled = enabled,
                    drawStopIndicator = null,
                )
            },
        )
        Row {
            Text(formatDuration(shown.toLong()), color = PrimaryText, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(nowPlaying.durationMs), color = PrimaryText, fontSize = 16.sp)
        }
    }
}

/** Scrolls so that the item before [index] is the first visible one, as far as the end of the list allows. */
private suspend fun LazyListState.scrollToCurrent(index: Int) {
    animateScrollToItem((index - 1).coerceAtLeast(0))
}

@Composable
private fun TrackRow(
    number: Int,
    track: Track,
    tempo: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onTempoClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) Highlight else Background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$number",
            color = if (isCurrent) Accent else SecondaryText,
            fontSize = 18.sp,
            modifier = Modifier.width(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (isCurrent) Accent else PrimaryText,
                fontSize = 20.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(track.artists, color = SecondaryText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // A separate touch target: tapping the row plays the track, tapping the tempo edits it
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .clickable(onClick = onTempoClick),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                tempo.orEmpty(),
                color = if (isCurrent) Accent else PrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            formatDuration(track.durationMs),
            color = SecondaryText,
            fontSize = 16.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

/** The tempo to show for a track: none for "No tempo" tracks, "–" if unknown. */
private fun tempoText(entry: BpmEntry?): String? = when {
    entry?.hidden == true -> null
    else -> entry?.bpm?.let(::formatBpm) ?: "–"
}

private val SyncStatus.label: Int
    get() = when (this) {
        SyncStatus.NO_PERMISSION -> R.string.sync_no_permission
        SyncStatus.BLUETOOTH_OFF -> R.string.sync_bluetooth_off
        SyncStatus.SEARCHING -> R.string.sync_searching
        SyncStatus.CONNECTED -> R.string.sync_connected
    }

/** The user's playlists to choose the training session from. */
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>?,
    error: String?,
    onSelect: (Playlist) -> Unit,
    onBack: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back), color = Accent, fontSize = 18.sp) }
            }
            Text(stringResource(R.string.choose_session), color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        error?.let { Text(it, color = ErrorColor) }
        when {
            playlists == null -> if (error == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Highlight)
            }
            playlists.isEmpty() -> Text(stringResource(R.string.no_playlists), color = SecondaryText, fontSize = 20.sp)
            else -> LazyColumn {
                items(playlists, key = { it.id }) { playlist ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(playlist) }
                            .padding(vertical = 14.dp)
                    ) {
                        Text(playlist.name, color = PrimaryText, fontSize = 22.sp)
                        Text(
                            pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount),
                            color = SecondaryText,
                            fontSize = 16.sp,
                        )
                    }
                    HorizontalDivider(color = Highlight)
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryText),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(36.dp))
    }
}

private const val PROGRESS_TICK_MS = 250L
private const val RECENTER_DELAY_MS = 8_000L

/** Formats milliseconds as m:ss. */
private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Preview(widthDp = 400, heightDp = 800)
@Composable
fun RemoteScreenPreview() {
    val tracks = listOf(
        Track("spotify:track:1", "Black Velvet", "Alannah Myles", 287_000),
        Track("spotify:track:2", "Summer in the City", "Joe Cocker", 285_000),
        Track("spotify:track:3", "Most Girls", "P!nk", 299_000),
    )
    RemoteScreen(
        state = RemoteState(
            loggedIn = true,
            connection = ConnectionState.Connected,
            nowPlaying = NowPlaying("spotify:track:1", "Black Velvet", "Alannah Myles", 287_000),
            isPaused = false,
            contextUri = "spotify:playlist:1",
            session = Playlist("1", "TR 28.10.2026", tracks.size),
            sessionTracks = tracks,
        ),
        bpmEntries = mapOf(
            "spotify:track:1" to BpmEntry(91.1, manualBpm = 23.0),
            "spotify:track:2" to BpmEntry(88.5, manualBpm = 22.0),
            "spotify:track:3" to BpmEntry(hidden = true),
        ),
        syncStatus = SyncStatus.CONNECTED,
        onRetry = {},
        onPrevious = {},
        onPlayPause = {},
        onNext = {},
        onPlayTrack = {},
        onSeek = {},
        onChangeSession = {},
        onSaveTempo = { _, _, _ -> },
    )
}

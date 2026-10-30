package de.gregoire.teamrowingremote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.bpm.formatBpm
import de.gregoire.teamrowingremote.prepare.RowingTempo
import de.gregoire.teamrowingremote.prepare.TapTempo
import de.gregoire.teamrowingremote.prepare.rowingTempoChoices
import de.gregoire.teamrowingremote.prepare.rowingTempo
import de.gregoire.teamrowingremote.prepare.suggestedHalvings
import de.gregoire.teamrowingremote.prepare.suggestedTempo
import de.gregoire.teamrowingremote.spotify.Track
import kotlin.math.roundToLong

private val SheetBackground = Color(0xFF1A1A1A)
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFB3B3B3)
private val Accent = Color(0xFFFFC107)
private val Highlight = Color(0xFF2A2A2A)

/** Playing the edited track in the sheet, to listen to it and tap its tempo. */
class TrackPreview(
    val state: RemoteState,
    /** Plays the track from its middle. */
    val onPlay: (Track) -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onSeek: (Long) -> Unit,
)

/**
 * Bottom sheet to correct a track's tempo: a number, ±1, "No tempo" and reset to the detected
 * value. Changes are only saved with the save button or the keyboard's done key. A track not prepared
 * yet starts with a rowing tempo suggested from the song's BPM. Tapping along with the song measures
 * its tempo. With a [preview], the track can be played in the sheet, from its middle, and [autoPlay]
 * starts it right away; playback started here is paused when the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet is still experimental in Material 3
@Composable
fun TempoEditorSheet(
    track: Track,
    entry: BpmEntry?,
    onSave: (manualBpm: Double?, hidden: Boolean) -> Unit,
    onDismiss: () -> Unit,
    preview: TrackPreview? = null,
    autoPlay: Boolean = false,
) {
    var text by remember {
        val tempo = rowingTempo(entry)
        val initial = if (tempo is RowingTempo.NotPrepared) tempo.detectedBpm?.let { suggestedTempo(it).roundToLong().toDouble() } else entry?.bpm
        mutableStateOf(initial?.let(::formatBpm).orEmpty())
    }
    var hidden by remember { mutableStateOf(entry?.hidden == true) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed > 0)
    val save = { if (valid) onSave(parsed, hidden) }

    var taps by remember { mutableStateOf(TapTempo()) }
    // How often the tapped tempo is halved; null for the one closest to a usual rowing tempo
    var halvings by remember { mutableStateOf<Int?>(null) }

    /** Enters the rowing tempo the tapped tempo stands for. */
    fun enterTapped() {
        val bpm = taps.bpm ?: return
        text = formatBpm((bpm / (1 shl (halvings ?: suggestedHalvings(bpm)))).roundToLong().toDouble())
    }

    /** Rounds the entered value, or the detected one if none is entered, and adds [delta]. */
    fun step(delta: Int) {
        val current = parsed ?: entry?.detectedBpm ?: return
        text = formatBpm((current.roundToLong() + delta).coerceAtLeast(1).toDouble())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBackground,
        // The sheet derives its system bar style from this: a light content color gives light bar icons
        contentColor = PrimaryText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    track.title,
                    color = PrimaryText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(track.artists, color = SecondaryText, fontSize = 18.sp, maxLines = 1)
            }

            preview?.let { PreviewPlayer(track, it, autoPlay) }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TapButton(taps, enabled = !hidden, modifier = Modifier.weight(1f)) {
                    taps = taps.tap(it)
                    enterTapped()
                }
                // Starts a new measurement right away, without waiting for the pause that does it, too
                OutlinedButton(
                    onClick = { taps = TapTempo() },
                    modifier = Modifier.size(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryText),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.tap_reset))
                }
            }
            // Always shown, with the chips' height, so the content doesn't move when the first taps fill it
            Row(
                modifier = Modifier.heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tap_rowing_tempo), color = SecondaryText, fontSize = 16.sp)
                val bpm = taps.bpm
                if (bpm == null) {
                    Text("–", color = SecondaryText, fontSize = 16.sp)
                } else {
                    val selected = halvings ?: suggestedHalvings(bpm)
                    rowingTempoChoices(bpm).forEach { (choice, tempo) ->
                        FilterChip(
                            selected = choice == selected,
                            onClick = {
                                halvings = choice
                                enterTapped()
                            },
                            enabled = !hidden,
                            label = { Text(formatBpm(tempo.roundToLong().toDouble()), fontSize = 16.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = PrimaryText,
                                selectedContainerColor = Accent,
                                selectedLabelColor = Color.Black,
                            ),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton("−1", enabled = !hidden) { step(-1) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !hidden,
                    isError = !valid,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Accent,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = SecondaryText,
                        cursorColor = Accent,
                    ),
                    modifier = Modifier.width(140.dp),
                )
                StepButton("+1", enabled = !hidden) { step(1) }
            }

            Text(detectedText(entry), color = SecondaryText, fontSize = 16.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = hidden,
                    onClick = { hidden = !hidden },
                    label = { Text(stringResource(R.string.no_tempo), fontSize = 16.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = PrimaryText,
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.Black,
                    ),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { text = entry?.detectedBpm?.let(::formatBpm).orEmpty() },
                    enabled = !hidden && entry?.detectedBpm != null,
                ) {
                    Text(stringResource(R.string.reset_tempo), color = Accent, fontSize = 16.sp)
                }
            }

            Button(
                onClick = save,
                enabled = valid,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.save), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Play and pause, and the position of the track, which is only shown while the track is playing.
 * Pauses playback started here when it leaves the composition, i.e. when the sheet closes.
 */
@Composable
private fun PreviewPlayer(track: Track, preview: TrackPreview, autoPlay: Boolean) {
    val state = preview.state
    val enabled = state.connection == ConnectionState.Connected
    val isThisTrack = state.nowPlaying?.uri == track.uri
    val isPlaying = isThisTrack && !state.isPaused
    // Something else that was playing, e.g. the session, keeps playing when the sheet closes
    var startedHere by remember { mutableStateOf(false) }
    fun play() {
        startedHere = true
        if (isThisTrack) preview.onResume() else preview.onPlay(track)
    }
    LaunchedEffect(Unit) { if (autoPlay && enabled) play() }
    val latestPreview by rememberUpdatedState(preview)
    val latestStartedHere by rememberUpdatedState(startedHere)
    DisposableEffect(Unit) {
        onDispose {
            val current = latestPreview.state
            if (latestStartedHere && current.nowPlaying?.uri == track.uri && !current.isPaused) latestPreview.onPause()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { if (isPlaying) preview.onPause() else play() },
            enabled = enabled,
            modifier = Modifier.size(64.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                if (isPlaying) MediaIcons.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                modifier = Modifier.size(32.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            val nowPlaying = NowPlaying(track.uri, track.title, track.artists, track.durationMs)
            if (isThisTrack) {
                PlaybackSlider(state, nowPlaying, enabled, preview.onSeek)
            } else {
                PlaybackSlider(RemoteState(), nowPlaying, enabled = false, onSeek = {})
            }
        }
    }
}

/**
 * A large area to tap along with the song, on each beat or each stroke. Taps count when the finger
 * touches it, the moment of the beat, rather than when it's lifted.
 */
@Composable
private fun TapButton(taps: TapTempo, enabled: Boolean, modifier: Modifier = Modifier, onTap: (uptimeMs: Long) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val latestOnTap by rememberUpdatedState(onTap)
    Column(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) Accent else Highlight)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    latestOnTap(down.uptimeMillis)
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val textColor = when {
            pressed -> Color.Black
            enabled -> PrimaryText
            else -> SecondaryText
        }
        Text(stringResource(R.string.tap), color = textColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        val bpm = taps.bpm
        Text(
            if (bpm == null) {
                stringResource(R.string.tap_hint)
            } else {
                pluralStringResource(R.plurals.tap_result, taps.count, formatBpm(bpm.roundToLong().toDouble()), taps.count)
            },
            color = if (pressed) Color.Black else SecondaryText,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(64.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryText),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 20.sp)
    }
}

/** Where the value comes from, e.g. "Detected: 105.9 (ReccoBeats)". */
@Composable
private fun detectedText(entry: BpmEntry?): String {
    val detected = entry?.detectedBpm ?: return stringResource(R.string.not_detected)
    val source = entry.source?.label
    return stringResource(R.string.detected_tempo, formatBpm(detected) + (source?.let { " ($it)" }.orEmpty()))
}

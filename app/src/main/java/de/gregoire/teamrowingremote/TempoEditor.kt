package de.gregoire.teamrowingremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import de.gregoire.teamrowingremote.spotify.Track
import kotlin.math.roundToLong

private val SheetBackground = Color(0xFF1A1A1A)
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFB3B3B3)
private val Accent = Color(0xFFFFC107)

/**
 * Bottom sheet to correct a track's tempo: a number, ±1, "No tempo" and reset to the detected
 * value. Changes are only saved with the save button or the keyboard's done key.
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet is still experimental in Material 3
@Composable
fun TempoEditorSheet(
    track: Track,
    entry: BpmEntry?,
    onSave: (manualBpm: Double?, hidden: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(entry?.bpm?.let(::formatBpm).orEmpty()) }
    var hidden by remember { mutableStateOf(entry?.hidden == true) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed > 0)
    val save = { if (valid) onSave(parsed, hidden) }

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

package de.gregoire.teamrowingremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.bpm.formatBpm
import de.gregoire.teamrowingremote.prepare.CheckedTrack
import de.gregoire.teamrowingremote.prepare.MINUTE_MS
import de.gregoire.teamrowingremote.prepare.PastSession
import de.gregoire.teamrowingremote.prepare.RowingTempo
import de.gregoire.teamrowingremote.prepare.Section
import de.gregoire.teamrowingremote.prepare.SessionCheck
import de.gregoire.teamrowingremote.prepare.SessionTemplate
import de.gregoire.teamrowingremote.prepare.TrackIssue
import de.gregoire.teamrowingremote.prepare.suggestedTempo
import de.gregoire.teamrowingremote.spotify.Playlist
import de.gregoire.teamrowingremote.spotify.Track
import kotlin.math.abs
import kotlin.math.roundToLong
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val Background = Color.Black
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFB3B3B3)
private val Accent = Color(0xFFFFC107)
private val Highlight = Color(0xFF2A2A2A)
private val ErrorColor = Color(0xFFFF6B6B)
private val EasyBand = Color(0xFF151515)
private val HardBand = Color(0xFF2A2210)
private val NotPreparedBar = Color(0xFF5A5A5A)
private val SheetBackground = Color(0xFF1A1A1A)

/**
 * Checks the session against the template: its length, a chart of the tempos over time, and its
 * tracks with the rules they break and when they were last used. The tracks can be moved, removed
 * and added from the fundus.
 */
@Composable
fun PrepareScreen(
    session: Playlist?,
    check: SessionCheck?,
    bpmEntries: Map<String, BpmEntry>,
    template: SessionTemplate,
    error: String?,
    newSessionName: String,
    onBack: () -> Unit,
    onChangeSession: () -> Unit,
    onNewSession: (String) -> Unit,
    onAddTracks: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onSaveTempo: (uri: String, manualBpm: Double?, hidden: Boolean) -> Unit,
    onUpdateTemplate: (SessionTemplate) -> Unit,
    preview: TrackPreview,
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
            preview = preview,
        )
    }
    var editingTemplate by rememberSaveable { mutableStateOf(false) }
    if (editingTemplate) {
        TemplateSheet(
            template = template,
            onSave = {
                onUpdateTemplate(it)
                editingTemplate = false
            },
            onDismiss = { editingTemplate = false },
        )
    }
    var creatingSession by rememberSaveable { mutableStateOf(false) }
    if (creatingSession) {
        NewSessionDialog(
            initialName = newSessionName,
            onCreate = {
                onNewSession(it)
                creatingSession = false
            },
            onDismiss = { creatingSession = false },
        )
    }
    // The track whose actions are shown; tapping a track shows them
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    // Keys that stay with a track while it's dragged, also for a track that is in the session twice
    val rows = remember(check?.tracks) {
        val occurrences = mutableMapOf<String, Int>()
        check?.tracks.orEmpty().map { SessionRow("${it.track.uri}#${occurrences.merge(it.track.uri, 1, Int::plus)}", it) }
    }
    // The order while a track is dragged; the move reaches the session when it's dropped
    var dragged by remember { mutableStateOf<List<SessionRow>?>(null) }
    var dragStart by remember { mutableStateOf<SessionRow?>(null) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val order = (dragged ?: rows).toMutableList()
        val fromIndex = order.indexOfFirst { it.key == from.key }
        val toIndex = order.indexOfFirst { it.key == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            order.add(toIndex, order.removeAt(fromIndex))
            dragged = order
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back), color = Accent, fontSize = 18.sp) }
            Text(
                stringResource(R.string.prepare_title),
                color = PrimaryText,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { editingTemplate = true }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = PrimaryText)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                session?.name.orEmpty(),
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
            TextButton(onClick = { creatingSession = true }) {
                Text(stringResource(R.string.new_session), color = Accent, fontSize = 18.sp)
            }
        }
        error?.let { Text(it, color = ErrorColor) }

        if (check == null) {
            if (error == null) {
                Text(stringResource(R.string.loading_history), color = SecondaryText)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Highlight)
            }
            return@Column
        }

        // The summary and chart scroll away with the tracks, which leaves the phone's screen to the list
        LazyColumn(state = listState) {
            item(key = "overview") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Summary(check)
                    TempoChart(check, Modifier.fillMaxWidth().height(140.dp))
                    Button(
                        onClick = onAddTracks,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(stringResource(R.string.add_tracks), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (check.tracks.isEmpty()) {
                        Text(stringResource(R.string.empty_session), color = SecondaryText, fontSize = 18.sp)
                    }
                }
            }
            // The section headers stay at their positions while a track is dragged past them, and show
            // which section it is dropped into
            // A drag that started before the session changed is left for the new list
            (dragged?.takeIf { it.size == rows.size } ?: rows).forEachIndexed { index, row ->
                val section = rows[index].checked.section
                if (index == 0 || rows[index - 1].checked.section != section) {
                    item(key = "section:$index") { SectionHeader(section) }
                }
                item(key = row.key) {
                    ReorderableItem(reorderState, key = row.key) { isDragging ->
                        val checked = row.checked
                        Column {
                            PrepareTrackRow(
                                checked = checked,
                                number = index + 1,
                                template = check.template,
                                selected = isDragging || (dragged == null && checked.index == selectedIndex),
                                onClick = { selectedIndex = checked.index.takeIf { it != selectedIndex } },
                                onTempoClick = { editing = checked.track },
                                handle = {
                                    Icon(
                                        MediaIcons.DragHandle,
                                        contentDescription = stringResource(R.string.move),
                                        tint = SecondaryText,
                                        modifier = Modifier
                                            .draggableHandle(
                                                onDragStarted = {
                                                    selectedIndex = null
                                                    dragStart = row
                                                },
                                                onDragStopped = {
                                                    val from = rows.indexOf(dragStart)
                                                    val to = dragged?.indexOf(dragStart) ?: from
                                                    dragged = null
                                                    dragStart = null
                                                    if (from >= 0 && to >= 0 && from != to) onMove(from, to)
                                                },
                                            )
                                            .size(48.dp)
                                            .padding(12.dp),
                                    )
                                },
                            )
                            if (dragged == null && checked.index == selectedIndex) {
                                TrackActions(
                                    onRemove = {
                                        onRemove(checked.index)
                                        selectedIndex = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A track of the session, with a key for the list that stays with it while it's dragged. */
private class SessionRow(val key: String, val checked: CheckedTrack)

/** The session's length against the template, and how many tracks need attention. */
@Composable
private fun Summary(check: SessionCheck) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stringResource(
                    R.string.session_length,
                    formatDuration(check.totalMs),
                    formatDuration(check.template.lengthMinutes * MINUTE_MS),
                ),
                color = if (check.lengthOk) PrimaryText else ErrorColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (check.stretchingMs > 0) {
                Text(
                    stringResource(R.string.stretching_length, formatDuration(check.stretchingMs)),
                    color = SecondaryText,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            if (!check.lengthOk) {
                val difference = formatDuration(abs(check.lengthDifferenceMs))
                Text(
                    stringResource(
                        if (check.lengthDifferenceMs > 0) R.string.length_too_long else R.string.length_too_short,
                        difference,
                    ),
                    color = ErrorColor,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
        }
        val parts = buildList {
            if (check.tracksWithIssues > 0) {
                add(pluralStringResource(R.plurals.tracks_with_issues, check.tracksWithIssues, check.tracksWithIssues))
            }
            if (check.notPrepared > 0) {
                add(pluralStringResource(R.plurals.tracks_not_prepared, check.notPrepared, check.notPrepared))
            }
            val lastSession = check.lastSession
            if (check.inLastSession > 0 && lastSession != null) {
                add(
                    pluralStringResource(
                        R.plurals.tracks_in_last_session,
                        check.inLastSession,
                        check.inLastSession,
                        lastSession.playlist.name,
                    ),
                )
            }
        }
        // An empty session has nothing to check yet
        if (check.tracks.isEmpty()) return@Column
        Text(
            parts.joinToString(" · ").ifEmpty { stringResource(R.string.no_issues) },
            color = if (check.tracksWithIssues > 0) ErrorColor else SecondaryText,
            fontSize = 15.sp,
        )
    }
}

/**
 * The tracks as bars over time, as wide as they last and as high as their tempo, on the easy and
 * hard tempo bands. Lines mark the end of the warm-up, the start of the cool-down and the template's
 * length. Bars of tracks breaking a rule are red, those without tempo grey.
 */
@Composable
private fun TempoChart(check: SessionCheck, modifier: Modifier = Modifier) {
    val template = check.template
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = SecondaryText, fontSize = 11.sp)
    Canvas(modifier) {
        val labelWidth = 24.dp.toPx()
        val chartWidth = size.width - labelWidth
        val lengthMs = maxOf(check.totalMs, template.lengthMinutes * MINUTE_MS).coerceAtLeast(1)
        val minTempo = (template.easyFrom - 4).toFloat()
        val maxTempo = (template.hardFrom + 8).toFloat()
        fun x(ms: Long) = labelWidth + chartWidth * ms / lengthMs
        fun y(tempo: Double) = size.height * (1 - ((tempo.toFloat() - minTempo) / (maxTempo - minTempo)).coerceIn(0f, 1f))

        val easyTop = y(template.hardFrom.toDouble())
        val easyBottom = y(template.easyFrom.toDouble())
        drawRect(HardBand, Offset(labelWidth, 0f), Size(chartWidth, easyTop))
        drawRect(EasyBand, Offset(labelWidth, easyTop), Size(chartWidth, easyBottom - easyTop))
        for (tempo in listOf(template.easyFrom, template.hardFrom)) {
            val label = textMeasurer.measure(tempo.toString(), labelStyle)
            drawText(label, topLeft = Offset(0f, y(tempo.toDouble()) - label.size.height / 2))
        }

        val markerEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        fun marker(ms: Long, color: Color) = drawLine(
            color,
            Offset(x(ms), 0f),
            Offset(x(ms), size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = markerEffect,
        )
        marker(template.warmUpMinutes * MINUTE_MS, SecondaryText)
        marker(check.totalMs - template.coolDownMinutes * MINUTE_MS, SecondaryText)
        marker(template.lengthMinutes * MINUTE_MS, PrimaryText)

        val gap = 1.dp.toPx()
        for (checked in check.tracks) {
            val (top, color) = when (val tempo = checked.tempo) {
                is RowingTempo.Strokes -> y(tempo.perMinute) to (if (checked.breaksRule) ErrorColor else Accent)
                // Stretching isn't rowing time
                RowingTempo.None -> continue
                is RowingTempo.NotPrepared -> y(minTempo + 2.0) to NotPreparedBar
            }
            bar(x(checked.startMs) + gap, x(checked.endMs) - gap, top, color)
        }
    }
}

private fun DrawScope.bar(left: Float, right: Float, top: Float, color: Color) {
    drawRect(color, Offset(left, top), Size((right - left).coerceAtLeast(1f), size.height - top))
}

private val CheckedTrack.breaksRule: Boolean
    get() = issues.any { it != TrackIssue.NotPrepared }

@Composable
private fun SectionHeader(section: Section) {
    Text(
        stringResource(
            when (section) {
                Section.WARM_UP -> R.string.section_warm_up
                Section.MAIN -> R.string.section_main
                Section.COOL_DOWN -> R.string.section_cool_down
                Section.STRETCHING -> R.string.section_stretching
            },
        ).uppercase(),
        color = SecondaryText,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PrepareTrackRow(
    checked: CheckedTrack,
    number: Int,
    template: SessionTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    onTempoClick: () -> Unit,
    handle: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Highlight else Background)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$number",
            color = SecondaryText,
            fontSize = 16.sp,
            modifier = Modifier.width(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                checked.track.title,
                color = PrimaryText,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                checked.track.artists,
                color = SecondaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            for (issue in checked.issues) {
                Text(issueText(issue, checked, template), color = ErrorColor, fontSize = 14.sp)
            }
            UsageHint(checked.inLastSession, checked.lastUsed)
        }
        TempoBox(checked.tempo, highlighted = checked.breaksRule, onClick = onTempoClick)
        Text(
            formatDuration(checked.track.durationMs),
            color = SecondaryText,
            fontSize = 15.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
        handle()
    }
}

/** When a track was last used: a highlighted hint if it was in the last session. */
@Composable
internal fun UsageHint(inLastSession: Boolean, lastUsed: PastSession?) {
    when {
        inLastSession -> Text(stringResource(R.string.hint_in_last_session), color = Accent, fontSize = 14.sp)
        lastUsed != null -> Text(
            stringResource(R.string.hint_last_used, lastUsed.playlist.name),
            color = SecondaryText,
            fontSize = 14.sp,
        )
    }
}

/** A track's rowing tempo, or the song's suggested one in grey; tapping it edits the tempo. */
@Composable
internal fun TempoBox(tempo: RowingTempo, highlighted: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        when (tempo) {
            is RowingTempo.Strokes -> Text(
                formatBpm(tempo.perMinute),
                color = if (highlighted) ErrorColor else PrimaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            RowingTempo.None -> Unit
            is RowingTempo.NotPrepared -> Text(
                tempo.detectedBpm?.let { "≈" + formatBpm(suggestedTempo(it).roundToLong().toDouble()) } ?: "–",
                color = NotPreparedBar,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun issueText(issue: TrackIssue, checked: CheckedTrack, template: SessionTemplate): String = when (issue) {
    TrackIssue.NotPrepared -> {
        val detected = (checked.tempo as? RowingTempo.NotPrepared)?.detectedBpm
        if (detected == null) {
            stringResource(R.string.issue_not_prepared)
        } else {
            stringResource(
                R.string.issue_not_prepared_suggested,
                formatBpm(detected),
                formatBpm(suggestedTempo(detected).roundToLong().toDouble()),
            )
        }
    }
    TrackIssue.TooSlow -> stringResource(R.string.issue_too_slow, template.easyFrom)
    is TrackIssue.HardIn -> stringResource(
        if (issue.section == Section.COOL_DOWN) R.string.issue_hard_in_cool_down else R.string.issue_hard_in_warm_up,
    )
    TrackIssue.HardAfterHard -> stringResource(R.string.issue_hard_after_hard)
    is TrackIssue.LargeStep -> stringResource(R.string.issue_large_step, formatBpm(issue.step))
    TrackIssue.Repeated -> stringResource(R.string.issue_repeated)
}

@Composable
private fun TrackActions(onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Highlight)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionButton(Icons.Filled.Close, stringResource(R.string.remove), true, onRemove)
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, tint = if (enabled) PrimaryText else Highlight)
        Text(label, color = if (enabled) PrimaryText else SecondaryText, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun NewSessionDialog(initialName: String, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheetBackground,
        titleContentColor = PrimaryText,
        textContentColor = SecondaryText,
        title = { Text(stringResource(R.string.new_session_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = PrimaryText, fontSize = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                )
                Text(stringResource(R.string.new_session_hint), fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.create), color = Accent, fontSize = 16.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Accent, fontSize = 16.sp) }
        },
    )
}

/** Bottom sheet to change the template the session is checked against. */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet is still experimental in Material 3
@Composable
private fun TemplateSheet(template: SessionTemplate, onSave: (SessionTemplate) -> Unit, onDismiss: () -> Unit) {
    var values by remember(template) { mutableStateOf(template.fields().map { it.second.toString() }) }
    val labels = template.fields().map { it.first }
    val parsed = values.map { it.toIntOrNull()?.takeIf { value -> value >= 0 } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetBackground,
        contentColor = PrimaryText,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.template_title), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            labels.forEachIndexed { index, label ->
                OutlinedTextField(
                    value = values[index],
                    onValueChange = { value -> values = values.toMutableList().also { it[index] = value } },
                    label = { Text(stringResource(label)) },
                    isError = parsed[index] == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = SecondaryText,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = SecondaryText,
                        cursorColor = Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row {
                TextButton(onClick = { values = SessionTemplate().fields().map { it.second.toString() } }) {
                    Text(stringResource(R.string.template_reset), color = Accent)
                }
            }
            Button(
                onClick = { onSave(templateOf(parsed.map { requireNotNull(it) })) },
                enabled = parsed.all { it != null },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.save), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The template's values with their labels, in the order of [templateOf]. */
private fun SessionTemplate.fields(): List<Pair<Int, Int>> = listOf(
    R.string.template_length to lengthMinutes,
    R.string.template_tolerance to toleranceMinutes,
    R.string.template_warm_up to warmUpMinutes,
    R.string.template_cool_down to coolDownMinutes,
    R.string.template_easy_from to easyFrom,
    R.string.template_hard_from to hardFrom,
    R.string.template_max_step to maxStep,
)

private fun templateOf(values: List<Int>) = SessionTemplate(
    lengthMinutes = values[0],
    toleranceMinutes = values[1],
    warmUpMinutes = values[2],
    coolDownMinutes = values[3],
    easyFrom = values[4],
    hardFrom = values[5],
    maxStep = values[6],
)

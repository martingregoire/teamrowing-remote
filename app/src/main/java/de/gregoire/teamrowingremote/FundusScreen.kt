package de.gregoire.teamrowingremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.prepare.FundusTrack
import de.gregoire.teamrowingremote.prepare.Intensity
import de.gregoire.teamrowingremote.prepare.RowingTempo
import de.gregoire.teamrowingremote.prepare.SessionHistory
import de.gregoire.teamrowingremote.prepare.SessionTemplate
import de.gregoire.teamrowingremote.prepare.rowingTempo
import de.gregoire.teamrowingremote.prepare.sessionDate
import de.gregoire.teamrowingremote.spotify.Playlist
import de.gregoire.teamrowingremote.spotify.Track
import java.time.LocalDate

private val Background = Color.Black
private val PrimaryText = Color.White
private val SecondaryText = Color(0xFFB3B3B3)
private val Accent = Color(0xFFFFC107)
private val Highlight = Color(0xFF2A2A2A)
private val ErrorColor = Color(0xFFFF6B6B)

private enum class TempoFilter(val label: Int) {
    ALL(R.string.filter_all),
    EASY(R.string.filter_easy),
    HARD(R.string.filter_hard),
    NOT_PREPARED(R.string.filter_not_prepared),
}

private enum class FundusOrder(val label: Int) {
    TEMPO(R.string.sort_tempo),
    UNUSED(R.string.sort_unused),
    LENGTH(R.string.sort_length),
}

/**
 * The tracks of the fundus playlists chosen by the trainer, to add to the session: with their tempo
 * and when they were last used, filtered by tempo and sorted by tempo, by how long they weren't used
 * or by length.
 */
@Composable
fun FundusScreen(
    session: Playlist?,
    sessionTracks: List<Track>,
    playlists: List<Playlist>?,
    fundusIds: Set<String>,
    fundusTracks: List<FundusTrack>?,
    history: SessionHistory?,
    bpmEntries: Map<String, BpmEntry>,
    template: SessionTemplate,
    error: String?,
    onBack: () -> Unit,
    onChangeFundus: (Set<String>) -> Unit,
    onAdd: (Track) -> Unit,
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
    var filter by rememberSaveable { mutableStateOf(TempoFilter.ALL) }
    var hideLastSession by rememberSaveable { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf(FundusOrder.TEMPO) }

    // The sessions before the prepared one; all if it isn't named like a session
    val earlier = history?.before(session?.name?.let(::sessionDate))
    val inSession = sessionTracks.map { it.uri }.toSet()
    // Sessions aren't fundus playlists
    val candidates = playlists?.filter { sessionDate(it.name) == null }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back), color = Accent, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fundus_title), color = PrimaryText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(session?.name.orEmpty(), color = SecondaryText, fontSize = 15.sp)
            }
        }
        error?.let { Text(it, color = ErrorColor) }

        val shown = fundusTracks.orEmpty()
            .mapNotNull { fundus -> earlier?.let { FundusRow(fundus.track, rowingTempo(bpmEntries[fundus.track.uri]), it) } }
            .filter { row ->
                val intensity = (row.tempo as? RowingTempo.Strokes)?.let { template.intensity(it.perMinute) }
                val matches = when (filter) {
                    TempoFilter.ALL -> true
                    TempoFilter.EASY -> intensity == Intensity.EASY
                    TempoFilter.HARD -> intensity == Intensity.HARD
                    TempoFilter.NOT_PREPARED -> row.tempo is RowingTempo.NotPrepared
                }
                matches && !(hideLastSession && row.inLastSession)
            }
            .let { rows ->
                when (order) {
                    FundusOrder.TEMPO -> rows.sortedBy { (it.tempo as? RowingTempo.Strokes)?.perMinute ?: Double.MAX_VALUE }
                    // Never used first, then the ones used longest ago
                    FundusOrder.UNUSED -> rows.sortedBy { it.lastUsedDate ?: LocalDate.MIN }
                    // Shortest first
                    FundusOrder.LENGTH -> rows.sortedBy { it.track.durationMs }
                }
            }
        // One list for the choices and the tracks: it keeps its scroll position while tracks are loaded,
        // and the choices scroll away to leave the phone's screen to the tracks
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "choices") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.fundus_choose).uppercase(),
                        color = SecondaryText,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                    )
                    when {
                        candidates == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Highlight)
                        candidates.isEmpty() -> Text(stringResource(R.string.fundus_no_playlists), color = SecondaryText)
                        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(candidates, key = { it.id }) { playlist ->
                                val selected = playlist.id in fundusIds
                                Chip(playlist.name, selected) {
                                    onChangeFundus(if (selected) fundusIds - playlist.id else fundusIds + playlist.id)
                                }
                            }
                        }
                    }
                    when {
                        fundusIds.isEmpty() -> Text(stringResource(R.string.fundus_none), color = SecondaryText, fontSize = 16.sp)
                        fundusTracks == null || earlier == null ->
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Highlight)
                        else -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(TempoFilter.entries) { Chip(stringResource(it.label), filter == it) { filter = it } }
                                item {
                                    Chip(stringResource(R.string.filter_hide_last_session), hideLastSession) {
                                        hideLastSession = !hideLastSession
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(FundusOrder.entries) { Chip(stringResource(it.label), order == it) { order = it } }
                            }
                            Text(
                                pluralStringResource(R.plurals.fundus_count, shown.size, shown.size),
                                color = SecondaryText,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
            if (fundusIds.isEmpty() || earlier == null) return@LazyColumn
            items(shown, key = { it.track.uri }) { row ->
                FundusTrackRow(
                    row = row,
                    inSession = row.track.uri in inSession,
                    onAdd = { onAdd(row.track) },
                    onTempoClick = { editing = row.track },
                )
            }
        }
    }
}

private class FundusRow(val track: Track, val tempo: RowingTempo, history: SessionHistory) {
    val inLastSession = history.isInLastSession(track.uri)
    val lastUsed = history.lastUsed(track.uri)
    val lastUsedDate: LocalDate? = lastUsed?.date
}

@Composable
private fun FundusTrackRow(row: FundusRow, inSession: Boolean, onAdd: () -> Unit, onTempoClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.track.title, color = PrimaryText, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.track.artists, color = SecondaryText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (inSession) {
                Text(stringResource(R.string.hint_in_session), color = PrimaryText, fontSize = 14.sp)
            } else if (row.lastUsed == null) {
                Text(stringResource(R.string.hint_never_used), color = SecondaryText, fontSize = 14.sp)
            } else {
                UsageHint(row.inLastSession, row.lastUsed)
            }
        }
        TempoBox(row.tempo, highlighted = false, onClick = onTempoClick)
        Text(
            formatDuration(row.track.durationMs),
            color = SecondaryText,
            fontSize = 15.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
        // Disabled for tracks in the session, which are usually only there once
        IconButton(onClick = onAdd, enabled = !inSession) {
            Icon(
                if (inSession) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = stringResource(R.string.add),
                tint = if (inSession) SecondaryText else Accent,
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 15.sp) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = PrimaryText,
            selectedContainerColor = Accent,
            selectedLabelColor = Color.Black,
        ),
    )
}

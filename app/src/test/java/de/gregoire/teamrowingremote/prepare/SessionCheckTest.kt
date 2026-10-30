package de.gregoire.teamrowingremote.prepare

import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.spotify.Playlist
import de.gregoire.teamrowingremote.spotify.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SessionCheckTest {

    private val template = SessionTemplate()
    private val noHistory = SessionHistory(emptyList())

    private fun track(id: Int, minutes: Double = 4.0) =
        Track("spotify:track:$id", "Track $id", "Artist", (minutes * MINUTE_MS).toLong())

    /** Tracks with the given rowing tempos, 4 minutes each; null for "No tempo". */
    private fun check(vararg tempos: Double?, history: SessionHistory = noHistory, isSession: Boolean = true): SessionCheck {
        val tracks = tempos.indices.map { track(it) }
        val entries = tracks.zip(tempos.toList()).associate { (track, tempo) ->
            track.uri to if (tempo == null) BpmEntry(hidden = true) else BpmEntry(tempo * 4, manualBpm = tempo)
        }
        return checkSession(tracks, entries, template, history, isSession)
    }

    @Test
    fun aSessionFollowingTheTemplateHasNoIssues() {
        // 11 tracks of 4 minutes: 44 minutes, warm-up and cool-down two tracks each
        val result = check(20.0, 22.0, 24.0, 22.0, 25.0, 22.0, 24.0, 21.0, 24.0, 22.0, 20.0)
        assertTrue(result.lengthOk)
        assertEquals(0, result.tracksWithIssues)
        assertEquals(
            listOf(Section.WARM_UP, Section.WARM_UP) + List(7) { Section.MAIN } + listOf(Section.COOL_DOWN, Section.COOL_DOWN),
            result.tracks.map { it.section },
        )
    }

    @Test
    fun pointsOutBrokenRules() {
        val result = check(24.0, 20.0, 24.0, 26.0, 21.0, 18.0)
        val issues = result.tracks.map { it.issues }
        assertEquals(listOf(TrackIssue.HardIn(Section.WARM_UP)), issues[0])
        assertEquals(listOf(TrackIssue.HardAfterHard), issues[3])
        assertEquals(listOf(TrackIssue.LargeStep(5.0)), issues[4])
        assertEquals(listOf(TrackIssue.TooSlow), issues[5])
    }

    @Test
    fun onlyPointsOutTheTracksThemselvesInOtherPlaylists() {
        val result = check(24.0, 20.0, 24.0, 26.0, 21.0, 18.0, isSession = false)
        assertEquals(List(5) { emptyList<TrackIssue>() } + listOf(listOf(TrackIssue.TooSlow)), result.tracks.map { it.issues })
    }

    @Test
    fun stretchingTracksDontCountAsRowingTime() {
        // 11 rowing tracks of 4 minutes and two stretching tracks at the end
        val result = check(20.0, 22.0, 24.0, 22.0, 25.0, 22.0, 24.0, 21.0, 24.0, 22.0, 20.0, null, null)
        assertTrue(result.lengthOk)
        assertEquals(44 * MINUTE_MS, result.totalMs)
        assertEquals(8 * MINUTE_MS, result.stretchingMs)
        assertEquals(Section.COOL_DOWN, result.tracks[10].section)
        assertEquals(listOf(Section.STRETCHING, Section.STRETCHING), result.tracks.drop(11).map { it.section })
        assertEquals(0, result.tracksWithIssues)
    }

    @Test
    fun noTempoTracksInterruptStepsAndHardSequences() {
        val result = check(22.0, 24.0, null, 26.0, 18.0, null, 20.0)
        assertEquals(emptyList<TrackIssue>(), result.tracks[3].issues)
        assertEquals(emptyList<TrackIssue>(), result.tracks[6].issues)
    }

    @Test
    fun lookedUpSongTempoIsNotPrepared() {
        val track = track(1)
        val result = checkSession(listOf(track), mapOf(track.uri to BpmEntry(91.0)), template, noHistory)
        assertEquals(RowingTempo.NotPrepared(91.0), result.tracks[0].tempo)
        assertEquals(1, result.notPrepared)
        assertEquals(0, result.tracksWithIssues)
    }

    @Test
    fun flagsTheLengthOutsideTheTolerance() {
        assertFalse(check(*Array<Double?>(10) { 22.0 }).lengthOk)
        assertTrue(check(*Array<Double?>(11) { 22.0 }).lengthOk)
    }

    @Test
    fun findsTheLastSessionAndTheLastUse() {
        fun session(date: String, vararg ids: Int) = PastSession(
            Playlist(date, "TR $date", ids.size),
            requireNotNull(sessionDate("TR $date")),
            ids.map { "spotify:track:$it" }.toSet(),
        )
        val history = SessionHistory(
            listOf(session("14.10.2026", 0, 1), session("21.10.2026", 1), session("28.10.2026", 2)),
        ).before(sessionDate("TR 28.10.2026"))

        val result = check(22.0, 22.0, 22.0, history = history)
        assertEquals("TR 21.10.2026", result.lastSession?.playlist?.name)
        assertEquals(listOf(false, true, false), result.tracks.map { it.inLastSession })
        assertEquals("TR 14.10.2026", result.tracks[0].lastUsed?.playlist?.name)
        assertEquals("TR 21.10.2026", result.tracks[1].lastUsed?.playlist?.name)
        // Only sessions before the checked one count
        assertNull(result.tracks[2].lastUsed)
    }

    @Test
    fun parsesSessionNames() {
        assertEquals(LocalDate.of(2026, 10, 28), sessionDate("TR 28.10.2026"))
        assertEquals(LocalDate.of(2026, 10, 28), sessionDate("TR 28.10.2026 Pyramide"))
        assertNull(sessionDate("Fundus ruhig"))
        assertNull(sessionDate("TR 31.02.2026"))
        assertEquals("TR 04.11.2026", sessionName(LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun suggestsARowingTempoForTheSongTempo() {
        assertEquals(22.75, suggestedTempo(91.0), 0.0)
        assertEquals(30.0, suggestedTempo(120.0), 0.0)
        assertEquals(24.0, suggestedTempo(24.0), 0.0)
    }
}

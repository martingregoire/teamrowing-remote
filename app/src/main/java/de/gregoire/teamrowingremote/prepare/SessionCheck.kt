package de.gregoire.teamrowingremote.prepare

import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.spotify.Track
import kotlin.math.abs

/**
 * What a session should look like, after the beginner advice of
 * https://rp3rowing.com/blog-items/how-do-you-incorporate-tempo-training-into-rowing-workouts/:
 * an easy warm-up and cool-down, and a main part that never has two hard tracks in a row, with
 * small tempo steps throughout. Tempos are strokes per minute.
 */
data class SessionTemplate(
    val lengthMinutes: Int = 45,
    val toleranceMinutes: Int = 2,
    val warmUpMinutes: Int = 9,
    val coolDownMinutes: Int = 7,
    /** The slowest easy tempo; slower ones are too slow. */
    val easyFrom: Int = 20,
    /** The slowest hard tempo; tempos from [easyFrom] up to it are easy. */
    val hardFrom: Int = 24,
    val maxStep: Int = 4,
) {
    fun intensity(tempo: Double): Intensity = when {
        tempo < easyFrom -> Intensity.SLOW
        tempo < hardFrom -> Intensity.EASY
        else -> Intensity.HARD
    }
}

enum class Section { WARM_UP, MAIN, COOL_DOWN }

enum class Intensity { SLOW, EASY, HARD }

/** A track's tempo for rowing, as far as the trainer has prepared it. */
sealed interface RowingTempo {
    data class Strokes(val perMinute: Double) : RowingTempo

    /** Marked "No tempo" by the trainer, e.g. for stretching. */
    data object None : RowingTempo

    /** Not corrected to a rowing tempo yet; [detectedBpm] is the song's tempo, if a service knows it. */
    data class NotPrepared(val detectedBpm: Double?) : RowingTempo
}

/**
 * The rowing tempo of a track. Lookups give the song's BPM, usually two or four times the rowing
 * tempo, so only values up to [MAX_ROWING_TEMPO] count as prepared.
 */
fun rowingTempo(entry: BpmEntry?): RowingTempo {
    val bpm = entry?.bpm
    return when {
        entry?.hidden == true -> RowingTempo.None
        bpm != null && bpm <= MAX_ROWING_TEMPO -> RowingTempo.Strokes(bpm)
        else -> RowingTempo.NotPrepared(entry?.detectedBpm)
    }
}

/** A rowing tempo for a song's [bpm]: the BPM halved as often as it brings it closest to a usual one. */
fun suggestedTempo(bpm: Double): Double =
    generateSequence(bpm) { it / 2 }.take(4).minBy { abs(it - USUAL_ROWING_TEMPO) }

const val MAX_ROWING_TEMPO = 40.0
private const val USUAL_ROWING_TEMPO = 25.0

sealed interface TrackIssue {
    data object NotPrepared : TrackIssue

    /** Slower than the easy tempos. */
    data object TooSlow : TrackIssue

    /** A hard track in the warm-up or cool-down. */
    data class HardIn(val section: Section) : TrackIssue

    data object HardAfterHard : TrackIssue

    /** The tempo changes by more than the template allows from the previous track. */
    data class LargeStep(val step: Double) : TrackIssue

    /** The track is in the session more than once. */
    data object Repeated : TrackIssue
}

data class CheckedTrack(
    val track: Track,
    /** Position in the session playlist. */
    val index: Int,
    val startMs: Long,
    val section: Section,
    val tempo: RowingTempo,
    val intensity: Intensity?,
    val issues: List<TrackIssue>,
    val inLastSession: Boolean,
    /** The newest earlier session with this track, or null if it was never used. */
    val lastUsed: PastSession?,
) {
    val endMs: Long
        get() = startMs + track.durationMs
}

data class SessionCheck(
    val tracks: List<CheckedTrack>,
    val template: SessionTemplate,
    val lastSession: PastSession?,
) {
    val totalMs: Long = tracks.sumOf { it.track.durationMs }

    /** Positive if the session is longer than the template's length. */
    val lengthDifferenceMs: Long = totalMs - template.lengthMinutes * MINUTE_MS

    val lengthOk: Boolean = abs(lengthDifferenceMs) <= template.toleranceMinutes * MINUTE_MS

    /** Tracks that break a rule, without the ones only missing their tempo. */
    val tracksWithIssues: Int = tracks.count { checked -> checked.issues.any { it != TrackIssue.NotPrepared } }

    val notPrepared: Int = tracks.count { it.tempo is RowingTempo.NotPrepared }

    val inLastSession: Int = tracks.count { it.inLastSession }
}

/**
 * Checks a session's [tracks] against the [template], with tempos from [entries] and the [history] of
 * the sessions before it. Breaking a rule is only pointed out, never prevented.
 */
fun checkSession(
    tracks: List<Track>,
    entries: Map<String, BpmEntry>,
    template: SessionTemplate,
    history: SessionHistory,
): SessionCheck {
    val totalMs = tracks.sumOf { it.durationMs }
    val coolDownFromMs = totalMs - template.coolDownMinutes * MINUTE_MS
    val repeated = tracks.groupingBy { it.uri }.eachCount().filterValues { it > 1 }.keys

    var startMs = 0L
    // The tempo of the previous track, to check the step; "No tempo" and unprepared tracks reset it
    var previousTempo: Double? = null
    var previousHard = false
    val checked = tracks.mapIndexed { index, track ->
        // A track belongs to the section that most of it is in
        val middleMs = startMs + track.durationMs / 2
        val section = when {
            middleMs < template.warmUpMinutes * MINUTE_MS -> Section.WARM_UP
            middleMs > coolDownFromMs -> Section.COOL_DOWN
            else -> Section.MAIN
        }
        val tempo = rowingTempo(entries[track.uri])
        val issues = mutableListOf<TrackIssue>()
        var intensity: Intensity? = null
        when (tempo) {
            is RowingTempo.Strokes -> {
                intensity = template.intensity(tempo.perMinute)
                when {
                    intensity == Intensity.SLOW -> issues += TrackIssue.TooSlow
                    intensity == Intensity.HARD && section != Section.MAIN -> issues += TrackIssue.HardIn(section)
                    intensity == Intensity.HARD && previousHard -> issues += TrackIssue.HardAfterHard
                }
                previousTempo?.let { previous ->
                    val step = abs(tempo.perMinute - previous)
                    if (step > template.maxStep) issues += TrackIssue.LargeStep(step)
                }
                previousTempo = tempo.perMinute
                previousHard = intensity == Intensity.HARD
            }
            RowingTempo.None -> {
                previousTempo = null
                previousHard = false
            }
            is RowingTempo.NotPrepared -> {
                issues += TrackIssue.NotPrepared
                previousTempo = null
                previousHard = false
            }
        }
        if (track.uri in repeated) issues += TrackIssue.Repeated

        CheckedTrack(
            track = track,
            index = index,
            startMs = startMs,
            section = section,
            tempo = tempo,
            intensity = intensity,
            issues = issues,
            inLastSession = history.isInLastSession(track.uri),
            lastUsed = history.lastUsed(track.uri),
        ).also { startMs += track.durationMs }
    }
    return SessionCheck(checked, template, history.lastSession)
}

const val MINUTE_MS = 60_000L

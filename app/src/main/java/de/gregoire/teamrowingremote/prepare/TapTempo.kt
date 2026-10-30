package de.gregoire.teamrowingremote.prepare

/**
 * A tempo measured by tapping along with a song: from the median time between the last taps, so a
 * single early or late tap hardly changes it. A pause of more than [RESET_MS] starts a new measurement.
 */
data class TapTempo(private val times: List<Long> = emptyList(), val count: Int = 0) {

    /** With a tap at [atMs], e.g. the touch event's uptime. */
    fun tap(atMs: Long): TapTempo {
        val restart = times.isNotEmpty() && atMs - times.last() > RESET_MS
        val kept = if (restart) emptyList() else times
        return TapTempo((kept + atMs).takeLast(MAX_TAPS), if (restart) 1 else count + 1)
    }

    /** Taps per minute, or null before the second tap. */
    val bpm: Double?
        get() {
            val intervals = times.zipWithNext { a, b -> b - a }.sorted()
            if (intervals.isEmpty()) return null
            val middle = intervals.size / 2
            val median = if (intervals.size % 2 == 1) intervals[middle].toDouble() else (intervals[middle - 1] + intervals[middle]) / 2.0
            return if (median > 0) MINUTE_MS / median else null
        }

    companion object {
        /** Long enough for tapping the strokes themselves, down to 15 per minute. */
        const val RESET_MS = 4_000L

        /** The taps the tempo is measured from, so it follows a corrected tapping quickly. */
        const val MAX_TAPS = 9
        private const val MINUTE_MS = 60_000.0
    }
}

/**
 * The rowing tempos a tapped [bpm] may stand for, by how often it is halved: whether the beat, every
 * second beat or the strokes themselves were tapped. Only plausible rowing tempos are included.
 */
fun rowingTempoChoices(bpm: Double): Map<Int, Double> =
    (0..MAX_HALVINGS).associateWith { bpm / (1 shl it) }.filterValues { it in MIN_ROWING_CHOICE..MAX_ROWING_TEMPO }

private const val MIN_ROWING_CHOICE = 10.0

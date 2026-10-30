package de.gregoire.teamrowingremote.prepare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapTempoTest {

    private fun taps(vararg times: Long) = times.fold(TapTempo()) { tempo, time -> tempo.tap(time) }

    @Test
    fun `needs two taps`() {
        assertNull(TapTempo().bpm)
        assertNull(taps(1_000).bpm)
        assertEquals(120.0, taps(1_000, 1_500).bpm!!, 0.0)
    }

    @Test
    fun `a single late tap hardly changes the tempo`() {
        val tempo = taps(0, 625, 1_250, 1_975, 2_500, 3_125)
        assertEquals(96.0, tempo.bpm!!, 0.0)
        assertEquals(6, tempo.count)
    }

    @Test
    fun `measures from the last taps`() {
        // Ten taps at 120 BPM, then nine at 100 BPM
        val times = (0L until 10).map { it * 500 } + (1L..9).map { 4_500 + it * 600 }
        assertEquals(100.0, taps(*times.toLongArray()).bpm!!, 0.0)
    }

    @Test
    fun `a pause starts a new measurement`() {
        val tempo = taps(0, 500, 1_000, 6_000)
        assertNull(tempo.bpm)
        assertEquals(1, tempo.count)
        assertEquals(30.0, tempo.tap(8_000).bpm!!, 0.0)
    }

    @Test
    fun `rowing tempo choices`() {
        // 96 and 48 are too fast for rowing
        assertEquals(mapOf(2 to 24.0, 3 to 12.0), rowingTempoChoices(96.0))
        assertEquals(mapOf(0 to 24.0, 1 to 12.0), rowingTempoChoices(24.0))
        assertEquals(2, suggestedHalvings(96.0))
        assertEquals(0, suggestedHalvings(24.0))
    }
}

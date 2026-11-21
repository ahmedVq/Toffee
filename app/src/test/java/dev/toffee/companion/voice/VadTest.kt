package dev.toffee.companion.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When he has stopped talking.
 *
 * [DeviceEars] gets this from the recogniser for free. [CloudEars] records the
 * audio itself, so it has to decide, and the alternative — a fixed six-second
 * window — makes every exchange feel like leaving a voicemail.
 */
class VadTest {

    private fun vad() = Vad()

    /** Feeds a constant level for a span and returns the moment it said stop,
     *  or null. */
    private fun run(v: Vad, rms: Double, fromMs: Long, toMs: Long, stepMs: Long = 100): Long? {
        var t = fromMs
        while (t < toMs) {
            if (v.offer(rms, t)) return t
            t += stepMs
        }
        return null
    }

    @Test
    fun `silence throughout stops at the ceiling`() {
        val v = vad()
        assertFalse("it must wait, in case he is slow to start", run(v, 0.0, 0, 9_000) != null)
        val stopped = run(v, 0.0, 9_000, 12_000)
        assertTrue("a tap he never spoke into cannot record for ever", stopped != null)
    }

    @Test
    fun `speech then silence stops after the hangover`() {
        val v = vad()
        assertTrue("talking is not stopping", run(v, 5_000.0, 0, 2_000) == null)

        val stopped = run(v, 0.0, 2_000, 6_000)
        assertTrue("it must end the recording once he stops", stopped != null)
        assertTrue(
            "it waited ${stopped!! - 2_000}ms, which is not the hangover",
            stopped - 2_000 in 1_000..2_000,
        )
    }

    // The whole reason this class exists rather than a fixed window.
    @Test
    fun `a breath does not end the sentence`() {
        val v = vad()
        assertTrue(run(v, 5_000.0, 0, 1_000) == null)
        assertTrue("half a second of thinking is not the end of a sentence", run(v, 0.0, 1_000, 1_500) == null)
        assertTrue(run(v, 5_000.0, 1_500, 2_500) == null)
    }

    @Test
    fun `it never stops before he has started`() {
        val v = vad()
        // Three seconds of nothing, then he speaks. The quiet before the
        // first word is not a hangover, or the recording ends before it began.
        assertTrue(run(v, 0.0, 0, 3_000) == null)
        assertTrue(run(v, 5_000.0, 3_000, 4_000) == null)
        assertTrue("the hangover is measured from the last word", run(v, 0.0, 4_000, 4_500) == null)
    }

    @Test
    fun `a level between the two thresholds neither starts nor ends it`() {
        val v = Vad(startRms = 600.0, stopRms = 400.0)
        // Hysteresis: room tone sitting at 500 must not trip the start, and
        // once speaking, must not be mistaken for silence either.
        assertTrue(run(v, 500.0, 0, 3_000) == null)
        assertTrue(run(v, 5_000.0, 3_000, 3_500) == null)
        assertTrue("room tone is not silence once he is talking", run(v, 500.0, 3_500, 6_000) == null)
    }
}

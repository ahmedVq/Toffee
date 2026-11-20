package dev.toffee.companion.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake curve, from `@keyframes cf-wake` in face-style.html.
 *
 * The spec: "On boot: eyes shut, then opening. This is the first thing anyone
 * sees it do and should be unhurried — roughly 2 seconds, with a slight
 * overshoot before settling."
 */
class WakeTest {

    @Test
    fun `the eyes start shut`() {
        assertEquals(0.025f, Wake.scaleYAt(0), 0.001f)
    }

    @Test
    fun `it holds shut for most of the first second`() {
        // 42% of 1900ms. The pause before the eyes open is what makes it read
        // as waking rather than as a loading spinner.
        assertEquals(0.025f, Wake.scaleYAt(798), 0.002f)
        assertTrue("it opened too early", Wake.scaleYAt(600) < 0.1f)
    }

    @Test
    fun `the wake overshoots before settling`() {
        assertEquals(1.06f, Wake.scaleYAt(1102), 0.02f)
        assertEquals(0.88f, Wake.scaleYAt(1368), 0.02f)
        assertEquals(1.0f, Wake.scaleYAt(1900), 0.001f)

        val peak = (0..1900 step 10).maxOf { Wake.scaleYAt(it.toLong()) }
        assertTrue("there must be an overshoot above 1.0, got $peak", peak > 1.0f)
    }

    @Test
    fun `it settles and stays settled`() {
        assertEquals(1.0f, Wake.scaleYAt(1900), 0.001f)
        assertEquals(1.0f, Wake.scaleYAt(5_000), 0.001f)
        assertEquals(1.0f, Wake.scaleYAt(3_600_000), 0.001f)
    }

    @Test
    fun `the wake is unhurried`() {
        assertEquals(1900L, Wake.DURATION_MS)
    }

    @Test
    fun `the right eye lags the left`() {
        assertEquals(120L, Wake.RIGHT_DELAY_MS)
        assertTrue(
            "the lag must be visible but not a stagger",
            Wake.RIGHT_DELAY_MS in 60L..250L,
        )
    }

    @Test
    fun `brightness rises past full and settles`() {
        assertEquals(0.35f, Wake.brightnessAt(0), 0.01f)
        assertTrue(
            "the eyes must flare as they open",
            (0..1900 step 10).maxOf { Wake.brightnessAt(it.toLong()) } > 1.0f,
        )
        assertEquals(1.0f, Wake.brightnessAt(1900), 0.01f)
        assertEquals(1.0f, Wake.brightnessAt(10_000), 0.01f)
    }

    @Test
    fun `nothing goes negative or inverts`() {
        for (t in -500..3000 step 10) {
            val s = Wake.scaleYAt(t.toLong())
            val b = Wake.brightnessAt(t.toLong())
            assertTrue("scaleY went non-positive at ${t}ms: $s", s > 0f)
            assertTrue("brightness went non-positive at ${t}ms: $b", b > 0f)
        }
    }
}

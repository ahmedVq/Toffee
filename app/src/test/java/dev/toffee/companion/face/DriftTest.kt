package dev.toffee.companion.face

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Burn-in. The spec calls it "a real risk on a ten-year-old panel showing two
 * bright shapes in fixed positions for months", and the mitigation is that
 * "the whole face drifts a few pixels on a slow cycle".
 *
 * These tests simulate a day, because a bug here does not show up for weeks
 * and then shows up permanently.
 */
class DriftTest {

    private val day = 24L * 60 * 60 * 1000
    private val minute = 60_000L

    @Test
    fun `the drift stays within a few pixels`() {
        var t = 0L
        while (t < day) {
            val p = Drift.offsetAt(t)
            assertTrue(
                "drifted to (${p.x}, ${p.y}) at ${t}ms, outside +/-${Drift.AMPLITUDE_DP}dp",
                abs(p.x) <= Drift.AMPLITUDE_DP + 0.001f && abs(p.y) <= Drift.AMPLITUDE_DP + 0.001f,
            )
            t += minute
        }
    }

    @Test
    fun `the amplitude is a few pixels, not a wander`() {
        assertTrue("too small to help", Drift.AMPLITUDE_DP >= 6f)
        assertTrue("the face would visibly roam", Drift.AMPLITUDE_DP <= 20f)
    }

    @Test
    fun `nothing sits on the same pixel for hours`() {
        var run = 0
        var worst = 0
        var prev: Pair<Int, Int>? = null
        var t = 0L
        while (t < day) {
            val p = Drift.offsetAt(t)
            val key = p.x.roundToInt() to p.y.roundToInt()
            run = if (key == prev) run + 1 else 0
            worst = max(worst, run)
            prev = key
            t += minute
        }
        assertTrue("the face held one position for $worst minutes", worst < 15)
    }

    @Test
    fun `the drift spreads the load around`() {
        val seen = mutableSetOf<Pair<Int, Int>>()
        var t = 0L
        while (t < day) {
            val p = Drift.offsetAt(t)
            seen += p.x.roundToInt() to p.y.roundToInt()
            t += minute
        }
        assertTrue("only ${seen.size} distinct positions in a day", seen.size > 150)
    }

    @Test
    fun `it does not retrace the same path every hour`() {
        // Two coprime periods, so x and y do not come back into phase quickly.
        // If they did, the same few hundred pixels would take the whole load.
        val firstHour = mutableSetOf<Pair<Int, Int>>()
        val wholeDay = mutableSetOf<Pair<Int, Int>>()
        var t = 0L
        while (t < day) {
            val p = Drift.offsetAt(t)
            val key = p.x.roundToInt() to p.y.roundToInt()
            wholeDay += key
            if (t < 60 * minute) firstHour += key
            t += minute
        }
        assertTrue(
            "a day covered ${wholeDay.size} positions and the first hour ${firstHour.size}; " +
                "the pattern is repeating",
            wholeDay.size > firstHour.size * 2,
        )
    }

    @Test
    fun `the motion is too slow to notice`() {
        // Anything faster than about a pixel a second is a twitch, not drift.
        var t = 0L
        while (t < 60 * minute) {
            val a = Drift.offsetAt(t)
            val b = Drift.offsetAt(t + 1000)
            assertTrue(
                "moved ${abs(b.x - a.x)}dp in a second at ${t}ms",
                abs(b.x - a.x) < 1f && abs(b.y - a.y) < 1f,
            )
            t += 10_000
        }
    }
}

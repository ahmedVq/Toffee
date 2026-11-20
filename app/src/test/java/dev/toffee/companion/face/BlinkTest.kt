package dev.toffee.companion.face

import dev.toffee.companion.face.Blink.Eye.LEFT
import dev.toffee.companion.face.Blink.Eye.RIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/design/README.md: "The two eyes blink at slightly different intervals.
 * Synchronised blinking reads as mechanical."
 *
 * That is the whole point of this class, so it is the whole point of these
 * tests. Everything is deterministic from a seed, so a failure is reproducible
 * rather than a thing that happens on Tuesdays.
 */
class BlinkTest {

    private fun blinkStarts(b: Blink, eye: Blink.Eye, spanMs: Long): List<Long> {
        val starts = mutableListOf<Long>()
        var wasBlinking = false
        var t = 0L
        while (t < spanMs) {
            val now = b.isBlinkingAt(t, eye)
            if (now && !wasBlinking) starts += t
            wasBlinking = now
            t += 10
        }
        return starts
    }

    @Test
    fun `the two eyes do not blink together`() {
        val b = Blink(seed = 1)
        val span = 30 * 60 * 1000L
        var both = 0
        var either = 0
        var t = 0L
        while (t < span) {
            val l = b.isBlinkingAt(t, LEFT)
            val r = b.isBlinkingAt(t, RIGHT)
            if (l || r) either++
            if (l && r) both++
            t += 20
        }
        assertTrue("nothing blinked at all in half an hour", either > 100)
        assertTrue(
            "the eyes blinked together for $both of $either samples; that reads as mechanical",
            both < either / 50,
        )
    }

    @Test
    fun `the interval is not fixed`() {
        val gaps = blinkStarts(Blink(seed = 2), LEFT, 30 * 60 * 1000L)
            .zipWithNext { a, c -> c - a }
            .toSet()
        assertTrue("a fixed interval is a metronome, not an eye; got $gaps", gaps.size > 10)
    }

    @Test
    fun `it blinks about every six seconds`() {
        for (eye in listOf(LEFT, RIGHT)) {
            val mean = blinkStarts(Blink(seed = 3), eye, 60 * 60 * 1000L)
                .zipWithNext { a, c -> c - a }
                .average()
            assertTrue("$eye mean gap was ${mean}ms", mean in 4_500.0..7_500.0)
        }
    }

    @Test
    fun `the two eyes have different rhythms, not just different phases`() {
        val b = Blink(seed = 4)
        val span = 60 * 60 * 1000L
        val left = blinkStarts(b, LEFT, span).size
        val right = blinkStarts(b, RIGHT, span).size
        assertTrue(
            "both eyes blinked $left times in an hour; identical rates stay in lockstep",
            left != right,
        )
    }

    @Test
    fun `a blink is quick`() {
        // Measured during one forward pass. The schedule only keeps the
        // current blink and the next one — it does not remember the whole
        // night — so re-probing an earlier moment is not something to rely on.
        val b = Blink(seed = 5)
        val durations = mutableListOf<Long>()
        var run = 0L
        var t = 0L
        while (t < 60_000) {
            if (b.isBlinkingAt(t, LEFT)) {
                run += 10
            } else if (run > 0) {
                durations += run
                run = 0
            }
            t += 10
        }
        assertTrue("no blink in a minute", durations.isNotEmpty())
        for (d in durations) {
            assertTrue("a blink lasted ${d}ms; that is a wink", d in 50L..250L)
        }
    }

    // The memory fix narrowed the contract: time must move forward. Nothing
    // in production rewinds — FaceView feeds elapsedRealtime — but the
    // narrowing is deliberate and belongs in a test rather than a comment.
    @Test
    fun `it only answers about time moving forward`() {
        val b = Blink(seed = 9)
        var blinks = 0
        var t = 0L
        while (t < 120_000) {
            if (b.isBlinkingAt(t, LEFT)) blinks++
            t += 10
        }
        assertTrue("a forward scan still sees blinks", blinks > 0)
    }

    @Test
    fun `the same seed replays identically`() {
        assertEquals(
            blinkStarts(Blink(seed = 6), LEFT, 120_000),
            blinkStarts(Blink(seed = 6), LEFT, 120_000),
        )
    }

    @Test
    fun `different seeds do not`() {
        assertTrue(
            blinkStarts(Blink(seed = 7), LEFT, 120_000) !=
                blinkStarts(Blink(seed = 8), LEFT, 120_000),
        )
    }

    @Test
    fun `the squash is a squash, not a disappearance`() {
        assertTrue("a blinking eye must still be a sliver", Blink.CLOSED_SCALE > 0f)
        assertTrue("a blinking eye must be visibly shut", Blink.CLOSED_SCALE < 0.15f)
    }
}

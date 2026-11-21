package dev.toffee.companion.voice

import dev.toffee.companion.face.Mood
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spec gives the voice two fixed points and leaves the rest to the
 * builder: "Pitch and rate carry mood: low and slow for menace, clipped for
 * irritation."
 *
 * So these tests pin the relationships, not the numbers. A table of nine
 * magic floats asserted against itself would prove only that someone typed it
 * twice.
 */
class ProsodyTest {

    @Test
    fun smugIsLowerAndSlowerThanNeutral() {
        val smug = Prosody.forMood(Mood.SMUG)
        val neutral = Prosody.forMood(Mood.NEUTRAL)
        assertTrue(
            "menace is low and slow: $smug against $neutral",
            smug.pitch < neutral.pitch && smug.rate < neutral.rate,
        )
    }

    @Test
    fun frustratedIsClipped() {
        assertTrue(
            "irritation is clipped",
            Prosody.forMood(Mood.FRUSTRATED).rate > Prosody.forMood(Mood.NEUTRAL).rate,
        )
    }

    @Test
    fun sleepyIsTheSlowestThingItSays() {
        val sleepy = Prosody.forMood(Mood.SLEEPY)
        for (m in Mood.values()) {
            assertTrue("$m is slower than sleepy", sleepy.rate <= Prosody.forMood(m).rate)
        }
    }

    @Test
    fun everyMoodIsSayableByAnEngine() {
        // TextToSpeech clamps anything outside this range rather than
        // failing, which would flatten the mood silently — exactly the kind
        // of bug that never surfaces as an error.
        for (m in Mood.values()) {
            val p = Prosody.forMood(m)
            assertTrue("$m pitch out of range: $p", p.pitch in 0.5f..2.0f)
            assertTrue("$m rate out of range: $p", p.rate in 0.5f..2.0f)
        }
    }
}

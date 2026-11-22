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

    /**
     * The fault Ahmed heard: "the voice changes with each reply".
     *
     * It was never switching engines — it binds one and keeps it. It was this
     * table. A 15% pitch shift on a synthesiser is not a mood, it is a
     * different speaker, and he was listening to a cast of them.
     */
    @Test
    fun everyMoodIsRecognisablyTheSameSpeaker() {
        val pitches = Mood.values().map { Prosody.forMood(it).pitch }
        val span = pitches.maxOrNull()!! - pitches.minOrNull()!!
        assertTrue(
            "pitch spans $span across the moods; this wide is a different " +
                "speaker, not a different mood",
            span <= 0.10f,
        )
    }

    /** Narrow is not flat. A voice that sounds identical in every mood has
     *  stopped carrying mood at all, which is the opposite failure. */
    @Test
    fun moodStillChangesHowSheSounds() {
        val distinct = Mood.values().map { Prosody.forMood(it) }.toSet()
        assertTrue("only ${distinct.size} distinct voices across the moods", distinct.size >= 5)
    }

    /**
     * Which dial does the work.
     *
     * People carry mood in timing far more than in pitch. Pitch is also the
     * dial that destroys identity, so the one that moves must be rate.
     */
    @Test
    fun rateCarriesMoodMoreThanPitchDoes() {
        val smug = Prosody.forMood(Mood.SMUG)
        val neutral = Prosody.forMood(Mood.NEUTRAL)
        assertTrue(
            "menace must be carried by slowness, not by dropping the pitch: " +
                "$smug against $neutral",
            (neutral.rate - smug.rate) > (neutral.pitch - smug.pitch),
        )
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

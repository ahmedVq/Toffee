package dev.toffee.companion.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as being called, as opposed to being talked about.
 *
 * The recogniser behind this is a 40MB model that has never heard the word
 * "Toffee" used as a name, so near misses are the normal case rather than
 * the edge case. Being generous about them is the difference between a wake
 * word that works and one Ahmed gives up on — but every variant accepted is
 * also a way for him to wake it by accident, and the worst accident is
 * Toffee waking himself on his own voice coming back through the microphone.
 */
class WakePhraseTest {

    @Test
    fun hisNameWakesHim() {
        assertTrue(WakePhrase.heard("hey toffee"))
    }

    @Test
    fun caseAndSurroundingSpaceDoNotMatter() {
        assertTrue(WakePhrase.heard("  Hey Toffee  "))
        assertTrue(WakePhrase.heard("HEY TOFFEE"))
    }

    /**
     * The one mishearing that is real.
     *
     * An earlier version of this test listed "hey toffy", "hey toffi" and
     * "hey tophie" as near misses to accept. The device disproved them: a
     * grammar-restricted recogniser can only emit words in its vocabulary,
     * and it logged "Ignoring word missing in vocabulary" for every one of
     * those spellings. They could never arrive, so accepting them was
     * decoration.
     *
     * "coffee" is in the vocabulary, is what the model reaches for when it
     * hears his name, and therefore is the only mishearing worth handling.
     */
    @Test
    fun theOneMishearingThatCanActuallyArriveCounts() {
        assertTrue(WakePhrase.heard("hey coffee"))
    }

    @Test
    fun heMayBeCalledWithoutTheHey() {
        assertTrue(WakePhrase.heard("toffee"))
        assertTrue(WakePhrase.heard("toffee, what time is it"))
    }

    /**
     * Review Focus 1. His own voice reaches his own microphone, and his
     * persona is specifically prone to repeating back what was said to him.
     * Being named in the middle of a sentence is being discussed, not called.
     */
    @Test
    fun beingTalkedAboutDoesNotWakeHim() {
        for (said in listOf(
            "you said hey toffee twice already",
            "i would like a toffee apple",
            "the toffee is in the drawer",
            "stop saying hey toffee",
        )) {
            assertFalse(said, WakePhrase.heard(said))
        }
    }

    @Test
    fun anEmptyOrBlankResultIsNotAWake() {
        assertFalse(WakePhrase.heard(""))
        assertFalse(WakePhrase.heard("   "))
    }

    @Test
    fun anUnrelatedSentenceDoesNotWakeHim() {
        for (said in listOf("what time is it", "the quick brown fox", "coffee")) {
            assertFalse(said, WakePhrase.heard(said))
        }
    }

    /** Punctuation is not something the recogniser emits, but a stray comma
     *  or full stop must not be the thing that stops him answering. */
    @Test
    fun punctuationDoesNotStopHimAnswering() {
        assertTrue(WakePhrase.heard("hey, toffee"))
        assertTrue(WakePhrase.heard("hey toffee."))
    }
}

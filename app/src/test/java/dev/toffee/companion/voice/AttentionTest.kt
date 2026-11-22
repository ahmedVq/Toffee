package dev.toffee.companion.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who holds the microphone, and in what order.
 *
 * This is the test the review said cost the most to lose. The handover used
 * to be three lines of ordering inside an Activity, which meant the only way
 * to check it was to stand in front of the tablet — and because only one
 * thing on this device can record at a time, a test that tried to check it
 * from outside competed with the app instead of observing it.
 *
 * So the ordering moved here, where a fake records it. Three faults the
 * review found live in these transitions: the microphone reopening over his
 * own voice, two conversations running at once and handing the microphone
 * back under each other, and the wake listener being asked to stop while it
 * was still loading.
 */
class AttentionTest {

    private class FakeWakeWord : WakeWord {
        val calls = mutableListOf<String>()
        override var listening = false
            private set

        override fun start() {
            calls.add("start")
            listening = true
        }

        override fun stop() {
            calls.add("stop")
            listening = false
        }

        override fun pause(paused: Boolean) {
            calls.add(if (paused) "pause" else "resume")
        }
    }

    private val wake = FakeWakeWord()
    private val attention = Attention(wake)

    @Test
    fun withNothingHappeningItListensForHisName() {
        attention.reconcile(speaking = false, conversing = false)
        assertEquals(listOf("start", "resume"), wake.calls)
    }

    /**
     * The conversation needs the microphone itself — Vosk holding it is
     * precisely why the recogniser that transcribes Ahmed gets silence.
     */
    @Test
    fun aConversationTakesTheMicrophoneAway() {
        attention.reconcile(speaking = false, conversing = false)
        wake.calls.clear()

        attention.reconcile(speaking = false, conversing = true)
        assertEquals(listOf("stop"), wake.calls)
    }

    /**
     * While he talks it stops decoding but keeps the microphone.
     *
     * Stopping outright would mean releasing and re-acquiring the recorder
     * on every single thing he says, and would leave the microphone
     * momentarily free for something else to take. Pausing is both cheaper
     * and tighter.
     */
    @Test
    fun whileHeTalksItStopsDecodingButKeepsTheMicrophone() {
        attention.reconcile(speaking = false, conversing = false)
        wake.calls.clear()

        attention.reconcile(speaking = true, conversing = false)
        assertEquals(listOf("pause"), wake.calls)
    }

    @Test
    fun whenHeStopsTalkingItListensAgain() {
        attention.reconcile(speaking = false, conversing = false)
        attention.reconcile(speaking = true, conversing = false)
        wake.calls.clear()

        attention.reconcile(speaking = false, conversing = false)
        assertEquals(listOf("resume"), wake.calls)
    }

    /** The conversation wins: inside one, the ears own the microphone
     *  whether or not he happens to be talking at that moment. */
    @Test
    fun talkingInsideAConversationDoesNotTakeTheMicrophoneBack() {
        attention.reconcile(speaking = false, conversing = true)
        wake.calls.clear()

        attention.reconcile(speaking = true, conversing = true)
        assertEquals(emptyList<String>(), wake.calls)
    }

    /**
     * The end of a conversation is the race the review found: the listener
     * is restarted the instant the loop exits, while he may still be
     * speaking the last reply. It must come back, but come back deaf.
     */
    @Test
    fun aConversationEndingWhileHeTalksLeavesItHoldingButPaused() {
        attention.reconcile(speaking = false, conversing = true)
        wake.calls.clear()

        attention.reconcile(speaking = true, conversing = false)
        assertEquals(listOf("start", "pause"), wake.calls)
    }

    @Test
    fun sayingTheSameThingTwiceDoesNothingTheSecondTime() {
        attention.reconcile(speaking = false, conversing = false)
        wake.calls.clear()

        attention.reconcile(speaking = false, conversing = false)
        attention.reconcile(speaking = false, conversing = false)
        assertEquals(emptyList<String>(), wake.calls)
    }

    /** Two conversations overlapping used to hand the microphone back under
     *  the one still running. Asking twice is not a second handover. */
    @Test
    fun askingForTheConversationTwiceOnlyTakesItOnce() {
        attention.reconcile(speaking = false, conversing = true)
        attention.reconcile(speaking = false, conversing = true)
        assertEquals(listOf("stop"), wake.calls)
    }

    @Test
    fun theWholeLifeOfOneConversationInOrder() {
        attention.reconcile(speaking = false, conversing = false) // idle
        attention.reconcile(speaking = false, conversing = true) // woken
        attention.reconcile(speaking = true, conversing = true) // answering
        attention.reconcile(speaking = false, conversing = true) // listening again
        attention.reconcile(speaking = false, conversing = false) // lapsed

        assertEquals(listOf("start", "resume", "stop", "start", "resume"), wake.calls)
    }
}

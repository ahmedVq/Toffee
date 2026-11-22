package dev.toffee.companion.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a conversation is still a conversation.
 *
 * Ahmed's second complaint: "i can't have a conversation if i tap it in each
 * reply". So a tap, or his name, opens one — and it stays open while things
 * are still being said, closing itself quietly when they stop.
 *
 * Pure, and all times are [android.os.SystemClock.elapsedRealtime] values
 * rather than wall clock. The wall clock jumps when the RTC is corrected
 * after a power cut, which on this tablet has already broken one timer.
 */
class ConversationTest {

    @Test
    fun itStartsClosed() {
        assertFalse(Conversation().isOpen(0))
    }

    @Test
    fun openingItOpensIt() {
        val c = Conversation()
        c.open(1_000)
        assertTrue(c.isOpen(1_000))
    }

    @Test
    fun silenceForLongEnoughClosesIt() {
        val c = Conversation()
        c.open(0)
        assertTrue(c.isOpen(Conversation.IDLE_AFTER_MS - 1))
        assertFalse(c.isOpen(Conversation.IDLE_AFTER_MS + 1))
    }

    @Test
    fun everyExchangeKeepsItAlive() {
        val c = Conversation()
        c.open(0)
        c.exchange(80_000)
        assertTrue("an exchange should have renewed it", c.isOpen(140_000))
        assertFalse(c.isOpen(80_000 + Conversation.IDLE_AFTER_MS + 1))
    }

    /**
     * Review Focus 4, and the first version of this test had it wrong.
     *
     * It asserted that an exchange arriving 94 seconds after the last one
     * kept the conversation alive — which directly contradicts
     * [anExchangeDoesNotReopenALapsedConversation] below, and both cannot
     * hold. Ninety-four seconds with nothing said IS an idle conversation;
     * there is no way to tell that gap from Ahmed walking away.
     *
     * The actual protection is that a long answer is not a gap. Toffee
     * streams a sentence at a time, and every sentence is an exchange, so
     * an answer of any length renews the conversation as it is delivered.
     * That is a requirement on the caller, and this is where it is pinned.
     */
    @Test
    fun aLongAnswerKeepsTheConversationAliveAsItIsDelivered() {
        val c = Conversation()
        c.open(0)
        c.exchange(1_000) // he spoke

        // A very long answer, a sentence every few seconds.
        var at = 5_000L
        while (at <= 95_000L) {
            c.exchange(at)
            at += 5_000L
        }

        assertTrue("closed while he was still answering", c.isOpen(96_000))
    }

    @Test
    fun closingItClosesItEvenInsideTheWindow() {
        val c = Conversation()
        c.open(0)
        c.close()
        assertFalse(c.isOpen(1))
    }

    @Test
    fun closingTwiceIsHarmless() {
        val c = Conversation()
        c.open(0)
        c.close()
        c.close()
        assertFalse(c.isOpen(1))
    }

    @Test
    fun itCanBeOpenedAgainAfterClosing() {
        val c = Conversation()
        c.open(0)
        c.close()
        c.open(200_000)
        assertTrue(c.isOpen(200_000))
    }

    /** A stale exchange must not resurrect a conversation that already
     *  lapsed — otherwise a reply arriving late reopens the microphone
     *  minutes after he walked away. */
    @Test
    fun anExchangeDoesNotReopenALapsedConversation() {
        val c = Conversation()
        c.open(0)
        c.exchange(Conversation.IDLE_AFTER_MS + 5_000)
        assertFalse(
            "a late exchange reopened a conversation that had already ended",
            c.isOpen(Conversation.IDLE_AFTER_MS + 5_001),
        )
    }

    @Test
    fun anExchangeOnAConversationThatWasNeverOpenedDoesNothing() {
        val c = Conversation()
        c.exchange(1_000)
        assertFalse(c.isOpen(1_000))
    }

    /**
     * One listen must fit comfortably inside the idle window, or the
     * conversation could never lapse: each attempt would outlast the
     * patience that is supposed to end it.
     *
     * This is the real relationship. The design named a separate follow-on
     * constant, which turned out to configure nothing — the recogniser's own
     * ceiling bounds each listen, and this class only decides whether to open
     * it again.
     */
    @Test
    fun oneListenFitsInsideTheIdleWindow() {
        assertTrue(
            "a single listen outlasts the conversation's whole patience",
            DeviceEars.LISTEN_TIMEOUT_MS < Conversation.IDLE_AFTER_MS,
        )
    }
}

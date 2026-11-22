package dev.toffee.companion.voice

/**
 * Whether a conversation is still going on.
 *
 * Ahmed's second complaint after using the tablet: "i can't have a
 * conversation if i tap it in each reply". Tapping to start is one tap you
 * pay once; tapping for every single answer is operating a device rather
 * than talking to someone.
 *
 * So a tap — or his name, once the wake word exists — opens a conversation,
 * and inside one the microphone reopens by itself after each thing Toffee
 * says. It closes when nothing has been said for [idleAfterMs], and closes
 * in silence: a companion that announces it is leaving the conversation is a
 * companion that talks too much, which is the first anti-goal in the design.
 *
 * Pure, so the timing rules are unit tests rather than something checked by
 * standing in front of the tablet for ninety seconds.
 *
 * Every time is an [android.os.SystemClock.elapsedRealtime] value, never the
 * wall clock. The wall clock jumps when the RTC is corrected after a power
 * cut, and on this tablet that has already broken one timer.
 */
class Conversation(private val idleAfterMs: Long = IDLE_AFTER_MS) {

    /** When this conversation was opened, or null if none is open. */
    var openedAtMs: Long? = null
        private set

    /** When something was last said, by either of them. */
    var lastExchangeMs: Long? = null
        private set

    /** A tap, or his name. */
    fun open(atMs: Long) {
        openedAtMs = atMs
        lastExchangeMs = atMs
    }

    /**
     * Something was said.
     *
     * Called when Ahmed speaks and again when Toffee has finished
     * answering. The second call is what stops the time he spent talking
     * from counting as silence.
     *
     * The limit, stated plainly because it is easy to assume otherwise:
     * nothing renews the conversation DURING an answer, so a single answer
     * longer than [idleAfterMs] ends the conversation once it finishes.
     * Nothing here could distinguish that from Ahmed walking away mid-reply.
     * It is not reachable today — the persona holds him to one or two
     * sentences — and if it ever is, the fix is to renew per sentence as
     * they stream, which [exchange] already supports.
     *
     * Ignored when no conversation is open, and ignored when the one that
     * was open has already lapsed: a reply arriving late must not reopen the
     * microphone minutes after he walked away.
     */
    fun exchange(atMs: Long) {
        if (!isOpen(atMs)) return
        lastExchangeMs = atMs
    }

    /** End it now: he walked off, or he interrupted, or it is over. */
    fun close() {
        openedAtMs = null
        lastExchangeMs = null
    }

    /** Whether, at [atMs], this is still a conversation. */
    fun isOpen(atMs: Long): Boolean {
        val last = lastExchangeMs ?: return false
        return atMs - last <= idleAfterMs
    }

    companion object {
        /**
         * How long a conversation survives with nothing said in it.
         *
         * There is deliberately no second constant for "how long the
         * microphone stays open after he stops speaking". The design named
         * one, and it would have been dead configuration: each listen is
         * bounded by the recogniser's own ceiling
         * ([DeviceEars.LISTEN_TIMEOUT_MS], 12s), and this class simply
         * decides whether to open it again. A knob that configures nothing
         * is worse than no knob, because the next person tunes it and
         * nothing happens.
         */
        const val IDLE_AFTER_MS = 90_000L
    }
}

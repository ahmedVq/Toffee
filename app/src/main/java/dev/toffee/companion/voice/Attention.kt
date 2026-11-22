package dev.toffee.companion.voice

/**
 * What the microphone is wanted for at this moment.
 *
 * Only one thing on this tablet can record at a time, so this is a choice,
 * not a set. Writing it as three named states rather than as conditions
 * scattered through an Activity is the point: every bug the review found in
 * the handover was a state nobody had named.
 */
enum class MicNeed {
    /** Nothing else wants it: listen for "hey toffee". */
    HIS_NAME,

    /** A conversation is open. The recogniser that transcribes Ahmed needs
     *  the microphone to itself, so the wake listener must let go of it. */
    THE_CONVERSATION,

    /** He is talking. Nothing should be listening — least of all the thing
     *  that reacts to hearing his name, in his own voice, out of his own
     *  speaker. */
    NOBODY,
}

/**
 * Who holds the microphone, kept honest.
 *
 * The handover used to live as ordering inside `MainActivity`: `wake.stop()`
 * before listening, `wake.start()` in a `finally` after. That reads
 * correctly and was wrong in four ways at once — it reopened the ears while
 * he was still speaking so he transcribed and answered himself; a second tap
 * started a second conversation whose `finally` handed the microphone back
 * under the first; the restart raced the recogniser's asynchronous release;
 * and outside a conversation the listener ran straight through everything he
 * said.
 *
 * So the ordering is here instead, as a level-triggered reconcile rather
 * than a sequence of events. Callers say what is true — is he talking, is a
 * conversation open — and this works out what that means for the microphone.
 * Saying the same thing twice does nothing, which is what makes a duplicate
 * tap or a doubled callback harmless rather than a handover.
 *
 * Not thread-safe by design: call it from one thread (the main one). The
 * cost of getting that wrong is two threads fighting over a device that
 * only one of them can have, which is the problem this exists to end.
 */
class Attention(private val wake: WakeWord) {

    private var applied: MicNeed? = null

    /** What [speaking] and [conversing] mean for the microphone. Pure, so
     *  the rule itself can be read without the machinery around it. */
    fun needed(speaking: Boolean, conversing: Boolean): MicNeed = when {
        // A conversation outranks everything: between his sentences, Ahmed
        // is expected to answer, and the ears cannot get the microphone if
        // the wake listener is holding it.
        conversing -> MicNeed.THE_CONVERSATION
        speaking -> MicNeed.NOBODY
        else -> MicNeed.HIS_NAME
    }

    /**
     * Make the microphone match the world.
     *
     * @param speaking whether audio is coming out of the speaker right now.
     * @param conversing whether a conversation is open.
     */
    fun reconcile(speaking: Boolean, conversing: Boolean) {
        val want = needed(speaking, conversing)
        if (want == applied) return
        val wasHoldingNothing = applied == null || applied == MicNeed.THE_CONVERSATION

        when (want) {
            MicNeed.THE_CONVERSATION -> wake.stop()

            // Started and then immediately silenced. A conversation can lapse
            // while he is still delivering its last answer, and he must take
            // the microphone back then — holding it is what stops anything
            // else grabbing it — but he must not be listening to himself do
            // it.
            MicNeed.NOBODY -> {
                if (wasHoldingNothing) wake.start()
                wake.pause(true)
            }

            MicNeed.HIS_NAME -> {
                if (wasHoldingNothing) wake.start()
                wake.pause(false)
            }
        }
        applied = want
    }
}

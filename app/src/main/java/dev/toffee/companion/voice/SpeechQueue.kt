package dev.toffee.companion.voice

/**
 * Whether he is still talking, when one reply is several queued sentences.
 *
 * [Voice.speaking] decides what a tap means — interrupt him, or listen to
 * Ahmed — so getting it wrong turns a deliberate interruption into a
 * microphone opened over the top of his own voice.
 *
 * Streaming made the old answer wrong. It was set false by `onDone`, which
 * fires once per utterance, so the first sentence finishing reported him as
 * silent while the rest of the reply was still queued behind it.
 *
 * Counting outstanding utterances is the obvious fix and is also wrong:
 * flushing the queue fires a terminal callback for each sentence it
 * cancelled, and those decrements race the new sentence's increment. So this
 * remembers the last utterance handed to the engine and ignores every
 * callback that is not about it. A stale callback from a flushed sentence
 * then cannot silence the sentence that replaced it.
 *
 * Pure, and the reason it is its own class: none of the above is visible
 * from reading [AndroidVoice], and all of it is a unit test here.
 */
class SpeechQueue(
    /**
     * Called when he starts talking and again when he stops — the edges
     * only, never once per sentence of one reply.
     *
     * The wake listener is the caller that needs this. It has to be paused
     * *before* his first word reaches the microphone, and a timer that
     * notices a tick later has already let the recogniser hear the start of
     * his own sentence. Defaults to doing nothing so the many places that
     * only care about [speaking] are unchanged.
     */
    private val onChange: (speaking: Boolean) -> Unit = {},
) {

    @Volatile
    private var last: String? = null

    /** True from the moment a sentence is handed to the engine until that
     *  same sentence reports back. */
    val speaking: Boolean get() = last != null

    /** A sentence was handed to the engine. */
    fun queued(id: String) {
        val wasQuiet = last == null
        last = id
        if (wasQuiet) onChange(true)
    }

    /**
     * The engine finished, failed, or stopped an utterance.
     *
     * @param id which one. Null on API 27's deprecated `onError`, and an
     *        unidentified callback is ignored rather than assumed to be the
     *        current one — guessing wrong here mutes him mid-reply.
     */
    fun finished(id: String?) {
        if (id == null || id != last) return
        last = null
        onChange(false)
    }

    /** Everything outstanding is gone: `hush`, or a shutdown. */
    fun silenced() {
        if (last == null) return
        last = null
        onChange(false)
    }
}

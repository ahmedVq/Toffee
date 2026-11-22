package dev.toffee.companion.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether he is still talking, when a reply is several queued sentences.
 *
 * This exists because `speaking` is read by `onTap` to decide between
 * hushing him and listening to Ahmed. Streaming broke the old answer: it was
 * set false by `onDone`, which fires per utterance, so the first sentence
 * finishing reported him as silent while the second was still queued — and a
 * tap meant to interrupt would instead have opened the microphone over the
 * top of him.
 *
 * Counting outstanding utterances does not work either, because flushing the
 * queue fires a terminal callback for the sentences it cancelled, and that
 * decrement races the new sentence's increment. So this tracks the LAST
 * utterance handed to the engine and ignores callbacks for any other.
 */
class SpeechQueueTest {

    @Test
    fun silentBeforeAnythingIsSaid() {
        assertFalse(SpeechQueue().speaking)
    }

    @Test
    fun speakingOnceSomethingIsQueued() {
        val q = SpeechQueue()
        q.queued("1")
        assertTrue(q.speaking)
    }

    @Test
    fun silentOnceThatUtteranceIsDone() {
        val q = SpeechQueue()
        q.queued("1")
        q.finished("1")
        assertFalse(q.speaking)
    }

    /** The streaming case: sentence one ends while sentence two is queued. */
    @Test
    fun stillSpeakingWhileALaterSentenceIsOutstanding() {
        val q = SpeechQueue()
        q.queued("1")
        q.queued("2")
        q.finished("1")
        assertTrue("reported silent with a sentence still queued", q.speaking)
        q.finished("2")
        assertFalse(q.speaking)
    }

    /** The interrupting case: a flush cancels the old sentence, and its
     *  terminal callback arrives after the new one was queued. */
    @Test
    fun aCancelledSentencesCallbackDoesNotSilenceTheNewOne() {
        val q = SpeechQueue()
        q.queued("1")
        q.queued("2")
        q.finished("1")
        q.finished("2")

        q.queued("3")
        q.finished("2") // late, from the flush
        assertTrue("a stale callback silenced the current sentence", q.speaking)
    }

    @Test
    fun hushSilencesItImmediately() {
        val q = SpeechQueue()
        q.queued("1")
        q.queued("2")
        q.silenced()
        assertFalse(q.speaking)
    }

    /** The platform calls the deprecated onError with no id on API 27. */
    @Test
    fun aTerminalCallbackWithNoIdIsIgnoredRatherThanAssumed() {
        val q = SpeechQueue()
        q.queued("1")
        q.finished(null)
        assertTrue("an unidentified callback must not be taken for the current one", q.speaking)
    }
}

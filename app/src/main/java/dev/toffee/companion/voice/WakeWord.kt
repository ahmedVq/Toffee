package dev.toffee.companion.voice

/**
 * Listening for his name, and nothing else.
 *
 * A seam for the same reason [Ears] and [Voice] are seams: the decision of
 * what counts as being called lives in [WakePhrase], which is pure and
 * tested, and everything below here is one Android implementation that can
 * be swapped or absent.
 *
 * Absent is a real state. The wake word is an improvement on tapping, not a
 * replacement for it — if it cannot start, the tap still works and the
 * tablet is merely back to what it was yesterday.
 */
interface WakeWord {

    /**
     * Start listening for the wake phrase.
     *
     * Takes the microphone, so nothing else can record until [stop]. The
     * caller owns that handover; see [Attention], which is where the ordering
     * lives and where it is tested.
     */
    fun start()

    /** Give the microphone back. Safe to call when not listening. */
    fun stop()

    /**
     * Stop or resume decoding without giving the microphone back.
     *
     * The difference from [stop] is the whole reason both exist. While
     * Toffee is talking, his own voice reaches his own microphone and must
     * not be listened to — but releasing the recorder for every sentence
     * would mean re-acquiring it immediately afterwards, and would leave the
     * microphone briefly free for something else to take. Pausing keeps the
     * claim and drops the audio.
     *
     * Safe to call when not listening, where it does nothing.
     */
    fun pause(paused: Boolean)

    /** Whether it currently holds the microphone. */
    val listening: Boolean
}

/**
 * A wake listener that is not there.
 *
 * The default, so the tablet behaves exactly as it did before the wake word
 * existed: Ahmed taps, and nothing listens in between.
 */
object SilentWakeWord : WakeWord {
    override fun start() = Unit
    override fun stop() = Unit
    override fun pause(paused: Boolean) = Unit
    override val listening: Boolean get() = false
}

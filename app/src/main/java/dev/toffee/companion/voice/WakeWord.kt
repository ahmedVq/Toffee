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
     * caller owns that handover; see `MainActivity.converse`.
     */
    fun start()

    /** Give the microphone back. Safe to call when not listening. */
    fun stop()

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
    override val listening: Boolean get() = false
}

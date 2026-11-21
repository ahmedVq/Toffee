package dev.toffee.companion.voice

/**
 * Decides when he has stopped talking.
 *
 * [DeviceEars] gets this from the recogniser for free. [CloudEars] holds the
 * microphone itself, so it has to decide, and the obvious alternative — record
 * for a fixed six seconds — turns every exchange into leaving a voicemail.
 *
 * Two thresholds rather than one, for the same reason the presence detector
 * has two: a single level means room tone sitting right on it flips the state
 * on every buffer.
 *
 * Pure and time-driven: it is told what the level is and what time it is, and
 * never reads a clock itself.
 */
class Vad(
    /** Loud enough to count as him starting. */
    private val startRms: Double = 600.0,
    /** Quiet enough to count as him stopping. Lower than [startRms], so room
     *  tone in between is neither. */
    private val stopRms: Double = 400.0,
    /** How much quiet means the sentence is over rather than a breath. */
    private val hangoverMs: Long = 1_200,
    /** The ceiling, for a tap he never speaks into at all. */
    private val maxMs: Long = 10_000,
) {

    private var firstMs: Long? = null
    private var speaking = false
    private var quietSinceMs: Long? = null

    /**
     * Whether anything above [startRms] was ever offered.
     *
     * The caller needs this, not just "has it stopped". A tap nobody spoke
     * into stops at [maxMs] with a full ten seconds of room tone recorded,
     * and that recording is long, not short — so a guard on its length lets
     * it through to a paid transcription endpoint.
     */
    var heardSpeech: Boolean = false
        private set

    /** @return true when the recording should stop. */
    fun offer(rms: Double, atMs: Long): Boolean {
        val first = firstMs ?: atMs.also { firstMs = it }
        if (atMs - first >= maxMs) return true

        if (!speaking) {
            // The quiet before the first word is not a hangover. Without
            // this the recording ends before it has begun.
            if (rms >= startRms) {
                speaking = true
                heardSpeech = true
                quietSinceMs = null
            }
            return false
        }

        if (rms >= stopRms) {
            quietSinceMs = null
            return false
        }

        val since = quietSinceMs ?: atMs.also { quietSinceMs = it }
        return atMs - since >= hangoverMs
    }
}

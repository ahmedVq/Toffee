package dev.toffee.companion.face

import java.util.Random

/**
 * Blinking.
 *
 * From `docs/design/README.md`: "The two eyes blink at slightly different
 * intervals. Synchronised blinking reads as mechanical." That is the entire
 * reason this is a class rather than a modulo in the draw loop.
 *
 * Deterministic from a seed, so a face that looks wrong can be reproduced
 * rather than waited for.
 */
class Blink(seed: Long = DEFAULT_SEED) {

    enum class Eye { LEFT, RIGHT }

    private val schedules = mapOf(
        Eye.LEFT to Schedule(Random(seed), LEFT_PERIOD_MS),
        // A different base period, not merely a different phase: two eyes on
        // the same period stay a fixed distance apart forever, which is its
        // own kind of mechanical.
        Eye.RIGHT to Schedule(Random(seed xor 0x5DEECE66DL), RIGHT_PERIOD_MS),
    )

    fun isBlinkingAt(tMs: Long, eye: Eye): Boolean =
        schedules.getValue(eye).isBlinkingAt(tMs)

    /**
     * Two timestamps, not a list.
     *
     * This used to keep every blink since boot in an ArrayList, which grew
     * by about sixteen thousand entries per eye per day and never shrank —
     * an out-of-memory walk on a device with 780 MB free that is meant to run
     * unattended for months. Worse, [FaceView] feeds it elapsedRealtime, so
     * the first frame after a restart on a long-uptime device had to generate
     * every blink since boot before it could draw anything.
     *
     * Only the current blink and the next one matter.
     */
    private class Schedule(private val random: Random, private val periodMs: Long) {
        private var current = 0L
        private var next = 0L

        init {
            current = nextGap()
            next = current + nextGap()
        }

        private fun nextGap(): Long = periodMs + random.nextInt(JITTER_MS)

        fun isBlinkingAt(tMs: Long): Boolean {
            // Time only moves forward here; a rewind (a new Blink, a test)
            // just means the schedule starts again from where it is.
            while (next <= tMs) {
                current = next
                next += nextGap()
            }
            return tMs >= current && tMs - current < BLINK_MS
        }
    }

    companion object {
        /** How far an eye squashes. From `scaleY(.06)` in the approved CSS:
         *  a sliver, not a disappearance — an eye that vanishes reads as a
         *  dropped frame. */
        const val CLOSED_SCALE = 0.06f

        const val BLINK_MS = 110L
        private const val LEFT_PERIOD_MS = 4800L
        private const val RIGHT_PERIOD_MS = 6400L
        private const val JITTER_MS = 900
        private const val DEFAULT_SEED = 0x70FFEEL
    }
}

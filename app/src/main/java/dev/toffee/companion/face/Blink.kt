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

    private class Schedule(private val random: Random, private val periodMs: Long) {
        /** Blink start times, extended on demand and never recomputed, so
         *  asking about an earlier moment gives the same answer twice. */
        private val starts = ArrayList<Long>()
        private var next = 0L

        init {
            advance()
        }

        private fun advance() {
            next += periodMs + random.nextInt(JITTER_MS)
            starts.add(next)
        }

        fun isBlinkingAt(tMs: Long): Boolean {
            while (starts.last() <= tMs) advance()
            // The most recent start at or before t.
            val i = starts.binarySearch { it.compareTo(tMs) }
            val idx = if (i >= 0) i else -i - 2
            if (idx < 0) return false
            val start = starts[idx]
            return tMs - start < BLINK_MS
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

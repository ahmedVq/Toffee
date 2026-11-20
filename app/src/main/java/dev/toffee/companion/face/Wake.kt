package dev.toffee.companion.face

import kotlin.math.abs

/**
 * Waking up.
 *
 * The spec: "On boot: eyes shut, then opening. This is the first thing anyone
 * sees it do and should be unhurried — roughly 2 seconds, with a slight
 * overshoot before settling."
 *
 * The keyframes and the easing are lifted from `@keyframes cf-wake` in
 * face-style.html, so what plays on the tablet is what was approved.
 */
object Wake {

    const val DURATION_MS = 1900L

    /** The right eye lags, so the two do not snap open as one shutter. */
    const val RIGHT_DELAY_MS = 120L

    /** Fraction of the duration, and the value there. */
    private val SCALE = listOf(
        0.00f to 0.025f,
        0.42f to 0.025f,
        0.58f to 1.060f,
        0.72f to 0.880f,
        1.00f to 1.000f,
    )

    private val BRIGHTNESS = listOf(
        0.00f to 0.35f,
        0.42f to 0.35f,
        0.58f to 1.25f,
        1.00f to 1.00f,
    )

    fun scaleYAt(tMs: Long): Float = sample(SCALE, tMs)

    fun brightnessAt(tMs: Long): Float = sample(BRIGHTNESS, tMs)

    private fun sample(frames: List<Pair<Float, Float>>, tMs: Long): Float {
        val p = (tMs.toFloat() / DURATION_MS).coerceIn(0f, 1f)
        for (i in 0 until frames.size - 1) {
            val (p0, v0) = frames[i]
            val (p1, v1) = frames[i + 1]
            if (p <= p1) {
                if (p1 == p0) return v1
                val local = (p - p0) / (p1 - p0)
                return v0 + (v1 - v0) * ease(local)
            }
        }
        return frames.last().second
    }

    // cubic-bezier(.2, .9, .25, 1) — the CSS easing, solved for y given x.
    private const val X1 = 0.2f
    private const val Y1 = 0.9f
    private const val X2 = 0.25f
    private const val Y2 = 1.0f

    private fun bezier(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }

    private fun ease(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // Bisection: twenty halvings puts us well inside a pixel, and unlike
        // Newton it cannot wander on the near-flat stretch after the control
        // points.
        var lo = 0f
        var hi = 1f
        var t = x
        repeat(20) {
            val guess = bezier(t, X1, X2)
            if (abs(guess - x) < 1e-5f) return bezier(t, Y1, Y2)
            if (guess < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return bezier(t, Y1, Y2)
    }
}

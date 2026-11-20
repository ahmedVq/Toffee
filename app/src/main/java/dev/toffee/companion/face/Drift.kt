package dev.toffee.companion.face

import kotlin.math.sin

/** A displacement in dp. Deliberately not `android.graphics.PointF`, which is
 *  stubbed in JVM unit tests and throws — and burn-in is the one bug here that
 *  takes weeks to appear and is permanent when it does. */
data class Offset(val x: Float, val y: Float)

/**
 * Burn-in drift.
 *
 * The spec calls burn-in "a real risk on a ten-year-old panel showing two
 * bright shapes in fixed positions for months", and the mitigation is that
 * "the whole face drifts a few pixels on a slow cycle".
 *
 * Two sine waves on coprime prime periods, so x and y do not come back into
 * phase for six days. A single period, or two that share a factor, would trace
 * the same short path over and over and concentrate the wear instead of
 * spreading it.
 */
object Drift {

    const val AMPLITUDE_DP = 12f

    private const val X_PERIOD_MS = 661_000f // 11m 1s
    private const val Y_PERIOD_MS = 787_000f // 13m 7s

    fun offsetAt(tMs: Long): Offset {
        val t = tMs.toFloat()
        return Offset(
            AMPLITUDE_DP * sin(2f * Math.PI.toFloat() * t / X_PERIOD_MS),
            AMPLITUDE_DP * sin(2f * Math.PI.toFloat() * t / Y_PERIOD_MS),
        )
    }
}

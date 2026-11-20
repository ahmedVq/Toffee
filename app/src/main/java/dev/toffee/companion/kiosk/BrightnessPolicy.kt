package dev.toffee.companion.kiosk

/**
 * How bright the panel should be, given who is at the desk and what time it
 * is.
 *
 * There is no OFF, and that is the point: "The screen is never fully off
 * while powered, because presence detection and the arrival ambush both
 * depend on it being awake."
 *
 * Pure, because the decision is the part worth being sure about; applying it
 * to a window is one line in [Kiosk].
 */
object BrightnessPolicy {

    enum class Level(val windowBrightness: Float) {
        FULL(1.0f),
        DIM(0.25f),

        /** Readable from across a dark room, and not one lumen more. */
        NIGHT(0.05f),
    }

    /** Leaving the desk for a few minutes is not leaving. Dimming the moment
     *  he stands up would make the thing feel like it sulks. */
    const val ABSENT_BEFORE_DIM_MS = 10 * 60_000L

    /** Inclusive at both ends: 23:00 through 06:59. */
    const val NIGHT_FROM_HOUR = 23
    const val NIGHT_TO_HOUR = 6

    fun levelFor(
        presentSinceMs: Long?,
        lastSeenMs: Long?,
        nowMs: Long,
        localHour: Int,
    ): Level {
        val present = presentSinceMs != null
        val night = localHour >= NIGHT_FROM_HOUR || localHour <= NIGHT_TO_HOUR

        if (night) return if (present) Level.DIM else Level.NIGHT

        val awayFor = if (lastSeenMs == null) Long.MAX_VALUE else nowMs - lastSeenMs
        return if (present || awayFor < ABSENT_BEFORE_DIM_MS) Level.FULL else Level.DIM
    }
}

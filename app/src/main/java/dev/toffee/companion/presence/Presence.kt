package dev.toffee.companion.presence

/**
 * Who is at the desk, decided from a stream of face counts.
 *
 * Pure and time-driven: it is told how many faces the last frame held and
 * what time it is, and never reads a clock or a camera itself. The camera is
 * the easy half; this is where the project is won or lost, because every
 * transition costs an LLM call and an interruption, and a detector that flaps
 * turns a companion into a nuisance within an afternoon.
 *
 * The thresholds are asymmetric on purpose. Arriving has to be sustained, so
 * that someone walking past is not greeted. Leaving has to be long, because
 * looking down at the keyboard, turning to the other monitor and leaning back
 * out of frame all look exactly like an empty chair to a face detector.
 *
 * Presence is a boolean, never an identity and never a count — the spec's
 * rule, and the reason the whole thing can be done on-device with nothing but
 * a flag leaving the tablet.
 */
class Presence(
    private val arriveAfterMs: Long = ARRIVE_AFTER_MS,
    private val leaveAfterMs: Long = LEAVE_AFTER_MS,
    private val stillHereEveryMs: Long = STILL_HERE_EVERY_MS,
    private val arriveGapMs: Long = ARRIVE_GAP_MS,
) {

    sealed class Change {
        /** He sat down. */
        object Arrived : Change()

        /** He got up, after this many minutes at the desk. */
        data class Left(val afterMinutes: Int) : Change()

        /** He has not moved for this many minutes, and that is itself worth
         *  a remark. The spec's example is ninety minutes without a break. */
        data class StillHere(val minutes: Int) : Change()
    }

    var present: Boolean = false
        private set

    /** When the current sitting began, or null if nobody is there. */
    var presentSinceMs: Long? = null
        private set

    /** The last frame a face was in. Outlives a departure, because the
     *  brightness policy dims on how long he has been gone. */
    var lastSeenMs: Long? = null
        private set

    /** When the current unbroken run of sightings began. Not the same as
     *  [presentSinceMs]: this one exists before he counts as present. */
    private var runSinceMs: Long? = null
    private var lastRemarkMs: Long? = null

    /** @return the one thing that changed, or null, which is most of the time. */
    fun sample(faces: Int, atMs: Long): Change? {
        val seen = faces > 0
        if (seen) lastSeenMs = atMs

        return if (present) whileHere(seen, atMs) else whileEmpty(seen, atMs)
    }

    private fun whileEmpty(seen: Boolean, atMs: Long): Change? {
        if (!seen) {
            // One missed detection is not an empty room. FAST mode at 320x240
            // on a 2016 front camera drops faces routinely — he looks down at
            // the keyboard while pulling the chair in — and resetting the run
            // on every miss meant he was greeted minutes late or never.
            val last = lastSeenMs
            if (last == null || atMs - last > arriveGapMs) runSinceMs = null
            return null
        }
        val since = runSinceMs ?: atMs.also { runSinceMs = it }
        if (atMs - since < arriveAfterMs) return null

        present = true
        // He arrived when the run started, not when we became sure of it.
        // Otherwise every sitting is recorded as six seconds shorter than it
        // was, and "ninety minutes without a break" drifts.
        presentSinceMs = since
        lastRemarkMs = since
        runSinceMs = null
        return Change.Arrived
    }

    private fun whileHere(seen: Boolean, atMs: Long): Change? {
        val since = presentSinceMs ?: atMs

        if (seen) {
            val last = lastRemarkMs ?: since
            if (atMs - last < stillHereEveryMs) return null
            lastRemarkMs = atMs
            return Change.StillHere(((atMs - since) / 60_000L).toInt())
        }

        val last = lastSeenMs ?: atMs
        if (atMs - last < leaveAfterMs) return null

        val sat = ((last - since) / 60_000L).toInt()
        present = false
        presentSinceMs = null
        runSinceMs = null
        lastRemarkMs = null
        return Change.Left(sat)
    }

    companion object {
        /** Long enough that someone crossing the room is not greeted. */
        const val ARRIVE_AFTER_MS = 6_000L

        /**
         * Two minutes. Short enough that coming back from a coffee is an
         * arrival worth ambushing, long enough that reading something on the
         * desk is not a departure. Must stay longer than [ARRIVE_AFTER_MS]:
         * believing he has gone is the expensive mistake.
         */
        const val LEAVE_AFTER_MS = 120_000L

        /** How long a face may be missing mid-arrival before the run counts
         *  as broken. Two sampling intervals plus room to spare. */
        const val ARRIVE_GAP_MS = 10_000L

        /** The spec asks for sustained presence to be an event. The backend
         *  already buckets `still_here` by the hour, so this matches. */
        const val STILL_HERE_EVERY_MS = 3_600_000L
    }
}

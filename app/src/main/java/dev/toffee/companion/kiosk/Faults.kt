package dev.toffee.companion.kiosk

import android.content.Context

/**
 * What is broken, somewhere a person can read it.
 *
 * This tablet has no Settings app, no launcher, and — as the ledger records —
 * a ROM that does not carry the app's logs to logcat. So a subsystem that
 * fails has exactly two places it can say so: the caption, which the next
 * thing the agent says overwrites within seconds, and here.
 *
 * Found in review: the one notice that the mouth was dead lived for under ten
 * seconds and was then unrecoverable, and a camera that failed to start said
 * nothing at all. A fault recorded here outlives both, and the maintenance
 * screen shows it next to the last install result.
 *
 * Deliberately empty of logic, like [dev.toffee.companion.net.KeystoreTokenStore]:
 * a string per subsystem, last one wins, cleared when it starts working.
 */
object Faults {

    const val VOICE = "voice"
    const val CAMERA = "camera"
    const val EARS = "ears"

    fun record(context: Context, subsystem: String, message: String) {
        store(context).edit().putString(subsystem, message).apply()
    }

    /** Called when a subsystem comes back, so a fixed fault stops being
     *  reported as current. */
    fun clear(context: Context, subsystem: String) {
        store(context).edit().remove(subsystem).apply()
    }

    /** Everything currently broken, in a fixed order so the screen does not
     *  reshuffle itself between visits. */
    fun all(context: Context): List<String> =
        listOf(VOICE, CAMERA, EARS).mapNotNull { store(context).getString(it, null) }

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences("toffee.faults", Context.MODE_PRIVATE)
}

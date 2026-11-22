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

    /** The wake word. Its own subsystem because it fails independently of
     *  [EARS] — the microphone can work perfectly while the model fails to
     *  load, and the symptom is only that he never answers to his name. */
    const val WAKE = "wake"

    fun record(context: Context, subsystem: String, message: String) {
        store(context).edit().putString(subsystem, message).apply()
    }

    /** Called when a subsystem comes back, so a fixed fault stops being
     *  reported as current. */
    fun clear(context: Context, subsystem: String) {
        store(context).edit().remove(subsystem).apply()
    }

    /** What is wrong with one subsystem, or null if nothing is. Lets a test
     *  ask about the live app rather than about a copy it made itself. */
    fun current(context: Context, subsystem: String): String? =
        store(context).getString(subsystem, null)

    /**
     * Every subsystem that can record a fault, in a fixed order so the
     * screen does not reshuffle itself between visits.
     *
     * One list, because there were two: [all] used to carry its own copy and
     * [WAKE] never reached it, so the wake word recorded faults into a store
     * that nothing rendered. FaultsTest now reads the constants off this
     * class and insists they all appear here, which is a check that cannot
     * be forgotten by the person most likely to forget it.
     */
    val SUBSYSTEMS: List<String> = listOf(VOICE, CAMERA, EARS, WAKE)

    /** Everything currently broken. */
    fun all(context: Context): List<String> =
        SUBSYSTEMS.mapNotNull { store(context).getString(it, null) }

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences("toffee.faults", Context.MODE_PRIVATE)
}

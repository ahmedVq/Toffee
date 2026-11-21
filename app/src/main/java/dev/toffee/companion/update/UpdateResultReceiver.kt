package dev.toffee.companion.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * What happened to the last silent install.
 *
 * This device's app logs do not reach logcat, so without somewhere to put the
 * answer a failed install is indistinguishable from one that is still going:
 * the version on screen simply never changes and there is nothing to read.
 *
 * So the result is written where the maintenance screen can show it, which is
 * the only diagnostic surface the tablet has.
 */
class UpdateResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        val said = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Last update: installed."
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                // Device Owner should never see this. If it appears, the
                // tablet is not device owner and nobody is there to tap.
                "Last update: blocked, it wanted someone to confirm it."
            else -> "Last update failed ($status): ${message ?: "no reason given"}"
        }

        store(context).edit().putString(KEY, said).apply()
    }

    companion object {
        const val ACTION = "dev.toffee.companion.INSTALL_RESULT"
        private const val KEY = "last"

        fun store(context: Context) =
            context.applicationContext.getSharedPreferences("toffee.update", Context.MODE_PRIVATE)

        /** The last thing that happened, or null if nothing ever has. */
        fun lastResult(context: Context): String? = store(context).getString(KEY, null)
    }
}

package dev.toffee.companion.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The route that works with no network at all.
 *
 * "A reminder that only fires when the tablet happens to be awake is not a
 * reminder." The server sweep plus a poll covers the ordinary case; this
 * covers the dead router, the paused free project, and the small hours. Both
 * routes land in [ReminderInbox], which drops whichever arrives second.
 *
 * Keyed by reminder id, so rescheduling replaces rather than duplicates.
 */
class AlarmScheduler(private val context: Context) {

    fun schedule(reminder: Reminder) {
        if (reminder.firedAtMs != null) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // setExactAndAllowWhileIdle, because the whole point is the hours
        // when nothing else is happening and the device has dozed off.
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.dueAtMs,
            pendingIntent(reminder.id, reminder.text),
        )
    }

    fun cancel(id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(id, ""))
    }

    /** Called at boot and after every poll, so the alarm set always matches
     *  what the server says is still pending. */
    fun syncFrom(pending: List<Reminder>) = pending.forEach(::schedule)

    private fun pendingIntent(id: String, text: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "dev.toffee.companion.REMINDER_DUE"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
    }
}

/**
 * Hands the alarm to whoever is listening in the app.
 *
 * Deliberately does nothing else: a BroadcastReceiver cannot be unit tested
 * without Robolectric, so it carries no decisions. Everything about whether
 * to speak lives in [ReminderInbox].
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val text = intent.getStringExtra(AlarmScheduler.EXTRA_TEXT).orEmpty()
        listener?.invoke(
            Reminder(id, text, System.currentTimeMillis(), System.currentTimeMillis()),
        )
    }

    companion object {
        /** Set by the running app. Null when nothing is listening, in which
         *  case the poll will pick the reminder up instead. */
        @Volatile
        var listener: ((Reminder) -> Unit)? = null
    }
}

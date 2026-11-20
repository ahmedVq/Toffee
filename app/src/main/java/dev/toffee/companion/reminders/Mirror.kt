package dev.toffee.companion.reminders

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local mirror.
 *
 * Reminders only. The spec asks for tasks too, but nothing in this app
 * displays a task list — the agent speaks tasks rather than showing them — so
 * a task cache would be written and never read. It arrives with the first
 * screen that needs it.
 *
 * Read-only with respect to the server: the tablet mutates nothing here that
 * the agent owns. Pending reminders are kept so local alarms can be scheduled
 * with no network, and announced ids are kept so a reboot does not make it
 * nag twice.
 */
@Entity(tableName = "reminders")
data class ReminderRow(
    @PrimaryKey val id: String,
    val text: String,
    val dueAtMs: Long,
    val firedAtMs: Long?,
)

@Entity(tableName = "announced")
data class AnnouncedRow(@PrimaryKey val id: String)

@Dao
interface MirrorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertReminders(rows: List<ReminderRow>)

    @Query("SELECT * FROM reminders WHERE firedAtMs IS NULL ORDER BY dueAtMs ASC")
    fun pendingReminders(): List<ReminderRow>

    @Query("SELECT * FROM reminders WHERE id = :id")
    fun reminder(id: String): ReminderRow?

    @Query("DELETE FROM reminders WHERE firedAtMs IS NOT NULL AND firedAtMs < :beforeMs")
    fun forgetFiredBefore(beforeMs: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun markAnnounced(row: AnnouncedRow)

    @Query("SELECT COUNT(*) FROM announced WHERE id = :id")
    fun announcedCount(id: String): Int
}

@Database(entities = [ReminderRow::class, AnnouncedRow::class], version = 1, exportSchema = false)
abstract class Mirror : RoomDatabase() {
    abstract fun dao(): MirrorDao

    companion object {
        @Volatile
        private var instance: Mirror? = null

        fun get(context: Context): Mirror = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                Mirror::class.java,
                "toffee-mirror",
            ).build().also { instance = it }
        }
    }
}

/** The durable half of [ReminderInbox]. Thin on purpose: every decision about
 *  what counts as announced lives in the inbox, which is tested. */
class RoomAnnouncedStore(private val dao: MirrorDao) : AnnouncedStore {
    override fun contains(id: String): Boolean = dao.announcedCount(id) > 0
    override fun add(id: String) = dao.markAnnounced(AnnouncedRow(id))
}

fun Reminder.toRow() = ReminderRow(id, text, dueAtMs, firedAtMs)
fun ReminderRow.toReminder() = Reminder(id, text, dueAtMs, firedAtMs)

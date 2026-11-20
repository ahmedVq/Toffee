package dev.toffee.companion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.toffee.companion.reminders.AnnouncedRow
import dev.toffee.companion.reminders.Mirror
import dev.toffee.companion.reminders.Reminder
import dev.toffee.companion.reminders.ReminderInbox
import dev.toffee.companion.reminders.RoomAnnouncedStore
import dev.toffee.companion.reminders.toRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room needs a device, so the durable half of the inbox is proven here.
 *
 * The unit tests prove the de-duplication rule against an in-memory store;
 * this proves the store Ahmed's tablet will actually use does what that rule
 * assumes — chiefly that "already announced" survives the process dying,
 * which is the whole reason it is in a database rather than a Set.
 */
@RunWith(AndroidJUnit4::class)
class MirrorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: Mirror

    private fun reminder(id: String, fired: Boolean = true, dueInMs: Long = -60_000) = Reminder(
        id = id,
        text = "reminder $id",
        dueAtMs = System.currentTimeMillis() + dueInMs,
        firedAtMs = if (fired) System.currentTimeMillis() + dueInMs else null,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, Mirror::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun announcedSurvivesANewInboxOverTheSameDatabase() {
        val store = RoomAnnouncedStore(db.dao())
        val inbox = ReminderInbox(store)
        val r = reminder("abc")

        assertEquals(listOf(r), inbox.offer(ReminderInbox.Source.POLL, listOf(r)))
        inbox.markAnnounced("abc")

        // A new inbox over the same database is what a cold start sees.
        val reborn = ReminderInbox(RoomAnnouncedStore(db.dao()))
        assertEquals(
            "a reboot must not make it nag again",
            emptyList<Reminder>(),
            reborn.offer(ReminderInbox.Source.ALARM, listOf(r)),
        )
    }

    @Test
    fun markingTheSameIdTwiceIsHarmless() {
        val dao = db.dao()
        dao.markAnnounced(AnnouncedRow("abc"))
        dao.markAnnounced(AnnouncedRow("abc"))
        assertEquals(1, dao.announcedCount("abc"))
    }

    @Test
    fun pendingRemindersAreTheOnesTheAlarmNeeds() {
        val dao = db.dao()
        dao.upsertReminders(
            listOf(
                reminder("fired", fired = true).toRow(),
                reminder("pending", fired = false, dueInMs = 600_000).toRow(),
            ),
        )

        val pending = dao.pendingReminders()
        assertEquals(1, pending.size)
        assertEquals("pending", pending[0].id)
        assertNull("a fired reminder needs no alarm", pending[0].firedAtMs)
    }

    @Test
    fun upsertReplacesRatherThanDuplicating() {
        val dao = db.dao()
        dao.upsertReminders(listOf(reminder("abc", fired = false, dueInMs = 600_000).toRow()))
        dao.upsertReminders(listOf(reminder("abc", fired = true).toRow()))

        assertEquals(0, dao.pendingReminders().size)
        assertTrue("the row must still be there, just updated", dao.reminder("abc") != null)
    }

    @Test
    fun oldFiredRemindersAreForgotten() {
        val dao = db.dao()
        val ancient = Reminder("old", "old", 1_000L, 1_000L)
        dao.upsertReminders(listOf(ancient.toRow(), reminder("recent").toRow()))

        dao.forgetFiredBefore(System.currentTimeMillis() - 86_400_000)

        assertNull("a reminder from the epoch is not worth keeping", dao.reminder("old"))
        assertTrue(dao.reminder("recent") != null)
    }
}

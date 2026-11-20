package dev.toffee.companion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.toffee.companion.reminders.Mirror
import dev.toffee.companion.reminders.Reminder
import dev.toffee.companion.reminders.ReminderInbox
import dev.toffee.companion.reminders.RoomAnnouncedStore
import dev.toffee.companion.reminders.pendingOnIo
import dev.toffee.companion.reminders.toRow
import dev.toffee.companion.reminders.upsertOnIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
        // Deliberately WITHOUT allowMainThreadQueries. The production
        // database does not allow it either, and the test having granted
        // itself the permission is why nobody noticed that every DAO call in
        // the app ran on the main thread and threw.
        db = Room.inMemoryDatabaseBuilder(context, Mirror::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    // The C2 regression guard: this is exactly the call the first reminder
    // of the day makes, from exactly the dispatcher it makes it on.
    @Test
    fun theAnnouncedStoreIsSafeToCallFromTheMainThread() = runBlocking {
        val store = RoomAnnouncedStore(db.dao())
        withContext(Dispatchers.Main) {
            store.add("main-thread")
            assertTrue(store.contains("main-thread"))
        }
    }

    @Test
    fun reminderRowsAreSafeToWriteFromTheMainThread() = runBlocking {
        val dao = db.dao()
        withContext(Dispatchers.Main) {
            dao.upsertOnIo(listOf(reminder("m1", fired = false, dueInMs = 600_000).toRow()))
            assertEquals(1, dao.pendingOnIo().size)
        }
    }

    @Test
    fun announcedSurvivesANewInboxOverTheSameDatabase() = runBlocking {
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
    fun markingTheSameIdTwiceIsHarmless() = runBlocking {
        val store = RoomAnnouncedStore(db.dao())
        store.add("abc")
        store.add("abc")
        assertTrue(store.contains("abc"))
    }

    @Test
    fun pendingRemindersAreTheOnesTheAlarmNeeds() = runBlocking {
        val dao = db.dao()
        dao.upsertOnIo(
            listOf(
                reminder("fired", fired = true).toRow(),
                reminder("pending", fired = false, dueInMs = 600_000).toRow(),
            ),
        )

        val pending = dao.pendingOnIo()
        assertEquals(1, pending.size)
        assertEquals("pending", pending[0].id)
        assertNull("a fired reminder needs no alarm", pending[0].firedAtMs)
    }

    @Test
    fun upsertReplacesRatherThanDuplicating() = runBlocking {
        val dao = db.dao()
        dao.upsertOnIo(listOf(reminder("abc", fired = false, dueInMs = 600_000).toRow()))
        dao.upsertOnIo(listOf(reminder("abc", fired = true).toRow()))

        assertEquals(0, dao.pendingOnIo().size)
        assertTrue(
            "the row must still be there, just updated",
            withContext(Dispatchers.IO) { dao.reminder("abc") } != null,
        )
    }

    @Test
    fun oldFiredRemindersAreForgotten() = runBlocking {
        val dao = db.dao()
        val ancient = Reminder("old", "old", 1_000L, 1_000L)
        dao.upsertOnIo(listOf(ancient.toRow(), reminder("recent").toRow()))

        withContext(Dispatchers.IO) {
            dao.forgetFiredBefore(System.currentTimeMillis() - 86_400_000)
            assertNull("a reminder from the epoch is not worth keeping", dao.reminder("old"))
            assertTrue(dao.reminder("recent") != null)
        }
    }
}

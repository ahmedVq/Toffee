package dev.toffee.companion.reminders

import dev.toffee.companion.reminders.ReminderInbox.Source.ALARM
import dev.toffee.companion.reminders.ReminderInbox.Source.POLL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * A reminder arrives by two independent routes on purpose — the server poll
 * and a local alarm — because a reminder that only fires when the Wi-Fi
 * happens to be up is not a reminder. The price of two routes is that it
 * must be announced exactly once, and must stay announced after a reboot.
 */
class ReminderInboxTest {

    private class MemoryAnnounced : AnnouncedStore {
        val ids = mutableSetOf<String>()
        override suspend fun contains(id: String) = id in ids
        override suspend fun add(id: String) { ids += id }
    }

    private lateinit var store: MemoryAnnounced
    private lateinit var inbox: ReminderInbox

    private fun reminder(id: String, firedMinutesAgo: Long = 1) = Reminder(
        id = id,
        text = "reminder $id",
        dueAtMs = NOW - firedMinutesAgo * 60_000,
        firedAtMs = NOW - firedMinutesAgo * 60_000,
    )

    @Before
    fun setUp() {
        store = MemoryAnnounced()
        inbox = ReminderInbox(store)
    }

    @Test
    fun `the same reminder from both sources is announced once`() = runBlocking {
        val r = reminder("abc")

        assertEquals(listOf(r), inbox.offer(POLL, listOf(r)))
        inbox.markAnnounced("abc")

        assertEquals(
            "the alarm must not repeat what the poll already said",
            emptyList<Reminder>(),
            inbox.offer(ALARM, listOf(r)),
        )
    }

    @Test
    fun `the alarm wins when it arrives first`() = runBlocking {
        val r = reminder("abc")

        assertEquals(listOf(r), inbox.offer(ALARM, listOf(r)))
        inbox.markAnnounced("abc")

        assertEquals(emptyList<Reminder>(), inbox.offer(POLL, listOf(r)))
    }

    @Test
    fun `an announced reminder stays announced across a restart`() = runBlocking {
        inbox.offer(POLL, listOf(reminder("abc")))
        inbox.markAnnounced("abc")

        // Same store, new inbox: what a cold start looks like.
        val reborn = ReminderInbox(store)
        assertEquals(
            "a reboot must not make it nag again",
            emptyList<Reminder>(),
            reborn.offer(POLL, listOf(reminder("abc"))),
        )
    }

    @Test
    fun `two different reminders are both announced`() = runBlocking {
        val a = reminder("a")
        val b = reminder("b")
        assertEquals(listOf(a, b), inbox.offer(POLL, listOf(a, b)))
    }

    @Test
    fun `offering twice before marking still returns it`() = runBlocking {
        // Offering is "here is what came due"; marking is the app saying it
        // actually spoke. Losing the gap between them loses the reminder
        // whenever speaking fails.
        val r = reminder("abc")
        assertEquals(listOf(r), inbox.offer(POLL, listOf(r)))
        assertEquals(listOf(r), inbox.offer(POLL, listOf(r)))
    }

    // The backend's sweep deliberately has no lower bound — a free project
    // pauses, a router dies, and the reminder must fire late rather than
    // vanish. The tablet must not reintroduce the bound the server avoided.
    @Test
    fun `a reminder that fired four hours ago is still announced`() = runBlocking {
        val old = reminder("stale", firedMinutesAgo = 240)
        assertEquals(listOf(old), inbox.offer(POLL, listOf(old)))
    }

    @Test
    fun `a reminder that has not fired is not announced`() = runBlocking {
        val pending = Reminder("x", "later", NOW + 3_600_000, firedAtMs = null)
        assertEquals(emptyList<Reminder>(), inbox.offer(POLL, listOf(pending)))
    }

    @Test
    fun `marking something never offered is harmless`() = runBlocking {
        inbox.markAnnounced("never-seen")
        assertTrue(store.contains("never-seen"))
    }

    @Test
    fun `an empty batch announces nothing`() = runBlocking {
        assertEquals(emptyList<Reminder>(), inbox.offer(POLL, emptyList()))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

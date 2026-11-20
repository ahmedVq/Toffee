package dev.toffee.companion

import dev.toffee.companion.face.Mood
import dev.toffee.companion.net.Agent
import dev.toffee.companion.net.Reply
import dev.toffee.companion.net.SessionState
import dev.toffee.companion.reminders.AnnouncedStore
import dev.toffee.companion.reminders.Reminder
import dev.toffee.companion.reminders.ReminderInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestration, with no Android and no network in it.
 *
 * Brain decides what the face shows and which calls go out; ToffeeClient
 * decides how to talk to the server and is tested against real HTTP. Keeping
 * them apart is what makes "a second reminder arriving while it is still
 * speaking" something that can be asserted rather than hoped for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrainTest {

    private class FakeAgent : Agent {
        val turns = mutableListOf<String>()
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var reply: Reply = Reply("something", Mood.NEUTRAL)
        var delayMs: Long = 0

        override suspend fun turn(text: String): Reply {
            turns += text
            if (delayMs > 0) delay(delayMs)
            return reply
        }

        override suspend fun event(kind: String, payload: Map<String, Any?>): Reply {
            events += kind to payload
            if (delayMs > 0) delay(delayMs)
            return reply
        }
    }

    private class MemoryAnnounced : AnnouncedStore {
        val ids = mutableSetOf<String>()
        override fun contains(id: String) = id in ids
        override fun add(id: String) { ids += id }
    }

    private fun reminder(id: String) = Reminder(id, "stand up", 0L, 1L)

    @Test
    fun `on arrival it posts an arrived event and takes the mood it is given`() = runTest {
        val agent = FakeAgent().apply { reply = Reply("Back, are you.", Mood.FRUSTRATED) }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.onArrival()
        // Not advanceUntilIdle: that would also run the line's own twenty
        // second timer and the assertion would read an empty screen.
        advanceTimeBy(1_000)

        assertEquals(listOf("arrived"), agent.events.map { it.first })
        assertEquals(Mood.FRUSTRATED, brain.state.value.mood)
        assertEquals("Back, are you.", brain.state.value.line)
    }

    @Test
    fun `a reply with no line leaves the face alone and says nothing`() = runTest {
        val agent = FakeAgent().apply { reply = Reply(null, Mood.SMUG) }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)
        val before = brain.state.value.mood

        brain.onArrival()
        advanceUntilIdle()

        assertNull("silence is not a line", brain.state.value.line)
        assertEquals("a mood it chose not to express is not a mood change", before, brain.state.value.mood)
    }

    @Test
    fun `a reminder becomes a reminder_due event and is marked announced`() = runTest {
        val agent = FakeAgent().apply { reply = Reply("Stand up.", Mood.FRUSTRATED) }
        val store = MemoryAnnounced()
        val inbox = ReminderInbox(store)
        val brain = Brain(agent, inbox, { SessionState.Active }, this)

        brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1")))
        advanceTimeBy(1_000)

        assertEquals(listOf("reminder_due"), agent.events.map { it.first })
        assertEquals("r1", agent.events[0].second["id"])
        assertTrue("it must not nag about this one again", store.contains("r1"))
        assertEquals("Stand up.", brain.state.value.line)
    }

    @Test
    fun `the same reminder from the other route is not announced twice`() = runTest {
        val agent = FakeAgent()
        val inbox = ReminderInbox(MemoryAnnounced())
        val brain = Brain(agent, inbox, { SessionState.Active }, this)

        brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1")))
        advanceUntilIdle()
        brain.onReminders(ReminderInbox.Source.ALARM, listOf(reminder("r1")))
        advanceUntilIdle()

        assertEquals("one reminder, one announcement", 1, agent.events.size)
    }

    @Test
    fun `talking shows the listening face before the request goes out`() = runTest {
        val agent = FakeAgent().apply { delayMs = 5_000 }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        launch { brain.say("hello") }
        advanceTimeBy(100)

        assertTrue("the mic state must be visible immediately", brain.state.value.listening)
        assertEquals(Mood.LISTENING, brain.state.value.mood)
    }

    @Test
    fun `the listening face is dropped when the reply arrives`() = runTest {
        val agent = FakeAgent().apply {
            delayMs = 5_000
            reply = Reply("Fine.", Mood.SMUG)
        }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.say("hello")
        advanceTimeBy(6_000)

        assertFalse(brain.state.value.listening)
        assertEquals(Mood.SMUG, brain.state.value.mood)
        assertEquals("Fine.", brain.state.value.line)
    }

    @Test
    fun `an offline reply shows the sulking face and the canned line`() = runTest {
        val agent = FakeAgent().apply {
            reply = Reply("No network. I can't reach my own brain.", Mood.OFFLINE, offline = true)
        }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.say("hello")
        advanceTimeBy(1_000)

        assertEquals(Mood.OFFLINE, brain.state.value.mood)
        assertTrue(brain.state.value.line!!.isNotBlank())
        assertFalse(brain.state.value.listening)
    }

    // The spec's answer to "someone picked it up". Once the identity is
    // disabled there is nothing to say and nobody to say it to.
    @Test
    fun `a revoked session shows a fixed inert state and stops asking`() = runTest {
        val agent = FakeAgent()
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Revoked }, this)

        brain.onArrival()
        brain.say("hello")
        brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1")))
        advanceUntilIdle()

        assertEquals("a revoked tablet must not call anything", 0, agent.events.size + agent.turns.size)
        assertEquals(Mood.OFFLINE, brain.state.value.mood)
        assertTrue(brain.state.value.line!!.isNotBlank())
    }

    @Test
    fun `a signed out session does not call either`() = runTest {
        val agent = FakeAgent()
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.SignedOut }, this)

        brain.say("hello")
        advanceUntilIdle()

        assertEquals(0, agent.turns.size)
    }

    @Test
    fun `the line clears itself but the mood stays`() = runTest {
        val agent = FakeAgent().apply { reply = Reply("Stand up.", Mood.FRUSTRATED) }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.say("hello")
        advanceTimeBy(1_000)
        assertEquals("Stand up.", brain.state.value.line)

        advanceTimeBy(Brain.LINE_TTL_MS + 100)
        assertNull("a line left on screen for an hour is clutter", brain.state.value.line)
        assertEquals("the expression outlasts the sentence", Mood.FRUSTRATED, brain.state.value.mood)
    }

    // Two reminders can come due in the same minute. The second must wait its
    // turn rather than overwrite the first mid-sentence or vanish.
    @Test
    fun `a second reminder arriving mid-sentence is queued, not dropped`() = runTest {
        val agent = FakeAgent().apply { delayMs = 3_000 }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        launch { brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1"))) }
        advanceTimeBy(500)
        launch { brain.onReminders(ReminderInbox.Source.ALARM, listOf(reminder("r2"))) }
        advanceUntilIdle()

        assertEquals("both reminders must be announced", 2, agent.events.size)
        assertEquals(listOf("r1", "r2"), agent.events.map { it.second["id"] })
    }

    @Test
    fun `talking while a reminder is being announced does not interleave`() = runTest {
        val agent = FakeAgent().apply { delayMs = 3_000 }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        launch { brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1"))) }
        advanceTimeBy(500)
        launch { brain.say("shut up") }
        advanceUntilIdle()

        assertEquals(1, agent.events.size)
        assertEquals(1, agent.turns.size)
    }
}

package dev.toffee.companion

import dev.toffee.companion.face.Mood
import dev.toffee.companion.net.Agent
import dev.toffee.companion.net.Reply
import dev.toffee.companion.net.SessionState
import dev.toffee.companion.reminders.AnnouncedStore
import dev.toffee.companion.reminders.Reminder
import dev.toffee.companion.reminders.ReminderInbox
import dev.toffee.companion.voice.Voice
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
        override suspend fun contains(id: String) = id in ids
        override suspend fun add(id: String) { ids += id }
    }

    private class RecordingVoice : Voice {
        val said = mutableListOf<Pair<String, Mood>>()
        var hushed = false
        override var speaking = false
        override fun say(text: String, mood: Mood) { said += text to mood }
        override fun hush() { hushed = true }
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

    // Found in review. `ask` never throws — every network failure becomes a
    // Reply with offline=true — so marking unconditionally meant a reminder
    // that came due with the router down was spent on a generic quip and
    // then never offered again, because AnnouncedStore is durable. That is
    // the spec's named anti-goal reached by the exact route the two-path
    // design existed to close.
    @Test
    fun `an offline reminder still reads out its own words`() = runTest {
        val agent = FakeAgent().apply {
            reply = Reply("Offline. For once my silence isn't a judgement.", Mood.OFFLINE, offline = true)
        }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.onReminders(ReminderInbox.Source.ALARM, listOf(reminder("r1")))
        advanceTimeBy(1_000)

        assertEquals("stand up", brain.state.value.line)
    }

    // Seen on the device: with the model unavailable the backend answers 200
    // with "my brain's out of juice". A successful call that delivers
    // nothing, and the reminder was marked announced and lost.
    @Test
    fun `a degraded reply does not swallow the reminder`() = runTest {
        val agent = FakeAgent().apply {
            reply = Reply("My brain's out of juice for the moment.", Mood.NEUTRAL)
        }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1")))
        advanceTimeBy(1_000)

        assertTrue(
            "the reminder's own words must survive a useless answer: ${brain.state.value.line}",
            brain.state.value.line!!.contains("stand up"),
        )
    }

    @Test
    fun `an agent that already said it is not repeated`() = runTest {
        val agent = FakeAgent().apply {
            reply = Reply("Time to stand up, Ahmed. Your spine is a rumour.", Mood.SMUG)
        }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1")))
        advanceTimeBy(1_000)

        assertEquals("Time to stand up, Ahmed. Your spine is a rumour.", brain.state.value.line)
    }

    @Test
    fun `a delivered reminder is never announced twice`() = runTest {
        val agent = FakeAgent().apply { reply = Reply("Offline.", Mood.OFFLINE, offline = true) }
        val store = MemoryAnnounced()
        val inbox = ReminderInbox(store)
        val brain = Brain(agent, inbox, { SessionState.Active }, this)

        brain.onReminders(ReminderInbox.Source.ALARM, listOf(reminder("r1")))
        advanceTimeBy(1_000)
        brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1")))
        advanceTimeBy(1_000)

        assertEquals("its words reached him once; that is the promise kept", 1, agent.events.size)
        assertTrue(store.contains("r1"))
    }

    // Found in review: nothing watched the session, so a revoked tablet kept
    // whatever expression it last wore until somebody touched it.
    @Test
    fun `revocation reaches the face without anyone touching it`() = runTest {
        var state: SessionState = SessionState.Active
        val agent = FakeAgent().apply { reply = Reply("Hello.", Mood.HAPPY) }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { state }, this)

        brain.onArrival()
        advanceTimeBy(1_000)
        assertEquals(Mood.HAPPY, brain.state.value.mood)

        state = SessionState.Revoked
        brain.refreshSessionState()
        advanceTimeBy(100)

        assertEquals(Mood.OFFLINE, brain.state.value.mood)
        assertTrue(brain.state.value.line!!.isNotBlank())
    }

    @Test
    fun `refreshing an active session changes nothing`() = runTest {
        val agent = FakeAgent().apply { reply = Reply("Hello.", Mood.HAPPY) }
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.onArrival()
        advanceTimeBy(1_000)
        brain.refreshSessionState()
        advanceTimeBy(100)

        assertEquals(Mood.HAPPY, brain.state.value.mood)
        assertEquals("Hello.", brain.state.value.line)
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

    // ---------------------------------------------------------------- voice

    @Test
    fun `every line it shows is also spoken`() = runTest {
        val agent = FakeAgent().apply { reply = Reply("Back, are you.", Mood.FRUSTRATED) }
        val voice = RecordingVoice()
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this, voice)

        brain.onArrival()
        advanceTimeBy(1_000)

        assertEquals(listOf("Back, are you." to Mood.FRUSTRATED), voice.said)
    }

    @Test
    fun `silence from the governor is not spoken`() = runTest {
        val agent = FakeAgent().apply { reply = Reply(null, Mood.SMUG) }
        val voice = RecordingVoice()
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this, voice)

        brain.onArrival()
        advanceUntilIdle()

        assertEquals("a decision not to speak is not a thing to say", emptyList<Pair<String, Mood>>(), voice.said)
    }

    @Test
    fun `a reminder is spoken with its own words`() = runTest {
        val agent = FakeAgent().apply {
            reply = Reply("My brain's out of juice for the moment.", Mood.NEUTRAL)
        }
        val voice = RecordingVoice()
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this, voice)

        brain.onReminders(ReminderInbox.Source.POLL, listOf(reminder("r1")))
        advanceTimeBy(1_000)

        assertTrue(
            "a reminder that is shown but not said is a reminder he can miss: ${voice.said}",
            voice.said.single().first.contains("stand up"),
        )
    }

    // It would otherwise repeat itself for ever: refreshSessionState runs on
    // every poll, and a dead tablet is precisely one nobody is touching.
    @Test
    fun `an inert tablet says nothing out loud`() = runTest {
        val voice = RecordingVoice()
        val brain = Brain(FakeAgent(), ReminderInbox(MemoryAnnounced()), { SessionState.Revoked }, this, voice)

        brain.onArrival()
        brain.refreshSessionState()
        advanceUntilIdle()

        assertTrue("the maintenance notice is read, not shouted", voice.said.isEmpty())
        assertTrue(brain.state.value.line!!.isNotBlank())
    }

    @Test
    fun `hush stops the voice`() = runTest {
        val voice = RecordingVoice()
        val brain = Brain(FakeAgent(), ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this, voice)

        brain.hush()

        assertTrue(voice.hushed)
    }

    // ----------------------------------------------------------- listening

    @Test
    fun `while it is listening the face says so`() = runTest {
        val brain = Brain(FakeAgent(), ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.onListening()

        assertEquals(Mood.LISTENING, brain.state.value.mood)
        assertTrue("the mic being live is never ambiguous", brain.state.value.listening)
    }

    // He taps, says nothing, walks off. No turn goes out, no money is spent,
    // and the face does not sit there listening until somebody notices.
    @Test
    fun `hearing nothing costs nothing`() = runTest {
        val agent = FakeAgent()
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this)

        brain.onListening()
        brain.onHeardNothing()
        advanceUntilIdle()

        assertEquals("silence is not a turn", 0, agent.turns.size)
        assertFalse(brain.state.value.listening)
        assertEquals(Mood.NEUTRAL, brain.state.value.mood)
        assertNull(brain.state.value.line)
    }

    @Test
    fun `a tablet that cannot hear says so out loud`() = runTest {
        val voice = RecordingVoice()
        val agent = FakeAgent()
        val brain = Brain(agent, ReminderInbox(MemoryAnnounced()), { SessionState.Active }, this, voice)

        brain.onListening()
        brain.onCannotHear("no recogniser")
        advanceTimeBy(1_000)

        assertEquals("nothing was heard, so nothing is asked", 0, agent.turns.size)
        assertFalse(brain.state.value.listening)
        assertTrue(brain.state.value.line!!.isNotBlank())
        assertTrue("there is no Settings app to find this out in", voice.said.isNotEmpty())
    }
}

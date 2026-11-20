package dev.toffee.companion

import dev.toffee.companion.face.Mood
import dev.toffee.companion.net.Agent
import dev.toffee.companion.net.Reply
import dev.toffee.companion.net.SessionState
import dev.toffee.companion.reminders.Reminder
import dev.toffee.companion.reminders.ReminderInbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the screen needs to know. */
data class FaceState(
    val mood: Mood = Mood.NEUTRAL,
    val line: String? = null,
    val listening: Boolean = false,
)

/**
 * What the companion does, with no Android and no sockets in it.
 *
 * The two loops the design turns on: Ahmed says something, or something
 * happens. Both end the same way — a reply, a mood, maybe a line on screen.
 *
 * Everything that reaches the agent goes through one mutex. Two reminders can
 * come due in the same minute, and a reminder can land while he is mid-
 * sentence; without serialising, the second either overwrites the first or
 * disappears, and a reminder that disappears is the one failure a reminder
 * must never have.
 */
class Brain(
    private val agent: Agent,
    private val inbox: ReminderInbox,
    private val sessionState: () -> SessionState,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(FaceState())
    val state: StateFlow<FaceState> = _state.asStateFlow()

    private val speaking = Mutex()
    private var clearJob: Job? = null

    /** He sat down. */
    suspend fun onArrival() = ask { agent.event("arrived", emptyMap()) }

    /** He left. */
    suspend fun onDeparture() = ask { agent.event("left", emptyMap()) }

    /** Nothing happened, and that is itself worth a look at the hour and the
     *  task list. The governor decides whether it is worth saying. */
    suspend fun onIdleCheck() = ask { agent.event("idle_check", emptyMap()) }

    /** He said something. */
    suspend fun say(text: String) {
        if (!usable()) return inert()
        _state.value = _state.value.copy(mood = Mood.LISTENING, listening = true)
        ask { agent.turn(text) }
    }

    /**
     * Reminders came due, by whichever route got here first. Announced one at
     * a time and marked only after the agent has actually been asked, so a
     * failure to speak does not quietly consume the reminder.
     */
    suspend fun onReminders(source: ReminderInbox.Source, reminders: List<Reminder>) {
        if (!usable()) return inert()
        for (r in inbox.offer(source, reminders)) {
            ask { agent.event("reminder_due", mapOf("id" to r.id, "text" to r.text)) }
            inbox.markAnnounced(r.id)
        }
    }

    private fun usable(): Boolean = sessionState() == SessionState.Active

    /** Disabled, or never signed in. There is nothing to say and nobody to
     *  say it to; the way back is the maintenance screen. */
    private fun inert() {
        clearJob?.cancel()
        _state.value = FaceState(
            mood = Mood.OFFLINE,
            line = "This tablet is not signed in. Hold the top-left corner.",
            listening = false,
        )
    }

    private suspend fun ask(call: suspend () -> Reply) {
        if (!usable()) return inert()
        val reply = speaking.withLock { call() }
        apply(reply)
    }

    private fun apply(reply: Reply) {
        val line = reply.speak
        if (line == null) {
            // The governor decided silence. A mood it chose not to express is
            // not a mood change, so the face is left exactly as it was.
            _state.value = _state.value.copy(listening = false)
            return
        }
        clearJob?.cancel()
        _state.value = FaceState(mood = reply.mood, line = line, listening = false)
        clearJob = scope.launch {
            delay(LINE_TTL_MS)
            // The sentence goes; the expression stays. A face that snaps back
            // to neutral the moment the text fades reads as a screensaver.
            _state.value = _state.value.copy(line = null)
        }
    }

    companion object {
        /** Long enough to read across a desk, short enough that the panel is
         *  not showing yesterday's nag at breakfast. */
        const val LINE_TTL_MS = 20_000L
    }
}

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
     * Reminders came due, by whichever route got here first.
     *
     * Marked only when the agent actually answered. An unreachable agent
     * still produces a Reply — the offline line — and marking on that would
     * spend the reminder on a generic quip and never offer it again, because
     * AnnouncedStore is durable. So when the network is gone the reminder
     * reads out its own text and stays owed until the agent can dress it up.
     */
    suspend fun onReminders(source: ReminderInbox.Source, reminders: List<Reminder>) {
        if (!usable()) return inert()
        for (r in inbox.offer(source, reminders)) {
            val reply = speaking.withLock {
                agent.event("reminder_due", mapOf("id" to r.id, "text" to r.text))
            }
            // A reminder always reads out its own words.
            //
            // Relying on the agent for them fails two ways, and both were
            // seen on the device: with no network the canned offline line
            // says nothing about the reminder, and when the model is
            // unavailable the backend still answers 200 with "my brain's out
            // of juice" — a successful call that delivers nothing. Either
            // way the reminder was marked announced and its text was never
            // read. A reminder you cannot trust is worse than no reminder.
            show(Reply(captionFor(r, reply), moodFor(reply)))
            inbox.markAnnounced(r.id)
        }
    }

    /**
     * Re-read the session and go inert if it has been revoked.
     *
     * Nothing else watches it: every other path here runs because something
     * happened, and a revoked tablet is precisely one where nothing happens
     * any more. Without this it kept whatever expression it last wore, for
     * ever, giving no sign it was dead.
     */
    fun refreshSessionState() {
        if (!usable()) inert()
    }

    /** The reminder's own words, with the agent's remark after them when it
     *  managed one that does not already contain them. */
    private fun captionFor(r: Reminder, reply: Reply): String {
        val spoken = reply.speak?.takeIf { !reply.offline }?.trim()
        return when {
            spoken.isNullOrEmpty() -> r.text
            spoken.contains(r.text, ignoreCase = true) -> spoken
            else -> listOf(r.text, spoken).joinToString(separator = NEWLINE)
        }
    }

    private fun moodFor(reply: Reply): Mood =
        if (reply.offline || reply.mood == Mood.NEUTRAL) Mood.FRUSTRATED else reply.mood

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
        show(speaking.withLock { call() })
    }

    private fun show(reply: Reply) {
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

        /** Written as a code point so no editing pass can mangle the escape. */
        private val NEWLINE: String = 10.toChar().toString()
    }
}

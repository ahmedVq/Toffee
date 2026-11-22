package dev.toffee.companion

import dev.toffee.companion.face.Mood
import dev.toffee.companion.net.Agent
import dev.toffee.companion.net.Reply
import dev.toffee.companion.net.SessionState
import dev.toffee.companion.reminders.Reminder
import dev.toffee.companion.reminders.ReminderInbox
import dev.toffee.companion.voice.SilentVoice
import dev.toffee.companion.voice.Voice
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
    /** Defaults to silence, so a tablet whose engine failed to start behaves
     *  exactly as it did before it had a voice: captions and nothing else. */
    private val voice: Voice = SilentVoice,
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

    /** He has not moved in a while. The backend buckets this by the hour, so
     *  each hour earns at most one remark however often it is reported. */
    suspend fun onStillHere(minutes: Int) =
        ask { agent.event("still_here", mapOf("minutes" to minutes)) }

    /**
     * He said something.
     *
     * The reply is spoken a sentence at a time, as it is written, rather
     * than after it is finished. Ahmed's first complaint after using this
     * was "it was too slow", and most of the wait was the tablet sitting
     * silent with the first sentence already available.
     *
     * The first sentence interrupts whatever came before; every later one
     * queues behind it, because they are one thought.
     */
    suspend fun say(text: String) {
        if (!usable()) return inert()
        _state.value = _state.value.copy(mood = Mood.LISTENING, listening = true)

        val caption = StringBuilder()
        var spoken = 0

        val reply = speaking.withLock {
            agent.turn(text) { sentence, mood ->
                clearJob?.cancel()
                if (caption.isNotEmpty()) caption.append(' ')
                caption.append(sentence)
                val first = spoken++ == 0
                // The caption grows as he talks, so the screen and the
                // speaker never disagree about what has been said.
                _state.value = FaceState(mood = mood, line = caption.toString(), listening = false)
                voice.say(sentence, mood, interrupt = first)
            }
        }

        // Nothing streamed: the agent was unreachable, or it chose silence.
        // That is exactly what show() is for, including saying the line.
        if (spoken == 0) return show(reply)

        // It is already said and already on screen. All that is left is the
        // final mood — the model may have changed its face after its last
        // sentence — and the timer that clears the caption.
        settle(reply.mood, reply.speak ?: caption.toString())
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

    /** Stop talking. A tap while it is mid-sentence is him interrupting, and
     *  a companion that talks over an interruption is a radio. */
    fun hush() = voice.hush()

    /**
     * The mic is open.
     *
     * Called before listening starts rather than after, because the gap
     * between the tap and the first word is exactly where he needs to know
     * whether the thing is listening. The spec asks for a listening state
     * that is unambiguous across a room; ambiguity here is the failure.
     */
    fun onListening() {
        clearJob?.cancel()
        _state.value = FaceState(mood = Mood.LISTENING, line = null, listening = true)
    }

    /**
     * The mic was open and nothing came of it.
     *
     * No turn goes out: an empty string would cost an LLM call and get a
     * reply to nothing. And the face must come back, or a tap he abandoned
     * leaves it listening until somebody notices.
     */
    fun onHeardNothing() {
        clearJob?.cancel()
        _state.value = FaceState(mood = Mood.NEUTRAL, line = null, listening = false)
    }

    /**
     * It cannot listen at all — no recogniser, no permission, no microphone.
     *
     * Said out loud as well as shown, because there is no Settings app on
     * this device to go and discover it in, and a face that does nothing
     * when tapped is indistinguishable from a broken one.
     */
    fun onCannotHear(reason: String) {
        show(Reply("I can't hear you. " + reason, Mood.FRUSTRATED))
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
        // The caption and the speaker carry the same words, always. A line
        // shown but not said is one he can miss from two feet away, which is
        // the whole failure a reminder must not have.
        voice.say(line, reply.mood)
        settle(reply.mood, line)
    }

    /**
     * The face as it is left after a line: the expression, the words, and
     * the timer that takes the words away again.
     *
     * Shared with the streaming path, which has already said and shown
     * everything and must not say it a second time.
     */
    private fun settle(mood: Mood, line: String) {
        clearJob?.cancel()
        _state.value = FaceState(mood = mood, line = line, listening = false)
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

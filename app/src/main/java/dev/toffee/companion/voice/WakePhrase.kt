package dev.toffee.companion.voice

/**
 * Whether Toffee was called, as opposed to talked about.
 *
 * The recogniser behind this is a 68MB model that has never heard "Toffee"
 * used as a name. Near misses are the normal case, not the edge case, so
 * this is deliberately generous about how the name comes out — and
 * deliberately strict about where in the sentence it appears.
 *
 * The position rule is the whole defence against Review Focus 1: his own
 * voice reaches his own microphone, and his persona is prone to repeating
 * back what was said to him. "Stop saying hey toffee" is being discussed.
 * "Hey toffee" is being called. Only the second one starts a conversation.
 *
 * Pure. Everything about what counts as his name lives here, so the Android
 * half has no opinion on it at all.
 */
object WakePhrase {

    /**
     * What the model can actually produce for his name.
     *
     * This list was longer, and the extra entries were imagination. The
     * device said so plainly the first time the grammar was loaded:
     *
     *   W VoskAPI: Ignoring word missing in vocabulary: 'toffy'
     *   W VoskAPI: Ignoring word missing in vocabulary: 'toffi'
     *   W VoskAPI: Ignoring word missing in vocabulary: 'toffe'
     *   W VoskAPI: Ignoring word missing in vocabulary: 'tophie'
     *
     * A recogniser restricted to a grammar can only ever emit words that
     * are in its vocabulary, so spellings it has never heard of are not
     * lenient — they are dead entries that make this look more forgiving
     * than it is. The actual leniency comes from the grammar itself: a
     * recogniser that can only answer with these phrases cannot mis-hear a
     * sentence into something else.
     */
    val NAMES: List<String> = listOf("toffee")

    /**
     * Mishearings that are also real words someone might just say.
     *
     * "coffee" is the substitution this model reaches for most, because it
     * knows that word well and barely knows "toffee" — so it has to be
     * accepted. But accepting it the way a name is accepted would mean the
     * word "coffee", alone, in an office, starts a conversation. These
     * count only when a greeting came first: "hey coffee" is someone
     * calling him; "coffee" is someone wanting one.
     */
    private val CONFUSABLE: List<String> = listOf("coffee")

    /** Optional, because "Toffee, what time is it" is also being called. */
    private val GREETINGS: List<String> = listOf("hey", "hi", "hello", "ok", "okay", "a")

    /**
     * The greetings a *mishearing* is allowed to follow.
     *
     * Narrower than [GREETINGS], and the difference is "a". That article is
     * in the list above so "a toffee" counts — it is his name with a word in
     * front of it. Crossed with [CONFUSABLE] it gave away something else
     * entirely: "a coffee" is a thing Ahmed says in his own office, on the
     * phone, to nobody, and it would have opened the microphone and put him
     * in the call.
     *
     * Leniency about a mishearing has to be narrower than leniency about the
     * real name, because a mishearing is a word other people use for its own
     * sake.
     */
    private val CONFUSABLE_GREETINGS: List<String> = GREETINGS - "a"

    /** The recogniser emits none of this, but a stray one must not be the
     *  thing that stops him answering. */
    private const val PUNCTUATION = ",.!?;:"

    /**
     * What the recogniser is allowed to hear at all.
     *
     * Vosk can be restricted to a grammar, and for a wake listener that is
     * worth doing twice over: a recogniser that can only produce these
     * phrases cannot mis-transcribe a conversation into one of them, and it
     * spends far less of a 2016 CPU deciding. `[unk]` is required — without
     * it every sound in the room is forced onto the nearest phrase, which
     * is the opposite of what this is for.
     */
    val GRAMMAR: List<String> = buildList {
        for (name in NAMES) {
            add(name)
            for (greeting in GREETINGS) add("$greeting $name")
        }
        for (word in CONFUSABLE) {
            for (greeting in CONFUSABLE_GREETINGS) add("$greeting $word")
        }
        add(NOISE)
    }

    /**
     * What the recogniser emits for a sound that is not one of its phrases.
     *
     * A real token, not a placeholder: it arrives in the text like any other
     * word. A cough or a chair before the greeting therefore lands at
     * position 0, where the position rule below would read it as him being
     * talked about. Stripping it is not leniency — it is refusing to count
     * room noise as a word.
     */
    private const val NOISE = "[unk]"

    /**
     * @param finalText a FINAL recognition result, never a partial one.
     *        Partials containing the phrase fire early and repeatedly, which
     *        is the specific bug a published Vosk assistant had to fix.
     */
    fun heard(finalText: String): Boolean {
        val words = finalText
            .lowercase()
            .map { if (it in PUNCTUATION) ' ' else it }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .dropWhile { it == NOISE }

        if (words.isEmpty()) return false

        // Called by name, with nothing in front of it.
        if (words[0] in NAMES) return true

        // Greeted by name: the greeting first, the name immediately after.
        // Anything else before it means he is being talked about. A
        // confusable word only ever counts here, never on its own.
        if (words.size < 2) return false
        if (words[1] in NAMES) return words[0] in GREETINGS
        if (words[1] in CONFUSABLE) return words[0] in CONFUSABLE_GREETINGS
        return false
    }
}

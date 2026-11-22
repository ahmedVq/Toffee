package dev.toffee.companion.voice

import dev.toffee.companion.face.Mood

/**
 * The mouth, as a seam.
 *
 * Brain decides what is said and with what expression; this decides how it
 * comes out of the speaker. Splitting them here is what lets "a reminder is
 * read out even when the agent is useless" be a unit test rather than
 * something you check by standing next to the tablet.
 *
 * Nothing here throws. A missing engine is a tablet that still shows its
 * captions, not a tablet with a dialog on it.
 */
interface Voice {

    /**
     * Say [text] in [mood].
     *
     * @param interrupt whether this replaces what is being said or follows
     *        it. Interrupting is the right default: the face has already
     *        changed to the new line, and a voice still finishing the old one
     *        is worse than a sentence cut short.
     *
     *        Streaming is the exception and the reason this parameter exists.
     *        A reply now arrives a sentence at a time, and the second
     *        sentence of one thought must queue behind the first — flushing
     *        would make every reply say only its last sentence, out loud,
     *        while the caption showed all of it.
     */
    fun say(text: String, mood: Mood, interrupt: Boolean = true)

    /** Stop immediately and say nothing more. */
    fun hush()

    /** True while audio is actually coming out. */
    val speaking: Boolean
}

/**
 * A mouth that is not there.
 *
 * The default, so every existing caller compiles unchanged and so a tablet
 * with no usable engine degrades to exactly the behaviour it had before it
 * had a voice at all.
 */
object SilentVoice : Voice {
    override fun say(text: String, mood: Mood, interrupt: Boolean) = Unit
    override fun hush() = Unit
    override val speaking: Boolean get() = false
}

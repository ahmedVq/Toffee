package dev.toffee.companion.voice

import dev.toffee.companion.face.Mood

/**
 * How a mood sounds.
 *
 * The face already says mood twice over — shape and colour — because the
 * design rule is that colour alone cannot carry it. The voice is the third
 * channel, and it has exactly two dials.
 *
 * The spec fixes two points and leaves the rest here: "Pitch and rate carry
 * mood: low and slow for menace, clipped for irritation." Everything below is
 * read off those two.
 *
 * Both values are multipliers on the engine's own normal, which is what
 * [android.speech.tts.TextToSpeech.setPitch] and `setSpeechRate` take. They
 * stay inside 0.5..2.0 because the engine silently clamps outside it, and a
 * mood that was clamped sounds like a mood that was never set.
 *
 * THE CORRECTION THAT MATTERS: this table used to span pitch 0.75..1.15, and
 * Ahmed's verdict on hearing it was "the voice changes with each reply". He
 * was right. A 15% pitch shift on a synthesiser does not read as the same
 * person in a different temper — it reads as a different person. Identity
 * beat expressiveness, and the table lost.
 *
 * So mood now rides almost entirely on RATE, which is where people carry it
 * anyway, and pitch moves only enough to colour it. Pitch stays within
 * 0.96..1.04 and rate within 0.80..1.18. The face already says the mood twice
 * over in shape and colour; the voice is the third and most redundant
 * channel, and can afford to be subtle.
 */
data class Prosody(val pitch: Float, val rate: Float) {

    companion object {
        fun forMood(mood: Mood): Prosody = when (mood) {
            // The baseline everything else is heard against.
            Mood.NEUTRAL -> Prosody(pitch = 1.00f, rate = 1.00f)

            // Quicker, and only just brighter. Delight is audible in the
            // hurry, not in becoming a different person.
            Mood.HAPPY -> Prosody(pitch = 1.04f, rate = 1.10f)

            Mood.SAD -> Prosody(pitch = 0.97f, rate = 0.88f)

            // "Clipped for irritation": the words come faster and the pitch
            // barely moves. Snapping, not shouting.
            Mood.FRUSTRATED -> Prosody(pitch = 1.02f, rate = 1.18f)

            // Warm and unhurried. The one register he is sincere in.
            Mood.LOVING -> Prosody(pitch = 1.03f, rate = 0.94f)

            // "Low and slow for menace." The menace is in the slowness; a
            // pitch drop big enough to hear on its own cost him his voice.
            Mood.SMUG -> Prosody(pitch = 0.97f, rate = 0.86f)

            // Slower than anything else he says, by the test's definition.
            Mood.SLEEPY -> Prosody(pitch = 0.96f, rate = 0.80f)

            // Never spoken — the mic is live and it is not talking — but a
            // mood without an entry would be a crash waiting for the one
            // frame where the two overlap.
            Mood.LISTENING -> Prosody(pitch = 1.00f, rate = 1.00f)

            // Flat. Not sulking, just reporting a fact about the router.
            Mood.OFFLINE -> Prosody(pitch = 0.98f, rate = 0.95f)
        }
    }
}

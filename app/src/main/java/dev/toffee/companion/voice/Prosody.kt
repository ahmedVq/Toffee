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
 */
data class Prosody(val pitch: Float, val rate: Float) {

    companion object {
        fun forMood(mood: Mood): Prosody = when (mood) {
            // The baseline everything else is heard against.
            Mood.NEUTRAL -> Prosody(pitch = 1.00f, rate = 1.00f)

            // Up and quick, because delight is not something it does calmly.
            Mood.HAPPY -> Prosody(pitch = 1.15f, rate = 1.10f)

            Mood.SAD -> Prosody(pitch = 0.85f, rate = 0.85f)

            // "Clipped for irritation": the pitch barely moves, the words
            // come faster. Snapping, not shouting.
            Mood.FRUSTRATED -> Prosody(pitch = 1.05f, rate = 1.25f)

            // Warm and unhurried. The one register it is sincere in.
            Mood.LOVING -> Prosody(pitch = 1.10f, rate = 0.92f)

            // "Low and slow for menace." This is the voice the personality
            // was designed around, so it is the one tuned hardest.
            Mood.SMUG -> Prosody(pitch = 0.88f, rate = 0.88f)

            // Slower than anything else it says, by the test's definition.
            Mood.SLEEPY -> Prosody(pitch = 0.75f, rate = 0.72f)

            // Never spoken — the mic is live and it is not talking — but a
            // mood without an entry would be a crash waiting for the one
            // frame where the two overlap.
            Mood.LISTENING -> Prosody(pitch = 1.00f, rate = 1.00f)

            // Flat. Not sulking, just reporting a fact about the router.
            Mood.OFFLINE -> Prosody(pitch = 0.90f, rate = 0.95f)
        }
    }
}

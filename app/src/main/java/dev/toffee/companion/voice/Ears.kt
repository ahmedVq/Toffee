package dev.toffee.companion.voice

/**
 * What came of opening the microphone.
 *
 * The three outcomes are deliberately not two. "Nothing" and "Unavailable"
 * feel the same from across the room and must not be treated the same: one
 * is him saying nothing, which is free and silent; the other is the tablet
 * being deaf, which he has no way of discovering unless it says so.
 */
sealed class Heard {

    /** He said something. Never blank. */
    data class Words(val text: String) : Heard()

    /** The mic opened and nothing intelligible came out of it. */
    object Nothing : Heard()

    /** It could not listen at all — no recogniser, no permission, no mic. */
    data class Unavailable(val reason: String) : Heard()
}

/**
 * The ears, as a seam.
 *
 * Two implementations, because the design doc names "whether `SpeechRecognizer`
 * works at all on API 27 with no Google account" as the main open technical
 * risk of the whole project. [DeviceEars] is free and uses the recogniser this
 * ROM ships; [CloudEars] records and posts the audio to an Edge Function, so
 * the provider key stays on the server. [bestEars] picks.
 */
interface Ears {

    /** Cheap, and asked before the microphone is ever opened. */
    fun available(): Boolean

    /** Opens the mic and returns when there is an answer or it gives up. */
    suspend fun listen(): Heard
}

/**
 * The device's own recogniser when it has one, the cloud when it does not.
 *
 * Decided on every listen, not once at startup. That was the bug: the
 * permission grant at startup is asynchronous on a device that is not yet
 * Device Owner, so a single early call to [Ears.available] saw no microphone,
 * pinned the tablet to the paid cloud path, and kept it there for the life of
 * the process while a free recogniser sat installed. In a kiosk that process
 * can live for months.
 *
 * Deliberately not a preference anyone can set: there is no Settings app on
 * this tablet, and the right answer is a property of the device rather than a
 * taste.
 */
class BestEars(private val device: Ears, private val cloud: Ears) : Ears {

    override fun available(): Boolean = device.available() || cloud.available()

    override suspend fun listen(): Heard = when {
        device.available() -> device.listen()
        cloud.available() -> cloud.listen()
        else -> Heard.Unavailable("I have no way to listen on this tablet.")
    }
}

fun bestEars(device: Ears, cloud: Ears): Ears = BestEars(device, cloud)

package dev.toffee.companion.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android's own speech recognition.
 *
 * Free, which is the whole point on a project with no budget, and on this
 * tablet it is Google's recogniser answering — `voice_recognition_service` is
 * set to `com.google.android.googlequicksearchbox` and the package survived
 * the factory reset. It needs the network but not a Google account.
 *
 * The audio leaves the device, which the design doc permits explicitly:
 * "Permitted: Ahmed has said desk audio may leave the device."
 *
 * Everything here is arranged around one rule: [listen] must always return.
 * A recogniser that never calls back leaves the face listening for ever, and
 * on a tablet with no Settings app and no launcher there is nothing he can do
 * about it.
 */
class DeviceEars(context: Context) : Ears {

    private val app = context.applicationContext

    override fun available(): Boolean =
        hasMic() && runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)

    private fun hasMic(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun listen(): Heard {
        if (!hasMic()) return Heard.Unavailable("I don't have permission to use the microphone.")
        if (!available()) return Heard.Unavailable("There is no speech recogniser on this tablet.")

        // SpeechRecognizer must be created, started and destroyed on the
        // thread that owns a Looper. The main thread is the only one here
        // that has one.
        return withContext(Dispatchers.Main) {
            // The belt to the recogniser's braces. Its own silence timeouts
            // are advisory and some implementations simply never fire.
            withTimeoutOrNull(LISTEN_TIMEOUT_MS) { once() } ?: Heard.Nothing
        }
    }

    private suspend fun once(): Heard = suspendCancellableCoroutine { cont ->
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull()
        if (recognizer == null) {
            cont.resume(Heard.Unavailable("The speech recogniser would not start."))
            return@suspendCancellableCoroutine
        }

        // The listener can be called more than once for one session — a
        // partial result followed by a final one, or an error after results.
        // Resuming a continuation twice is a crash, so the first answer wins.
        var answered = false
        fun finish(heard: Heard) {
            if (answered) return
            answered = true
            runCatching { recognizer.destroy() }
            cont.resume(heard)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                finish(if (best.isNullOrEmpty()) Heard.Nothing else Heard.Words(best))
            }

            override fun onError(error: Int) = finish(forError(error))

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        cont.invokeOnCancellation {
            answered = true
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_ENDS_IT_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_ENDS_IT_MS,
            )
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure { finish(Heard.Unavailable("The microphone would not open.")) }
    }

    /**
     * An error he could do something about is worth saying; an error that
     * just means "you didn't say anything" is not.
     */
    private fun forError(error: Int): Heard = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> Heard.Nothing

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> Heard.Unavailable("I can't reach the thing that does my listening.")

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            Heard.Unavailable("I don't have permission to use the microphone.")

        // ERROR_RECOGNIZER_BUSY, ERROR_AUDIO, ERROR_CLIENT, ERROR_SERVER.
        // None of these is his fault and none of them is actionable, but a
        // silent face is worse than an unhelpful sentence.
        else -> Heard.Unavailable("My ears aren't working (error $error).")
    }

    companion object {
        /** How long a pause means he has finished talking. */
        const val SILENCE_ENDS_IT_MS = 1_500L

        /** The hard ceiling. Longer than anyone speaks in one go at a desk,
         *  and short enough that a wedged recogniser is a hiccup rather than
         *  a tablet that has to be rebooted. */
        const val LISTEN_TIMEOUT_MS = 12_000L
    }
}

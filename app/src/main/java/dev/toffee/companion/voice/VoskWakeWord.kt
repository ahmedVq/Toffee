package dev.toffee.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * The only file that knows about Vosk.
 *
 * Picovoice would have been the obvious choice and is not available: its
 * free-tier keys were switched off on 2026-06-30 and custom wake words need
 * a paid account, which fails the project's no-budget rule outright. Vosk is
 * Apache-2.0 with no account at all, and ships an `armeabi-v7a` library —
 * verified by opening the AAR before a line of this was written, the same
 * way the camera's ABI was checked.
 *
 * Two things here are load-bearing and neither is obvious:
 *
 * It acts on FINAL results only. A published Vosk assistant had to make this
 * exact change: partial results containing the phrase fire early, and then
 * again, and again, as the partial is revised.
 *
 * It holds the microphone for as long as it listens. Nothing else on the
 * tablet can record while it does, so [stop] must be called before the
 * recogniser that transcribes Ahmed, on every path.
 *
 * Never throws. A tablet whose wake word failed to load is a tablet Ahmed
 * taps, which is what he does today.
 */
class VoskWakeWord(
    context: Context,
    private val onWake: () -> Unit,
    private val onUnavailable: (String) -> Unit,
) : WakeWord {

    private val app = context.applicationContext

    @Volatile private var service: SpeechService? = null
    @Volatile private var model: Model? = null
    @Volatile private var dead = false

    override val listening: Boolean get() = service != null

    override fun start() {
        if (dead || listening) return

        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return fail("I can't listen for my name without the microphone.")
        }

        // Unpacking is 68MB of file copying on first run and must not happen
        // on the main thread; the model load itself is also slow enough to
        // drop frames. Everything here is off the UI thread, and the
        // listener callbacks come back on Vosk's own thread.
        Thread({
            val loaded = runCatching {
                val dir = unpackedModel() ?: return@runCatching null
                Model(dir.absolutePath)
            }.getOrNull()

            if (loaded == null) {
                fail("My wake word didn't load. Tap me instead.")
                return@Thread
            }
            if (dead) {
                runCatching { loaded.close() }
                return@Thread
            }

            val started = runCatching {
                // Restricted to the phrases that mean his name. A recogniser
                // that cannot produce a sentence cannot mishear one into a
                // wake, and it costs this CPU far less.
                val grammar = JSONArray(WakePhrase.GRAMMAR).toString()
                val recognizer = Recognizer(loaded, SAMPLE_RATE, grammar)
                SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(Listener()) }
            }.getOrNull()

            if (started == null) {
                runCatching { loaded.close() }
                fail("My wake word couldn't open the microphone. Tap me instead.")
                return@Thread
            }

            model = loaded
            service = started
        }, "toffee-wake").start()
    }

    override fun stop() {
        val running = service ?: return
        service = null
        // Both: stop() ends the recognition, shutdown() releases the
        // microphone. Leaving the second out means the next thing that wants
        // to record silently gets nothing, which is the failure mode this
        // whole handover exists to avoid.
        runCatching { running.stop() }
        runCatching { running.shutdown() }
    }

    /** Release the model as well. Call when the activity is done. */
    fun release() {
        dead = true
        stop()
        model?.let { runCatching { it.close() } }
        model = null
    }

    private fun fail(why: String) {
        dead = true
        onUnavailable(why)
    }

    /**
     * The model, as files on disk.
     *
     * Vosk opens it by path, and an asset inside an APK is not a path, so it
     * has to be copied out once. Keyed on a marker holding the asset's name:
     * a new model in a later build invalidates the old copy without needing
     * anything cleared by hand on a tablet nobody can reach.
     */
    private fun unpackedModel(): File? = runCatching {
        val target = File(app.filesDir, UNPACKED_DIR)
        val marker = File(target, MARKER)

        if (marker.isFile && marker.readText() == ASSET_DIR) return@runCatching target

        target.deleteRecursively()
        target.mkdirs()
        copyAssets(ASSET_DIR, target)
        marker.writeText(ASSET_DIR)
        target
    }.getOrNull()

    private fun copyAssets(from: String, to: File) {
        val entries = app.assets.list(from) ?: return
        if (entries.isEmpty()) {
            // A leaf: assets.list returns nothing for a file.
            app.assets.open(from).use { input ->
                to.outputStream().use { input.copyTo(it) }
            }
            return
        }
        to.mkdirs()
        for (entry in entries) copyAssets("$from/$entry", File(to, entry))
    }

    private inner class Listener : RecognitionListener {
        /**
         * Final results only.
         *
         * [onPartialResult] is deliberately empty: a partial containing the
         * phrase fires early and then repeatedly as it is revised, so a wake
         * listener built on partials wakes several times for one greeting.
         */
        override fun onPartialResult(hypothesis: String?) = Unit

        override fun onResult(hypothesis: String?) {
            val said = textOf(hypothesis) ?: return
            if (WakePhrase.heard(said)) onWake()
        }

        override fun onFinalResult(hypothesis: String?) = Unit

        override fun onError(e: Exception?) {
            // The class name, not the message: this string is shown on the
            // maintenance screen and a message could carry anything. Naming
            // it at all is the point — the first version said only "it
            // stopped working", which is exactly as useful as silence when
            // the device's logs reach nobody.
            fail("My wake word stopped working (${e?.javaClass?.simpleName ?: "no reason given"}).")
        }

        override fun onTimeout() = Unit
    }

    /** Vosk answers with `{"text": "..."}`. */
    private fun textOf(hypothesis: String?): String? = runCatching {
        if (hypothesis.isNullOrBlank()) return null
        (LENIENT.parseToJsonElement(hypothesis) as JsonObject)["text"]
            ?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        /** What the model was trained at; anything else is resampled badly. */
        const val SAMPLE_RATE = 16_000f

        const val ASSET_DIR = "vosk-model-en-us"
        const val UNPACKED_DIR = "vosk-model"
        const val MARKER = ".unpacked-from"

        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

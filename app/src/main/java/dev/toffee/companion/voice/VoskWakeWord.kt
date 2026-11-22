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
 * Four things here are load-bearing and none is obvious:
 *
 * It acts on FINAL results only. A published Vosk assistant had to make this
 * exact change: partial results containing the phrase fire early, and then
 * again, and again, as the partial is revised.
 *
 * The model is loaded once and kept. `SpeechService.shutdown()` was read in
 * the bytecode and does exactly one thing — `recorder.release()`. It does
 * not stop the recogniser thread and it frees neither the `Recognizer` nor
 * the `Model`, both of which are JNA pointers with no finaliser. Allocating
 * a model per conversation therefore leaked about 40MB of native memory
 * each time, on a tablet with 1.5GB, that cannot be restarted by hand.
 *
 * A busy microphone is not a death sentence. `SpeechService`'s constructor
 * allocates the `AudioRecord` and throws when it cannot get one, which is
 * exactly what happens in the moment after a conversation ends: releasing
 * the speech recogniser is an IPC to another process and the microphone
 * comes free slightly later. Treating that as fatal — which it was — meant
 * the first conversation of the day permanently ended the wake word, on a
 * device that is HOME and never restarts.
 *
 * It holds the microphone for as long as it listens. Nothing else on the
 * tablet can record while it does, so [stop] must be called before the
 * recogniser that transcribes Ahmed, on every path. [Attention] owns that
 * decision; this only carries it out.
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
    @Volatile private var recognizer: Recognizer? = null

    /** Kept for the life of the process once loaded. Reloading it per
     *  conversation cost 68MB of file reads, several seconds of deafness,
     *  and — because nothing frees the old one — the memory as well. */
    @Volatile private var model: Model? = null

    /** Fatal only: no microphone permission, a model that will not load, or
     *  [release]. Never set for something that might work next time. */
    @Volatile private var dead = false

    /** A start is in flight. Without this, [Attention] asking twice spawns
     *  two loader threads racing for one microphone. */
    @Volatile private var starting = false

    /** Bumped by [stop]. A loader thread that finds the number changed under
     *  it was superseded and tears down what it built instead of installing
     *  it — the window is about five seconds wide on this tablet, which is
     *  exactly when Ahmed taps after a reboot. */
    @Volatile private var generation = 0

    /** Remembered rather than applied, because [pause] is normally called in
     *  the same breath as [start] and there is no service to pause yet. */
    @Volatile private var paused = false

    override val listening: Boolean get() = service != null

    override fun start() {
        if (dead || service != null || starting) return

        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return fatal("I can't listen for my name without the microphone.")
        }

        starting = true
        val mine = generation

        // Unpacking is 68MB of file copying on first run and must not happen
        // on the main thread; the model load itself is also slow enough to
        // drop frames. Everything here is off the UI thread, and the
        // listener callbacks come back on Vosk's own thread.
        Thread({ load(mine) }, "toffee-wake").start()
    }

    private fun load(mine: Int) {
        val loaded = model ?: runCatching {
            val dir = unpackedModel() ?: return@runCatching null
            Model(dir.absolutePath)
        }.getOrNull()

        if (loaded == null) {
            starting = false
            // The model is in the APK. If it will not load it will not load
            // on the next try either, so this one really is final.
            fatal("My wake word didn't load. Tap me instead.")
            return
        }
        model = loaded

        if (dead || mine != generation) {
            starting = false
            return
        }

        // The microphone may still be held by the recogniser that just
        // finished transcribing Ahmed: releasing it is an IPC and completes
        // after the call returns. Retrying is the whole difference between
        // a wake word that survives a conversation and one that does not.
        var attempt = 0
        while (true) {
            if (dead || mine != generation) {
                starting = false
                return
            }

            val opened = runCatching {
                // Restricted to the phrases that mean his name. A recogniser
                // that cannot produce a sentence cannot mishear one into a
                // wake, and it costs this CPU far less.
                val grammar = JSONArray(WakePhrase.GRAMMAR).toString()
                val r = Recognizer(loaded, SAMPLE_RATE, grammar)
                val s = runCatching { SpeechService(r, SAMPLE_RATE) }
                    .getOrElse { runCatching { r.close() }; throw it }
                r to s
            }.getOrNull()

            if (opened != null) {
                val (r, s) = opened
                if (dead || mine != generation) {
                    // Superseded while we were opening it. Tear down rather
                    // than install, or the thing that superseded us is now
                    // sharing a microphone it believes it owns.
                    runCatching { s.stop() }
                    runCatching { s.shutdown() }
                    runCatching { r.close() }
                    starting = false
                    return
                }
                s.startListening(Listener())
                s.setPause(paused)
                recognizer = r
                service = s
                starting = false
                return
            }

            if (++attempt >= OPEN_ATTEMPTS) break
            runCatching { Thread.sleep(OPEN_RETRY_MS) }
        }

        starting = false
        // Not fatal: say so, and let the next handover try again. Nothing
        // here has set `dead`, so the end of the next conversation is
        // another chance.
        onUnavailable("My wake word couldn't open the microphone. Tap me instead.")
    }

    override fun stop() {
        // Before anything else: any loader thread in flight is now stale and
        // must not install what it is building.
        generation++
        starting = false

        val running = service ?: run {
            recognizer?.let { runCatching { it.close() } }
            recognizer = null
            return
        }
        service = null
        val r = recognizer
        recognizer = null

        // All three, in this order. stop() ends the recogniser thread,
        // shutdown() releases the microphone, and close() frees the native
        // recogniser — which nothing in Vosk does for you.
        runCatching { running.stop() }
        runCatching { running.shutdown() }
        r?.let { runCatching { it.close() } }
    }

    override fun pause(paused: Boolean) {
        this.paused = paused
        service?.let { runCatching { it.setPause(paused) } }
    }

    /** Release the model as well. Call when the activity is done. */
    fun release() {
        dead = true
        stop()
        model?.let { runCatching { it.close() } }
        model = null
    }

    private fun fatal(why: String) {
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
            // Paused means his own voice is in the room. Vosk delivers a
            // result at an endpoint, which is after the speech that produced
            // it, so a result can still arrive just after the pause began.
            if (paused) return
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
            //
            // Not fatal. An error from the recogniser thread is the kind of
            // thing a restart fixes, and the next handover is a restart.
            service = null
            onUnavailable("My wake word stopped working (${e?.javaClass?.simpleName ?: "no reason given"}).")
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

        /** How many times to wait out a microphone someone else still holds.
         *  Five attempts at 400ms covers the handover from the speech
         *  recogniser, which is the only thing that competes for it. */
        const val OPEN_ATTEMPTS = 5
        const val OPEN_RETRY_MS = 400L

        const val ASSET_DIR = "vosk-model-en-us"
        const val UNPACKED_DIR = "vosk-model"
        const val MARKER = ".unpacked-from"

        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

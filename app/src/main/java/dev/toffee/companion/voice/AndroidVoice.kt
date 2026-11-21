package dev.toffee.companion.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.toffee.companion.face.Mood
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real mouth: Android's own [TextToSpeech], whichever engine the device
 * has.
 *
 * The design doc says "eSpeak-NG bundled in the APK, full stop", reasoning
 * from the fact that with no Google account Google TTS cannot fetch offline
 * voices. That reasoning is sound and the conclusion is one step too far: on
 * Android, eSpeak NG *is* a TextToSpeech engine. Writing against the platform
 * API costs nothing and keeps eSpeak available as a sideload rather than an
 * NDK build.
 *
 * Starting an engine is asynchronous and can fail two different ways — the
 * service never binds, or it binds and has no voice for the language — so
 * this walks [TtsEngines.order] and treats both the same: try the next one.
 * Only when the list runs out does the tablet become one that writes.
 *
 * @param onFailure called at most once, with a sentence fit to put on screen.
 */
class AndroidVoice(
    context: Context,
    private val onFailure: (String) -> Unit,
) : Voice {

    private val app = context.applicationContext

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var dead = false

    /** Only the newest matters: [say] flushes the queue anyway, so a line
     *  said before the engine was ready supersedes any before it. */
    @Volatile private var pending: Pair<String, Mood>? = null

    @Volatile private var utterance = 0L
    @Volatile override var speaking: Boolean = false
        private set

    private val reported = AtomicBoolean(false)

    private val candidates: List<String?> = TtsEngines.order(installedEngines(), defaultEngine())
    private var next = 0

    init {
        advance()
    }

    /** Every engine on the device that answers the TTS service intent. The
     *  only way to ask the question that does not need a working
     *  [TextToSpeech] to ask it with. */
    private fun installedEngines(): List<String> = runCatching {
        app.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    private fun defaultEngine(): String? = runCatching {
        Settings.Secure.getString(app.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Start the next candidate, or give up when there are none left. */
    private fun advance() {
        if (next >= candidates.size) {
            return fail(
                if (candidates.isEmpty()) "No speech engine on this tablet. You get subtitles."
                else "No speech engine here has a voice. You get subtitles.",
            )
        }
        val name = candidates[next++]
        val tts = if (name == null) TextToSpeech(app, ::onInit) else TextToSpeech(app, ::onInit, name)
        engine = tts
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { speaking = false }
            @Deprecated("the signature the platform still calls on API 27")
            override fun onError(utteranceId: String?) { speaking = false }
            override fun onError(utteranceId: String?, errorCode: Int) { speaking = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { speaking = false }
        })
    }

    private fun onInit(status: Int) {
        if (dead) return
        val tts = engine

        if (status == TextToSpeech.SUCCESS && tts != null && hasAVoice(tts)) {
            ready = true
            pending?.let { (text, mood) -> pending = null; say(text, mood) }
            return
        }

        // Bound but voiceless, or never bound at all. Same answer either way.
        tts?.let { runCatching { it.shutdown() } }
        engine = null
        advance()
    }

    /** English first, because that is what the agent writes in; the device's
     *  own locale after, because a voice with an accent beats no voice. */
    private fun hasAVoice(tts: TextToSpeech): Boolean =
        listOf(Locale.UK, Locale.US, Locale.ENGLISH, Locale.getDefault()).any {
            val r = runCatching { tts.setLanguage(it) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }

    private fun fail(why: String) {
        dead = true
        ready = false
        pending = null
        engine?.let { runCatching { it.shutdown() } }
        engine = null
        if (reported.compareAndSet(false, true)) onFailure(why)
    }

    override fun say(text: String, mood: Mood) {
        if (dead || text.isBlank()) return
        val tts = engine
        if (tts == null || !ready) {
            pending = text to mood
            return
        }

        val (pitch, rate) = Prosody.forMood(mood)
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)

        val id = "toffee-" + (++utterance)
        // QUEUE_FLUSH, because the face has already changed to the new line
        // and a voice still finishing the old one reads as a malfunction.
        val result = runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        }.getOrDefault(TextToSpeech.ERROR)

        if (result == TextToSpeech.ERROR) {
            fail("The speech engine stopped answering. You get subtitles.")
        } else {
            speaking = true
        }
    }

    override fun hush() {
        speaking = false
        pending = null
        engine?.let { runCatching { it.stop() } }
    }

    /** Call from `onDestroy`. An un-shut-down engine holds a binding to the
     *  engine service for the life of the process. */
    fun shutdown() {
        hush()
        dead = true
        engine?.let { runCatching { it.shutdown() } }
        engine = null
        ready = false
    }
}

package dev.toffee.companion

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.toffee.companion.face.Mood
import dev.toffee.companion.voice.AndroidVoice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether this tablet can talk at all.
 *
 * Nothing about a TTS engine can be established off the device: the engine
 * list, whether it initialises, and whether it has an English voice are all
 * properties of this ROM after this factory reset. The unit tests prove the
 * mood table and the seam; this proves there is a mouth behind it.
 */
@RunWith(AndroidJUnit4::class)
class VoiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Reports what is installed in its own failure message, because the
     *  answer is the point — it decides whether eSpeak NG has to be
     *  sideloaded or whether Samsung's engine survived the wipe. */
    @Test
    fun theTabletHasAnEngineInstalled() {
        val latch = CountDownLatch(1)
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { latch.countDown() }
        assertTrue("TextToSpeech never called onInit", latch.await(20, TimeUnit.SECONDS))

        val names = runCatching { tts.engines.map { it.name } }.getOrDefault(emptyList())
        val default = runCatching { tts.defaultEngine }.getOrNull()
        tts.shutdown()

        assertTrue(
            "no speech engine on this device; sideload eSpeak NG. default=$default engines=$names",
            names.isNotEmpty(),
        )
    }

    @Test
    fun itCanSayASentenceOutLoud() {
        val failure = AtomicReference<String?>(null)
        val voice = AndroidVoice(context, onFailure = { failure.set(it) })

        // AndroidVoice queues anything said before onInit lands, so there is
        // nothing to wait for here beyond the speech itself.
        voice.say("Testing. One. Two.", Mood.SMUG)

        val deadline = System.currentTimeMillis() + 20_000
        var everSpoke = false
        while (System.currentTimeMillis() < deadline) {
            if (voice.speaking) everSpoke = true
            if (everSpoke && !voice.speaking) break
            Thread.sleep(100)
        }
        voice.shutdown()

        assertTrue(
            "nothing came out of the speaker; AndroidVoice reported: ${failure.get()}",
            everSpoke,
        )
    }

    /**
     * Review Focus 2, from the side this device can actually prove.
     *
     * The survey says this tablet has a working `com.google.android.tts`, so
     * `onFailure` must never fire here at all. Asserting "at most once" was
     * useless: the count was zero, the failure path was never entered, and
     * the test passed identically with the reporting deleted. Asserting zero
     * on a working engine is a real constraint — it fails if AndroidVoice
     * ever starts crying wolf, which on this device would put a permanent
     * "you get subtitles" fault on the maintenance screen.
     */
    @Test
    fun aWorkingEngineNeverReportsAFailure() {
        val calls = AtomicInteger(0)
        val voice = AndroidVoice(context, onFailure = { calls.incrementAndGet() })

        repeat(5) { voice.say("line $it", Mood.NEUTRAL) }
        Thread.sleep(3_000)
        voice.hush()
        voice.shutdown()

        assertEquals(
            "the engine works, so nothing should have been reported broken",
            0,
            calls.get(),
        )
    }

    @Test
    fun hushSilencesItMidSentence() {
        val voice = AndroidVoice(context, onFailure = { })
        voice.say(
            "This is a deliberately long sentence that would take several seconds to finish saying.",
            Mood.SLEEPY,
        )
        Thread.sleep(1_500)
        voice.hush()
        Thread.sleep(500)

        val stillTalking = voice.speaking
        voice.shutdown()
        assertEquals("hush must stop it, not queue behind it", false, stillTalking)
    }
}

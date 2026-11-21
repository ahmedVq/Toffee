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
        val voice = AndroidVoice(context) { failure.set(it) }

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

    /** Review Focus 2: a tablet that cannot speak must still caption, and
     *  must say so exactly once rather than every line for ever. */
    @Test
    fun aBrokenEngineIsReportedOnceAndNeverCrashes() {
        val calls = AtomicInteger(0)
        val voice = AndroidVoice(context) { calls.incrementAndGet() }

        repeat(5) { voice.say("line $it", Mood.NEUTRAL) }
        Thread.sleep(2_000)
        voice.hush()
        voice.shutdown()

        assertTrue("onFailure fired more than once: ${calls.get()}", calls.get() <= 1)
    }

    @Test
    fun hushSilencesItMidSentence() {
        val voice = AndroidVoice(context) { }
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

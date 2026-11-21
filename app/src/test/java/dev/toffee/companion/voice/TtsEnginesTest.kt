package dev.toffee.companion.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which engine to try, and in what order.
 *
 * This exists because of what the tablet turned out to be: after the factory
 * reset `settings get secure tts_default_synth` is null, so there is no
 * platform default to fall back on, and the only engine installed is Google's.
 * Asking a TextToSpeech instance which engines exist does not help when the
 * instance itself failed to start, so the list comes from PackageManager and
 * the ordering is decided here, where it can be tested.
 */
class TtsEnginesTest {

    @Test
    fun `the platform default is tried first when there is one`() {
        assertEquals(listOf(null), TtsEngines.order(listOf("com.google.android.tts"), "com.google.android.tts"))
    }

    // The tablet's actual state after the wipe. Before this, AndroidVoice
    // gave up and showed "no speech engine" with Google TTS sitting installed.
    @Test
    fun `a device with no default engine still has a candidate`() {
        assertEquals(
            listOf("com.google.android.tts"),
            TtsEngines.order(listOf("com.google.android.tts"), null),
        )
    }

    @Test
    fun `preferred engines come before unknown ones`() {
        val order = TtsEngines.order(listOf("zz.unknown.engine", "com.reecedunn.espeak"), null)
        assertTrue(
            "espeak is a known good robot voice; zz.unknown is a guess: $order",
            order.indexOf("com.reecedunn.espeak") < order.indexOf("zz.unknown.engine"),
        )
    }

    @Test
    fun `an engine that is not installed is never tried`() {
        assertEquals(listOf("x.y"), TtsEngines.order(listOf("x.y"), "not.installed.engine"))
    }

    @Test
    fun `no engines means nothing to try`() {
        assertTrue(TtsEngines.order(emptyList(), null).isEmpty())
        assertTrue(TtsEngines.order(emptyList(), "ghost").isEmpty())
    }

    @Test
    fun `no engine is tried twice`() {
        val order = TtsEngines.order(
            listOf("com.google.android.tts", "com.samsung.SMT", "other"),
            "com.samsung.SMT",
        )
        assertEquals("a retry of the same engine proves nothing", order.distinct(), order)
    }
}

package dev.toffee.companion

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.toffee.companion.voice.DeviceEars
import dev.toffee.companion.voice.Heard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The spec's main open technical risk, settled on the hardware.
 *
 * "Whether `SpeechRecognizer` works at all on API 27 with no Google account.
 * If it does not, re-adding an account after provisioning is the fallback,
 * and voice input survives. This must be tested on the device before the
 * listening half is built."
 *
 * The listening half was built before this could run, because the tablet was
 * flat. That is the only reason CloudEars exists — so the answer here changes
 * which path is taken, not whether the feature ships.
 */
@RunWith(AndroidJUnit4::class)
class EarsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Records the answer rather than demanding one, because either answer is
     * a legitimate state of this device and BestEars covers both.
     *
     * It prints rather than asserts: a passing assertion never shows its
     * message, so `assertTrue(message, true)` recorded the answer nowhere at
     * all. What it does assert is the thing that must hold either way —
     * that the tablet has *some* way to listen.
     */
    @Test
    fun doesThisTabletHaveARecogniser() {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        println("RECOGNISER=$available MIC_GRANTED=$mic")

        assertTrue(
            "no device recogniser and no microphone either, so neither DeviceEars " +
                "nor CloudEars can work: RECOGNISER=$available MIC_GRANTED=$mic",
            available || mic,
        )
    }

    /** Review Focus 3, on the hardware: a tap he never speaks into must come
     *  back, or the face listens for ever and there is no Settings app. */
    @Test
    fun listeningToSilenceEndsInNothingNotAHang() {
        val ears = DeviceEars(context)
        if (!ears.available()) return // CloudEars covers this device instead.

        val started = System.currentTimeMillis()
        val heard = runBlocking { ears.listen() }
        val took = System.currentTimeMillis() - started

        assertTrue(
            "listen() took ${took}ms; it must give up inside its own ceiling",
            took <= DeviceEars.LISTEN_TIMEOUT_MS + 3_000,
        )
        assertTrue(
            "silence is Nothing or Unavailable, never Words: $heard",
            heard is Heard.Nothing || heard is Heard.Unavailable,
        )
    }

    /**
     * Review Focus 1. On a Device Owner tablet this must come back empty: a
     * permission the app cannot grant itself is one no one can ever grant,
     * because the kiosk removed the screen it would be granted from.
     */
    @Test
    fun theMicPermissionCanBeGrantedWithoutSettings() {
        val activity = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .packageManager
            .getLaunchIntentForPackage(context.packageName)
        assertTrue("the app must have a launch intent", activity != null)

        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val owner = dev.toffee.companion.kiosk.Kiosk.isDeviceOwner(context)

        // Off the Device Owner path the grant is a dialog nobody is there to
        // answer during an instrumented run, so this only asserts where the
        // assertion means something.
        if (owner) {
            assertTrue(
                "Device Owner must be able to hold RECORD_AUDIO without Settings",
                granted,
            )
        }
    }
}

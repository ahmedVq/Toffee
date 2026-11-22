package dev.toffee.companion

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.toffee.companion.kiosk.Faults
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wake word, on the hardware, without breaking it in the process.
 *
 * This file started out much larger: it built its own [VoskWakeWord], took
 * the microphone, and checked the handover directly. Every one of those
 * tests was wrong to exist here, for a reason that only showed up on the
 * device.
 *
 * Toffee is the HOME activity. It never stops, and its wake listener holds
 * the microphone for as long as it runs. Only one thing on this tablet can
 * record at a time — so a test that opens the microphone does not observe
 * the app, it FIGHTS it. Both directions were seen within ten minutes: the
 * test failing with an IOException out of AudioRecord because the app held
 * the microphone, and then the app recording a wake-word fault because the
 * test had taken it first. Even the innocent-looking "is the microphone
 * free" probe opens it, and opening it during the app's startup is what
 * made the app fail.
 *
 * So this observes and does not touch. The app's own fault record is the
 * evidence, which is the same thing the maintenance screen shows Ahmed and
 * the same way the camera was verified.
 *
 * Whether it hears "hey toffee" is not testable here and is not attempted:
 * nobody is in the room to say it.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The live app's wake listener started and stayed started.
     *
     * It is not instant. The app clears the fault, then loads 68MB on a
     * background thread, and only records a fault if that fails — so this
     * has to wait long enough for a failure to have appeared if it were
     * going to. Measured at about five seconds on this tablet; the wait is
     * far longer than that.
     *
     * The same gap exists after every reboot: for a few seconds after
     * power-on he does not answer to his name, and a tap is the only way in.
     */
    @Test
    fun theLiveAppStartedItsWakeListener() {
        assumeTrue(
            "no microphone permission, so there is nothing to start",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (Faults.current(context, Faults.WAKE) != null) break
            Thread.sleep(500)
        }

        val fault = Faults.current(context, Faults.WAKE)
        assertNull(
            "the live app could not start listening for its name: " + fault,
            fault,
        )
    }

    /**
     * Nothing else is broken either.
     *
     * The wake word takes the microphone permanently, which is exactly the
     * kind of change that breaks a neighbour quietly — the ears, in this
     * case, which need the same microphone and only get it because the wake
     * listener hands it over.
     */
    @Test
    fun takingTheMicrophonePermanentlyBrokeNothingElse() {
        // The ears and the mouth only. The camera does not touch the
        // microphone, so asserting it here would fail this test for a
        // reason that has nothing to do with the wake word — and a camera
        // fault is a state this tablet is documented as having sometimes.
        for (subsystem in listOf(Faults.EARS, Faults.VOICE)) {
            assertNull(
                subsystem + " is broken: " + Faults.current(context, subsystem),
                Faults.current(context, subsystem),
            )
        }
    }
}

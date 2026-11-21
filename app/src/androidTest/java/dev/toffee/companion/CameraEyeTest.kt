package dev.toffee.companion

import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.toffee.companion.presence.CameraEye
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The camera half, on the hardware it has to run on.
 *
 * None of this can be established off the device: whether a 2016 Samsung
 * exposes its front camera to CameraX at all, whether the bundled ML Kit
 * detector's armeabi-v7a library actually loads, and whether a sample every
 * four seconds is a cadence this SoC can hold without heating up.
 */
@RunWith(AndroidJUnit4::class)
class CameraEyeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Resumed : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun thereIsAFrontCamera() {
        assertTrue(
            "no front camera: presence cannot work on this device",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT),
        )
    }

    /** Review Focus 1, for the camera. In a kiosk there is no Settings app,
     *  so a permission the app cannot grant itself is gone for good. */
    @Test
    fun theCameraPermissionCanBeGrantedWithoutSettings() {
        val granted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (dev.toffee.companion.kiosk.Kiosk.isDeviceOwner(context)) {
            assertTrue("Device Owner must be able to hold CAMERA without Settings", granted)
        }
    }

    /**
     * The throttle is the whole thermal story. Too fast and the SoC cooks;
     * too slow and he has sat down and started working before it notices.
     */
    @Test
    fun itDeliversSamplesAtRoughlyTheRequestedRate() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // Covered by the test above; nothing to measure here.
        }

        val samples = AtomicInteger(0)
        lateinit var owner: Resumed
        lateinit var eye: CameraEye

        instrumentation.runOnMainSync {
            owner = Resumed()
            owner.registry.currentState = Lifecycle.State.RESUMED
            eye = CameraEye(context, owner, everyMs = 2_000) { _, _ -> samples.incrementAndGet() }
            eye.start()
        }

        Thread.sleep(21_000)
        instrumentation.runOnMainSync {
            eye.stop()
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }

        val n = samples.get()
        assertTrue(
            "$n samples in 21s at one every 2s — the camera is not delivering frames",
            n >= 4,
        )
        assertTrue("$n samples in 21s at one every 2s — the throttle is not working", n <= 14)
    }
}

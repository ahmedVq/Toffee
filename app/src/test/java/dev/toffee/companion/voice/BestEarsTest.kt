package dev.toffee.companion.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which ears get used, and when that is decided.
 *
 * Found in review: this was a function called once in `onCreate`, microseconds
 * after an asynchronous permission request. On any build not yet provisioned
 * as Device Owner the microphone permission had not landed yet, so
 * `DeviceEars.available()` was false, and the tablet was pinned to the paid
 * cloud path for the life of the process — with a free recogniser installed.
 */
class BestEarsTest {

    private class FakeEars(var there: Boolean, val name: String) : Ears {
        var asked = 0
        var listened = 0
        override fun available(): Boolean { asked++; return there }
        override suspend fun listen(): Heard { listened++; return Heard.Words(name) }
    }

    @Test
    fun `the device is used when it has a recogniser`() = runBlocking {
        val device = FakeEars(there = true, name = "device")
        val cloud = FakeEars(there = true, name = "cloud")

        assertEquals(Heard.Words("device"), BestEars(device, cloud).listen())
        assertEquals("the free one costs nothing and must win", 0, cloud.listened)
    }

    @Test
    fun `the cloud is used when the device has none`() = runBlocking {
        val device = FakeEars(there = false, name = "device")
        val cloud = FakeEars(there = true, name = "cloud")

        assertEquals(Heard.Words("cloud"), BestEars(device, cloud).listen())
        assertEquals(0, device.listened)
    }

    // The defect itself. A permission granted a second after startup, or a
    // recogniser still installing on first boot, must not pin the tablet to
    // the paid path until somebody reboots it — which in a kiosk could be
    // months.
    @Test
    fun `it asks again every time, so a late permission is noticed`() = runBlocking {
        val device = FakeEars(there = false, name = "device")
        val cloud = FakeEars(there = true, name = "cloud")
        val ears = BestEars(device, cloud)

        assertEquals(Heard.Words("cloud"), ears.listen())
        device.there = true
        assertEquals(Heard.Words("device"), ears.listen())

        assertTrue("availability must be re-read, not cached", device.asked >= 2)
    }

    @Test
    fun `with neither available it says so rather than pretending`() = runBlocking {
        val ears = BestEars(FakeEars(false, "device"), FakeEars(false, "cloud"))

        assertFalse(ears.available())
        assertTrue("a tablet with no ears must say so: ${ears.listen()}", ears.listen() is Heard.Unavailable)
    }

    @Test
    fun `either one being there makes it available`() {
        assertTrue(BestEars(FakeEars(true, "d"), FakeEars(false, "c")).available())
        assertTrue(BestEars(FakeEars(false, "d"), FakeEars(true, "c")).available())
    }
}

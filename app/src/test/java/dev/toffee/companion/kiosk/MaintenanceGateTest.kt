package dev.toffee.companion.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The way back in.
 *
 * Device Owner with lock task means no Settings and no launcher, so if the
 * router password changes and there is no hidden door, the tablet is bricked
 * until a factory reset. The door is a long press in one corner and a PIN.
 */
class CornerHoldTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `five seconds in the corner opens it`() {
        assertTrue(CornerHold.held(10f, 10f, now, now + 5_000))
    }

    @Test
    fun `four and a half seconds is not enough`() {
        assertFalse(CornerHold.held(10f, 10f, now, now + 4_500))
    }

    @Test
    fun `a long hold anywhere else does nothing`() {
        // Otherwise every accidental lean on the panel opens maintenance.
        assertFalse(CornerHold.held(500f, 500f, now, now + 20_000))
        assertFalse(CornerHold.held(10f, 500f, now, now + 20_000))
        assertFalse(CornerHold.held(500f, 10f, now, now + 20_000))
    }

    @Test
    fun `the corner is the corner, not a quadrant`() {
        assertTrue(CornerHold.held(CornerHold.BOX_DP - 1f, CornerHold.BOX_DP - 1f, now, now + 6_000))
        assertFalse(CornerHold.held(CornerHold.BOX_DP + 1f, 10f, now, now + 6_000))
    }

    @Test
    fun `it is the press that must start in the corner`() {
        // The coordinates are where the finger went down. A drag that ends in
        // the corner is not a deliberate hold.
        assertFalse(CornerHold.held(900f, 40f, now, now + 9_000))
    }

    @Test
    fun `the hold is long enough not to happen by accident`() {
        assertTrue(CornerHold.HOLD_MS >= 3_000)
    }
}

class PinTest {

    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `the right pin verifies`() {
        val stored = Pin.hash("4815", salt)
        assertTrue(Pin.verify("4815", salt, stored))
    }

    @Test
    fun `the wrong pin does not`() {
        val stored = Pin.hash("4815", salt)
        assertFalse(Pin.verify("4816", salt, stored))
        assertFalse(Pin.verify("", salt, stored))
        assertFalse(Pin.verify("48150", salt, stored))
    }

    @Test
    fun `the same pin with a different salt hashes differently`() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertFalse(Pin.hash("4815", salt) == Pin.hash("4815", other))
    }

    @Test
    fun `what is stored is not the pin`() {
        val stored = Pin.hash("4815", salt)
        assertFalse("the pin must not be recoverable from storage", stored.contains("4815"))
        assertEquals("sha-256, hex", 64, stored.length)
    }

    @Test
    fun `hashing is stable`() {
        assertEquals(Pin.hash("4815", salt), Pin.hash("4815", salt))
    }

    @Test
    fun `a fresh salt is not predictable`() {
        val salts = (1..20).map { Pin.newSalt().toList() }.toSet()
        assertEquals("every salt must be different", 20, salts.size)
        assertEquals(16, Pin.newSalt().size)
    }
}

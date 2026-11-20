package dev.toffee.companion.kiosk

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The hidden door.
 *
 * Device Owner plus lock task means no Settings, no launcher and no way out.
 * If the Wi-Fi password changes and there is no door, the tablet is a brick
 * until a factory reset. So: hold the top-left corner for five seconds, then
 * a PIN.
 *
 * The coordinates are where the finger went DOWN. A drag that happens to end
 * in the corner is not a deliberate hold, and a lean on the panel should not
 * open maintenance.
 */
object CornerHold {

    const val HOLD_MS = 5_000L

    /** The corner, not a quadrant. Big enough to hit without looking, small
     *  enough that nothing lands there by accident. */
    const val BOX_DP = 80f

    fun held(downXDp: Float, downYDp: Float, downAtMs: Long, nowMs: Long): Boolean =
        downXDp in 0f..BOX_DP &&
            downYDp in 0f..BOX_DP &&
            nowMs - downAtMs >= HOLD_MS
}

/**
 * The PIN behind the door.
 *
 * Salted SHA-256. Not a password-hashing function, and deliberately not
 * pretending to be one: this guards a tablet in a locked office against an
 * accidental press, not against an attacker with the storage in hand — and
 * anyone holding the device can factory reset it regardless.
 */
object Pin {

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(entered: String, salt: ByteArray, stored: String): Boolean {
        val candidate = hash(entered, salt)
        // Constant time for its length, which costs nothing and avoids
        // teaching the next person a bad habit.
        if (candidate.length != stored.length) return false
        var diff = 0
        for (i in candidate.indices) diff = diff or (candidate[i].code xor stored[i].code)
        return diff == 0
    }
}

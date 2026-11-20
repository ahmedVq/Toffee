package dev.toffee.companion.kiosk

import android.content.Context

/**
 * Where the maintenance PIN lives.
 *
 * Set once, during setup, alongside the identity. It used to be set by
 * whoever reached the maintenance screen first — and that screen offers
 * "Leave kiosk" and "Sign this tablet out", so an unattended tablet handed
 * its own escape hatch to the first person who held a corner for five
 * seconds. A tablet on a desk is a tablet anyone can pick up.
 */
class PinStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(): Boolean = prefs.getString(KEY_HASH, null) != null

    fun set(pin: String) {
        val salt = Pin.newSalt()
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, Pin.hash(pin, salt))
            .apply()
    }

    fun verify(entered: String): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        return Pin.verify(entered, salt, stored)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex() =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val PREFS = "toffee.maintenance"
        const val KEY_HASH = "pin"
        const val KEY_SALT = "salt"
    }
}

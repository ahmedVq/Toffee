package dev.toffee.companion.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** What the tablet holds instead of a secret: a session for one narrow,
 *  revocable Supabase identity. Disabling that identity makes all of this
 *  worthless within one refresh cycle, which is the point. */
data class Tokens(
    val access: String,
    val refresh: String,
    val expiresAtMs: Long,
)

interface TokenStore {
    fun save(t: Tokens)
    fun load(): Tokens?
    fun clear()
}

/**
 * Backed by the Android Keystore, hardware-backed where the device offers it.
 *
 * Not unit tested, and deliberately empty of logic: everything that could be
 * wrong about sessions lives in [Session], which is tested against a fake.
 */
class KeystoreTokenStore(context: Context) : TokenStore {

    /**
     * Null when the Keystore entry is unusable.
     *
     * This is the library's best-known failure mode, and it is reached from
     * `MainActivity.onCreate`. Letting it throw crashed the HOME activity,
     * which Android then restarted, which crashed again — a loop with no way
     * to reach the maintenance screen. Losing the session instead costs one
     * re-entry of the identity, which is recoverable.
     */
    private val prefs: SharedPreferences? by lazy {
        try {
            open(context)
        } catch (first: Exception) {
            Log.w(TAG, "encrypted store unusable, rebuilding: $first")
            try {
                context.deleteSharedPreferences(FILE)
                open(context)
            } catch (second: Exception) {
                Log.e(TAG, "encrypted store unavailable; the tablet will ask to sign in: $second")
                null
            }
        }
    }

    private fun open(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(t: Tokens) {
        val prefs = prefs ?: return
        prefs.edit()
            .putString(ACCESS, t.access)
            .putString(REFRESH, t.refresh)
            .putLong(EXPIRES, t.expiresAtMs)
            .apply()
    }

    override fun load(): Tokens? {
        val prefs = prefs ?: return null
        val access = prefs.getString(ACCESS, null) ?: return null
        val refresh = prefs.getString(REFRESH, null) ?: return null
        return Tokens(access, refresh, prefs.getLong(EXPIRES, 0L))
    }

    override fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    private companion object {
        const val TAG = "Toffee"
        const val FILE = "toffee.session"
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val EXPIRES = "expires_at"
    }
}

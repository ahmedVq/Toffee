package dev.toffee.companion.net

import android.content.Context
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

    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "toffee.session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(t: Tokens) {
        prefs.edit()
            .putString(ACCESS, t.access)
            .putString(REFRESH, t.refresh)
            .putLong(EXPIRES, t.expiresAtMs)
            .apply()
    }

    override fun load(): Tokens? {
        val access = prefs.getString(ACCESS, null) ?: return null
        val refresh = prefs.getString(REFRESH, null) ?: return null
        return Tokens(access, refresh, prefs.getLong(EXPIRES, 0L))
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val EXPIRES = "expires_at"
    }
}

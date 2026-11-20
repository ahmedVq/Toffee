package dev.toffee.companion.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/** Where the device's credential stands. */
sealed interface SessionState {
    /** Nothing stored. Someone has to type the identity in. */
    object SignedOut : SessionState

    /** Usable, or at least believed usable — a token may be stale while the
     *  network is down, which is not the same as being refused. */
    object Active : SessionState

    /** The server refused the refresh token. Ahmed disabled this identity, or
     *  it was deleted. Nothing here will work again without re-provisioning,
     *  so the tablet stops asking. */
    object Revoked : SessionState
}

/** Shared OkHttp construction, so the timeout is one number in one place and
 *  tests can shorten it. */
object ToffeeHttp {
    /** Matches the backend's own DEFAULT_TIMEOUT_MS. Past this the agent has
     *  given up anyway and the tablet is waiting on nothing. */
    const val DEFAULT_TIMEOUT_MS = 25_000L

    fun client(timeoutMs: Long = DEFAULT_TIMEOUT_MS): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
}

/**
 * Signing in, staying signed in, and knowing when to stop.
 *
 * The distinction this class exists to draw: a refusal is not an outage. A
 * 400 on a refresh means the identity is gone and the tablet should go inert.
 * A dropped connection means the router rebooted and it should try again in a
 * minute. Treating the second as the first throws away a working credential;
 * treating the first as the second leaves a disabled tablet hammering an
 * endpoint forever.
 */
class Session(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val store: TokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var revoked = false

    fun state(): SessionState = when {
        revoked -> SessionState.Revoked
        store.load() == null -> SessionState.SignedOut
        else -> SessionState.Active
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = mutex.withLock {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        when (val r = token("password", body)) {
            is TokenResult.Ok -> {
                revoked = false
                store.save(r.tokens)
                Result.success(Unit)
            }
            is TokenResult.Refused ->
                Result.failure(IllegalStateException("Refused: wrong identity or password."))
            is TokenResult.Unreachable ->
                Result.failure(IllegalStateException("Could not reach $baseUrl — ${r.cause}"))
        }
    }

    /** A usable access token, refreshing first if the stored one is close to
     *  expiry. Null means "not right now" — check [state] for why. */
    suspend fun accessToken(): String? = mutex.withLock { accessTokenLocked() }

    /** Force a refresh regardless of expiry. Used after a 401, where the
     *  server disagrees with our arithmetic and the server is right. */
    suspend fun refresh(): Boolean = mutex.withLock {
        if (revoked) return@withLock false
        refreshLocked() != null
    }

    fun clear() {
        store.clear()
        revoked = false
    }

    private suspend fun accessTokenLocked(): String? {
        if (revoked) return null
        val current = store.load() ?: return null
        if (clock() < current.expiresAtMs - REFRESH_MARGIN_MS) return current.access
        return refreshLocked()
    }

    private suspend fun refreshLocked(): String? {
        val current = store.load() ?: return null
        val body = buildJsonObject { put("refresh_token", current.refresh) }
        return when (val r = token("refresh_token", body)) {
            is TokenResult.Ok -> {
                store.save(r.tokens)
                r.tokens.access
            }
            is TokenResult.Refused -> {
                // The identity is gone. Keeping the token would only make the
                // tablet look alive while being useless.
                revoked = true
                store.clear()
                null
            }
            is TokenResult.Unreachable -> null
        }
    }

    private sealed interface TokenResult {
        data class Ok(val tokens: Tokens) : TokenResult
        object Refused : TokenResult
        data class Unreachable(val cause: Exception) : TokenResult
    }

    private suspend fun token(grant: String, body: JsonObject): TokenResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=$grant")
                .addHeader("apikey", apiKey)
                .post(body.toString().toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    when {
                        res.isSuccessful -> parse(text)?.let { TokenResult.Ok(it) }
                            ?: TokenResult.Unreachable(IllegalStateException("unparseable token"))
                        // 4xx is the server saying no. 5xx is the server
                        // having a bad day, which is an outage.
                        res.code in 400..499 -> TokenResult.Refused
                        else -> TokenResult.Unreachable(
                            IllegalStateException("HTTP ${res.code}"),
                        )
                    }
                }
            } catch (e: Exception) {
                TokenResult.Unreachable(e)
            }
        }

    private fun parse(text: String): Tokens? = try {
        val json = LENIENT.parseToJsonElement(text) as JsonObject
        val access = json["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val refresh = json["refresh_token"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        if (access == null || refresh == null) {
            null
        } else {
            Tokens(access, refresh, clock() + expiresIn * 1000L)
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Refresh this far ahead of expiry, so a request made now cannot
         *  land after the token dies. */
        const val REFRESH_MARGIN_MS = 60_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

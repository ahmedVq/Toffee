package dev.toffee.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.toffee.companion.face.Mood
import dev.toffee.companion.net.KeystoreTokenStore
import dev.toffee.companion.net.Session
import dev.toffee.companion.net.SessionState
import dev.toffee.companion.net.ToffeeClient
import dev.toffee.companion.net.ToffeeHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real tablet, the real network, the real backend.
 *
 * The unit tests prove the logic against MockWebServer; they cannot prove
 * that TLS negotiates on Android 8.1, that DNS resolves, that the Android
 * Keystore actually persists a refresh token across process death, or that
 * the deployed Edge Functions accept this device's JWT. Those are exactly the
 * things that were wrong, and none of them are visible in a screenshot.
 *
 * The password is never in this file. Pass it in:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.toffeePassword=<password>
 *
 * Without it these tests skip rather than fail, so a plain run stays green.
 */
@RunWith(AndroidJUnit4::class)
class LiveBackendTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val email: String
        get() = InstrumentationRegistry.getArguments().getString("toffeeEmail")
            ?: "tablet@toffee.local"

    private val password: String?
        get() = InstrumentationRegistry.getArguments().getString("toffeePassword")

    private fun session(store: KeystoreTokenStore = KeystoreTokenStore(context)) = Session(
        http = ToffeeHttp.client(),
        baseUrl = ToffeeConfig.baseUrl,
        apiKey = ToffeeConfig.publishableKey,
        store = store,
    )

    @Test
    fun theTabletCanReachItsOwnProject() = runBlocking {
        // No credential needed: an unauthenticated POST to the auth endpoint
        // still proves DNS, TLS and routing work from this device.
        val response = ToffeeHttp.client(10_000).newCall(
            okhttp3.Request.Builder()
                .url(ToffeeConfig.functionUrl("turn"))
                .addHeader("apikey", ToffeeConfig.publishableKey)
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build(),
        ).execute()
        response.use {
            assertTrue(
                "reached ${ToffeeConfig.baseUrl} and got HTTP ${it.code}",
                it.code in 200..499,
            )
        }
    }

    @Test
    fun signInStoresAnIdentityThatSurvivesTheProcess() = runBlocking {
        val pw = password
        assumeTrue("no toffeePassword argument; skipping", pw != null)

        val store = KeystoreTokenStore(context)
        store.clear()

        val s = session(store)
        val result = s.signIn(email, pw!!)
        assertTrue("sign in failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(SessionState.Active, s.state())

        // A fresh store object reads what the Keystore actually persisted,
        // which is what a cold start does.
        val reread = KeystoreTokenStore(context).load()
        assertNotNull("the refresh token did not persist", reread)
        assertEquals(SessionState.Active, session(KeystoreTokenStore(context)).state())
    }

    @Test
    fun theAgentAnswersThisDevice() = runBlocking {
        val pw = password
        assumeTrue("no toffeePassword argument; skipping", pw != null)

        val store = KeystoreTokenStore(context)
        val s = session(store)
        if (s.state() != SessionState.Active) {
            assertTrue(s.signIn(email, pw!!).isSuccess)
        }

        val client = ToffeeClient(
            ToffeeHttp.client(),
            ToffeeConfig.baseUrl,
            ToffeeConfig.publishableKey,
            s,
        )
        val reply = client.turn("what is left on my task list")

        assertFalse(
            "the agent was unreachable from the tablet: ${reply.speak}",
            reply.offline,
        )
        assertTrue("it said nothing at all", !reply.speak.isNullOrBlank())
        // Any mood is fine; what matters is that it is a real one and the
        // reply parsed. NEUTRAL is what an unknown mood falls back to.
        assertNotNull(reply.mood)
        println("TOFFEE SAID [${reply.mood}]: ${reply.speak}")
    }

    @Test
    fun anEventAlsoRoundTrips() = runBlocking {
        val pw = password
        assumeTrue("no toffeePassword argument; skipping", pw != null)

        val s = session()
        if (s.state() != SessionState.Active) assertTrue(s.signIn(email, pw!!).isSuccess)

        val client = ToffeeClient(
            ToffeeHttp.client(),
            ToffeeConfig.baseUrl,
            ToffeeConfig.publishableKey,
            s,
        )
        val reply = client.event("arrived", mapOf("battery" to 54))
        assertFalse("event was unreachable: ${reply.speak}", reply.offline)
        println("TOFFEE ON ARRIVAL [${reply.mood}]: ${reply.speak ?: "(said nothing)"}")
    }

    @Test
    fun aWrongPasswordIsRefusedNotMistakenForAnOutage() = runBlocking {
        val store = KeystoreTokenStore(context)
        val saved = store.load()
        try {
            val s = session(store)
            val result = s.signIn(email, "definitely-not-the-password")
            assertTrue(result.isFailure)
            assertTrue(
                "a refusal must not be reported as unreachable: " +
                    result.exceptionOrNull()?.message,
                result.exceptionOrNull()?.message?.contains("Refused") == true,
            )
        } finally {
            saved?.let { store.save(it) }
        }
    }

    @Test
    fun theApkCarriesNoSecret() {
        assertTrue(ToffeeConfig.publishableKey.startsWith("sb_publishable_"))
        assertFalse(ToffeeConfig.publishableKey.startsWith("sb_secret_"))
        assertEquals(Mood.NEUTRAL, Mood.parse(null))
    }
}

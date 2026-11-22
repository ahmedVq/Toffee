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

    /**
     * The server route, end to end, against a row seeded from the laptop.
     *
     * Pass the marker the seeder used:
     *   -Pandroid.testInstrumentationRunnerArguments.toffeeReminderMark=zz-abc
     */
    @Test
    fun thePollerFindsARealFiredReminder() = runBlocking {
        val pw = password
        val mark = InstrumentationRegistry.getArguments().getString("toffeeReminderMark")
        assumeTrue("no credential or marker; skipping", pw != null && mark != null)

        val s = session()
        if (s.state() != SessionState.Active) assertTrue(s.signIn(email, pw!!).isSuccess)

        val poller = dev.toffee.companion.reminders.ReminderPoller(
            http = ToffeeHttp.client(),
            baseUrl = ToffeeConfig.baseUrl,
            apiKey = ToffeeConfig.publishableKey,
            session = s,
        )
        val found = poller.pollOnce()
        println("POLLED ${found.size}: " + found.joinToString { it.text })
        assertTrue(
            "the tablet did not see the reminder seeded as $mark",
            found.any { it.text.contains(mark!!) },
        )

        // And it does not hand the same one over twice.
        assertTrue(
            "a second poll must not repeat it",
            poller.pollOnce().none { it.text.contains(mark!!) },
        )
    }

    /**
     * The device may read the live release row and nothing else.
     *
     * With no row marked active this must be an empty list, not a 401 and not
     * a 403 — a tablet that cannot read the table at all would report "nothing
     * new" for ever and the self-update would be silently dead.
     */
    @Test
    fun theTabletCanAskWhichBuildItShouldBeRunning() = runBlocking {
        val pw = password
        val s = session()
        if (s.state() != SessionState.Active) {
            assumeTrue("no credential; skipping", pw != null)
            assertTrue(s.signIn(email, pw!!).isSuccess)
        }

        val token = s.accessToken()
        assertTrue("no access token", token != null)

        val response = ToffeeHttp.client(10_000).newCall(
            okhttp3.Request.Builder()
                .url(
                    ToffeeConfig.baseUrl +
                        "/rest/v1/app_release?select=version_code,url,sha256&active=eq.true",
                )
                .addHeader("apikey", ToffeeConfig.publishableKey)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build(),
        ).execute()

        response.use {
            val body = it.body?.string().orEmpty()
            assertEquals("RLS refused the device its own release row: " + body, 200, it.code)
            println("APP_RELEASE: " + body)
        }
    }

    /**
     * And that it can fetch the file that row points at.
     *
     * The build lives in a private Storage bucket, so reading the row and
     * reading the bytes are two different permissions. Without the second
     * one the maintenance screen says "Could not download version N" — which
     * reads as a network fault and is actually a policy that was never
     * written. Asks for the first kilobyte rather than the whole APK: this
     * is a question about permission, not about bandwidth.
     */
    @Test
    fun theTabletCanFetchTheBuildItWasToldAbout() = runBlocking {
        val pw = password
        val s = session()
        if (s.state() != SessionState.Active) {
            assumeTrue("no credential; skipping", pw != null)
            assertTrue(s.signIn(email, pw!!).isSuccess)
        }
        val token = s.accessToken()
        assertTrue("no access token", token != null)

        val rows = ToffeeHttp.client(10_000).newCall(
            okhttp3.Request.Builder()
                .url(ToffeeConfig.baseUrl + "/rest/v1/app_release?select=url&active=eq.true")
                .addHeader("apikey", ToffeeConfig.publishableKey)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build(),
        ).execute().use { it.body?.string().orEmpty() }

        val url = Regex("\"url\":\"([^\"]+)\"").find(rows)?.groupValues?.get(1)
        assumeTrue("no active release row to fetch; skipping", url != null)

        // The client the Updater itself uses. At 10s this timed out against
        // Storage before it had an answer to report, which is a measurement
        // of the tablet's radio rather than of the policy.
        ToffeeHttp.client().newCall(
            okhttp3.Request.Builder()
                .url(url!!)
                .addHeader("apikey", ToffeeConfig.publishableKey)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Range", "bytes=0-1023")
                .get()
                .build(),
        ).execute().use {
            val body = it.body?.string().orEmpty()
            assertTrue(
                "the device may read the release row but not the build it names: " +
                    "HTTP " + it.code + " " + body,
                it.code == 200 || it.code == 206,
            )
        }
    }

    /**
     * The whole self-update path, end to end, against a build staged to be
     * refused.
     *
     * [dev.toffee.companion.update.Install] is pure and unit-tested, but
     * every one of those tests hands it a `Downloaded` built by hand. This is
     * the only thing that proves the rule fires on bytes that actually
     * crossed the network and an APK Android actually parsed — and that the
     * 16MB transfer finishes inside the Updater's own timeout, which is the
     * difference between a real refusal and "could not download".
     *
     * Safe to run: the active row claims version 2 and the file it points at
     * is version 1, so two separate rules refuse it before `install()` is
     * reached. Nothing is installed.
     */
    @Test
    fun theUpdateGateRefusesABuildThatLiesAboutItsVersion() = runBlocking {
        val pw = password
        val s = session()
        if (s.state() != SessionState.Active) {
            assumeTrue("no credential; skipping", pw != null)
            assertTrue(s.signIn(email, pw!!).isSuccess)
        }

        val started = System.currentTimeMillis()
        val said = dev.toffee.companion.update.Updater(
            http = ToffeeHttp.client(),
            baseUrl = ToffeeConfig.baseUrl,
            apiKey = ToffeeConfig.publishableKey,
            session = s,
            context = context,
        ).check()
        val took = System.currentTimeMillis() - started

        // Recorded where a passing run can still show it: this is the number
        // that says whether a real update could ever complete on this radio.
        context.getSharedPreferences("toffee.bench", android.content.Context.MODE_PRIVATE)
            .edit().putLong("update_ms", took).putString("update_said", said).apply()

        assumeTrue("no build staged to check; skipping", !said.startsWith("Nothing new"))
        assertTrue(
            "the gate let a mislabelled build through, or never got to it: " + said,
            said.contains("says version 1") && said.contains("published as 2"),
        )
    }

    /**
     * The stored identity is readable by a fresh [Session] over the same
     * Keystore.
     *
     * This is what makes the tablet work after a reboot, and it is the one
     * thing no unit test can stand in for: EncryptedSharedPreferences is
     * backed by hardware here, and KeystoreTokenStore is deliberately written
     * to swallow a Keystore fault and return null rather than crash the HOME
     * activity in a loop. That swallowing means a broken store looks exactly
     * like a tablet nobody has signed in yet.
     */
    @Test
    fun theStoredIdentityCanBeReadBack() {
        val prefs = context.getSharedPreferences("toffee.session", android.content.Context.MODE_PRIVATE)
        val store = KeystoreTokenStore(context)
        val loaded = store.load()
        val state = session(store).state()

        assertEquals(
            "the Keystore-backed store returned nothing. encrypted file has " +
                prefs.all.size + " entries; session state was " + state,
            SessionState.Active,
            state,
        )
        assertTrue("a session with no access token is not a session", loaded!!.access.isNotEmpty())
    }

    /**
     * How long a turn takes, from this device, on this network.
     *
     * The spec asks to "stream the LLM response and begin speaking at the
     * first sentence boundary rather than waiting for the full reply", and
     * also to "measure end-to-end before tuning anything else". The second
     * instruction decides the first: /turn currently answers with one JSON
     * body, and whether that is worth replacing with SSE depends entirely on
     * this number.
     *
     * The elapsed time is written to a prefs file rather than asserted
     * against a tight bound, because a passing assertion never shows its
     * message and the number is the whole point. The assertion is only a
     * ceiling: past this the tablet has been staring silently at him for
     * long enough that the joke is dead whatever the cause.
     */
    @Test
    fun aTurnComesBackFastEnoughToBeFunny() = runBlocking {
        val s = session()
        assumeTrue("the tablet is not signed in; nothing to measure", s.state() == SessionState.Active)

        val client = dev.toffee.companion.net.ToffeeClient(
            ToffeeHttp.client(),
            ToffeeConfig.baseUrl,
            ToffeeConfig.publishableKey,
            s,
        )

        // The prompt is fixed, and must stay fixed. This number is compared
        // against previous runs to decide whether a change made him faster
        // or slower; asking him something else makes it a different
        // measurement wearing the same name. Briefly replaced with a memory
        // probe, which produced a much longer reply and an apparent 2.3s
        // regression that was really just more tokens.
        //
        // It was changed once more, deliberately, when /turn began to
        // stream: at one sentence, time-to-first-word and time-to-done are
        // the same number and the streaming win is invisible. Two sentences
        // separates them. So turn_ms before 2026-11-22 (2957ms, 3051ms) is
        // NOT comparable with turn_ms after it. turn_first_ms has no
        // predecessor at all -- before streaming, the first word arrived
        // only when the whole reply did, so it was turn_ms by construction.
        val started = System.currentTimeMillis()
        // The number that actually decides whether a joke lands. The whole
        // reply still has to arrive, but Ahmed stops waiting the moment the
        // first word comes out of the speaker -- and since /turn streams,
        // that is no longer the same measurement as the turn's length.
        var firstAt = 0L
        val reply = client.turn("Say two short sentences back to me.") { _, _ ->
            if (firstAt == 0L) firstAt = System.currentTimeMillis()
        }
        val took = System.currentTimeMillis() - started
        val toFirst = if (firstAt == 0L) -1L else firstAt - started

        context.getSharedPreferences("toffee.bench", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("turn_ms", took)
            .putLong("turn_first_ms", toFirst)
            .putString("turn_reply", reply.speak ?: "(silence)")
            .putBoolean("turn_offline", reply.offline)
            .apply()

        assertFalse("the agent was unreachable, so this measures nothing", reply.offline)
        assertTrue("a turn took " + took + "ms, which is past any comic timing", took < 20_000)
    }

    /**
     * The seeded history actually reaches the model.
     *
     * Unit tests prove `renderSystem` prints the facts and `buildContext`
     * fetches them. Neither can prove the assembled prompt reached a real
     * model and came back with him using it — which is the only form of
     * "he knows who Ahmed is" that counts.
     *
     * Deliberately not the benchmark: this asks an open question and gets a
     * long answer, which is a fine thing to read and a useless thing to time.
     */
    @Test
    fun heKnowsWhoAhmedIs() = runBlocking {
        val s = session()
        assumeTrue("the tablet is not signed in", s.state() == SessionState.Active)

        val reply = dev.toffee.companion.net.ToffeeClient(
            ToffeeHttp.client(),
            ToffeeConfig.baseUrl,
            ToffeeConfig.publishableKey,
            s,
        ).turn("In one sentence: what do you know about me?")

        context.getSharedPreferences("toffee.bench", android.content.Context.MODE_PRIVATE)
            .edit().putString("knows", reply.speak ?: "(silence)").apply()

        assumeTrue("the agent was unreachable", !reply.offline)
        val said = (reply.speak ?: "").lowercase()
        // Any one of the seeded facts. Asserting on a particular one would
        // be asserting on the model's choice of material, which is his.
        val touchstones = listOf("ahmed", "free tier", "office", "task list", "tablet", "later")
        assertTrue(
            "he answered without using anything he was told about Ahmed: " + reply.speak,
            touchstones.any { it in said },
        )
    }

    @Test
    fun theApkCarriesNoSecret() {
        assertTrue(ToffeeConfig.publishableKey.startsWith("sb_publishable_"))
        assertFalse(ToffeeConfig.publishableKey.startsWith("sb_secret_"))
        assertEquals(Mood.NEUTRAL, Mood.parse(null))
    }
}

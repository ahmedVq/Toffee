package dev.toffee.companion.net

import dev.toffee.companion.face.Mood
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What the tablet says to the agent, and what it does when the agent is not
 * there.
 *
 * The second half is most of this file. A companion that hangs, crashes, or
 * silently stops answering when the Wi-Fi drops is worse than one that admits
 * it, and the Wi-Fi will drop.
 */
class ToffeeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var store: TokenStore
    private var now = 1_700_000_000_000L

    private class MemoryStore : TokenStore {
        var tokens: Tokens? = null
        override fun save(t: Tokens) { tokens = t }
        override fun load(): Tokens? = tokens
        override fun clear() { tokens = null }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = MemoryStore().also { it.save(Tokens("acc-live", "ref-live", now + 3_600_000L)) }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(timeoutMs: Long = 2_000): ToffeeClient {
        val base = server.url("/").toString().trimEnd('/')
        val http = ToffeeHttp.client(timeoutMs)
        return ToffeeClient(
            http = http,
            baseUrl = base,
            apiKey = "sb_publishable_test",
            session = Session(http, base, "sb_publishable_test", store, clock = { now }),
        )
    }

    private fun reply(json: String) = MockResponse().setBody(json)

    // ------------------------------------------------------------ the happy path

    @Test
    fun `turn posts the text and the bearer token`() = runBlocking {
        server.enqueue(reply("""{"reply":"You have one task.","mood":"smug","calls":[]}"""))

        val r = client().turn("what am I meant to be doing")

        assertEquals("You have one task.", r.speak)
        assertEquals(Mood.SMUG, r.mood)
        assertFalse(r.offline)

        val req = server.takeRequest()
        assertEquals("/functions/v1/turn", req.path)
        assertEquals("Bearer acc-live", req.getHeader("Authorization"))
        assertEquals("sb_publishable_test", req.getHeader("apikey"))
        assertTrue(req.body.readUtf8().contains("what am I meant to be doing"))
    }

    @Test
    fun `event posts the kind and its payload`() = runBlocking {
        server.enqueue(reply("""{"speak":"Back, are you.","mood":"frustrated"}"""))

        val r = client().event("arrived", mapOf("battery" to 82))

        assertEquals(Mood.FRUSTRATED, r.mood)
        val req = server.takeRequest()
        assertEquals("/functions/v1/event", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"kind\":\"arrived\""))
        assertTrue(body.contains("82"))
    }

    @Test
    fun `a null speak means there is nothing to say`() = runBlocking {
        server.enqueue(reply("""{"speak":null,"mood":"neutral","why":"cooldown"}"""))

        val r = client().event("idle_check")
        assertNull(r.speak)
        assertEquals(Mood.NEUTRAL, r.mood)
        assertFalse("silence is not an outage", r.offline)
    }

    // ------------------------------------------------- the degraded backend
    //
    // Observed in production: when the model is unavailable the backend still
    // answers 200, but the reply has no mood, or a mood this app has never
    // heard of. The face must render something sane rather than go black.

    @Test
    fun `a reply with no mood field is neutral`() = runBlocking {
        server.enqueue(reply("""{"reply":"The thinking part of me is unavailable."}"""))
        val r = client().turn("hello")
        assertEquals(Mood.NEUTRAL, r.mood)
        assertNotNull(r.speak)
    }

    @Test
    fun `a reply with an unknown mood is neutral`() = runBlocking {
        server.enqueue(reply("""{"reply":"hm","mood":"apoplectic"}"""))
        assertEquals(Mood.NEUTRAL, client().turn("hello").mood)
    }

    @Test
    fun `a reply with unknown extra fields still parses`() = runBlocking {
        server.enqueue(reply("""{"reply":"hm","mood":"sad","calls":[],"spent":{"in":1,"out":2}}"""))
        assertEquals(Mood.SAD, client().turn("hello").mood)
    }

    @Test
    fun `a reply that is not json becomes the offline reply`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>502 Bad Gateway</html>"))
        val r = client().turn("hello")
        assertTrue(r.offline)
        assertEquals(Mood.OFFLINE, r.mood)
    }

    @Test
    fun `a server error becomes the offline reply`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(client().turn("hello").offline)
    }

    // ------------------------------------------------------------ no network

    @Test
    fun `turn returns the offline reply when the network is gone`() = runBlocking {
        server.shutdown()

        val r = client().turn("hello")
        assertTrue(r.offline)
        assertEquals(Mood.OFFLINE, r.mood)
        assertTrue("it must still say something", !r.speak.isNullOrBlank())
    }

    @Test
    fun `the client recovers on the next request after an outage`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(reply("""{"reply":"There you are.","mood":"happy"}"""))

        assertTrue("the first attempt should fail", c.turn("hello").offline)

        val second = c.turn("hello again")
        assertFalse("no restart should be needed", second.offline)
        assertEquals(Mood.HAPPY, second.mood)
    }

    @Test
    fun `a request that stalls gives up rather than hanging`() = runBlocking {
        // Three seconds, not thirty: long enough that a 500ms client cannot
        // possibly have waited for it, short enough that MockWebServer can
        // still drain the queue at teardown.
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))

        val started = System.currentTimeMillis()
        val r = client(timeoutMs = 500).turn("hello")
        val took = System.currentTimeMillis() - started

        assertTrue(r.offline)
        assertTrue("it waited ${took}ms; comic timing dies long before that", took < 2_000)
    }

    // ----------------------------------------------------------- expired auth

    @Test
    fun `a 401 refreshes once and retries once`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(reply("""{"access_token":"acc-2","refresh_token":"ref-2","expires_in":3600}"""))
        server.enqueue(reply("""{"reply":"fine","mood":"neutral"}"""))

        val r = client().turn("hello")

        assertEquals("fine", r.speak)
        assertEquals(3, server.requestCount)
        assertEquals("/functions/v1/turn", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.contains("grant_type=refresh_token"))
        assertEquals("Bearer acc-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 401 that survives the retry does not loop`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(reply("""{"access_token":"acc-2","refresh_token":"ref-2","expires_in":3600}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        val r = client().turn("hello")

        assertEquals("exactly one retry, then stop", 3, server.requestCount)
        assertEquals(Mood.OFFLINE, r.mood)
    }

    @Test
    fun `a revoked session does not reach the network at all`() = runBlocking {
        store.clear()
        server.enqueue(MockResponse().setResponseCode(400))

        val c = client()
        val r = c.turn("hello")

        assertEquals("signed out means no requests", 0, server.requestCount)
        assertEquals(Mood.OFFLINE, r.mood)
    }

    // Found on the device, not here: /turn answers with `reply` and /event
    // with `speak`, and the client read only `speak`. Every tap was silent
    // while every test passed, because the tests had invented the field name
    // as confidently as the client had. These pin the real wire format.
    @Test
    fun `turn speaks through the reply field`() = runBlocking {
        server.enqueue(reply("""{"reply":"You have one task.","mood":"smug","calls":[]}"""))
        assertEquals("You have one task.", client().turn("x").speak)
    }

    @Test
    fun `event speaks through the speak field`() = runBlocking {
        server.enqueue(reply("""{"speak":"Back, are you.","mood":"frustrated"}"""))
        assertEquals("Back, are you.", client().event("arrived").speak)
    }

    // --------------------------------------------------------- what it says

    @Test
    fun `the offline lines do not pretend to be thinking`() {
        assertTrue("one canned line would wear out in a week", Offline.lines.size >= 4)
        for (line in Offline.lines) {
            assertTrue("an offline line must say something", line.isNotBlank())
            for (pretending in listOf("thinking", "let me", "one moment", "working on")) {
                assertFalse(
                    "\"$line\" pretends to be thinking; the spec says admitting it is better",
                    line.contains(pretending, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `it does not say the same thing every time`() {
        val seen = (1..60).map { Offline.line() }.toSet()
        assertTrue("only ${seen.size} distinct lines in 60 draws", seen.size >= 3)
    }
}

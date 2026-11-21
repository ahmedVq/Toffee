package dev.toffee.companion.presence

import dev.toffee.companion.net.Session
import dev.toffee.companion.net.ToffeeHttp
import dev.toffee.companion.net.TokenStore
import dev.toffee.companion.net.Tokens
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What leaves the tablet when it sees him.
 *
 * The spec's hardest line, and the reason the whole camera half can exist at
 * all: "Only `{present: bool, at: timestamp}` leaves the tablet. No frame, no
 * descriptor, no identity." A unit test cannot prove a bitmap was never
 * written to disk, but it can prove the request body has exactly three keys
 * in it and never grows a fourth — which is how that rule would actually be
 * broken: by somebody helpfully adding `confidence` or `faces` one day.
 */
class PresenceReporterTest {

    private lateinit var server: MockWebServer
    private val now = 1_700_000_000_000L

    private class MemoryStore(var tokens: Tokens?) : TokenStore {
        override fun save(t: Tokens) { tokens = t }
        override fun load(): Tokens? = tokens
        override fun clear() { tokens = null }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reporter(signedIn: Boolean = true): PresenceReporter {
        val base = server.url("/").toString().trimEnd('/')
        val http = ToffeeHttp.client(2_000)
        val store = MemoryStore(
            if (signedIn) Tokens("acc-live", "ref-live", now + 3_600_000) else null,
        )
        return PresenceReporter(
            http = http,
            baseUrl = base,
            apiKey = "sb_publishable_test",
            session = Session(http, base, "sb_publishable_test", store, clock = { now }),
        )
    }

    @Test
    fun `only a flag and a time ever leave the tablet`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        reporter().report(present = true, atMs = now, durationS = 42)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(
            "a fourth key here is a privacy regression, not a feature",
            setOf("present", "at", "duration_s"),
            body.keys,
        )
        assertEquals("true", body["present"]!!.jsonPrimitive.content)
        assertEquals("42", body["duration_s"]!!.jsonPrimitive.content)
    }

    @Test
    fun `it inserts into presence_events`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        reporter().report(present = false, atMs = now, durationS = null)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue("wrong table: ${request.path}", request.path!!.startsWith("/rest/v1/presence_events"))
    }

    /**
     * RLS grants the device insert on presence_events and no select at all,
     * so asking PostgREST for the row back would be refused and would look
     * exactly like a refused insert. This cost an afternoon once already.
     */
    @Test
    fun `it does not ask for the row back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        reporter().report(present = true, atMs = now, durationS = null)

        val prefer = server.takeRequest().getHeader("Prefer").orEmpty()
        assertTrue("Prefer was '$prefer'", prefer.contains("return=minimal"))
    }

    @Test
    fun `an unknown duration is sent as null rather than a zero`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        reporter().report(present = true, atMs = now, durationS = null)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue("a sitting of unknown length is not a sitting of zero", body["duration_s"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `a signed out tablet reports nothing`() = runBlocking {
        reporter(signedIn = false).report(present = true, atMs = now, durationS = null)
        assertNull("nothing to send it with", server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `a server that refuses is not a crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        // Presence is a nice-to-have. Losing one sample must never take the
        // face down with it.
        reporter().report(present = true, atMs = now, durationS = null)
        assertTrue(true)
    }
}

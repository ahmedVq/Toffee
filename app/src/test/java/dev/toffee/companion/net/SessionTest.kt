package dev.toffee.companion.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The device's credential, and what happens when it stops being one.
 *
 * Real HTTP against MockWebServer rather than a mocked client: the thing most
 * likely to be wrong here is the shape of a request or the handling of a
 * status code, and a mock would happily agree with whatever the code does.
 */
class SessionTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeTokenStore
    private var now = 1_700_000_000_000L

    private class FakeTokenStore : TokenStore {
        var tokens: Tokens? = null
        override fun save(t: Tokens) { tokens = t }
        override fun load(): Tokens? = tokens
        override fun clear() { tokens = null }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FakeTokenStore()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun session() = Session(
        http = ToffeeHttp.client(timeoutMs = 2_000),
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = "sb_publishable_test",
        store = store,
        clock = { now },
    )

    private fun tokenBody(access: String, refresh: String, expiresIn: Int = 3600) =
        MockResponse().setBody(
            """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn}""",
        )

    // ------------------------------------------------------------- signing in

    @Test
    fun `sign in stores the refresh token`() = runBlocking {
        server.enqueue(tokenBody("acc-1", "ref-1"))

        val s = session()
        assertTrue(s.signIn("tablet@toffee.local", "hunter2").isSuccess)

        val saved = store.load()
        assertNotNull(saved)
        assertEquals("ref-1", saved!!.refresh)
        assertEquals("acc-1", saved.access)
        assertEquals(now + 3_600_000L, saved.expiresAtMs)
        assertEquals(SessionState.Active, s.state())

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("grant_type=password"))
        assertEquals("sb_publishable_test", req.getHeader("apikey"))
        assertTrue(req.body.readUtf8().contains("tablet@toffee.local"))
    }

    @Test
    fun `signing in with the wrong password fails and stays signed out`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        val s = session()
        assertTrue(s.signIn("tablet@toffee.local", "wrong").isFailure)
        assertNull(store.load())
        assertEquals(SessionState.SignedOut, s.state())
    }

    // --------------------------------------------------------------- refresh

    @Test
    fun `a valid token is reused without a network call`() = runBlocking {
        store.save(Tokens("acc-live", "ref-live", now + 3_600_000L))

        assertEquals("acc-live", session().accessToken())
        assertEquals("a live token must not cost a round trip", 0, server.requestCount)
    }

    @Test
    fun `a token close to expiry is refreshed first`() = runBlocking {
        // Inside the sixty-second margin: still technically valid, but a
        // request made now could easily land after it dies.
        store.save(Tokens("acc-old", "ref-old", now + 30_000L))
        server.enqueue(tokenBody("acc-new", "ref-new"))

        assertEquals("acc-new", session().accessToken())
        assertEquals("ref-new", store.load()!!.refresh)
        assertTrue(server.takeRequest().path!!.contains("grant_type=refresh_token"))
    }

    @Test
    fun `an expired token is refreshed`() = runBlocking {
        store.save(Tokens("acc-dead", "ref-ok", now - 1L))
        server.enqueue(tokenBody("acc-new", "ref-new"))

        assertEquals("acc-new", session().accessToken())
    }

    // ------------------------------------------------------------- revocation
    //
    // The spec's answer to "someone picked it up" is disabling the auth user.
    // The tablet must then go inert — not sit on a desk hammering the auth
    // endpoint every twenty seconds until someone notices the bill.

    @Test
    fun `a refresh rejected with 400 revokes the session and stops asking`() = runBlocking {
        store.save(Tokens("acc-dead", "ref-revoked", now - 1L))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        val s = session()
        assertNull(s.accessToken())
        assertEquals(SessionState.Revoked, s.state())
        assertNull("a revoked token must not be kept", store.load())

        assertNull(s.accessToken())
        assertEquals("it asked again after being told no", 1, server.requestCount)
    }

    @Test
    fun `an outage does not look like revocation`() = runBlocking {
        store.save(Tokens("acc-dead", "ref-ok", now - 1L))
        server.shutdown()

        val s = session()
        assertNull("no token is available right now", s.accessToken())
        assertEquals(
            "a dead router must not throw away a working credential",
            SessionState.Active,
            s.state(),
        )
        assertNotNull(store.load())
    }

    @Test
    fun `a server error does not look like revocation either`() = runBlocking {
        store.save(Tokens("acc-dead", "ref-ok", now - 1L))
        server.enqueue(MockResponse().setResponseCode(503))

        val s = session()
        assertNull(s.accessToken())
        assertEquals(SessionState.Active, s.state())
        assertNotNull(store.load())
    }

    @Test
    fun `it recovers once the network comes back`() = runBlocking {
        store.save(Tokens("acc-dead", "ref-ok", now - 1L))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(tokenBody("acc-new", "ref-new"))

        val s = session()
        assertNull(s.accessToken())
        assertEquals("acc-new", s.accessToken())
        assertEquals(SessionState.Active, s.state())
    }

    @Test
    fun `clearing signs it out`() = runBlocking {
        store.save(Tokens("a", "r", now + 3_600_000L))
        val s = session()
        s.clear()
        assertEquals(SessionState.SignedOut, s.state())
        assertNull(s.accessToken())
    }

    @Test
    fun `with nothing stored it is simply signed out`() = runBlocking {
        val s = session()
        assertEquals(SessionState.SignedOut, s.state())
        assertNull(s.accessToken())
        assertEquals(0, server.requestCount)
    }
}

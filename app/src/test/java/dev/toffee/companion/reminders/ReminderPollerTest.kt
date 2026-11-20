package dev.toffee.companion.reminders

import dev.toffee.companion.net.Session
import dev.toffee.companion.net.ToffeeHttp
import dev.toffee.companion.net.TokenStore
import dev.toffee.companion.net.Tokens
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The server half of the two routes.
 *
 * It polls rather than holding a Realtime socket open: on an API-27 device a
 * twenty-second poll is one fewer moving part than a Phoenix channel that
 * drops and must reconnect, and the local alarm already covers the case where
 * the network is gone entirely.
 */
class ReminderPollerTest {

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

    private fun poller(signedIn: Boolean = true): ReminderPoller {
        val base = server.url("/").toString().trimEnd('/')
        val http = ToffeeHttp.client(2_000)
        val store = MemoryStore(
            if (signedIn) Tokens("acc-live", "ref-live", now + 3_600_000) else null,
        )
        return ReminderPoller(
            http = http,
            baseUrl = base,
            apiKey = "sb_publishable_test",
            session = Session(http, base, "sb_publishable_test", store, clock = { now }),
        )
    }

    private fun rows(vararg json: String) =
        MockResponse().setBody("[${json.joinToString(",")}]")

    private fun row(id: String, fired: String, text: String = "stand up") =
        """{"id":"$id","text":"$text","due_at":"$fired","fired_at":"$fired"}"""

    @Test
    fun `it asks postgrest for reminders that have fired`() = runBlocking {
        server.enqueue(rows())
        poller().pollOnce()

        val path = server.takeRequest().path!!
        assertTrue("wrong table: $path", path.startsWith("/rest/v1/reminders"))
        assertTrue("must filter on fired_at: $path", path.contains("fired_at"))
    }

    @Test
    fun `it returns what came back`() = runBlocking {
        server.enqueue(rows(row("a", "2026-11-20T10:00:00+00:00")))

        val got = poller().pollOnce()
        assertEquals(1, got.size)
        assertEquals("a", got[0].id)
        assertEquals("stand up", got[0].text)
        assertTrue(got[0].firedAtMs != null)
    }

    @Test
    fun `the cursor moves past the newest one it has seen`() = runBlocking {
        val p = poller()
        server.enqueue(rows(row("a", "2026-11-20T10:00:00+00:00")))
        p.pollOnce()
        server.takeRequest()

        server.enqueue(rows())
        p.pollOnce()
        val second = server.takeRequest().path!!

        // Assert on the value, not its spelling: OffsetDateTime.toString()
        // drops zero seconds, so matching "10:00:00" would fail on correct
        // code and pass on nothing useful.
        val sent = java.net.URLDecoder.decode(
            second.substringAfter("fired_at=gt.").substringBefore("&"),
            "UTF-8",
        )
        assertEquals(
            "the second poll must resume from the first one's fired_at",
            parseTimestamp("2026-11-20T10:00:00+00:00"),
            parseTimestamp(sent),
        )
    }

    @Test
    fun `the same reminder is not returned twice by the poller`() = runBlocking {
        val p = poller()
        server.enqueue(rows(row("a", "2026-11-20T10:00:00+00:00")))
        assertEquals(1, p.pollOnce().size)

        // The server would not send it again, but if it does — a clock skew,
        // a gte instead of a gt — the poller must not re-announce it.
        server.enqueue(rows(row("a", "2026-11-20T10:00:00+00:00")))
        assertEquals(0, p.pollOnce().size)
    }

    @Test
    fun `a server error yields nothing and does not throw`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(emptyList<Reminder>(), poller().pollOnce())
    }

    @Test
    fun `an outage yields nothing and does not throw`() = runBlocking {
        server.shutdown()
        assertEquals(emptyList<Reminder>(), poller().pollOnce())
    }

    @Test
    fun `rubbish in the body yields nothing and does not throw`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>nope</html>"))
        assertEquals(emptyList<Reminder>(), poller().pollOnce())
    }

    @Test
    fun `a signed out session does not reach the network`() = runBlocking {
        assertEquals(emptyList<Reminder>(), poller(signedIn = false).pollOnce())
        assertEquals(0, server.requestCount)
    }

    // Postgres hands back microseconds and a +00:00 offset, not the Z form
    // Instant.parse wants. Getting this wrong makes every reminder look
    // un-fired, which is the silent failure this whole task exists to avoid.
    @Test
    fun `postgres timestamps parse, in every shape it sends them`() {
        val expected = 1_763_632_800_000L // 2025-11-20T10:00:00Z
        for (shape in listOf(
            "2025-11-20T10:00:00+00:00",
            "2025-11-20T10:00:00.123456+00:00",
            "2025-11-20T10:00:00Z",
            "2025-11-20T10:00:00.5Z",
        )) {
            val got = parseTimestamp(shape)
            assertTrue("$shape did not parse", got != null)
            assertTrue(
                "$shape parsed to $got, expected about $expected",
                kotlin.math.abs(got!! - expected) < 1000,
            )
        }
        assertEquals(null, parseTimestamp("not a date"))
        assertEquals(null, parseTimestamp(null))
    }

    // Found in review, and it is the whole of Task 5's second route.
    //
    // pollOnce only ever returns reminders that have ALREADY fired, so the
    // only rows the app stored were fired ones, so pendingReminders() was
    // always empty, so AlarmScheduler.syncFrom scheduled nothing and
    // ReminderAlarmReceiver could never fire. The offline backstop did not
    // exist. Nothing caught it because every test that exercised the alarm
    // route constructed its own Reminder.
    @Test
    fun `pending reminders are fetched so alarms can be set for them`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"p1","text":"stand up","due_at":"2026-11-20T12:00:00+00:00","fired_at":null}]""",
            ),
        )

        val pending = poller().pollPending()

        assertEquals(1, pending.size)
        assertEquals("p1", pending[0].id)
        assertEquals(
            "an alarm can only be set for something that has not fired",
            null,
            pending[0].firedAtMs,
        )

        val path = server.takeRequest().path!!
        assertTrue("must ask for unfired rows: $path", path.contains("fired_at=is.null"))
    }

    @Test
    fun `a fired reminder is never offered as pending`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"f1","text":"done","due_at":"2026-11-20T12:00:00+00:00","fired_at":"2026-11-20T12:00:00+00:00"}]""",
            ),
        )
        assertEquals(emptyList<Reminder>(), poller().pollPending())
    }

    @Test
    fun `a failed pending poll yields nothing rather than throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(emptyList<Reminder>(), poller().pollPending())
    }

    @Test
    fun `a signed out session does not ask for pending reminders either`() = runBlocking {
        assertEquals(emptyList<Reminder>(), poller(signedIn = false).pollPending())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the poll interval is short enough to feel immediate`() {
        assertTrue(ReminderPoller.DEFAULT_INTERVAL_MS in 5_000..30_000)
    }
}

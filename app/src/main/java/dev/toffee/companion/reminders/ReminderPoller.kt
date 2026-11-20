package dev.toffee.companion.reminders

import dev.toffee.companion.net.Session
import dev.toffee.companion.net.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * The server route: ask PostgREST what has fired since last time.
 *
 * Not Supabase Realtime. The backend does broadcast on a channel, but
 * speaking the Phoenix protocol — or pulling an SDK that does onto a 1.86 GB
 * API-27 device — is more moving parts than a twenty-second poll, and the
 * local alarm already covers the case this would be protecting against. If
 * Realtime is ever wanted, it becomes a third [ReminderInbox.Source] and
 * nothing else changes.
 *
 * Nothing here throws. A poll that fails is a poll that returns nothing and
 * tries again in twenty seconds.
 */
class ReminderPoller(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val session: Session,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    /** Everything fired after this has already been handed out. Starts at the
     *  epoch deliberately: the backend's sweep has no lower bound, so a
     *  reminder that came due while the tablet was unplugged must still
     *  arrive. Late is a nuisance; missing is a broken promise. */
    private var cursorMs: Long = 0L

    /** Fired reminders not yet seen by this poller. */
    suspend fun pollOnce(): List<Reminder> {
        val since = formatTimestamp(cursorMs)
        val query = "select=id,text,due_at,fired_at" +
            "&fired_at=gt.${URLEncoder.encode(since, "UTF-8")}" +
            "&order=fired_at.asc" +
            "&limit=20"

        val fresh = rows(query).filter { it.firedAtMs != null && it.firedAtMs > cursorMs }
        fresh.maxOfOrNull { it.firedAtMs!! }?.let { cursorMs = it }
        return fresh
    }

    private suspend fun rows(query: String): List<Reminder> {
        val token = session.accessToken() ?: return emptyList()

        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/reminders?$query")
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() else null
                }
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()

        val parsed = try {
            LENIENT.parseToJsonElement(body) as JsonArray
        } catch (_: Exception) {
            return emptyList()
        }

        return parsed.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val firedAt = parseTimestamp(row["fired_at"]?.jsonPrimitive?.contentOrNull())
            val dueAt = parseTimestamp(row["due_at"]?.jsonPrimitive?.contentOrNull())
            Reminder(
                id = row["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                text = row["text"]?.jsonPrimitive?.content.orEmpty(),
                dueAtMs = dueAt ?: firedAt ?: return@mapNotNull null,
                firedAtMs = firedAt,
            )
        }
    }

    /**
     * Reminders that have NOT fired yet, so local alarms can be set for them.
     *
     * This is the other half of the two-route design, and for a while it was
     * missing: [pollOnce] by construction only ever returns rows that have
     * already fired, so nothing in the app ever learned about a reminder
     * before it was due, and the offline backstop had nothing to schedule.
     *
     * Bounded to the near future. Scheduling an alarm for something due next
     * year costs nothing but tells us nothing either, and the device can hold
     * only so many exact alarms.
     */
    suspend fun pollPending(): List<Reminder> {
        val query = "select=id,text,due_at,fired_at" +
            "&fired_at=is.null" +
            "&order=due_at.asc" +
            "&limit=50"
        return rows(query).filter { it.firedAtMs == null }
    }

    /**
     * A thin loop around [pollOnce]. All the behaviour worth testing is in the
     * two poll functions; this only decides how often.
     *
     * Completes when the identity has been revoked. A tablet whose auth user
     * has been disabled has nothing to ask for, and looping forever on a
     * short-circuit is how a dead device still looks busy.
     */
    fun flow(): Flow<List<Reminder>> = flow {
        while (session.state() != SessionState.Revoked) {
            emit(pollOnce())
            delay(intervalMs)
        }
    }

    companion object {
        /** Twenty seconds. Long enough to be free on any tier, short enough
         *  that a reminder never feels late. */
        const val DEFAULT_INTERVAL_MS = 20_000L

        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

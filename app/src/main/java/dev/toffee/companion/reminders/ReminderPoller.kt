package dev.toffee.companion.reminders

import dev.toffee.companion.net.Session
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
        val token = session.accessToken() ?: return emptyList()
        val since = formatTimestamp(cursorMs)
        val query = "select=id,text,due_at,fired_at" +
            "&fired_at=gt.${URLEncoder.encode(since, "UTF-8")}" +
            "&order=fired_at.asc" +
            "&limit=20"

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

        val rows = try {
            LENIENT.parseToJsonElement(body) as JsonArray
        } catch (_: Exception) {
            return emptyList()
        }

        val fresh = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val firedAt = parseTimestamp(row["fired_at"]?.jsonPrimitive?.contentOrNull())
                ?: return@mapNotNull null
            Reminder(
                id = row["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                text = row["text"]?.jsonPrimitive?.content.orEmpty(),
                dueAtMs = parseTimestamp(row["due_at"]?.jsonPrimitive?.contentOrNull()) ?: firedAt,
                firedAtMs = firedAt,
            )
        }.filter { it.firedAtMs!! > cursorMs }

        fresh.maxOfOrNull { it.firedAtMs!! }?.let { cursorMs = it }
        return fresh
    }

    /** A thin loop around [pollOnce]. All the behaviour worth testing is in
     *  pollOnce; this only decides how often. */
    fun flow(): Flow<List<Reminder>> = flow {
        while (true) {
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

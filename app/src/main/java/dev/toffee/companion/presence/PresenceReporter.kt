package dev.toffee.companion.presence

import dev.toffee.companion.net.Session
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The only thing the camera is allowed to tell the server.
 *
 * "Only `{present: bool, at: timestamp}` leaves the tablet. No frame, no
 * descriptor, no identity. This holds even though it is his own database: it
 * keeps the blast radius of the whole project at zero if anything leaks."
 *
 * So this class takes three primitives and has no way of accepting anything
 * else. Adding a fourth field would mean changing this signature, which is
 * exactly the moment somebody should have to think about it.
 */
class PresenceReporter(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val session: Session,
) {

    /**
     * @param durationS how long the sitting that just ended lasted, when
     *        that is known. Null is not zero.
     */
    suspend fun report(present: Boolean, atMs: Long, durationS: Int?) {
        val token = session.accessToken() ?: return

        val body = buildJsonObject {
            put("present", present)
            put("at", Instant.ofEpochMilli(atMs).toString())
            if (durationS == null) put("duration_s", JsonNull) else put("duration_s", durationS)
        }

        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/presence_events")
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $token")
                // RLS grants the device insert here and no select at all, so
                // asking for the row back would be refused and would look
                // exactly like a refused insert.
                .addHeader("Prefer", "return=minimal")
                .post(body.toString().toRequestBody(JSON))
                .build()

            // Presence is a nice-to-have on top of a companion that already
            // works. A sample that does not land is a sample that does not
            // land; it must never take the face down with it.
            runCatching { http.newCall(request).execute().use { } }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

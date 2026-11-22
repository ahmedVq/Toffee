package dev.toffee.companion.net

import dev.toffee.companion.face.Mood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What came back from the agent.
 *
 * [speak] is null when there is genuinely nothing to say — the governor
 * decided silence, which is the default and not a failure. [offline] is true
 * only when the agent could not be reached at all.
 */
data class Reply(
    val speak: String?,
    val mood: Mood,
    val offline: Boolean = false,
)

/**
 * The two things the tablet says to the backend, as a seam.
 *
 * Brain orchestrates; ToffeeClient talks. Splitting them here is what lets
 * "a second reminder arriving while it is still speaking" be asserted in a
 * test rather than hoped for.
 */
interface Agent {
    /**
     * @param onSentence called with each sentence as it arrives, and the
     *        mood in force when it did — the mood matters per sentence
     *        because the face is set by a tool that runs before he speaks,
     *        and a sentence spoken in the previous mood is the bug that
     *        emitting mood early exists to prevent. The returned [Reply]
     *        still carries the whole thing, so a caller that does not care
     *        can ignore this and behave exactly as it did when `/turn`
     *        answered in one piece.
     */
    suspend fun turn(text: String, onSentence: (String, Mood) -> Unit = { _, _ -> }): Reply
    suspend fun event(kind: String, payload: Map<String, Any?> = emptyMap()): Reply
}

/**
 * The two things the tablet says to the backend.
 *
 * `/turn` is Ahmed talking. `/event` is something happening — he arrived, he
 * left, a reminder came due. Both run the same agent; the difference is who
 * started it.
 *
 * Nothing here throws. A companion whose face goes black because the Wi-Fi
 * dropped is worse than one that admits the Wi-Fi dropped, so every failure
 * becomes a [Reply] with [Reply.offline] set.
 */
class ToffeeClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val session: Session,
) : Agent {

    override suspend fun turn(text: String, onSentence: (String, Mood) -> Unit): Reply =
        stream("turn", buildJsonObject { put("text", text) }, onSentence)

    /**
     * `/turn` answers as Server-Sent Events: a sentence leaves the server the
     * moment the model finishes writing it, so he can start speaking while
     * the rest is still being written.
     *
     * Same 401-and-retry shape as [post]. A 401 arrives before any of the
     * stream does, so there is no half-spoken reply to worry about there.
     */
    private suspend fun stream(
        slug: String,
        body: JsonObject,
        onSentence: (String, Mood) -> Unit,
    ): Reply {
        val token = session.accessToken() ?: return offline()

        val first = sendStream(slug, body, token, onSentence)
        if (first.code != 401) return first.reply

        if (!session.refresh()) return offline()
        val retryToken = session.accessToken() ?: return offline()
        return sendStream(slug, body, retryToken, onSentence).reply
    }

    private suspend fun sendStream(
        slug: String,
        body: JsonObject,
        token: String,
        onSentence: (String, Mood) -> Unit,
    ): Attempt = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/$slug")
            .addHeader("apikey", apiKey)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()

        // Declared out here so a stream that dies halfway can still report
        // what arrived. He has already said those sentences out loud; coming
        // back with the offline line would be the tablet contradicting
        // itself.
        val heard = StringBuilder()
        var mood = Mood.NEUTRAL
        var finished: String? = null
        var sawDone = false
        var code = 0

        try {
            http.newCall(request).execute().use { res ->
                code = res.code
                if (!res.isSuccessful) return@withContext Attempt(res.code, offline())
                val source = res.body?.source() ?: return@withContext Attempt(res.code, offline())

                Sse.parse(source) { name, data ->
                    when (name) {
                        "mood" -> mood = Mood.parse(field(data, "mood"))
                        "text" -> field(data, "text")?.let { sentence ->
                            if (heard.isNotEmpty()) heard.append(' ')
                            heard.append(sentence)
                            onSentence(sentence, mood)
                        }
                        "done" -> {
                            sawDone = true
                            finished = field(data, "reply")
                            field(data, "mood")?.let { mood = Mood.parse(it) }
                        }
                        else -> Unit
                    }
                }
            }
        } catch (_: Exception) {
            // Nothing arrived at all: this is an outage like any other.
            if (heard.isEmpty()) return@withContext Attempt(code, offline())
        }

        // A 200 carrying something that is not an event stream at all — a
        // proxy's HTML error page is the one seen in production — yields no
        // frames. Without this it would read as "he had nothing to say",
        // which is a legitimate answer from /event and never from /turn.
        if (!sawDone && heard.isEmpty()) return@withContext Attempt(code, offline())

        // `done` is the authority when it arrived, because the server knows
        // the whole reply; the sentences are the fallback when it did not.
        val spoken = (finished ?: heard.toString()).takeIf { it.isNotBlank() }
        Attempt(code, Reply(spoken, mood))
    }

    /** One string field out of an SSE frame's JSON payload. */
    private fun field(data: String, name: String): String? = try {
        (LENIENT.parseToJsonElement(data) as JsonObject)[name]
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    override suspend fun event(kind: String, payload: Map<String, Any?>): Reply =
        post(
            "event",
            buildJsonObject {
                put("kind", kind)
                put("payload", payload.toJson())
            },
        )

    private suspend fun post(slug: String, body: JsonObject): Reply {
        val token = session.accessToken() ?: return offline()

        val first = send(slug, body, token)
        if (first.code != 401) return first.reply

        // The server disagrees with our expiry arithmetic, and the server is
        // right. One refresh, one retry, then stop — a tablet that loops on
        // 401 is a tablet nobody notices is broken.
        if (!session.refresh()) return offline()
        val retryToken = session.accessToken() ?: return offline()
        return send(slug, body, retryToken).reply
    }

    private class Attempt(val code: Int, val reply: Reply)

    private suspend fun send(slug: String, body: JsonObject, token: String): Attempt =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/functions/v1/$slug")
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        Attempt(res.code, offline())
                    } else {
                        Attempt(res.code, parse(text) ?: offline())
                    }
                }
            } catch (_: Exception) {
                Attempt(0, offline())
            }
        }

    private fun parse(text: String): Reply? = try {
        val json = LENIENT.parseToJsonElement(text) as JsonObject
        // The two endpoints name the spoken line differently, and they are
        // right to: `/turn` always answers, so its field is `reply`; `/event`
        // may decide to stay quiet, so its field is `speak` and is nullable.
        // Reading only one of them makes half the app silently mute — which
        // is exactly what happened, and no MockWebServer test caught it
        // because the test invented the field name too.
        val spoken = (json["speak"] ?: json["reply"])
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
        Reply(spoken, Mood.parse(json["mood"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content))
    } catch (_: Exception) {
        null
    }

    private fun offline() = Reply(Offline.line(), Mood.OFFLINE, offline = true)

    private fun Map<String, Any?>.toJson(): JsonObject = buildJsonObject {
        for ((k, v) in this@toJson) {
            when (v) {
                null -> put(k, JsonNull)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

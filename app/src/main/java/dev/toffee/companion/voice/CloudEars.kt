package dev.toffee.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import dev.toffee.companion.net.Session
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Ears that do not depend on what this ROM happens to ship.
 *
 * Holds the microphone itself, decides when he has stopped talking with
 * [Vad], and posts the audio to the `/listen` Edge Function — which is where
 * the provider key lives, and where it stays. The tablet never learns it.
 *
 * Used only when [DeviceEars] is unavailable, because this one costs money
 * per minute and the device's own recogniser costs nothing. See [bestEars].
 *
 * The design doc permits the audio leaving: "Permitted: Ahmed has said desk
 * audio may leave the device."
 */
class CloudEars(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val session: Session,
    context: Context,
) : Ears {

    private val app = context.applicationContext

    override fun available(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun listen(): Heard {
        if (!available()) return Heard.Unavailable("I don't have permission to use the microphone.")

        val pcm = withContext(Dispatchers.IO) { record() }
            ?: return Heard.Unavailable("The microphone would not open.")

        // Less than a quarter second of audio is a tap, not a sentence, and
        // is not worth a round trip or the money it costs.
        if (pcm.size < Wav.SAMPLE_RATE / 2) return Heard.Nothing

        val token = session.accessToken() ?: return Heard.Unavailable("This tablet is not signed in.")
        return withContext(Dispatchers.IO) { send(pcm, token) }
    }

    /** @return raw 16-bit little-endian mono PCM, or null if the mic failed. */
    private suspend fun record(): ByteArray? {
        val minimum = AudioRecord.getMinBufferSize(
            Wav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return null

        // Roughly a fifth of a second per read, so the level feeding Vad is
        // sampled often enough for a 1.2s hangover to be meaningful.
        val block = maxOf(minimum, Wav.SAMPLE_RATE / 5 * 2)

        val recorder = runCatching {
            AudioRecord(
                // VOICE_RECOGNITION gets the tuning the platform applies for
                // speech rather than for music, and skips the AGC that makes
                // a quiet room sound like a loud one.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Wav.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                block * 2,
            )
        }.getOrNull() ?: return null

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return null
        }

        val out = ByteArrayOutputStream()
        val samples = ShortArray(block / 2)
        val vad = Vad()
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val n = recorder.read(samples, 0, samples.size)
                if (n <= 0) break

                var sum = 0.0
                for (i in 0 until n) {
                    val s = samples[i].toInt()
                    sum += (s * s).toDouble()
                    // Little-endian, to match the header Wav writes.
                    out.write(s and 0xFF)
                    out.write((s shr 8) and 0xFF)
                }

                if (vad.offer(sqrt(sum / n), SystemClock.elapsedRealtime())) break
            }
        } catch (_: Exception) {
            return null
        } finally {
            // Both, on every path. An AudioRecord left running holds the
            // microphone for the life of the process, and the next tap then
            // silently records nothing.
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        return out.toByteArray()
    }

    private fun send(pcm: ByteArray, token: String): Heard {
        val wav = Wav.header(Wav.SAMPLE_RATE, pcm.size) + pcm
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/listen")
            .addHeader("apikey", apiKey)
            .addHeader("Authorization", "Bearer $token")
            .post(wav.toRequestBody(WAV))
            .build()

        return try {
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    return Heard.Unavailable("I can't reach the thing that does my listening.")
                }
                val json = LENIENT.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
                val text = json["text"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive?.content
                    ?.trim()
                if (text.isNullOrEmpty()) Heard.Nothing else Heard.Words(text)
            }
        } catch (_: Exception) {
            Heard.Unavailable("I can't reach the thing that does my listening.")
        }
    }

    private companion object {
        val WAV = "audio/wav".toMediaType()
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

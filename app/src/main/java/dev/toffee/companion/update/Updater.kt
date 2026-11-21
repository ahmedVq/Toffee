package dev.toffee.companion.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.toffee.companion.kiosk.Kiosk
import dev.toffee.companion.net.Session
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Updating the tablet without a cable.
 *
 * Device Owner may install an APK silently, and on this device that is the
 * only maintenance route that survives the kiosk: there is no Settings app,
 * no launcher, and the USB port on the laptop charges slower than the screen
 * drains, so "just plug it in" is not reliably available.
 *
 * Manual for now — the maintenance screen's button, behind the PIN. An
 * unattended auto-update on a device nobody can reach is how a tablet becomes
 * a brick, and the `active` flag on the row is the remote switch that will
 * make a scheduled check safe to add later.
 *
 * Never throws. Every path returns a sentence fit to show on that screen.
 */
class Updater(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val session: Session,
    context: Context,
) {

    private val app = context.applicationContext

    suspend fun check(): String = withContext(Dispatchers.IO) {
        val token = session.accessToken() ?: return@withContext "This tablet is not signed in."

        val release = fetchRelease(token) ?: return@withContext "Nothing new."
        val current = currentVersion()

        // Checked before downloading as well as after, so the common case —
        // already up to date — costs one small request and no megabytes.
        if (release.versionCode <= current) return@withContext "Nothing new (on $current)."
        if (!Kiosk.isDeviceOwner(app)) {
            return@withContext "Version ${release.versionCode} is out, but this tablet is not " +
                "device owner, so it cannot install it itself. Use a cable."
        }

        val apk = File(app.cacheDir, "update-${release.versionCode}.apk")
        val sha = download(release.url, apk)
            ?: return@withContext "Could not download version ${release.versionCode}."

        Install.refuse(current, release, sha, apk.length())?.let { why ->
            apk.delete()
            return@withContext why
        }

        val started = install(apk, release.versionCode)
        apk.delete()
        started
    }

    private fun fetchRelease(token: String): Release? {
        val request = Request.Builder()
            .url(
                "$baseUrl/rest/v1/app_release" +
                    "?select=version_code,url,sha256&active=eq.true&limit=1",
            )
            .addHeader("apikey", apiKey)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        return runCatching {
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val rows = LENIENT.parseToJsonElement(res.body?.string().orEmpty()) as JsonArray
                val row = rows.firstOrNull()?.jsonObject ?: return null
                fun str(key: String) = row[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                Release(
                    versionCode = str("version_code")?.toIntOrNull() ?: return null,
                    url = str("url") ?: return null,
                    sha256 = str("sha256").orEmpty(),
                )
            }
        }.getOrNull()
    }

    /** @return the hex SHA-256 of what actually landed on disk, or null. */
    private fun download(url: String, into: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body ?: return null
            into.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        // Hashed as it is written, so the hash is of the
                        // bytes on disk rather than of a second read that
                        // might differ.
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun install(apk: File, versionCode: Int): String = runCatching {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("toffee", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The result is broadcast to UpdateResultReceiver, which writes
            // it where the maintenance screen can show it. This device's app
            // logs do not reach logcat, so that is the only diagnostic there
            // is if the install goes wrong.
            val intent = Intent(UpdateResultReceiver.ACTION).setPackage(app.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            session.commit(
                PendingIntent.getBroadcast(app, sessionId, intent, flags).intentSender,
            )
        }
        "Installing version $versionCode. It will restart itself."
    }.getOrElse { "Could not start the install: ${it.javaClass.simpleName}" }

    private fun currentVersion(): Int = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

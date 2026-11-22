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
import java.util.concurrent.TimeUnit
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
 * Never throws. Every path returns a sentence fit to show on that screen, and
 * every path says what actually happened — "nothing new" is reserved for
 * there genuinely being nothing new, because a self-update that is broken and
 * reports itself healthy is worse than one that is obviously broken.
 */
class Updater(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val session: Session,
    context: Context,
) {

    private val app = context.applicationContext

    private sealed class Fetched {
        data class Row(val release: Release) : Fetched()
        object None : Fetched()
        data class Failed(val why: String) : Fetched()
    }

    suspend fun check(): String = withContext(Dispatchers.IO) {
        val token = session.accessToken() ?: return@withContext "This tablet is not signed in."

        val release = when (val fetched = fetchRelease(token)) {
            is Fetched.Row -> fetched.release
            is Fetched.None -> return@withContext "Nothing new (on ${currentVersion()})."
            is Fetched.Failed -> return@withContext fetched.why
        }

        val current = currentVersion()

        // The row's own faults, before a byte is fetched. Downloading first
        // meant a plain-HTTP url was blocked by the platform and reported as
        // "could not download" — telling whoever is standing there that the
        // network failed when the fault was the row in front of them.
        Install.refuse(current, release, app.packageName, null)?.let { return@withContext it }

        if (!Kiosk.isDeviceOwner(app)) {
            return@withContext "Version ${release.versionCode} is out, but this tablet is not " +
                "device owner, so it cannot install it itself. Use a cable."
        }

        val apk = File(app.cacheDir, "update-${release.versionCode}.apk")
        try {
            val sha = download(release.url, apk, token)
                ?: return@withContext "Could not download version ${release.versionCode}."

            val landed = Downloaded(
                sha256 = sha,
                bytes = apk.length(),
                versionCode = archiveVersion(apk),
                packageName = archivePackage(apk),
            )
            Install.refuse(current, release, app.packageName, landed)
                ?.let { return@withContext it }

            install(apk, release.versionCode)
        } finally {
            // On every path, including the ones that return early. A
            // truncated APK left in the cache is bytes nobody will ever look
            // at again.
            apk.delete()
        }
    }

    private fun fetchRelease(token: String): Fetched {
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
                if (!res.isSuccessful) {
                    // Chiefly an RLS refusal, which would otherwise look
                    // exactly like having nothing to install.
                    return Fetched.Failed(
                        "Could not read the release list (HTTP ${res.code}).",
                    )
                }
                val rows = LENIENT.parseToJsonElement(res.body?.string().orEmpty()) as JsonArray
                val row = rows.firstOrNull()?.jsonObject ?: return Fetched.None

                fun str(key: String) = row[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                val version = str("version_code")?.toIntOrNull()
                val url = str("url")
                if (version == null || url == null) {
                    return Fetched.Failed("The release row is missing a version or a url.")
                }
                Fetched.Row(Release(version, url, str("sha256").orEmpty()))
            }
        }.getOrElse { Fetched.Failed("Could not reach the project to ask about updates.") }
    }

    /** @return the hex SHA-256 of what actually landed on disk, or null. */
    private fun download(url: String, into: File, token: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val builder = Request.Builder().url(url).get()
        // Its own client, because OkHttp's call timeout covers the whole
        // transfer and the default is tuned for a conversational turn.
        // Measured on this tablet: 16MB took 14.9s of a 25s budget, and the
        // APK is now several times that with the wake-word model bundled —
        // so the default would abort every update partway and report it as
        // a network failure.
        val slow = http.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        // Only to our own project, and only so that a private Storage bucket
        // works. Sending the tablet's token to a third-party host would hand
        // that host the tablet's identity.
        if (url.startsWith("$baseUrl/")) {
            builder.addHeader("apikey", apiKey).addHeader("Authorization", "Bearer $token")
        }

        slow.newCall(builder.build()).execute().use { res ->
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

    /** What the file itself claims, which is the only claim that binds. */
    private fun archiveVersion(apk: File): Int? = runCatching {
        val info = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrNull()

    private fun archivePackage(apk: File): String? = runCatching {
        app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    }.getOrNull()

    private fun install(apk: File, versionCode: Int): String {
        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        return runCatching {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("toffee", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // The result is broadcast to UpdateResultReceiver, which
                // writes it where the maintenance screen can show it. This
                // device's app logs do not reach logcat, so that is the only
                // diagnostic there is if the install goes wrong.
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
        }.getOrElse {
            // An abandoned session is reclaimed; one left open is not.
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            "Could not start the install: ${it.javaClass.simpleName}"
        }
    }

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

        /**
         * Long enough for a whole APK on this tablet's Wi-Fi, with room for
         * a bad day. 16MB measured at 14.9s, and the bundled wake-word model
         * takes the APK several times past that; five minutes is generous
         * rather than tuned, which is the right side to err on for something
         * that only runs when someone presses a button.
         */
        const val DOWNLOAD_TIMEOUT_MS = 300_000L
    }
}

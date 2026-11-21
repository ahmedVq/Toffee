package dev.toffee.companion.update

/** A build sitting in Supabase Storage, as the `app_release` table describes it. */
data class Release(
    val versionCode: Int,
    val url: String,
    val sha256: String,
)

/**
 * What is allowed to reach the installer.
 *
 * Device Owner installs silently — no dialog, nobody looking — on a device
 * with no Settings app, no launcher and no reliable USB connection. A bad APK
 * that lands here does not get a second chance, so every rule is checked
 * before `PackageInstaller` is so much as opened.
 *
 * Pure, so each rule can be mutated in a test and fail exactly one assertion.
 */
object Install {

    /**
     * @param current this build's `versionCode`.
     * @param downloadedSha256 the hash of the bytes actually on disk, not the
     *        one the server claimed.
     * @return why it must not be installed, or null when it may be. The
     *         string is shown on the maintenance screen to someone holding a
     *         tablet with no other diagnostics on it.
     */
    fun refuse(
        current: Int,
        release: Release,
        downloadedSha256: String,
        bytes: Long,
    ): String? = when {
        // A downgrade is how a fixed bug comes back, and reinstalling what is
        // already running is at best a pointless reboot.
        release.versionCode <= current ->
            "Already on version $current; that build is ${release.versionCode}."

        // An APK fetched over plain HTTP is an APK anyone on the network chose.
        !release.url.startsWith("https://") ->
            "That build is not served over HTTPS."

        release.sha256.isBlank() ->
            "That build has no checksum, so there is no way to tell what it is."

        bytes <= 0L ->
            "The download was empty."

        // A truncated download has a valid APK's name and none of its bytes.
        !release.sha256.equals(downloadedSha256, ignoreCase = true) ->
            "The download does not match its checksum."

        else -> null
    }
}

package dev.toffee.companion.update

/** A build sitting in Supabase Storage, as the `app_release` table describes it. */
data class Release(
    val versionCode: Int,
    val url: String,
    val sha256: String,
)

/**
 * What actually arrived on disk, as opposed to what the row claimed.
 *
 * [versionCode] and [packageName] are read out of the APK itself. They are
 * null when the file is not something Android can parse as a package at all.
 */
data class Downloaded(
    val sha256: String,
    val bytes: Long,
    val versionCode: Int?,
    val packageName: String?,
)

/**
 * What is allowed to reach the installer.
 *
 * Device Owner installs silently — no dialog, nobody looking — on a device
 * with no Settings app, no launcher and no reliable USB connection. A bad APK
 * that lands here does not get a second chance.
 *
 * One gate, called twice. Before downloading, with [apk] null, so a bad row
 * is refused before megabytes are fetched and the reason given is the real
 * one rather than whatever the network happened to say. Then again with the
 * bytes that arrived.
 *
 * Pure, so each rule can be mutated in a test and fail exactly one assertion.
 */
object Install {

    /**
     * @param current this build's `versionCode`.
     * @param expectedPackage the application id this tablet is running.
     * @param apk what landed on disk, or null before anything has been
     *        fetched.
     * @return why it must not be installed, or null when it may be. The
     *         string is shown on the maintenance screen to someone holding a
     *         tablet with no other diagnostics on it.
     */
    fun refuse(
        current: Int,
        release: Release,
        expectedPackage: String,
        apk: Downloaded?,
    ): String? {
        // Faults in the row itself, checkable before a byte is fetched.
        when {
            // A downgrade is how a fixed bug comes back, and reinstalling
            // what is already running is at best a pointless reboot.
            release.versionCode <= current ->
                return "Already on version $current; that build is ${release.versionCode}."

            // An APK over plain HTTP is an APK anyone on the network chose.
            !release.url.startsWith("https://") ->
                return "That build is not served over HTTPS."

            release.sha256.isBlank() ->
                return "That build has no checksum, so there is no way to tell what it is."
        }

        if (apk == null) return null

        return when {
            apk.bytes <= 0L ->
                "The download was empty."

            // A truncated download has a valid APK's name and none of its bytes.
            !release.sha256.equals(apk.sha256, ignoreCase = true) ->
                "The download does not match its checksum."

            apk.versionCode == null || apk.packageName == null ->
                "That download is not an Android package."

            // Otherwise a wrong url installs whatever that file declares,
            // silently, with Device Owner privilege.
            apk.packageName != expectedPackage ->
                "That build is ${apk.packageName}, not $expectedPackage."

            // The row is a claim; the APK is the fact. Believing the row is
            // how a build that identifies as version 1 gets published as 2,
            // installs, reports success, and is reinstalled on every check
            // for ever.
            apk.versionCode != release.versionCode ->
                "That file says version ${apk.versionCode}, but it was published as " +
                    "${release.versionCode}."

            apk.versionCode <= current ->
                "That file is version ${apk.versionCode} and this tablet is already on $current."

            else -> null
        }
    }
}

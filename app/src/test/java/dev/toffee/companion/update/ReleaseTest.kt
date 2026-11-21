package dev.toffee.companion.update

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is allowed to reach the installer.
 *
 * Every rule here is the difference between a tablet that updates itself and
 * a brick. Device Owner installs silently, with no dialog and nobody looking,
 * on a device that has no Settings app, no launcher, and no reliable USB
 * connection. There is no second chance after a bad APK lands.
 *
 * One gate, called twice: once before downloading with [Downloaded] null, so
 * that a bad row is refused before megabytes are fetched and the reason given
 * is the real one, and once after with the bytes that actually arrived.
 */
class ReleaseTest {

    private val good = Release(
        versionCode = 2,
        url = "https://project.supabase.co/storage/v1/object/public/builds/toffee-2.apk",
        sha256 = "ab12cd34",
    )
    private val mine = "dev.toffee.companion"
    private val landed = Downloaded(
        sha256 = "ab12cd34",
        bytes = 9_000_000L,
        versionCode = 2,
        packageName = mine,
    )

    private fun refuse(current: Int = 1, release: Release = good, apk: Downloaded? = landed) =
        Install.refuse(current, release, mine, apk)

    @Test
    fun `a newer build whose bytes match is allowed`() {
        assertNull(refuse())
    }

    @Test
    fun `the same version is refused`() {
        assertNotNull("reinstalling what is running is at best a pointless reboot", refuse(current = 2))
    }

    @Test
    fun `an older version is refused`() {
        assertNotNull("a downgrade is how a fixed bug comes back", refuse(current = 3))
    }

    @Test
    fun `a mismatched hash is refused`() {
        assertNotNull(
            "a truncated download has a valid APK's name and none of its bytes",
            refuse(apk = landed.copy(sha256 = "ffffffff")),
        )
    }

    @Test
    fun `the hash comparison ignores case`() {
        assertNull("hex is hex", refuse(apk = landed.copy(sha256 = "AB12CD34")))
    }

    @Test
    fun `an empty download is refused`() {
        assertNotNull(refuse(apk = landed.copy(bytes = 0)))
    }

    @Test
    fun `a plain http url is refused`() {
        assertNotNull(
            "an APK over plain HTTP is an APK anyone on the network chose",
            refuse(release = good.copy(url = "http://project.supabase.co/builds/toffee-2.apk")),
        )
    }

    @Test
    fun `a release with no checksum is refused`() {
        assertNotNull("an unverifiable release is not a release", refuse(release = good.copy(sha256 = "")))
    }

    // ------------------------------------------------- before the download

    @Test
    fun `the row's own faults are caught before a byte is fetched`() {
        assertNotNull("version", refuse(current = 5, apk = null))
        assertNotNull("scheme", refuse(release = good.copy(url = "http://x/y.apk"), apk = null))
        assertNotNull("checksum", refuse(release = good.copy(sha256 = " "), apk = null))
    }

    // Found in review: the download ran first, so a plain-HTTP row was
    // blocked by the platform's cleartext policy and reported as "could not
    // download" — telling the operator the network had failed when the fault
    // was the row in front of them.
    @Test
    fun `a sound row passes the pre-download gate`() {
        assertNull("nothing checkable is wrong yet", refuse(apk = null))
    }

    // ------------------------------------------- what the APK itself says

    // Found in review. Nothing read the downloaded APK, so the downgrade
    // guard trusted an integer typed into a database. versionCode is pinned
    // at 1 in build.gradle.kts and no script bumps it, so a build published
    // as version 2 installed, reported success, still identified as 1, and
    // was downloaded and reinstalled on every check for ever.
    @Test
    fun `an apk that does not match the version it was published as is refused`() {
        assertNotNull(
            "the row says 2; the APK says 1; believing the row is an endless reinstall",
            refuse(apk = landed.copy(versionCode = 1)),
        )
    }

    @Test
    fun `an apk no newer than the running build is refused whatever the row claims`() {
        assertNotNull(refuse(current = 2, release = good.copy(versionCode = 9), apk = landed.copy(versionCode = 2)))
    }

    @Test
    fun `an apk for a different application is refused`() {
        assertNotNull(
            "a wrong url would otherwise install someone else's package under Device Owner",
            refuse(apk = landed.copy(packageName = "com.someone.else")),
        )
    }

    @Test
    fun `a file android cannot parse as an apk is refused`() {
        assertNotNull(refuse(apk = landed.copy(versionCode = null, packageName = null)))
    }

    @Test
    fun `the reason says which rule refused it`() {
        val why = refuse(apk = landed.copy(sha256 = "ffffffff"))
        assertNotNull(why)
        // Shown on the maintenance screen, to someone standing in front of a
        // tablet with no other diagnostics on it at all.
        assertTrue(why!!.isNotBlank())
    }
}

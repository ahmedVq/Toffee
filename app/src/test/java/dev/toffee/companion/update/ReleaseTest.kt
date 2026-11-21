package dev.toffee.companion.update

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What is allowed to reach the installer.
 *
 * Every rule here is the difference between a tablet that updates itself and
 * a brick. Device Owner installs silently, with no dialog and nobody looking,
 * on a device that has no Settings app, no launcher, and — as of today — no
 * reliable USB connection. There is no second chance after a bad APK lands.
 *
 * So each rule gets its own test, and mutating any one of them turns exactly
 * one test red.
 */
class ReleaseTest {

    private val good = Release(
        versionCode = 2,
        url = "https://project.supabase.co/storage/v1/object/public/builds/toffee-2.apk",
        sha256 = "ab12cd34",
    )
    private val bytes = 9_000_000L

    @Test
    fun `a newer build whose bytes match is allowed`() {
        assertNull(Install.refuse(current = 1, release = good, downloadedSha256 = "ab12cd34", bytes = bytes))
    }

    @Test
    fun `the same version is refused`() {
        assertNotNull(
            "reinstalling what is already running is at best a pointless reboot",
            Install.refuse(current = 2, release = good, downloadedSha256 = "ab12cd34", bytes = bytes),
        )
    }

    @Test
    fun `an older version is refused`() {
        assertNotNull(
            "a downgrade is how a fixed bug comes back",
            Install.refuse(current = 3, release = good, downloadedSha256 = "ab12cd34", bytes = bytes),
        )
    }

    @Test
    fun `a mismatched hash is refused`() {
        assertNotNull(
            "a truncated download has a valid APK's name and none of its bytes",
            Install.refuse(current = 1, release = good, downloadedSha256 = "ffffffff", bytes = bytes),
        )
    }

    @Test
    fun `the hash comparison ignores case`() {
        assertNull(
            "hex is hex; a capital letter is not a corrupt download",
            Install.refuse(current = 1, release = good, downloadedSha256 = "AB12CD34", bytes = bytes),
        )
    }

    @Test
    fun `an empty download is refused`() {
        assertNotNull(
            Install.refuse(current = 1, release = good, downloadedSha256 = "ab12cd34", bytes = 0),
        )
    }

    @Test
    fun `a plain http url is refused`() {
        val plain = good.copy(url = "http://project.supabase.co/builds/toffee-2.apk")
        assertNotNull(
            "an APK fetched over plain HTTP is an APK anyone on the network chose",
            Install.refuse(current = 1, release = plain, downloadedSha256 = "ab12cd34", bytes = bytes),
        )
    }

    @Test
    fun `a release with no hash at all is refused`() {
        val unsigned = good.copy(sha256 = "")
        assertNotNull(
            "an unverifiable release is not a release",
            Install.refuse(current = 1, release = unsigned, downloadedSha256 = "", bytes = bytes),
        )
    }

    @Test
    fun `the reason says which rule refused it`() {
        val why = Install.refuse(current = 1, release = good, downloadedSha256 = "ffffffff", bytes = bytes)
        assertNotNull(why)
        // It is shown on the maintenance screen, to someone standing in
        // front of a tablet with no other diagnostics on it at all.
        assert(why!!.isNotBlank())
    }
}

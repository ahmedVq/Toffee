package dev.toffee.companion.update

import dev.toffee.companion.net.ToffeeHttp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download gets longer than a conversation does.
 *
 * This is the one number standing between a 67MB APK and a tablet that
 * cannot be repaired. Self-update is the only remote repair channel this
 * device has: it is Device Owner, it is HOME, it cannot be uninstalled, and
 * nobody but Ahmed is allowed in the room it sits in. An update that aborts
 * partway reports itself as a network failure, which is indistinguishable
 * from the thing it was sent to fix.
 *
 * OkHttp's `callTimeout` covers the entire transfer, body included, so the
 * client tuned for a conversational turn is the wrong client for a download.
 * The APK was 16MB and measured 14.9s of a 25s budget; bundling the wake
 * word took it to 67MB, which at the same rate is about a minute. The
 * default would have aborted every update from the day the model shipped.
 *
 * Asserting the relationship rather than the number: whichever of the two
 * someone changes later, this still has an opinion.
 */
class UpdaterBudgetTest {

    @Test
    fun aDownloadGetsLongerThanATurnDoes() {
        assertTrue(
            "a download budget of ${Updater.DOWNLOAD_TIMEOUT_MS}ms is not more than " +
                "the ${ToffeeHttp.DEFAULT_TIMEOUT_MS}ms a conversational turn gets",
            Updater.DOWNLOAD_TIMEOUT_MS > ToffeeHttp.DEFAULT_TIMEOUT_MS,
        )
    }

    /**
     * And by enough to matter.
     *
     * 67MB at the rate measured on this tablet is about 63 seconds. A budget
     * that merely beat the turn timeout by a second would pass the test
     * above and still fail on the device, so this pins the real requirement:
     * room for the APK that exists, plus room for a bad day.
     */
    @Test
    fun withRoomForTheApkThatActuallyShips() {
        val measuredSecondsFor67Mb = 63
        assertTrue(
            "no room for a 67MB APK that takes about ${measuredSecondsFor67Mb}s",
            Updater.DOWNLOAD_TIMEOUT_MS >= measuredSecondsFor67Mb * 2 * 1000L,
        )
    }
}

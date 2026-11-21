package dev.toffee.companion.kiosk

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/** The component Device Owner is granted to. Empty by design — Device Owner
 *  is a privilege, not a place to put logic. */
class ToffeeDeviceAdmin : DeviceAdminReceiver()

/**
 * Burying Android.
 *
 * Three things together make the tablet stop being a tablet: the app is the
 * HOME activity, so there is no launcher to go back to; lock task pins it, so
 * the system bars do nothing; and Device Owner is what permits lock task
 * without a prompt.
 *
 * Everything here degrades. Without Device Owner the app still runs, still
 * fills the screen and still answers — it is simply escapable. That matters
 * because Device Owner needs a factory reset, which is Ahmed's decision and
 * close to a one-way door.
 */
object Kiosk {

    private const val TAG = "Toffee"

    /** Only used off the Device Owner path; nothing waits on the result. */
    private const val PERMISSION_REQUEST = 7301

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true

    /**
     * Pin the app and keep the screen alive. Safe to call when not Device
     * Owner: it does everything it can and says what it could not.
     */
    fun enter(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        hideSystemUi(activity)

        val manager = dpm(activity)
        if (manager != null && isDeviceOwner(activity)) {
            val admin = ComponentName(activity, ToffeeDeviceAdmin::class.java)
            manager.setLockTaskPackages(admin, arrayOf(activity.packageName))
            // No launcher to fall back to, and no way to add an account that
            // would hand someone a way out.
            manager.addUserRestriction(admin, "no_safe_boot")
            manager.addUserRestriction(admin, "no_factory_reset")
            runCatching { activity.startLockTask() }
                .onFailure { Log.w(TAG, "lock task refused: $it") }
        } else {
            Log.i(TAG, "not device owner; running unpinned and escapable")
        }
    }

    /**
     * Grant the app its own runtime permissions.
     *
     * In a kiosk this is not a convenience, it is the only route. Device
     * Owner removes Settings and the launcher, so a permission dialog the
     * user dismisses once — or that never appears because the activity is
     * pinned — is a microphone or a camera that is off for ever, with no way
     * back short of a factory reset.
     *
     * A Device Owner may grant them to itself with no prompt at all. Without
     * Device Owner this falls back to asking, which is the right behaviour
     * during development on an unpinned device.
     *
     * @return the permissions still not held, so the caller can say so
     *         rather than failing silently.
     */
    fun grantOwnPermissions(activity: Activity, vararg permissions: String): List<String> {
        val manager = dpm(activity)
        if (manager != null && isDeviceOwner(activity)) {
            val admin = ComponentName(activity, ToffeeDeviceAdmin::class.java)
            for (permission in permissions) {
                runCatching {
                    manager.setPermissionGrantState(
                        admin,
                        activity.packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }.onFailure { Log.w(TAG, "could not grant $permission: $it") }
            }
        } else {
            val missing = permissions.filter { !granted(activity, it) }
            if (missing.isNotEmpty()) {
                runCatching { activity.requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST) }
            }
        }
        // Re-read rather than trust the call: setPermissionGrantState returns
        // false for a permission the platform will not hand over, and the
        // caller needs the truth, not the attempt.
        return permissions.filter { !granted(activity, it) }
    }

    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun exit(activity: Activity) {
        runCatching { activity.stopLockTask() }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun apply(activity: Activity, level: BrightnessPolicy.Level) {
        val attrs = activity.window.attributes
        attrs.screenBrightness = level.windowBrightness
        activity.window.attributes = attrs
    }

    /** Device Owner can set Wi-Fi without Settings, which is the whole
     *  reason the maintenance screen can rescue a changed router password. */
    fun canConfigureWifi(context: Context): Boolean =
        isDeviceOwner(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun openWifiSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "could not open wifi settings: $it") }
    }

    fun hideSystemUi(activity: Activity) {
        activity.window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
}

/**
 * Comes back by itself.
 *
 * The tablet lives on mains in an office. If the power blips at 3am, nobody
 * is there to tap anything, so the companion has to return on its own.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val launch = Intent().setClassName(
            context.packageName,
            "dev.toffee.companion.MainActivity",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch, Bundle()) }
    }
}

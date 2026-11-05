package com.blockapp.android.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.blockapp.android.BlockApplication
import com.blockapp.android.MainActivity
import com.blockapp.android.accessibility.AppBlockAccessibilityService

/**
 * Device Admin is what makes the system refuse to uninstall this app. Removing it goes either
 * through RemoveProtectionScreen (the sanctioned route, in-process, refused while a lock runs) or
 * through Settings' Device Admin pages, which Tier 1 guards. This receiver is the last line on
 * the Settings route: it hears about a Deactivate tap before the confirm dialog exists.
 */
class BlockDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Called when the user taps Deactivate in Settings, before the confirm dialog shows; the
     * returned text goes into that dialog. Never called by DeviceAdminHelper.removeAdmin, so
     * nothing here can get in the way of the in-app removal.
     *
     * The accessibility service backs out of the page first ([AppBlockAccessibilityService.
     * repelAdminDeactivation]). On a Pixel the Device Admin pages were not recognised by class
     * name, and the user deactivated admin mid-lock and then switched Accessibility off: this
     * catches that route whatever the page is called. While a lock is running the screen is
     * also locked, so a dialog that still made it up has to be reached again from the lock
     * screen, where Tier 1 gets another go at it. Every step is guarded: this runs on the main
     * thread of the process that enforces locks (invariant 9).
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val locked = runCatching {
            (context.applicationContext as BlockApplication).repository.activeLocks.value.isNotEmpty()
        }.getOrDefault(false)

        val repelled = AppBlockAccessibilityService.repelAdminDeactivation()
        if (locked) {
            try {
                // force-lock is declared in device_admin_receiver.xml.
                context.getSystemService(DevicePolicyManager::class.java)?.lockNow()
            } catch (e: SecurityException) {
                Log.w(TAG, "lockNow refused", e)
            }
        }
        if (!repelled) {
            // The service isn't running, so nothing backed out of the page. Bring the app up
            // so the user at least sees their locks before confirming.
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }

        return if (locked) {
            "A lock is running. App Blocker can't be removed until it ends. Turning Device Admin " +
                "off now doesn't end the lock: the app keeps guarding Accessibility and " +
                "uninstall until the timer runs out."
        } else {
            "This turns off App Blocker's protection. To remove the app, use Settings → Remove " +
                "protection inside App Blocker instead."
        }
    }

    /**
     * Called when admin has been disabled. Brings the app forward so the user can re-grant it;
     * MainActivity opens on setup whenever admin is missing. A lock that is still running keeps
     * Tier 1 armed without admin (AppBlockAccessibilityService.Guard.LOCKED), so this is not a
     * way out of it.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        val sinceInApp = android.os.SystemClock.elapsedRealtime() - DeviceAdminHelper.removedInAppAt
        if (DeviceAdminHelper.removedInAppAt > 0L && sinceInApp < IN_APP_REMOVAL_WINDOW_MS) return
        runCatching {
            Toast.makeText(
                context,
                "Device Admin was removed. Open App Blocker to turn it back on.",
                Toast.LENGTH_LONG,
            ).show()
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private companion object {
        const val TAG = "BlockAdminReceiver"

        /** How soon after DeviceAdminHelper.removeAdmin an onDisabled counts as that removal. */
        const val IN_APP_REMOVAL_WINDOW_MS = 10_000L
    }
}

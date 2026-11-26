package com.blockapp.android.sync

import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import com.blockapp.android.BlockApplication
import com.blockapp.android.accessibility.ServiceState
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.usage.UsageStatsProvider
import com.blockapp.android.util.Haptics
import org.json.JSONObject

/**
 * `window.KeelAndroid` in the shared UI (ui/src/bridge.ts). Hands the session to [KeelSync] so
 * the phone keeps enforcing with the UI closed, reports what only the phone knows (permissions),
 * and opens the native screens the web UI can't be: setup, which walks Android's restricted
 * settings, and Remove protection, which must stay reachable from the hub (invariant 1).
 *
 * Methods run on a WebView binder thread, never the main thread, so anything touching views
 * goes through [openNative], which posts to the activity.
 */
class KeelBridge(private val context: Context, private val openNative: (String) -> Unit) {

    private val app get() = context.applicationContext as BlockApplication

    @JavascriptInterface
    fun getSession(): String = app.sync.sessionJson()

    @JavascriptInterface
    fun setSession(json: String) {
        app.sync.setSession(json)
    }

    @JavascriptInterface
    fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    @JavascriptInterface
    fun status(): String = statusJson(context)

    @JavascriptInterface
    fun action(name: String, arg: String): String {
        when (name) {
            "haptic" -> Haptics.perform(
                context,
                when (arg) {
                    "success" -> Haptics.Kind.CONFIRM
                    "reject" -> Haptics.Kind.REJECT
                    "tick" -> Haptics.Kind.TICK
                    else -> Haptics.Kind.CLICK
                },
            )
            // Every permission is fixed through the native setup, which knows the restricted-
            // settings dance (RestrictedSettings) that a plain Settings intent would hit.
            "openSetup", "openAccessibility", "openUsageAccess", "openAdmin", "openBattery" -> openNative("setup")
            "openRemoveProtection" -> openNative("removeProtection")
            "openReelEngine" -> openNative("reelEngine")
            "sync" -> app.sync.nudge()
        }
        return "{\"ok\":true}"
    }

    companion object {
        fun statusJson(context: Context): String {
            val app = context.applicationContext as BlockApplication
            return JSONObject()
                .put("accessibility", ServiceState.connected.value || DeviceAdminHelper.isAccessibilityActive(context))
                .put("usageAccess", UsageStatsProvider.hasUsageAccess(context))
                .put("admin", DeviceAdminHelper.isAdminActive(context))
                .put("battery", DeviceAdminHelper.isIgnoringBatteryOptimizations(context))
                .put("lastSync", app.sync.lastSync.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("syncError", app.sync.lastError ?: JSONObject.NULL)
                .put("canRemoveProtection", !Policy.hardLocked())
                .put("version", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")
                .toString()
        }
    }
}

package com.blockapp.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.blockapp.android.BlockApplication
import com.blockapp.android.R
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.ui.BlockOverlayActivity
import com.blockapp.android.util.ProtectedPackages
import com.blockapp.android.util.ReelApps
import com.blockapp.android.sync.Catalog
import com.blockapp.android.sync.Policy
import com.blockapp.android.util.InstalledAppsProvider
import com.blockapp.android.util.ReelSettings
import com.blockapp.android.util.Haptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Two-tier protection:
 *
 * Tier 1 – active once Device Admin is active (i.e. setup is complete), or while a lock runs:
 *   Guards the system screens that could be used to strip this app of its protective
 *   capabilities: Accessibility settings (silently kills this service), Device Admin list/detail
 *   (first step to deactivating admin), and this app's own App Info / uninstall screens.
 *   Also guards the Play Store uninstall flow for this package. See [Guard] for the two levels.
 *
 *   Screens are recognised by class name *and* by what they show. Class names alone missed the
 *   Device Admin screens on a Pixel: Settings opens most pages inside one generic SubSettings
 *   activity, so "Device admin apps" → this app → Deactivate never matched a hint, and the user
 *   could switch admin off mid-lock and then the Accessibility switch with it. The content check
 *   ([settingsWindowIsTamperRoute]) and the [tamperPoll] that repeats it while Settings is in
 *   front close that, and BlockDeviceAdminReceiver.onDisableRequested backs out of the
 *   Deactivate confirmation itself through [repelAdminDeactivation].
 *
 *   Gating this on Device Admin being active (rather than running unconditionally) is what
 *   lets first-time setup complete at all: the Accessibility Service has to be turned on
 *   *before* Device Admin can be activated, so if this guard ran the moment the service turns
 *   on, it would immediately kick the user out of the Device Admin activation screen — a
 *   permanent lockout before protection is even armed. Once Device Admin is active, Tier 1 is
 *   fully armed and the only sanctioned way back into these screens is the in-app
 *   "Remove protection" flow (RemoveProtectionScreen), which deactivates admin itself,
 *   in-process, without ever needing to reach the guarded Settings UI.
 *
 * Tier 2 – the synced policy (sync/Policy), active while it has anything to enforce:
 *   an app whose category a strict focus round, a lockdown or quiet hours closes, or whose
 *   shared daily time limit is spent across every device, is kicked to the home screen and
 *   the wall is shown with the reason.
 *
 * Device Admin deactivation itself already goes through a system confirmation dialog with our
 * own warning text (see BlockDeviceAdminReceiver) -- the guard here stops reaching that
 * dialog at all.
 *
 * Separately from both tiers, scroll and content-change events feed [ReelTracker], which counts
 * short-form videos watched per app (and the time spent on them) for HomeScreen and Insights,
 * and opens a session while a reel viewer is on screen, during which [ReelBubble] floats
 * today's total beside the camera. Both only observe and never act on the foreground app, so
 * they have no bearing on enforcement. Content-change events in particular go nowhere near
 * Tier 1 or Tier 2; they only arm the tracker's throttled scan.
 *
 * Tier 3 – the shared reel limit (Policy.reelVerdict), the one place reel tracking leads to
 *   action: once the total across the phone and every browser reaches the limit (or a focus
 *   round, lockdown or reel-free hours close reels), a reel session that opens (or one that is open
 *   when the limit is reached) is answered with GLOBAL_ACTION_BACK and the reel-limit wall. It
 *   acts only on what ReelTracker already calls a reel viewer, which never includes this app,
 *   launchers, or ProtectedPackages, so it can't reach the screens setup and removal depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppBlockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null
    private var watchdogScheduled = false
    private var reelTracker: ReelTracker? = null
    private var reelBubble: ReelBubble? = null
    private var bubbleTransientPackages: Set<String> = emptySet()

    /**
     * The apps the tracker watches, as the user set them (util/ReelSettings). One live Set
     * handed to the tracker once, whose contents are swapped underneath it, so a change in the
     * Reel engine screen applies without rebuilding the tracker mid-session. Volatile because
     * ReelScreenScanner reads it on its own thread.
     */
    private val trackedPackages = object : AbstractSet<String>() {
        @Volatile var current: Set<String> = ReelApps.KNOWN
        override val size: Int get() = current.size
        override fun iterator(): Iterator<String> = current.iterator()
        override fun contains(element: String): Boolean = element in current
    }

    /** Held in a field: SharedPreferences keeps its listeners weakly, and a lambda would be collected. */
    private val reelSettingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshTrackedPackages()
    }

    private fun refreshTrackedPackages() {
        trackedPackages.current = runCatching { ReelSettings.trackedPackages(this) }.getOrDefault(ReelApps.KNOWN)
    }

    /**
     * Screen-off ends the reel session. Without it the bubble, an accessibility overlay, stays
     * up over the lock screen, and the session's watch time keeps running while nobody watches.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only ever registered from API 28, alongside the tracker (see onServiceConnected).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.onScreenOff()
        }
    }
    private var screenOffRegistered = false

    private var reelSessionPackage: String? = null
    private var reelTotalToday = -1
    private var lastLimitKickAt = 0L
    private var pendingLimitWall: Pair<String, Policy.Verdict>? = null

    private val showReelLimitWall = Runnable {
        val (pkg, verdict) = pendingLimitWall ?: return@Runnable
        pendingLimitWall = null
        runCatching { startActivity(BlockOverlayActivity.intent(this, pkg, verdict, reels = true)) }
            .onFailure { Log.w(TAG, "reel wall refused", it) }
    }

    /**
     * Backstop against missed events. onAccessibilityEvent only fires when the system decides
     * a window state *changed*, and under rapid repeated open/close taps that event can be
     * coalesced or dropped entirely — by the notificationTimeout debounce, by OEM event
     * throttling, or by an animation race around performGlobalAction(HOME). If the one event
     * that would have caught a re-opened locked app is lost, nothing else fires afterward (the
     * app is just sitting there, unchanged) and it stays open for good, which is the "blocking
     * stops working after a while" bug. Polling the foreground window on a short fixed interval
     * closes that gap: even a fully missed event gets caught on the very next tick instead of
     * never.
     *
     * Only ever scheduled while Policy.armed() (see
     * the collector in onServiceConnected) — there is nothing to self-heal when nothing is
     * locked, so it costs zero wakeups the rest of the time this service is connected, which is
     * effectively all the time once Accessibility is granted.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                checkForeground()
            } catch (e: RuntimeException) {
                Log.w(TAG, "watchdog check failed", e)
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * This service (re)connects any time the system (re)binds it — notably after the app's
     * process was killed and restarted, whether by low memory or an OEM battery manager. In
     * that scenario a locked app can already be sitting in the foreground with no new
     * TYPE_WINDOW_STATE_CHANGED event pending (the window didn't just change, this service
     * just came back), so onAccessibilityEvent alone would never catch it until the user
     * switched away and back. Checking the current window here, straight from Room rather
     * than the in-memory cache (which may not have caught up with Room's async Flow yet this
     * soon after a cold start), closes that gap immediately; the collector started below then
     * keeps the watchdog running for as long as a lock is actually active.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceState.markConnected(true)
        val app = application as BlockApplication
        live = java.lang.ref.WeakReference(this)
        homePackages = homePackages()
        try {
            // Policy loaded its stored copy at process start, so this needs no network.
            checkForeground()
        } catch (e: RuntimeException) {
            // The watchdog below catches the same app a tick later; a failed first look must not
            // stop the service from finishing its setup.
            Log.w(TAG, "reconnect check failed", e)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope

        // The tracker needs scrollDeltaY, which AccessibilityEvent only gained in API 28 (P).
        // Below that the counter simply stays at zero, and HomeScreen says why.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Reel counting is an extra, blocking is the point. A failure setting up the tracker
            // (an OEM WindowManager refusing the bubble, a missing system service) used to throw
            // out of onServiceConnected, which crashed the service before the lock watchdog
            // below was ever started.
            try {
                connectReelTracking(app, scope)
            } catch (e: RuntimeException) {
                Log.w(TAG, "reel tracking unavailable", e)
                reelTracker = null
                reelBubble = null
            }
        }
        scope.launch {
            Policy.state.collect {
                if (Policy.armed()) {
                    if (!watchdogScheduled) {
                        watchdogScheduled = true
                        handler.post(watchdog)
                    }
                } else if (watchdogScheduled) {
                    watchdogScheduled = false
                    handler.removeCallbacks(watchdog)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun connectReelTracking(app: BlockApplication, scope: CoroutineScope) {
        val bubble = ReelBubble(this, app.repository, scope, { Policy.reelLimit() }, { Policy.reelsElsewhere() })
        reelBubble = bubble
        bubbleTransientPackages = bubbleTransientPackages()
        refreshTrackedPackages()
        ReelSettings.registerListener(this, reelSettingsListener)
        reelTracker = ReelTracker(
            service = this,
            handler = handler,
            // An allowlist, not a blocklist: see ReelApps.KNOWN for the browser images that
            // were counted as reels when every app was watched. The user can narrow it further
            // (ReelSettings), but never widen it past KNOWN.
            trackedPackages = trackedPackages,
            listener = object : ReelTracker.Listener {
                override fun onReelsWatched(packageName: String, count: Int, watchMs: Long) {
                    app.repository.addReelsWatched(packageName, count, watchMs)
                }

                override fun onSessionStarted(packageName: String) {
                    reelSessionPackage = packageName
                    bubble.show(packageName)
                    enforceReelLimit()
                }

                override fun onSessionEnded(packageName: String) {
                    reelSessionPackage = null
                    bubble.hide()
                }
            },
        )
        // Not exported: screen-off is a system broadcast, which reaches a not-exported
        // receiver anyway, and nothing else should be able to end a session.
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffRegistered = true

        // Today's total, re-keyed at midnight so the limit resets with the day.
        scope.launch {
            todayTicker()
                .flatMapLatest { day -> app.repository.observeReelTotal(day) }
                .collect { total -> onReelTotal(total) }
        }
        scope.launch {
            // An app switched off yesterday stops being counted from today.
            todayTicker().collect { refreshTrackedPackages() }
        }
        // A limit lowered on another device, or a focus round starting, must close a reel
        // viewer that's already open, not only the next one.
        scope.launch { Policy.state.collect { enforceReelLimit() } }
    }

    override fun onDestroy() {
        ServiceState.markConnected(false)
        if (live.get() === this) live.clear()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ServiceState.markConnected(false)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(showOverlay)
        handler.removeCallbacks(showReelLimitWall)
        handler.removeCallbacks(tamperPoll)
        tamperPollScheduled = false
        if (live.get() === this) live.clear()
        reelSessionPackage = null
        reelTotalToday = -1
        watchdogScheduled = false
        // The tracker only exists from API 28, but the field's type doesn't say so.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.release()
        reelTracker = null
        runCatching { ReelSettings.unregisterListener(this, reelSettingsListener) }
        reelBubble?.hide(immediate = true)
        reelBubble = null
        if (screenOffRegistered) {
            screenOffRegistered = false
            runCatching { unregisterReceiver(screenOffReceiver) }
        }
        serviceScope?.cancel()
        serviceScope = null
        return super.onUnbind(intent)
    }

    /**
     * Everything an event leads to runs inside one guard. An exception escaping here crashes the
     * service, and with it the whole process: enforcement stops, Settings keeps showing the
     * switch on (Samsung adds "Not working"), and the app looks broken. One bad event from one
     * app's odd window tree must cost that event, not the service.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEvent(event)
        } catch (e: RuntimeException) {
            Log.w(TAG, "event dropped", e)
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.onScrolled(event)
                return
            }
            // Many per second while a video plays. Only the tracker's throttle sees these.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.onContentChanged(pkg, bubbleTransientPackages)
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            else -> return
        }
        val eventPackage = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()

        // Runs before Tier 1/2 so a bounce home also ends the reel session and its bubble.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            reelTracker?.onWindowStateChanged(eventPackage, className, bubbleTransientPackages)
        }

        // Tier 1: guard our own protective system screens, but only once Device Admin is
        // active or a lock is running, and never the admin activation screen before admin is on
        // — otherwise this would block the setup flow itself (see class doc and [Guard]).
        val guard = guardMode()
        if (guard != Guard.OFF) {
            if (eventPackage in GUARDED_PACKAGES) startTamperPoll()
            if (isProtectiveSystemScreen(eventPackage, className, guard)) {
                bounceFromProtectedScreen()
                return
            }
        }

        // Tier 2: the synced policy.
        checkForeground(eventPackage)
    }

    /** Launchers: never blocked, or HOME would bounce straight back into the wall. */
    private var homePackages: Set<String> = emptySet()

    private fun homePackages(): Set<String> = runCatching {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0,
        ).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** The target in front and since when, so a time limit runs out mid-session, not after. */
    private var foregroundTarget: String? = null
    private var foregroundSince = 0L
    /** Soft focus: the distraction in front and since when; see [checkSoftFocus]. */
    private var distraction: Pair<String, Long>? = null
    private val violatedSessions = HashSet<Long>()

    /**
     * Looks up [knownPackage] (or, if absent, whatever's currently in front) against the synced
     * policy and kicks it home if it is blocked. Shared by the immediate event path (which
     * already knows the package) and the [watchdog] poll (which doesn't).
     */
    private fun checkForeground(knownPackage: String? = null) {
        if (Policy.state.value == null) return
        val pkg = knownPackage ?: rootInActiveWindow?.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ProtectedPackages.ALL || pkg in homePackages ||
            pkg in bubbleTransientPackages
        ) {
            distraction = null
            return
        }
        val target = Catalog.targetFor(this, pkg)
        val now = System.currentTimeMillis()
        if (target != foregroundTarget) {
            foregroundTarget = target
            foregroundSince = now
        }
        val e = Policy.state.value ?: return
        val category = Catalog.category(this, target, e.overrides)
        // Usage stats as of the last report, plus the part of this session since then.
        val live = (now - maxOf(foregroundSince, Policy.localAt)).coerceAtLeast(0L) / 1000
        val localSec = (Policy.localUsage[target] ?: 0L) + live
        val name = Catalog.name(this, target) ?: InstalledAppsProvider.label(this, pkg)
        val verdict = Policy.verdict(target, name, category, localSec)
        if (verdict != null) {
            distraction = null
            kickToHome(pkg, verdict)
            return
        }
        checkSoftFocus(e, target, name, category, now)
    }

    /**
     * A soft focus round blocks nothing; holding a distraction in front past
     * [SOFT_FOCUS_GRACE_MS] withers the tree instead, once per session.
     */
    private fun checkSoftFocus(e: Policy.Enforce, target: String, name: String, category: String, now: Long) {
        val f = e.focus
        if (f == null || f.strict || Policy.workPhase(e) == null || category !in f.categories || f.id in violatedSessions) {
            distraction = null
            return
        }
        val d = distraction
        if (d == null || d.first != target) {
            distraction = target to now
            Toast.makeText(this, "$name during focus: your tree withers in ${SOFT_FOCUS_GRACE_MS / 1000}s", Toast.LENGTH_SHORT).show()
            return
        }
        if (now - d.second >= SOFT_FOCUS_GRACE_MS) {
            violatedSessions += f.id
            distraction = null
            (application as BlockApplication).sync.violation(f.id, name)
            Haptics.reject(this)
        }
    }

    /**
     * How much of Tier 1 is armed.
     *
     * [Guard.FULL] once Device Admin is active, which is the end of setup. [Guard.LOCKED] while
     * a lock runs without admin: admin removed from Safe Mode, say, or a lock started before
     * setup was finished. Without it, admin off meant Tier 1 off, and the Accessibility switch
     * and uninstall were open mid-lock. LOCKED leaves the Device Admin screens alone, because
     * activating admin is the one setup step still possible with this service running, and
     * blocking it would break invariant 2. The Accessibility screens it does guard can't be part
     * of setup either: this service running proves that switch is already on.
     */
    private enum class Guard { OFF, LOCKED, FULL }

    private fun guardMode(): Guard = when {
        DeviceAdminHelper.isAdminActive(this) -> Guard.FULL
        Policy.hardLocked() -> Guard.LOCKED
        else -> Guard.OFF
    }

    /**
     * Returns true if [eventPackage]/[className] is a system screen that could be used to
     * uninstall this app or revoke its permissions, regardless of whether a lock is active.
     * [className] may be empty (the [tamperPoll]), which leaves only the content check.
     */
    private fun isProtectiveSystemScreen(eventPackage: String, className: String, guard: Guard): Boolean {
        val full = guard == Guard.FULL
        // --- Settings app ---
        if (eventPackage in ProtectedPackages.SETTINGS) {
            // Accessibility settings — silently kills our service if the user disables it
            if (className.isNotEmpty() && ACCESSIBILITY_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return true
            }
            // Device Admin screens — first step to removing admin, which unblocks uninstall
            if (full && className.isNotEmpty() && DEVICE_ADMIN_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return true
            }
            // App Info / Uninstall screens — only when *this* app is the target
            if (className.isNotEmpty() && APP_INFO_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } &&
                currentWindowMentionsSelf()
            ) {
                return true
            }
            return settingsWindowIsTamperRoute(full)
        }

        // --- Package installer (AOSP & OEM variants) ---
        if (eventPackage in ProtectedPackages.PACKAGE_INSTALLER) {
            // Any uninstall-related screen in the installer
            if (UNINSTALLER_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return currentWindowMentionsSelf()
            }
        }

        // --- Play Store uninstall flow ---
        if (eventPackage == ProtectedPackages.PLAY_STORE) {
            if (PLAY_STORE_UNINSTALL_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return currentWindowMentionsSelf()
            }
        }

        return false
    }

    /**
     * The Settings screens that matter, told apart by what they say rather than by activity
     * name, since most of them share one activity (see the class doc). A screen counts only if
     * it names this app *and* carries one of the words of a route out: the admin list or its
     * Deactivate page, this app's Accessibility page, or its App info (Uninstall, Force stop).
     * Requiring this app's name keeps the rest of Settings usable, including the battery and
     * overlay pages setup links to, which name the app but none of the words.
     *
     * English only. On another system language this falls back to the class-name hints alone.
     *
     * With [full] off ([Guard.LOCKED]) the Device Admin pages are let through, since that is
     * where admin gets switched back on. That page also has an "Uninstall app" button, which is
     * why the admin words are checked first and end the check.
     */
    private fun settingsWindowIsTamperRoute(full: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in ProtectedPackages.SETTINGS) return false
        if (!root.hasText(getString(R.string.app_name))) return false
        if (ADMIN_SCREEN_WORDS.any { root.hasText(it) }) return full
        return ACCESSIBILITY_SCREEN_WORDS.any { root.hasText(it) } ||
            APP_INFO_SCREEN_WORDS.any { root.hasText(it) }
    }

    private fun android.view.accessibility.AccessibilityNodeInfo.hasText(text: String): Boolean =
        findAccessibilityNodeInfosByText(text).isNotEmpty()

    /**
     * Repeats [isProtectiveSystemScreen] while a Settings, installer or Play Store window is in
     * front. A Settings page fills its list after the window-state event that announced it, so
     * the content check on that event alone often saw an empty page. Polling also covers moving
     * between pages of one SubSettings activity, which fires no window-state event at all.
     * Content-change events are not used for this (they only ever feed the reel tracker's
     * throttle), and the poll stops itself the moment none of those apps is in front.
     */
    private val tamperPoll = object : Runnable {
        override fun run() {
            tamperPollScheduled = false
            try {
                val guard = guardMode()
                if (guard == Guard.OFF) return
                val pkg = rootInActiveWindow?.packageName?.toString() ?: return
                if (pkg !in GUARDED_PACKAGES) return
                if (isProtectiveSystemScreen(pkg, "", guard)) {
                    bounceFromProtectedScreen()
                    return
                }
                startTamperPoll()
            } catch (e: RuntimeException) {
                Log.w(TAG, "tamper poll failed", e)
            }
        }
    }
    private var tamperPollScheduled = false

    private fun startTamperPoll() {
        if (tamperPollScheduled) return
        tamperPollScheduled = true
        handler.postDelayed(tamperPoll, TAMPER_POLL_MS)
    }

    private var lastBounceToastAt = 0L

    private fun bounceFromProtectedScreen() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.uptimeMillis()
        // The poll can bounce twice inside one HOME transition; one toast is enough.
        if (now - lastBounceToastAt < BOUNCE_TOAST_GAP_MS) return
        lastBounceToastAt = now
        val locked = Policy.hardLocked()
        Toast.makeText(
            this,
            if (locked) {
                "Not during a focus round or lockdown. ${getString(R.string.app_name)} can be removed once it ends."
            } else {
                "This setting is protected by ${getString(R.string.app_name)}. Use Remove protection in the app."
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** See the companion's [Companion.repelAdminDeactivation]. */
    private fun backOutOfAdminDeactivation() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Checks whether the currently active window contains any reference to this app
     * (by label or package name), so we only block when *our* app is the uninstall target.
     */
    private fun currentWindowMentionsSelf(): Boolean {
        val root = rootInActiveWindow ?: return false
        val label = getString(R.string.app_name)
        return root.findAccessibilityNodeInfosByText(label).isNotEmpty() ||
            root.findAccessibilityNodeInfosByText(packageName).isNotEmpty()
    }

    /**
     * Sends the locked app home at once, then puts the block wall up [OVERLAY_DELAY_MS] later.
     *
     * The delay is load-bearing. GLOBAL_ACTION_HOME is carried out asynchronously by the
     * system, and started together with the wall it routinely landed *after* it, so the launcher
     * ended up on top and the wall was never seen: observed on an API 34 emulator, where the
     * log read overlay, overlay, HOME, HOME, overlay, HOME. The user was just dumped on the
     * home screen with no reason given. Re-posting one pending start on every kick means the
     * wall always comes last, after the final HOME of a burst. The HOME itself stays
     * immediate, so enforcement never waits on the delay.
     */
    private fun kickToHome(packageName: String, verdict: Policy.Verdict) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.removeCallbacks(showOverlay)
        pendingOverlay = packageName to verdict
        handler.postDelayed(showOverlay, OVERLAY_DELAY_MS)
    }

    private var pendingOverlay: Pair<String, Policy.Verdict>? = null

    private val showOverlay = Runnable {
        val (packageName, verdict) = pendingOverlay ?: return@Runnable
        pendingOverlay = null
        // A posted Runnable is outside onAccessibilityEvent's guard. The bounce home has already
        // happened by now, so a refused start loses the explanation, not the block.
        runCatching { startActivity(BlockOverlayActivity.intent(this, packageName, verdict)) }
            .onFailure { Log.w(TAG, "wall refused", it) }
    }

    /**
     * Warns once as the total closes in on the limit, then enforces it. The first value after
     * (re)connecting only sets the baseline, so a restart mid-evening doesn't re-announce a
     * warning the user already saw.
     */
    private fun onReelTotal(total: Int) {
        val previous = reelTotalToday
        reelTotalToday = total
        val limit = Policy.reelLimit()
        val warnBefore = ReelSettings.warnAt(this)
        if (limit != null && warnBefore > 0 && previous >= 0) {
            val elsewhere = Policy.reelsElsewhere()
            val warnAt = limit - warnBefore
            if (previous + elsewhere in 0 until warnAt && total + elsewhere in warnAt until limit) {
                Toast.makeText(this, "${limit - total - elsewhere} reels left today, on every device", Toast.LENGTH_SHORT).show()
                if (ReelSettings.warnBuzz(this)) Haptics.reject(this)
            }
        }
        enforceReelLimit()
    }

    /**
     * Tier 3: backs out of an open reel viewer when the shared limit is spent or reels are
     * closed right now (focus round, lockdown, reel-free hours), and shows why.
     */
    private fun enforceReelLimit() {
        val packageName = reelSessionPackage ?: return
        // reelTotalToday is -1 until Room's first answer, so a 0 limit waits for it too.
        if (reelTotalToday < 0) return
        val verdict = Policy.reelVerdict(reelTotalToday) ?: return
        val now = SystemClock.uptimeMillis()
        // One kick per burst: the session-start and total-changed paths often fire together.
        if (now - lastLimitKickAt < LIMIT_KICK_COOLDOWN_MS) return
        lastLimitKickAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.removeCallbacks(showReelLimitWall)
        pendingLimitWall = packageName to verdict
        handler.postDelayed(showReelLimitWall, OVERLAY_DELAY_MS)
    }

    private fun todayTicker(): Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(DAY_CHECK_MS)
        }
    }.distinctUntilChanged()

    /**
     * Windows that open on top of the reel viewer without the user leaving it: the
     * notification shade and the keyboard (typing a comment). Hiding for those would make
     * the bubble flicker away every time either appears.
     */
    private fun bubbleTransientPackages(): Set<String> {
        val keyboards = getSystemService(InputMethodManager::class.java)
            ?.inputMethodList
            ?.map { it.packageName }
            .orEmpty()
        return keyboards.toSet() + SYSTEM_UI_PACKAGE
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "AppBlockService"

        /**
         * The connected service, for BlockDeviceAdminReceiver. Weak so a torn-down service is
         * never kept alive or acted through.
         */
        private var live = java.lang.ref.WeakReference<AppBlockAccessibilityService>(null)

        /**
         * Backs out of Settings' Device Admin "Deactivate" confirmation, from the admin
         * receiver's onDisableRequested. That callback runs while Settings waits for our warning
         * text and before it shows the confirm dialog, whichever screen the user came through,
         * so it holds even where Tier 1 didn't recognise the page. BACK finishes the page so the
         * dialog never appears; HOME follows in case BACK only closed a sheet. The in-app
         * removal (RemoveProtectionScreen → removeActiveAdmin) never calls onDisableRequested,
         * so the sanctioned way out is untouched. Does nothing if the service isn't running,
         * which is also what keeps a crashed service from stranding anyone. Main thread only.
         */
        fun repelAdminDeactivation(): Boolean {
            val service = live.get() ?: return false
            return runCatching { service.backOutOfAdminDeactivation() }.isSuccess
        }

        /**
         * How often the watchdog re-checks the foreground app while the policy is armed. Fast
         * enough that a missed window event costs well under a second; slower than the 200ms
         * 1.x used, because it now runs whenever any limit exists, not only during a lock.
         */
        private const val WATCHDOG_INTERVAL_MS = 500L

        /** How long a soft focus round lets a distraction sit in front before the tree withers. */
        private const val SOFT_FOCUS_GRACE_MS = 10_000L

        /**
         * Wait between sending a locked app home and showing the block wall. Long enough for
         * the HOME transition to finish (150-450ms in the emulator log), short enough that the
         * wall reads as the cause of the bounce rather than a separate event.
         */
        private const val OVERLAY_DELAY_MS = 350L

        /** Minimum spacing between reel-limit kicks; see enforceReelLimit. */
        private const val LIMIT_KICK_COOLDOWN_MS = 1_500L

        /** How often the day is re-checked so today's reel total resets at midnight. */
        private const val DAY_CHECK_MS = 60_000L

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /**
         * How often [tamperPoll] re-reads a Settings window. Short enough that a Deactivate page
         * is gone before a deliberate tap lands on it, long enough that walking a Settings
         * window a few times a second costs nothing noticeable. Only runs while Settings, the
         * installer or the Play Store is in front.
         */
        private const val TAMPER_POLL_MS = 300L

        /** Minimum spacing between "this setting is protected" toasts. */
        private const val BOUNCE_TOAST_GAP_MS = 2_500L

        /** The apps [tamperPoll] watches; everything Tier 1 can act on. */
        private val GUARDED_PACKAGES = ProtectedPackages.ALL

        /**
         * Words on Settings' Device Admin list and its per-app page ("Device admin apps",
         * "Deactivate this device admin app"). Matched case-insensitively as substrings, and
         * only together with this app's name; see settingsWindowIsTamperRoute.
         */
        private val ADMIN_SCREEN_WORDS = listOf("device admin", "Deactivate", "admin app")

        /** This app's Accessibility page: the list's title, and the per-service shortcut row. */
        private val ACCESSIBILITY_SCREEN_WORDS = listOf("Accessibility", "shortcut")

        /** This app's App info page, on AOSP, Pixel and One UI alike. */
        private val APP_INFO_SCREEN_WORDS = listOf("Uninstall", "Force stop")

        /** Matches any Accessibility-settings activity across OEMs. */
        private val ACCESSIBILITY_CLASS_HINTS = listOf(
            "AccessibilitySettings",
            "AccessibilityService",
            "ToggleAccessibility",
            "AccessibilityDetail",
            "ManageAccessibility",
        )

        /** Matches Device Admin list and detail screens. */
        private val DEVICE_ADMIN_CLASS_HINTS = listOf(
            "DeviceAdmin",
            "DevicePolicyManager",
            "ActiveAdmin",
        )

        /**
         * Matches App Info / App Details screens.
         * These are shared by all apps, so callers must also check [currentWindowMentionsSelf].
         */
        private val APP_INFO_CLASS_HINTS = listOf(
            "InstalledAppDetails",
            "AppInfoDashboard",
            "AppInfoBase",
            "AppInfo",
            "ApplicationInfo",
            "AppDetailActivity",
            "ManageApplications",        // OEM: app list
            "AppStorageSettings",
            "AppPermissionActivity",
            "AppOpsDetails",
        )

        /** Matches uninstall confirmation / progress screens in the package installer. */
        private val UNINSTALLER_CLASS_HINTS = listOf(
            "UninstallerActivity",
            "UninstallAppProgress",
            "UninstallConfirm",
            "UninstallFinish",
            "DeletePackage",
        )

        /** Matches Play Store uninstall confirmation dialogs. */
        private val PLAY_STORE_UNINSTALL_HINTS = listOf(
            "UninstallActivity",
            "ConfirmUninstall",
            "AppActionDialog",
        )
    }
}

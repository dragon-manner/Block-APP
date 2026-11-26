# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Keel** (2.x; the repo is still Block-APP): one account across an Android phone, Linux/Windows
computers and their browsers. Every device reports screen time to a small self-hosted server and
enforces one synced rule set: a shared daily reel limit, per-target time limits sharing one
budget across devices, quiet hours, focus/Pomodoro sessions started from anywhere, and tasks whose
missed deadlines cost coins and reels and can lock distracting apps everywhere ("lockdown").
Coins/XP/streaks reward the opposite. 1.x was a phone-only timed app locker with offline unlock
keys; locks and keys are gone.

Single-user personal tool: no test suite. Verification is manual (see "Verifying a change").

## Project layout

- `server/` — Go sync server (`keel-server`): SQLite via modernc (pure Go, static binary),
  WebSocket push (`coder/websocket`), HTTPS via `autocert`. Runs on a 1GB VPS; keep it lean.
  `embed/` holds copies of the built UI and catalog (`make sync`).
- `ui/` — the one UI (Preact + TS + Vite), used by the web dashboard, the desktop app and the
  Android WebView. Platform differences go through `src/bridge.ts` only.
- `desktop/` — Tauri 2 app (Rust in `src-tauri/src`): `tracker.rs` (active window per desktop),
  `engine.rs` (accounting + verdicts), `sync.rs`, `ext.rs` (localhost bridge for the extension),
  `model.rs` (enforcement contract + verdict logic).
- `extension/` — MV3 extension (Chromium + Firefox). Relays to the desktop app; owns no state.
- `android/` — the Android Gradle project (`android/app`, Kotlin/Compose/Room, minSdk 26,
  compile/targetSdk 34). Package `com.blockapp.android` is kept so it installs over 1.x.
- `shared/catalog.json` — targets: which Android packages, domains and desktop app ids are the
  same thing ("youtube"), plus categories. Copied into server, desktop, Android, extension.
- `deploy/` — systemd unit + `deploy.sh` for the VPS.

Android package layout under `android/app/src/main/java/com/blockapp/android/`:
`accessibility/` (enforcement + reel engine), `admin/` (Device Admin), `alarm/` (daily archive +
boot), `data/` (Room + repository), `service/` (keep-alive), `sync/` (server link, policy, catalog,
JS bridge), `ui/` (the native screens left: setup, wall, Remove protection, reel engine), `usage/`
(screen-time stats), `util/`.

## Commands

```bash
make sync                      # build ui/ and copy it + the catalog into every platform
make server                    # → dist/keel-server (embeds the UI)
./dist/keel-server -addr :8080 -db keel.db [-open-signup]
./deploy/deploy.sh             # build for linux/amd64 and install on root@89.124.4.220
cd ui && npm run build         # type-check + build the UI
cd desktop/src-tauri && cargo check / cargo build --release   # needs desktop/dist (make sync)
cd desktop && npx tauri build  # installers
cd android && ./gradlew assembleDebug / installRelease / lintDebug
```

The Android release build is minified, resource-shrunk, signed with the local debug key, and
ships `src/main/baseline-prof.txt`; put that one on the phone, not debug (debug is laggy).
Debug builds may talk plain HTTP to `10.0.2.2` (`src/debug` network config + mixed content) so
the emulator can reach a dev server; release is HTTPS only. `KeelBridge`'s
`@JavascriptInterface` methods are kept by a rule in `proguard-rules.pro`; without it R8 strips
them and every bridge call silently fails in release only.

**KSP is pinned to the KSP1 backend** (`ksp.useKSP2=false` in `gradle.properties`, KSP
`2.2.10-2.0.2` in the root `build.gradle.kts`). Room 2.6.1's processor crashes under KSP2 with
`unexpected jvm signature V` and fails `:app:kspDebugKotlin` before emitting anything. The two
settings only move together, and only with a Room upgrade — KSP 2.3+ deletes the KSP1 backend
outright, so bumping KSP alone hard-fails at configuration time. The `Language version 1.9 is
deprecated` warning on every build comes from this backend and is expected.

There are no unit or instrumentation tests, so there is no test command and nothing to run a
single test against. Do not claim a change is "tested" — say what was built and what still needs
manual on-device verification.

## Architecture

**Dependency wiring is manual.** There is no DI framework. `BlockApplication` constructs the
single `BlockRepository` and `KeelSync` and exposes them plus an `applicationScope`. Everything reaches it the same
way — `context.applicationContext as BlockApplication` — from composables, services, and
receivers alike. Keep that pattern; don't introduce a container.

**The cross-device contract.** The server computes an `Enforce` object per *device*
(`server/enforce.go`): rules plus usage split into `others` (every other device) and `mine`. A
client adds its own live, unsynced count to `others`, so the device that watched reel 30 blocks
reel 31 itself, without a round trip. Usage uploads are **absolute daily totals**, never deltas,
so retries can't double count. The server pushes a bare `{"type":"changed"}` over WebSocket and
clients refetch `/api/enforce`; a lost or reordered message can't leave stale rules applied.
The verdict logic exists three times and must stay in lockstep: `desktop/src-tauri/src/model.rs`,
`android/.../sync/Policy.kt`, and the explanation in `ui/src/lib.ts`.

**Rules only tighten today** (`server/rules.go` `tighten`): a change applies at once only as far
as it is stricter; anything looser goes to `pending` and replaces the rules at the next day
boundary. Removing a limit, raising one, shrinking quiet hours, lowering a penalty, turning
lockdown or a taper off, or moving a target out of a distracting category are all loosenings.
`PUT /api/rules` decodes onto tomorrow's rules so partial updates don't zero fields.

**Consequences and rewards** live in `server/enforce.go` and `api.go`, serialised by
`Server.mu`. Overdue tasks are penalised once (`penalized`), and with `penalties.lockdown` the
distracting categories are blocked everywhere until the task is done or a ≥25-min focus session
clears the debt. Give-up after the 10s grace withers the tree and costs coins + reels. The day is
settled by `rollover` (clean day → coins + streak; freezes protect streaks; a day no device
reported is `nodata` and neutral). Shop items never switch a limit off.

**Android: Policy is the source of truth for enforcement.** `KeelSync` (owned by
`BlockApplication`) reports usage every 30s, pushes reel totals within ~400ms, listens on the
socket, and writes each `Enforce` into `Policy`, which persists it in SharedPreferences and
loads it at process start, so a reboot or OEM kill mid-focus enforces before the network is
back. `Catalog` maps packages to targets. `KeelBridge` is `window.KeelAndroid` for the WebView UI
(`MainActivity`, assets served via `WebViewAssetLoader`); it hands the session to `KeelSync`.

**Enforcement is two-tiered**, both in `AppBlockAccessibilityService` (`accessibility/`):

- *Tier 2 — the policy.* `checkForeground` maps the package to a target and asks
  `Policy.verdict` (strict focus work phase → category; lockdown → category; quiet hours →
  category; per-target limit with `others + local` seconds, local = last report + the live part
  of this session). A blocked package is kicked home via `performGlobalAction(GLOBAL_ACTION_HOME)`
  and `BlockOverlayActivity` is shown `OVERLAY_DELAY_MS` later with the reason (HOME runs
  asynchronously and used to land on top of a wall started at the same moment). Two paths feed
  it: the live `typeWindowStateChanged` event and a 500ms watchdog that runs only while
  `Policy.armed()`, because window-state events get dropped under rapid taps. Never blocked: this
  app, `ProtectedPackages`, launchers, keyboards, System UI. Soft focus doesn't block; holding a
  distraction 10s reports a violation (the server withers the tree). The wall finishes itself
  once the live policy no longer blocks.
- *Tier 1 — self-protection.* Once Device Admin is active, the service also bounces the user out
  of Settings' Accessibility screens, the Device Admin list/detail screens, this app's own App
  Info / uninstall screens, and the Play Store uninstall flow — matched by package
  (`ProtectedPackages`) plus **substring** class-name hints in `isProtectiveSystemScreen`. For
  screens shared by all apps (App Info, uninstaller), `currentWindowMentionsSelf()` further
  requires that *this* app is the target. **Class names alone weren't enough:** a Pixel opens
  most Settings pages inside one generic `SubSettings` activity, so the user deactivated admin
  mid-lock from "Device admin apps" and then switched Accessibility off. So Settings windows are
  also judged by content (`settingsWindowIsTamperRoute`: this app's label *plus* a route word,
  "device admin"/"Deactivate", "Accessibility"/"shortcut", "Uninstall"/"Force stop"; English
  only), re-checked by a 300ms `tamperPoll` that runs only while Settings, an installer or the
  Play Store is in front. And `BlockDeviceAdminReceiver.onDisableRequested` (which Settings calls
  on a Deactivate tap, before its confirm dialog, and `removeActiveAdmin` never calls) asks the
  live service to press BACK + HOME (`repelAdminDeactivation`) and, mid-lock, `lockNow()`s.

Tier 1 has two levels (`Guard`). FULL once Device Admin is active. LOCKED while `Policy.hardLocked()`
(strict focus work phase or lockdown) without admin (admin removed from Safe Mode, say): it guards
Accessibility, App info and uninstall but **leaves the Device Admin pages alone**, since
re-activating admin is the one setup step still possible with the service running. The admin
gate is load-bearing: Accessibility must be enabled *before* Device Admin can be activated, so an
unconditional Tier 1 would bounce the user out of the Device Admin activation screen and make
setup impossible to complete. In LOCKED the admin-word check runs first and ends the check,
because the activation page also carries an "Uninstall app" button.

**Reel counter (observe-only).** The same service also receives `typeViewScrolled` and
`typeWindowContentChanged` and hands them to `ReelTracker`, which counts short-form videos (and
the time spent on them) per app per day into `reel_counts` for `HomeScreen` and
`HistoryScreen`. **It only watches the apps in `util/ReelApps.KNOWN`**: the tracker and swipe
detector drop every other package before doing anything. It used to watch every app on screen
shape alone, and browsing images in Chrome counted as reels, because a full-screen viewer that
snaps one item at a time *is* the reel shape. Don't widen it back to a blocklist. In
`ReelApps.NEEDS_VIDEO_EVIDENCE` (Reddit, LinkedIn, X, Pinterest, Tumblr: photos and videos share
viewers) a scanner match also needs a video player on screen (`ReelScreenScanner.VideoCheck`:
ExoPlayer `exo_` IDs, player/video view classes, a seek bar, mute/pause/play labels, never
caption text). A swipe alone never opens a session: it needs a recent scanner candidate, or
pager semantics or a single visible item plus a second snapped swipe, and in evidence apps only a
candidate. Within the tracked apps it uses three independent signals, because no one of them
works in every app:

- `ReelScreenScanner` walks the active window (throttled to about once every 800ms, on a
  background `HandlerThread`) looking for a full-screen scrollable showing exactly one
  edge-to-edge child. That shape plus a second signal (page up/down actions, or a reel-ish view
  ID such as `reel_recycler` / `clips_viewer_view_pager`) opens a session on the *first* reel,
  before any swipe. It also counts a page change as a reel unless a measured swipe claims it
  within `PAGE_CHANGE_GRACE_MS`, and that is the *only* counter that works for Compose apps:
  Compose reports scroll deltas in an estimated unit, not pixels (a 2209px page swipe arrived
  as −7px on an emulator), and drops semantics-free page containers from the tree, so a Compose
  pager is recognised by its page up/down actions alone. Returning to a reel is recognised by
  the page's visible text, because Compose rebuilds the node when a page comes back.
- The **action rail**, `ReelScreenScanner.RailScan`, is the fallback when no pager is
  recognisable, which on a real phone was YouTube Shorts and everything else except Instagram.
  It looks for a column of small buttons down the right edge, at least two of them labelled
  with a reaction word or a count ("Like", "12K"), over a full-screen backdrop. Feeds put those
  actions in a row, never a right-hand column. The column must name **at least two different
  reactions** and sit in one compact cluster: a comments list has a "Like" heart down the right
  of every comment, and a regular post's comments screen used to count as reels, one more per
  scroll. A rail has no page node, so a new reel is told apart by the words in the caption zone
  (bottom-left, above the nav bar), minus chrome words ("Follow", "Original audio"), compared
  by overlap with the *smaller* set, **and** by the rail's own counts changing. Caption text
  alone changed under auto-captions and an expanded "…more", and each change counted a reel.
  A scanner-seen page change only counts if that page is still on screen when the grace ends.
- `ReelScrollDetector` measures swipes. A burst that lands exactly on whole container heights is
  a pager. One that doesn't is a feed. Inside a session it takes two such swipes in a row to end
  it: ending on one hid the pill mid-reel and the 30s feed cooldown stopped counting. That
  confirms viewers the scanner wasn't sure about, and counts by furthest position so rewatching
  doesn't double-count. Another app drawing while a session is open triggers a scan that ends
  the session if that app is really in front, so a dropped window event can't leave the pill
  over the launcher.

Two service-config flags exist only for this, and removing either silently breaks counting:
`flagIncludeNotImportantViews`, because the system drops scroll events from views marked
not important unless asked, and many pagers are; and `flagReportViewIds` for the hints.
`SurfaceView`/`TextureView` report as plain `android.view.View`, so "is a video playing" can't
be detected by class name. Don't try.

All reel geometry is measured against the **real display size** (`ReelTracker.realScreenSize`),
not `resources.displayMetrics`, which leaves out the system bars while node bounds include them.
Against the smaller height the caption zone missed the caption, and every reel read as the
same one.

The tracker never acts on the foreground app. The one action reel tracking leads to is **Tier 3**
in `AppBlockAccessibilityService.enforceReelLimit`: an open reel session gets
`GLOBAL_ACTION_BACK` plus the reel wall (`BlockOverlayActivity` with `EXTRA_REEL_LIMIT`) when
today's total has reached the **daily reel limit** (`util/ReelLimit`), or at any count during
the plan's **reel-free hours** (`EXTRA_QUIET_FROM` set). Every wall's fresh create also vibrates (`Haptics.reject`),
but not on `onNewIntent`, which the watchdog re-delivers every poll. It only fires on sessions, which never
exist for this app, launchers or `ProtectedPackages`. Lowering the limit applies now; raising or
removing it is pending until tomorrow, the same "only ever tighten" rule as invariant 4. A limit
of **0 is real** ("no reels", where the quit plan ends). Only `null`/`OFF` means no limit, so
never test a limit with `> 0`, and `EXTRA_REEL_LIMIT`'s "absent" value is `Int.MIN_VALUE`, not 0.
Content-change events must never reach Tier 1/2, only the tracker's throttle. An exception from it
must not reach the service (see `BlockRepository.addReelsWatched` and the guards in `ReelTracker`).
Requires API 28 (`scrollDeltaY`). Every decision is logged under `adb logcat -s ReelTracker` for
on-device debugging.

**Reel engine settings** (`util/ReelSettings`, prefs `reel_settings`, `ReelEngineScreen`): which
of `ReelApps.KNOWN` are counted, the pill's visibility, side (`Gravity.END` for left of camera)
and wording, and the "reels left" warning distance (0 = off) and buzz. **Switching an app's
counting off waits until tomorrow; on is immediate**, the same tighten-only rule as `ReelLimit`,
because an uncounted app can't reach Tier 3. The service hands the tracker one live `Set` whose
contents it swaps on a prefs listener and at midnight; never widen it past `KNOWN`.

`ReelBubble` is its on-screen half: a tiny `TYPE_ACCESSIBILITY_OVERLAY` pill beside the camera
cutout showing today's total. It is shown for exactly as long as the tracker has a session open.
The session ends on a feed-like scroll, when the viewer leaves the screen, when another app
comes to the front, and on screen-off (a receiver in the service), so the pill never sits over
the lock screen. It needs no `SYSTEM_ALERT_WINDOW`, but only through the service's own
`WindowManager`, and it must stay `FLAG_NOT_TOUCHABLE` because it sits over other apps' top
bars. Its WindowManager calls are guarded for the same reason as the tracker.

**History survives the system's week and a reinstall.** The system keeps raw usage events for
about a week, so `usage/UsageArchive` copies each day into Room (`usage_days`, `usage_apps`,
schema v4). It runs on process start, from the daily inexact `UsageArchiveReceiver` alarm
(`AlarmScheduler.scheduleDailyArchive`, re-armed on boot and on every start), whenever History
opens, and on each of Home's minute refreshes. `BlockRepository.archiveUsage` never writes an
empty day, and never replaces a day with a smaller one: an old day replays as empty or short,
and either would erase what the archive had. `HistoryScreen` reads *only* Room, back to the
earlier of the install date and the first archived day. **Auto Backup is on**
(`res/xml/backup_rules.xml` / `data_extraction_rules.xml`: database and shared prefs), so a
reinstall brings back history, the plan, running locks and used nonces. Locks and nonces coming
back is deliberate. Uninstalling is not a way out of a lock, and a spent key stays spent. A
restored lock has no alarm, which is why `BlockApplication` re-arms every running lock's alarm on
start. That also covers a force-stop, which cancels them. The backup goes through the system's
backup service. The app still has no network permission (invariant 8).

**Screen time** (`usage/UsageStatsProvider.history`) replays the raw `queryEvents` resume/pause
stream rather than reading `queryUsageStats` buckets. The buckets overlap the requested range,
which inflated "today", and can't be split by hour. It needs Usage access, and every screen
that shows it degrades to a grant prompt without it.

**"Switched on" is not "running".** `accessibility/ServiceState` is a process-local flag the
service sets in `onServiceConnected` and clears on unbind/destroy. Settings.Secure keeps the
switch on over a crashed service (Samsung: "Not working"), so the UI reads protection through
`ui/rememberProtection`: RUNNING, STARTING (switch on, not bound yet, a 4s grace for cold
starts), NOT_RUNNING (needs the switch cycled; setup walks it) or OFF. `isAccessibilityActive`
reads both the Secure string and `AccessibilityManager`'s enabled list, since some OEM builds
format the former differently.

**Crash-proofing the service is load-bearing.** A crash anywhere in the process kills the
accessibility service and leaves the switch on, which reads to the user as "the app says not
protected and keeps crashing". So: `onAccessibilityEvent` runs inside one guard, reel-tracking
setup is guarded separately from the lock watchdog, posted wall starts are guarded,
`applicationScope` has a `CoroutineExceptionHandler`, and `BlockGuardService.start` returns false
instead of throwing when Android 12+ refuses a background foreground-service start
(`BlockApplication.onAppForeground` retries from MainActivity / BlockOverlayActivity resume).
`util/CrashLog` keeps the last crash in `filesDir` and Settings shows it with a copy button, since
there is no crash reporter.

**OEM background killers** (`admin/OemBackground`): Samsung, Xiaomi, Oppo/Realme/OnePlus, Vivo,
Huawei/Honor each get explicit-component deep links to their own autostart / sleeping-apps
screens, tried in order, ending on App info. They can't be read, so the user ticks them off
(keyed to `firstInstallTime`). None of their activity names may match a Tier 1 class hint.
`SYSTEM_ALERT_WINDOW` is declared as an optional extra purely for its exemption from background
activity/FGS start limits; nothing is drawn with it.

**The only sanctioned way out** is `RemoveProtectionScreen` (reached from the setup header and
from the hub: Devices → This phone → Remove protection), which calls
`DeviceAdminHelper.removeAdmin()` in-process. **It refuses only while `Policy.hardLocked()`**
(strict focus round or lockdown); both end on their own. Daily limits alone never block it.

**Process keep-alive.** `BlockApplication` starts/stops `BlockGuardService` as `Policy.armed()`
flips, **debounced 300ms**. Only `BlockApplication` may start or stop it.

**Desktop.** A 1s sampler thread asks `tracker.rs` for the active window (Hyprland IPC socket;
Hyprland 0.55+ Lua configs need `hl.dsp.window.close({ window = "address:…" })`, older ones
`closewindow`; X11 via xdotool; KDE Wayland via kdotool; Win32), skips idle (>5 min), attributes
browser time to the extension's focused-tab domain, and on a blocked verdict asks the window to
close (never kills) and shows `block.html` full-screen for 6s. Browsers are never closed; the
extension walls the tab. Tray Quit waits out focus/lockdown. Reverse-DNS classes
(`org.kde.kcalc`) are normalised to their last part so targets match the Windows exe name.
State (session, today's usage, last enforcement) lives in `~/.local/share/keel` / `%APPDATA%`.

**Extension bridge** (`ext.rs`, 127.0.0.1:47823) only answers requests carrying an
`X-Keel-Ext` header from extension origins, so a web page can't spoof reel counts. Reel ids are
de-duplicated per day; a reel opened over the limit is walled, not counted.

## Invariants that must not break

1. **The escape hatch must always work outside a focus round or lockdown.** With Tier 1 armed,
   `RemoveProtectionScreen` → `removeAdmin()` is the only way back to Accessibility/Device Admin
   settings. It must stay reachable from the hub and from setup, and every hard lock must end on
   its own (focus rounds end at `ends`; lockdowns end when worked off). Verify on-device with
   Device Admin active.
2. **Never make Tier 1 unconditional** or arm it before Device Admin is active (setup breaks).
3. **Never block a package in `ProtectedPackages`**, a launcher or this app.
4. **Rules only tighten today.** Every new rule field needs a case in `tighten`, or it becomes an
   in-app off switch. Same for anything sold in the shop.
5. **Keep Tier 1's class-name hints narrow** (substring matches; see Code conventions).
6. **Don't remove the 300ms foreground-service debounce** (ForegroundServiceDidNotStartInTime).
7. **A Room schema change needs a version bump and a migration.** `AppDatabase` is `version = 5`,
   no destructive fallback. The 1.x lock/nonce tables stay in the schema, unused.
8. **The enforcement contract is shared.** Changing `Enforce` (server) means changing
   `model.rs`, `Policy.kt` and `ui/src/types.ts` together. Usage uploads stay absolute totals.
9. **Never weaken a fallback into a crash.** Network, parse and permission failures degrade to
   the last stored policy; the accessibility service must survive any of them.
10. **Keep the server lean** for a 1GB VPS: one SQLite connection, no new runtimes or services.
    The web UI is embedded; there is no separate web server.

## Code conventions

Follow what's already there; the existing files are consistent. Concretely:

**Comments explain the failure mode, not the mechanic.** This is the strongest convention in the
repo and the most important one to continue. Comments here don't say *what* the code does — they
name the specific bug the code prevents, and often that it was observed in testing. See
`BlockApplication`'s debounce, the `watchdog` KDoc, `AlarmScheduler.schedule`, and
`UsageStatsProvider`'s `INTERVAL_BEST` note. When you write a line whose *absence* would cause a
subtle bug, record which bug, so the next person doesn't "simplify" it away. Skip comments on code
that is already obvious.

**Class-level KDoc states the contract and the constraints**, including who is allowed to call it
(`BlockGuardService`: "Only BlockApplication starts/stops this") and what must not change. Put
cross-references to other files in prose — this codebase leans on them heavily.

**Stateless helpers are `object` singletons that take `Context` as a parameter** rather than
holding one: `DeviceAdminHelper`, `AlarmScheduler`, `KeyVerifier`, `ProtectedPackages`,
`InstalledAppsProvider`, `UsageStatsProvider`. Don't add a class with an injected `Context` for
this kind of helper.

**Type.** Two bundled variable fonts in `res/font` (OFL): Bricolage Grotesque for figures and
headings (`DisplayFamily` at a display optical size, `HeadingFamily` at a text one, width 92) and
Manrope for everything read. Keep tracking near zero on Bricolage; the platform sans's tight
negative tracking ran its letters together. No downloadable-fonts provider (invariant 8).

**UI look.** Dark-only on purpose (`ui/theme/Color.kt`): neutral graphite surfaces (`Ink*`),
text in `Bone*`, Lime (`primary`, with `LimeInk` on it) for anything the user does, and Ember
orange (`tertiary`) for the reel counter only. **No gradients and no glows**: the indigo and
violet-to-coral look before this one read as generated, so emphasis comes from size and weight.
Screens are assembled from `ui/UiKit.kt` (`Panel`, `ScreenHeader`,
`PrimaryButton`, `BarChart`, `ProgressRing`, `SegmentedToggle`, `ChoiceCard`, `HeatCalendar`,
`LoadingRows`, …), so reach for those before styling a Material component by hand. Charts are
drawn with `Canvas`; no chart library. Illustrations are static `Canvas` drawings in `ui/Art.kt`,
not image files. The launcher icon is a vector adaptive icon with its own cut-out monochrome layer.

**Never touch PackageManager during composition.** App lists load through `rememberLaunchableApps`
and icons through `rememberAppIdentity` (`ui/AppIdentity.kt`), both off the main thread and
cached. `InstalledAppsProvider.label` memoises labels, because resolving one loads that app's
resources. Calling `listLaunchableApps` inside `remember {}` froze the picker for most of a
second. And nothing at the top level of a screen may change every second: Home's countdowns tick
inside each `LockCard`, because a ticker read at the top recomposed the whole screen once a second.
`RemoveProtectionScreen` and `UnlockKeyScreen` deliberately only pick up the theme, so their
structure (invariant 1) isn't touched by visual work. `OnboardingScreen` was rebuilt on the kit
(one required step open at a time) but keeps "Remove protection" in its header in every state.

**The restricted-settings walk-through** (`admin/RestrictedSettings`, Android 13+). On a
sideloaded install Android greys out the Accessibility switch, and App info's ⋮ → "Allow
restricted settings" item **only appears after the switch has been tried once** and the
"Restricted setting" dialog shown. Onboarding walks it in that order: try the switch, App info,
switch again. Whether the app is restricted can't be read (system-only API), so
`likelyRestricted` guesses from `InstallSourceInfo.packageSource` and only picks wording.

**Haptics** all go through `util/Haptics` (vibrator primitives on API 30+, predefined effects
on 29, one-shots below; never throws; in-app switch under Settings → Feel). Compose's
`TextHandleMove` was so faint on a Pixel 8 that the app read as not vibrating. `BlockAppTheme`
installs `ui/Haptic.kt`'s `HapticRipple` as `LocalIndication`, so every default-indication
`clickable`/`toggleable` buzzes on release with no opt-in. Material3's Button, IconButton,
TextButton, Switch and Checkbox use `ripple()` directly and bypass it, so their handlers are
wrapped in `withHaptic` / `withHapticToggle`. Never do both on one clickable (double buzz).
`RemoveProtectionScreen` and `UnlockKeyScreen` buttons are left unwrapped (see below).

**Motion** lives in `ui/Motion.kt` (`Reveal`, `AnimatedCount`, `RollingText`, `PulsingDot`) and
`LockedCelebration.kt`. One hard rule: **anything that animates forever must be drawn in a
`Canvas`, never animated through `size()`, offsets or `graphicsLayer`.** Compose reports every
change to a node's on-screen bounds, transforms included, to accessibility services. A looping
`graphicsLayer` scale on the status dot fired ~8 `TYPE_WINDOW_CONTENT_CHANGED` events a second
from an idle Home screen, waking every accessibility service on the phone, this app's included.
Check with `adb shell uiautomator events` on an idle screen: it should print nothing. Finite
animations (arrival, press, count-up) are fine.

**UI is plain composables, no ViewModels.** Each screen is a top-level `@Composable` taking
`onDone`/`onBack`-style lambdas; state is `remember { mutableStateOf(...) }`; navigation is a
`private sealed class Screen` + `when` block in `MainActivity`. Repository data is collected with
`LaunchedEffect`, and anything that must re-read system state after the user returns from Settings
uses `LifecycleResumeEffect` (a one-shot `LaunchedEffect(Unit)` won't fire — the composable stays
alive while the user is away).

**Tunable numbers become named constants in a `private companion object`** at the bottom of the
file, with a comment on what they trade off — `WATCHDOG_INTERVAL_MS`, `SERVICE_TOGGLE_DEBOUNCE_MS`.
No bare magic numbers on behavioural paths.

**Room:** `suspend` for one-shot reads/writes, `Flow` for observation, soft-delete via
`active = 0`. Query strings live in `BlockDao` annotations; no query building elsewhere.

**Broadcast receivers that do async work use `goAsync()`** with `pendingResult.finish()` inside the
coroutine (`BootCompletedReceiver`), never a fire-and-forget launch.

**API-level differences are guarded explicitly** with `Build.VERSION.SDK_INT` and a comment saying
which API changed and why it matters, with `@Suppress("DEPRECATION")` scoped to the legacy branch
only (`UsageStatsProvider.hasUsageAccess`).

**Strings:** `res/values/strings.xml` holds only text the *system* renders — app label,
accessibility-service description, notification channel/title/text. In-app Compose text is
inline English literals. Match that split; don't half-migrate the UI to string resources.

**Formatting:** 4-space indent, ~100-column limit including comments, trailing commas on
multi-line argument lists, explicit imports (no wildcards).

## Dependencies

Deliberately minimal: Compose BOM + Material3, `core-ktx`, lifecycle (incl.
`lifecycle-runtime-compose` for `LifecycleResumeEffect`), `activity-compose`, Room + KSP, and
`profileinstaller` (applies the baseline profile on a sideload). Nothing
else, and no version catalog — versions are literals in `app/build.gradle.kts`, with plugin
versions in the root `build.gradle.kts`.

Don't add without a real need: any DI framework (Hilt/Koin) — `BlockApplication` is the wiring;
Navigation-Compose — the `sealed class` + `when` (three bottom-bar tabs, Today/History/Settings,
plus nine sub-screens) is
sufficient; WorkManager —
`AlarmManager` already covers expiry and exact timing matters; analytics or crash libraries; LiveData or RxJava — the codebase is coroutines/Flow throughout.

Kotlin, the Compose compiler plugin, and KSP versions move together — bumping one alone will fail
the build.

## Before changing anything

1. **Locate the mechanism first.** A rule is enforced in up to five places (server, Policy.kt,
   model.rs, extension, UI copy). Grep all of them before changing one.
2. **Read the comments before deciding something is redundant.** Most defensive code here is a
   recorded bug fix.
3. **Ask what happens when the process dies or the network drops** mid-focus. Enforcement must
   come back from stored state (`Policy.load`, desktop `enforce.json`) without the server.
4. **Check both directions of a permission being absent.**
5. **Keep the user's way out intact** (invariant 1).

## Verifying a change

No automated tests. Worth walking through for enforcement changes (an emulator works for most;
`adb shell settings put secure enabled_accessibility_services …`, `appops set … GET_USAGE_STATS
allow` and `dpm set-active-admin …` skip the setup taps; write `shared_prefs/keel_session.xml`
via `run-as` to sign in):

- A 0-minute limit on "youtube" set from another device: the YouTube app bounces to the wall,
  and youtube.com in a browser with the extension shows the in-page wall.
- Start a strict focus round from the desktop or web: within ~2s a distracting app on the phone
  bounces with a countdown, and the wall closes itself when the round ends.
- Reel limit N: count reels on the phone, then in a browser; the N+1th reel anywhere is walled.
- Raise a limit: today keeps the old one, the UI says the looser one starts tomorrow.
- Let a task go overdue with lockdown on: distracting apps lock on every device; a 25-minute
  focus clears it; Remove protection refuses meanwhile and works after.
- Force-stop / reboot mid-focus with the network off: enforcement resumes from stored policy.
- With Device Admin active: Settings → Accessibility, Device admin list, App info bounce;
  Remove protection still removes admin outside focus/lockdown.
- Desktop: a 0-minute limit on a `desktop:<app>` target closes that window and shows the wall
  (tested on Hyprland with kcalc); tray Quit refuses during focus.

## Threat model

This is a best-effort deterrent, not a cryptographic guarantee, and the README is deliberately
explicit about that. A determined user can still disable the Accessibility service from Settings,
deactivate Device Admin and uninstall, or boot into Safe Mode (which disables all third-party
accessibility services). Closing those would require Device Owner provisioning on a factory-reset
device, which is out of scope for a daily-driver phone.

Keep that framing in code comments, UI copy, and commit messages — don't describe any of the
bounce-back behaviour as making escape impossible. The honest claim is that it makes impulsive
unlocking meaningfully harder.

Note for any future thought of distribution: this app relies on the Accessibility API for
non-accessibility purposes and declares `QUERY_ALL_PACKAGES`, both of which are restricted under
Play Store policy. It is built as a personal sideloaded tool.

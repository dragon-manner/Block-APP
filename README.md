# Keel

One account across your phone, laptop and browsers. Keel tracks screen time on all of them,
enforces **one** set of limits everywhere, and adds focus sessions, deadlines with real
consequences, and rewards for keeping steady.

Set a 30-reel limit on your phone, watch 30 Shorts in the YouTube app, and the 31st Reel on
Instagram in your laptop's browser is walled. Start a focus session on the laptop, and your
phone closes Instagram until the round ends.

```
 Android app ─┐                         ┌─ Desktop app (Linux / Windows)
 (reel engine,│   HTTPS + WebSocket     │  (active-window tracking,
 a11y blocker)├────── keel-server ──────┤   closes blocked apps)
              │   (Go, SQLite, ~12MB)   │        │ 127.0.0.1:47321
 Web dashboard┘                         └─ Browser extension
                                            (counts reels, walls sites)
```

## What it does

- **Combined screen time.** Every device reports its day; Today and Insights show the total,
  split by device, app/site, category and hour. Day, week and month views, compared with the
  previous period. A "YouTube" target is the Android app, youtube.com and any desktop client at
  once (`shared/catalog.json`).
- **Shared limits.** A daily reel limit counted across the phone and every browser, per-app time
  limits that share one budget across devices, nightly quiet hours, and an optional taper that
  steps the reel limit down each week. **Tighter changes apply now; looser ones wait until
  tomorrow**, so the limit can't be switched off in the moment it matters.
- **Focus.** Deep focus or Pomodoro, started from any device, enforced on all of them. Each
  finished session plants a procedurally grown tree in your **Grove**; giving up withers it.
  Strict mode closes distracting apps and sites; soft mode lets you open them but withers the
  tree after 10 seconds.
- **Tasks with consequences.** Give tasks deadlines. Finish on time for coins. Miss one and you
  lose coins and reels off today's allowance, and (by default) a **lockdown** closes social,
  video, games, news and shopping on every device until you finish it or work it off with 25
  focused minutes. Pushing a deadline back costs coins; an overdue task can't be moved or
  deleted.
- **Rewards.** Coins and XP from focus, tasks, clean days and streaks. Spend them on +5 reels,
  a 15-minute breather from app limits, forgiveness for an overdue penalty, or a streak freeze.
  Nothing for sale switches a limit off. Levels unlock accent colours.

## Components

| Path | What | Built with |
| --- | --- | --- |
| `server/` | Sync server: accounts, rules, usage, focus, tasks, economy, live push | Go, SQLite (pure Go), WebSocket |
| `ui/` | The app's interface, shared by all three platforms and the web dashboard | Preact, TypeScript, Vite |
| `desktop/` | Linux and Windows app: tracks the active window, closes blocked apps, tray | Tauri 2 (Rust) |
| `extension/` | Chrome / Brave / Edge / Firefox: reels and site time, walls | Manifest V3 |
| `android/` | Android app: reel engine, accessibility blocker, usage stats, sync | Kotlin, Compose, Room |
| `shared/catalog.json` | Which packages, domains and desktop apps are the same target | JSON |
| `deploy/` | systemd unit and deploy script for the VPS | |

`make sync` copies the built UI and the catalog into the server, desktop and Android trees.

## Setup

### 1. Server (once)

```bash
./deploy/deploy.sh          # builds, copies to root@89.124.4.220, installs the systemd unit
```

It serves HTTPS on `https://89-124-4-220.sslip.io` with an automatic Let's Encrypt
certificate (sslip.io maps the name to the IP, so no domain is needed). The first account to
register owns the server; later sign-ups are refused unless it runs with `-open-signup`. The
web dashboard is at the same address.

Run it locally instead: `make server && ./dist/keel-server -addr :8080`.

### 2. Android

Install the APK from Releases (or `cd android && ./gradlew installRelease`), sign in, then walk
the setup: Accessibility, Device admin, Usage access and battery. On a sideloaded install,
Android 13+ greys out the Accessibility switch until "Allow restricted settings" is used;
setup walks you through it.

### 3. Linux / Windows

Install the `.deb` / `.rpm` / `.AppImage` or the Windows installer from Releases, or build with
`make desktop`. It starts with the computer and lives in the tray; closing the window hides it.
Window tracking works on **Hyprland**, **X11**, **KDE Wayland** (needs `kdotool` from the AUR)
and **Windows**. GNOME on Wayland gives apps no way to see the active window.

### 4. Browser extension

Chrome/Edge: `chrome://extensions` → Developer mode → Load unpacked → `extension/`.
Brave on this machine: add `--load-extension=/path/to/Block-APP/extension` to
`~/.config/brave-flags.conf`. Firefox: `about:debugging` → Load Temporary Add-on, or sign the
zip from Releases. The extension talks only to the Keel desktop app on `127.0.0.1:47321`; with
the desktop app closed it shows a "!" badge and nothing in that browser is counted.

## How strong is it?

Keel makes giving in harder, not impossible. On Android the accessibility service closes
blocked apps and Device Admin stops a two-tap uninstall; during a focus round or lockdown the
Settings routes that would switch these off are guarded, and **Remove protection** inside the
app refuses until it's over. Outside those times Remove protection always works. Safe Mode
still disables everything. On the desktop, quitting from the tray waits out a focus round or
lockdown, but killing the process works. Browser limits hold only with the extension installed
and the desktop app running. That's the honest boundary: it removes the impulse path, not every
path.

## Development

```bash
make ui                         # build the shared UI
cd ui && npm run dev            # UI dev server (set VITE_KEEL_SERVER=http://localhost:8080)
make server && ./dist/keel-server -addr :8080 -open-signup
cd desktop && npx tauri dev     # desktop app against the built UI
cd android && ./gradlew assembleDebug   # debug builds may reach http://10.0.2.2 (the dev host)
```

CI (`.github/workflows/release.yml`) builds everything on each push; tagging `v*` publishes a
release with the server binaries, desktop installers, APK and extension zip.

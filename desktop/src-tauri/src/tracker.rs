//! Which window is in front, how long the user has been idle, and how to close a window.
//! One backend per desktop: Hyprland (its IPC socket, no process spawn per sample), X11
//! (xdotool), KDE Wayland (kdotool), Windows (Win32). GNOME on Wayland exposes none of this to
//! ordinary apps, so it reports itself as unsupported rather than guessing.

use std::time::{Duration, Instant};

#[derive(Clone, Debug, PartialEq, Default)]
pub struct Win {
    pub app: String,    // lowercased class / exe stem
    pub title: String,
    pub handle: String, // hyprland address, X11 id, or HWND
}

pub struct Tracker {
    backend: Backend,
    last_sig: String,
    last_change: Instant,
    idle_cache: (Instant, u64),
    cursor: String,
}

enum Backend {
    #[cfg(unix)]
    Hyprland(std::path::PathBuf),
    #[cfg(unix)]
    X11,
    #[cfg(unix)]
    Kde,
    #[cfg(windows)]
    Windows,
    None(String),
}

#[cfg(unix)]
fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let out = std::process::Command::new(cmd).args(args).output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}

#[cfg(unix)]
fn has(cmd: &str) -> bool {
    std::env::var_os("PATH")
        .map(|p| std::env::split_paths(&p).any(|d| d.join(cmd).is_file()))
        .unwrap_or(false)
}

#[cfg(unix)]
fn hypr(sock: &std::path::Path, req: &str) -> Option<String> {
    use std::io::{Read, Write};
    let mut s = std::os::unix::net::UnixStream::connect(sock).ok()?;
    s.set_read_timeout(Some(Duration::from_millis(400))).ok()?;
    s.write_all(req.as_bytes()).ok()?;
    let mut buf = String::new();
    s.read_to_string(&mut buf).ok()?;
    Some(buf)
}

impl Tracker {
    pub fn new() -> Tracker {
        Tracker { backend: Self::detect(), last_sig: String::new(), last_change: Instant::now(), idle_cache: (Instant::now() - Duration::from_secs(60), 0), cursor: String::new() }
    }

    #[cfg(windows)]
    fn detect() -> Backend {
        Backend::Windows
    }

    #[cfg(unix)]
    fn detect() -> Backend {
        if let Ok(sig) = std::env::var("HYPRLAND_INSTANCE_SIGNATURE") {
            let rt = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".into());
            for base in [format!("{rt}/hypr/{sig}/.socket.sock"), format!("/tmp/hypr/{sig}/.socket.sock")] {
                let p = std::path::PathBuf::from(base);
                if p.exists() {
                    return Backend::Hyprland(p);
                }
            }
        }
        let wayland = std::env::var("XDG_SESSION_TYPE").map(|s| s == "wayland").unwrap_or(false) || std::env::var_os("WAYLAND_DISPLAY").is_some();
        let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default().to_lowercase();
        if wayland && desktop.contains("kde") {
            return if has("kdotool") { Backend::Kde } else { Backend::None("KDE Wayland needs kdotool (AUR: kdotool) to read the active window".into()) };
        }
        if !wayland && std::env::var_os("DISPLAY").is_some() && has("xdotool") {
            return Backend::X11;
        }
        if wayland && std::env::var_os("DISPLAY").is_some() && has("xdotool") && !desktop.contains("gnome") {
            return Backend::X11; // XWayland only: native Wayland windows will read as nothing
        }
        Backend::None(format!("No window tracking for this desktop ({desktop}). Hyprland, KDE (with kdotool), X11 and Windows are supported."))
    }

    pub fn backend_name(&self) -> String {
        match &self.backend {
            #[cfg(unix)]
            Backend::Hyprland(_) => "Hyprland".into(),
            #[cfg(unix)]
            Backend::X11 => "X11".into(),
            #[cfg(unix)]
            Backend::Kde => "KDE".into(),
            #[cfg(windows)]
            Backend::Windows => "Windows".into(),
            Backend::None(why) => why.clone(),
        }
    }

    pub fn working(&self) -> bool {
        !matches!(self.backend, Backend::None(_))
    }

    pub fn active(&mut self) -> Option<Win> {
        let w = self.sample();
        let sig = w.as_ref().map(|w| format!("{}|{}", w.app, w.title)).unwrap_or_default();
        if sig != self.last_sig {
            self.last_sig = sig;
            self.last_change = Instant::now();
        }
        w
    }

    fn sample(&mut self) -> Option<Win> {
        match &self.backend {
            #[cfg(unix)]
            Backend::Hyprland(sock) => {
                let raw = hypr(sock, "j/activewindow")?;
                let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
                let app = v.get("class")?.as_str()?.to_lowercase();
                if app.is_empty() {
                    return None;
                }
                Some(Win { app, title: v.get("title").and_then(|t| t.as_str()).unwrap_or("").into(), handle: v.get("address").and_then(|t| t.as_str()).unwrap_or("").into() })
            }
            #[cfg(unix)]
            Backend::X11 => {
                let id = run("xdotool", &["getactivewindow"])?;
                let app = run("xdotool", &["getwindowclassname", &id]).unwrap_or_default().to_lowercase();
                let title = run("xdotool", &["getwindowname", &id]).unwrap_or_default();
                (!app.is_empty()).then_some(Win { app, title, handle: id })
            }
            #[cfg(unix)]
            Backend::Kde => {
                let id = run("kdotool", &["getactivewindow"])?;
                let app = run("kdotool", &["getwindowclassname", &id]).unwrap_or_default().to_lowercase();
                let title = run("kdotool", &["getwindowname", &id]).unwrap_or_default();
                (!app.is_empty()).then_some(Win { app, title, handle: id })
            }
            #[cfg(windows)]
            Backend::Windows => win::active(),
            Backend::None(_) => None,
        }
    }

    /// Seconds since the user last did anything, as best this desktop can tell.
    pub fn idle_secs(&mut self) -> u64 {
        #[cfg(windows)]
        {
            return win::idle_secs();
        }
        #[cfg(unix)]
        {
            // Asking D-Bus spawns a process, so it is asked at most every 15s.
            if self.idle_cache.0.elapsed() < Duration::from_secs(15) {
                return self.idle_cache.1 + self.idle_cache.0.elapsed().as_secs();
            }
            let heuristic = self.last_change.elapsed().as_secs();
            let mut idle = None;
            if let Backend::Hyprland(sock) = &self.backend {
                // A locked screen is idle no matter what the last window was.
                if run("pgrep", &["-x", "hyprlock"]).is_some() {
                    idle = Some(u64::MAX / 4);
                } else if let Some(pos) = hypr(sock, "cursorpos") {
                    if pos != self.cursor {
                        self.cursor = pos;
                        self.last_change = Instant::now();
                    }
                    idle = Some(self.last_change.elapsed().as_secs());
                }
            }
            if idle.is_none() {
                idle = run("dbus-send", &["--session", "--print-reply", "--dest=org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver.GetSessionIdleTime"])
                    .and_then(|s| s.split_whitespace().last().and_then(|n| n.parse::<u64>().ok()))
                    .map(|ms| ms / 1000);
            }
            let v = idle.unwrap_or(heuristic);
            self.idle_cache = (Instant::now(), v);
            v
        }
    }

    /// Asks the window to close, the way its own close button would: unsaved-work prompts
    /// still get to run. Never kills the process.
    pub fn close(&self, w: &Win) -> bool {
        match &self.backend {
            #[cfg(unix)]
            Backend::Hyprland(sock) => {
                if w.handle.is_empty() {
                    return false;
                }
                // Hyprland 0.55+ with a Lua config only takes Lua dispatchers; classic configs
                // only take the old syntax. Try the new one and fall back on anything but "ok".
                let lua = format!("dispatch hl.dsp.window.close({{ window = \"address:{}\" }})", w.handle);
                if hypr(sock, &lua).is_some_and(|r| r.trim() == "ok") {
                    return true;
                }
                hypr(sock, &format!("dispatch closewindow address:{}", w.handle)).is_some_and(|r| r.trim() == "ok")
            }
            #[cfg(unix)]
            Backend::X11 => run("xdotool", &["windowclose", &w.handle]).is_some() || run("wmctrl", &["-ic", &w.handle]).is_some(),
            #[cfg(unix)]
            Backend::Kde => run("kdotool", &["windowclose", &w.handle]).is_some(),
            #[cfg(windows)]
            Backend::Windows => win::close(&w.handle),
            Backend::None(_) => false,
        }
    }
}

#[cfg(windows)]
mod win {
    use super::Win;
    use windows_sys::Win32::Foundation::{CloseHandle, HWND};
    use windows_sys::Win32::System::SystemInformation::GetTickCount;
    use windows_sys::Win32::System::Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION};
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{GetLastInputInfo, LASTINPUTINFO};
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId, PostMessageW, WM_CLOSE};

    pub fn active() -> Option<Win> {
        unsafe {
            let hwnd = GetForegroundWindow();
            if hwnd.is_null() {
                return None;
            }
            let mut pid = 0u32;
            GetWindowThreadProcessId(hwnd, &mut pid);
            let mut title = [0u16; 512];
            let n = GetWindowTextW(hwnd, title.as_mut_ptr(), title.len() as i32);
            let title = String::from_utf16_lossy(&title[..n.max(0) as usize]);
            let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if h.is_null() {
                return None;
            }
            let mut buf = [0u16; 1024];
            let mut len = buf.len() as u32;
            let ok = QueryFullProcessImageNameW(h, PROCESS_NAME_WIN32, buf.as_mut_ptr(), &mut len);
            CloseHandle(h);
            if ok == 0 {
                return None;
            }
            let path = String::from_utf16_lossy(&buf[..len as usize]);
            let exe = path.rsplit(['\\', '/']).next().unwrap_or("").to_lowercase();
            let app = exe.strip_suffix(".exe").unwrap_or(&exe).to_string();
            Some(Win { app, title, handle: (hwnd as isize).to_string() })
        }
    }

    pub fn idle_secs() -> u64 {
        unsafe {
            let mut info = LASTINPUTINFO { cbSize: std::mem::size_of::<LASTINPUTINFO>() as u32, dwTime: 0 };
            if GetLastInputInfo(&mut info) == 0 {
                return 0;
            }
            (GetTickCount().wrapping_sub(info.dwTime) / 1000) as u64
        }
    }

    pub fn close(handle: &str) -> bool {
        let Ok(h) = handle.parse::<isize>() else { return false };
        unsafe { PostMessageW(h as HWND, WM_CLOSE, 0, 0) != 0 }
    }
}

//! The browser extension's way in. Chromium 14x+ (Local Network Access) blocks an extension's
//! service worker from fetching 127.0.0.1 unless the user grants a permission that a worker has
//! no way to prompt for, so on a current Brave or Chrome the extension silently never reached
//! the localhost bridge. Native messaging has no such gate: the browser starts this binary with
//! the extension's origin as an argument and talks to it over stdin/stdout. This mode does no
//! UI and no Tauri; it relays each message to the running app's bridge (ext.rs) and answers.
//!
//! It also writes the native-host manifests that tell each installed browser where this binary
//! is and which extension may call it ([install_manifests]).

use crate::ext::PORT;
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

pub const HOST_NAME: &str = "app.keel.bridge";
/// Fixed by the `key` in extension/manifest.json, so it is the same wherever it's loaded from.
pub const CHROME_EXTENSION_ID: &str = "peplbebfnbjpephhngiodiocekamldkf";
pub const FIREFOX_EXTENSION_ID: &str = "keel@keel.app";

/// Chrome-family browsers pass the caller's origin; Firefox passes the manifest path and the
/// extension id. Either means "you are a native host, not the app".
pub fn invoked_as_host() -> bool {
    std::env::args().skip(1).any(|a| {
        a.starts_with("chrome-extension://") || a == FIREFOX_EXTENSION_ID || a == "--native-host" || a.ends_with(&format!("{HOST_NAME}.json"))
    })
}

pub fn run_host() {
    let mut input = std::io::stdin().lock();
    let mut out = std::io::stdout().lock();
    loop {
        let mut len = [0u8; 4];
        if input.read_exact(&mut len).is_err() {
            return; // the browser closed the port
        }
        let n = u32::from_ne_bytes(len) as usize;
        if n > MAX_MESSAGE {
            return;
        }
        let mut buf = vec![0u8; n];
        if input.read_exact(&mut buf).is_err() {
            return;
        }
        let req: Value = serde_json::from_slice(&buf).unwrap_or_default();
        let path = req.get("path").and_then(|p| p.as_str()).unwrap_or("/ext/status");
        // Only the bridge's own endpoints; this relay is not a general localhost proxy.
        let reply = if path.starts_with("/ext/") {
            match relay(path, &req.get("body").cloned().unwrap_or(json!({})).to_string()) {
                Some(data) => json!({ "id": req.get("id"), "ok": true, "data": data }),
                None => json!({ "id": req.get("id"), "ok": false, "error": "Keel desktop is not running" }),
            }
        } else {
            json!({ "id": req.get("id"), "ok": false, "error": "unknown path" })
        };
        let bytes = reply.to_string().into_bytes();
        if out.write_all(&(bytes.len() as u32).to_ne_bytes()).and_then(|_| out.write_all(&bytes)).and_then(|_| out.flush()).is_err() {
            return;
        }
    }
}

fn relay(path: &str, body: &str) -> Option<Value> {
    let addr = SocketAddr::from(([127, 0, 0, 1], PORT));
    let mut s = TcpStream::connect_timeout(&addr, Duration::from_millis(800)).ok()?;
    s.set_read_timeout(Some(Duration::from_secs(4))).ok()?;
    let req = format!(
        "POST {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Keel-Ext: 1\r\nOrigin: chrome-extension://{CHROME_EXTENSION_ID}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    s.write_all(req.as_bytes()).ok()?;
    let mut resp = Vec::new();
    s.read_to_end(&mut resp).ok()?;
    let text = String::from_utf8_lossy(&resp);
    let (_, payload) = text.split_once("\r\n\r\n")?;
    serde_json::from_str(payload.trim()).ok()
}

/// Writes (or refreshes) the native-host manifest for every installed browser. Cheap and
/// idempotent, so it runs on every start: the binary may have moved since last time.
pub fn install_manifests() {
    let Ok(exe) = std::env::current_exe() else { return };
    let exe = exe.to_string_lossy().to_string();
    let chrome = json!({
        "name": HOST_NAME, "description": "Keel desktop bridge", "path": exe, "type": "stdio",
        "allowed_origins": [format!("chrome-extension://{CHROME_EXTENSION_ID}/")],
    });
    let firefox = json!({
        "name": HOST_NAME, "description": "Keel desktop bridge", "path": exe, "type": "stdio",
        "allowed_extensions": [FIREFOX_EXTENSION_ID],
    });
    platform::install(&chrome.to_string(), &firefox.to_string());
}

#[cfg(unix)]
mod platform {
    use super::HOST_NAME;

    pub fn install(chrome: &str, firefox: &str) {
        let Some(home) = dirs::home_dir() else { return };
        let config = dirs::config_dir().unwrap_or_else(|| home.join(".config"));
        // Only browsers that exist get a manifest: creating their config dirs would be litter.
        let chromium_family = [
            "BraveSoftware/Brave-Browser", "google-chrome", "google-chrome-beta", "chromium",
            "microsoft-edge", "vivaldi", "thorium",
        ];
        for b in chromium_family {
            let base = config.join(b);
            if base.is_dir() {
                let dir = base.join("NativeMessagingHosts");
                let _ = std::fs::create_dir_all(&dir);
                let _ = std::fs::write(dir.join(format!("{HOST_NAME}.json")), chrome);
            }
        }
        for base in [home.join(".mozilla"), home.join(".librewolf"), home.join(".zen"), home.join(".floorp")] {
            if base.is_dir() {
                let dir = base.join("native-messaging-hosts");
                let _ = std::fs::create_dir_all(&dir);
                let _ = std::fs::write(dir.join(format!("{HOST_NAME}.json")), firefox);
            }
        }
    }
}

#[cfg(windows)]
mod platform {
    use super::HOST_NAME;
    use std::os::windows::process::CommandExt;

    pub fn install(chrome: &str, firefox: &str) {
        let Some(dir) = dirs::data_dir().map(|d| d.join("keel").join("native")) else { return };
        let _ = std::fs::create_dir_all(&dir);
        let (c, f) = (dir.join("chrome.json"), dir.join("firefox.json"));
        if std::fs::write(&c, chrome).is_err() || std::fs::write(&f, firefox).is_err() {
            return;
        }
        let keys = [
            (r"Software\Google\Chrome\NativeMessagingHosts", &c),
            (r"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts", &c),
            (r"Software\Microsoft\Edge\NativeMessagingHosts", &c),
            (r"Software\Chromium\NativeMessagingHosts", &c),
            (r"Software\Mozilla\NativeMessagingHosts", &f),
        ];
        for (key, file) in keys {
            // CREATE_NO_WINDOW: reg.exe would otherwise flash a console on every start.
            let _ = std::process::Command::new("reg")
                .args(["add", &format!(r"HKCU\{key}\{HOST_NAME}"), "/ve", "/t", "REG_SZ", "/d", &file.to_string_lossy(), "/f"])
                .creation_flags(0x0800_0000)
                .output();
        }
    }
}

/// Native messages from the browser are capped at 1MB by Chrome anyway.
const MAX_MESSAGE: usize = 1 << 20;

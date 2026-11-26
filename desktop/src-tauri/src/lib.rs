//! Keel for Linux and Windows. The window is the shared Keel UI; the work happens in the
//! background: a 1s sampling thread (tracker + engine), the sync tasks, and the local bridge
//! for the browser extension. Closing the window hides it to the tray, because limits only
//! hold while this process runs.

mod engine;
mod ext;
mod model;
mod native;
mod sync;
mod tracker;

use engine::{Action, Engine, Session};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

struct App {
    engine: Arc<Mutex<Engine>>,
    sync: Arc<sync::Sync>,
}

#[tauri::command]
fn get_session(app: State<App>) -> Option<Session> {
    app.engine.lock().unwrap().session.clone()
}

#[tauri::command]
fn set_session(app: State<App>, session: Option<Session>) {
    app.engine.lock().unwrap().set_session(session);
    app.sync.session_changed.notify_waiters();
    let s = app.sync.clone();
    tauri::async_runtime::spawn(async move { s.report().await });
}

#[tauri::command]
fn device_name() -> String {
    #[cfg(windows)]
    let host = std::env::var("COMPUTERNAME").unwrap_or_default();
    #[cfg(unix)]
    let host = std::fs::read_to_string("/proc/sys/kernel/hostname").or_else(|_| std::fs::read_to_string("/etc/hostname")).unwrap_or_default();
    let host = host.trim();
    if host.is_empty() { "Computer".into() } else { host.to_string() }
}

#[tauri::command]
fn local_status(app: State<App>, handle: AppHandle) -> serde_json::Value {
    let mut e = app.engine.lock().unwrap();
    e.autostart = handle.autolaunch().is_enabled().unwrap_or(false);
    e.status()
}

#[tauri::command]
fn action(name: String, arg: String, handle: AppHandle) -> serde_json::Value {
    match name.as_str() {
        "autostart" => {
            let al = handle.autolaunch();
            let r = if arg == "on" { al.enable() } else { al.disable() };
            serde_json::json!({ "ok": r.is_ok() })
        }
        "openExtensionHelp" => {
            open_url("https://github.com/BurhanRajkot/Block-APP#browser-extension");
            serde_json::json!({ "ok": true })
        }
        _ => serde_json::json!({ "ok": false }),
    }
}

fn open_url(url: &str) {
    #[cfg(windows)]
    let _ = std::process::Command::new("cmd").args(["/C", "start", "", url]).spawn();
    #[cfg(unix)]
    let _ = std::process::Command::new("xdg-open").arg(url).spawn();
}

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// The wall: a full-screen Keel window saying why the app just closed. It closes itself.
fn show_wall(app: &AppHandle, reason: &str, message: &str, app_name: &str) {
    let q = |s: &str| s.bytes().map(|b| if b.is_ascii_alphanumeric() { (b as char).to_string() } else { format!("%{b:02X}") }).collect::<String>();
    let url = format!("block.html?reason={}&msg={}&app={}", q(reason), q(message), q(app_name));
    let app = app.clone();
    let _ = app.clone().run_on_main_thread(move || {
        if let Some(w) = app.get_webview_window("block") {
            let _ = w.close();
        }
        let built = WebviewWindowBuilder::new(&app, "block", WebviewUrl::App(url.into()))
            .title("Keel")
            .fullscreen(true)
            .always_on_top(true)
            .decorations(false)
            .skip_taskbar(true)
            .focused(true)
            .build();
        if built.is_ok() {
            let app2 = app.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_secs(WALL_SECS));
                let app3 = app2.clone();
                let _ = app2.run_on_main_thread(move || {
                    if let Some(w) = app3.get_webview_window("block") {
                        let _ = w.close();
                    }
                });
            });
        }
    });
}

pub fn run() {
    // Started by a browser as the extension's native-messaging host: relay and exit, no UI.
    // Must come before the single-instance plugin, which would otherwise hand the call to the
    // running app and pop its window open on every extension message.
    if native::invoked_as_host() {
        native::run_host();
        return;
    }
    native::install_manifests();
    let engine = Arc::new(Mutex::new(Engine::new()));
    let sync = sync::Sync::new(engine.clone());
    let hidden = std::env::args().any(|a| a == "--hidden");

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .manage(App { engine: engine.clone(), sync: sync.clone() })
        .invoke_handler(tauri::generate_handler![get_session, set_session, device_name, local_status, action])
        .setup(move |app| {
            let handle = app.handle().clone();

            // First run: start with the computer by default. Turning it off is one switch away.
            let first = dirs::data_dir().map(|d| d.join("keel").join(".autostart-set"));
            if let Some(f) = first.filter(|f| !f.exists()) {
                let _ = handle.autolaunch().enable();
                let _ = std::fs::write(f, "1");
            }

            sync.start();
            ext::start(engine.clone(), sync.clone());

            // The 1s sampler. Window-system calls block, so it's a plain thread, not a task.
            let (eng, snc, h) = (engine.clone(), sync.clone(), handle.clone());
            std::thread::spawn(move || {
                let mut tracker = tracker::Tracker::new();
                {
                    let mut e = eng.lock().unwrap();
                    e.backend = tracker.backend_name();
                    e.tracking = tracker.working();
                }
                loop {
                    std::thread::sleep(Duration::from_secs(1));
                    let win = tracker.active();
                    let idle = tracker.idle_secs();
                    let act = eng.lock().unwrap().tick(win, idle);
                    match act {
                        Action::Close(w, v) => {
                            let name = eng.lock().unwrap().target_for(&w).map(|t| t.1).unwrap_or_default();
                            tracker.close(&w);
                            show_wall(&h, &v.reason, &v.message, &name);
                        }
                        Action::Violation(id, target) => {
                            let s = snc.clone();
                            tauri::async_runtime::spawn(async move { s.violation(id, target).await });
                        }
                        Action::None => {}
                    }
                }
            });

            let open = MenuItem::with_id(app, "open", "Open Keel", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            let eng = engine.clone();
            TrayIconBuilder::with_id("keel")
                .icon(app.default_window_icon().cloned().expect("icon"))
                .tooltip("Keel")
                .menu(&menu)
                .on_menu_event(move |app, ev| match ev.id().as_ref() {
                    "open" => show_main(app),
                    "quit" => {
                        // Quitting is the obvious way out of a focus session or a lockdown, so
                        // it waits until they're over. Outside those it just quits.
                        let e = eng.lock().unwrap();
                        let locked = e.enforce.focus.is_some() || e.enforce.lockdown.active;
                        drop(e);
                        if locked {
                            show_main(app);
                        } else {
                            eng.lock().unwrap().save();
                            app.exit(0);
                        }
                    }
                    _ => {}
                })
                .build(app)?;

            if let Some(w) = app.get_webview_window("main") {
                let w2 = w.clone();
                w.on_window_event(move |ev| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = ev {
                        api.prevent_close();
                        let _ = w2.hide();
                    }
                });
                if !hidden {
                    let _ = w.show();
                }
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running Keel");
}

/// Long enough to read, short enough not to feel like a hostage screen.
const WALL_SECS: u64 = 6;

//! The local bridge the browser extension talks to, on 127.0.0.1 only. The extension tells it
//! which site is in the focused tab and which reels it saw; it answers with what to wall.
//!
//! Any web page can also send requests to localhost, so every request must carry the
//! X-Keel-Ext header. A page setting a custom header triggers a CORS preflight, and the
//! preflight is only granted to extension origins, so a page can't inflate the reel count.

use crate::engine::Engine;
use crate::sync::Sync;
use std::io::Read;
use std::sync::{Arc, Mutex};
use tiny_http::{Header, Method, Response, Server};

// 47321 was the first choice and collided with StreamVault on the dev machine, so the bridge
// silently never started and the extension talked to the wrong app. A failed bind is now
// reported in the Devices screen ([BIND_ERROR]) instead of only in a log nobody reads.
pub const PORT: u16 = 47823;

pub static BIND_ERROR: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

fn header(k: &str, v: &str) -> Header {
    Header::from_bytes(k.as_bytes(), v.as_bytes()).expect("static header")
}

fn ext_origin(origin: &str) -> bool {
    origin.starts_with("chrome-extension://") || origin.starts_with("moz-extension://") || origin.starts_with("safari-web-extension://")
}

pub fn start(engine: Arc<Mutex<Engine>>, sync: Arc<Sync>) {
    std::thread::spawn(move || {
        let server = match Server::http(("127.0.0.1", PORT)) {
            Ok(s) => s,
            Err(e) => {
                log::error!("extension bridge could not bind {PORT}: {e}");
                *BIND_ERROR.lock().unwrap() = Some(format!("Port {PORT} is taken by another app, so the browser extension can't connect ({e})"));
                return;
            }
        };
        for mut req in server.incoming_requests() {
            let origin = req.headers().iter().find(|h| h.field.equiv("Origin")).map(|h| h.value.to_string()).unwrap_or_default();
            let allowed = origin.is_empty() || ext_origin(&origin);
            let cors = |r: Response<std::io::Cursor<Vec<u8>>>| {
                let mut r = r.with_header(header("Content-Type", "application/json"));
                if allowed && !origin.is_empty() {
                    r = r
                        .with_header(header("Access-Control-Allow-Origin", &origin))
                        .with_header(header("Access-Control-Allow-Headers", "Content-Type, X-Keel-Ext"))
                        .with_header(header("Access-Control-Allow-Methods", "GET, POST, OPTIONS"))
                        // Chrome's Private Network Access asks before an extension reaches localhost.
                        .with_header(header("Access-Control-Allow-Private-Network", "true"));
                }
                r
            };
            if *req.method() == Method::Options {
                let _ = req.respond(cors(Response::from_data(Vec::new()).with_status_code(if allowed { 204 } else { 403 })));
                continue;
            }
            let has_mark = req.headers().iter().any(|h| h.field.equiv("X-Keel-Ext"));
            if !allowed || !has_mark {
                let _ = req.respond(cors(Response::from_string("{\"error\":\"forbidden\"}").with_status_code(403)));
                continue;
            }
            let mut body = String::new();
            let _ = req.as_reader().take(64 * 1024).read_to_string(&mut body);
            let v: serde_json::Value = serde_json::from_str(&body).unwrap_or_default();
            let s = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
            let out = {
                let mut e = engine.lock().unwrap();
                match req.url() {
                    "/ext/heartbeat" => e.ext_heartbeat(&s("browser"), &s("domain"), v.get("focused").and_then(|f| f.as_bool()).unwrap_or(false)),
                    "/ext/reel" => {
                        let r = e.ext_reel(&s("domain"), &s("id"));
                        if e.reels_dirty {
                            sync.reel.notify_one();
                        }
                        r
                    }
                    "/ext/status" => e.ext_verdict(&s("domain")),
                    _ => serde_json::json!({ "error": "not found" }),
                }
            };
            let _ = req.respond(cors(Response::from_string(out.to_string())));
        }
    });
}

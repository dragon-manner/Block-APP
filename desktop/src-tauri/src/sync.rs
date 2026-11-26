//! Talks to the Keel server: reports this computer's day every 30s (absolute totals, so a
//! retry can't double count), pushes reel counts the moment they change, and listens on the
//! WebSocket so a limit set on the phone lands here within a second.

use crate::engine::{now_ms, Engine, Session};
use crate::model::Enforce;
use futures_util::StreamExt;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::Notify;

pub struct Sync {
    pub engine: Arc<Mutex<Engine>>,
    pub http: reqwest::Client,
    /// Poked when the session changes (sign in/out) so the socket reconnects as the new user.
    pub session_changed: Notify,
    /// Poked when the extension counts a reel.
    pub reel: Notify,
    /// Poked when the server says something changed.
    pub changed: Notify,
}

impl Sync {
    pub fn new(engine: Arc<Mutex<Engine>>) -> Arc<Sync> {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(15)).user_agent("keel-desktop").build().expect("http client");
        Arc::new(Sync { engine, http, session_changed: Notify::new(), reel: Notify::new(), changed: Notify::new() })
    }

    fn session(&self) -> Option<Session> {
        self.engine.lock().unwrap().session.clone()
    }

    async fn call(&self, s: &Session, method: reqwest::Method, path: &str, body: Option<serde_json::Value>) -> Result<serde_json::Value, String> {
        let url = format!("{}/api{}", s.server.trim_end_matches('/'), path);
        let mut req = self.http.request(method, url).bearer_auth(&s.token);
        if let Some(b) = body {
            req = req.json(&b);
        }
        let res = req.send().await.map_err(|e| format!("offline ({})", e.without_url()))?;
        let status = res.status();
        let v: serde_json::Value = res.json().await.unwrap_or_default();
        if status.as_u16() == 401 {
            // Signed out remotely (the device was removed): stop enforcing as that account.
            self.engine.lock().unwrap().set_session(None);
            self.session_changed.notify_waiters();
            return Err("signed out".into());
        }
        if !status.is_success() {
            return Err(v.get("error").and_then(|e| e.as_str()).unwrap_or("server error").to_string());
        }
        Ok(v)
    }

    fn apply(&self, v: serde_json::Value, sent: i64) {
        let mut e = self.engine.lock().unwrap();
        match serde_json::from_value::<Enforce>(v) {
            Ok(enf) => {
                e.set_enforce(enf, sent);
                e.last_sync = now_ms();
                e.sync_error.clear();
            }
            Err(err) => e.sync_error = format!("bad response: {err}"),
        }
    }

    fn fail(&self, err: String) {
        self.engine.lock().unwrap().sync_error = err;
    }

    pub async fn report(&self) {
        let Some(s) = self.session() else { return };
        let (unsent, day) = {
            let e = self.engine.lock().unwrap();
            (e.unsent.clone(), e.day.clone())
        };
        if let Some(old) = unsent {
            // Yesterday's last minutes, sent once before today's first report.
            if self.call(&s, reqwest::Method::POST, "/usage", Some(Engine::usage_payload(&old))).await.is_ok() {
                self.engine.lock().unwrap().unsent = None;
            }
        }
        let sent = now_ms();
        match self.call(&s, reqwest::Method::POST, "/usage", Some(Engine::usage_payload(&day))).await {
            Ok(v) => self.apply(v, sent),
            Err(e) => self.fail(e),
        }
    }

    pub async fn refresh(&self) {
        let Some(s) = self.session() else { return };
        let sent = now_ms();
        match self.call(&s, reqwest::Method::GET, "/enforce", None).await {
            Ok(v) => self.apply(v, sent),
            Err(e) => self.fail(e),
        }
    }

    pub async fn push_reels(&self) {
        let Some(s) = self.session() else { return };
        let (date, count) = {
            let mut e = self.engine.lock().unwrap();
            e.reels_dirty = false;
            (e.day.date.clone(), e.day.reels)
        };
        let sent = now_ms();
        match self.call(&s, reqwest::Method::POST, "/reels", Some(serde_json::json!({ "date": date, "count": count }))).await {
            Ok(v) => self.apply(v, sent),
            Err(e) => self.fail(e),
        }
    }

    pub async fn status(&self) {
        let Some(s) = self.session() else { return };
        let st = self.engine.lock().unwrap().status();
        let _ = self.call(&s, reqwest::Method::POST, "/device/status", Some(st)).await;
    }

    pub async fn violation(&self, focus_id: i64, target: String) {
        let Some(s) = self.session() else { return };
        let _ = self.call(&s, reqwest::Method::POST, &format!("/focus/{focus_id}/violation"), Some(serde_json::json!({ "target": target }))).await;
        self.refresh().await;
    }

    pub fn start(self: &Arc<Self>) {
        let me = self.clone();
        tauri::async_runtime::spawn(async move {
            let mut n = 0u64;
            loop {
                me.report().await;
                if n % 10 == 0 {
                    me.status().await;
                }
                n += 1;
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(REPORT_EVERY_SECS)) => {}
                    _ = me.session_changed.notified() => {}
                }
            }
        });
        let me = self.clone();
        tauri::async_runtime::spawn(async move {
            loop {
                me.reel.notified().await;
                // Coalesce a fast swipe streak into one request.
                tokio::time::sleep(Duration::from_millis(300)).await;
                me.push_reels().await;
            }
        });
        let me = self.clone();
        tauri::async_runtime::spawn(async move {
            loop {
                me.changed.notified().await;
                tokio::time::sleep(Duration::from_millis(200)).await;
                me.refresh().await;
            }
        });
        let me = self.clone();
        tauri::async_runtime::spawn(async move { me.socket_loop().await });
    }

    async fn socket_loop(self: Arc<Self>) {
        let mut backoff = 1u64;
        loop {
            let Some(s) = self.session() else {
                self.session_changed.notified().await;
                continue;
            };
            let url = format!("{}/api/ws?token={}", s.server.trim_end_matches('/').replacen("http", "ws", 1), s.token);
            match tokio_tungstenite::connect_async(url.as_str()).await {
                Ok((mut ws, _)) => {
                    backoff = 1;
                    self.refresh().await;
                    loop {
                        tokio::select! {
                            msg = ws.next() => match msg {
                                Some(Ok(m)) if m.is_text() => {
                                    if m.to_text().unwrap_or("").contains("changed") {
                                        self.changed.notify_one();
                                    }
                                }
                                Some(Ok(_)) => {}
                                _ => break,
                            },
                            _ = self.session_changed.notified() => break,
                        }
                    }
                }
                Err(e) => self.fail(format!("live updates offline ({e})")),
            }
            tokio::time::sleep(Duration::from_secs(backoff)).await;
            backoff = (backoff * 2).min(30);
        }
    }
}

/// Each report also returns fresh enforcement, so this is the worst-case staleness when the
/// socket is down. The socket makes the usual case about a second.
const REPORT_EVERY_SECS: u64 = 30;

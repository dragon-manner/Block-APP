//! The desktop engine: what's in front, how long it has been, what the browser extension says
//! the tab is, and what to do about it. Everything here is synchronous and lives behind one
//! mutex; the network side (sync.rs) and the extension bridge (ext.rs) only read and poke it.

use crate::model::{reel_verdict, verdict, work_phase, Catalog, Enforce, Verdict};
use crate::tracker::Win;
use chrono::{Local, Timelike};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::time::{Duration, Instant};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub server: String,
    pub token: String,
    pub device_id: i64,
    #[serde(default)]
    pub email: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct AppUse {
    pub name: String,
    pub seconds: i64,
    pub opens: i64,
    pub reels: i64,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Day {
    pub date: String,
    pub apps: HashMap<String, AppUse>,
    pub hours: Vec<i64>,
    pub reels: i64,
    #[serde(default)]
    pub seen_reels: VecDeque<String>,
}

impl Day {
    fn new(date: String) -> Day {
        Day { date, hours: vec![0; 24], ..Default::default() }
    }
}

pub struct ExtBeat {
    pub at: Instant,
    pub browser: String,
    pub domain: String,
    pub focused: bool,
}

pub enum Action {
    None,
    /// Close this window and show the wall.
    Close(Win, Verdict),
    /// Soft focus session: a distraction was held open past the warning.
    Violation(i64, String),
}

pub struct Engine {
    pub session: Option<Session>,
    pub enforce: Enforce,
    pub skew: i64,
    pub catalog: Catalog,
    pub day: Day,
    pub unsent: Option<Day>,
    pub current: Option<(String, Win)>,
    pub ext: Option<ExtBeat>,
    pub last_sync: i64,
    pub sync_error: String,
    pub backend: String,
    pub tracking: bool,
    pub autostart: bool,
    pub reels_dirty: bool,
    distraction_since: Option<(String, Instant)>,
    violated: HashSet<i64>,
    last_close: Option<(String, Instant)>,
    dir: PathBuf,
    last_save: Instant,
}

pub fn today() -> String {
    Local::now().format("%Y-%m-%d").to_string()
}

pub fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

impl Engine {
    pub fn new() -> Engine {
        let dir = dirs::data_dir().unwrap_or_else(std::env::temp_dir).join("keel");
        let _ = std::fs::create_dir_all(&dir);
        let read = |f: &str| std::fs::read_to_string(dir.join(f)).ok();
        let session = read("session.json").and_then(|s| serde_json::from_str(&s).ok());
        // The last enforcement survives a restart, so a reboot mid-focus doesn't open everything
        // up until the network comes back.
        let enforce = read("enforce.json").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        let mut day: Day = read("day.json").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_else(|| Day::new(today()));
        let mut unsent = None;
        if day.date != today() {
            unsent = Some(day);
            day = Day::new(today());
        }
        if day.hours.len() != 24 {
            day.hours = vec![0; 24];
        }
        Engine {
            session, enforce, skew: 0, catalog: Catalog::load(), day, unsent, current: None, ext: None,
            last_sync: 0, sync_error: String::new(), backend: String::new(), tracking: false, autostart: false,
            reels_dirty: false, distraction_since: None, violated: HashSet::new(), last_close: None, dir, last_save: Instant::now(),
        }
    }

    pub fn now(&self) -> i64 {
        now_ms() + self.skew
    }

    pub fn set_session(&mut self, s: Option<Session>) {
        match &s {
            Some(s) => {
                let _ = std::fs::write(self.dir.join("session.json"), serde_json::to_string(s).unwrap_or_default());
            }
            None => {
                let _ = std::fs::remove_file(self.dir.join("session.json"));
                self.enforce = Enforce::default();
                let _ = std::fs::remove_file(self.dir.join("enforce.json"));
            }
        }
        self.session = s;
    }

    pub fn set_enforce(&mut self, e: Enforce, sent_at: i64) {
        // Half the round trip is the best guess at when the server wrote `now`.
        self.skew = e.now - (sent_at + (now_ms() - sent_at) / 2);
        self.enforce = e;
        let _ = std::fs::write(self.dir.join("enforce.json"), serde_json::to_string(&self.enforce).unwrap_or_default());
    }

    pub fn save(&mut self) {
        let _ = std::fs::write(self.dir.join("day.json"), serde_json::to_string(&self.day).unwrap_or_default());
        self.last_save = Instant::now();
    }

    fn roll_day(&mut self) {
        let t = today();
        if self.day.date != t {
            let old = std::mem::replace(&mut self.day, Day::new(t));
            self.unsent = Some(old);
            self.violated.clear();
            self.save();
        }
    }

    pub fn local_secs(&self, target: &str) -> i64 {
        self.day.apps.get(target).map(|a| a.seconds).unwrap_or(0)
    }

    fn ext_fresh(&self) -> Option<&ExtBeat> {
        self.ext.as_ref().filter(|b| b.at.elapsed() < Duration::from_secs(6))
    }

    pub fn ext_connected(&self) -> bool {
        self.ext.as_ref().is_some_and(|b| b.at.elapsed() < Duration::from_secs(60))
    }

    /// The target a window counts towards. A browser counts as the site in its focused tab
    /// while the extension is reporting, otherwise as the browser itself.
    pub fn target_for(&self, w: &Win) -> Option<(String, String)> {
        if w.app.contains("keel") {
            return None;
        }
        if self.catalog.is_browser(&w.app) {
            if let Some(b) = self.ext_fresh().filter(|b| b.focused && !b.domain.is_empty()) {
                return Some(match self.catalog.domain_target(&b.domain) {
                    Some(t) => (t.id.clone(), t.name.clone()),
                    None => (format!("web:{}", b.domain.trim_start_matches("www.")), b.domain.trim_start_matches("www.").to_string()),
                });
            }
        }
        Some(match self.catalog.desktop_target(&w.app) {
            Some(t) => (t.id.clone(), t.name.clone()),
            // Reverse-DNS classes (Wayland's "org.kde.kcalc") name the app in their last part,
            // which is also what the same app's .exe is called on Windows.
            None => {
                let base = if w.app.matches('.').count() >= 2 { w.app.rsplit('.').next().unwrap_or(&w.app) } else { w.app.as_str() };
                (format!("desktop:{base}"), pretty(base))
            }
        })
    }

    /// One second of the world. Returns what the caller (which owns the window system) must do.
    pub fn tick(&mut self, win: Option<Win>, idle_secs: u64) -> Action {
        self.roll_day();
        let target = win.as_ref().and_then(|w| self.target_for(w).map(|t| (t, w.clone())));
        let changed = self.current.as_ref().map(|c| &c.0) != target.as_ref().map(|t| &t.0 .0);
        if let Some(((id, name), w)) = &target {
            if idle_secs < IDLE_AFTER_SECS {
                let h = Local::now().hour() as usize;
                let e = self.day.apps.entry(id.clone()).or_default();
                e.name = name.clone();
                e.seconds += 1;
                if changed {
                    e.opens += 1;
                }
                self.day.hours[h] += 1;
            }
            self.current = Some((id.clone(), w.clone()));
        } else {
            self.current = None;
        }
        if self.last_save.elapsed() > Duration::from_secs(60) {
            self.save();
        }
        if self.session.is_none() {
            return Action::None;
        }
        let Some(((id, _), w)) = target else {
            self.distraction_since = None;
            return Action::None;
        };
        let now = self.now();
        let v = verdict(&self.enforce, &self.catalog, &id, self.local_secs(&id), now);
        if v.blocked {
            // Sites are walled by the extension inside the tab; closing the whole browser for
            // one tab would take every other tab with it.
            if self.catalog.is_browser(&w.app) {
                return Action::None;
            }
            // Re-closing the same window every second while the WM animates it out does nothing.
            if self.last_close.as_ref().is_some_and(|(h, t)| *h == w.handle && t.elapsed() < Duration::from_secs(3)) {
                return Action::None;
            }
            self.last_close = Some((w.handle.clone(), Instant::now()));
            return Action::Close(w, v);
        }
        // Soft focus: nothing is blocked, but holding a distraction open withers the tree.
        if let (Some(f), Some(_)) = (self.enforce.focus.as_ref(), work_phase(&self.enforce, now)) {
            let cat = self.catalog.category(&id, &self.enforce.category_overrides);
            if !f.strict && f.categories.contains(&cat) && !self.violated.contains(&f.id) {
                let since = match &self.distraction_since {
                    Some((t, at)) if *t == id => *at,
                    _ => {
                        self.distraction_since = Some((id.clone(), Instant::now()));
                        Instant::now()
                    }
                };
                if since.elapsed() > Duration::from_secs(SOFT_GRACE_SECS) {
                    self.violated.insert(f.id);
                    return Action::Violation(f.id, self.catalog.name(&id));
                }
                return Action::None;
            }
        }
        self.distraction_since = None;
        Action::None
    }

    // ---- Browser extension ------------------------------------------------------------------

    pub fn ext_heartbeat(&mut self, browser: &str, domain: &str, focused: bool) -> serde_json::Value {
        self.ext = Some(ExtBeat { at: Instant::now(), browser: browser.into(), domain: domain.to_lowercase(), focused });
        self.ext_verdict(domain)
    }

    pub fn ext_verdict(&self, domain: &str) -> serde_json::Value {
        let now = self.now();
        let signed_in = self.session.is_some();
        let d = domain.to_lowercase();
        let d = d.trim_start_matches("www.");
        let (target, reels_site) = match self.catalog.domain_target(d) {
            Some(t) => (t.id.clone(), true),
            None => (format!("web:{d}"), false),
        };
        let site = if signed_in && !d.is_empty() { verdict(&self.enforce, &self.catalog, &target, self.local_secs(&target), now) } else { Verdict::open() };
        let (reel, remaining) = if signed_in { reel_verdict(&self.enforce, self.day.reels, now) } else { (Verdict::open(), None) };
        json!({
            "signedIn": signed_in,
            "target": target,
            "site": site,
            "reels": { "allowed": !reel.blocked, "remaining": remaining, "total": self.enforce.reels.others + self.day.reels, "limit": self.enforce.reels.limit, "verdict": reel },
            "reelsSite": reels_site,
            "focus": self.enforce.focus.as_ref().map(|f| json!({ "ends": f.ends, "label": f.label })),
        })
    }

    /// Counts a reel the extension saw. Ids are remembered for the day, so the same Short open
    /// in two tabs, or scrolled back to, counts once.
    pub fn ext_reel(&mut self, domain: &str, id: &str) -> serde_json::Value {
        self.roll_day();
        let key = format!("{domain}:{id}");
        if !id.is_empty() && !self.day.seen_reels.contains(&key) {
            self.day.seen_reels.push_back(key);
            while self.day.seen_reels.len() > 2000 {
                self.day.seen_reels.pop_front();
            }
            let (now, reels) = (self.now(), self.day.reels);
            let (v, _) = reel_verdict(&self.enforce, reels, now);
            // A reel that opens over the limit is walled, not counted: counting it would push
            // the shared total past the limit on every other device too.
            if !v.blocked {
                self.day.reels += 1;
                let target = self.catalog.domain_target(domain).map(|t| t.id.clone()).unwrap_or_else(|| format!("web:{domain}"));
                let name = self.catalog.name(&target);
                let e = self.day.apps.entry(target).or_default();
                e.reels += 1;
                if e.name.is_empty() {
                    e.name = name;
                }
                self.reels_dirty = true;
            }
        }
        self.ext_verdict(domain)
    }

    // ---- Sync payloads ----------------------------------------------------------------------

    pub fn usage_payload(day: &Day) -> serde_json::Value {
        let apps: Vec<_> = day.apps.iter().map(|(t, a)| json!({ "target": t, "name": a.name, "seconds": a.seconds, "opens": a.opens, "reels": a.reels })).collect();
        json!({ "date": day.date, "apps": apps, "hours": day.hours, "pickups": 0, "reels": day.reels })
    }

    pub fn status(&self) -> serde_json::Value {
        json!({
            "tracking": self.tracking,
            "backend": self.backend,
            "extension": self.ext_connected(),
            "extensionBrowser": self.ext.as_ref().map(|b| b.browser.clone()),
            "autostart": self.autostart,
            "active": self.current.as_ref().map(|c| self.catalog.name(&c.0)),
            "lastSync": if self.last_sync > 0 { Some(self.last_sync) } else { None },
            "syncError": if self.sync_error.is_empty() { None } else { Some(self.sync_error.clone()) },
            "bridgeError": crate::ext::BIND_ERROR.lock().unwrap().clone(),
            "version": env!("CARGO_PKG_VERSION"),
        })
    }
}

fn pretty(app: &str) -> String {
    let base = app.rsplit('.').next().unwrap_or(app);
    let mut c = base.chars();
    c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
}

/// Five minutes without input stops the clock, so a laptop left on YouTube overnight doesn't
/// log eight hours.
const IDLE_AFTER_SECS: u64 = 300;
/// How long a soft-focus session lets a distraction sit in front before the tree withers.
const SOFT_GRACE_SECS: u64 = 10;

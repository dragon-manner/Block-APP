//! The server's enforcement contract (server/enforce.go) and the shared catalog, plus the one
//! function that turns them into "is this blocked right now". The Android app carries the same
//! rules in Kotlin (sync/Verdict.kt); change one and change the other.

use chrono::{Local, Timelike};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Quiet {
    pub enabled: bool,
    pub from: String,
    pub to: String,
    pub block_reels: bool,
    pub categories: Vec<String>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct ReelState {
    pub limit: Option<i64>,
    pub others: i64,
    pub mine: i64,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct LimitState {
    pub target: String,
    pub minutes: i64,
    pub others: i64,
    pub mine: i64,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct Phase {
    pub kind: String,
    pub start: i64,
    pub end: i64,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Focus {
    pub id: i64,
    pub label: String,
    pub ends: i64,
    pub strict: bool,
    pub categories: Vec<String>,
    pub phases: Vec<Phase>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct Lockdown {
    pub active: bool,
    pub tasks: Vec<String>,
    pub categories: Vec<String>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Enforce {
    pub now: i64,
    pub date: String,
    pub reels: ReelState,
    pub limits: Vec<LimitState>,
    pub focus: Option<Focus>,
    pub lockdown: Lockdown,
    pub quiet: Quiet,
    pub pass_until: i64,
    pub category_overrides: HashMap<String, String>,
    pub armed: bool,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct Target {
    pub id: String,
    pub name: String,
    pub category: String,
    pub domains: Vec<String>,
    pub desktop: Vec<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct Category {
    pub id: String,
    pub distracting: bool,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct Catalog {
    pub categories: Vec<Category>,
    pub targets: Vec<Target>,
    pub browsers: Vec<String>,
}

impl Catalog {
    pub fn load() -> Catalog {
        serde_json::from_str(include_str!("../catalog.json")).unwrap_or_default()
    }

    fn segments(app: &str) -> impl Iterator<Item = &str> {
        app.split(|c: char| c == '.' || c == '-' || c == ' ' || c == '_').filter(|s| !s.is_empty())
    }

    /// Desktop app id (window class or exe stem, lowercase) to a catalog target.
    pub fn desktop_target(&self, app: &str) -> Option<&Target> {
        self.targets.iter().find(|t| {
            t.desktop.iter().any(|m| {
                app == m
                    || (m.ends_with('_') && app.starts_with(m.as_str()))
                    || Self::segments(app).any(|s| s == m)
            })
        })
    }

    pub fn is_browser(&self, app: &str) -> bool {
        self.browsers.iter().any(|b| app == b || Self::segments(app).any(|s| s == b))
    }

    /// A domain like "www.youtube.com" or "m.youtube.com" to its target, matching on the
    /// registrable suffix so every subdomain shares the site's budget.
    pub fn domain_target(&self, domain: &str) -> Option<&Target> {
        let d = domain.trim_start_matches("www.");
        self.targets.iter().find(|t| t.domains.iter().any(|m| d == m || d.ends_with(&format!(".{m}"))))
    }

    pub fn category(&self, target: &str, overrides: &HashMap<String, String>) -> String {
        if let Some(c) = overrides.get(target) {
            return c.clone();
        }
        self.targets.iter().find(|t| t.id == target).map(|t| t.category.clone()).unwrap_or_else(|| "other".into())
    }

    pub fn name(&self, target: &str) -> String {
        self.targets.iter().find(|t| t.id == target).map(|t| t.name.clone()).unwrap_or_else(|| {
            let rest = target.split_once(':').map(|x| x.1).unwrap_or(target);
            let mut c = rest.chars();
            c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
        })
    }
}

#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Verdict {
    pub blocked: bool,
    pub reason: String, // focus | lockdown | quiet | limit | reels
    pub message: String,
    pub until: i64,
}

impl Verdict {
    pub fn open() -> Verdict {
        Verdict { blocked: false, reason: String::new(), message: String::new(), until: 0 }
    }
    fn block(reason: &str, message: String, until: i64) -> Verdict {
        Verdict { blocked: true, reason: reason.into(), message, until }
    }
}

fn hhmm(s: &str) -> Option<u32> {
    let (h, m) = s.split_once(':')?;
    let (h, m): (u32, u32) = (h.parse().ok()?, m.parse().ok()?);
    (h < 24 && m < 60).then_some(h * 60 + m)
}

pub fn quiet_active(q: &Quiet) -> bool {
    if !q.enabled {
        return false;
    }
    let (Some(f), Some(t)) = (hhmm(&q.from), hhmm(&q.to)) else { return false };
    let now = Local::now();
    let m = now.hour() * 60 + now.minute();
    if f == t {
        false
    } else if f < t {
        m >= f && m < t
    } else {
        m >= f || m < t
    }
}

pub fn work_phase(e: &Enforce, now: i64) -> Option<&Phase> {
    e.focus.as_ref()?.phases.iter().find(|p| p.kind == "work" && now >= p.start && now < p.end)
}

/// Whether `target` may be in front right now. `local_secs` is this device's own time on it
/// today, which is fresher than the server's copy of it.
pub fn verdict(e: &Enforce, cat: &Catalog, target: &str, local_secs: i64, now: i64) -> Verdict {
    let category = cat.category(target, &e.category_overrides);
    let name = cat.name(target);
    if let (Some(f), Some(p)) = (e.focus.as_ref(), work_phase(e, now)) {
        if f.strict && f.categories.iter().any(|c| *c == category) {
            return Verdict::block("focus", format!("{name} is blocked while you focus{}.", if f.label.is_empty() { String::new() } else { format!(" on “{}”", f.label) }), p.end);
        }
    }
    if e.lockdown.active && e.lockdown.categories.iter().any(|c| *c == category) {
        let task = e.lockdown.tasks.first().cloned().unwrap_or_default();
        return Verdict::block("lockdown", format!("Lockdown: “{task}” is overdue. Finish it or work it off with 25 focused minutes."), 0);
    }
    if quiet_active(&e.quiet) && e.quiet.categories.iter().any(|c| *c == category) {
        return Verdict::block("quiet", format!("Quiet hours until {}.", e.quiet.to), 0);
    }
    if let Some(l) = e.limits.iter().find(|l| l.target == target) {
        if e.pass_until <= now && l.others + local_secs >= l.minutes * 60 {
            return Verdict::block("limit", format!("You've used today's {} min of {name} across your devices.", l.minutes), 0);
        }
    }
    Verdict::open()
}

/// Whether one more reel may play, given this device's own count today.
pub fn reel_verdict(e: &Enforce, local_reels: i64, now: i64) -> (Verdict, Option<i64>) {
    let remaining = e.reels.limit.map(|l| (l - e.reels.others - local_reels).max(0));
    if work_phase(e, now).is_some() {
        return (Verdict::block("focus", "Reels are closed during a focus round.".into(), 0), remaining);
    }
    if e.lockdown.active {
        return (Verdict::block("lockdown", "Reels are locked until your overdue task is done.".into(), 0), remaining);
    }
    if e.quiet.block_reels && quiet_active(&e.quiet) {
        return (Verdict::block("quiet", format!("Reel-free hours until {}.", e.quiet.to), 0), remaining);
    }
    if remaining == Some(0) {
        return (Verdict::block("reels", format!("That's today's {} reels, counted across your phone and browsers.", e.reels.limit.unwrap_or(0)), 0), remaining);
    }
    (Verdict::open(), remaining)
}

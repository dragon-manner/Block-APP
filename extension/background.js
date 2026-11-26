// Keel extension background. It is a relay: content scripts report what they see, this
// forwards it to the Keel desktop app, and hands back the verdict. All counting, limits and
// syncing live in the desktop app, so the browser can't disagree with the phone.
//
// The way in is native messaging (host "app.keel.bridge", a relay mode of the Keel binary).
// Chromium 14x+ blocks an extension worker's fetch to 127.0.0.1 behind a Local Network Access
// permission it has no way to ask for, so the direct fetch below is only a fallback for
// browsers that still allow it. The port is kept open: a connected native port also keeps the
// MV3 worker alive, and it avoids starting a process per heartbeat.

const BRIDGE = 'http://127.0.0.1:47823';
const HOST = 'app.keel.bridge';
const api = globalThis.browser ?? globalThis.chrome;
const BROWSER = navigator.userAgent.includes('Firefox') ? 'Firefox'
  : navigator.userAgent.includes('Edg/') ? 'Edge'
  : navigator.brave ? 'Brave' : 'Chrome';

let last = { ok: false, at: 0, data: null, via: '' };
let port = null;
let nextId = 1;
const waiting = new Map();
let nativeBroken = false;

function openPort() {
  if (port || nativeBroken) return port;
  try {
    port = api.runtime.connectNative(HOST);
  } catch {
    nativeBroken = true;
    return null;
  }
  port.onMessage.addListener((m) => {
    const cb = waiting.get(m.id);
    if (cb) {
      waiting.delete(m.id);
      cb(m);
    }
  });
  port.onDisconnect.addListener(() => {
    const err = api.runtime.lastError?.message || '';
    // "Specified native messaging host not found" means the desktop app never installed its
    // manifest here: stop trying and fall back to fetch until the worker restarts.
    if (/not found|forbidden/i.test(err)) nativeBroken = true;
    port = null;
    for (const [, cb] of waiting) cb({ ok: false, error: err || 'native host closed' });
    waiting.clear();
  });
  return port;
}

function viaNative(path, body) {
  return new Promise((resolve) => {
    const p = openPort();
    if (!p) return resolve(null);
    const id = nextId++;
    const timer = setTimeout(() => {
      waiting.delete(id);
      resolve(null);
    }, 4000);
    waiting.set(id, (m) => {
      clearTimeout(timer);
      resolve(m.ok ? m.data : m.error === 'Keel desktop is not running' ? { __down: true } : null);
    });
    try {
      p.postMessage({ id, path, body });
    } catch {
      clearTimeout(timer);
      waiting.delete(id);
      port = null;
      resolve(null);
    }
  });
}

async function viaFetch(path, body) {
  try {
    const res = await fetch(BRIDGE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Keel-Ext': '1' },
      body: JSON.stringify(body),
    });
    return await res.json();
  } catch {
    return null;
  }
}

async function bridge(path, payload) {
  const body = { browser: BROWSER, ...payload };
  let data = await viaNative(path, body);
  let via = 'native';
  if (data === null) {
    data = await viaFetch(path, body);
    via = 'fetch';
  }
  if (data && !data.__down) {
    last = { ok: true, at: Date.now(), data, via };
    setBadge(data);
    return data;
  }
  // Keel isn't running (or can't be reached): nothing is enforced here, and the popup says so.
  last = { ok: false, at: Date.now(), data: null, via, nativeBroken };
  setBadge(null);
  return null;
}

function setBadge(data) {
  const a = api.action;
  if (!a) return;
  if (!data) {
    a.setBadgeText({ text: '!' });
    a.setBadgeBackgroundColor({ color: '#FF7A3D' });
    return;
  }
  const r = data.reels || {};
  a.setBadgeText({ text: r.remaining == null ? '' : String(r.remaining) });
  a.setBadgeBackgroundColor({ color: r.allowed ? '#3C4137' : '#FF7A3D' });
}

api.runtime.onMessage.addListener((msg, sender, reply) => {
  const tabActive = sender.tab ? sender.tab.active : false;
  if (msg.type === 'beat') {
    // Only the active tab of a focused window counts as "in front".
    bridge('/ext/heartbeat', { domain: msg.domain, focused: !!msg.focused && tabActive }).then(reply);
    return true;
  }
  if (msg.type === 'reel') {
    bridge('/ext/reel', { domain: msg.domain, id: msg.id }).then(reply);
    return true;
  }
  if (msg.type === 'status') {
    bridge('/ext/status', { domain: msg.domain || '' }).then((d) => reply({ ...last, data: d }));
    return true;
  }
  return false;
});

// Keel extension background. It is a relay: content scripts report what they see, this
// forwards it to the Keel desktop app on 127.0.0.1, and hands back the verdict. All counting,
// limits and syncing live in the desktop app, so the browser can't disagree with the phone.
//
// The worker is woken by the content scripts' heartbeats, which is also what keeps it alive
// while you browse: MV3 suspends idle workers, and a timer here would not survive that.

const BRIDGE = 'http://127.0.0.1:47823';
const api = globalThis.browser ?? globalThis.chrome;
const BROWSER = navigator.userAgent.includes('Firefox') ? 'Firefox'
  : navigator.userAgent.includes('Edg/') ? 'Edge'
  : navigator.brave ? 'Brave' : 'Chrome';

let last = { ok: false, at: 0, data: null };

async function bridge(path, body) {
  try {
    const res = await fetch(BRIDGE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Keel-Ext': '1' },
      body: JSON.stringify({ browser: BROWSER, ...body }),
    });
    const data = await res.json();
    last = { ok: true, at: Date.now(), data };
    setBadge(data);
    return data;
  } catch {
    last = { ok: false, at: Date.now(), data: null };
    setBadge(null);
    // Keel isn't running: nothing is enforced here, and the popup says so.
    return null;
  }
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
  const text = r.remaining == null ? '' : String(r.remaining);
  a.setBadgeText({ text });
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
    if (msg.domain === undefined) {
      reply(last);
      return false;
    }
    bridge('/ext/status', { domain: msg.domain }).then((d) => reply({ ...last, data: d }));
    return true;
  }
  return false;
});

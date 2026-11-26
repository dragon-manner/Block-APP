const api = globalThis.browser ?? globalThis.chrome;
const out = document.getElementById('out');

function render(s) {
  out.textContent = '';
  const add = (tag, text, cls) => {
    const el = document.createElement(tag);
    el.textContent = text;
    if (cls) el.className = cls;
    out.appendChild(el);
    return el;
  };
  if (!s || !s.ok) {
    out.className = 'card';
    add('div', 'Keel desktop is not running', 'bad');
    add('div', 'Nothing is counted or blocked in this browser until it is. Start Keel on this computer.', 'muted');
    return;
  }
  const d = s.data || {};
  if (!d.signedIn) {
    out.className = 'card';
    add('div', 'Signed out', 'bad');
    add('div', 'Open Keel on this computer and sign in.', 'muted');
    return;
  }
  out.className = 'card';
  const r = d.reels || {};
  add('div', 'Reels today', 'muted');
  add('div', r.limit == null ? String(r.total ?? 0) : `${r.total ?? 0} / ${r.limit}`, 'big');
  add('div', r.allowed ? (r.remaining == null ? 'No reel limit' : `${r.remaining} left on every device`) : r.verdict?.message || 'Reels are closed', r.allowed ? 'muted' : 'bad');
  if (d.site && d.site.blocked) add('div', d.site.message, 'bad');
  if (d.focus) add('div', 'Focus session running', 'good');
}

api.tabs?.query({ active: true, currentWindow: true }, (tabs) => {
  let domain = '';
  try {
    domain = new URL(tabs?.[0]?.url || '').hostname;
  } catch {}
  api.runtime.sendMessage({ type: 'status', domain }, render);
});

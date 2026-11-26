// Keel content script: tells the desktop app which site is in front, counts short-form videos,
// and puts up the wall when the shared limits say so. Reels are recognised by the id in the
// URL where the site has one (YouTube Shorts, Instagram, Facebook), and by the playing video's
// permalink on TikTok's feed, where the URL never changes.

(() => {
  if (window.top !== window) return;
  const api = globalThis.browser ?? globalThis.chrome;
  const host = location.hostname.replace(/^www\./, '');

  const send = (msg) =>
    new Promise((resolve) => {
      try {
        api.runtime.sendMessage(msg, (r) => {
          void api.runtime.lastError;
          resolve(r || null);
        });
      } catch {
        resolve(null); // extension reloaded underneath this page
      }
    });

  // ---- Reel recognition -------------------------------------------------------------------

  function reelId() {
    const p = location.pathname;
    let m;
    if (/(^|\.)youtube\.com$/.test(host) && (m = p.match(/^\/shorts\/([\w-]{6,})/))) return 'yt:' + m[1];
    if (/(^|\.)instagram\.com$/.test(host) && (m = p.match(/^\/reels?\/([\w-]{5,})/))) return 'ig:' + m[1];
    if (/(^|\.)facebook\.com$/.test(host) && (m = p.match(/^\/reel\/(\d+)/))) return 'fb:' + m[1];
    if (/(^|\.)tiktok\.com$/.test(host)) {
      if ((m = p.match(/\/video\/(\d+)/))) return 'tt:' + m[1];
      return tiktokFeedId();
    }
    return null;
  }

  function onReelSurface() {
    const p = location.pathname;
    return (
      (/(^|\.)youtube\.com$/.test(host) && p.startsWith('/shorts')) ||
      (/(^|\.)instagram\.com$/.test(host) && /^\/reels?\//.test(p)) ||
      (/(^|\.)facebook\.com$/.test(host) && /^\/reel/.test(p)) ||
      /(^|\.)tiktok\.com$/.test(host)
    );
  }

  function tiktokFeedId() {
    let best = null;
    let bestArea = 0;
    for (const v of document.querySelectorAll('video')) {
      if (v.paused) continue;
      const r = v.getBoundingClientRect();
      const area = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0)) * Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
      if (area > bestArea) {
        bestArea = area;
        best = v;
      }
    }
    if (!best || bestArea < innerWidth * innerHeight * 0.15) return null;
    const box = best.closest('article, [data-e2e*="item"], [class*="DivItemContainer"]') || best.parentElement;
    const a = box && box.querySelector('a[href*="/video/"]');
    const m = a && a.getAttribute('href').match(/\/video\/(\d+)/);
    return m ? 'tt:' + m[1] : null;
  }

  // ---- The wall ---------------------------------------------------------------------------

  let wall = null;
  let pauser = 0;

  const titles = {
    focus: 'Back to focus',
    lockdown: 'Lockdown',
    quiet: 'Quiet hours',
    limit: 'Done for today',
    reels: 'Reel limit reached',
  };

  function showWall(verdict, kind) {
    const key = kind + verdict.reason + verdict.message;
    if (wall && wall.dataset.key === key) return;
    hideWall();
    wall = document.createElement('div');
    wall.dataset.key = key;
    const root = wall.attachShadow({ mode: 'closed' });
    const ember = verdict.reason === 'lockdown' || verdict.reason === 'limit' || verdict.reason === 'reels';
    root.innerHTML = `
      <style>
        :host { all: initial; }
        .w { position: fixed; inset: 0; z-index: 2147483647; background: #0E0F0C; color: #F1F2EC; display: grid; place-items: center; text-align: center; font-family: Manrope, system-ui, sans-serif; }
        .c { max-width: 520px; padding: 24px; }
        .m { width: 64px; height: 64px; border-radius: 20px; background: ${ember ? '#FF7A3D' : '#C6F432'}; color: ${ember ? '#0E0F0C' : '#141A00'}; display: grid; place-items: center; font: 800 34px system-ui; margin: 0 auto 24px; }
        h1 { font: 700 34px/1.15 system-ui, sans-serif; margin: 0 0 10px; }
        p { color: #9A9D93; font-size: 17px; line-height: 1.5; margin: 0 0 22px; }
        button { font: 700 15px system-ui; border: 0; border-radius: 14px; padding: 13px 22px; background: #23261F; color: #F1F2EC; cursor: pointer; }
        small { display: block; color: #666A60; margin-top: 22px; font-size: 13px; }
      </style>
      <div class="w"><div class="c">
        <div class="m">K</div>
        <h1></h1><p></p>
        <button type="button"></button>
        <small>Keel · the same limits as your phone and computer</small>
      </div></div>`;
    root.querySelector('h1').textContent = titles[verdict.reason] || 'Blocked';
    root.querySelector('p').textContent = verdict.message || '';
    const btn = root.querySelector('button');
    btn.textContent = kind === 'reels' ? 'Leave the reels' : 'Close this tab';
    btn.addEventListener('click', () => {
      if (kind === 'reels') location.href = location.origin + '/';
      else if (history.length > 1) history.back();
      else location.href = 'about:blank';
    });
    (document.documentElement || document).appendChild(wall);
    // Videos keep playing under an overlay, and sites restart them; keep them stopped.
    const stop = () => document.querySelectorAll('video, audio').forEach((m) => { try { m.pause(); m.muted = true; } catch {} });
    stop();
    pauser = setInterval(stop, 400);
  }

  function hideWall() {
    if (wall) wall.remove();
    wall = null;
    clearInterval(pauser);
  }

  function apply(v) {
    if (!v || !v.signedIn) return hideWall();
    if (v.site && v.site.blocked) return showWall(v.site, 'site');
    if (onReelSurface() && v.reels && !v.reels.allowed) return showWall(v.reels.verdict, 'reels');
    hideWall();
  }

  // ---- Loop -------------------------------------------------------------------------------

  let lastReel = null;
  let lastBeat = 0;

  async function tick() {
    const visible = document.visibilityState === 'visible';
    const id = visible ? reelId() : null;
    if (id && id !== lastReel) {
      lastReel = id;
      apply(await send({ type: 'reel', domain: host, id }));
      lastBeat = Date.now();
      return;
    }
    if (visible && Date.now() - lastBeat > BEAT_MS) {
      lastBeat = Date.now();
      apply(await send({ type: 'beat', domain: host, focused: document.hasFocus() }));
    }
  }

  const BEAT_MS = 2500;
  setInterval(tick, 600);
  document.addEventListener('visibilitychange', () => {
    lastBeat = 0;
    void tick();
  });
  addEventListener('focus', () => {
    lastBeat = 0;
    void tick();
  });
  void tick();
})();

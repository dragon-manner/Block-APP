import { render } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { boot, booted, connect, link, refresh, session, signOut, state, toast } from './api';
import { bridge } from './bridge';
import { Icon } from './components/ui';
import { cls, dur, reelTotal } from './lib';
import { Login } from './screens/Login';
import { Today } from './screens/Today';
import { FocusScreen } from './screens/Focus';
import { Tasks } from './screens/Tasks';
import { Insights } from './screens/Insights';
import { Grove } from './screens/Grove';
import { Rewards } from './screens/Rewards';
import { Limits } from './screens/Limits';
import { Devices } from './screens/Devices';
import { applyAccent } from './screens/accent';
import { go, route, type Route } from './nav';
import './styles.css';

const routes: { id: Route; label: string; icon: string; primary?: boolean }[] = [
  { id: 'today', label: 'Today', icon: 'today', primary: true },
  { id: 'focus', label: 'Focus', icon: 'focus', primary: true },
  { id: 'tasks', label: 'Tasks', icon: 'tasks', primary: true },
  { id: 'insights', label: 'Insights', icon: 'insights', primary: true },
  { id: 'grove', label: 'Grove', icon: 'grove' },
  { id: 'rewards', label: 'Rewards', icon: 'rewards' },
  { id: 'limits', label: 'Limits', icon: 'limits' },
  { id: 'devices', label: 'Devices', icon: 'devices' },
];


function Screen() {
  switch (route.value) {
    case 'focus': return <FocusScreen />;
    case 'tasks': return <Tasks />;
    case 'insights': return <Insights />;
    case 'grove': return <Grove />;
    case 'rewards': return <Rewards />;
    case 'limits': return <Limits />;
    case 'devices': return <Devices />;
    default: return <Today />;
  }
}

function Shell() {
  const [more, setMore] = useState(false);
  const s = state.value;
  const focusOn = !!s?.enforce.focus;
  const overdue = s?.enforce.lockdown.active;
  const badge: Partial<Record<Route, string>> = {};
  if (focusOn) badge.focus = '•';
  if (overdue) badge.tasks = '!';
  return (
    <div class={cls('shell', bridge.kind === 'android' && 'is-android')}>
      <aside class="side">
        <div class="brand">
          <span class="brand-mark">K</span>
          <span>Keel</span>
        </div>
        <nav>
          {routes.map((r) => (
            <button key={r.id} class={cls('side-link', route.value === r.id && 'on')} onClick={() => go(r.id)}>
              <Icon name={r.icon} />
              <span>{r.label}</span>
              {badge[r.id] && <em class="badge">{badge[r.id]}</em>}
            </button>
          ))}
        </nav>
        {s && (
          <div class="side-foot">
            <div class="side-stat"><Icon name="coin" size={16} /> {s.wallet.coins} coins</div>
            <div class="side-stat"><Icon name="flame" size={16} /> {s.wallet.streak}-day streak</div>
            <div class="side-stat"><Icon name="reel" size={16} /> {reelTotal(s.enforce)}{s.enforce.reels.limit !== null ? ` / ${s.enforce.reels.limit}` : ''} reels</div>
            <div class="side-stat"><Icon name="clock" size={16} /> {dur(s.today.seconds)} today</div>
            <div class={cls('link-dot', link.value)}>{link.value === 'online' ? 'Synced' : link.value === 'offline' ? 'Offline: retrying' : 'Connecting'}</div>
          </div>
        )}
      </aside>
      <main>
        {link.value === 'offline' && <div class="offline-bar">Offline. Limits still apply from the last sync.</div>}
        <Screen />
      </main>
      <nav class="tabbar">
        {routes.filter((r) => r.primary).map((r) => (
          <button key={r.id} class={cls('tab', route.value === r.id && 'on')} onClick={() => go(r.id)}>
            <Icon name={r.icon} size={22} />
            <span>{r.label}</span>
            {badge[r.id] && <em class="badge">{badge[r.id]}</em>}
          </button>
        ))}
        <button class={cls('tab', !routes.find((r) => r.id === route.value)?.primary && 'on')} onClick={() => setMore(true)}>
          <Icon name="more" size={22} />
          <span>More</span>
        </button>
      </nav>
      {more && (
        <div class="sheet-backdrop" onClick={() => setMore(false)}>
          <div class="sheet more-sheet" onClick={(e) => e.stopPropagation()}>
            <div class="more-grid">
              {routes.filter((r) => !r.primary).map((r) => (
                <button key={r.id} class="more-item" onClick={() => { setMore(false); go(r.id); }}>
                  <Icon name={r.icon} size={26} />
                  <span>{r.label}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** Signed in, but the first load failed. Limits still hold on the device; this only says so. */
function Unreachable() {
  const [busy, setBusy] = useState(false);
  if (link.value !== 'offline') return <div class="splash"><span class="brand-mark big pulse">K</span></div>;
  return (
    <div class="splash">
      <div class="unreachable">
        <span class="brand-mark big">K</span>
        <h2>Can't reach your Keel server</h2>
        <p class="muted">{session.value?.server.replace(/^https?:\/\//, '')} isn't answering. This device keeps enforcing the limits it last received.</p>
        <button class="btn btn-primary" disabled={busy} onClick={async () => { setBusy(true); await refresh(); connect(); setBusy(false); }}>{busy ? 'Trying…' : 'Try again'}</button>
        <button class="btn btn-quiet" onClick={() => signOut(false)}>Sign out</button>
      </div>
    </div>
  );
}

function App() {
  useEffect(() => {
    applyAccent();
    void boot();
    const h = () => {
      const r = location.hash.slice(1) as Route;
      if (routes.some((x) => x.id === r)) route.value = r;
    };
    addEventListener('hashchange', h);
    return () => removeEventListener('hashchange', h);
  }, []);
  const t = toast.value;
  return (
    <>
      {!booted.value ? <div class="splash"><span class="brand-mark big">K</span></div> : session.value && state.value ? <Shell /> : session.value ? <Unreachable /> : <Login />}
      {t && <div class={cls('toast', 'toast-' + t.tone)} role="status">{t.text}</div>}
    </>
  );
}

render(<App />, document.getElementById('app')!);

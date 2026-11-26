import { useEffect, useState } from 'preact/hooks';
import { act, api, state } from '../api';
import { haptic } from '../bridge';
import { Button, Icon, PageHead, Panel } from '../components/ui';
import { ago, cls } from '../lib';
import { ACCENTS, applyAccent, currentAccent } from './accent';

type Entry = { ts: number; coins: number; xp: number; reason: string; ref: string };

function describe(e: Entry) {
  let ref: any = {};
  try { ref = JSON.parse(e.ref); } catch {}
  switch (e.reason) {
    case 'focus': return `Focused ${ref.minutes} min`;
    case 'focus-goal': return 'Hit the daily focus goal';
    case 'task': return `${ref.onTime ? 'Finished' : 'Finished late'}: ${ref.title}`;
    case 'task-undo': return `Un-finished: ${ref.title}`;
    case 'overdue': return `Overdue: ${ref.title} (−${ref.reels} reels)`;
    case 'gave-up': return `Gave up a session (−${ref.reels} reels)`;
    case 'snooze': return `Moved a deadline: ${ref.title}`;
    case 'clean-day': return 'A day within every limit';
    case 'streak': return `${ref.days}-day streak bonus`;
    case 'welcome': return 'Welcome to Keel';
    default: return e.reason.startsWith('shop:') ? `Bought ${e.reason.slice(5)}` : e.reason;
  }
}

export function Rewards() {
  const s = state.value!;
  const w = s.wallet;
  const [ledger, setLedger] = useState<Entry[]>([]);
  const [accent, setAccent] = useState(currentAccent());
  useEffect(() => {
    api('GET', '/ledger').then((d) => setLedger(d.entries)).catch(() => {});
  }, [w.coins, w.xp]);

  const st = s.stats;
  const badges = [
    { name: 'First tree', got: st.sessions >= 1, hint: 'Finish a focus session' },
    { name: 'Copse', got: st.sessions >= 10, hint: '10 sessions' },
    { name: 'Forest floor', got: st.sessions >= 50, hint: '50 sessions' },
    { name: 'Ten hours', got: st.focusMinutes >= 600, hint: '600 focused minutes' },
    { name: 'Hundred hours', got: st.focusMinutes >= 6000, hint: '6,000 focused minutes' },
    { name: 'Closer', got: st.tasksDone >= 10, hint: 'Finish 10 tasks' },
    { name: 'Steady week', got: w.bestStreak >= 7, hint: '7-day streak' },
    { name: 'Steady month', got: w.bestStreak >= 30, hint: '30-day streak' },
    { name: 'No regrets', got: st.sessions >= 20 && st.withered === 0, hint: '20 sessions, none withered' },
  ];
  const progress = (w.xp - w.levelFloor) / Math.max(1, w.levelNext - w.levelFloor);

  return (
    <div class="page">
      <PageHead title="Rewards" sub="Coins come from focus, finished tasks and clean days. Penalties take them away." />
      <div class="wallet">
        <div class="wallet-coins"><Icon name="coin" size={30} /><strong>{w.coins}</strong><span class="muted">coins</span></div>
        <div class="wallet-level">
          <div class="row between"><span>Level {w.level}</span><span class="muted small">{w.xp} / {w.levelNext} XP</span></div>
          <div class="meter"><div class="meter-fill" style={{ width: `${progress * 100}%`, background: 'var(--lime)' }} /></div>
        </div>
        <div class="wallet-streak"><Icon name="flame" size={22} /><strong>{w.streak}</strong><span class="muted small">day streak · best {w.bestStreak}</span></div>
        <div class="wallet-streak"><Icon name="snow" size={22} /><strong>{w.freezes}</strong><span class="muted small">freezes</span></div>
      </div>

      <Panel title="Spend">
        <div class="shop">
          {s.shop.map((it) => (
            <div class={cls('shop-item', !!it.disabled && 'off')} key={it.id}>
              <div class="grow">
                <strong>{it.name}</strong>
                <p class="muted small">{it.desc}</p>
                <span class="muted small">{it.disabled || `${it.left} left today`}</span>
              </div>
              <Button small kind="ghost" icon="coin" disabled={!!it.disabled || w.coins < it.price} onClick={async () => { if (await act(() => api('POST', '/shop', { item: it.id }), `${it.name}: done`)) haptic('success'); }}>
                {it.price}
              </Button>
            </div>
          ))}
        </div>
        <p class="muted small mt-s">Nothing here can switch a limit off. Extra reels are capped at 15 a day, and focus sessions and lockdowns can't be bought out of.</p>
      </Panel>

      <div class="grid-2">
        <Panel title="Milestones">
          <div class="badges">
            {badges.map((b) => (
              <div class={cls('badge-card', b.got && 'got')} key={b.name} title={b.hint}>
                <Icon name={b.got ? 'rewards' : 'lock'} size={22} />
                <strong>{b.name}</strong>
                <span class="muted small">{b.hint}</span>
              </div>
            ))}
          </div>
        </Panel>
        <Panel title="Accent colour" action={<span class="muted small">Unlocks with level</span>}>
          <div class="accents">
            {ACCENTS.map((a) => {
              const locked = w.level < a.level;
              return (
                <button key={a.id} class={cls('accent', accent === a.id && 'on')} disabled={locked} onClick={() => { applyAccent(a.id); setAccent(a.id); haptic('tick'); }}>
                  <i style={{ background: locked ? 'var(--ink-700)' : a.main }} />
                  <span>{a.name}</span>
                  {locked && <span class="muted small">Lv {a.level}</span>}
                </button>
              );
            })}
          </div>
        </Panel>
      </div>

      <Panel title="History">
        <div class="ledger">
          {ledger.map((e, i) => (
            <div class="ledger-row" key={i}>
              <span class="grow">{describe(e)}</span>
              <span class="muted small">{ago(e.ts)}</span>
              <span class={cls('ledger-amt', e.coins > 0 ? 'good' : e.coins < 0 ? 'bad' : 'muted')}>{e.coins > 0 ? '+' : ''}{e.coins}</span>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  );
}

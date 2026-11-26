import { act, api, deviceNames, serverNow, state } from '../api';
import { go } from '../nav';
import { Bars, Button, Empty, Icon, Legend, Meter, PageHead, Panel, Ring, Split, useNow } from '../components/ui';
import { Tree } from '../components/Tree';
import { clock, currentPhase, deviceColor, dur, plural, quietActive, reelTotal, swatch, targetInfo, when } from '../lib';
import { TaskRow } from './Tasks';

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? 'Up late' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}

export function Today() {
  const s = state.value!;
  const e = s.enforce;
  const now = useNow(1000);
  const sn = serverNow();
  const total = reelTotal(e);
  const limit = e.reels.limit;
  const left = limit === null ? null : Math.max(0, limit - total);
  const phase = currentPhase(e, sn);
  const quiet = quietActive(e, new Date(now));
  const openTasks = s.tasks.filter((t) => t.status === 'open');
  const devices = Object.entries(s.today.byDevice).sort((a, b) => b[1] - a[1]);
  const hourBars = Array.from({ length: 24 }, (_, h) =>
    Object.entries(s.today.hours).map(([dev, hrs]) => [dev, hrs[h] || 0, deviceColor(dev)] as [string, number, string]),
  );
  const reels5 = s.shop.find((x) => x.id === 'reels5');

  return (
    <div class="page">
      <PageHead
        title={greeting()}
        sub={<>{new Date().toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' })}</>}
        right={
          <div class="chip-stats">
            <button class="stat-pill" onClick={() => go('rewards')}><Icon name="coin" size={16} />{s.wallet.coins}</button>
            <button class="stat-pill" onClick={() => go('rewards')}><Icon name="flame" size={16} />{s.wallet.streak}</button>
          </div>
        }
      />

      {e.lockdown.active && (
        <Panel tone="ember" class="consequence">
          <div class="consequence-row">
            <Icon name="lock" size={26} />
            <div>
              <h3>Lockdown: {plural(e.lockdown.tasks.length, 'overdue task')}</h3>
              <p>Social, video, games, news and shopping are locked on every device until you finish <b>{e.lockdown.tasks[0]}</b>{e.lockdown.tasks.length > 1 ? ' and the rest' : ''}, or work it off with 25 focused minutes.</p>
            </div>
          </div>
          <div class="row gap">
            <Button kind="ember" icon="focus" onClick={() => go('focus')}>Work it off</Button>
            <Button kind="ghost" onClick={() => go('tasks')}>See tasks</Button>
          </div>
        </Panel>
      )}

      {e.focus && (
        <Panel class="focus-mini" tone="lime">
          <button class="focus-mini-btn" onClick={() => go('focus')}>
            <Tree seed={e.focus.seed} minutes={e.focus.workMin * e.focus.cycles} growth={(sn - e.focus.started) / (e.focus.ends - e.focus.started)} size={64} />
            <div>
              <span class="eyebrow">{phase?.kind === 'break' ? 'Break' : 'Focusing'}{e.focus.label ? ` · ${e.focus.label}` : ''}</span>
              <strong class="figure">{clock((phase?.end ?? e.focus.ends) - sn)}</strong>
              <span class="muted small">{phase?.kind === 'break' ? 'Apps are open until the next round.' : 'Distractions are blocked on every device.'}</span>
            </div>
          </button>
        </Panel>
      )}

      <div class="grid-2">
        <Panel title="Screen time" action={<button class="link small" onClick={() => go('insights')}>Details</button>}>
          <div class="big-figure">{dur(s.today.seconds)}</div>
          <p class="muted small">across {plural(devices.length || 0, 'device')} today{s.today.pickups ? ` · ${s.today.pickups} phone pickups` : ''}</p>
          {devices.length > 0 && (
            <>
              <Split parts={devices.map(([id, v]) => ({ key: id, value: v, color: deviceColor(id), label: deviceNames.value[id] ?? 'Device' }))} />
              <Legend items={devices.map(([id, v]) => ({ color: deviceColor(id), label: <>{deviceNames.value[id] ?? 'Device'} <b>{dur(v)}</b></> }))} />
            </>
          )}
        </Panel>

        <Panel title="Reels" tone={left === 0 ? 'ember' : undefined}>
          <div class="reels-card">
            <Ring value={total} max={limit} color="var(--ember)">
              <strong class="figure">{total}</strong>
              <span class="muted small">{limit === null ? 'no limit' : `of ${limit}`}</span>
            </Ring>
            <div class="reels-side">
              {quiet && e.quiet.blockReels ? (
                <p><b>Reel-free hours.</b> Reels are closed until {e.quiet.to}.</p>
              ) : left === 0 ? (
                <p><b>Limit reached.</b> Reel viewers close on every device until midnight.</p>
              ) : left !== null ? (
                <p><b>{left} left</b> today, shared by your phone and browsers.</p>
              ) : (
                <p>No reel limit yet. <button class="link" onClick={() => go('limits')}>Set one</button></p>
              )}
              {(e.reels.bonus > 0 || e.reels.penalty > 0) && (
                <p class="muted small">
                  {e.reels.base} base{e.reels.bonus > 0 && <> · <span class="good">+{e.reels.bonus} bought</span></>}
                  {e.reels.penalty > 0 && <> · <span class="bad">−{e.reels.penalty} penalties</span></>}
                </p>
              )}
              <p class="muted small">This device {e.reels.mine} · others {e.reels.others}</p>
              {reels5 && limit !== null && (
                <Button small kind="ghost" icon="coin" disabled={!!reels5.disabled || s.wallet.coins < reels5.price} onClick={() => act(() => api('POST', '/shop', { item: 'reels5' }), '+5 reels today')}>
                  +5 for {reels5.price}
                </Button>
              )}
            </div>
          </div>
        </Panel>
      </div>

      {e.limits.length > 0 && (
        <Panel title="App limits" action={e.passUntil > now ? <span class="tag good">Breather · {clock(e.passUntil - now)}</span> : undefined}>
          <div class="limit-list">
            {e.limits.map((l) => {
              const used = l.others + l.mine;
              const t = targetInfo(l.target);
              return (
                <div class="limit-row" key={l.target}>
                  <div class="row between">
                    <span><i class="dot" style={{ background: swatch(l.target) }} />{t.name}</span>
                    <span class={used >= l.minutes * 60 ? 'bad' : 'muted'}>{dur(used)} / {dur(l.minutes * 60)}</span>
                  </div>
                  <Meter value={used} max={l.minutes * 60} />
                </div>
              );
            })}
          </div>
        </Panel>
      )}

      <div class="grid-2">
        <Panel title="Up next" action={<button class="link small" onClick={() => go('tasks')}>All tasks</button>}>
          {openTasks.length === 0 ? (
            <Empty icon="tasks" title="Nothing due">Add a task with a deadline. Finish on time for coins. Miss it and the consequences kick in.</Empty>
          ) : (
            <div class="task-list">{openTasks.slice(0, 4).map((t) => <TaskRow key={t.id} t={t} compact />)}</div>
          )}
          {!e.focus && (
            <Button icon="focus" class="mt" onClick={() => go('focus')}>Start a focus session</Button>
          )}
        </Panel>

        <Panel title="Most used today">
          {s.today.apps.length === 0 ? (
            <Empty icon="insights" title="No usage yet">Keel on your phone and computer reports here every half minute.</Empty>
          ) : (
            <div class="app-list">
              {s.today.apps.slice(0, 6).map((a) => {
                const t = targetInfo(a.target, a.name);
                return (
                  <div class="app-row" key={a.target}>
                    <span class="app-badge" style={{ background: swatch(a.target) }}>{t.name.charAt(0)}</span>
                    <div class="grow">
                      <div class="row between">
                        <span>{t.name}</span>
                        <span class="muted">{dur(a.seconds)}</span>
                      </div>
                      <Split parts={Object.entries(a.byDevice).map(([d, v]) => ({ key: d, value: v, color: deviceColor(d), label: deviceNames.value[d] ?? '' }))} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </Panel>
      </div>

      {s.today.seconds > 0 && (
        <Panel title="Hour by hour">
          <Bars bars={hourBars} labels={hourBars.map((_, h) => (h % 6 === 0 ? `${h}` : ''))} height={90} format={(v) => dur(v)} />
        </Panel>
      )}

      {s.pending && (
        <p class="muted small center">Looser limits you asked for start {when(new Date(s.pendingFrom + 'T00:00').getTime()).replace(' 12:00 AM', '')}.</p>
      )}
    </div>
  );
}

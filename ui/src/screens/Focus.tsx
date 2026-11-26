import { useEffect, useRef, useState } from 'preact/hooks';
import { act, api, deviceNames, refresh, say, serverNow, state } from '../api';
import { haptic } from '../bridge';
import { Button, Chips, HoldButton, Icon, PageHead, Panel, Stepper, Toggle, useNow } from '../components/ui';
import { SPECIES, Tree, speciesOf } from '../components/Tree';
import { categoryName, clock, cls, currentPhase, distractingCategories, plural } from '../lib';
import type { Focus } from '../types';

export function FocusScreen() {
  const s = state.value!;
  const f = s.enforce.focus;
  const [done, setDone] = useState<Focus | null>(null);
  const last = useRef<Focus | null>(null);

  // A session that disappears after its end time finished (the server pays it out); one that
  // disappears early was given up or cancelled, and the give-up path shows its own message.
  useEffect(() => {
    if (last.current && !f && serverNow() >= last.current.ends - 1500) setDone(last.current);
    last.current = f;
  }, [f?.id]);

  return (
    <div class="page">
      <PageHead title="Focus" sub="Start here and every device joins in. Distractions lock everywhere until the tree is grown." />
      {f ? <Active f={f} /> : done ? <Planted f={done} onAgain={() => setDone(null)} /> : <Setup />}
    </div>
  );
}

function Planted({ f, onAgain }: { f: Focus; onAgain: () => void }) {
  const mins = f.workMin * f.cycles;
  useEffect(() => haptic('success'), []);
  return (
    <Panel class="planted">
      <div class="planted-tree"><Tree seed={f.seed} minutes={mins} size={220} /></div>
      <h2>{SPECIES[speciesOf(f.seed)]} planted</h2>
      <p class="muted">{mins} focused minutes. <span class="good">+{mins} coins</span>, +{mins * 2} XP{mins >= 25 ? '. If anything was overdue, its lockdown is lifted.' : '.'}</p>
      <Button onClick={onAgain}>Another round</Button>
    </Panel>
  );
}

function Active({ f }: { f: Focus }) {
  const s = state.value!;
  useNow(500);
  const now = serverNow();
  const phase = currentPhase(s.enforce, now) ?? f.phases[f.phases.length - 1];
  const growth = (now - f.started) / (f.ends - f.started);
  const inGrace = now - f.started < 10_000;
  const p = s.rules.penalties;
  const works = f.phases.filter((x) => x.kind === 'work');
  const round = works.findIndex((x) => now < x.end) + 1 || works.length;
  const remaining = phase.end - now;

  useEffect(() => {
    if (remaining <= 0) {
      const t = setTimeout(() => void refresh(), 1200);
      return () => clearTimeout(t);
    }
  }, [remaining <= 0, phase.end]);

  const giveUp = async () => {
    const r = await act(() => api('POST', `/focus/${f.id}/giveup`));
    if (r?.status === 'abandoned') say(`Tree withered. −${p.giveUpCoins} coins, −${p.giveUpReels} reels today.`, 'bad');
    else if (r?.status === 'cancelled') say('Cancelled. No harm done.');
  };

  return (
    <Panel class={cls('focus-active', phase.kind === 'break' && 'is-break')}>
      <div class="focus-tree"><Tree seed={f.seed} minutes={f.workMin * f.cycles} growth={growth} size={260} /></div>
      <span class="eyebrow">{phase.kind === 'break' ? 'Break: apps are open' : f.mode === 'pomodoro' ? `Round ${round} of ${f.cycles}` : 'Deep focus'}</span>
      <div class="focus-clock">{clock(remaining)}</div>
      {f.label && <p class="focus-label">{f.label}</p>}
      {f.mode === 'pomodoro' && (
        <div class="phase-strip">
          {f.phases.map((x, i) => (
            <span key={i} class={cls('phase', x.kind, now >= x.end && 'past', now >= x.start && now < x.end && 'now')} style={{ flex: x.end - x.start }} />
          ))}
        </div>
      )}
      <p class="muted small">
        {f.strict ? `Blocking ${f.categories.map(categoryName).join(', ').toLowerCase()} and reels on every device.` : 'Soft mode: open a distraction and the tree withers.'}
        {' '}Started on {deviceNames.value[String(f.deviceId)] ?? 'a device'}.
      </p>
      <div class="focus-actions">
        {inGrace ? (
          <Button kind="ghost" onClick={giveUp}>Cancel (free for {Math.ceil((10_000 - (now - f.started)) / 1000)}s)</Button>
        ) : (
          <HoldButton onDone={giveUp}>Hold to give up · −{p.giveUpCoins} coins, −{p.giveUpReels} reels</HoldButton>
        )}
      </div>
    </Panel>
  );
}

function Setup() {
  const s = state.value!;
  const [mode, setMode] = useState<'focus' | 'pomodoro'>('focus');
  const [minutes, setMinutes] = useState(25);
  const [work, setWork] = useState(25);
  const [brk, setBrk] = useState(5);
  const [cycles, setCycles] = useState(4);
  const [strict, setStrict] = useState(true);
  const [taskId, setTaskId] = useState(0);
  const [cats, setCats] = useState<string[]>(s.rules.focusCategories.length ? s.rules.focusCategories : distractingCategories());
  const [busy, setBusy] = useState(false);
  const open = s.tasks.filter((t) => t.status === 'open');
  const total = mode === 'focus' ? minutes : work * cycles + brk * (cycles - 1);
  const workMin = mode === 'focus' ? minutes : work * cycles;
  const p = s.rules.penalties;
  const focusToday = s.today.focusMin;
  const goal = s.rules.dailyFocusGoal;

  const start = async () => {
    setBusy(true);
    await act(() => api('POST', '/focus', { mode, minutes, work, break: brk, cycles, strict, taskId, categories: cats }));
    haptic('success');
    setBusy(false);
  };

  return (
    <div class="grid-2 focus-setup">
      <Panel>
        <div class="setup-preview">
          <Tree seed={(minutes * 7919 + cycles * 104729 + work) % 2147483647} minutes={workMin} growth={0.3} size={120} />
          <div>
            <span class="eyebrow">{mode === 'focus' ? 'One deep session' : `${cycles} rounds of ${work}`}</span>
            <div class="big-figure">{total >= 60 ? `${Math.floor(total / 60)}h ${total % 60 ? `${total % 60}m` : ''}` : `${total}m`}</div>
            <span class="muted small">Plant a tree worth {workMin} coins</span>
          </div>
        </div>
        <Chips options={[{ value: 'focus', label: 'Deep focus' }, { value: 'pomodoro', label: 'Pomodoro' }]} value={mode} onChange={setMode} />
        {mode === 'focus' ? (
          <div class="mt">
            <Chips options={[15, 25, 45, 60, 90, 120].map((m) => ({ value: m, label: m >= 60 ? `${m / 60}h` : `${m}m` }))} value={minutes} onChange={setMinutes} />
            <div class="row between mt"><span class="muted">Custom</span><Stepper value={minutes} onChange={setMinutes} min={5} max={240} step={5} suffix="min" /></div>
          </div>
        ) : (
          <div class="pomo-grid mt">
            <div><span class="muted small">Work</span><Stepper value={work} onChange={setWork} min={10} max={90} step={5} suffix="m" /></div>
            <div><span class="muted small">Break</span><Stepper value={brk} onChange={setBrk} min={1} max={30} suffix="m" /></div>
            <div><span class="muted small">Rounds</span><Stepper value={cycles} onChange={setCycles} min={1} max={8} /></div>
          </div>
        )}
        <Button class="mt wide" icon="play" disabled={busy} onClick={start}>Start{taskId ? ' on task' : ''}</Button>
        <p class="muted small center mt-s">Free to cancel for 10 seconds. After that, giving up costs {p.giveUpCoins} coins and {plural(p.giveUpReels, 'reel')}, and the tree withers in your grove.</p>
      </Panel>

      <div class="stack">
        <Panel title="What gets blocked">
          <Toggle on={strict} onChange={setStrict} label="Lock distractions on every device" sub={strict ? 'Phone, laptop and browsers close these the moment they open.' : 'Soft mode: nothing is blocked, but opening one withers your tree.'} />
          <div class="mt">
            <Chips multi options={distractingCategories().map((c) => ({ value: c, label: categoryName(c) }))} value={cats} onChange={setCats} />
          </div>
          <p class="muted small mt-s">Reels are always closed during a work round.</p>
        </Panel>
        {open.length > 0 && (
          <Panel title="Work on a task">
            <div class="task-pick">
              <button class={cls('pick', taskId === 0 && 'on')} onClick={() => setTaskId(0)}>No task</button>
              {open.slice(0, 8).map((t) => (
                <button key={t.id} class={cls('pick', taskId === t.id && 'on', t.penalized && !t.debtCleared && 'overdue')} onClick={() => setTaskId(t.id)}>
                  {t.penalized && !t.debtCleared && <Icon name="lock" size={14} />} {t.title}
                </button>
              ))}
            </div>
            <p class="muted small mt-s">25+ minutes clears the oldest overdue lockdown, task or not.</p>
          </Panel>
        )}
        {goal > 0 && (
          <Panel title="Today's focus goal">
            <div class="row between"><span class="figure">{focusToday}<small> / {goal} min</small></span>{focusToday >= goal ? <span class="tag good">Done · +25 coins</span> : <span class="muted small">{goal - focusToday} to go</span>}</div>
            <div class="meter mt-s"><div class="meter-fill" style={{ width: `${Math.min(100, (focusToday / goal) * 100)}%`, background: 'var(--lime)' }} /></div>
          </Panel>
        )}
      </div>
    </div>
  );
}

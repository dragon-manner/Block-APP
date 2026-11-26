import { useEffect, useMemo, useState } from 'preact/hooks';
import { act, api, catalog, state } from '../api';
import { Button, Chips, Field, Icon, PageHead, Panel, Sheet, Stepper, Toggle } from '../components/ui';
import { categoryName, distractingCategories, dur, swatch, targetInfo } from '../lib';
import type { Rules } from '../types';

const clone = <T,>(x: T): T => JSON.parse(JSON.stringify(x));

export function Limits() {
  const s = state.value!;
  // Edit what tomorrow will be, so a queued loosening isn't silently overwritten by today's rules.
  const base = s.pending ?? s.rules;
  const [r, setR] = useState<Rules>(clone(base));
  const [picking, setPicking] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => setR(clone(s.pending ?? s.rules)), [JSON.stringify(s.pending ?? s.rules)]);
  const dirty = JSON.stringify(r) !== JSON.stringify(base);
  const set = (fn: (x: Rules) => void) => setR((cur) => {
    const n = clone(cur);
    fn(n);
    return n;
  });

  const save = async () => {
    setBusy(true);
    const res = await act(() => api('PUT', '/rules', r));
    setBusy(false);
    if (res) {
      if (res.pending) alertPending(res.pendingFrom);
    }
  };
  const [pendingNote, setPendingNote] = useState('');
  const alertPending = (from: string) => setPendingNote(from);

  const reelPresets = [0, 10, 20, 30, 50, 100];

  return (
    <div class="page">
      <PageHead title="Limits" sub="One set of rules, enforced on every device. Tighter changes apply now; looser ones wait until tomorrow." />
      {(pendingNote || s.pending) && (
        <Panel tone="lime">
          <p><b>Queued for {pendingNote || s.pendingFrom}.</b> Some of your changes loosen a limit, so today keeps the stricter version. Everything you asked for takes over at midnight.</p>
        </Panel>
      )}

      <Panel title="Daily reels" action={<span class="muted small">Shared by phone + browsers</span>}>
        <Chips
          options={[...reelPresets.map((n) => ({ value: n, label: n === 0 ? 'None' : String(n) })), { value: -1, label: 'Off' }]}
          value={r.reelLimit === null ? -1 : reelPresets.includes(r.reelLimit) ? r.reelLimit : -2}
          onChange={(v: number) => set((x) => { x.reelLimit = v < 0 ? null : v; })}
        />
        {r.reelLimit !== null && (
          <div class="row between mt"><span class="muted">Exact</span><Stepper value={r.reelLimit} onChange={(v) => set((x) => { x.reelLimit = v; })} min={0} max={500} step={5} suffix=" reels" /></div>
        )}
        <div class="mt">
          <Toggle on={r.taper.enabled} onChange={(v) => set((x) => { x.taper.enabled = v; if (!x.taper.weeklyDrop) x.taper.weeklyDrop = 20; if (!x.taper.goal) x.taper.goal = 10; })} label="Taper down each week" sub="The limit steps down automatically until it reaches your goal." />
          {r.taper.enabled && (
            <div class="pomo-grid mt-s">
              <div><span class="muted small">Weekly cut</span><Stepper value={r.taper.weeklyDrop} onChange={(v) => set((x) => { x.taper.weeklyDrop = v; })} min={5} max={60} step={5} suffix="%" /></div>
              <div><span class="muted small">Goal</span><Stepper value={r.taper.goal} onChange={(v) => set((x) => { x.taper.goal = v; })} min={0} max={200} step={5} /></div>
            </div>
          )}
        </div>
      </Panel>

      <Panel title="App & site time limits" action={<Button small kind="ghost" icon="plus" onClick={() => setPicking(true)}>Add</Button>}>
        {r.appLimits.length === 0 && <p class="muted">None yet. A limit on YouTube covers the app, youtube.com and any desktop client, all from one budget.</p>}
        <div class="limit-edit">
          {r.appLimits.map((l, i) => {
            const t = targetInfo(l.target);
            return (
              <div class="limit-edit-row" key={l.target}>
                <span class="app-badge" style={{ background: swatch(l.target) }}>{t.name.charAt(0)}</span>
                <span class="grow">{t.name}<span class="muted small block">{categoryName(t.category)}</span></span>
                <Stepper value={l.minutes} onChange={(v) => set((x) => { x.appLimits[i].minutes = v; })} min={0} max={720} step={5} suffix="m" />
                <button class="icon-btn subtle" aria-label="Remove" onClick={() => set((x) => { x.appLimits.splice(i, 1); })}><Icon name="trash" size={16} /></button>
              </div>
            );
          })}
        </div>
        <div class="muted small mt-s">0 minutes blocks it all day.</div>
      </Panel>

      <Panel title="Reel-free hours">
        <Toggle on={r.quiet.enabled} onChange={(v) => set((x) => { x.quiet.enabled = v; if (!x.quiet.from) { x.quiet.from = '23:00'; x.quiet.to = '07:00'; } })} label="Quiet hours every night" sub="Reels and the categories below close on every device." />
        {r.quiet.enabled && (
          <>
            <div class="row gap mt">
              <Field label="From"><input type="time" value={r.quiet.from} onInput={(e) => set((x) => { x.quiet.from = (e.target as HTMLInputElement).value; })} /></Field>
              <Field label="Until"><input type="time" value={r.quiet.to} onInput={(e) => set((x) => { x.quiet.to = (e.target as HTMLInputElement).value; })} /></Field>
            </div>
            <Toggle on={r.quiet.blockReels} onChange={(v) => set((x) => { x.quiet.blockReels = v; })} label="Close reels" />
            <Chips multi options={distractingCategories().map((c) => ({ value: c, label: categoryName(c) }))} value={r.quiet.categories} onChange={(v) => set((x) => { x.quiet.categories = v; })} />
          </>
        )}
      </Panel>

      <Panel title="Focus sessions block">
        <Chips multi options={distractingCategories().map((c) => ({ value: c, label: categoryName(c) }))} value={r.focusCategories} onChange={(v) => set((x) => { x.focusCategories = v; })} />
        <div class="row between mt">
          <span>Daily focus goal<span class="muted small block">Hitting it pays 25 coins</span></span>
          <Stepper value={r.dailyFocusGoal} onChange={(v) => set((x) => { x.dailyFocusGoal = v; })} min={0} max={600} step={25} suffix="m" />
        </div>
      </Panel>

      <Panel title="Consequences">
        <Toggle on={r.penalties.lockdown} onChange={(v) => set((x) => { x.penalties.lockdown = v; })} label="Lockdown on overdue tasks" sub="Distracting apps stay locked everywhere until the task is done or worked off." />
        <div class="penalty-grid">
          <div><span class="muted small">Overdue task: reels lost</span><Stepper value={r.penalties.overdueReels} onChange={(v) => set((x) => { x.penalties.overdueReels = v; })} max={50} /></div>
          <div><span class="muted small">Overdue task: coins lost</span><Stepper value={r.penalties.overdueCoins} onChange={(v) => set((x) => { x.penalties.overdueCoins = v; })} max={200} step={5} /></div>
          <div><span class="muted small">Give up focus: reels lost</span><Stepper value={r.penalties.giveUpReels} onChange={(v) => set((x) => { x.penalties.giveUpReels = v; })} max={50} /></div>
          <div><span class="muted small">Give up focus: coins lost</span><Stepper value={r.penalties.giveUpCoins} onChange={(v) => set((x) => { x.penalties.giveUpCoins = v; })} max={200} step={5} /></div>
        </div>
      </Panel>

      <div class="save-bar" hidden={!dirty}>
        <span>Unsaved changes</span>
        <Button kind="ghost" small onClick={() => setR(clone(base))}>Discard</Button>
        <Button small disabled={busy} onClick={save}>Save</Button>
      </div>
      <TargetPicker open={picking} onClose={() => setPicking(false)} taken={r.appLimits.map((l) => l.target)} onPick={(t) => { set((x) => { x.appLimits.push({ target: t, minutes: 30 }); }); setPicking(false); }} />
      <p class="muted small center">Used today: {r.appLimits.map((l) => `${targetInfo(l.target).name} ${dur((s.enforce.limits.find((x) => x.target === l.target)?.others ?? 0) + (s.enforce.limits.find((x) => x.target === l.target)?.mine ?? 0))}`).join(' · ') || 'nothing limited'}</p>
    </div>
  );
}

function TargetPicker({ open, onClose, onPick, taken }: { open: boolean; onClose: () => void; onPick: (t: string) => void; taken: string[] }) {
  const [q, setQ] = useState('');
  const seen = state.value!.today.apps.map((a) => a.target);
  const list = useMemo(() => {
    const ids = Array.from(new Set([...catalog.value.targets.map((t) => t.id), ...seen]));
    return ids
      .filter((id) => !taken.includes(id))
      .map((id) => targetInfo(id, state.value!.today.apps.find((a) => a.target === id)?.name))
      .filter((t) => !q || t.name.toLowerCase().includes(q.toLowerCase()) || t.id.includes(q.toLowerCase()));
  }, [q, taken.join(), seen.join()]);
  return (
    <Sheet open={open} onClose={onClose} title="Limit an app or site">
      <input placeholder="Search" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
      <div class="picker-list">
        {list.map((t) => (
          <button key={t.id} class="picker-row" onClick={() => onPick(t.id)}>
            <span class="app-badge" style={{ background: swatch(t.id) }}>{t.name.charAt(0)}</span>
            <span class="grow">{t.name}</span>
            <span class="muted small">{categoryName(t.category)}</span>
          </button>
        ))}
      </div>
    </Sheet>
  );
}

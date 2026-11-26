import { useEffect, useState } from 'preact/hooks';
import { api, state } from '../api';
import { go } from '../nav';
import { Button, Chips, Empty, PageHead, Panel, Sheet } from '../components/ui';
import { SPECIES, Tree, speciesOf } from '../components/Tree';
import { dur } from '../lib';
import type { SessionRow } from '../types';

export function Grove() {
  const [days, setDays] = useState(30);
  const [list, setList] = useState<SessionRow[] | null>(null);
  const [pick, setPick] = useState<SessionRow | null>(null);
  const s = state.value!;
  useEffect(() => {
    let live = true;
    api('GET', `/grove?days=${days}`).then((d) => live && setList(d.sessions)).catch(() => {});
    return () => { live = false; };
  }, [days, s.stats.sessions, s.stats.withered]);

  const grown = list?.filter((x) => x.status === 'completed') ?? [];
  const withered = list?.filter((x) => x.status === 'abandoned') ?? [];
  const byDay = new Map<string, SessionRow[]>();
  for (const x of list ?? []) {
    const k = new Date(x.started).toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
    byDay.set(k, [...(byDay.get(k) ?? []), x]);
  }
  const species = new Set(grown.map((x) => speciesOf(x.seed)));

  return (
    <div class="page">
      <PageHead title="Grove" sub="Every finished session plants a tree. Every one you give up on stays here too." right={<Chips options={[{ value: 7, label: 'Week' }, { value: 30, label: 'Month' }, { value: 365, label: 'Year' }]} value={days} onChange={setDays} />} />
      <div class="kpis">
        <div class="kpi"><span class="kpi-label">Trees grown</span><strong class="lime">{grown.length}</strong></div>
        <div class="kpi"><span class="kpi-label">Withered</span><strong class="muted">{withered.length}</strong></div>
        <div class="kpi"><span class="kpi-label">Focused</span><strong>{dur(grown.reduce((t, x) => t + x.minutes, 0) * 60)}</strong></div>
        <div class="kpi"><span class="kpi-label">Species found</span><strong>{species.size}<small>/{SPECIES.length}</small></strong></div>
      </div>
      {list && list.length === 0 && (
        <Panel><Empty icon="grove" title="An empty field">Your first focus session plants the first tree.<br /><Button class="mt" onClick={() => go('focus')}>Plant one</Button></Empty></Panel>
      )}
      <Panel class="grove-field">
        {[...byDay.entries()].reverse().map(([day, xs]) => (
          <div class="grove-day" key={day}>
            <span class="grove-date">{day}</span>
            <div class="grove-trees">
              {xs.map((x) => (
                <button key={x.id} class="grove-tree" onClick={() => setPick(x)} title={x.label || SPECIES[speciesOf(x.seed)]}>
                  <Tree seed={x.seed} minutes={x.minutes} withered={x.status === 'abandoned'} size={58 + Math.min(40, x.minutes / 3)} />
                </button>
              ))}
            </div>
          </div>
        ))}
      </Panel>
      <Sheet open={!!pick} onClose={() => setPick(null)} title={pick ? (pick.status === 'abandoned' ? 'Withered' : SPECIES[speciesOf(pick.seed)]) : ''}>
        {pick && (
          <div class="center stack">
            <Tree seed={pick.seed} minutes={pick.minutes} withered={pick.status === 'abandoned'} size={200} />
            <strong>{pick.label || (pick.mode === 'pomodoro' ? 'Pomodoro set' : 'Deep focus')}</strong>
            <p class="muted">
              {new Date(pick.started).toLocaleString([], { weekday: 'long', hour: 'numeric', minute: '2-digit' })} ·{' '}
              {pick.status === 'abandoned' ? `gave up (${pick.reason})` : `${pick.minutes} focused minutes`}
            </p>
          </div>
        )}
      </Sheet>
    </div>
  );
}

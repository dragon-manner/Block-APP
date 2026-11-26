import { useEffect, useState } from 'preact/hooks';
import { api, deviceNames, state } from '../api';
import { Bars, Chips, Empty, Icon, Legend, PageHead, Panel, Split } from '../components/ui';
import { Tree } from '../components/Tree';
import { addDays, categoryName, cls, dateLabel, deviceColor, dur, swatch, targetInfo } from '../lib';
import type { DaySummary, SessionRow, UsageRow } from '../types';

type Range = 'day' | 'week' | 'month';

const catColors: Record<string, string> = {
  social: '#FF7A3D', video: '#FF8DB4', games: '#BFA3FF', news: '#FFD15C', shopping: '#7CC4FF',
  messaging: '#8FB3C9', work: '#C6F432', other: '#666A60',
};

export function Insights() {
  const [range, setRange] = useState<Range>('day');
  const [date, setDate] = useState(state.value!.enforce.date);
  return (
    <div class="page">
      <PageHead title="Insights" sub="Every device in one picture." right={<Chips options={[{ value: 'day', label: 'Day' }, { value: 'week', label: 'Week' }, { value: 'month', label: 'Month' }]} value={range} onChange={setRange} />} />
      {range === 'day' ? <DayView date={date} setDate={setDate} /> : <PeriodView days={range === 'week' ? 7 : 30} onPickDay={(d) => { setDate(d); setRange('day'); }} />}
    </div>
  );
}

function AppTable({ apps, total }: { apps: UsageRow[]; total: number }) {
  if (apps.length === 0) return <Empty icon="insights" title="Nothing recorded" />;
  return (
    <div class="app-table">
      {apps.map((a) => {
        const t = targetInfo(a.target, a.name);
        return (
          <div class="app-row" key={a.target}>
            <span class="app-badge" style={{ background: swatch(a.target) }}>{t.name.charAt(0)}</span>
            <div class="grow">
              <div class="row between">
                <span>{t.name} <span class="muted small">{categoryName(t.category)}</span></span>
                <span>{dur(a.seconds)} <span class="muted small">{total ? Math.round((a.seconds / total) * 100) : 0}%</span></span>
              </div>
              <Split parts={Object.entries(a.byDevice).map(([d, v]) => ({ key: d, value: v, color: deviceColor(d), label: deviceNames.value[d] ?? '' }))} />
              {(a.opens > 0 || a.reels > 0) && (
                <div class="muted small">{a.opens > 0 && `${a.opens} opens`}{a.opens > 0 && a.reels > 0 && ' · '}{a.reels > 0 && `${a.reels} reels`}</div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function Categories({ apps }: { apps: UsageRow[] }) {
  const by: Record<string, number> = {};
  for (const a of apps) {
    const c = targetInfo(a.target, a.name).category;
    by[c] = (by[c] || 0) + a.seconds;
  }
  const parts = Object.entries(by).sort((a, b) => b[1] - a[1]);
  if (!parts.length) return null;
  return (
    <>
      <Split parts={parts.map(([c, v]) => ({ key: c, value: v, color: catColors[c] ?? '#666A60', label: categoryName(c) }))} />
      <Legend items={parts.map(([c, v]) => ({ color: catColors[c] ?? '#666A60', label: <>{categoryName(c)} <b>{dur(v)}</b></> }))} />
    </>
  );
}

function DayView({ date, setDate }: { date: string; setDate: (d: string) => void }) {
  const [data, setData] = useState<{ day: DaySummary; sessions: SessionRow[] } | null>(null);
  const today = state.value!.enforce.date;
  useEffect(() => {
    let live = true;
    setData(null);
    api('GET', `/day?date=${date}`).then((d) => live && setData(d)).catch(() => {});
    return () => { live = false; };
  }, [date, date === today ? state.value : null]);

  const d = data?.day;
  const hourBars = d ? Array.from({ length: 24 }, (_, h) => Object.entries(d.hours).map(([dev, hrs]) => [dev, hrs[h] || 0, deviceColor(dev)] as [string, number, string])) : [];
  const devices = d ? Object.entries(d.byDevice).sort((a, b) => b[1] - a[1]) : [];

  return (
    <>
      <div class="date-nav">
        <button class="icon-btn" onClick={() => setDate(addDays(date, -1))} aria-label="Previous day"><Icon name="chevL" /></button>
        <strong>{date === today ? 'Today' : dateLabel(date, 'long')}</strong>
        <button class="icon-btn" disabled={date >= today} onClick={() => setDate(addDays(date, 1))} aria-label="Next day"><Icon name="chevR" /></button>
      </div>
      {!d ? <div class="loading" /> : (
        <>
          <div class="kpis">
            <div class="kpi"><span class="kpi-label">Screen time</span><strong>{dur(d.seconds)}</strong></div>
            <div class="kpi"><span class="kpi-label">Reels</span><strong class="ember">{d.reels}</strong>{d.reelLimit !== null && <span class="muted small">limit {d.reelLimit}</span>}</div>
            <div class="kpi"><span class="kpi-label">Focus</span><strong>{dur(d.focusMin * 60)}</strong></div>
            <div class="kpi"><span class="kpi-label">Pickups</span><strong>{d.pickups}</strong></div>
          </div>
          {devices.length > 0 && (
            <Panel title="By device">
              <Split parts={devices.map(([id, v]) => ({ key: id, value: v, color: deviceColor(id), label: deviceNames.value[id] ?? 'Device' }))} />
              <Legend items={devices.map(([id, v]) => ({ color: deviceColor(id), label: <>{deviceNames.value[id] ?? 'Removed device'} <b>{dur(v)}</b></> }))} />
            </Panel>
          )}
          <Panel title="Hour by hour">
            <Bars bars={hourBars} labels={hourBars.map((_, h) => (h % 3 === 0 ? (h === 0 ? '12a' : h < 12 ? `${h}a` : h === 12 ? '12p' : `${h - 12}p`) : ''))} height={120} format={(v) => dur(v)} />
          </Panel>
          <div class="grid-2">
            <Panel title="Apps & sites"><AppTable apps={d.apps} total={d.seconds} /></Panel>
            <div class="stack">
              <Panel title="Categories"><Categories apps={d.apps} />{!d.apps.length && <p class="muted">No data.</p>}</Panel>
              <Panel title="Focus sessions">
                {data!.sessions.length === 0 ? <p class="muted">None this day.</p> : (
                  <div class="session-list">
                    {data!.sessions.map((x) => (
                      <div class="session" key={x.id}>
                        <Tree seed={x.seed} minutes={x.minutes} withered={x.status === 'abandoned'} size={44} />
                        <div class="grow">
                          <div>{x.label || (x.mode === 'pomodoro' ? 'Pomodoro' : 'Deep focus')}</div>
                          <div class="muted small">{new Date(x.started).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })} · {x.status === 'abandoned' ? `withered (${x.reason})` : `${x.minutes} min`}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </Panel>
            </div>
          </div>
        </>
      )}
    </>
  );
}

function PeriodView({ days, onPickDay }: { days: number; onPickDay: (d: string) => void }) {
  const [data, setData] = useState<{ days: DaySummary[]; apps: UsageRow[] } | null>(null);
  const [prev, setPrev] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    setData(null);
    api('GET', `/history?days=${days}`).then((d) => live && setData(d)).catch(() => {});
    api('GET', `/history?days=${days * 2}`).then((d) => {
      if (!live) return;
      const older = (d.days as DaySummary[]).slice(0, days);
      setPrev(older.reduce((s, x) => s + x.seconds, 0));
    }).catch(() => {});
    return () => { live = false; };
  }, [days]);
  if (!data) return <div class="loading" />;
  const list = data.days;
  const total = list.reduce((s, d) => s + d.seconds, 0);
  const tracked = list.filter((d) => d.seconds > 0).length || 1;
  const reels = list.reduce((s, d) => s + d.reels, 0);
  const focus = list.reduce((s, d) => s + d.focusMin, 0);
  const change = prev ? Math.round(((total - prev) / prev) * 100) : null;
  const label = (d: DaySummary) => (days === 7 ? dateLabel(d.date).split(' ')[0] : d.date.slice(8) === '01' || list.indexOf(d) % 5 === 0 ? String(Number(d.date.slice(8))) : '');
  const screenBars = list.map((d) => Object.entries(d.byDevice).map(([dev, v]) => [dev, v, deviceColor(dev)] as [string, number, string]));
  const reelBars = list.map((d) => [['r', d.reels, d.reelLimit !== null && d.reels > d.reelLimit ? 'var(--ember)' : 'var(--ember-dim)']] as [string, number, string][]);
  const focusBars = list.map((d) => [['f', d.focusMin, 'var(--lime)']] as [string, number, string][]);
  const devIds = Array.from(new Set(list.flatMap((d) => Object.keys(d.byDevice))));
  const resultClass: Record<string, string> = { clean: 'clean', frozen: 'frozen', broken: 'broken', free: 'free', nodata: '' };

  return (
    <>
      <div class="kpis">
        <div class="kpi"><span class="kpi-label">Daily average</span><strong>{dur(total / tracked)}</strong>{change !== null && <span class={cls('small', change > 0 ? 'bad' : 'good')}>{change > 0 ? '▲' : '▼'} {Math.abs(change)}% vs previous</span>}</div>
        <div class="kpi"><span class="kpi-label">Total</span><strong>{dur(total)}</strong></div>
        <div class="kpi"><span class="kpi-label">Reels</span><strong class="ember">{reels}</strong><span class="muted small">{Math.round(reels / tracked)}/day</span></div>
        <div class="kpi"><span class="kpi-label">Focused</span><strong>{dur(focus * 60)}</strong></div>
      </div>
      <Panel title="Screen time per day">
        <Bars bars={screenBars} labels={list.map(label)} onPick={(i) => onPickDay(list[i].date)} format={(v) => dur(v)} />
        <Legend items={devIds.map((id) => ({ color: deviceColor(id), label: deviceNames.value[id] ?? 'Removed device' }))} />
      </Panel>
      <div class="grid-2">
        <Panel title="Reels per day"><Bars bars={reelBars} labels={list.map(label)} height={100} onPick={(i) => onPickDay(list[i].date)} format={(v) => `${v} reels`} goal={state.value!.enforce.reels.base ?? undefined} /></Panel>
        <Panel title="Focus minutes"><Bars bars={focusBars} labels={list.map(label)} height={100} onPick={(i) => onPickDay(list[i].date)} format={(v) => `${v} min`} goal={state.value!.rules.dailyFocusGoal || undefined} /></Panel>
      </div>
      <div class="grid-2">
        <Panel title="Top apps & sites"><AppTable apps={data.apps.slice(0, 12)} total={total} /></Panel>
        <div class="stack">
          <Panel title="Categories"><Categories apps={data.apps} /></Panel>
          <Panel title="Streak days">
            <div class="cal">
              {list.map((d) => (
                <button key={d.date} class={cls('cal-day', resultClass[d.result])} onClick={() => onPickDay(d.date)} title={`${d.date}: ${d.result || 'today'}`}>
                  {Number(d.date.slice(8))}
                </button>
              ))}
            </div>
            <Legend items={[{ color: 'var(--lime)', label: 'Within limits' }, { color: 'var(--steel)', label: 'Freeze used' }, { color: 'var(--ember)', label: 'Over a limit' }]} />
          </Panel>
        </div>
      </div>
    </>
  );
}

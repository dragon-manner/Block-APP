import { useState } from 'preact/hooks';
import { act, api, say, state } from '../api';
import { haptic } from '../bridge';
import { go } from '../nav';
import { Button, Chips, Empty, Field, Icon, PageHead, Panel, Sheet, Stepper } from '../components/ui';
import { cls, plural, when } from '../lib';
import type { Task } from '../types';

function at(daysAhead: number, h: number, m = 0) {
  const d = new Date();
  d.setDate(d.getDate() + daysAhead);
  d.setHours(h, m, 0, 0);
  return d.getTime();
}

function quickDues() {
  const now = Date.now();
  const opts = [
    { label: 'In 1 hour', value: Math.ceil((now + 3600_000) / 300_000) * 300_000 },
    { label: 'Today 6 PM', value: at(0, 18) },
    { label: 'Tonight 11 PM', value: at(0, 23) },
    { label: 'Tomorrow 9 AM', value: at(1, 9) },
    { label: 'Tomorrow 6 PM', value: at(1, 18) },
  ].filter((o) => o.value > now + 5 * 60_000);
  return [{ label: 'No deadline', value: 0 }, ...opts];
}

function toLocalInput(ms: number) {
  const d = new Date(ms - new Date().getTimezoneOffset() * 60000);
  return d.toISOString().slice(0, 16);
}

export function TaskRow({ t, compact }: { t: Task; compact?: boolean }) {
  const overdue = t.status === 'open' && t.due > 0 && t.due < Date.now();
  const debt = t.penalized && !t.debtCleared && t.status === 'open';
  const toggle = async () => {
    haptic(t.status === 'open' ? 'success' : 'tap');
    const r = await act(() => api('PATCH', `/tasks/${t.id}`, { status: t.status === 'open' ? 'done' : 'open' }));
    if (r?.coins) say(r.coins > 0 ? `Done. +${r.coins} coins, +${r.xp} XP` : `${r.coins} coins`, r.coins > 0 ? 'good' : 'plain');
  };
  return (
    <div class={cls('task', t.status === 'done' && 'done', overdue && 'overdue')}>
      <button class="check" onClick={toggle} aria-label={t.status === 'done' ? 'Mark not done' : 'Mark done'}>
        {t.status === 'done' && <Icon name="check" size={16} />}
      </button>
      <div class="grow">
        <div class="task-title">{t.title}</div>
        <div class="task-meta">
          {t.due > 0 && <span class={overdue ? 'bad' : ''}>{overdue ? 'Overdue · ' : ''}{when(t.due)}</span>}
          {!compact && <span>{plural(t.est, 'pomodoro')}</span>}
          {t.focusMin > 0 && <span>{t.focusMin}m focused</span>}
          {debt && <span class="tag bad"><Icon name="lock" size={12} /> lockdown</span>}
          {t.penalized && t.debtCleared && t.status === 'open' && <span class="tag">worked off</span>}
        </div>
      </div>
      {!compact && t.status === 'open' && (
        <button class="icon-btn" onClick={() => go('focus')} aria-label="Focus on this"><Icon name="focus" /></button>
      )}
    </div>
  );
}

export function Tasks() {
  const s = state.value!;
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Task | null>(null);
  const now = Date.now();
  const open = s.tasks.filter((t) => t.status === 'open');
  const overdue = open.filter((t) => t.due > 0 && t.due < now);
  const endOfToday = at(0, 23, 59);
  const today = open.filter((t) => t.due >= now && t.due <= endOfToday);
  const later = open.filter((t) => t.due > endOfToday);
  const someday = open.filter((t) => t.due === 0);
  const done = s.tasks.filter((t) => t.status === 'done');
  const p = s.rules.penalties;

  const group = (title: string, list: Task[], tone?: string) =>
    list.length > 0 && (
      <Panel title={`${title} · ${list.length}`} tone={tone as any}>
        <div class="task-list">
          {list.map((t) => (
            <div key={t.id} class="task-wrap" onDblClick={() => setEditing(t)}>
              <TaskRow t={t} />
              {t.status === 'open' && <button class="icon-btn subtle" onClick={() => setEditing(t)} aria-label="Edit"><Icon name="edit" size={16} /></button>}
            </div>
          ))}
        </div>
      </Panel>
    );

  return (
    <div class="page">
      <PageHead title="Tasks" sub="Deadlines with teeth. Finish on time for coins; miss one and it costs you." right={<Button icon="plus" onClick={() => setAdding(true)}>Add task</Button>} />
      {open.length === 0 && done.length === 0 && (
        <Panel><Empty icon="tasks" title="No tasks yet">Give each task a deadline. On time pays 20 coins plus 5 per pomodoro.</Empty></Panel>
      )}
      {group('Overdue', overdue, 'ember')}
      {group('Today', today)}
      {group('Coming up', later)}
      {group('Whenever', someday)}
      {group('Done recently', done)}
      <Panel title="How consequences work">
        <ul class="rules-list">
          <li><b>Overdue:</b> −{p.overdueCoins} coins and −{plural(p.overdueReels, 'reel')} off today's allowance, once per task.</li>
          {p.lockdown && <li><b>Lockdown:</b> while a task is overdue, distracting apps and sites are locked on every device. Finish it, or do 25 focused minutes to work it off.</li>}
          <li><b>Moving a deadline later</b> costs 10 coins (free for 10 minutes after you add it). An overdue task can't be moved or deleted.</li>
          <li><b>On time:</b> +20 coins and +5 per pomodoro estimated. Late still pays 5.</li>
        </ul>
        <button class="link small" onClick={() => go('limits')}>Change consequences</button>
      </Panel>
      <TaskSheet open={adding} onClose={() => setAdding(false)} />
      {editing && <TaskSheet open task={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function TaskSheet({ open, onClose, task }: { open: boolean; onClose: () => void; task?: Task }) {
  const [title, setTitle] = useState(task?.title ?? '');
  const [notes, setNotes] = useState(task?.notes ?? '');
  const [due, setDue] = useState<number>(task ? task.due : at(0, 18) > Date.now() + 3600_000 ? at(0, 18) : at(1, 9));
  const [est, setEst] = useState(task?.est ?? 1);
  const [custom, setCustom] = useState(false);
  const qd = quickDues();
  const penalized = task?.penalized;

  const save = async (e: Event) => {
    e.preventDefault();
    if (!title.trim()) return;
    const body = { title, notes, due, est };
    const r = task
      ? await act(() => api('PATCH', `/tasks/${task.id}`, penalized ? { title, notes, est } : body))
      : await act(() => api('POST', '/tasks', body), 'Task added');
    if (r) {
      if (r.coins) say(`Deadline moved: ${r.coins} coins`, 'bad');
      onClose();
    }
  };
  const del = async () => {
    if (task && (await act(() => api('DELETE', `/tasks/${task.id}`), 'Deleted'))) onClose();
  };

  return (
    <Sheet open={open} onClose={onClose} title={task ? 'Edit task' : 'New task'}>
      <form class="stack" onSubmit={save}>
        <Field label="What needs doing">
          <input autoFocus value={title} maxLength={200} onInput={(e) => setTitle((e.target as HTMLInputElement).value)} placeholder="Finish the report draft" />
        </Field>
        {!penalized && (
          <Field label="Deadline">
            <Chips options={qd.map((q) => ({ value: q.value, label: q.label }))} value={qd.some((q) => q.value === due) && !custom ? due : -1} onChange={(v: number) => { setCustom(false); setDue(v); }} />
            <button type="button" class="link small" onClick={() => setCustom(true)}>{custom || !qd.some((q) => q.value === due) ? 'Custom:' : 'Pick a time…'}</button>
            {(custom || !qd.some((q) => q.value === due)) && (
              <input type="datetime-local" value={due ? toLocalInput(due) : ''} min={toLocalInput(Date.now())} onInput={(e) => setDue(new Date((e.target as HTMLInputElement).value).getTime() || 0)} />
            )}
          </Field>
        )}
        <div class="row between">
          <span>Estimate</span>
          <Stepper value={est} onChange={setEst} min={1} max={20} suffix={est === 1 ? ' pomodoro' : ' pomodoros'} />
        </div>
        <Field label="Notes">
          <textarea rows={3} value={notes} onInput={(e) => setNotes((e.target as HTMLTextAreaElement).value)} />
        </Field>
        {task && task.due > 0 && due > task.due && Date.now() - task.created > 600_000 && <p class="bad small">Moving the deadline later costs 10 coins.</p>}
        <div class="row gap">
          <Button type="submit" class="grow">{task ? 'Save' : 'Add task'}</Button>
          {task && <Button kind="danger" icon="trash" onClick={del} />}
        </div>
      </form>
    </Sheet>
  );
}

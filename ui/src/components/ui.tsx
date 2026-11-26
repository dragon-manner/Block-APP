import type { ComponentChildren, JSX } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { haptic } from '../bridge';
import { cls } from '../lib';

// ---- Icons (stroke, 24 grid) ---------------------------------------------------------------

const paths: Record<string, string> = {
  today: 'M4 11.5 12 5l8 6.5V20a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z',
  focus: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zm0-4a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0-4a1 1 0 1 0 0-2 1 1 0 0 0 0 2z',
  tasks: 'M9 6h11M9 12h11M9 18h11M4 6l1 1 2-2M4 12l1 1 2-2M4 18l1 1 2-2',
  insights: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  grove: 'M12 22v-6m0 0c-4 0-7-2.5-7-6.5C5 6 8 3 12 3s7 3 7 6.5c0 4-3 6.5-7 6.5zm0-5-3-3m3 1 3-3',
  rewards: 'M12 15a6 6 0 1 0 0-12 6 6 0 0 0 0 12zm-4 0-1 7 5-3 5 3-1-7',
  limits: 'M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z M9 12l2 2 4-4',
  devices: 'M3 6a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v8H3zM1 17h14M19 9h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  plus: 'M12 5v14M5 12h14',
  check: 'M5 12.5 10 17 19 7',
  close: 'M6 6l12 12M18 6 6 18',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13',
  chevL: 'M15 5l-7 7 7 7',
  chevR: 'M9 5l7 7-7 7',
  flame: 'M12 22c4 0 7-3 7-7 0-5-5-7-5-12-3 2-4 5-4 7-1-1-2-2-2-4-2 2-3 5-3 9 0 4 3 7 7 7z',
  coin: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zm0-13v8m-3-5.5c0-1 1.3-1.8 3-1.8s3 .8 3 1.8-1.3 1.6-3 1.6-3 .7-3 1.7 1.3 1.8 3 1.8 3-.8 3-1.8',
  lock: 'M6 11h12v10H6zM8 11V8a4 4 0 0 1 8 0v3',
  play: 'M7 4v16l13-8z',
  reel: 'M4 4h16v16H4zM4 9h16M9 4l2 5M15 4l2 5M10 13v5l4-2.5z',
  bolt: 'M13 2 4 14h7l-1 8 9-12h-7z',
  logout: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11',
  edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  phone: 'M8 2h8a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM11 18h2',
  laptop: 'M4 5h16v10H4zM2 19h20',
  globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.5 3.5 6 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-6-3.5-9s1-6.5 3.5-9z',
  snow: 'M12 2v20M4 6l16 12M20 6 4 18',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zm0-14v5l3 2',
};

export function Icon({ name, size = 20, class: c }: { name: string; size?: number; class?: string }) {
  return (
    <svg class={cls('icon', c)} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d={paths[name] ?? ''} />
    </svg>
  );
}

// ---- Layout --------------------------------------------------------------------------------

export function Panel({ children, class: c, title, action, tone }: { children: ComponentChildren; class?: string; title?: string; action?: ComponentChildren; tone?: 'ember' | 'lime' }) {
  return (
    <section class={cls('panel', tone && 'panel-' + tone, c)}>
      {(title || action) && (
        <header class="panel-head">
          {title && <h3>{title}</h3>}
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHead({ title, sub, right }: { title: string; sub?: ComponentChildren; right?: ComponentChildren }) {
  return (
    <header class="page-head">
      <div>
        <h1>{title}</h1>
        {sub && <p class="muted">{sub}</p>}
      </div>
      {right}
    </header>
  );
}

export function Button({ children, onClick, kind = 'primary', small, disabled, type = 'button', class: c, icon }: {
  children?: ComponentChildren; onClick?: (e: MouseEvent) => void; kind?: 'primary' | 'ghost' | 'quiet' | 'danger' | 'ember';
  small?: boolean; disabled?: boolean; type?: 'button' | 'submit'; class?: string; icon?: string;
}) {
  return (
    <button
      type={type}
      class={cls('btn', 'btn-' + kind, small && 'btn-small', c)}
      disabled={disabled}
      onClick={(e) => {
        haptic('tap');
        onClick?.(e as unknown as MouseEvent);
      }}
    >
      {icon && <Icon name={icon} size={small ? 16 : 18} />}
      {children}
    </button>
  );
}

/** Press and hold to confirm: for the actions that cost something (giving up a session). */
export function HoldButton({ children, onDone, ms = 1600, kind = 'danger' }: { children: ComponentChildren; onDone: () => void; ms?: number; kind?: 'danger' | 'ghost' }) {
  const [p, setP] = useState(0);
  const raf = useRef(0);
  const start = useRef(0);
  const ticks = useRef(0);
  const stop = () => {
    cancelAnimationFrame(raf.current);
    setP(0);
  };
  const down = () => {
    start.current = performance.now();
    ticks.current = 0;
    const step = () => {
      const v = Math.min(1, (performance.now() - start.current) / ms);
      setP(v);
      const t = Math.floor(v * 6);
      if (t > ticks.current) {
        ticks.current = t;
        haptic('tick');
      }
      if (v >= 1) {
        haptic('reject');
        setP(0);
        onDone();
        return;
      }
      raf.current = requestAnimationFrame(step);
    };
    raf.current = requestAnimationFrame(step);
  };
  useEffect(() => () => cancelAnimationFrame(raf.current), []);
  return (
    <button class={cls('btn', 'btn-' + kind, 'hold')} onPointerDown={down} onPointerUp={stop} onPointerLeave={stop} onPointerCancel={stop} onContextMenu={(e) => e.preventDefault()}>
      <span class="hold-fill" style={{ transform: `scaleX(${p})` }} />
      <span class="hold-label">{children}</span>
    </button>
  );
}

export function Chips<T extends string | number>({ options, value, onChange, multi }: {
  options: { value: T; label: ComponentChildren }[]; value: T | T[]; onChange: (v: any) => void; multi?: boolean;
}) {
  const sel = (v: T) => (multi ? (value as T[]).includes(v) : value === v);
  return (
    <div class="chips" role={multi ? 'group' : 'radiogroup'}>
      {options.map((o) => (
        <button
          type="button"
          key={String(o.value)}
          class={cls('chip', sel(o.value) && 'on')}
          aria-pressed={sel(o.value)}
          onClick={() => {
            haptic('tick');
            if (!multi) onChange(o.value);
            else {
              const cur = value as T[];
              onChange(cur.includes(o.value) ? cur.filter((x) => x !== o.value) : [...cur, o.value]);
            }
          }}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Toggle({ on, onChange, label, sub }: { on: boolean; onChange: (v: boolean) => void; label: ComponentChildren; sub?: ComponentChildren }) {
  return (
    <label class="toggle-row">
      <span>
        <span class="toggle-label">{label}</span>
        {sub && <span class="muted small block">{sub}</span>}
      </span>
      <input
        type="checkbox"
        class="switch"
        checked={on}
        onChange={(e) => {
          haptic('tick');
          onChange((e.target as HTMLInputElement).checked);
        }}
      />
    </label>
  );
}

export function Stepper({ value, onChange, min = 0, max = 999, step = 1, suffix }: { value: number; onChange: (v: number) => void; min?: number; max?: number; step?: number; suffix?: string }) {
  const set = (v: number) => {
    haptic('tick');
    onChange(Math.max(min, Math.min(max, v)));
  };
  return (
    <div class="stepper">
      <button type="button" onClick={() => set(value - step)} disabled={value <= min} aria-label="Less">−</button>
      <span class="stepper-val">
        {value}
        {suffix && <small>{suffix}</small>}
      </span>
      <button type="button" onClick={() => set(value + step)} disabled={value >= max} aria-label="More">+</button>
    </div>
  );
}

export function Sheet({ open, onClose, title, children }: { open: boolean; onClose: () => void; title?: string; children: ComponentChildren }) {
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    addEventListener('keydown', k);
    return () => removeEventListener('keydown', k);
  }, [open]);
  if (!open) return null;
  return (
    <div class="sheet-backdrop" onClick={onClose}>
      <div class="sheet" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <header class="sheet-head">
          <h3>{title}</h3>
          <button class="icon-btn" onClick={onClose} aria-label="Close">
            <Icon name="close" />
          </button>
        </header>
        <div class="sheet-body">{children}</div>
      </div>
    </div>
  );
}

// ---- Data marks ----------------------------------------------------------------------------

export function Ring({ value, max, size = 132, stroke = 12, color = 'var(--lime)', children }: { value: number; max: number | null; size?: number; stroke?: number; color?: string; children?: ComponentChildren }) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const frac = max === null ? 0 : max === 0 ? (value > 0 ? 1 : 0) : Math.min(1, value / max);
  return (
    <div class="ring" style={{ width: size, height: size }}>
      <svg width={size} height={size}>
        <circle cx={size / 2} cy={size / 2} r={r} stroke="var(--ink-800)" stroke-width={stroke} fill="none" />
        <circle
          cx={size / 2} cy={size / 2} r={r} stroke={color} stroke-width={stroke} fill="none" stroke-linecap="round"
          stroke-dasharray={`${c * frac} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} class="ring-arc"
        />
      </svg>
      <div class="ring-center">{children}</div>
    </div>
  );
}

export function Meter({ value, max, color = 'var(--lime)', warnAt = 0.85 }: { value: number; max: number; color?: string; warnAt?: number }) {
  const f = max <= 0 ? 1 : Math.min(1, value / max);
  return (
    <div class="meter">
      <div class="meter-fill" style={{ width: `${f * 100}%`, background: f >= 1 ? 'var(--ember)' : f >= warnAt ? 'var(--gold)' : color }} />
    </div>
  );
}

/** Stacked vertical bars. Each bar is a list of [key, value, color]. */
export function Bars({ bars, height = 140, labels, onPick, picked, format, goal }: {
  bars: [string, number, string][][]; height?: number; labels: string[]; onPick?: (i: number) => void; picked?: number;
  format?: (v: number) => string; goal?: number;
}) {
  const totals = bars.map((b) => b.reduce((s, x) => s + x[1], 0));
  const max = Math.max(1, goal ?? 0, ...totals);
  return (
    <div class="bars" style={{ height: height + 22 }}>
      {goal !== undefined && goal > 0 && <div class="bars-goal" style={{ bottom: 22 + (goal / max) * height }} />}
      {bars.map((stack, i) => (
        <button
          type="button"
          key={i}
          class={cls('bar-col', picked === i && 'picked', !onPick && 'static')}
          onClick={() => onPick?.(i)}
          title={format ? `${labels[i]}: ${format(totals[i])}` : labels[i]}
          aria-label={format ? `${labels[i]}: ${format(totals[i])}` : labels[i]}
        >
          <div class="bar-stack" style={{ height }}>
            {stack.filter((s) => s[1] > 0).map(([k, v, c]) => (
              <div key={k} class="bar-seg" style={{ height: `${(v / max) * 100}%`, background: c }} />
            ))}
          </div>
          <span class="bar-label">{labels[i]}</span>
        </button>
      ))}
    </div>
  );
}

export function Split({ parts }: { parts: { key: string; value: number; color: string; label: string }[] }) {
  const total = parts.reduce((s, p) => s + p.value, 0) || 1;
  return (
    <div class="split">
      <div class="split-bar">
        {parts.filter((p) => p.value > 0).map((p) => (
          <div key={p.key} style={{ width: `${(p.value / total) * 100}%`, background: p.color }} title={p.label} />
        ))}
      </div>
    </div>
  );
}

export function Legend({ items }: { items: { color: string; label: ComponentChildren }[] }) {
  return (
    <div class="legend">
      {items.map((it, i) => (
        <span key={i}>
          <i style={{ background: it.color }} />
          {it.label}
        </span>
      ))}
    </div>
  );
}

export function Empty({ icon = 'grove', title, children }: { icon?: string; title: string; children?: ComponentChildren }) {
  return (
    <div class="empty">
      <Icon name={icon} size={28} />
      <strong>{title}</strong>
      {children && <p class="muted">{children}</p>}
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: ComponentChildren; hint?: ComponentChildren }) {
  return (
    <label class="field">
      <span class="field-label">{label}</span>
      {children}
      {hint && <span class="muted small">{hint}</span>}
    </label>
  );
}

export function useNow(ms = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}

export type Child = JSX.Element | null;

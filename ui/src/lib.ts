import { catalog, state } from './api';
import type { Enforce, Target } from './types';

// ---- Targets -------------------------------------------------------------------------------

export function targetInfo(id: string, fallbackName = ''): { id: string; name: string; category: string; known: boolean } {
  const t = catalog.value.targets.find((x) => x.id === id);
  const override = state.value?.rules.categoryOverrides?.[id];
  if (t) return { id, name: t.name, category: override || t.category, known: true };
  let name = fallbackName;
  if (!name) {
    const [, rest = id] = id.split(':');
    // "com.spotify.music" is Spotify, not Music: the vendor segment names the app far more
    // often than the product word after it does.
    const generic = ['com', 'org', 'net', 'io', 'in', 'co', 'app', 'apps', 'android', 'www', 'google', 'mobile', 'lite', 'exe'];
    name = rest.split('.').filter((p) => p && !generic.includes(p.toLowerCase()))[0] || rest;
    name = name.charAt(0).toUpperCase() + name.slice(1);
  }
  return { id, name, category: override || 'other', known: false };
}

export function categoryName(id: string) {
  return catalog.value.categories.find((c) => c.id === id)?.name ?? id;
}

export const distractingCategories = () => catalog.value.categories.filter((c) => c.distracting).map((c) => c.id);

export function pickableTargets(): Target[] {
  return catalog.value.targets;
}

// A stable, quiet colour per app so the same app reads the same everywhere.
const swatches = ['#C6F432', '#FF7A3D', '#8FB3C9', '#BFA3FF', '#5CE6B0', '#FFD15C', '#FF8DB4', '#7CC4FF', '#E3E6DA'];
export function swatch(id: string) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  return swatches[Math.abs(h) % swatches.length];
}

export const deviceColors = ['#C6F432', '#8FB3C9', '#FF7A3D', '#BFA3FF', '#5CE6B0', '#FFD15C'];
export function deviceColor(deviceId: string | number) {
  const ids = (state.value?.devices ?? []).map((d) => String(d.id)).sort((a, b) => Number(a) - Number(b));
  const i = ids.indexOf(String(deviceId));
  return deviceColors[(i < 0 ? 0 : i) % deviceColors.length];
}

// ---- Time ----------------------------------------------------------------------------------

export function dur(sec: number, short = false): string {
  sec = Math.max(0, Math.round(sec));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (h > 0) return short ? `${h}h${m ? ` ${m}m` : ''}` : `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return sec > 0 ? '<1m' : '0m';
}

export function clock(ms: number): string {
  const s = Math.max(0, Math.ceil(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(ss)}` : `${pad(m)}:${pad(ss)}`;
}

export function when(ms: number): string {
  const d = new Date(ms);
  const now = new Date();
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((day(d) - day(now)) / 86400000);
  const t = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  if (diff === 0) return `Today ${t}`;
  if (diff === 1) return `Tomorrow ${t}`;
  if (diff === -1) return `Yesterday ${t}`;
  if (Math.abs(diff) < 7) return `${d.toLocaleDateString([], { weekday: 'short' })} ${t}`;
  return `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })} ${t}`;
}

export function ago(ms: number): string {
  const s = (Date.now() - ms) / 1000;
  if (s < 90) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

export function dateLabel(date: string, style: 'short' | 'long' = 'short') {
  const [y, m, d] = date.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  return style === 'long'
    ? dt.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' })
    : dt.toLocaleDateString([], { weekday: 'short', day: 'numeric' });
}

export function addDays(date: string, n: number) {
  const [y, m, d] = date.split('-').map(Number);
  const dt = new Date(y, m - 1, d + n);
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
}

// ---- Enforcement, as the UI explains it ----------------------------------------------------

export function minutesOfDay(d = new Date()) {
  return d.getHours() * 60 + d.getMinutes();
}

export function hhmm(s: string) {
  const [h, m] = s.split(':').map(Number);
  return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : -1;
}

export function quietActive(e: Enforce, now = new Date()) {
  const q = e.quiet;
  if (!q?.enabled) return false;
  const f = hhmm(q.from), t = hhmm(q.to), m = minutesOfDay(now);
  if (f < 0 || t < 0 || f === t) return false;
  return f < t ? m >= f && m < t : m >= f || m < t;
}

export function currentPhase(e: Enforce, now: number) {
  return e.focus?.phases.find((p) => now >= p.start && now < p.end) ?? null;
}

export function reelTotal(e: Enforce) {
  return e.reels.others + e.reels.mine;
}

export function plural(n: number, one: string, many = one + 's') {
  return `${n} ${n === 1 ? one : many}`;
}

export function cls(...xs: (string | false | null | undefined)[]) {
  return xs.filter(Boolean).join(' ');
}

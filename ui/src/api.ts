import { signal, computed } from '@preact/signals';
import { bridge, type Session } from './bridge';
import type { Catalog, State } from './types';
import bundledCatalog from '../../shared/catalog.json';

export const DEFAULT_SERVER: string =
  (import.meta.env.VITE_KEEL_SERVER as string | undefined) ||
  (bridge.kind === 'web' && location.protocol.startsWith('http') && !location.port.startsWith('51')
    ? location.origin
    : 'https://89-124-4-220.sslip.io');

export const session = signal<Session | null>(null);
export const state = signal<State | null>(null);
export const catalog = signal<Catalog>(bundledCatalog as Catalog);
export const link = signal<'online' | 'offline' | 'connecting'>('connecting');
export const toast = signal<{ text: string; tone: 'good' | 'bad' | 'plain'; id: number } | null>(null);
export const booted = signal(false);

/** serverNow - Date.now(), so countdowns agree across devices whose clocks disagree. */
export const skew = signal(0);
export const serverNow = () => Date.now() + skew.value;

export const deviceNames = computed(() => {
  const m: Record<string, string> = {};
  for (const d of state.value?.devices ?? []) m[String(d.id)] = d.name;
  return m;
});

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function api<T = any>(method: string, path: string, body?: unknown, s = session.value): Promise<T> {
  if (!s) throw new ApiError(401, 'signed out');
  let res: Response;
  try {
    res = await fetch(s.server.replace(/\/$/, '') + '/api' + path, {
      method,
      headers: { Authorization: 'Bearer ' + s.token, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    link.value = 'offline';
    throw new ApiError(0, "Can't reach the server.");
  }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) {
    await signOut(false);
    throw new ApiError(401, 'Signed out');
  }
  if (!res.ok) throw new ApiError(res.status, data.error || `Error ${res.status}`);
  return data as T;
}

export async function authCall(server: string, kind: 'login' | 'register', email: string, password: string) {
  const base = server.trim().replace(/\/$/, '');
  let res: Response;
  try {
    res = await fetch(base + '/api/' + kind, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email,
        password,
        tz: Intl.DateTimeFormat().resolvedOptions().timeZone,
        device: { name: await bridge.deviceName(), platform: bridge.platform },
      }),
    });
  } catch {
    throw new Error(`Can't reach ${base}. Check the address and your connection.`);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Error ${res.status}`);
  const s: Session = { server: base, token: data.token, deviceId: data.deviceId, email };
  await bridge.setSession(s);
  session.value = s;
  bridge.pref('lastServer', base);
  await refresh();
  connect();
}

export async function signOut(callServer = true) {
  if (callServer) await api('POST', '/logout').catch(() => {});
  await bridge.setSession(null);
  session.value = null;
  state.value = null;
  ws?.close();
}

let refreshing: Promise<void> | null = null;
let again = false;

export function refresh(): Promise<void> {
  if (refreshing) {
    again = true;
    return refreshing;
  }
  refreshing = (async () => {
    try {
      const t0 = Date.now();
      const s = await api<State>('GET', '/state');
      const rtt = Date.now() - t0;
      skew.value = s.enforce.now - (t0 + rtt / 2);
      state.value = s;
      link.value = 'online';
    } catch (e) {
      if (e instanceof ApiError && e.status !== 0) say(e.message, 'bad');
      // Nothing loaded yet: show the offline screen rather than an endless splash.
      if (!state.value && session.value) link.value = 'offline';
    } finally {
      refreshing = null;
      if (again) {
        again = false;
        void refresh();
      }
    }
  })();
  return refreshing;
}

let ws: WebSocket | null = null;
let backoff = 1000;
let refreshTimer: number | undefined;

export function connect() {
  const s = session.value;
  if (!s) return;
  ws?.close();
  const url = s.server.replace(/^http/, 'ws').replace(/\/$/, '') + '/api/ws?token=' + encodeURIComponent(s.token);
  const sock = new WebSocket(url);
  ws = sock;
  sock.onopen = () => {
    backoff = 1000;
    link.value = 'online';
  };
  sock.onmessage = (ev) => {
    try {
      const m = JSON.parse(ev.data);
      if (m.type === 'changed') {
        clearTimeout(refreshTimer);
        refreshTimer = window.setTimeout(() => void refresh(), 250);
      }
    } catch {}
  };
  sock.onclose = () => {
    if (ws !== sock || !session.value) return;
    link.value = 'offline';
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30000);
  };
}

export async function boot() {
  session.value = await bridge.getSession();
  if (session.value) {
    await refresh();
    connect();
  }
  booted.value = true;
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && session.value) void refresh();
  });
  setInterval(() => session.value && document.visibilityState === 'visible' && refresh(), 60_000);
}

let toastId = 0;
export function say(text: string, tone: 'good' | 'bad' | 'plain' = 'plain') {
  const id = ++toastId;
  toast.value = { text, tone, id };
  setTimeout(() => {
    if (toast.value?.id === id) toast.value = null;
  }, 3200);
}

/** Runs a mutation, shows its error as a toast, and refreshes state. */
export async function act<T>(fn: () => Promise<T>, ok?: string): Promise<T | undefined> {
  try {
    const r = await fn();
    if (ok) say(ok, 'good');
    await refresh();
    return r;
  } catch (e: any) {
    say(e?.message || 'Something went wrong', 'bad');
    return undefined;
  }
}

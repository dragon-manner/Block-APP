// The one seam between the shared UI and the platform it runs in. The UI talks to the sync
// server itself; the bridge only hands the session to the native engine (which enforces and
// reports usage even with the window closed) and exposes what only the device can know.

export type Session = { server: string; token: string; deviceId: number; email?: string };
export type Kind = 'android' | 'desktop' | 'web';

export interface Bridge {
  kind: Kind;
  platform: string; // android | linux | windows | web
  getSession(): Promise<Session | null>;
  setSession(s: Session | null): Promise<void>;
  deviceName(): Promise<string>;
  status(): Promise<Record<string, any> | null>;
  action(name: string, arg?: string): Promise<any>;
  pref(key: string, value?: string): string | null;
}

declare global {
  interface Window {
    KeelAndroid?: {
      getSession(): string;
      setSession(json: string): void;
      deviceName(): string;
      status(): string;
      action(name: string, arg: string): string;
    };
    __TAURI__?: { core: { invoke(cmd: string, args?: Record<string, unknown>): Promise<any> } };
  }
}

function localPref(key: string, value?: string): string | null {
  try {
    if (value !== undefined) localStorage.setItem('keel.' + key, value);
    return localStorage.getItem('keel.' + key);
  } catch {
    return null;
  }
}

function parse<T>(s: string | null | undefined): T | null {
  if (!s) return null;
  try {
    return JSON.parse(s) as T;
  } catch {
    return null;
  }
}

function androidBridge(): Bridge {
  const k = window.KeelAndroid!;
  return {
    kind: 'android',
    platform: 'android',
    getSession: async () => parse<Session>(k.getSession()),
    setSession: async (s) => k.setSession(s ? JSON.stringify(s) : ''),
    deviceName: async () => k.deviceName() || 'Android phone',
    status: async () => parse(k.status()),
    action: async (name, arg = '') => parse(k.action(name, arg)),
    pref: localPref,
  };
}

function tauriBridge(): Bridge {
  const invoke = window.__TAURI__!.core.invoke;
  const ua = navigator.userAgent.toLowerCase();
  return {
    kind: 'desktop',
    platform: ua.includes('windows') ? 'windows' : 'linux',
    getSession: async () => (await invoke('get_session')) as Session | null,
    setSession: async (s) => {
      await invoke('set_session', { session: s });
    },
    deviceName: async () => ((await invoke('device_name')) as string) || 'Computer',
    status: async () => invoke('local_status'),
    action: async (name, arg = '') => invoke('action', { name, arg }),
    pref: localPref,
  };
}

function webBridge(): Bridge {
  return {
    kind: 'web',
    platform: 'web',
    getSession: async () => parse<Session>(localPref('session')),
    setSession: async (s) => {
      if (s) localPref('session', JSON.stringify(s));
      else
        try {
          localStorage.removeItem('keel.session');
        } catch {}
    },
    deviceName: async () => {
      const ua = navigator.userAgent;
      const b = /Firefox/.test(ua) ? 'Firefox' : /Edg/.test(ua) ? 'Edge' : /Chrome/.test(ua) ? 'Chrome' : 'Browser';
      return `${b} (web)`;
    },
    status: async () => null,
    action: async () => null,
    pref: localPref,
  };
}

export const bridge: Bridge = window.KeelAndroid ? androidBridge() : window.__TAURI__ ? tauriBridge() : webBridge();

export function haptic(kind: 'tap' | 'success' | 'reject' | 'tick' = 'tap') {
  if (bridge.kind === 'android') void bridge.action('haptic', kind);
  else if (navigator.vibrate && kind !== 'tick') navigator.vibrate(kind === 'reject' ? [30, 40, 30] : 12);
}

import { useEffect, useState } from 'preact/hooks';
import { act, api, session, signOut, state } from '../api';
import { bridge } from '../bridge';
import { Button, Icon, PageHead, Panel, Sheet, Toggle } from '../components/ui';
import { ago, cls, deviceColor } from '../lib';
import type { Device } from '../types';

const platformIcon: Record<string, string> = { android: 'phone', linux: 'laptop', windows: 'laptop', macos: 'laptop', web: 'globe' };

function Check({ ok, label, fix, onFix }: { ok: boolean | undefined; label: string; fix?: string; onFix?: () => void }) {
  return (
    <div class="check-row">
      <span class={cls('check-dot', ok ? 'ok' : 'bad')}>{ok ? <Icon name="check" size={12} /> : '!'}</span>
      <span class="grow">{label}</span>
      {!ok && fix && onFix && <Button small kind="ghost" onClick={onFix}>{fix}</Button>}
    </div>
  );
}

function ThisDevice() {
  const [st, setSt] = useState<Record<string, any> | null>(null);
  const load = () => bridge.status().then(setSt).catch(() => setSt(null));
  useEffect(() => {
    void load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, []);
  if (bridge.kind === 'web') {
    return (
      <Panel title="This browser">
        <p class="muted">The web dashboard shows and changes everything, but can't track or block on its own. Install Keel on each computer and phone, and the browser extension for websites and reels.</p>
      </Panel>
    );
  }
  if (!st) return <Panel title="This device"><p class="muted">Reading the engine…</p></Panel>;
  if (bridge.kind === 'android') {
    return (
      <Panel title="This phone" action={<Button small kind="ghost" onClick={() => bridge.action('openSetup')}>Open setup</Button>}>
        <Check ok={st.accessibility} label="Accessibility service running (blocking + reel counting)" fix="Turn on" onFix={() => bridge.action('openAccessibility')} />
        <Check ok={st.usageAccess} label="Usage access (screen time)" fix="Allow" onFix={() => bridge.action('openUsageAccess')} />
        <Check ok={st.admin} label="Device admin (uninstall protection)" fix="Activate" onFix={() => bridge.action('openAdmin')} />
        <Check ok={st.battery} label="Not battery-optimised (stays alive)" fix="Allow" onFix={() => bridge.action('openBattery')} />
        <p class="muted small mt-s">Last sync {st.lastSync ? ago(st.lastSync) : 'never'}{st.syncError ? ` · ${st.syncError}` : ''}.</p>
        {st.canRemoveProtection !== undefined && (
          <Button small kind="quiet" class="mt-s" onClick={() => bridge.action('openRemoveProtection')}>Remove protection…</Button>
        )}
      </Panel>
    );
  }
  return (
    <Panel title="This computer">
      <Check ok={st.tracking} label={`Window tracking${st.backend ? ` (${st.backend})` : ''}`} />
      <Check ok={st.extension} label={st.extension ? `Browser extension connected${st.extensionBrowser ? ` (${st.extensionBrowser})` : ''}` : 'Browser extension not connected: websites and reels go uncounted'} fix="How?" onFix={() => bridge.action('openExtensionHelp')} />
      <Toggle on={!!st.autostart} onChange={(v) => bridge.action('autostart', v ? 'on' : 'off').then(load)} label="Start with the computer" sub="Limits only hold while Keel is running." />
      {st.active && <p class="muted small mt-s">Now: {st.active}</p>}
      <p class="muted small">Last sync {st.lastSync ? ago(st.lastSync) : 'never'}{st.syncError ? ` · ${st.syncError}` : ''}.</p>
    </Panel>
  );
}

export function Devices() {
  const s = state.value!;
  const [edit, setEdit] = useState<Device | null>(null);
  const [name, setName] = useState('');
  return (
    <div class="page">
      <PageHead title="Devices" sub="Everything signed in to this account. Each one reports usage and enforces the same limits." />
      <ThisDevice />
      <Panel title="All devices">
        <div class="device-list">
          {s.devices.map((d) => (
            <div class="device" key={d.id}>
              <span class="device-icon" style={{ color: deviceColor(d.id) }}><Icon name={platformIcon[d.platform] ?? 'devices'} size={22} /></span>
              <div class="grow">
                <div>{d.name}{d.id === s.deviceId && <span class="tag">this one</span>}</div>
                <div class="muted small">{d.platform} · {d.online ? <span class="good">online</span> : `seen ${ago(d.lastSeen)}`}</div>
                {d.platform === 'android' && d.status?.accessibility === false && <div class="bad small">Accessibility is off: this phone isn't enforcing.</div>}
                {d.status?.tracking === false && <div class="bad small">Not tracking windows.</div>}
              </div>
              <button class="icon-btn subtle" aria-label="Rename" onClick={() => { setEdit(d); setName(d.name); }}><Icon name="edit" size={16} /></button>
            </div>
          ))}
        </div>
      </Panel>
      <Panel title="Account">
        <p>{s.user.email}<span class="muted small block">Server {session.value?.server.replace(/^https?:\/\//, '')} · time zone {s.user.tz}</span></p>
        {s.user.tz !== Intl.DateTimeFormat().resolvedOptions().timeZone && (
          <Button small kind="ghost" onClick={() => act(() => api('PUT', '/me', { tz: Intl.DateTimeFormat().resolvedOptions().timeZone }), 'Time zone updated')}>
            Use {Intl.DateTimeFormat().resolvedOptions().timeZone}
          </Button>
        )}
        <Button kind="quiet" icon="logout" class="mt" onClick={() => signOut()}>Sign out of this device</Button>
        {s.enforce.armed && bridge.kind !== 'web' && <p class="muted small mt-s">Signing out stops this device from enforcing. Your limits stay on every other device.</p>}
      </Panel>
      <Sheet open={!!edit} onClose={() => setEdit(null)} title="Device">
        {edit && (
          <div class="stack">
            <input value={name} onInput={(e) => setName((e.target as HTMLInputElement).value)} />
            <Button onClick={async () => { if (await act(() => api('PATCH', `/devices/${edit.id}`, { name }), 'Renamed')) setEdit(null); }}>Rename</Button>
            {edit.id !== s.deviceId && (
              <Button kind="danger" onClick={async () => { if (await act(() => api('DELETE', `/devices/${edit.id}`), 'Signed out remotely')) setEdit(null); }}>Sign it out</Button>
            )}
          </div>
        )}
      </Sheet>
    </div>
  );
}

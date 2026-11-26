import { useState } from 'preact/hooks';
import { authCall, DEFAULT_SERVER } from '../api';
import { bridge } from '../bridge';
import { Button, Field } from '../components/ui';
import { Tree } from '../components/Tree';

export function Login() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [pw, setPw] = useState('');
  const [server, setServer] = useState(bridge.pref('lastServer') || DEFAULT_SERVER);
  const [showServer, setShowServer] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  const submit = async (e: Event) => {
    e.preventDefault();
    setBusy(true);
    setErr('');
    try {
      await authCall(server, mode, email, pw);
    } catch (x: any) {
      setErr(x.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div class="login">
      <div class="login-art" aria-hidden="true">
        <Tree seed={7} minutes={90} size={120} />
        <Tree seed={18} minutes={60} size={92} />
        <Tree seed={42} minutes={45} size={104} />
      </div>
      <h1 class="display">Keel</h1>
      <p class="lede">One account across your phone, laptop and browser. Reels, apps and focus, kept steady everywhere.</p>
      <form class="login-form" onSubmit={submit}>
        <Field label="Email">
          <input type="email" autocomplete="email" required value={email} onInput={(e) => setEmail((e.target as HTMLInputElement).value)} />
        </Field>
        <Field label="Password" hint={mode === 'register' ? 'At least 8 characters.' : undefined}>
          <input type="password" autocomplete={mode === 'login' ? 'current-password' : 'new-password'} required minLength={8} value={pw} onInput={(e) => setPw((e.target as HTMLInputElement).value)} />
        </Field>
        {showServer ? (
          <Field label="Sync server" hint="Your own Keel server. Every device must use the same one.">
            <input type="url" required value={server} onInput={(e) => setServer((e.target as HTMLInputElement).value)} />
          </Field>
        ) : (
          <button type="button" class="link small" onClick={() => setShowServer(true)}>
            Server: {server.replace(/^https?:\/\//, '')}
          </button>
        )}
        {err && <p class="error">{err}</p>}
        <Button type="submit" disabled={busy}>{busy ? 'One moment…' : mode === 'login' ? 'Sign in' : 'Create account'}</Button>
        <button type="button" class="link" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setErr(''); }}>
          {mode === 'login' ? 'First time? Create the account' : 'Already have an account? Sign in'}
        </button>
      </form>
    </div>
  );
}

import { useState } from 'react';
import { ApiError } from '../api/client';
import { useAuth } from '../auth';

export function LoginPage() {
  const { login, expired } = useAuth();
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  return (
    <div className="login">
      <form
        className="panel login-card"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError(undefined);
          try {
            await login(username.trim(), password);
          } catch (err) {
            setError(err instanceof ApiError
              ? err.offline ? 'Control plane is not reachable.' : err.message
              : 'Login failed.');
          } finally {
            setBusy(false);
            setPassword('');
          }
        }}
      >
        <h1>ByteVault</h1>
        <p className="muted">Sign in to the operations console.</p>
        {expired && <div className="note warn">Your session expired. Please sign in again.</div>}
        {error && <div className="note error" role="alert">{error}</div>}
        <label className="field">
          Username
          <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
        </label>
        <label className="field">
          Password
          <input type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required autoFocus />
        </label>
        <button className="btn primary" type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <p className="muted small">The first admin password is <code>BV_BOOTSTRAP_ADMIN_PASSWORD</code> in <code>.env</code>.</p>
      </form>
    </div>
  );
}

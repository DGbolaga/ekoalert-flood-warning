import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ApiError, isOffline } from '../api/client';
import { useAuth } from '../state/auth';

export function LoginScreen() {
  const { signIn, expired } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const session = await signIn(username.trim(), password);
      navigate(session.role === 'ADMIN' ? '/admin' : '/report', { replace: true });
    } catch (err) {
      if (isOffline(err)) setError('The network did not answer. Check your connection.');
      // The server answers a wrong password and an unknown username identically
      // on purpose, so the form cannot be used to discover who has an account.
      // Nothing here tries to tell them apart.
      else if (err instanceof ApiError) setError(err.message);
      else setError('That did not go through.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="screen">
      <div className="wrap" style={{ paddingTop: 32 }}>
        <button className="t15 muted" type="button" onClick={() => navigate('/')}>
          Back to the map
        </button>

        <h1 className="t34" style={{ marginTop: 24 }}>
          Sign in
        </h1>
        <p className="t15 muted" style={{ marginTop: 8 }}>
          The map is open to everybody. Signing in is for reporters and admins.
        </p>

        {expired && (
          <p className="card t15" role="status" style={{ marginTop: 16, padding: 12 }}>
            Your session ended. Sign in again. Anything waiting to send is still queued.
          </p>
        )}

        {(location.state as { from?: string } | null)?.from === '/report' && !expired && (
          <p className="card t15" style={{ marginTop: 16, padding: 12 }}>
            Sign in to file a report or to correct the map.
          </p>
        )}

        <form onSubmit={submit} className="stack" style={{ gap: 14, marginTop: 24 }}>
          <label className="stack" style={{ gap: 6 }}>
            <span className="t15">Username</span>
            <input
              className="field"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
          </label>

          <label className="stack" style={{ gap: 6 }}>
            <span className="t15">Password</span>
            <input
              className="field"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>

          {error && (
            <p className="t15" role="alert">
              {error}
            </p>
          )}

          <button className="btn btn--primary btn--wide" type="submit" disabled={busy}>
            {busy ? 'Signing in' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  );
}

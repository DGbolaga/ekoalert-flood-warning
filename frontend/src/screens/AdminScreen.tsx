import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import './AdminScreen.css';
import {
  ApiError,
  createReporter,
  isOffline,
  listReporters,
  resetReporterPassword,
  setKillSwitch,
  suspendReporter,
  verifyReporter,
} from '../api/client';
import type { ReporterCredentials, ReporterView } from '../api/types';
import { clockTime } from '../lib/time';
import { useAuth } from '../state/auth';
import { useLive } from '../state/live';

export function AdminScreen() {
  const { signOut } = useAuth();
  const { zones } = useLive();
  const navigate = useNavigate();
  const unnamed = zones.filter((z) => z.needsFieldNaming);

  return (
    <div className="screen">
      <div className="wrap">
        <div className="admin__head">
          <h1 className="t22">Admin</h1>
          <span className="spacer" />
          <button
            className="t13 muted"
            type="button"
            onClick={() => {
              signOut();
              navigate('/');
            }}
          >
            Sign out
          </button>
        </div>

        <KillSwitch />

        <section className="admin__section">
          <h2>Replay a past event</h2>
          <p className="t15 muted" style={{ padding: '12px 0' }}>
            Feed timestamped reports through the engine against the current graph and see what it
            would have predicted.
          </p>
          <Link className="btn btn--wide" to="/admin/replay">
            Open replay
          </Link>
        </section>

        <Reporters />

        <section className="admin__section">
          <h2>Zones still waiting on a name</h2>
          {unnamed.length === 0 ? (
            <p className="t15 muted" style={{ padding: '14px 0' }}>
              Nothing outstanding, or the map has not loaded yet.
            </p>
          ) : (
            <>
              <p className="t15 muted" style={{ padding: '12px 0 0' }}>
                These sit more than two kilometres from any named place on OpenStreetMap. They are
                not empty land. Somebody has to go and name them.
              </p>
              {unnamed.map((zone) => (
                <div className="admin__row" key={zone.id}>
                  <span className="t17 expanded admin__id">{zone.displayName}</span>
                  <span className="t15 muted">{zone.corridor}</span>
                  <span className="spacer" />
                  <span className="t13 muted">
                    {zone.lat.toFixed(4)}, {zone.lng.toFixed(4)}
                  </span>
                </div>
              ))}
            </>
          )}
        </section>
      </div>
    </div>
  );
}

/**
 * There is no endpoint that reports the current kill switch state, so this does
 * not render a toggle. A toggle asserts a position, and asserting one it cannot
 * read would be a lie about whether warnings are reaching people. Instead it
 * offers two explicit commands and reports only what it has actually set.
 */
function KillSwitch() {
  const [known, setKnown] = useState<{ enabled: boolean; at: string } | undefined>();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const send = async (enabled: boolean) => {
    setBusy(true);
    setError(undefined);
    try {
      const res = await setKillSwitch(enabled);
      setKnown({ enabled: res.alertsEnabled, at: res.at });
      setConfirming(false);
    } catch (err) {
      if (isOffline(err)) setError('That did not reach the server. The setting is unchanged.');
      else if (err instanceof ApiError) setError(err.message);
      else setError('That did not go through.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="kill" aria-labelledby="kill-title">
      <h2 id="kill-title" className="t13 muted">
        Outgoing alerts
      </h2>

      <p className="kill__state">
        {known === undefined
          ? 'State unknown'
          : known.enabled
            ? 'Alerts are being sent'
            : 'Alerts are halted'}
      </p>

      <p className="kill__note">
        {known === undefined
          ? 'The server does not report the current setting, so set it explicitly to be sure.'
          : `Set by you at ${clockTime(known.at)}.`}
      </p>

      {confirming ? (
        <div className="kill__confirm">
          <p className="t15">
            Halt every outgoing alert. Water will still be tracked and rows will still be written,
            but nobody downstream will be told anything until you turn this back on.
          </p>
          <div className="kill__actions">
            <button
              className="btn btn--primary"
              type="button"
              onClick={() => void send(false)}
              disabled={busy}
            >
              {busy ? 'Halting' : 'Halt all alerts'}
            </button>
            <button className="btn btn--quiet" type="button" onClick={() => setConfirming(false)}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <div className="kill__actions">
          {/* Halting asks for confirmation. Turning it back on does not. */}
          <button className="btn" type="button" onClick={() => setConfirming(true)} disabled={busy}>
            Halt all alerts
          </button>
          <button
            className="btn btn--primary"
            type="button"
            onClick={() => void send(true)}
            disabled={busy}
          >
            {busy ? 'Working' : 'Send alerts'}
          </button>
        </div>
      )}

      {error && (
        <p className="t15" role="alert" style={{ marginTop: 12 }}>
          {error}
        </p>
      )}
    </section>
  );
}

/**
 * No endpoint lists reporters, so this takes the id directly. A suspended
 * reporter's reports are still stored; they just stop counting toward a quorum.
 */
function Reporters() {
  const { zones } = useLive();
  const [list, setList] = useState<ReporterView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();
  const [adding, setAdding] = useState(false);
  // Shown once, then gone. Nothing can read it back.
  const [issued, setIssued] = useState<ReporterCredentials | undefined>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await listReporters());
      setError(undefined);
    } catch (err) {
      setError(describe(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const act = async (run: () => Promise<unknown>) => {
    setError(undefined);
    try {
      await run();
      await load();
    } catch (err) {
      setError(describe(err));
    }
  };

  return (
    <section className="admin__section">
      <h2>Reporters</h2>
      <p className="t15 muted" style={{ padding: '12px 0' }}>
        Only vetted, unsuspended reporters count toward a quorum. Nobody can enrol themselves:
        two reports have to be two people, or the rule means nothing.
      </p>

      {issued && <IssuedPassword issued={issued} onDismiss={() => setIssued(undefined)} />}

      {adding ? (
        <AddReporter
          zoneIds={zones.map((z) => z.id)}
          onCancel={() => setAdding(false)}
          onDone={(creds) => {
            setIssued(creds);
            setAdding(false);
            void load();
          }}
        />
      ) : (
        <button className="btn" type="button" onClick={() => setAdding(true)}>
          Add a reporter
        </button>
      )}

      {error && (
        <p className="t13" role="alert" style={{ marginTop: 10 }}>
          {error}
        </p>
      )}

      {loading && list.length === 0 ? (
        <p className="t15 muted" style={{ paddingTop: 14 }}>
          Loading.
        </p>
      ) : list.length === 0 ? (
        <p className="t15 muted" style={{ paddingTop: 14 }}>
          Nobody is enrolled yet.
        </p>
      ) : (
        <ul className="rep__list">
          {list.map((r) => (
            <li key={r.id} className="rep">
              <div className="rep__who">
                <span className="t17 expanded">{r.displayName}</span>
                <span className="t13 muted">
                  {r.zoneId}
                  {r.username ? ` · signs in as ${r.username}` : ' · no login'}
                </span>
                <span className="t13 muted">{statusWord(r)}</span>
              </div>
              <div className="rep__acts">
                <button
                  className="btn btn--quiet btn--small"
                  type="button"
                  onClick={() => void act(() => verifyReporter(r.id, !r.verifiedAt))}
                >
                  {r.verifiedAt ? 'Revoke vetting' : 'Vet'}
                </button>
                <button
                  className="btn btn--quiet btn--small"
                  type="button"
                  onClick={() => void act(() => suspendReporter(r.id, !r.suspended))}
                >
                  {r.suspended ? 'Unblock' : 'Block'}
                </button>
                <button
                  className="btn btn--quiet btn--small"
                  type="button"
                  onClick={() =>
                    void act(async () => setIssued(await resetReporterPassword(r.id)))
                  }
                >
                  New password
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function statusWord(r: ReporterView): string {
  if (r.suspended) return 'Blocked. Files reports, none of them count.';
  if (!r.verifiedAt) return 'Not vetted. Files reports, none of them count.';
  return 'Vetted. Counts toward a quorum.';
}

function AddReporter({
  zoneIds,
  onCancel,
  onDone,
}: {
  zoneIds: string[];
  onCancel: () => void;
  onDone: (creds: ReporterCredentials) => void;
}) {
  const [displayName, setDisplayName] = useState('');
  const [zoneId, setZoneId] = useState(zoneIds[0] ?? '');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const submit = async () => {
    setBusy(true);
    setError(undefined);
    try {
      onDone(await createReporter({ displayName: displayName.trim(), zoneId, phone: phone.trim() }));
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  };

  const ready = displayName.trim().length > 1 && phone.trim().length > 5 && zoneId !== '';

  return (
    <div className="rep__add">
      <input
        className="field"
        placeholder="Name"
        aria-label="Reporter name"
        value={displayName}
        onChange={(e) => setDisplayName(e.target.value)}
      />
      <select
        className="field"
        aria-label="Zone"
        value={zoneId}
        onChange={(e) => setZoneId(e.target.value)}
      >
        {zoneIds.map((z) => (
          <option key={z} value={z}>
            {z}
          </option>
        ))}
      </select>
      <input
        className="field"
        placeholder="Phone"
        inputMode="tel"
        aria-label="Phone"
        value={phone}
        onChange={(e) => setPhone(e.target.value)}
      />
      <div className="rep__add-acts">
        <button className="btn" type="button" disabled={!ready || busy} onClick={() => void submit()}>
          {busy ? 'Adding' : 'Add and vet'}
        </button>
        <button className="btn btn--quiet" type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
      {error && (
        <p className="t13" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

/* The one moment this password exists anywhere readable. */
function IssuedPassword({
  issued,
  onDismiss,
}: {
  issued: ReporterCredentials;
  onDismiss: () => void;
}) {
  return (
    <div className="rep__cred" role="status">
      <p className="t13 muted">Give these to {issued.reporter.displayName}, then dismiss.</p>
      <p className="rep__cred-line">
        <span className="t13 muted">username</span> <strong>{issued.username}</strong>
      </p>
      <p className="rep__cred-line">
        <span className="t13 muted">password</span> <strong>{issued.password}</strong>
      </p>
      <p className="t13 muted">
        This is the only time it is shown. If it is lost, issue a new one; it cannot be read back.
      </p>
      <button className="btn btn--quiet btn--small" type="button" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}

function describe(err: unknown): string {
  if (isOffline(err)) return 'That did not reach the server.';
  if (err instanceof ApiError) return err.message;
  return 'That did not go through.';
}

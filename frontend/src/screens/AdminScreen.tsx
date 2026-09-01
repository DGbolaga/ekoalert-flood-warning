import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import './AdminScreen.css';
import { ApiError, isOffline, setKillSwitch, suspendReporter } from '../api/client';
import type { ReporterView } from '../api/types';
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

        <ReporterSuspension />

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
function ReporterSuspension() {
  const [id, setId] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ReporterView | undefined>();
  const [error, setError] = useState<string | undefined>();

  const send = async (suspended: boolean) => {
    const numeric = Number(id);
    if (!Number.isInteger(numeric) || numeric <= 0) {
      setError('Enter the reporter id as a whole number.');
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      setResult(await suspendReporter(numeric, suspended));
    } catch (err) {
      if (isOffline(err)) setError('That did not reach the server.');
      else if (err instanceof ApiError) setError(err.message);
      else setError('That did not go through.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="admin__section">
      <h2>Reporter suspension</h2>
      <p className="t15 muted" style={{ padding: '12px 0' }}>
        A suspended reporter keeps filing, and the reports are still stored, but they stop counting
        toward a quorum.
      </p>
      <div className="row">
        <input
          className="field"
          inputMode="numeric"
          placeholder="Reporter id"
          value={id}
          onChange={(e) => setId(e.target.value)}
          aria-label="Reporter id"
          style={{ maxWidth: 160 }}
        />
        <button className="btn" type="button" onClick={() => void send(true)} disabled={busy}>
          Suspend
        </button>
        <button className="btn btn--quiet" type="button" onClick={() => void send(false)} disabled={busy}>
          Lift
        </button>
      </div>

      {result && (
        <p className="t15" role="status" style={{ marginTop: 12 }}>
          {result.displayName}, vetted for {result.zoneId}, is now{' '}
          {result.suspended ? 'suspended' : 'active again'}.
        </p>
      )}
      {error && (
        <p className="t15" role="alert" style={{ marginTop: 12 }}>
          {error}
        </p>
      )}
    </section>
  );
}

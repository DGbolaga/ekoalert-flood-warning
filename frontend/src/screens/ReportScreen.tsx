import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './ReportScreen.css';
import { ApiError, isOffline, postReport } from '../api/client';
import type { AlertView, ReportResponse, Severity } from '../api/types';
import { SEVERITY_WORD } from '../lib/severity';
import { clockTime } from '../lib/time';
import { DepthGlyph } from '../components/DepthGlyph';
import { useAuth } from '../state/auth';
import { useLive } from '../state/live';
import { enqueue, flush, onQueueChange, toRequest, type QueuedReport } from '../state/reportQueue';

type Stage =
  | { name: 'choose' }
  | { name: 'sending' }
  | { name: 'sent'; response: ReportResponse }
  | { name: 'queued'; observedAt: string }
  | { name: 'refused'; message: string };

const LEVELS: Array<{ level: Severity; note: string }> = [
  { level: 'ANKLE', note: 'Water is over the foot' },
  { level: 'KNEE', note: 'Wading, but passable' },
  { level: 'IMPASSABLE', note: 'Nobody can get through' },
];

export function ReportScreen() {
  const navigate = useNavigate();
  const { knownZoneId, rememberZone, signOut } = useAuth();
  const { recordAlerts, zoneById } = useLive();

  const [level, setLevel] = useState<Severity | undefined>();
  // observedAt is stamped when he says what he sees, not when the send succeeds,
  // so a report that waits in the queue still carries the right time.
  const [observedAt, setObservedAt] = useState<string | undefined>();
  const [drainBlocked, setDrainBlocked] = useState(false);
  // Absent is not the same as saying the drain is clear, so the field is only
  // sent once he has actually touched it.
  const [drainTouched, setDrainTouched] = useState(false);
  const [stage, setStage] = useState<Stage>({ name: 'choose' });
  const [pending, setPending] = useState<QueuedReport[]>([]);

  useEffect(() => onQueueChange(setPending), []);

  const drain = useCallback(async () => {
    const outcome = await flush(postReport, isOffline);
    outcome.sent.forEach(({ response }) => {
      rememberZone(response.zoneId);
      recordAlerts(response.alerts);
    });
    if (outcome.sent.length > 0) {
      const last = outcome.sent[outcome.sent.length - 1];
      setStage({ name: 'sent', response: last.response });
    }
  }, [recordAlerts, rememberZone]);

  // Retry on reconnect, and once on arrival in case the browser was closed with
  // something still queued.
  useEffect(() => {
    void drain();
    const onOnline = () => void drain();
    window.addEventListener('online', onOnline);
    return () => window.removeEventListener('online', onOnline);
  }, [drain]);

  const pick = (next: Severity) => {
    setLevel(next);
    setObservedAt(new Date().toISOString());
  };

  const submit = async () => {
    if (!level) return;
    const stamped = observedAt ?? new Date().toISOString();
    const draft = {
      level,
      observedAt: stamped,
      ...(drainTouched ? { drainBlocked } : {}),
    };

    setStage({ name: 'sending' });
    try {
      const response = await postReport(toRequest({ ...draft, queuedAt: stamped }));
      rememberZone(response.zoneId);
      recordAlerts(response.alerts);
      setStage({ name: 'sent', response });
    } catch (err) {
      if (isOffline(err)) {
        // Write it down before telling him anything, so the queue is real by the
        // time the screen claims it exists.
        await enqueue({ ...draft, queuedAt: new Date().toISOString() });
        setStage({ name: 'queued', observedAt: stamped });
        return;
      }
      setStage({
        name: 'refused',
        message: err instanceof ApiError ? err.message : 'The server would not take that report.',
      });
    }
  };

  const reset = () => {
    setLevel(undefined);
    setObservedAt(undefined);
    setDrainBlocked(false);
    setDrainTouched(false);
    setStage({ name: 'choose' });
  };

  if (stage.name === 'sent') {
    return (
      <Acknowledgement
        response={stage.response}
        zoneLabel={zoneById.get(stage.response.zoneId)?.displayName ?? stage.response.zoneId}
        onDone={reset}
        onConfirmEdges={() => navigate(`/?zone=${encodeURIComponent(stage.response.zoneId)}`)}
      />
    );
  }

  if (stage.name === 'queued') {
    return <QueuedAcknowledgement observedAt={stage.observedAt} pending={pending.length} onDone={reset} />;
  }

  const zoneLabel = knownZoneId ? (zoneById.get(knownZoneId)?.displayName ?? knownZoneId) : undefined;
  const corridor = knownZoneId ? zoneById.get(knownZoneId)?.corridor : undefined;

  return (
    <div className="report">
      <div className="report__inner">
        <div className="report__top">
          <span className="t13 muted">Reporting</span>
          <span className="spacer" />
          <button className="t13 muted" type="button" onClick={() => navigate('/')}>
            Map
          </button>
          <button className="t13 muted" type="button" onClick={() => { signOut(); navigate('/'); }}>
            Sign out
          </button>
        </div>

        <div className="report__zone">
          {zoneLabel ? (
            <>
              <h1 className="t34 expanded">{zoneLabel}</h1>
              {corridor && <p className="t15 muted">{corridor}</p>}
            </>
          ) : (
            // No endpoint returns the signed-in reporter's own zone, so it is not
            // guessed. It is filled in from the server's answer to his first report.
            <>
              <h1 className="t22">Your vetted zone</h1>
              <p className="t15 muted">
                The server assigns your zone and will name it on your first report.
              </p>
            </>
          )}
        </div>

        <div className="report__levels" role="group" aria-label="How deep is the water">
          {LEVELS.map(({ level: option, note }) => (
            <button
              key={option}
              type="button"
              className="level"
              aria-pressed={level === option}
              onClick={() => pick(option)}
            >
              <DepthGlyph level={option} size={44} />
              <span>
                <span className="level__word">{SEVERITY_WORD[option]}</span>
                <br />
                <span className="level__note">{note}</span>
              </span>
            </button>
          ))}
        </div>

        <button
          type="button"
          className="drain"
          aria-pressed={drainTouched && drainBlocked}
          onClick={() => {
            setDrainTouched(true);
            setDrainBlocked((v) => !v);
          }}
        >
          <span className="drain__box" aria-hidden="true">
            {drainTouched && drainBlocked && (
              <svg width="16" height="16" viewBox="0 0 16 16">
                <path
                  d="M3 8.5l3.5 3.5L13 4.5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.25"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            )}
          </span>
          <span className="t17">Drain is blocked here</span>
        </button>

        {stage.name === 'refused' && (
          <p className="card t15" role="alert" style={{ marginTop: 12, padding: 12 }}>
            {stage.message}
          </p>
        )}

        <div className="report__submit">
          {level ? (
            <button
              className="btn btn--primary btn--wide"
              type="button"
              onClick={() => void submit()}
              disabled={stage.name === 'sending'}
            >
              {stage.name === 'sending' ? 'Sending' : `Send: ${SEVERITY_WORD[level].toLowerCase()}`}
            </button>
          ) : (
            <button className="btn btn--primary btn--wide" type="button" disabled>
              Choose how deep the water is
            </button>
          )}

          {pending.length > 0 && (
            <p className="report__queued" role="status">
              {pending.length === 1
                ? '1 report is waiting for signal and will send itself.'
                : `${pending.length} reports are waiting for signal and will send themselves.`}
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

/* The acknowledgement ------------------------------------------------------- */

function Acknowledgement({
  response,
  zoneLabel,
  onDone,
  onConfirmEdges,
}: {
  response: ReportResponse;
  zoneLabel: string;
  onDone: () => void;
  onConfirmEdges: () => void;
}) {
  const delivered = response.alerts.filter((a) => !('suppressedBy' in a) || a.suppressedBy === undefined);
  const heldByEdge = response.alerts.filter((a) => a.suppressedBy === 'inferred_edge');
  const heldBySwitch = response.alerts.filter((a) => a.suppressedBy === 'kill_switch');

  return (
    <div className="ack" role="status" aria-live="polite">
      <div className="ack__inner">
        <div className="ack__mark">
          <DepthGlyph level={response.level} size={34} />
          <span className="t17 medium">{SEVERITY_WORD[response.level]} in {zoneLabel}</span>
        </div>

        <h1 className="ack__headline">{headline(response, delivered.length)}</h1>
        <p className="ack__body">{explanation(response, delivered, heldByEdge, heldBySwitch)}</p>

        {delivered.length > 0 && (
          <div className="ack__list">
            {delivered.map((alert) => (
              <div className="ack__row" key={alert.id}>
                <DepthGlyph level={alert.level} size={26} />
                <span className="t17 expanded">{alert.targetZone}</span>
                <span className="ack__eta">
                  <span className="ack__eta-number expanded">{alert.etaMinutes}</span>
                  <span className="t13 muted"> min</span>
                </span>
              </div>
            ))}
          </div>
        )}

        {delivered.length === 0 && heldByEdge.length > 0 && (
          <div className="ack__list">
            {heldByEdge.map((alert) => (
              <div className="ack__row ack__row--held" key={alert.id}>
                <DepthGlyph level={alert.level} size={26} />
                <span className="t17 expanded">{alert.targetZone}</span>
                <span className="t13">not warned</span>
              </div>
            ))}
          </div>
        )}

        <div className="ack__actions">
          {/* Anything that stopped short of a delivery is fixable from the map,
              except a halted kill switch, which no resident can do anything about. */}
          {response.escalated && delivered.length === 0 && heldBySwitch.length === 0 && (
            <button className="btn btn--primary btn--wide" type="button" onClick={onConfirmEdges}>
              Check the connections below you
            </button>
          )}
          <button className="btn btn--wide" type="button" onClick={onDone}>
            File another report
          </button>
        </div>
      </div>
    </div>
  );
}

function headline(response: ReportResponse, deliveredCount: number): string {
  if (!response.escalated && !response.countedTowardQuorum) return 'Logged.';
  if (!response.escalated) return 'Logged. Your zone was already flagged.';
  if (deliveredCount > 0) {
    return deliveredCount === 1 ? '1 area was warned.' : `${deliveredCount} areas were warned.`;
  }
  return 'Your zone is now flagged.';
}

function explanation(
  response: ReportResponse,
  delivered: AlertView[],
  heldByEdge: AlertView[],
  heldBySwitch: AlertView[],
): string {
  if (!response.escalated && !response.countedTowardQuorum) {
    return 'One more report from another reporter in your zone will raise the alarm.';
  }
  if (!response.escalated) {
    return 'Your zone is already alerting at this level or worse, so nothing was sent again.';
  }
  if (heldBySwitch.length > 0 && delivered.length === 0) {
    return 'Nothing was sent downstream, because an admin has halted all alerts. Your report is on the record.';
  }
  if (response.alerts.length === 0) {
    // An escalation that produced no alerts at all: every way out of this zone is
    // blocked, or nothing downstream of it has been mapped yet.
    return 'Nobody downstream was warned. The system has no open way out of your zone, either because the drains below you are reported blocked or because nothing below you has been mapped yet.';
  }
  if (heldByEdge.length > 0 && delivered.length === 0) {
    // Never show the raw suppressedBy string. Explain it, and turn the system's
    // biggest limitation into its main call to action.
    return 'Nothing was sent downstream, because the connections below you are still unconfirmed. Confirming them takes one tap each.';
  }
  if (heldByEdge.length > 0) {
    return `Some areas below you were not warned, because the connections to them are still unconfirmed.`;
  }
  return 'The people downstream have been told, with the time the water is expected.';
}

function QueuedAcknowledgement({
  observedAt,
  pending,
  onDone,
}: {
  observedAt: string;
  pending: number;
  onDone: () => void;
}) {
  return (
    <div className="ack" role="status" aria-live="assertive">
      <div className="ack__inner">
        <h1 className="ack__headline">Not sent yet.</h1>
        <p className="ack__body">
          The network did not answer, so your report is saved on this phone and will send itself the
          moment you have signal. You can close the app. It keeps the time you saw the water, which
          was {clockTime(observedAt)}.
        </p>
        <p className="ack__body muted t15">
          {pending === 1 ? '1 report is waiting.' : `${pending} reports are waiting.`}
        </p>
        <div className="ack__actions">
          <button className="btn btn--wide" type="button" onClick={onDone}>
            Back to reporting
          </button>
        </div>
      </div>
    </div>
  );
}

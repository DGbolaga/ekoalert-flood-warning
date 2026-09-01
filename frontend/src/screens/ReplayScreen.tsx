import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import './ReplayScreen.css';
import { ApiError, isOffline, runReplay } from '../api/client';
import type { ReplayRequest, ReplayResponse } from '../api/types';
import { DepthGlyph, ClearGlyph } from '../components/DepthGlyph';
import { SEVERITY_WORD } from '../lib/severity';
import { clockTime, dayAndClock } from '../lib/time';
import { useLive } from '../state/live';

const SAMPLE = `{
  "reports": [
    { "zoneId": "Z01", "reporterId": 1, "level": "IMPASSABLE", "observedAt": "2025-07-08T06:00:00Z" },
    { "zoneId": "Z01", "reporterId": 2, "level": "IMPASSABLE", "observedAt": "2025-07-08T06:20:00Z" },
    { "zoneId": "Z04", "reporterId": 3, "level": "KNEE",       "observedAt": "2025-07-08T07:10:00Z" },
    { "zoneId": "Z04", "reporterId": 4, "level": "IMPASSABLE", "observedAt": "2025-07-08T07:25:00Z" }
  ]
}`;

export function ReplayScreen() {
  const { zoneById } = useLive();
  const [text, setText] = useState(SAMPLE);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [result, setResult] = useState<ReplayResponse | undefined>();

  const label = (id: string) => zoneById.get(id)?.displayName ?? id;

  const run = async () => {
    let body: ReplayRequest;
    try {
      body = JSON.parse(text) as ReplayRequest;
    } catch {
      setError('That is not valid JSON.');
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      setResult(await runReplay(body));
    } catch (err) {
      if (isOffline(err)) setError('That did not reach the server.');
      else if (err instanceof ApiError) setError(err.message);
      else setError('The replay did not run.');
    } finally {
      setBusy(false);
    }
  };

  const loadFile = async (file: File) => {
    setText(await file.text());
    setResult(undefined);
  };

  const timeline = useMemo(() => buildTimeline(result), [result]);

  return (
    <div className="screen">
      <div className="wrap">
        <div className="replay__head">
          <h1 className="t22">Replay</h1>
          <span className="spacer" />
          <Link className="t13 muted" to="/admin">
            Admin
          </Link>
        </div>

        <p className="t15 muted" style={{ paddingBottom: 14 }}>
          Nothing is written and nothing is sent. The kill switch is ignored. The same request always
          gives the same answer, so a run can be compared against what actually happened.
        </p>

        <textarea
          className="replay__input"
          value={text}
          onChange={(e) => setText(e.target.value)}
          spellCheck={false}
          aria-label="Scenario as JSON"
        />

        <div className="row" style={{ marginTop: 12 }}>
          <button className="btn btn--primary" type="button" onClick={() => void run()} disabled={busy}>
            {busy ? 'Running' : 'Run this scenario'}
          </button>
          <label className="btn btn--quiet" style={{ cursor: 'pointer' }}>
            Load a file
            <input
              type="file"
              accept="application/json,.json"
              className="visually-hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void loadFile(file);
              }}
            />
          </label>
        </div>

        {error && (
          <p className="t15" role="alert" style={{ marginTop: 12 }}>
            {error}
          </p>
        )}

        {result && (
          <>
            <div className="replay__lead">
              <span className="replay__lead-number expanded">
                {result.summary.suppressedByUnconfirmedPath}
              </span>
              <p className="replay__lead-text">
                {result.summary.suppressedByUnconfirmedPath === 1
                  ? 'warning would not have gone out, because nobody had confirmed the connections it had to travel through.'
                  : 'warnings would not have gone out, because nobody had confirmed the connections they had to travel through.'}
              </p>
            </div>

            <div className="replay__stats">
              <Stat n={result.summary.reportsReplayed} label="reports replayed" />
              <Stat n={result.summary.zonesEscalated} label="zones escalated" />
              <Stat n={result.summary.alertsPredicted} label="alerts predicted" />
              <Stat n={result.summary.alertsDeliverable} label="would have been sent" />
            </div>

            {result.summary.firstReportAt && (
              <p className="t13 muted" style={{ marginTop: 10 }}>
                From {dayAndClock(result.summary.firstReportAt)}
                {result.summary.lastReportAt ? ` to ${dayAndClock(result.summary.lastReportAt)}` : ''}
              </p>
            )}

            <section className="replay__section">
              <h2>Zones that escalated</h2>
              {result.escalations.length === 0 ? (
                <p className="t15 muted" style={{ padding: '14px 0' }}>
                  No zone reached a quorum in this scenario.
                </p>
              ) : (
                result.escalations.map((e) => (
                  <div className="admin__row" key={`${e.zoneId}-${e.at}`}>
                    <DepthGlyph level={e.level} size={24} />
                    <span className="t17 expanded">{label(e.zoneId)}</span>
                    <span className="t15 muted">{SEVERITY_WORD[e.level]}</span>
                    <span className="spacer" />
                    <span className="t15">{clockTime(e.at)}</span>
                    <span className="t13 muted">
                      {e.alertsProduced === 1 ? '1 alert' : `${e.alertsProduced} alerts`}
                    </span>
                  </div>
                ))
              )}
            </section>

            <section className="replay__section">
              <h2>Predicted arrivals</h2>
              {timeline.length === 0 ? (
                <p className="t15 muted" style={{ padding: '14px 0' }}>
                  Nothing was predicted to arrive anywhere.
                </p>
              ) : (
                <div className="tl" style={{ marginTop: 8 }}>
                  {timeline.map((row) => (
                    <div
                      className={`tl__row${row.alert.wouldDeliver ? '' : ' tl__row--held'}`}
                      key={`${row.alert.originZone}-${row.alert.targetZone}-${row.alert.expectedArrival}`}
                    >
                      <span className="tl__time expanded">{clockTime(row.alert.expectedArrival)}</span>
                      <span className={`tl__pip${row.alert.wouldDeliver ? '' : ' tl__pip--held'}`} />
                      <div className="tl__line">
                        <DepthGlyph level={row.alert.level} size={24} />
                        <span className="t17 expanded">{label(row.alert.targetZone)}</span>
                        <span className="t13 muted">
                          from {label(row.alert.originZone)} · {row.alert.hops === 1 ? '1 hop' : `${row.alert.hops} hops`}
                        </span>
                      </div>
                      {/* Bar length is the wait between the report and the water. */}
                      <div className="tl__bar" style={{ width: `${row.width}%` }} />
                      <p className="tl__note">
                        {row.alert.wouldDeliver
                          ? `Warned ${row.alert.etaMinutes} minutes ahead.`
                          : `Held back. The path was not confirmed, so nobody in ${label(row.alert.targetZone)} would have heard.`}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section className="replay__section">
              <h2>All-clears</h2>
              {result.allClears.length === 0 ? (
                <p className="t15 muted" style={{ padding: '14px 0' }}>
                  Nothing cleared inside the window replayed.
                </p>
              ) : (
                result.allClears.map((c) => (
                  <div className="admin__row" key={`${c.originZone}-${c.targetZone}-${c.at}`}>
                    <ClearGlyph size={24} />
                    <span className="t17 expanded">{label(c.targetZone)}</span>
                    <span className="t13 muted">from {label(c.originZone)}</span>
                    <span className="spacer" />
                    <span className="t15">{clockTime(c.at)}</span>
                  </div>
                ))
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
}

function Stat({ n, label }: { n: number; label: string }) {
  return (
    <div>
      <span className="replay__stat-number expanded">{n}</span>
      <span className="t13 muted">{label}</span>
    </div>
  );
}

interface TimelineRow {
  alert: ReplayResponse['alerts'][number];
  width: number;
}

function buildTimeline(result: ReplayResponse | undefined): TimelineRow[] {
  if (!result || result.alerts.length === 0) return [];
  const sorted = [...result.alerts].sort(
    (a, b) => new Date(a.expectedArrival).getTime() - new Date(b.expectedArrival).getTime(),
  );
  const longest = Math.max(...sorted.map((a) => a.etaMinutes), 1);
  return sorted.map((alert) => ({
    alert,
    width: Math.max(6, Math.round((alert.etaMinutes / longest) * 100)),
  }));
}

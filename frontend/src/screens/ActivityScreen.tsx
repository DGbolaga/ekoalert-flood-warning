import { useNavigate } from 'react-router-dom';
import './ActivityScreen.css';
import { DepthGlyph, ClearGlyph } from '../components/DepthGlyph';
import { useLive, type ActivityItem } from '../state/live';
import { SEVERITY_WORD, isSeverity } from '../lib/severity';
import { clockTime, relativeTime } from '../lib/time';
import type { AlertView } from '../api/types';

export function ActivityScreen() {
  const { activity, stream, zoneById } = useLive();
  const navigate = useNavigate();
  const label = (id: string) => zoneById.get(id)?.displayName ?? id;

  return (
    <div className="screen">
      <div className="wrap">
        <div className="act__head">
          <h1 className="t22">Activity</h1>
          <span className="spacer" />
          {stream === 'down' && <span className="t13 muted">Disconnected</span>}
          {stream === 'connecting' && <span className="t13 muted">Connecting</span>}
        </div>

        {activity.length === 0 ? (
          <p className="act__empty">
            Nothing has happened since you opened this. Alerts and all-clears appear here as they
            are sent.
          </p>
        ) : (
          <div className="act__list">
            {activity.map((item) => {
              if (item.kind === 'alert') {
                return (
                  <AlertRow
                    key={item.key}
                    alert={item.alert}
                    label={label}
                    onOpenEdge={() => navigate(`/?zone=${encodeURIComponent(item.alert.originZone)}`)}
                  />
                );
              }
              if (item.kind === 'all-clear') {
                return <AllClearRow key={item.key} item={item} label={label} />;
              }
              return <ZoneStatusRow key={item.key} item={item} label={label} />;
            })}
          </div>
        )}
      </div>
    </div>
  );
}

function AlertRow({
  alert,
  label,
  onOpenEdge,
}: {
  alert: AlertView;
  label: (id: string) => string;
  onOpenEdge: () => void;
}) {
  const held = alert.suppressedBy !== undefined;

  return (
    <div className={`act__item${held ? ' act__item--held' : ''}`}>
      <DepthGlyph level={alert.level} size={30} />
      <div className="act__body">
        <div className="act__zones">
          <span className="t17 expanded">{label(alert.targetZone)}</span>
          <span className="t13 muted">from {label(alert.originZone)}</span>
        </div>
        <div className="t13 muted">
          {SEVERITY_WORD[alert.level]} expected · {clockTime(alert.firedAt)} · {relativeTime(alert.firedAt)}
        </div>

        {alert.suppressedBy === 'inferred_edge' && (
          <>
            {/* A suppressed alert is the system explaining itself, not a failure. */}
            <div className="act__reason">
              Not sent. The path from {label(alert.originZone)} runs through a connection nobody has
              confirmed.
            </div>
            <button className="act__link" type="button" onClick={onOpenEdge}>
              Go and confirm it
            </button>
          </>
        )}

        {alert.suppressedBy === 'kill_switch' && (
          <div className="act__reason">Not sent. An admin has halted all alerts.</div>
        )}
      </div>

      <div className="act__eta">
        <span className="act__eta-number expanded">{alert.etaMinutes}</span>
        <span className="t13 muted">min</span>
      </div>
    </div>
  );
}

/* What a zone itself is reporting, as distinct from a warning sent onward. */
function ZoneStatusRow({
  item,
  label,
}: {
  item: Extract<ActivityItem, { kind: 'zone-status' }>;
  label: (id: string) => string;
}) {
  const level = item.event.level;
  const cleared = level === 'CLEAR' || !isSeverity(level);

  return (
    <div className="act__item">
      {cleared ? <ClearGlyph size={30} /> : <DepthGlyph level={level} size={30} />}
      <div className="act__body">
        <div className="act__zones">
          <span className="t17 expanded">{label(item.event.zoneId)}</span>
        </div>
        <div className="t13 muted">
          {cleared ? 'Water has gone down' : `${SEVERITY_WORD[level]} reported here`} ·{' '}
          {clockTime(item.event.at)} · {relativeTime(item.event.at)}
        </div>
      </div>
    </div>
  );
}

/* An all-clear carries the same weight as an alert. A person who never sees one
   stops believing the warnings. */
function AllClearRow({
  item,
  label,
}: {
  item: Extract<ActivityItem, { kind: 'all-clear' }>;
  label: (id: string) => string;
}) {
  return (
    <div className="act__item">
      <ClearGlyph size={30} />
      <div className="act__body">
        <div className="act__zones">
          <span className="t17 expanded">{label(item.event.targetZone)}</span>
          <span className="t13 muted">from {label(item.event.originZone)}</span>
        </div>
        <div className="t13 muted">
          All clear · {clockTime(item.event.at)} · {relativeTime(item.event.at)}
        </div>
        <div className="act__reason">The water has gone down. Nothing more is expected here.</div>
      </div>
    </div>
  );
}

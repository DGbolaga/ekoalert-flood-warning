import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './ActivityScreen.css';
import { DepthGlyph, ClearGlyph } from '../components/DepthGlyph';
import { useLive, type ActivityItem } from '../state/live';
import { SEVERITY_WORD, isSeverity } from '../lib/severity';
import { clockTime, relativeTime } from '../lib/time';
import { readSubscriptions, SUBS_EVENT } from '../lib/device';
import type { AlertView } from '../api/types';

/**
 * Which zone a row is about, from the reader's point of view. A warning belongs
 * to the place it is warning, not the place the water started, because the
 * person who asked to hear about Ketu wants Ketu's warnings however far
 * upstream they were triggered.
 */
function subjectZone(item: ActivityItem): string {
  if (item.kind === 'alert') return item.alert.targetZone;
  if (item.kind === 'all-clear') return item.event.targetZone;
  return item.event.zoneId;
}

/** Subscriptions live in localStorage and can change while this screen is open. */
function useSubscribedZones(): Set<string> {
  const [zones, setZones] = useState<Set<string>>(readSubscriptions);
  useEffect(() => {
    const sync = () => setZones(readSubscriptions());
    window.addEventListener(SUBS_EVENT, sync);
    // Another tab on the same device counts too.
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(SUBS_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);
  return zones;
}

export function ActivityScreen() {
  const { activity, stream, zoneById } = useLive();
  const navigate = useNavigate();
  const label = (id: string) => zoneById.get(id)?.displayName ?? id;

  const subscribed = useSubscribedZones();
  const [mineOnly, setMineOnly] = useState(true);
  // Someone who has asked for nothing has no personal feed to show, so the
  // choice is not offered and the whole corridor is.
  const filtering = subscribed.size > 0 && mineOnly;
  const shown = filtering ? activity.filter((i) => subscribed.has(subjectZone(i))) : activity;
  const hidden = activity.length - shown.length;

  return (
    <div className="screen">
      <div className="wrap">
        <div className="act__head">
          <h1 className="t22">Activity</h1>
          <span className="spacer" />
          {stream === 'down' && <span className="t13 muted">Disconnected</span>}
          {stream === 'connecting' && <span className="t13 muted">Connecting</span>}
        </div>

        {subscribed.size > 0 && (
          <div className="act__scope" role="group" aria-label="Which areas to show">
            <button
              type="button"
              className={`act__scope-btn${mineOnly ? ' act__scope-btn--on' : ''}`}
              aria-pressed={mineOnly}
              onClick={() => setMineOnly(true)}
            >
              My areas ({subscribed.size})
            </button>
            <button
              type="button"
              className={`act__scope-btn${mineOnly ? '' : ' act__scope-btn--on'}`}
              aria-pressed={!mineOnly}
              onClick={() => setMineOnly(false)}
            >
              Everywhere
            </button>
          </div>
        )}

        {shown.length === 0 ? (
          <p className="act__empty">
            {activity.length === 0
              ? 'Nothing has happened since you opened this. Alerts and all-clears appear here as they are sent.'
              : `Nothing in your areas yet. ${hidden} other ${hidden === 1 ? 'thing has' : 'things have'} happened on the corridor.`}
          </p>
        ) : (
          <div className="act__list">
            {shown.map((item) => {
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

        {filtering && hidden > 0 && shown.length > 0 && (
          <p className="act__more t13 muted">
            {hidden} more elsewhere on the corridor.{' '}
            <button className="act__link" type="button" onClick={() => setMineOnly(false)}>
              Show everywhere
            </button>
          </p>
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

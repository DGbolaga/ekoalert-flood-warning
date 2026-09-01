import { useCallback, useEffect, useState } from 'react';
import './ZoneSheet.css';
import { Sheet } from '../components/Sheet';
import { ConnectionRow } from '../components/ConnectionRow';
import { DepthGlyph, ClearGlyph } from '../components/DepthGlyph';
import { ApiError, getZone, isOffline, subscribe } from '../api/client';
import type { CorrectionResponse, EdgeView, ZoneDetail } from '../api/types';
import { SEVERITY_HEX, SEVERITY_WASH, SEVERITY_WORD } from '../lib/severity';
import { relativeTime } from '../lib/time';
import { deviceAddress, readSubscriptions, rememberSubscription } from '../lib/device';
import { useAuth } from '../state/auth';
import { useLive } from '../state/live';

export function ZoneSheet({
  zoneId,
  focusEdgeId,
  onClose,
  onNeedsSignIn,
  onEdgeFlipped,
}: {
  zoneId?: string;
  focusEdgeId?: number;
  onClose: () => void;
  onNeedsSignIn: () => void;
  onEdgeFlipped: (edgeId: number) => void;
}) {
  const { isReporter } = useAuth();
  const { putEdge, zoneById } = useLive();
  const [detail, setDetail] = useState<ZoneDetail | undefined>();
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'error'>('loading');
  const [message, setMessage] = useState<string | undefined>();

  useEffect(() => {
    if (!zoneId) return;
    const controller = new AbortController();
    setState('loading');
    setDetail(undefined);
    setMessage(undefined);
    getZone(zoneId, controller.signal)
      .then((found) => {
        if (controller.signal.aborted) return;
        if (!found) {
          setState('missing');
          return;
        }
        setDetail(found);
        setState('ready');
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setMessage(err instanceof ApiError ? err.message : 'This zone could not be loaded.');
        setState('error');
      });
    return () => controller.abort();
  }, [zoneId]);

  const applyCorrection = useCallback(
    (response: CorrectionResponse) => {
      const edge = response.edge;
      if (!edge) return;
      putEdge(edge);
      setDetail((current) => {
        if (!current) return current;
        const swap = (list: EdgeView[]) =>
          list.map((e) =>
            e.id === edge.id
              ? {
                  ...edge,
                  // Vote counts only exist on the zone detail, so carry the fresh
                  // number across from the correction rather than losing it.
                  confirmations:
                    response.action === 'confirm' ? response.distinctVoices : e.confirmations,
                  rejections: response.action === 'reject' ? response.distinctVoices : e.rejections,
                }
              : e,
          );
        return { ...current, outbound: swap(current.outbound), inbound: swap(current.inbound) };
      });
      if (response.thresholdMet) onEdgeFlipped(edge.id);
    },
    [putEdge, onEdgeFlipped],
  );

  const zone = detail?.zone ?? (zoneId ? zoneById.get(zoneId) : undefined);

  return (
    <Sheet open={zoneId !== undefined} onClose={onClose} labelledBy="zone-sheet-title">
      {state === 'loading' && !zone && <ZoneSkeleton />}

      {zone && (
        <>
          <div className="zs__head">
            <div className="zs__name">
              {/* Bound to displayName, which falls back to the id. Never invented. */}
              <h2 id="zone-sheet-title" className="t22 expanded">
                {zone.displayName}
              </h2>
            </div>
            <p className="t15 muted">{zone.corridor}</p>
            {zone.landmark && <p className="t15">{zone.landmark}</p>}
          </div>

          <ZoneStatus zone={zone} />

          {zone.needsFieldNaming && (
            <p className="zs__flag">
              This zone has no name on any map. It is waiting on somebody to go and name it.
            </p>
          )}

          <SubscribeControl zoneId={zone.id} />

          {state === 'ready' && detail && (
            <>
              <Connections
                title="Water goes to"
                edges={detail.outbound}
                direction="outbound"
                nameOf={(edge) => zoneById.get(edge.toZone)?.displayName ?? edge.toZone}
                empty="Nothing downstream has been mapped from here yet."
                canCorrect={isReporter}
                onNeedsSignIn={onNeedsSignIn}
                onCorrected={applyCorrection}
                focusEdgeId={focusEdgeId}
              />
              <Connections
                title="Water comes from"
                edges={detail.inbound}
                direction="inbound"
                nameOf={(edge) => zoneById.get(edge.fromZone)?.displayName ?? edge.fromZone}
                empty="Nothing upstream has been mapped into here yet."
                canCorrect={isReporter}
                onNeedsSignIn={onNeedsSignIn}
                onCorrected={applyCorrection}
                focusEdgeId={focusEdgeId}
              />
            </>
          )}

          {state === 'loading' && <div className="zs__section"><ZoneSkeleton /></div>}
        </>
      )}

      {state === 'missing' && (
        <p className="zs__empty">That zone is not on the map any more.</p>
      )}
      {state === 'error' && (
        <p className="zs__empty" role="alert">
          {message}
        </p>
      )}
    </Sheet>
  );
}

function ZoneStatus({ zone }: { zone: { status: { active: boolean; level?: import('../api/types').Severity; escalatedAt?: string; clearedAt?: string } } }) {
  const { status } = zone;
  if (status.active && status.level) {
    return (
      <div
        className="zs__status zs__status--active"
        style={{ ['--depth' as string]: SEVERITY_HEX[status.level], ['--wash' as string]: SEVERITY_WASH[status.level] }}
      >
        <DepthGlyph level={status.level} size={28} />
        <div className="zs__status-text">
          <div className="t17 medium">{SEVERITY_WORD[status.level]}</div>
          {status.escalatedAt && (
            <div className="t13 muted">Escalated {relativeTime(status.escalatedAt)}</div>
          )}
        </div>
      </div>
    );
  }
  return (
    <div className="zs__status">
      <ClearGlyph size={28} />
      <div className="zs__status-text">
        <div className="t17 medium">No water reported</div>
        {status.clearedAt && <div className="t13 muted">Cleared {relativeTime(status.clearedAt)}</div>}
      </div>
    </div>
  );
}

function Connections({
  title,
  edges,
  direction,
  nameOf,
  empty,
  canCorrect,
  onNeedsSignIn,
  onCorrected,
  focusEdgeId,
}: {
  title: string;
  edges: EdgeView[];
  direction: 'outbound' | 'inbound';
  nameOf: (edge: EdgeView) => string;
  empty: string;
  canCorrect: boolean;
  onNeedsSignIn: () => void;
  onCorrected: (response: CorrectionResponse) => void;
  focusEdgeId?: number;
}) {
  return (
    <section className="zs__section">
      <h3 className="zs__section-title">{title}</h3>
      {edges.length === 0 ? (
        <p className="zs__empty">{empty}</p>
      ) : (
        edges.map((edge) => (
          <ConnectionRow
            key={edge.id}
            edge={edge}
            otherLabel={nameOf(edge)}
            direction={direction}
            canCorrect={canCorrect}
            onNeedsSignIn={onNeedsSignIn}
            onCorrected={onCorrected}
            highlight={edge.id === focusEdgeId}
          />
        ))
      )}
    </section>
  );
}

function SubscribeControl({ zoneId }: { zoneId: string }) {
  const [done, setDone] = useState(() => readSubscriptions().has(zoneId));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();

  useEffect(() => {
    setDone(readSubscriptions().has(zoneId));
    setError(undefined);
  }, [zoneId]);

  // Repeat calls return the existing row rather than creating a duplicate, so
  // the control can be optimistic.
  const go = async () => {
    setBusy(true);
    setError(undefined);
    setDone(true);
    try {
      await subscribe(zoneId, deviceAddress());
      rememberSubscription(zoneId);
    } catch (err) {
      setDone(false);
      if (isOffline(err)) setError('That did not reach the server. Try again when you have signal.');
      else if (err instanceof ApiError) setError(err.message);
      else setError('That did not go through.');
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <p className="zs__subscribe t15 muted" role="status">
        You will be warned about this area.
      </p>
    );
  }

  return (
    <div className="zs__subscribe">
      <button className="btn btn--wide" type="button" onClick={() => void go()} disabled={busy}>
        Warn me about this area
      </button>
      {error && (
        <p className="t13" role="alert" style={{ marginTop: 8 }}>
          {error}
        </p>
      )}
    </div>
  );
}

function ZoneSkeleton() {
  return (
    <div className="stack" style={{ gap: 10, paddingTop: 6 }}>
      <div className="skel" style={{ height: 26, width: '40%' }} />
      <div className="skel" style={{ height: 16, width: '55%' }} />
      <div className="skel" style={{ height: 60, width: '100%' }} />
      <div className="skel" style={{ height: 60, width: '100%' }} />
    </div>
  );
}

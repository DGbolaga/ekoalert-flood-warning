import { useState } from 'react';
import './ConnectionRow.css';
import { ApiError, confirmEdge, isOffline, rejectEdge } from '../api/client';
import type { CorrectionResponse, EdgeView } from '../api/types';
import { EdgeMark, edgeStateWord } from './EdgeMark';
import { learnThreshold, voicesPhrase } from '../lib/threshold';
import { minutesWord } from '../lib/time';

export interface ConnectionRowProps {
  edge: EdgeView;
  /** The zone at the far end, already resolved to a display name. */
  otherLabel: string;
  direction: 'outbound' | 'inbound';
  canCorrect: boolean;
  onNeedsSignIn: () => void;
  onCorrected: (response: CorrectionResponse) => void;
  highlight?: boolean;
}

/** The mark carries plain inferred already. A word is only worth a line when the
    edge is blocked, rejected, or has actually been confirmed. */
function needsStateWord(edge: EdgeView): boolean {
  return edge.blocked || edge.confidence !== 'INFERRED';
}

export function ConnectionRow({
  edge,
  otherLabel,
  direction,
  canCorrect,
  onNeedsSignIn,
  onCorrected,
  highlight,
}: ConnectionRowProps) {
  const [busy, setBusy] = useState<'confirm' | 'reject' | undefined>();
  const [error, setError] = useState<string | undefined>();
  const [flip, setFlip] = useState<string | undefined>();

  const confirmations = edge.confirmations ?? 0;
  const rejections = edge.rejections ?? 0;

  const send = async (action: 'confirm' | 'reject') => {
    // The control is visible whether or not he is signed in. Seeing that
    // correction is possible is half of what makes anyone do it.
    if (!canCorrect) {
      onNeedsSignIn();
      return;
    }
    setBusy(action);
    setError(undefined);
    setFlip(undefined);
    try {
      const response = action === 'confirm' ? await confirmEdge(edge.id) : await rejectEdge(edge.id);
      learnThreshold(response.threshold);
      if (response.thresholdMet) {
        setFlip(
          action === 'confirm'
            ? `Confirmed. Water reported in ${response.fromZone} will now warn ${response.toZone}.`
            : `Rejected. This connection is no longer drawn on the map.`,
        );
      }
      onCorrected(response);
    } catch (err) {
      if (isOffline(err)) setError('That did not reach the server. Try again when you have signal.');
      else if (err instanceof ApiError) setError(err.message);
      else setError('That did not go through.');
    } finally {
      setBusy(undefined);
    }
  };

  return (
    <div className="conn" style={highlight ? { boxShadow: 'inset 3px 0 0 var(--ink)', paddingLeft: 12 } : undefined}>
      <div className="conn__head">
        <EdgeMark edge={edge} />
        <div className="conn__names">
          <div className="t17 medium">{otherLabel}</div>
          {/* The section heading already says which way this goes, and the mark
              already says whether it is confirmed, so the only line worth
              spending here is how close the edge is to flipping. */}
          <div className="conn__votes t13">
            {needsStateWord(edge) && <span>{edgeStateWord(edge)}</span>}
            <span>{voicesPhrase(confirmations, 'confirm')}</span>
            {rejections > 0 && <span>{voicesPhrase(rejections, 'reject')}</span>}
          </div>
        </div>
        <div className="conn__eta t15">{minutesWord(edge.travelMinutes)}</div>
      </div>

      <div className="conn__actions">
        <button
          type="button"
          className={`conn__tap${edge.confidence === 'CONFIRMED' ? ' conn__tap--on' : ''}`}
          onClick={() => void send('confirm')}
          disabled={busy !== undefined}
          aria-label={
            direction === 'outbound'
              ? `Yes, water from here reaches ${otherLabel}`
              : `Yes, water from ${otherLabel} reaches here`
          }
        >
          {busy === 'confirm' ? 'Sending' : 'Yes, water goes this way'}
        </button>
        <button
          type="button"
          className={`conn__tap${edge.confidence === 'REJECTED' ? ' conn__tap--on' : ''}`}
          onClick={() => void send('reject')}
          disabled={busy !== undefined}
          aria-label={
            direction === 'outbound'
              ? `No, water from here does not reach ${otherLabel}`
              : `No, water from ${otherLabel} does not reach here`
          }
        >
          {busy === 'reject' ? 'Sending' : 'No, it does not'}
        </button>
      </div>

      {flip && (
        <p className="conn__flip" role="status">
          {flip}
        </p>
      )}
      {error && (
        <p className="conn__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

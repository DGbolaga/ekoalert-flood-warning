import type { EdgeView } from '../api/types';

/**
 * The same three states the map draws, at row scale, so the vocabulary a person
 * learns on the map carries straight into the sheet.
 */
export function EdgeMark({ edge }: { edge: EdgeView }) {
  const confirmed = edge.confidence === 'CONFIRMED';
  const rejected = edge.confidence === 'REJECTED';
  const stroke = confirmed ? 'var(--ink)' : 'var(--mist)';

  return (
    <svg
      width="46"
      height="16"
      viewBox="0 0 46 16"
      aria-hidden="true"
      focusable="false"
      style={{ flex: 'none', opacity: rejected ? 0.35 : 1 }}
    >
      <line
        x1="1"
        y1="8"
        x2={confirmed ? 36 : 45}
        y2="8"
        stroke={stroke}
        strokeWidth={confirmed ? 2.5 : 2}
        strokeDasharray={confirmed ? undefined : '5 5'}
        strokeLinecap="round"
        opacity={confirmed ? (edge.blocked ? 0.5 : 1) : 0.6}
      />
      {confirmed && (
        <path d="M36 3 L45 8 L36 13 Z" fill={stroke} opacity={edge.blocked ? 0.5 : 1} />
      )}
      {edge.blocked && <rect x="20.5" y="1" width="2.5" height="14" rx="1.25" fill={stroke} />}
      {rejected && (
        <line x1="16" y1="2" x2="30" y2="14" stroke="var(--slate)" strokeWidth="1.75" strokeLinecap="round" />
      )}
    </svg>
  );
}

export function edgeStateWord(edge: EdgeView): string {
  if (edge.confidence === 'REJECTED') return 'Residents rejected this';
  if (edge.confidence === 'CONFIRMED') {
    return edge.blocked ? 'Confirmed, drain blocked' : 'Confirmed';
  }
  return edge.blocked ? 'Not yet confirmed, drain blocked' : 'Not yet confirmed';
}

import type { Severity } from '../api/types';
import { SEVERITY_HEX, SEVERITY_WORD } from '../lib/severity';

/**
 * One glyph, used at every scale: map marker, report button, alert row, sheet.
 *
 * A fixed baseline with water standing against it. Ankle fills a third, knee two
 * thirds, impassable the whole vessel. Never used alone: the word travels with
 * it everywhere, so it survives colour blindness, glare and a cheap LCD.
 */
export function DepthGlyph({
  level,
  size = 24,
  title,
}: {
  level: Severity;
  size?: number;
  title?: string;
}) {
  const fill = SEVERITY_HEX[level];
  const heights: Record<Severity, number> = { ANKLE: 6, KNEE: 12, IMPASSABLE: 18 };
  const water = heights[level];
  const label = title ?? SEVERITY_WORD[level];

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      role="img"
      aria-label={label}
      focusable="false"
      style={{ display: 'block', flex: 'none' }}
    >
      {/* the vessel */}
      <rect
        x="4.5"
        y="2.5"
        width="15"
        height="19"
        rx="1.5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        opacity="0.45"
      />
      {/* water standing against the baseline */}
      <rect x="5.25" y={21.25 - water} width="13.5" height={water} rx="0.75" fill={fill} />
      {/* the baseline itself, always drawn at full strength */}
      <rect x="3" y="21.25" width="18" height="1.75" rx="0.875" fill="currentColor" />
    </svg>
  );
}

/** The same shape with no water in it, for a zone that is clear. */
export function ClearGlyph({ size = 24, title = 'Clear' }: { size?: number; title?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      role="img"
      aria-label={title}
      focusable="false"
      style={{ display: 'block', flex: 'none' }}
    >
      <rect
        x="4.5"
        y="2.5"
        width="15"
        height="19"
        rx="1.5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        opacity="0.3"
      />
      <rect x="3" y="21.25" width="18" height="1.75" rx="0.875" fill="currentColor" opacity="0.55" />
    </svg>
  );
}

/** Markup form, for Leaflet div icons which take an HTML string. */
export function depthGlyphMarkup(level: Severity | undefined, size: number, strokeColor: string): string {
  const heights: Record<Severity, number> = { ANKLE: 6, KNEE: 12, IMPASSABLE: 18 };
  const water = level ? heights[level] : 0;
  const fill = level ? SEVERITY_HEX[level] : 'none';
  const waterRect = level
    ? `<rect x="5.25" y="${21.25 - water}" width="13.5" height="${water}" rx="0.75" fill="${fill}"/>`
    : '';
  return [
    `<svg width="${size}" height="${size}" viewBox="0 0 24 24" aria-hidden="true" focusable="false">`,
    `<rect x="4.5" y="2.5" width="15" height="19" rx="1.5" fill="none" stroke="${strokeColor}" stroke-width="1.5" opacity="0.45"/>`,
    waterRect,
    `<rect x="3" y="21.25" width="18" height="1.75" rx="0.875" fill="${strokeColor}"/>`,
    '</svg>',
  ].join('');
}

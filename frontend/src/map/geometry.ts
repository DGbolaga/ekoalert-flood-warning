import L from 'leaflet';
import type { EdgeView, ZoneSummary } from '../api/types';

export interface EdgeGeometry {
  from: ZoneSummary;
  to: ZoneSummary;
  /** Screen-space bearing in degrees, clockwise from east. Zoom invariant. */
  angle: number;
  midpoint: L.LatLngExpression;
  /** A point along the line where the arrowhead sits, short of the target marker. */
  head: L.LatLngExpression;
}

const ARROW_AT = 0.78;

/**
 * Web Mercator scales uniformly, so the angle between two projected points does
 * not change with zoom. Computing it once means the arrowheads and blockage bars
 * never have to be re-rotated as the user zooms.
 */
export function edgeGeometry(
  edge: EdgeView,
  zoneById: Map<string, ZoneSummary>,
): EdgeGeometry | undefined {
  const from = zoneById.get(edge.fromZone);
  const to = zoneById.get(edge.toZone);
  if (!from || !to) return undefined;

  const a = L.Projection.SphericalMercator.project(L.latLng(from.lat, from.lng));
  const b = L.Projection.SphericalMercator.project(L.latLng(to.lat, to.lng));

  // Projected y grows northward while screen y grows downward, so the vertical
  // component is negated to give an angle that CSS rotate() can use directly.
  const angle = (Math.atan2(-(b.y - a.y), b.x - a.x) * 180) / Math.PI;

  const lerp = (t: number): L.LatLngExpression => [
    from.lat + (to.lat - from.lat) * t,
    from.lng + (to.lng - from.lng) * t,
  ];

  return { from, to, angle, midpoint: lerp(0.5), head: lerp(ARROW_AT) };
}

export function boundsOf(zones: ZoneSummary[]): L.LatLngBounds | undefined {
  if (zones.length === 0) return undefined;
  return L.latLngBounds(zones.map((z) => [z.lat, z.lng] as [number, number]));
}

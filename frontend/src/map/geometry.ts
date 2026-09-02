import L from 'leaflet';
import type { EdgeView, ZoneSummary } from '../api/types';

/**
 * How far the arc bows away from the straight line between two zones, as a
 * fraction of the distance between them.
 *
 * The bow is the whole point. A straight stroke laid over a street basemap reads
 * as a route along the ground, and an edge is not a route: it claims only that
 * water seen in one zone tends to reach another about N minutes later. A curve
 * cannot be mistaken for a street.
 */
const BEND = 0.22;

/** Where along the curve the arrowhead sits, short of the target marker. */
const ARROW_AT = 0.78;

/** Points sampled along the curve. Enough to look smooth at any zoom we allow. */
const ARC_STEPS = 24;

export interface EdgeGeometry {
  from: ZoneSummary;
  to: ZoneSummary;
  /** The curve itself, ready to hand to L.polyline. */
  points: L.LatLngExpression[];
  /** Apex of the curve, where the travel time label hangs. */
  apex: L.LatLngExpression;
  /** Arrowhead position and the screen angle of the curve there. */
  head: L.LatLngExpression;
  headAngle: number;
  /** Midpoint and screen angle, for the bar drawn across a blocked channel. */
  midpoint: L.LatLngExpression;
  midAngle: number;
}

type Point = { x: number; y: number };

const project = (lat: number, lng: number): Point =>
  L.Projection.SphericalMercator.project(L.latLng(lat, lng));

const unproject = (p: Point): L.LatLngExpression => {
  const ll = L.Projection.SphericalMercator.unproject(L.point(p.x, p.y));
  return [ll.lat, ll.lng];
};

/** Quadratic Bezier. */
function bezier(a: Point, c: Point, b: Point, t: number): Point {
  const u = 1 - t;
  return {
    x: u * u * a.x + 2 * u * t * c.x + t * t * b.x,
    y: u * u * a.y + 2 * u * t * c.y + t * t * b.y,
  };
}

/** Its derivative, which gives the direction the curve is travelling at t. */
function bezierTangent(a: Point, c: Point, b: Point, t: number): Point {
  const u = 1 - t;
  return {
    x: 2 * u * (c.x - a.x) + 2 * t * (b.x - c.x),
    y: 2 * u * (c.y - a.y) + 2 * t * (b.y - c.y),
  };
}

/**
 * Screen angle in degrees, clockwise from east, ready for a CSS rotate().
 *
 * Projected y grows northward while screen y grows downward, so the vertical
 * component is negated. Web Mercator scales uniformly, so this angle does not
 * change with zoom and never needs recomputing as the user zooms.
 */
function screenAngle(v: Point): number {
  return (Math.atan2(-v.y, v.x) * 180) / Math.PI;
}

export function edgeGeometry(
  edge: EdgeView,
  zoneById: Map<string, ZoneSummary>,
): EdgeGeometry | undefined {
  const from = zoneById.get(edge.fromZone);
  const to = zoneById.get(edge.toZone);
  if (!from || !to) return undefined;

  const a = project(from.lat, from.lng);
  const b = project(to.lat, to.lng);

  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const span = Math.hypot(dx, dy);

  // Two zones at the same coordinate would give no direction to bow away from.
  if (span === 0) return undefined;

  // Always bow to the same side of the direction of travel, so that a pair of
  // opposing edges never lands on top of itself and the bow itself reads as
  // which way the water is going.
  const control: Point = {
    x: a.x + dx / 2 + (-dy / span) * span * BEND,
    y: a.y + dy / 2 + (dx / span) * span * BEND,
  };

  const points: L.LatLngExpression[] = [];
  for (let i = 0; i <= ARC_STEPS; i += 1) {
    points.push(unproject(bezier(a, control, b, i / ARC_STEPS)));
  }

  return {
    from,
    to,
    points,
    apex: unproject(bezier(a, control, b, 0.5)),
    head: unproject(bezier(a, control, b, ARROW_AT)),
    headAngle: screenAngle(bezierTangent(a, control, b, ARROW_AT)),
    midpoint: unproject(bezier(a, control, b, 0.5)),
    midAngle: screenAngle(bezierTangent(a, control, b, 0.5)),
  };
}

/** Smallest and largest catchment radius we are willing to draw, in metres. */
const RADIUS_MIN = 150;
const RADIUS_MAX = 600;

/**
 * A rough catchment for each zone: half the distance to the nearest other zone.
 *
 * A zone is a street cluster or an estate, not a point, and drawing it as a dot
 * makes it read as a pin. This gives it visible extent without pretending to a
 * boundary nobody has surveyed.
 *
 * The standing of this number is exactly the standing of an inferred edge: a
 * deterministic geometric rule, reproducible by anyone, and not a measurement.
 * The clamp is a legibility choice, not a finding. Label it honestly wherever it
 * appears and never call it a boundary.
 */
export function catchmentRadii(zones: ZoneSummary[]): Map<string, number> {
  const radii = new Map<string, number>();
  if (zones.length < 2) return radii;

  const latLngs = zones.map((z) => L.latLng(z.lat, z.lng));

  zones.forEach((zone, i) => {
    let nearest = Infinity;
    for (let j = 0; j < zones.length; j += 1) {
      if (i === j) continue;
      const d = latLngs[i].distanceTo(latLngs[j]);
      if (d > 0 && d < nearest) nearest = d;
    }
    if (!Number.isFinite(nearest)) return;
    // Slightly under half, so neighbouring catchments stop just short of each
    // other. At exactly half they kiss, and a chain of them reads as one tube
    // rather than as a row of separate places.
    radii.set(zone.id, Math.min(Math.max(nearest * 0.42, RADIUS_MIN), RADIUS_MAX));
  });

  return radii;
}

/**
 * Zones the map stops at: no outbound connection anyone has recorded.
 *
 * These are the corridor termini. Junction edges between corridors were left
 * blank on purpose, because inference is worst at exactly the edges that matter
 * most, so a terminus is not missing data. It is an open question, and the one
 * residents are best placed to answer.
 */
export function terminusZoneIds(zones: ZoneSummary[], edges: EdgeView[]): Set<string> {
  const hasOutbound = new Set(
    edges.filter((e) => e.confidence !== 'REJECTED').map((e) => e.fromZone),
  );
  return new Set(zones.filter((z) => !hasOutbound.has(z.id)).map((z) => z.id));
}

/**
 * The most upstream zone of each corridor, where the corridor name is drawn.
 * Upstream means nothing flows into it from inside its own corridor.
 */
export function corridorHeads(zones: ZoneSummary[], edges: EdgeView[]): ZoneSummary[] {
  const byCorridor = new Map<string, ZoneSummary[]>();
  zones.forEach((z) => {
    const list = byCorridor.get(z.corridor) ?? [];
    list.push(z);
    byCorridor.set(z.corridor, list);
  });

  const zoneById = new Map(zones.map((z) => [z.id, z]));
  const heads: ZoneSummary[] = [];

  byCorridor.forEach((members, corridor) => {
    const ids = new Set(members.map((m) => m.id));
    const fedFromWithin = new Set(
      edges
        .filter(
          (e) =>
            e.confidence !== 'REJECTED' &&
            ids.has(e.toZone) &&
            zoneById.get(e.fromZone)?.corridor === corridor,
        )
        .map((e) => e.toZone),
    );
    const head = members.find((m) => !fedFromWithin.has(m.id));
    if (head) heads.push(head);
  });

  return heads;
}

export function boundsOf(zones: ZoneSummary[]): L.LatLngBounds | undefined {
  if (zones.length === 0) return undefined;
  return L.latLngBounds(zones.map((z) => [z.lat, z.lng] as [number, number]));
}

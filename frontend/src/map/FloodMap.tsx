import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import './map.css';
import type { EdgeView, PlaceView, ZoneSummary } from '../api/types';
import { SEVERITY_HEX, SEVERITY_WORD } from '../lib/severity';
import { depthGlyphMarkup } from '../components/DepthGlyph';
import {
  boundsOf,
  catchmentRadii,
  corridorHeads,
  edgeGeometry,
  terminusZoneIds,
} from './geometry';

/**
 * The basemap has to be near monochrome so that zone status is the only colour on
 * the screen. Default OpenStreetMap tiles are loud and coloured and would compete
 * with the depth ramp for meaning.
 *
 * CARTO Positron is the reference for this treatment, but its keyless endpoint now
 * watermarks every tile, so the default here is Stadia alidade smooth, which is the
 * same near monochrome design and needs no key on localhost. A deployed domain
 * needs a free Stadia key, or any other pair of light and dark tiles: set
 * VITE_TILE_URL_LIGHT, VITE_TILE_URL_DARK and VITE_TILE_ATTRIBUTION.
 */
const DEFAULT_TILES = {
  light: 'https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}{r}.png',
  dark: 'https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/{z}/{x}/{y}{r}.png',
  attribution:
    '&copy; <a href="https://stadiamaps.com/">Stadia Maps</a> &copy; <a href="https://openmaptiles.org/">OpenMapTiles</a> &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
};

const KEYED_LIGHT = import.meta.env.VITE_TILE_URL_LIGHT;
const KEYED_DARK = import.meta.env.VITE_TILE_URL_DARK;
const KEYED_ATTRIBUTION = import.meta.env.VITE_TILE_ATTRIBUTION;

export function tileSource(dark: boolean): { url: string; attribution: string } {
  if (KEYED_LIGHT && KEYED_DARK) {
    return {
      url: dark ? KEYED_DARK : KEYED_LIGHT,
      attribution: KEYED_ATTRIBUTION ?? DEFAULT_TILES.attribution,
    };
  }
  return {
    url: dark ? DEFAULT_TILES.dark : DEFAULT_TILES.light,
    attribution: DEFAULT_TILES.attribution,
  };
}

/** Below this zoom the travel-time labels have no room and only add noise. */
const TIME_LABEL_ZOOM = 13;

function escapeHtml(value: string): string {
  return value.replace(/[&<>"]/g, (c) =>
    c === '&' ? '&amp;' : c === '<' ? '&lt;' : c === '>' ? '&gt;' : '&quot;',
  );
}

function readToken(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value.length > 0 ? value : fallback;
}

export interface FloodMapProps {
  zones: ZoneSummary[];
  edges: EdgeView[];
  /** Resident-named places that are not zones yet. Only the located ones can be drawn. */
  places?: PlaceView[];
  onSelectZone: (zoneId: string) => void;
  onSelectPlace?: (place: PlaceView) => void;
  onSelectEdge: (edge: EdgeView) => void;
  selectedZoneId?: string;
  /** Set to the id of an edge that has just crossed the confirmation threshold. */
  celebrateEdgeId?: number;
  onCelebrationDone?: () => void;
}

export function FloodMap({
  zones,
  edges,
  places,
  onSelectZone,
  onSelectPlace,
  onSelectEdge,
  selectedZoneId,
  celebrateEdgeId,
  onCelebrationDone,
}: FloodMapProps) {
  const holder = useRef<HTMLDivElement | null>(null);
  const map = useRef<L.Map | null>(null);
  const haloLayer = useRef<L.LayerGroup | null>(null);
  const edgeLayer = useRef<L.LayerGroup | null>(null);
  const zoneLayer = useRef<L.LayerGroup | null>(null);
  const placeLayer = useRef<L.LayerGroup | null>(null);
  const labelLayer = useRef<L.LayerGroup | null>(null);
  const edgePaths = useRef(new Map<number, L.Polyline>());
  const edgeArrows = useRef(new Map<number, L.Marker>());
  const fitted = useRef(false);
  const [zoom, setZoom] = useState(12);

  const handlers = useRef({ onSelectZone, onSelectEdge });
  handlers.current = { onSelectZone, onSelectEdge };

  /* Create the map once. -------------------------------------------------- */
  useEffect(() => {
    if (!holder.current || map.current) return;

    const instance = L.map(holder.current, {
      zoomControl: false,
      attributionControl: true,
      preferCanvas: false,
      center: [6.57, 3.39],
      zoom: 12,
    });

    const dark = window.matchMedia('(prefers-color-scheme: dark)');

    const applyTiles = (isDark: boolean) => {
      const source = tileSource(isDark);
      return L.tileLayer(source.url, { maxZoom: 19, attribution: source.attribution }).addTo(instance);
    };

    let tiles = applyTiles(dark.matches);

    const swapTiles = (event: MediaQueryListEvent) => {
      instance.removeLayer(tiles);
      tiles = applyTiles(event.matches);
      tiles.bringToBack();
    };
    dark.addEventListener('change', swapTiles);

    // Order matters: halos sit under the edges, labels over everything.
    haloLayer.current = L.layerGroup().addTo(instance);
    edgeLayer.current = L.layerGroup().addTo(instance);
    zoneLayer.current = L.layerGroup().addTo(instance);
    placeLayer.current = L.layerGroup().addTo(instance);
    labelLayer.current = L.layerGroup().addTo(instance);
    map.current = instance;

    // Seventeen travel-time labels fight each other at the fitted zoom, so they
    // only appear once the map is close enough for them to have room.
    const trackZoom = () => setZoom(instance.getZoom());
    instance.on('zoomend', trackZoom);
    setZoom(instance.getZoom());

    return () => {
      dark.removeEventListener('change', swapTiles);
      instance.off('zoomend', trackZoom);
      instance.remove();
      map.current = null;
      haloLayer.current = null;
      edgeLayer.current = null;
      zoneLayer.current = null;
      placeLayer.current = null;
      labelLayer.current = null;
    };
  }, []);

  /* Edges. ---------------------------------------------------------------- */
  useEffect(() => {
    const layer = edgeLayer.current;
    const instance = map.current;
    if (!layer || !instance) return;

    layer.clearLayers();
    edgePaths.current.clear();
    edgeArrows.current.clear();

    const ink = readToken('--ink', '#14181A');
    const mist = readToken('--map-line', '#AEB6B8');
    const zoneById = new Map(zones.map((z) => [z.id, z]));

    edges.forEach((edge) => {
      // A rejected edge is not drawn at all. Residents said it is not there.
      if (edge.confidence === 'REJECTED') return;

      const geometry = edgeGeometry(edge, zoneById);
      if (!geometry) return;

      const confirmed = edge.confidence === 'CONFIRMED';
      const stroke = confirmed ? ink : mist;

      // The curve, not a straight line. See the note on BEND in geometry.ts.
      const path = L.polyline(geometry.points, {
        color: stroke,
        weight: confirmed ? 2.5 : 2.25,
        opacity: confirmed ? (edge.blocked ? 0.5 : 1) : 0.75,
        dashArray: confirmed ? undefined : '5 5',
        lineCap: 'round',
        lineJoin: 'round',
        interactive: true,
        bubblingMouseEvents: false,
      });

      path.on('click', (event) => {
        L.DomEvent.stop(event);
        handlers.current.onSelectEdge(edge);
      });
      path.bindTooltip(edgeTooltip(edge, geometry.from, geometry.to), { sticky: true });
      path.addTo(layer);
      edgePaths.current.set(edge.id, path);

      // Only a confirmed edge earns an arrowhead. On an inferred edge nobody has
      // backed the direction, so the interface does not assert one.
      if (confirmed) {
        const arrow = L.marker(geometry.head, {
          interactive: false,
          keyboard: false,
          icon: L.divIcon({
            className: 'edge-deco',
            iconSize: [14, 14],
            iconAnchor: [7, 7],
            html:
              `<svg class="edge-arrow" width="14" height="14" viewBox="0 0 14 14" ` +
              `style="transform: rotate(${geometry.headAngle}deg); opacity:${edge.blocked ? 0.5 : 1}">` +
              `<path d="M2 2 L11 7 L2 12 Z" fill="${stroke}"/></svg>`,
          }),
        });
        arrow.addTo(layer);
        edgeArrows.current.set(edge.id, arrow);
      }

      // Blockage is a property of the channel, so it reads as a bar laid across
      // it rather than as a change to the line itself.
      if (edge.blocked) {
        L.marker(geometry.midpoint, {
          interactive: false,
          keyboard: false,
          icon: L.divIcon({
            className: 'edge-deco',
            iconSize: [16, 16],
            iconAnchor: [8, 8],
            html:
              `<svg width="16" height="16" viewBox="0 0 16 16" ` +
              `style="transform: rotate(${geometry.midAngle + 90}deg)">` +
              `<rect x="7" y="1" width="2.5" height="14" rx="1.25" fill="${stroke}"/></svg>`,
          }),
        }).addTo(layer);
      }

      // The number is what the line actually claims: not a route, a delay. Saying
      // it on the line is the most direct way to stop it being read as a street.
      if (zoom >= TIME_LABEL_ZOOM) {
        L.marker(geometry.apex, {
          interactive: false,
          keyboard: false,
          zIndexOffset: 500,
          icon: L.divIcon({
            className: 'edge-deco',
            iconSize: [56, 20],
            iconAnchor: [28, 10],
            html:
              `<span class="edge-time${confirmed ? '' : ' edge-time--inferred'}">` +
              `${edge.travelMinutes} min</span>`,
          }),
        }).addTo(layer);
      }
    });
  }, [edges, zones, zoom]);

  /* Catchment halos. ------------------------------------------------------ */
  useEffect(() => {
    const layer = haloLayer.current;
    if (!layer) return;

    layer.clearLayers();
    const radii = catchmentRadii(zones);
    const mist = readToken('--map-line', '#AEB6B8');

    zones.forEach((zone) => {
      const radius = radii.get(zone.id);
      if (radius === undefined) return;

      // A rough catchment, not a boundary. Derived from the spacing between
      // zones, drawn soft and dashed so it never reads as a surveyed line, and
      // non-interactive so it never steals a tap from the marker inside it.
      // Deliberately not dashed. A dash means one thing on this map, an
      // unconfirmed connection, and a ring of dashes around every zone was
      // stealing that meaning and drowning the edges in the same grey.
      L.circle([zone.lat, zone.lng], {
        radius,
        color: mist,
        weight: 1,
        opacity: 0.3,
        fillColor: mist,
        fillOpacity: 0.05,
        interactive: false,
      }).addTo(layer);
    });
  }, [zones]);

  /* Corridor names. ------------------------------------------------------- */
  useEffect(() => {
    const layer = labelLayer.current;
    if (!layer) return;

    layer.clearLayers();

    // Three corridors with no connections between them is the most important
    // fact on this map. Naming each chain at its head makes them read as three
    // named things rather than three accidents.
    corridorHeads(zones, edges).forEach((head) => {
      L.marker([head.lat, head.lng], {
        interactive: false,
        keyboard: false,
        icon: L.divIcon({
          className: 'edge-deco',
          iconSize: [160, 20],
          iconAnchor: [80, 34],
          html: `<span class="corridor-label">${escapeHtml(head.corridor)}</span>`,
        }),
      }).addTo(layer);
    });
  }, [zones, edges]);

  /* Zones. ---------------------------------------------------------------- */
  useEffect(() => {
    const layer = zoneLayer.current;
    if (!layer) return;

    layer.clearLayers();
    const ink = readToken('--ink', '#14181A');
    const termini = terminusZoneIds(zones, edges);

    zones.forEach((zone) => {
      const active = zone.status.active && zone.status.level !== undefined;
      const level = zone.status.level;
      const selected = zone.id === selectedZoneId;
      // Nothing has been recorded downstream of this zone. Not missing data: an
      // open question, and the one residents are best placed to answer.
      const terminus = termini.has(zone.id);

      const html = active && level
        ? `<div class="zone-marker__active" style="--depth:${SEVERITY_HEX[level]};` +
          `${selected ? `box-shadow:0 0 0 3px ${ink};` : ''}">` +
          '<span class="zone-marker__pulse"></span>' +
          depthGlyphMarkup(level, 20, SEVERITY_HEX[level]) +
          '</div>'
        : `<div class="zone-marker__dot${terminus ? ' zone-marker__dot--terminus' : ''}" ` +
          `style="${selected ? `border-color:${ink};border-width:3px;` : ''}"></div>`;

      const size = active ? 38 : 16;

      const marker = L.marker([zone.lat, zone.lng], {
        keyboard: true,
        title: zoneTitle(zone),
        alt: zoneTitle(zone),
        riseOnHover: true,
        zIndexOffset: active ? 1000 : 0,
        icon: L.divIcon({
          className: 'zone-marker',
          iconSize: [size, size],
          iconAnchor: [size / 2, size / 2],
          html,
        }),
      });

      marker.on('click', () => handlers.current.onSelectZone(zone.id));
      marker.on('keypress', (event) => {
        const original = (event as unknown as { originalEvent?: KeyboardEvent }).originalEvent;
        if (!original || original.key === 'Enter' || original.key === ' ') {
          handlers.current.onSelectZone(zone.id);
        }
      });
      marker.bindTooltip(terminus ? `${zoneTitle(zone)}. The map stops here.` : zoneTitle(zone), {
        direction: 'top',
        offset: [0, -size / 2],
      });
      marker.addTo(layer);
    });
  }, [zones, edges, selectedZoneId]);

  /* Places residents have named, not yet zones. --------------------------- */
  useEffect(() => {
    const layer = placeLayer.current;
    if (!layer) return;

    layer.clearLayers();
    // A place nobody has pinned has no position to draw. It is still real and
    // still counts; it just cannot appear here until somebody stands at it.
    (places ?? [])
      .filter((place) => place.located && place.lat !== undefined && place.lng !== undefined)
      .forEach((place) => {
        const marker = L.marker([place.lat as number, place.lng as number], {
          keyboard: true,
          title: place.landmark,
          alt: place.landmark,
          riseOnHover: true,
          icon: L.divIcon({
            className: 'place-marker',
            iconSize: [18, 18],
            iconAnchor: [9, 9],
            html: '<div class="place-marker__dot"></div>',
          }),
        });
        marker.bindTooltip(
          `${place.landmark}. Named by ${place.distinctVoices} of ${place.threshold} people, not on the map yet.`,
          { direction: 'top', offset: [0, -9] },
        );
        if (onSelectPlace) marker.on('click', () => onSelectPlace(place));
        marker.addTo(layer);
      });
  }, [places, onSelectPlace]);

  /* Fit to the network once the zones are in. ----------------------------- */
  useEffect(() => {
    const instance = map.current;
    if (!instance || fitted.current || zones.length === 0) return;
    const bounds = boundsOf(zones);
    if (!bounds) return;
    instance.fitBounds(bounds, { padding: [48, 96] });
    fitted.current = true;
  }, [zones]);

  /* The one orchestrated moment in the product. --------------------------- */
  useEffect(() => {
    if (celebrateEdgeId === undefined) return;
    const path = edgePaths.current.get(celebrateEdgeId);
    const arrow = edgeArrows.current.get(celebrateEdgeId);
    const element = path?.getElement() as SVGPathElement | undefined;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const arrowSvg = arrow?.getElement()?.querySelector('svg') as SVGElement | null;

    if (arrowSvg) arrowSvg.style.opacity = '0';

    let timer: number;
    if (element && !reduced && typeof element.getTotalLength === 'function') {
      // The line redraws from dashed to solid along its own length, once.
      const length = element.getTotalLength();
      element.style.transition = 'none';
      element.style.strokeDasharray = `${length}`;
      element.style.strokeDashoffset = `${length}`;
      // Force the browser to take the start state before animating away from it.
      void element.getBoundingClientRect();
      element.style.transition = 'stroke-dashoffset 600ms cubic-bezier(0.22,0.61,0.36,1)';
      element.style.strokeDashoffset = '0';
      timer = window.setTimeout(() => {
        element.style.transition = '';
        element.style.strokeDasharray = '';
        element.style.strokeDashoffset = '';
        if (arrowSvg) arrowSvg.style.opacity = '1';
        onCelebrationDone?.();
      }, 640);
    } else {
      // Reduced motion gets a crossfade instead of a redraw.
      if (element) {
        element.style.transition = 'opacity 300ms linear';
        element.style.opacity = '0.15';
        void element.getBoundingClientRect();
        element.style.opacity = '1';
      }
      timer = window.setTimeout(() => {
        if (element) element.style.transition = '';
        if (arrowSvg) arrowSvg.style.opacity = '1';
        onCelebrationDone?.();
      }, 320);
    }

    return () => window.clearTimeout(timer);
  }, [celebrateEdgeId, edges, onCelebrationDone]);

  /* Imperative controls. --------------------------------------------------- */
  const zoomBy = (delta: number) => map.current?.zoomIn(delta);
  const locate = () => {
    const instance = map.current;
    if (!instance || !navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      (pos) => instance.setView([pos.coords.latitude, pos.coords.longitude], 15),
      () => {},
      { enableHighAccuracy: true, timeout: 8000 },
    );
  };

  return (
    <div className="map">
      <div ref={holder} style={{ height: '100%' }} />
      {/* Controls sit low and right, inside the reach of a thumb. */}
      <div className="map__controls">
        <button className="map__control" type="button" onClick={() => zoomBy(1)} aria-label="Zoom in">
          +
        </button>
        <button className="map__control" type="button" onClick={() => zoomBy(-1)} aria-label="Zoom out">
          &minus;
        </button>
        <button className="map__control" type="button" onClick={locate} aria-label="Go to my location">
          <svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true">
            <circle cx="10" cy="10" r="4.5" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <circle cx="10" cy="10" r="1.4" fill="currentColor" />
            <path
              d="M10 1.5v2.5M10 16v2.5M1.5 10h2.5M16 10h2.5"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
            />
          </svg>
        </button>
      </div>
    </div>
  );
}

function zoneTitle(zone: ZoneSummary): string {
  const status = zone.status.active && zone.status.level
    ? `, ${SEVERITY_WORD[zone.status.level].toLowerCase()}`
    : '';
  return `${zone.displayName}, ${zone.corridor}${status}`;
}

function edgeTooltip(edge: EdgeView, from: ZoneSummary, to: ZoneSummary): string {
  const state = edge.confidence === 'CONFIRMED' ? 'Confirmed' : 'Not yet confirmed';
  const blocked = edge.blocked ? ', drain reported blocked' : '';
  return `${from.displayName} to ${to.displayName}, about ${edge.travelMinutes} minutes. ${state}${blocked}`;
}

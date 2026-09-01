import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import './map.css';
import type { EdgeView, ZoneSummary } from '../api/types';
import { SEVERITY_HEX, SEVERITY_WORD } from '../lib/severity';
import { depthGlyphMarkup } from '../components/DepthGlyph';
import { boundsOf, edgeGeometry } from './geometry';

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

function tileSource(dark: boolean): { url: string; attribution: string } {
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

function readToken(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value.length > 0 ? value : fallback;
}

export interface FloodMapProps {
  zones: ZoneSummary[];
  edges: EdgeView[];
  onSelectZone: (zoneId: string) => void;
  onSelectEdge: (edge: EdgeView) => void;
  selectedZoneId?: string;
  /** Set to the id of an edge that has just crossed the confirmation threshold. */
  celebrateEdgeId?: number;
  onCelebrationDone?: () => void;
}

export function FloodMap({
  zones,
  edges,
  onSelectZone,
  onSelectEdge,
  selectedZoneId,
  celebrateEdgeId,
  onCelebrationDone,
}: FloodMapProps) {
  const holder = useRef<HTMLDivElement | null>(null);
  const map = useRef<L.Map | null>(null);
  const edgeLayer = useRef<L.LayerGroup | null>(null);
  const zoneLayer = useRef<L.LayerGroup | null>(null);
  const edgePaths = useRef(new Map<number, L.Polyline>());
  const edgeArrows = useRef(new Map<number, L.Marker>());
  const fitted = useRef(false);

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

    edgeLayer.current = L.layerGroup().addTo(instance);
    zoneLayer.current = L.layerGroup().addTo(instance);
    map.current = instance;

    return () => {
      dark.removeEventListener('change', swapTiles);
      instance.remove();
      map.current = null;
      edgeLayer.current = null;
      zoneLayer.current = null;
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

      const path = L.polyline(
        [
          [geometry.from.lat, geometry.from.lng],
          [geometry.to.lat, geometry.to.lng],
        ],
        {
          color: stroke,
          weight: confirmed ? 2.5 : 2,
          opacity: confirmed ? (edge.blocked ? 0.5 : 1) : 0.6,
          dashArray: confirmed ? undefined : '5 5',
          lineCap: 'round',
          interactive: true,
          bubblingMouseEvents: false,
        },
      );

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
              `style="transform: rotate(${geometry.angle}deg); opacity:${edge.blocked ? 0.5 : 1}">` +
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
              `style="transform: rotate(${geometry.angle + 90}deg)">` +
              `<rect x="7" y="1" width="2.5" height="14" rx="1.25" fill="${stroke}"/></svg>`,
          }),
        }).addTo(layer);
      }
    });
  }, [edges, zones]);

  /* Zones. ---------------------------------------------------------------- */
  useEffect(() => {
    const layer = zoneLayer.current;
    if (!layer) return;

    layer.clearLayers();
    const ink = readToken('--ink', '#14181A');

    zones.forEach((zone) => {
      const active = zone.status.active && zone.status.level !== undefined;
      const level = zone.status.level;
      const selected = zone.id === selectedZoneId;

      const html = active && level
        ? `<div class="zone-marker__active" style="--depth:${SEVERITY_HEX[level]};` +
          `${selected ? `box-shadow:0 0 0 3px ${ink};` : ''}">` +
          '<span class="zone-marker__pulse"></span>' +
          depthGlyphMarkup(level, 20, SEVERITY_HEX[level]) +
          '</div>'
        : `<div class="zone-marker__dot" style="${selected ? `border-color:${ink};border-width:3px;` : ''}"></div>`;

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
      marker.bindTooltip(zoneTitle(zone), { direction: 'top', offset: [0, -size / 2] });
      marker.addTo(layer);
    });
  }, [zones, selectedZoneId]);

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

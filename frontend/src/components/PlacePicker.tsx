import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import './PlacePicker.css';
import { tileSource } from '../map/FloodMap';
import type { ZoneSummary } from '../api/types';
import { distanceMeters, distanceWord } from '../lib/geo';

export interface PlacePickerProps {
  /** The zone the water is coming from. The crosshair starts here. */
  origin: ZoneSummary;
  /** Where the crosshair is now, if it has been moved or set from GPS. */
  value?: { lat: number; lng: number };
  onChange: (at: { lat: number; lng: number }) => void;
}

/**
 * Putting the pin where the place actually is.
 *
 * <p>A phone's GPS answers "where am I", which is only the same question as
 * "where is the place" when the person happens to be standing in it. Somebody
 * looking at a street two zones away on the map and recognising it is doing
 * something the device cannot help with, and that is the common case: you notice
 * the gap while looking at the map, not while wading through it.
 *
 * <p>So the crosshair is fixed and the map moves under it, rather than asking
 * for a tap. A tap on a phone is worth about 40 m of accuracy at this zoom, and
 * panning under a fixed cross costs nothing and is exact.
 */
export function PlacePicker({ origin, value, onChange }: PlacePickerProps) {
  const holder = useRef<HTMLDivElement | null>(null);
  const map = useRef<L.Map | null>(null);
  const report = useRef(onChange);
  report.current = onChange;
  const [centre, setCentre] = useState(value ?? { lat: origin.lat, lng: origin.lng });

  useEffect(() => {
    if (!holder.current || map.current) return;
    const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const source = tileSource(dark);

    const instance = L.map(holder.current, {
      zoomControl: false,
      attributionControl: false,
      center: [value?.lat ?? origin.lat, value?.lng ?? origin.lng],
      // Close enough that a street is a street, not a neighbourhood.
      zoom: 15,
    });
    L.tileLayer(source.url, { maxZoom: 19, attribution: source.attribution }).addTo(instance);

    // The origin, so the crosshair is placed in relation to something known
    // rather than against bare tiles.
    L.marker([origin.lat, origin.lng], {
      interactive: false,
      keyboard: false,
      icon: L.divIcon({
        className: 'pp__origin',
        iconSize: [14, 14],
        iconAnchor: [7, 7],
        html: '<div class="pp__origin-dot"></div>',
      }),
    }).addTo(instance);

    const moved = () => {
      const at = instance.getCenter();
      const next = { lat: at.lat, lng: at.lng };
      setCentre(next);
      report.current(next);
    };
    instance.on('moveend', moved);

    map.current = instance;
    // The sheet animates open, so the container has no height on the first
    // frame and Leaflet would size the canvas to nothing.
    const timer = window.setTimeout(() => instance.invalidateSize(), 60);

    return () => {
      window.clearTimeout(timer);
      instance.off('moveend', moved);
      instance.remove();
      map.current = null;
    };
  }, [origin.id, origin.lat, origin.lng]);

  // A GPS fix arriving from outside moves the map rather than competing with it,
  // so the crosshair stays the single answer to where the place is.
  useEffect(() => {
    if (!value || !map.current) return;
    const at = map.current.getCenter();
    if (Math.abs(at.lat - value.lat) < 1e-7 && Math.abs(at.lng - value.lng) < 1e-7) return;
    map.current.setView([value.lat, value.lng], Math.max(map.current.getZoom(), 15));
  }, [value?.lat, value?.lng]);

  const away = distanceMeters(origin, centre);

  return (
    <div className="pp">
      <div className="pp__map" ref={holder} role="application" aria-label="Move the map to place the pin" />
      <div className="pp__cross" aria-hidden="true">
        <span className="pp__cross-ring" />
      </div>
      <div className="pp__readout">
        <span className="pp__readout-dist">{distanceWord(away)} from {origin.displayName}</span>
        <span className="pp__readout-hint">Drag the map to put the cross on the place</span>
      </div>
    </div>
  );
}

/**
 * Great-circle distance in metres. Small enough to keep the screens free of a
 * Leaflet import just to sort a list of zones by how near they are.
 */
export function distanceMeters(
  a: { lat: number; lng: number },
  b: { lat: number; lng: number },
): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 + Math.sin(dLng / 2) ** 2 * Math.cos(lat1) * Math.cos(lat2);
  return 2 * R * Math.asin(Math.sqrt(h));
}

/** "1.1 km" or "870 m", whichever reads better at that size. */
export function distanceWord(metres: number): string {
  if (metres < 1000) return `${Math.round(metres / 10) * 10} m`;
  return `${(metres / 1000).toFixed(1)} km`;
}

/** What the device said, and how much to trust it. */
export interface Fix {
  lat: number;
  lng: number;
  /** Radius of uncertainty in metres, as the browser reports it. */
  accuracyM: number;
}

/**
 * A GPS fix, or a refusal written for a person rather than a console.
 *
 * A landmark is worth having on its own, so nothing here is allowed to block a
 * proposal. The caller is expected to carry on without a fix and let somebody
 * standing at the place supply it later.
 */
export function currentFix(timeoutMs = 12000): Promise<Fix> {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) {
      reject(new Error('This device cannot report its location.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) =>
        resolve({
          lat: position.coords.latitude,
          lng: position.coords.longitude,
          accuracyM: position.coords.accuracy,
        }),
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          reject(new Error('Location is turned off for this site.'));
        } else if (err.code === err.TIMEOUT) {
          reject(new Error('Could not get a fix. Try again under open sky.'));
        } else {
          reject(new Error('Your location is not available right now.'));
        }
      },
      { enableHighAccuracy: true, timeout: timeoutMs, maximumAge: 0 },
    );
  });
}

/** "within 8 m" or "within 40 m", so the number means something to a reader. */
export function accuracyWord(metres: number): string {
  return `within ${metres < 10 ? Math.round(metres) : Math.round(metres / 5) * 5} m`;
}

/**
 * `address` on a subscription is opaque to the backend: whatever identifies this
 * subscriber on that channel. For the sse channel that is this browser, so a
 * stable random id per device is the honest value to send.
 */
const KEY = 'ekoalert.deviceId';

export function deviceAddress(): string {
  let id = localStorage.getItem(KEY);
  if (!id) {
    id = `device-${crypto.randomUUID()}`;
    localStorage.setItem(KEY, id);
  }
  return id;
}

const SUBS_KEY = 'ekoalert.subscribedZones';

export function readSubscriptions(): Set<string> {
  try {
    const raw = localStorage.getItem(SUBS_KEY);
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

export function rememberSubscription(zoneId: string): void {
  const set = readSubscriptions();
  set.add(zoneId);
  localStorage.setItem(SUBS_KEY, JSON.stringify([...set]));
}

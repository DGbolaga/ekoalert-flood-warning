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

/**
 * Subscribing has to reach anything already on screen. The activity feed reads
 * this list to decide what belongs to you, and it is mounted while the zone
 * sheet is open, so without a notification it would keep showing the old set
 * until the next reload.
 */
export const SUBS_EVENT = 'ekoalert:subscriptions';

export function rememberSubscription(zoneId: string): void {
  const set = readSubscriptions();
  set.add(zoneId);
  localStorage.setItem(SUBS_KEY, JSON.stringify([...set]));
  window.dispatchEvent(new CustomEvent(SUBS_EVENT));
}

/**
 * The read endpoints return `confirmations` and `rejections` but never the
 * threshold those counts are moving toward, so it is only known once a
 * correction response has carried one back. Until then the UI says how many
 * people have spoken and does not claim to know how many it takes.
 */
const KEY = 'ekoalert.correctionThreshold';

let learned: number | undefined = readStored();

function readStored(): number | undefined {
  const raw = localStorage.getItem(KEY);
  if (!raw) return undefined;
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : undefined;
}

export function learnThreshold(value: number): void {
  if (!Number.isFinite(value) || value <= 0) return;
  learned = value;
  localStorage.setItem(KEY, String(value));
}

export function knownThreshold(): number | undefined {
  return learned;
}

/** "1 of 2 people confirm this". People, because that is what the server counts. */
export function voicesPhrase(count: number, verb: 'confirm' | 'reject'): string {
  const threshold = knownThreshold();
  if (threshold !== undefined) {
    return `${count} of ${threshold} people ${verb} this`;
  }
  if (count === 0) return `Nobody has ${verb}ed this yet`;
  if (count === 1) return `1 person ${verb}s this`;
  return `${count} people ${verb} this`;
}

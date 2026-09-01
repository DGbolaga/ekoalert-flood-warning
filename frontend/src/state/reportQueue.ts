/**
 * Offline queue for reports.
 *
 * A reporter who taps submit in the rain, sees nothing and walks away believing
 * he warned people has been failed at the exact moment the system existed for.
 * So a report that cannot be sent is written to IndexedDB before the failure is
 * shown, kept across a reload, and retried on reconnect.
 *
 * `observedAt` is stamped at the tap, never at the send, so a report that sat in
 * the queue for twenty minutes still carries the time the water was seen.
 */
import type { ReportRequest, ReportResponse, Severity } from '../api/types';

const DB_NAME = 'ekoalert';
const DB_VERSION = 1;
const STORE = 'pendingReports';

export interface QueuedReport {
  id: number;
  reporterId?: number;
  level: Severity;
  drainBlocked?: boolean;
  observedAt: string;
  queuedAt: string;
  attempts: number;
}

export type QueuedDraft = Omit<QueuedReport, 'id' | 'attempts'>;

let dbPromise: Promise<IDBDatabase> | undefined;

function openDb(): Promise<IDBDatabase> {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const transaction = db.transaction(STORE, mode);
        const request = run(transaction.objectStore(STORE));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      }),
  );
}

const listeners = new Set<(pending: QueuedReport[]) => void>();

export function onQueueChange(listener: (pending: QueuedReport[]) => void): () => void {
  listeners.add(listener);
  void listPending().then(listener);
  return () => listeners.delete(listener);
}

async function announce(): Promise<void> {
  const pending = await listPending();
  listeners.forEach((l) => l(pending));
}

export async function listPending(): Promise<QueuedReport[]> {
  try {
    const all = await tx<QueuedReport[]>('readonly', (store) => store.getAll() as IDBRequest<QueuedReport[]>);
    return all.sort((a, b) => a.id - b.id);
  } catch {
    // Private browsing can refuse IndexedDB outright. Report it as empty rather
    // than crashing the screen that has to work.
    return [];
  }
}

export async function enqueue(draft: QueuedDraft): Promise<QueuedReport | undefined> {
  try {
    const id = await tx<IDBValidKey>('readwrite', (store) =>
      store.add({ ...draft, attempts: 0 }) as IDBRequest<IDBValidKey>,
    );
    await announce();
    return { ...draft, id: Number(id), attempts: 0 };
  } catch {
    return undefined;
  }
}

export async function drop(id: number): Promise<void> {
  try {
    await tx<undefined>('readwrite', (store) => store.delete(id) as IDBRequest<undefined>);
    await announce();
  } catch {
    /* nothing more to do; the record is either gone or unreachable */
  }
}

async function bumpAttempts(record: QueuedReport): Promise<void> {
  try {
    await tx<IDBValidKey>('readwrite', (store) =>
      store.put({ ...record, attempts: record.attempts + 1 }) as IDBRequest<IDBValidKey>,
    );
    await announce();
  } catch {
    /* an attempt count is not worth failing a retry over */
  }
}

export function toRequest(record: QueuedReport | QueuedDraft): ReportRequest {
  const body: ReportRequest = { level: record.level, observedAt: record.observedAt };
  // Absent is not the same as saying the drain is clear, so only send the field
  // when the reporter actually touched the toggle.
  if (record.drainBlocked !== undefined) body.drainBlocked = record.drainBlocked;
  return body;
}

export interface FlushOutcome {
  sent: Array<{ queued: QueuedReport; response: ReportResponse }>;
  stillQueued: number;
}

type Sender = (body: ReportRequest) => Promise<ReportResponse>;
type IsOffline = (err: unknown) => boolean;

let flushing = false;

/**
 * Sends everything that will go. A network failure stops the run and leaves the
 * rest queued; a rejection the server explained drops that record, because
 * retrying a report the server refuses would queue it forever.
 */
export async function flush(send: Sender, offline: IsOffline): Promise<FlushOutcome> {
  if (flushing) return { sent: [], stillQueued: (await listPending()).length };
  flushing = true;
  const sent: FlushOutcome['sent'] = [];
  try {
    const pending = await listPending();
    for (const record of pending) {
      try {
        const response = await send(toRequest(record));
        await drop(record.id);
        sent.push({ queued: record, response });
      } catch (err) {
        if (offline(err)) {
          await bumpAttempts(record);
          break;
        }
        // The server read it and said no. Keeping it would retry forever.
        await drop(record.id);
      }
    }
  } finally {
    flushing = false;
  }
  return { sent, stillQueued: (await listPending()).length };
}

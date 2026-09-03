import type {
  CorrectionResponse,
  GraphResponse,
  KillSwitchResponse,
  LoginResponse,
  PlaceView,
  ReplayRequest,
  ReplayResponse,
  ReportRequest,
  ReportResponse,
  ReporterCredentials,
  ReporterView,
  SubscriptionResponse,
  ZoneDetail,
  ZoneSummary,
} from './types';
import type { ApiErrorBody } from './types';

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080/api/v1';

/** A response the server explained. `message` is written to be read by a person. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly at?: string;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.name = 'ApiError';
    this.status = status;
    this.code = body.error;
    this.at = body.at;
  }
}

/** The request never reached the server, or the reply never came back. */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super('The network did not answer.');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

export function isOffline(err: unknown): err is NetworkError {
  return err instanceof NetworkError;
}

type TokenSource = () => string | undefined;

let getToken: TokenSource = () => undefined;
let onUnauthorized: () => void = () => {};

export function configureAuth(source: TokenSource, unauthorized: () => void): void {
  getToken = source;
  onUnauthorized = unauthorized;
}

interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  auth?: boolean;
  signal?: AbortSignal;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, auth = false, signal } = options;

  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth) {
    const token = getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }

  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    if (signal?.aborted) throw cause;
    throw new NetworkError(cause);
  }

  if (res.status === 401) {
    onUnauthorized();
  }

  if (!res.ok) {
    throw new ApiError(res.status, await readErrorBody(res));
  }

  if (res.status === 204) return undefined as T;

  const text = await res.text();
  if (text.length === 0) return undefined as T;
  return JSON.parse(text) as T;
}

async function readErrorBody(res: Response): Promise<ApiErrorBody> {
  // GET /zones/{id} answers 404 with an empty body, so never assume JSON.
  const fallback: ApiErrorBody = {
    error: res.status === 404 ? 'not_found' : 'unknown',
    message: fallbackMessage(res.status),
    at: new Date().toISOString(),
  };
  let text = '';
  try {
    text = await res.text();
  } catch {
    return fallback;
  }
  if (text.trim().length === 0) return fallback;
  try {
    const parsed = JSON.parse(text) as Partial<ApiErrorBody>;
    if (typeof parsed.message === 'string' && typeof parsed.error === 'string') {
      return { error: parsed.error, message: parsed.message, at: parsed.at ?? fallback.at };
    }
  } catch {
    /* fall through */
  }
  return fallback;
}

function fallbackMessage(status: number): string {
  if (status === 401) return 'Your session has ended. Sign in again.';
  if (status === 403) return 'This account cannot do that.';
  if (status === 404) return 'Not found.';
  return `The server answered ${status}.`;
}

/* Public reads ------------------------------------------------------------- */

export const getZones = (signal?: AbortSignal) => request<ZoneSummary[]>('/zones', { signal });

export const getGraph = (signal?: AbortSignal) => request<GraphResponse>('/graph', { signal });

/** Resolves to undefined for an unknown zone, which the server answers 404 empty. */
export async function getZone(id: string, signal?: AbortSignal): Promise<ZoneDetail | undefined> {
  try {
    return await request<ZoneDetail>(`/zones/${encodeURIComponent(id)}`, { signal });
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return undefined;
    throw err;
  }
}

/* Actions ------------------------------------------------------------------ */

export const login = (username: string, password: string) =>
  request<LoginResponse>('/auth/login', { method: 'POST', body: { username, password } });

export const subscribe = (zoneId: string, address: string, channel = 'sse') =>
  request<SubscriptionResponse>('/subscriptions', {
    method: 'POST',
    body: { zoneId, address, channel },
  });

export const postReport = (body: ReportRequest) =>
  request<ReportResponse>('/reports', { method: 'POST', body, auth: true });

export const confirmEdge = (edgeId: number) =>
  request<CorrectionResponse>(`/edges/${edgeId}/confirm`, { method: 'POST', auth: true });

export const rejectEdge = (edgeId: number) =>
  request<CorrectionResponse>(`/edges/${edgeId}/reject`, { method: 'POST', auth: true });

export const proposeEdge = (fromZone: string, toZone: string) =>
  request<CorrectionResponse>('/edges/propose', {
    method: 'POST',
    body: { fromZone, toZone },
    auth: true,
  });

/* Places ------------------------------------------------------------------- */

/** Pending places are public: one nobody can see is one nobody can corroborate. */
export const getPlaces = (signal?: AbortSignal) =>
  request<PlaceView[]>('/places', { signal });

export const proposePlace = (
  fromZone: string,
  landmark: string,
  at?: { lat: number; lng: number },
) =>
  request<PlaceView>('/places/propose', {
    method: 'POST',
    body: { fromZone, landmark, lat: at?.lat, lng: at?.lng },
    auth: true,
  });

export const affirmPlace = (id: number, at?: { lat: number; lng: number }) =>
  request<PlaceView>(`/places/${id}/affirm`, {
    method: 'POST',
    body: { lat: at?.lat, lng: at?.lng },
    auth: true,
  });

/* Admin -------------------------------------------------------------------- */

export const setKillSwitch = (enabled: boolean) =>
  request<KillSwitchResponse>('/admin/kill-switch', {
    method: 'POST',
    body: { enabled },
    auth: true,
  });

export const listReporters = () =>
  request<ReporterView[]>('/admin/reporters', { auth: true });

export const createReporter = (body: {
  zoneId: string;
  displayName: string;
  phone: string;
  username?: string;
  verified?: boolean;
}) => request<ReporterCredentials>('/admin/reporters', { method: 'POST', body, auth: true });

export const verifyReporter = (id: number, verified: boolean) =>
  request<ReporterView>(`/admin/reporters/${id}/verify`, {
    method: 'POST',
    body: { verified },
    auth: true,
  });

export const resetReporterPassword = (id: number) =>
  request<ReporterCredentials>(`/admin/reporters/${id}/password`, { method: 'POST', auth: true });

export const suspendReporter = (id: number, suspended: boolean) =>
  request<ReporterView>(`/admin/reporters/${id}/suspend`, {
    method: 'POST',
    body: { suspended },
    auth: true,
  });

export const runReplay = (body: ReplayRequest) =>
  request<ReplayResponse>('/replay', { method: 'POST', body, auth: true });

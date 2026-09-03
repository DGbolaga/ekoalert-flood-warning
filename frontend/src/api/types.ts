/**
 * Copied from API_CONTRACT.md. Optional fields are optional because the server
 * omits nulls rather than sending them. Check with `?.` or `in`, never `=== null`.
 */

export type Severity = 'ANKLE' | 'KNEE' | 'IMPASSABLE';
export type Confidence = 'INFERRED' | 'CONFIRMED' | 'REJECTED';
export type Role = 'REPORTER' | 'ADMIN';

export const SEVERITY_ORDER: Severity[] = ['ANKLE', 'KNEE', 'IMPASSABLE'];

export interface ApiErrorBody {
  error: string;
  message: string;
  at: string;
}

export interface LoginResponse {
  token: string;
  role: Role;
  reporterId?: number;
  expiresAt: string;
}

export interface ZoneStatusView {
  level?: Severity;
  escalatedAt?: string;
  clearedAt?: string;
  active: boolean;
}

export interface ZoneSummary {
  id: string;
  corridor: string;
  name?: string;
  landmark?: string;
  displayName: string;
  lat: number;
  lng: number;
  needsFieldNaming: boolean;
  status: ZoneStatusView;
}

export interface EdgeView {
  id: number;
  fromZone: string;
  toZone: string;
  travelMinutes: number;
  distanceM?: number;
  confidence: Confidence;
  blocked: boolean;
  /** Derived server side as CONFIRMED && !blocked. Read it, do not recompute it. */
  alertable: boolean;
  inferenceBasis?: string;
  updatedAt: string;
  confirmations?: number;
  rejections?: number;
}

export interface ZoneDetail {
  zone: ZoneSummary;
  outbound: EdgeView[];
  inbound: EdgeView[];
}

export interface GraphCounts {
  zones: number;
  edges: number;
  inferred: number;
  confirmed: number;
  rejected: number;
  blocked: number;
}

export interface GraphResponse {
  zones: ZoneSummary[];
  edges: EdgeView[];
  counts: GraphCounts;
}

export type SuppressionReason = 'inferred_edge' | 'kill_switch';

export interface AlertView {
  id: number;
  originZone: string;
  targetZone: string;
  level: Severity;
  etaMinutes: number;
  hops: number;
  firedAt: string;
  /** Absent means the alert was actually delivered. */
  suppressedBy?: SuppressionReason;
}

export interface ReportRequest {
  level: Severity;
  zoneId?: string;
  drainBlocked?: boolean;
  observedAt?: string;
}

export interface ReportResponse {
  reportId: number;
  zoneId: string;
  level: Severity;
  observedAt: string;
  countedTowardQuorum: boolean;
  quorumLevel?: Severity;
  escalated: boolean;
  alerts: AlertView[];
}

export interface CorrectionResponse {
  correctionId: number;
  action: 'confirm' | 'reject' | 'propose';
  fromZone: string;
  toZone: string;
  distinctVoices: number;
  threshold: number;
  thresholdMet: boolean;
  /** Absent for a proposal that has not yet crossed the threshold. */
  edge?: EdgeView;
}

/**
 * A place a resident named that the graph has no node for yet.
 *
 * `located` is separate from having voices on purpose: the person who knows what
 * somewhere is called is not always the person standing at it, so a place can be
 * fully corroborated and still be waiting on a GPS fix before it can be drawn.
 */
export interface PlaceView {
  id: number;
  landmark: string;
  lat?: number;
  lng?: number;
  located: boolean;
  fromZone: string;
  status: 'pending' | 'promoted' | 'rejected';
  distinctVoices: number;
  threshold: number;
  promoted: boolean;
  /** The place turned out to be an existing zone under a name residents use. */
  mergedInto: boolean;
  proposedAt: string;
  /** Absent until promoted. */
  zone?: ZoneSummary;
  /** Absent until promoted. Arrives INFERRED, so it warns nobody. */
  edge?: EdgeView;
}

export interface SubscriptionResponse {
  id: number;
  zoneId: string;
  channel: string;
  address: string;
}

export interface KillSwitchResponse {
  alertsEnabled: boolean;
  at: string;
}

export interface ReporterView {
  id: number;
  zoneId: string;
  displayName: string;
  suspended: boolean;
  /** Absent means never vetted, or vetted and then revoked. */
  verifiedAt?: string;
  username?: string;
  phone?: string;
}

/** The password is returned once, on enrolment or reset, and never again. */
export interface ReporterCredentials {
  reporter: ReporterView;
  username: string;
  password: string;
}

export interface ReplayReport {
  zoneId: string;
  reporterId: number;
  level: Severity;
  observedAt: string;
}

export interface ReplayRequest {
  reports: ReplayReport[];
  edges?: unknown[];
  settings?: Record<string, unknown>;
}

export interface ReplayEscalation {
  zoneId: string;
  level: Severity;
  at: string;
  alertsProduced: number;
}

export interface ReplayAlert {
  originZone: string;
  targetZone: string;
  level: Severity;
  etaMinutes: number;
  hops: number;
  firedAt: string;
  expectedArrival: string;
  wouldDeliver: boolean;
}

export interface ReplayAllClear {
  originZone: string;
  targetZone: string;
  at: string;
}

export interface ReplaySummary {
  reportsReplayed: number;
  zonesEscalated: number;
  alertsPredicted: number;
  alertsDeliverable: number;
  suppressedByUnconfirmedPath: number;
  firstReportAt?: string;
  lastReportAt?: string;
}

export interface ReplayResponse {
  escalations: ReplayEscalation[];
  alerts: ReplayAlert[];
  allClears: ReplayAllClear[];
  summary: ReplaySummary;
}

/* Server-sent events ------------------------------------------------------- */

export interface AlertEvent {
  id: number;
  originZone: string;
  targetZone: string;
  level: Severity;
  etaMinutes: number;
  hops: number;
  firedAt: string;
}

export interface AllClearEvent {
  originZone: string;
  targetZone: string;
  at: string;
}

/** The one sentinel in the contract: a cleared zone sends the string CLEAR. */
export interface ZoneStatusEvent {
  zoneId: string;
  level: Severity | 'CLEAR';
  at: string;
}

export interface ConnectedEvent {
  zones: string[];
  at: string;
}

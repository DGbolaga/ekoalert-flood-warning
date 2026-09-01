# EkoAlert API contract

Everything the frontend needs to talk to the backend. Every payload below was
copied from a live server, not written from memory.

Backend is a Spring Boot service on `http://localhost:8080`. Frontend is React,
Vite, TypeScript and Leaflet, and owns nothing in this document except the
consuming of it.

## What the backend already does

Vetted reporters log rising water. Two independent reports in one zone inside 45
minutes escalate that zone. A propagation engine walks the drainage graph
downstream and produces alerts for zones the water is expected to reach, with a
time to impact. Alerts are only delivered along paths whose every edge has been
confirmed by residents; everything else is recorded and held back. An admin can
halt all outgoing alerts. A past event can be replayed against the graph.

All of that works and is covered by 114 tests. The frontend does not need to
implement any of it, only to display it and to send four kinds of user action:
log in, file a report, correct an edge, subscribe to a zone.

## Running the backend locally

```bash
cd backend

docker run -d --name ekoalert-db \
  -e POSTGRES_DB=ekoalert -e POSTGRES_USER=ekoalert -e POSTGRES_PASSWORD=ekoalert \
  -p 5433:5432 postgis/postgis:16-3.4

./mvnw -DskipTests package

EKOALERT_DB_URL='jdbc:postgresql://localhost:5433/ekoalert?stringtype=unspecified' \
  java -jar app/target/app-0.1.0-SNAPSHOT.jar --seed --demo-users
```

Wait for `SEED demo users ready`. You now have 20 zones, 17 edges, and three
logins: `admin`, `ada`, `bola`, all with password `ekoalert-demo`. `ada` and
`bola` are both vetted for zone `Z01`, which is what makes a quorum reachable.

## Conventions

**Base path** is `/api/v1`. Everything below is relative to it.

**Times** are ISO-8601 instants in UTC, always with a `Z`. Send them that way
too. `new Date(iso)` parses them correctly.

**Null fields are omitted, not sent as null.** This is the single thing most
likely to trip you up. A zone with no survey name has no `name` key at all. A
report that did not reach quorum has no `quorumLevel` key. A delivered alert has
no `suppressedBy` key. Write your types with optional fields and check with `in`
or `?.`, never `=== null`.

**CORS** is configured for `http://localhost:5173` and `http://127.0.0.1:5173`,
which is the Vite default. Allowed methods are GET, POST, OPTIONS; allowed
headers are `Authorization` and `Content-Type`. Credentials are off, because the
token is held in memory rather than in a cookie. If you run the dev server on a
different port, set `ekoalert.cors.allowed-origins` on the backend, comma
separated. An origin that is not on the list gets a 403 on preflight.

**Errors** all have one shape, whatever the status:

```json
{ "error": "bad_request", "message": "reporter 1 is vetted for Z01, not Z07", "at": "2026-09-01T14:22:33.017Z" }
```

| `error` | Status | Meaning |
|---|---|---|
| `unauthorized` | 401 | No token, or the token is expired or unreadable. Log in again. |
| `forbidden` | 403 | Valid token, wrong role. Logging in again will not help. |
| `bad_request` | 400 | The input was wrong in a way the message explains. Show the message. |
| `validation_failed` | 400 | A required field was missing or malformed. `message` names the fields. |
| `unreadable_body` | 400 | The JSON did not parse. |
| `conflict` | 409 | Server state made the request impossible. |

The one exception: `GET /zones/{id}` for an unknown zone returns **404 with an
empty body**. Do not try to parse it.

## Enums

```ts
type Severity   = 'ANKLE' | 'KNEE' | 'IMPASSABLE';   // ordered, least to most severe
type Confidence = 'INFERRED' | 'CONFIRMED' | 'REJECTED';
type Role       = 'REPORTER' | 'ADMIN';
```

`Severity` order matters. Water decays one step per hop as it propagates, so an
IMPASSABLE origin produces KNEE one hop away and ANKLE two hops away.

## Authentication

`POST /auth/login` with a username and password, then send the token as
`Authorization: Bearer <token>` on every authenticated call. There is no refresh
endpoint; the token lasts 12 hours and then the user logs in again.

```
POST /api/v1/auth/login          public
```

Request:
```json
{ "username": "ada", "password": "ekoalert-demo" }
```

200:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "role": "REPORTER",
  "reporterId": 1,
  "expiresAt": "2026-09-02T02:21:42.611Z"
}
```

`reporterId` is absent for admins. Keep the token in memory or `sessionStorage`.
Do not put it in a cookie; the backend does not use cookies and CORS credentials
are off.

401 on a wrong password or an unknown username, with the same message for both so
the form cannot be used to discover which usernames exist.

## Public reads

These need no token. The map has to work for someone who has never signed up, so
do not gate them behind a login screen.

### `GET /zones`

Every zone with its current status. This is the map.

```json
[
  {
    "id": "Z01",
    "corridor": "Drainage Channel",
    "displayName": "Z01",
    "lat": 6.53941,
    "lng": 3.37123,
    "needsFieldNaming": false,
    "status": { "active": false }
  }
]
```

An escalated zone:

```json
{
  "id": "Z01",
  "corridor": "Drainage Channel",
  "displayName": "Z01",
  "lat": 6.53941,
  "lng": 3.37123,
  "needsFieldNaming": false,
  "status": {
    "level": "KNEE",
    "escalatedAt": "2026-09-01T14:54:14Z",
    "active": true
  }
}
```

`name` and `landmark` are absent until a field survey fills them in. Never invent
one. `displayName` is always present and falls back to the id, so bind labels to
`displayName` and treat `name` as a bonus.

`needsFieldNaming: true` marks the four zones that sit more than two kilometres
from any named place on OpenStreetMap. They are not empty land, they need a
person to go and name them. Worth surfacing in an admin view.

### `GET /zones/{id}`

One zone with the edges in and out of it. This is the panel that opens when
somebody taps a zone.

```json
{
  "zone": { "...": "as above" },
  "outbound": [
    {
      "id": 2,
      "fromZone": "Z02",
      "toZone": "Z03",
      "travelMinutes": 17,
      "distanceM": 943,
      "confidence": "INFERRED",
      "blocked": false,
      "alertable": false,
      "inferenceBasis": "next zone south along corridor",
      "updatedAt": "2026-09-01T14:21:27.693Z",
      "confirmations": 0,
      "rejections": 0
    }
  ],
  "inbound": [ "..." ]
}
```

`confirmations` and `rejections` are counts of **distinct residents**, not taps,
and only appear here. They are what you show next to a confirm button so the user
can see how close an edge is to flipping.

404 with an empty body for an unknown zone.

### `GET /graph`

The whole graph in one call, for drawing. Same zone shape, same edge shape
without the vote counts, plus a summary.

```json
{
  "zones": [ "..." ],
  "edges": [ "..." ],
  "counts": { "zones": 20, "edges": 17, "inferred": 17, "confirmed": 0, "rejected": 0, "blocked": 0 }
}
```

Inferred edges are included on purpose. The map is complete on day one and almost
silent on day one, and hiding unconfirmed edges would hide exactly what residents
are being asked to correct. Draw them differently, do not drop them.

## Resident actions

### `POST /subscriptions`

Public, no login. Warning people should be easy to opt into.

```json
{ "zoneId": "Z02", "address": "resident-1", "channel": "sse" }
```

`channel` is optional and defaults to `"sse"`. `address` is opaque to the
backend: whatever identifies this subscriber on that channel. 201:

```json
{ "id": 1, "zoneId": "Z02", "channel": "sse", "address": "resident-1" }
```

Subscribing twice with the same three values returns the existing row rather than
creating a duplicate, so you can call it freely.

400 with `bad_request` if the zone does not exist.

## Reporter actions

All need `Authorization: Bearer <token>` from a `REPORTER` login.

### `POST /reports`

```json
{ "level": "IMPASSABLE", "drainBlocked": true, "observedAt": "2026-06-15T12:00:00Z" }
```

`level` is required. `zoneId` is optional and, if you send it, must equal the
reporter's own zone; a reporter is vetted for one zone and sending another is a
400 rather than a silent override. Simplest thing is to omit it. `observedAt` is
optional and defaults to now, and exists so somebody can log water they saw
twenty minutes ago on the walk home. `drainBlocked` is the optional one tap extra
field; omit it entirely when the reporter said nothing about the drain, because
absent is not the same as "the drain is clear".

201, no quorum yet:
```json
{
  "reportId": 1,
  "zoneId": "Z01",
  "level": "IMPASSABLE",
  "observedAt": "2026-06-15T12:00:00Z",
  "countedTowardQuorum": false,
  "escalated": false,
  "alerts": []
}
```

201, quorum reached and the zone escalated:
```json
{
  "reportId": 2,
  "zoneId": "Z01",
  "level": "IMPASSABLE",
  "observedAt": "2026-06-15T12:10:00Z",
  "countedTowardQuorum": true,
  "quorumLevel": "IMPASSABLE",
  "escalated": true,
  "alerts": [
    { "id": 1, "originZone": "Z01", "targetZone": "Z02", "level": "KNEE",  "etaMinutes": 16, "hops": 1,
      "firedAt": "2026-06-15T12:10:00Z", "suppressedBy": "inferred_edge" },
    { "id": 2, "originZone": "Z01", "targetZone": "Z03", "level": "ANKLE", "etaMinutes": 33, "hops": 2,
      "firedAt": "2026-06-15T12:10:00Z", "suppressedBy": "inferred_edge" }
  ]
}
```

`suppressedBy` is **absent when the alert was actually delivered**. When present
it is one of:

| Value | What to tell the user |
|---|---|
| `inferred_edge` | The path runs through an edge nobody has confirmed. Nothing was sent. |
| `kill_switch` | An admin has halted alerting. Nothing was sent. |

`escalated: false` with a `quorumLevel` present means there was a quorum but the
zone was already alerting at that level or worse, so nothing was re-propagated.
That is deliberate: re-firing on every neighbouring report would train people to
ignore the alerts.

### `POST /edges/{id}/confirm` and `POST /edges/{id}/reject`

One tap. No body, no form. A form gets used by nobody.

```
POST /api/v1/edges/1/confirm
Authorization: Bearer <token>
```

200:
```json
{
  "correctionId": 2,
  "action": "confirm",
  "fromZone": "Z01",
  "toZone": "Z02",
  "distinctVoices": 2,
  "threshold": 2,
  "thresholdMet": true,
  "edge": { "id": 1, "confidence": "CONFIRMED", "alertable": true, "...": "full edge" }
}
```

`distinctVoices` counts people, not taps, so the same login tapping twice does not
move it. `thresholdMet` is true only on the tap that actually flipped the edge,
which is your cue to show something celebratory. `edge` is the edge after the tap,
so you can update state from the response without refetching.

400 with `bad_request` for an unknown edge id.

### `POST /edges/propose`

For the junction edges between corridors that inference deliberately left blank.

```json
{ "fromZone": "Z08", "toZone": "Z11" }
```

Below the threshold, no edge exists yet, so `edge` is absent:
```json
{ "correctionId": 3, "action": "propose", "fromZone": "Z08", "toZone": "Z11",
  "distinctVoices": 1, "threshold": 2, "thresholdMet": false }
```

On the tap that crosses the threshold the edge is created, and it arrives
`INFERRED`, not confirmed. Proposing is not the same as confirming.

```json
{ "correctionId": 4, "action": "propose", "fromZone": "Z08", "toZone": "Z11",
  "distinctVoices": 2, "threshold": 2, "thresholdMet": true,
  "edge": { "id": 18, "travelMinutes": 132, "distanceM": 7234, "confidence": "INFERRED",
            "alertable": false,
            "inferenceBasis": "resident proposal, timing estimated at 55 m/min pending observation" } }
```

## Admin actions

Need an `ADMIN` token. A `REPORTER` token gets 403.

### `POST /admin/kill-switch`

```json
{ "enabled": false }
```
200: `{ "alertsEnabled": false, "at": "2026-09-01T14:23:59.254Z" }`

This is a first class feature, not a settings toggle buried three screens deep.
Give it a prominent, unambiguous control and show the current state at all times.
When it is off, propagation still runs and alert rows are still written and
marked, so the operator can see what would have gone out.

### `POST /admin/reporters/{id}/suspend`

```json
{ "suspended": true }
```
The body is optional and defaults to suspending; lifting a suspension takes an
explicit `false`. 200 returns the reporter:

```json
{ "id": 2, "zoneId": "Z01", "displayName": "bola", "suspended": true,
  "verifiedAt": "2026-09-01T14:23:58.415Z" }
```

A suspended reporter's reports are still stored, so the audit trail stays
complete, but they never count toward a quorum.

### `POST /replay`

Runs a past event through the whole pipeline with delivery disabled and returns
what would have fired. Writes nothing, sends nothing, ignores the kill switch.

```json
{
  "reports": [
    { "zoneId": "Z01", "reporterId": 1, "level": "IMPASSABLE", "observedAt": "2025-07-08T06:00:00Z" },
    { "zoneId": "Z01", "reporterId": 2, "level": "IMPASSABLE", "observedAt": "2025-07-08T06:20:00Z" }
  ]
}
```

Optional `edges` replays against a hypothetical graph instead of the live one.
Optional `settings` overrides `maxHops`, `requireConfirmedEdges`, `quorumWindow`,
`quorumSize`, `deEscalationAfter`; durations are ISO-8601 like `"PT45M"`.

200:
```json
{
  "escalations": [ { "zoneId": "Z01", "level": "IMPASSABLE", "at": "2025-07-08T06:20:00Z", "alertsProduced": 2 } ],
  "alerts": [
    { "originZone": "Z01", "targetZone": "Z02", "level": "KNEE", "etaMinutes": 16, "hops": 1,
      "firedAt": "2025-07-08T06:20:00Z", "expectedArrival": "2025-07-08T06:36:00Z", "wouldDeliver": false }
  ],
  "allClears": [],
  "summary": {
    "reportsReplayed": 2, "zonesEscalated": 1, "alertsPredicted": 2,
    "alertsDeliverable": 0, "suppressedByUnconfirmedPath": 2,
    "firstReportAt": "2025-07-08T06:00:00Z", "lastReportAt": "2025-07-08T06:20:00Z"
  }
}
```

`expectedArrival` is the number to compare against what actually happened.
`suppressedByUnconfirmedPath` is usually the interesting figure: it is the cost,
in warnings, of edges nobody has confirmed yet. The same request always replays
to the same result, so a replay view can be cached and diffed.

This is the project defence demo. A timeline of predicted arrivals against
observed ones is worth building properly.

## The live stream

```
GET /api/v1/alerts/stream            public, text/event-stream
GET /api/v1/alerts/stream?zones=Z01,Z02   filtered
```

Public, so plain `EventSource` works. That matters, because `EventSource` cannot
set an `Authorization` header, and you would otherwise need a polyfill.

```ts
const es = new EventSource(`${BASE}/alerts/stream`);
es.addEventListener('alert',       e => onAlert(JSON.parse(e.data)));
es.addEventListener('all-clear',   e => onAllClear(JSON.parse(e.data)));
es.addEventListener('zone-status', e => onZoneStatus(JSON.parse(e.data)));
es.addEventListener('connected',   e => setConnected(true));
```

Real events from a live server:

```
event:connected
data:{"zones":[],"at":"2026-09-01T12:03:18.518Z"}

event:alert
data:{"level":"KNEE","hops":1,"firedAt":"2026-06-15T20:10:00Z","targetZone":"Z02","originZone":"Z01","id":7,"etaMinutes":16}

event:zone-status
data:{"zoneId":"Z01","level":"IMPASSABLE","at":"2026-06-15T20:10:00Z"}

event:all-clear
data:{"targetZone":"Z02","at":"2026-09-01T12:03:24.722Z","originZone":"Z01"}

event:zone-status
data:{"zoneId":"Z01","level":"CLEAR","at":"2026-09-01T12:03:24.722Z"}
```

Note `zone-status` uses the literal string `"CLEAR"` for a zone that has cleared,
rather than omitting the level. It is the one place a sentinel is used instead of
an absent field.

The connection times out after 30 minutes. `EventSource` reconnects on its own,
but there is no event replay, so refetch `GET /graph` on reconnect rather than
assuming you missed nothing.

Only alerts and all-clears are gated by the kill switch. Zone status keeps
flowing while alerting is halted, because an admin who has just pulled the switch
still needs to see what the system thinks is happening.

## Rules the UI has to respect

These are not style preferences. Breaking them breaks the design of the system.

**Draw inferred edges, and draw them as unconfirmed.** The map is complete on day
one and nearly silent on day one. That gap is what residents are being asked to
close. An edge is only live when `alertable` is true, which is derived on the
server as `confidence === 'CONFIRMED' && !blocked`. Do not recompute it, just
read it. Three visual states earn their keep: inferred, confirmed, rejected, with
blocked shown on top of any of them.

**Correction is one tap.** Not a modal, not a form, not a confirmation dialog.
Tap an edge, confirm or reject, done. Show `distinctVoices` of `threshold` so the
user can see the edge moving, and make something visible happen when
`thresholdMet` comes back true. That moment is the product.

**Never invent a zone name.** `name` and `landmark` come from a field survey that
has not happened. Bind to `displayName`, which falls back to the zone id.

**The all-clear matters as much as the alert.** If people never see one they stop
trusting the warnings. Give `all-clear` the same visual weight as `alert`, do not
treat it as a quiet dismissal.

**Show why an alert was not delivered.** A row with `suppressedBy` is not a
failure to hide, it is the system explaining itself. `inferred_edge` in
particular is an invitation to go and confirm that edge.

**The kill switch is a first class control.** Prominent, unambiguous, current
state always visible.

## TypeScript types

Optional fields are optional because the server omits nulls. This compiles
against every payload above.

```ts
export type Severity   = 'ANKLE' | 'KNEE' | 'IMPASSABLE';
export type Confidence = 'INFERRED' | 'CONFIRMED' | 'REJECTED';
export type Role       = 'REPORTER' | 'ADMIN';

export interface ApiError { error: string; message: string; at: string; }

export interface LoginResponse {
  token: string; role: Role; reporterId?: number; expiresAt: string;
}

export interface ZoneStatusView {
  level?: Severity; escalatedAt?: string; clearedAt?: string; active: boolean;
}

export interface ZoneSummary {
  id: string; corridor: string;
  name?: string; landmark?: string;
  displayName: string;
  lat: number; lng: number;
  needsFieldNaming: boolean;
  status: ZoneStatusView;
}

export interface EdgeView {
  id: number; fromZone: string; toZone: string;
  travelMinutes: number; distanceM?: number;
  confidence: Confidence; blocked: boolean;
  alertable: boolean;                 // derived server side, do not recompute
  inferenceBasis?: string; updatedAt: string;
  confirmations?: number;             // zone detail only
  rejections?: number;                // zone detail only
}

export interface ZoneDetail { zone: ZoneSummary; outbound: EdgeView[]; inbound: EdgeView[]; }

export interface GraphResponse {
  zones: ZoneSummary[]; edges: EdgeView[];
  counts: { zones: number; edges: number; inferred: number; confirmed: number; rejected: number; blocked: number; };
}

export interface AlertView {
  id: number; originZone: string; targetZone: string;
  level: Severity; etaMinutes: number; hops: number; firedAt: string;
  suppressedBy?: 'inferred_edge' | 'kill_switch';   // absent means delivered
}

export interface ReportRequest {
  level: Severity; zoneId?: string; drainBlocked?: boolean; observedAt?: string;
}

export interface ReportResponse {
  reportId: number; zoneId: string; level: Severity; observedAt: string;
  countedTowardQuorum: boolean; quorumLevel?: Severity;
  escalated: boolean; alerts: AlertView[];
}

export interface CorrectionResponse {
  correctionId: number; action: 'confirm' | 'reject' | 'propose';
  fromZone: string; toZone: string;
  distinctVoices: number; threshold: number; thresholdMet: boolean;
  edge?: EdgeView;                    // absent for a proposal below threshold
}

export interface SubscriptionResponse { id: number; zoneId: string; channel: string; address: string; }

// Server-sent events
export interface AlertEvent      { id: number; originZone: string; targetZone: string;
                                   level: Severity; etaMinutes: number; hops: number; firedAt: string; }
export interface AllClearEvent   { originZone: string; targetZone: string; at: string; }
export interface ZoneStatusEvent { zoneId: string; level: Severity | 'CLEAR'; at: string; }
export interface ConnectedEvent  { zones: string[]; at: string; }
```

## Endpoint summary

| Method | Path | Auth | Success |
|---|---|---|---|
| POST | `/auth/login` | public | 200 |
| GET | `/zones` | public | 200 |
| GET | `/zones/{id}` | public | 200, 404 empty body |
| GET | `/graph` | public | 200 |
| GET | `/alerts/stream` | public | 200 event stream |
| POST | `/subscriptions` | public | 201 |
| POST | `/reports` | reporter | 201 |
| POST | `/edges/{id}/confirm` | reporter | 200 |
| POST | `/edges/{id}/reject` | reporter | 200 |
| POST | `/edges/propose` | reporter | 200 |
| POST | `/admin/kill-switch` | admin | 200 |
| POST | `/admin/reporters/{id}/suspend` | admin | 200 |
| POST | `/replay` | admin | 200 |

## Suggested order of work

1. `GET /graph` on a Leaflet map. Zones as markers, edges as directed lines,
   styled by `confidence` and `blocked`. This alone is a working deliverable and
   needs no login.
2. `EventSource` on `/alerts/stream`, recolouring zones on `zone-status` and
   showing alerts and all-clears as they arrive. Open two browser tabs and run
   `backend/demo.sh` to watch it move.
3. Login, then the reporter flow: a big obvious control for the three severities,
   and the optional blocked drain tap.
4. The one tap correction on edges, with the vote count visible. This is the
   feature the pilot exists to exercise.
5. Admin: kill switch, reporter suspension, and the replay view.

## Not built yet, if you need them

No endpoint currently lists past alerts, lists corrections for audit, edits the
graph as an admin, or registers a new reporter. Say which you need and they are
small additions on top of what is already there; the repositories already support
all four.

# EkoAlert backend — build brief

Read `CLAUDE.md` first for project context and the graph model. This document
covers what to build and how it will be verified.

Frontend is being built separately. Build the backend only, plus enough of a
seed/demo path that the API can be exercised without a UI.

---

## 1. Module layout

Single Spring Boot deployable, four modules, dependencies point one way only:

```
ekoalert/
  engine/        pure Java 21, NO Spring, NO JPA, NO database imports
  domain/        entities, repositories, Flyway migrations
  api/           controllers, DTOs, auth, SSE
  app/           Spring Boot main class, wiring, config
```

`engine` must compile and its tests must run with the database absent and
Spring absent. If `engine` ever imports `org.springframework` or `jakarta.persistence`,
that is a build failure, not a style problem. Enforce it with an ArchUnit test.

---

## 2. Schema

Flyway migration `V1__initial.sql`. PostGIS enabled.

```sql
CREATE TABLE zone (
  id              TEXT PRIMARY KEY,            -- 'Z01'
  corridor        TEXT NOT NULL,
  name            TEXT,                        -- null until field survey
  landmark        TEXT,
  location        GEOGRAPHY(POINT,4326) NOT NULL,
  needs_field_naming BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TYPE edge_confidence AS ENUM ('inferred','confirmed','rejected');

CREATE TABLE edge (
  id              BIGSERIAL PRIMARY KEY,
  from_zone       TEXT NOT NULL REFERENCES zone(id),
  to_zone         TEXT NOT NULL REFERENCES zone(id),
  travel_minutes  INT  NOT NULL,
  distance_m      INT,
  confidence      edge_confidence NOT NULL DEFAULT 'inferred',
  blocked         BOOLEAN NOT NULL DEFAULT FALSE,
  inference_basis TEXT,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (from_zone, to_zone),
  CHECK (from_zone <> to_zone)
);

CREATE TYPE severity AS ENUM ('ankle','knee','impassable');

CREATE TABLE reporter (
  id          BIGSERIAL PRIMARY KEY,
  zone_id     TEXT NOT NULL REFERENCES zone(id),
  display_name TEXT NOT NULL,
  phone       TEXT NOT NULL UNIQUE,
  verified_at TIMESTAMPTZ,
  suspended   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE report (
  id          BIGSERIAL PRIMARY KEY,
  zone_id     TEXT NOT NULL REFERENCES zone(id),
  reporter_id BIGINT NOT NULL REFERENCES reporter(id),
  level       severity NOT NULL,
  drain_blocked BOOLEAN,                       -- optional one-tap field
  observed_at TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON report (zone_id, observed_at DESC);

CREATE TABLE zone_status (
  zone_id     TEXT PRIMARY KEY REFERENCES zone(id),
  level       severity,
  escalated_at TIMESTAMPTZ,
  cleared_at  TIMESTAMPTZ
);

CREATE TABLE alert (
  id            BIGSERIAL PRIMARY KEY,
  origin_zone   TEXT NOT NULL REFERENCES zone(id),
  target_zone   TEXT NOT NULL REFERENCES zone(id),
  level         severity NOT NULL,
  eta_minutes   INT NOT NULL,
  hops          INT NOT NULL,
  fired_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  suppressed_by TEXT                            -- 'kill_switch', 'inferred_edge', null
);

CREATE TABLE edge_correction (
  id          BIGSERIAL PRIMARY KEY,
  edge_id     BIGINT REFERENCES edge(id),
  from_zone   TEXT NOT NULL,
  to_zone     TEXT NOT NULL,                    -- proposed target
  reporter_id BIGINT REFERENCES reporter(id),
  action      TEXT NOT NULL,                    -- 'confirm' | 'reject' | 'propose'
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE system_flag (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
INSERT INTO system_flag VALUES ('alerts_enabled','true');
```

`subscription` (resident to zone) can wait until residents exist. Add it when
you build the subscriber endpoints.

---

## 3. Engine contract

The engine takes an immutable snapshot of the graph and an origin, and returns
alerts. It performs no I/O.

```java
package ng.ekoalert.engine;

public record ZoneId(String value) {}

public enum Severity { ANKLE, KNEE, IMPASSABLE }

public enum Confidence { INFERRED, CONFIRMED, REJECTED }

public record Edge(ZoneId from, ZoneId to, int travelMinutes,
                   Confidence confidence, boolean blocked) {}

public record DrainageGraph(List<Edge> edges) {
    // adjacency built once in the constructor
}

public record PropagatedAlert(ZoneId target, Severity level,
                              int etaMinutes, int hops) {}

public record PropagationConfig(int maxHops, boolean requireConfirmedEdges) {}

public interface PropagationEngine {
    List<PropagatedAlert> propagate(DrainageGraph graph,
                                    ZoneId origin,
                                    Severity originLevel,
                                    PropagationConfig config);
}
```

Rules, in this order:

1. Traversal is **best-first by cumulative ETA**, using a priority queue.
   Nearest-in-time zones are settled first.
2. **Cycles must be tolerated.** Lagos drainage has them. Keep a settled set;
   a zone is emitted at most once, at its lowest ETA.
3. **Blocked edges are not traversed.** `blocked == true` means no propagation
   through that edge.
4. **Rejected edges are not traversed**, ever.
5. **Inferred edges are traversed for display but produce non-alertable
   results** when `requireConfirmedEdges` is true. Any path containing at least
   one inferred edge yields an alert marked non-alertable by the caller. Simplest
   correct approach: the engine returns all reachable zones plus a flag on each
   result indicating whether its path was fully confirmed. Add
   `boolean pathConfirmed` to `PropagatedAlert`.
6. **Severity decays by hop**: IMPASSABLE at hop 1 becomes KNEE at hop 2 and
   ANKLE at hop 3. Never escalates. A path that decays below ANKLE is dropped.
7. **maxHops caps depth**, default 3.
8. ETA is the sum of `travelMinutes` along the path.
9. Self-edges are impossible by schema; the engine must still not loop forever
   if given one.

---

## 4. Quorum and escalation

Lives in `domain`, not `engine`.

A zone escalates when **two reports from two distinct non-suspended verified
reporters in that zone, with `observed_at` inside a 45-minute window, both at
ANKLE or above**. The zone level is the lower of the two levels (conservative).

One report never escalates. Two reports from the same reporter never escalate.
A suspended reporter's reports never count.

De-escalation: if no new report arrives in the zone for 90 minutes, the zone
clears and an all-clear is emitted to everyone who received an alert from it.
The all-clear matters as much as the alert. If people never see one, they stop
trusting the warnings.

---

## 5. Kill switch

`system_flag.alerts_enabled`. When false, propagation still runs and still
writes to `alert`, but every row gets `suppressed_by = 'kill_switch'` and
nothing is delivered. Admin endpoint toggles it. Ships in the first sprint that
has any alerting at all, not later.

---

## 6. API surface (v1)

```
POST   /api/v1/reports                  reporter files a report
GET    /api/v1/zones                    all zones with current status
GET    /api/v1/zones/{id}               zone detail + inbound/outbound edges
GET    /api/v1/graph                    full graph for the map
POST   /api/v1/edges/{id}/confirm       one-tap resident confirmation
POST   /api/v1/edges/{id}/reject        one-tap rejection
POST   /api/v1/edges/propose            propose a missing edge
GET    /api/v1/alerts/stream            SSE, live alerts
POST   /api/v1/admin/kill-switch        {enabled: bool}
POST   /api/v1/admin/reporters/{id}/suspend
POST   /api/v1/replay                   run a scenario, see section 8
```

JWT auth. Reporters and admins are authenticated; the map and graph reads are
public.

---

## 7. Testing strategy

Four layers. The first two are where correctness lives.

**Layer 1 — engine unit tests, no Spring, no database.**
Fast, deterministic, hand-built graphs. This is the bulk of the test suite.
Cover at minimum: linear chain, cycle, blocked edge, rejected edge, inferred
edge with `requireConfirmedEdges` both ways, severity decay to nothing,
maxHops boundary, diamond graph where two paths reach the same zone at
different ETAs (the lower must win), and a zone with no outbound edges.

**Layer 2 — the golden scenario.** See section 8. Written before the engine.
Do not modify it to make it pass.

**Layer 3 — integration tests with Testcontainers PostGIS.**
Quorum logic, escalation, de-escalation, kill switch suppression, correction
recording. Real database, real migrations.

**Layer 4 — API tests with MockMvc.** Status codes, auth, payload shapes.

Rules for the agent building this:

- Write Layer 1 tests **before** the implementation they cover.
- Never change an assertion to make a test pass. Change the code.
- The golden scenario file is immutable. If it fails, the engine is wrong.
- No mocking inside the engine. It is pure functions over records; mocks there
  would only test the mocks.

---

## 8. The golden scenario

This is the fixed acceptance case. Put it at
`engine/src/test/resources/golden/scenario-01.json` and drive a test from it.
Expected outputs were worked out by hand and are not negotiable.

Graph (a five-zone chain plus one branch and one cycle):

```
A --20min--> B --15min--> C --10min--> D
             B --30min--> E
             D --25min--> B        (cycle back)
```

All edges CONFIRMED and unblocked unless stated.

**Case 1 — origin A, IMPASSABLE, maxHops 3, requireConfirmedEdges true**

| target | level      | eta | hops |
|--------|-----------|-----|------|
| B      | KNEE      | 20  | 1    |
| C      | ANKLE     | 35  | 2    |
| E      | ANKLE     | 50  | 2    |

D is not present: it would be hop 3, and IMPASSABLE decays to nothing below
ANKLE after three hops. B is never re-emitted despite the cycle from D.

**Case 2 — origin A, ANKLE**

No alerts at all. ANKLE decays below ANKLE at hop 1.

**Case 3 — origin A, IMPASSABLE, edge B->C blocked**

| target | level | eta | hops |
|--------|-------|-----|------|
| B      | KNEE  | 20  | 1    |
| E      | ANKLE | 50  | 2    |

C and D unreachable.

**Case 4 — origin A, IMPASSABLE, edge A->B is INFERRED, requireConfirmedEdges true**

Same three rows as Case 1, but every row has `pathConfirmed = false`. The
engine still returns them; the caller suppresses delivery.

**Case 5 — origin D, IMPASSABLE**

| target | level | eta | hops |
|--------|-------|-----|------|
| B      | KNEE  | 25  | 1    |
| C      | ANKLE | 40  | 2    |
| E      | ANKLE | 55  | 2    |

Proves the cycle is traversable in the other direction and terminates.

---

## 9. Replay mode

`POST /api/v1/replay` takes a graph snapshot and a list of timestamped reports,
runs the whole pipeline with alert delivery disabled, and returns what would
have fired and when. This is both the validation mechanism for the graph and
the demo for the project defence. Build it no later than the sprint after the
engine works.

---

## 10. Seed loader

A command that reads `ekoalert_zones.csv` and populates `zone` and `edge`.
Idempotent: running it twice must not duplicate edges. Rows with an empty
`drains_into` produce a zone and no outbound edge. Every seeded edge gets
`confidence = 'inferred'`.

Verify after loading: 20 zones, 17 edges, 0 confirmed. (21 rows minus the
Z09/Z14 duplicate, which the loader should reject as a distinct-coordinate
violation or which will already be removed from the CSV.)

---

## 11. Definition of done for the backend

- `./mvnw test` green, including the golden scenario.
- ArchUnit test proves `engine` has no Spring or JPA imports.
- Seed loader runs against a fresh database and reports the counts above.
- `POST /api/v1/reports` twice into one zone escalates it; once does not.
- With the kill switch off, an escalation writes `alert` rows all marked
  `suppressed_by = 'kill_switch'` and delivers nothing.
- With all edges inferred, an escalation delivers nothing.
- After confirming a path's edges, the same escalation delivers alerts.
- Replay of a scenario file returns a deterministic result.

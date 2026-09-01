# EkoAlert backend

Java 21, Spring Boot 3.3, PostgreSQL with PostGIS. One deployable, four modules,
dependencies pointing one way only.

```
engine/   pure Java. No Spring, no JPA, no database, no I/O.
domain/   entities, repositories, Flyway migrations, quorum, escalation, replay, seed
api/      controllers, DTOs, JWT auth, server-sent events
app/      Spring Boot entry point, wiring, seed command
```

`engine` compiles and its tests run with Spring and the database absent. Two
ArchUnit tests enforce that: `EnginePurityTest` in the engine module, and
`ModuleBoundaryTest` in app, which checks the layering across the assembled
classpath.

## Build and test

```bash
./mvnw test
```

One command runs everything: engine unit tests, the golden scenario, the domain
unit tests, the Testcontainers layer and the API tests. Surefire is configured to
pick up `*IT` classes as well as `*Test`, because the definition of done is a
single green `./mvnw test` rather than a phase somebody has to remember.

The Testcontainers layer needs a running Docker daemon. It pulls
`postgis/postgis:16-3.4` on first run.

Current state: 111 tests, all passing.

| Layer | Where | Count |
|---|---|---|
| 1. Engine units, no Spring, no database | `engine` | 23 |
| 2. Golden scenario | `engine` | 5 cases |
| 2b. Engine purity | `engine` | 4 |
| 3. Domain units, no database | `domain` | 21 |
| 4. Testcontainers PostGIS | `app` | 29 |
| 5. API, MockMvc and real HTTP | `app` | 27 |
| 6. Module boundaries | `app` | 2 |

## Run it

```bash
# 5433 on the host, because a local PostgreSQL is usually already on 5432.
docker run -d --name ekoalert-db \
  -e POSTGRES_DB=ekoalert -e POSTGRES_USER=ekoalert -e POSTGRES_PASSWORD=ekoalert \
  -p 5433:5432 postgis/postgis:16-3.4

./mvnw -DskipTests package

EKOALERT_DB_URL='jdbc:postgresql://localhost:5433/ekoalert?stringtype=unspecified' \
  java -jar app/target/app-0.1.0-SNAPSHOT.jar --seed --demo-users
```

`--seed` loads `ekoalert_zones.csv` into `zone` and `edge`. It is idempotent.
`--demo-users` creates an admin and two reporters vetted for the same zone, which
is what makes a quorum reachable from the command line. Both are optional; with
neither flag the app just starts.

A fresh seed reports:

```
SEED zones=20 edges=17 confirmed=0 (inserted 20 zones, 17 edges; merged 1 duplicates)
SEED merged: Z14 merged into Z09
SEED skipped: Z09 to Z14: both sides collapsed onto Z09 once duplicates merged
```

`./demo.sh` walks the whole path against a running instance: quorum, the
confidence gate, one-tap confirmation, the kill switch, de-escalation and replay.

## Try it yourself

Five steps, about ten minutes. Steps 1 and 5 are the ones that actually prove
something; 2 to 4 are for seeing it move.

### 1. Run the tests

```bash
./mvnw test
```

Needs Docker running, and pulls `postgis/postgis:16-3.4` the first time. Expect
111 tests and `BUILD SUCCESS` in around a minute. This alone covers the whole
definition of done: the golden scenario, quorum, the kill switch, de-escalation,
the seed counts and the API contract.

### 2. Start it

```bash
docker run -d --name ekoalert-db \
  -e POSTGRES_DB=ekoalert -e POSTGRES_USER=ekoalert -e POSTGRES_PASSWORD=ekoalert \
  -p 5433:5432 postgis/postgis:16-3.4

./mvnw -DskipTests package

EKOALERT_DB_URL='jdbc:postgresql://localhost:5433/ekoalert?stringtype=unspecified' \
  java -jar app/target/app-0.1.0-SNAPSHOT.jar --seed --demo-users \
       --ekoalert.de-escalation-sweep=PT5S
```

Wait for `SEED demo users ready`. The short sweep interval is only so the demo
does not sit for ninety minutes waiting for a zone to clear.

### 3. Watch the whole story

In a second terminal:

```bash
./demo.sh
```

It walks nine steps: a full silent map, one report doing nothing, two reports
escalating but delivering nothing, residents confirming edges one tap at a time,
the same escalation now delivering, the kill switch, an escalation with alerting
halted, and a replay. Step 6 is the transition the pilot exists to produce.

### 4. Watch it live

In a third terminal, before running `demo.sh`:

```bash
curl -N http://localhost:8080/api/v1/alerts/stream
```

You will see `connected`, then `alert` events as zones escalate, `zone-status` as
the map changes, and `all-clear` when the zone goes quiet. That stream is what the
Leaflet frontend will consume.

### 5. Prove the tests are not lying

The most convincing thing you can do, and worth showing at the defence. Break the
engine on purpose:

In `engine/src/main/java/ng/ekoalert/engine/BestFirstPropagationEngine.java`,
find this line in `emit`:

```java
Optional<Severity> level = originLevel.decayedBy(step.hops());
```

Change it to `Optional.of(originLevel)`, which stops severity decaying with
distance. Then:

```bash
./mvnw -pl engine test
```

17 of the 23 unit tests and 4 of the 5 golden cases fail. Put the line back and
they pass again. That is the difference between a suite that checks behaviour and
one that just runs.

Poke at it by hand too:

```bash
BASE=http://localhost:8080/api/v1
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"ada","password":"ekoalert-demo"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s $BASE/graph | python3 -m json.tool | head -40
curl -s $BASE/zones/Z01 | python3 -m json.tool

# one report: escalated is false
curl -s -X POST $BASE/reports -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"level":"IMPASSABLE","observedAt":"2026-06-15T22:00:00Z"}' | python3 -m json.tool
```

Things worth trying to break:

- File both reports from the same reporter. It never escalates.
- Put the two reports more than 45 minutes apart. It never escalates.
- Suspend a reporter via `POST /api/v1/admin/reporters/{id}/suspend` as admin,
  then have them report. Stored, never counted.
- Confirm an edge twice from the same login. The count does not move.
- Send `"drainBlocked": true` on a report. The zone's outbound edges block and
  propagation stops.

### Cleaning up

```bash
docker rm -f ekoalert-db
```


## Configuration

Everything lives under `ekoalert` in `application.yml`. The defaults are the
numbers in the build brief: 45 minute quorum window, quorum of 2, 90 minute
de-escalation, 3 hops, confirmation required, correction threshold of 2.

Set `EKOALERT_JWT_SECRET` before this reaches anyone outside the team. The
built-in development secret logs a warning on startup.

`ekoalert.cors.allowed-origins` defaults to the Vite dev server on
`http://localhost:5173` and `http://127.0.0.1:5173`. Set it, comma separated, if
the frontend runs anywhere else. It is a list rather than a wildcard on purpose:
the report and correction endpoints are authenticated, and a wildcard would let
any page a reporter has open act with their token.

## Things worth knowing before you change anything

**The golden scenario is immutable.** `engine/src/test/resources/golden/scenario-01.json`
and its expected outputs are fixed. If a case fails, the engine is wrong.

**Severity decay is `origin - hops`, starting at the first hop.** The brief's
prose in section 3 rule 6 says IMPASSABLE becomes KNEE at hop 2, but every golden
table puts an IMPASSABLE origin at KNEE on hop 1, and case 2 has an ANKLE origin
producing nothing at all. The tables are the specification, so the code follows
them. The prose is off by one.

**`requireConfirmedEdges` moves the flag, not the result set.** Rule 5 and golden
case 4 together require the engine to return unconfirmed paths rather than filter
them, so the parameter cannot be a filter. When true, `pathConfirmed` reports
honestly whether every edge on the winning path was confirmed. When false, the
run is not gating on confidence and every result comes back deliverable. Either
way the same zones are returned.

**A zone is settled once, at its lowest ETA.** That is rule 2 taken literally. It
has a consequence worth a decision from you: if the fastest path to a zone decays
below ANKLE, that zone is dropped even when a slower path with fewer hops would
have carried a real level. Since severity depends on hops and ETA does not, the
two can disagree. The current behaviour is the conservative one and matches the
brief; if you would rather never lose a warning that way, the fix is to settle on
`(hops, eta)` and it is about ten lines. Flagging it rather than deciding it.

**The kill switch gates alerts and all-clears, not the map.** Propagation still
runs and every alert row is still written and marked `suppressed_by =
'kill_switch'`, which is what makes the halt auditable. Zone status keeps flowing
to the live stream, because an admin who has just pulled the switch still needs to
see what the system thinks is happening. Say so if you want the map frozen too.

**Suppression precedence is kill switch first, then unconfirmed path.** An
operator who has halted alerting wants the log to say that, not that the edge was
inferred.

## Additions beyond the brief

The brief left these open, so they are choices rather than requirements. Each is
easy to reverse.

- `V2__subscription.sql` adds `subscription` and `alert_delivery`. The all-clear
  goes to everyone who received an alert, so it needs a record of who actually
  received one. A suppressed alert reaches nobody and produces no all-clear.
- `V3__auth.sql` adds `app_user`. The brief specifies JWT auth for reporters and
  admins but gives no user table. A `reporter` row is the field identity; an
  `app_user` row is the login.
- A report's `drain_blocked` flag sets or clears `blocked` on the reporting
  zone's outbound edges. One report is enough in either direction, because it is
  an observation about a physical object in front of the reporter rather than a
  claim about where water goes.
- A proposed edge, once enough separate residents have proposed it, is created
  with its travel time estimated from the great-circle distance at the same
  provisional 55 m/min that seeded the CSV. It arrives `inferred`. Proposing is
  not confirming.
- Quorum generalises past two reporters: each reporter contributes their most
  severe reading, and the level is the one at the quorum position. For two that is
  exactly the lower of the two levels the brief asks for.
- A zone already alerting is not re-propagated unless the level gets worse.
  Re-firing on every neighbouring report would train people to ignore it.

## Build details you would otherwise rediscover the hard way

- **`-parameters` is set explicitly.** This build imports the Spring Boot BOM
  rather than inheriting from `spring-boot-starter-parent`, so it does not get
  that flag for free. Without it Spring cannot read `@PathVariable` and
  `@RequestParam` names and those endpoints fail at runtime with a 400.
- **`stringtype=unspecified` is on the JDBC URL.** The schema uses PostgreSQL
  enum types with lowercase labels. This lets the driver hand plain text to those
  columns and lets the server coerce it, which keeps the migration exactly as the
  brief wrote it and the Java enums idiomatic.
- **`docker.api.version` is pinned to 1.44.** docker-java still negotiates API
  1.32 by default and Docker Engine 29 refuses anything below 1.40. Override with
  `-Ddocker.api.version=` if your engine is older.
- **`AuthorizationHttpIT` uses real HTTP, not MockMvc.** A denied request is
  forwarded to `/error`, and that forward re-enters the security chain with the
  context already cleared. MockMvc performs no error dispatch, so it cannot see
  that, and a 403 rewritten into a 401 passes there unnoticed. It was a real bug
  and this suite is what catches it.

## API

Full contract for the frontend, with real payloads, TypeScript types and the
rules the UI has to respect, is at [`../API_CONTRACT.md`](../API_CONTRACT.md).

```
POST   /api/v1/auth/login                 public
GET    /api/v1/zones                      public
GET    /api/v1/zones/{id}                 public
GET    /api/v1/graph                      public
GET    /api/v1/alerts/stream              public, server-sent events, ?zones=Z01,Z02
POST   /api/v1/subscriptions              public
POST   /api/v1/reports                    reporter
POST   /api/v1/edges/{id}/confirm         reporter, one tap, no body
POST   /api/v1/edges/{id}/reject          reporter, one tap, no body
POST   /api/v1/edges/propose              reporter
POST   /api/v1/admin/kill-switch          admin
POST   /api/v1/admin/reporters/{id}/suspend  admin
POST   /api/v1/replay                     admin
```

Anonymous callers on a protected route get 401. Authenticated callers without the
role get 403.

## Replay

`POST /api/v1/replay` takes an optional graph snapshot and a list of timestamped
reports, runs the whole pipeline with delivery disabled, and returns what would
have fired and when. It writes nothing, sends nothing, and ignores the kill
switch, because the switch governs what reaches real people and nothing here
does. Omit `edges` to replay against the graph as it stands today, which is
usually the question worth asking.

The same request always replays to the same result. Replay is only useful if two
runs of one event agree.

# EkoAlert

A community-sourced flood early-warning system for Lagos.

University of Lagos, Department of Computer Sciences. Group 1, 12 people across
five pods over five sprints.

## The problem

WhatsApp groups and TikTok already tell people that Ojota is flooded, at a reach
this project will never match. But they describe water that is already there.
That message is worth nothing to the man two zones downstream, and he is the one
person who could still act on it.

Turning "Ojota is flooded" into "Ketu has forty minutes" means knowing how the
drainage connects. That knowledge is not in any dataset. We pulled 95 km of
waterway from OpenStreetMap for this corridor and got 105 segments in 44
disconnected pieces, which is the evidence rather than the setback: the topology
cannot be derived automatically here, and it has to come from the residents who
watch it happen every season.

So the system does two things. Vetted reporters log rising water in their zone.
When two of them agree inside a time window, the zone escalates and a propagation
engine walks the drainage graph downstream, warning subscribed residents in the
zones the water is expected to reach, with a rough time to impact.

## The idea that shapes everything

An edge from zone A to zone B does **not** claim a channel runs between them. It
claims: when water is reported in A, water tends to show up in B about N minutes
later. It is an observational claim from residents, not a hydrological one.

Every edge starts out `inferred`, guessed by a geometric rule, so the map is
complete on day one. **No inferred edge may ever fire an alert.** Residents
confirm or reject edges one tap at a time, and as they do, the system gets
progressively louder.

Day one is therefore a full map and near silence. That transition is the product,
and it is the defence demo.

## What is built so far

| | Status |
|---|---|
| Propagation engine, pure Java, no Spring | Done, covered by the golden scenario |
| REST API, JWT auth, live SSE stream | Done |
| PostgreSQL + PostGIS, Flyway migrations, seed loader | Done |
| Quorum, escalation, de-escalation, all-clears | Done |
| Kill switch and replay mode | Done |
| React frontend: map, reporting, corrections, admin | Done |
| Residents naming places the graph has no node for | Done |
| Backend test suite | 125 tests passing |

The seeded pilot graph is 20 zones and 17 edges along three corridors in the
Ojota to Ogudu to Bariga area.

## Running it

You need Docker, Java 21 and Node 20+.

### Backend

```bash
cd backend

docker run -d --name ekoalert-db \
  -e POSTGRES_DB=ekoalert -e POSTGRES_USER=ekoalert -e POSTGRES_PASSWORD=ekoalert \
  -p 5433:5432 postgis/postgis:16-3.4

./mvnw -DskipTests package

EKOALERT_DB_URL='jdbc:postgresql://localhost:5433/ekoalert?stringtype=unspecified' \
  java -jar app/target/app-0.1.0-SNAPSHOT.jar --seed --demo-users
```

Port 5433 on the host, because a local PostgreSQL is usually already on 5432.
Wait for the log line `SEED demo users ready`.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. Stay on that port: the backend only allows CORS from
`localhost:5173` and `127.0.0.1:5173`.

### Signing in

Three demo accounts, all with the password `ekoalert-demo`:

| Username | Role | Lands on |
|---|---|---|
| `ada` | reporter, vetted for zone Z01 | Report screen |
| `bola` | reporter, vetted for zone Z01 | Report screen |
| `admin` | admin | Admin screen |

The map needs no login at all.

`ada` and `bola` are both vetted for **Z01**, and that is deliberate: a quorum
needs two *different* people reporting the same zone, so those two accounts are
what make an escalation reachable. One account reporting twice will never
escalate anything, and one account tapping confirm twice will never flip an edge.
Use two browsers, or one normal window and one incognito.

### Seeing the whole story in one go

With the backend running:

```bash
cd backend && ./demo.sh
```

It walks the definition of done end to end: a complete but silent map, one report
that does nothing, a second that escalates but delivers nothing because the path
is unconfirmed, residents confirming the path, the same escalation now
delivering, the kill switch, and a replay.

## Where to read next

| File | What it is for |
|---|---|
| `CLAUDE.md` | Project context, the graph model, and the rules that bind. Read first. |
| `BACKEND_BRIEF.md` | Schema, engine contract, quorum rules, test strategy, the golden scenario. |
| `API_CONTRACT.md` | Every payload, copied from a live server. Authoritative for frontend work. |
| `FRONTEND_BRIEF.md` | Design direction and screen specs. |
| `backend/README.md` | How to build, run and change the backend. |
| `frontend/README.md` | Frontend layout, and the contract gaps it works around. |
| `ekoalert_zones.csv` | The seeded graph, with a column-by-column note on where each value came from. |

## State of things, so nobody walks into a gap

Known and deliberate:

- **Zone names and landmarks are empty.** They come from a field survey that has
  not happened. Never populate them by inference. The UI binds to `displayName`,
  which falls back to the zone id.
- **Junction edges between the three corridors do not exist.** Inference is worst
  at exactly the edges that matter most, so they were left blank on purpose.
  Residents fill them in from the map: one tap to connect two zones the graph
  already has, or a name and a GPS fix for somewhere it does not. Nothing lands
  on the graph on one person's say-so, and what does land arrives `inferred`.
  `POST /edges/propose` is the way residents add them.
- **Four zones are flagged `needs_field_naming`.** They sit more than 2 km from
  any named place in OpenStreetMap. They are not empty land, they need a person.

Not built yet:

- No endpoint returns the signed-in reporter's own zone (`GET /auth/me`).
- No endpoint returns the current kill switch state.
- No endpoint lists past alerts or corrections for audit.
- No admin UI for editing the graph directly, and no reporter self-registration.

The repositories already support the last three; they are small additions when a
pod needs them.

## Ground rules

- The golden scenario at `backend/engine/src/test/resources/golden/scenario-01.json`
  is **immutable**. If a case fails, the engine is wrong. Never edit an assertion
  to make a test pass.
- Engine tests are written before the code they cover.
- The `engine` module must never import Spring or JPA. An ArchUnit test enforces
  it, and a violation is a build failure rather than a style note.
- The graph lives in the database, never hardcoded. It will be wrong, and it must
  be fixable without a redeploy.

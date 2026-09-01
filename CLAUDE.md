# EkoAlert — context for Claude Code

## What this project is

A community-sourced flood early-warning system for Lagos. Vetted community
reporters log rising water in their zone. When two independent reports in the
same zone agree within a time window, the zone escalates. A propagation engine
then walks a directed drainage graph downstream from that zone and warns
subscribed residents in the zones water is expected to reach, with a rough
time-to-impact.

University of Lagos, Department of Computer Sciences group project. Group 1,
12 people across five pods (Engine, Platform, Frontend, Delivery, Coordinator)
over five sprints. Backend must be Java.

## Why this exists, and not just a WhatsApp group or TikTok

Those channels already warn people, at reach this will never match. But they
describe water that is already there. A video of flooding in Ojota tells the
man two zones downstream nothing, and he is the person who most needs to act.
Turning "Ojota is flooded" into "Ketu has forty minutes" requires knowing how
the drainage connects, and that knowledge exists in fragments across thousands
of residents rather than in any dataset or any one person's head. Assembling
it, and warning forward from it, is the entire reason software is involved.

Broadcast channels also cannot address individuals, so people learn to ignore
them, and they have no verification, so old flood videos recirculate every
season. The quorum rule and the per-zone subscription answer those two.

The known weakness, which should be stated rather than hidden: reach. On day
one this has none. Informal channels are the distribution route, not the
competitor. A zone chairman pasting an EkoAlert warning into his estate group
is the system working.

## Pilot corridor

Ojota through Ogudu to Bariga: the Drainage Channel, Ogudu Creek and Agboyi
Creek. The Ogun river was dropped as not relevant to urban drainage and too
sparsely populated at its northern end. 20 zones, 17 seeded edges, three
corridor termini deliberately left without outbound edges because those are
the junction edges that inference is worst at.

Geography came from OpenStreetMap: waterway geometry for the corridors, place
nodes for the names. Coverage of the corridor is 95 km across 105 segments
forming 44 disconnected components, which is itself the evidence that drainage
topology cannot be derived automatically here and has to come from residents.

## Repo map

- `CLAUDE.md` — this file, project context and the rules that bind
- `BACKEND_BRIEF.md` — schema, engine contract, quorum rules, test strategy
- `API_CONTRACT.md` — live payloads, authoritative for the frontend
- `FRONTEND_BRIEF.md` — design direction and screen specs
- `ekoalert_zones.csv` — the seeded graph

## Stack (already decided, do not re-litigate)

- Java 21, Spring Boot 3.3, modular monolith (not microservices)
- PostgreSQL + PostGIS, Flyway migrations
- JWT auth
- Server-Sent Events for live map updates
- React + Vite + TypeScript frontend, Leaflet for maps
- The propagation engine is **pure Java with no Spring dependencies**, so it is
  unit-testable in isolation and reusable for any city given a graph

## The graph model — read this before writing any engine code

Nodes are zones (a street cluster, an estate, a ward). Edges are directed.

An edge from A to B does **not** claim a physical channel connects them. It
claims: *when water is reported in A, water tends to appear in B roughly N
minutes later.* It is an observational claim sourced from residents, not a
hydrological one.

Consequences for the code:

- Every edge carries a `confidence` value. Edges confirmed by multiple
  residents and by historical events are strong; edges inferred from a map are
  weak. Weak edges propagate at reduced severity or not at all.
- Every edge carries a `blocked` flag. Lagos drains silt up and get built over.
  Reporters can flag blockage as an optional field on a normal report; a blocked
  edge is downweighted or disabled until reported clear.
- Propagation depth is capped (2-3 hops). Errors compound, and false alarms
  destroy trust faster than missed alarms do.
- The graph lives in the database with an admin editing UI. Never hardcoded.
  It will be wrong and must be fixable without a redeploy.
- Traversal is best-first and must tolerate cycles. Lagos drainage has them.

## Data provenance — the hard boundary

`ekoalert_zones.csv` holds 21 candidate zones along three corridors
(Drainage Channel, Ogudu Creek, Agboyi Creek) in the Ojota–Ogudu–Bariga area.

Columns and where they come from:

| Column | Source | Trustworthy? |
|---|---|---|
| `lat`, `lng` | OpenStreetMap waterway geometry | Yes |
| `corridor` | OSM named waterway | Yes |
| `nearest_place`, `dist_m`, `second_nearest` | computed join against OSM place nodes | Yes, but see below |
| `drains_into`, `travel_minutes`, `distance_m` | **geometric inference** — next zone south along the same corridor, time from distance at a provisional 55 m/min surface-flow rate | Provisional only |
| `zone_name`, `landmark` | **field survey — not yet collected** | — |
| `confidence`, `alertable` | seeded `inferred` / `no`; changed by confirmation | — |

## The confidence rule — core to the design

Every edge is seeded by inference so the map is complete on day one. No
inferred edge may fire an alert.

- `confidence` is one of `inferred`, `confirmed`, `rejected`.
- `alertable` is derived: only `confirmed` edges propagate alerts.
- An edge becomes `confirmed` when residents affirm it (a threshold, not one
  vote) or when observed reports show the timing holds.
- An edge becomes `rejected` the same way, and stops being drawn as active.
- Residents correct edges from the map in **one tap**, not a form. A form gets
  used by nobody.
- Every correction is logged with who and when. This is the project's evidence
  trail and the most interesting data the pilot will produce.

So at launch the system shows a full map and sends almost nothing. As edges
cross the confirmation threshold it gets progressively louder. That transition
is the demo.

Do not populate `zone_name` or `landmark` by inference — those come from
residents. And note why the geometric inference above is only provisional:
Lagos is too flat for DEM-derived flow direction to work. At SRTM resolution
the height differences that route water are smaller than the sensor error, so
D8 flow direction produces confident nonsense. The inference here is a
deterministic geometric rule (next zone downstream along the corridor line,
direction taken as toward the lagoon), reproducible and checkable by anyone —
not a hydrological claim.

Junction edges between corridors are deliberately left blank. Those are the
edges that matter most and the ones inference is worst at.

Four rows are flagged `needs_field_naming=yes` — they sit more than 2 km from
any OSM place node, in areas OSM has not named. They are not empty land; they
need a person.

Build everything so it works with these columns blank, and so it degrades
gracefully when a zone has no outbound edges.

## Kill switch

An admin must be able to halt all outgoing alerts immediately. This is a
first-class feature, not a nice-to-have, and ships early. Any system that
pushes warnings to real people needs an off button.

## Replay mode

The system must be able to replay a past flood event against the graph:
feed timestamped historical reports in, watch what the engine would have
predicted, compare against what actually happened. This is both the primary
validation mechanism for the graph and the demo for the project defence.

## Style

- No em dashes in generated prose, docs, or comments.
- Prefer minimal, voice-preserving edits when revising existing text.

## Verification

`BACKEND_BRIEF.md` in this repo holds the schema, engine contract, quorum
rules, API surface and test strategy. Read it before writing backend code.

Two rules override convenience:

1. The golden scenario at `engine/src/test/resources/golden/scenario-01.json`
   and its expected outputs are **immutable**. If a case fails, the engine is
   wrong. Never edit an assertion to make a test pass.
2. Engine tests are written before the code they cover. The engine is pure
   functions over records, so there is nothing to mock and nothing that needs
   a database.

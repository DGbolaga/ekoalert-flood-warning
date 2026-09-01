# EkoAlert frontend

React, Vite, TypeScript and Leaflet. Consumes the backend described in
`../API_CONTRACT.md` and nothing else.

## Running it

```bash
npm install
npm run dev            # http://localhost:5173
```

The backend has to be up first, on `http://localhost:8080`:

```bash
cd ../backend
docker run -d --name ekoalert-db \
  -e POSTGRES_DB=ekoalert -e POSTGRES_USER=ekoalert -e POSTGRES_PASSWORD=ekoalert \
  -p 5433:5432 postgis/postgis:16-3.4
./mvnw -DskipTests package
EKOALERT_DB_URL='jdbc:postgresql://localhost:5433/ekoalert?stringtype=unspecified' \
  java -jar app/target/app-0.1.0-SNAPSHOT.jar --seed --demo-users
```

Stay on port 5173. CORS on the backend allows only `localhost:5173` and
`127.0.0.1:5173`.

Demo logins are `admin`, `ada` and `bola`, password `ekoalert-demo`. `ada` and
`bola` are both vetted for `Z01`, which is what makes a quorum reachable.

To watch the map move, open a second tab on the map and run `../backend/demo.sh`.

`npm run build` typechecks and produces `dist/`. `npm run typecheck` alone.

## Configuration

Copy `.env.example` to `.env` if you need to change anything.

- `VITE_API_BASE` defaults to `http://localhost:8080/api/v1`.
- `VITE_TILE_URL_LIGHT`, `VITE_TILE_URL_DARK`, `VITE_TILE_ATTRIBUTION` override
  the basemap. See the note below.

## Where things are

```
src/api/          types.ts is the contract, transcribed; client.ts is the only
                  place fetch is called
src/state/        auth (token, role), live (graph, stream, activity feed),
                  reportQueue (IndexedDB, offline reports)
src/map/          raw Leaflet, no react-leaflet, so the edge decorations and
                  div icons stay under our control
src/components/   DepthGlyph, EdgeMark, Sheet, ConnectionRow
src/screens/      Map, ZoneSheet, Report, Activity, Login, Admin, Replay
src/styles/       tokens.css is the whole palette and type scale
```

`DESIGN_PLAN.md` holds the design plan the brief asked for, including the four
things that were changed after checking the plan against the brief.

## Notes for whoever picks this up

### The basemap

The brief asks for CARTO Positron. CARTO's keyless endpoint now stamps
`API KEY REQUIRED` across every tile, which would put a watermark through the
middle of the demo. The default here is Stadia `alidade_smooth` and
`alidade_smooth_dark`, which is the same near monochrome treatment and needs no
key on localhost. A deployed domain needs a free Stadia key, or set
`VITE_TILE_URL_LIGHT` and `VITE_TILE_URL_DARK` to a keyed Positron pair.

### Three things the contract does not expose

Each is handled by saying so rather than by guessing.

1. **The signed in reporter's own zone.** The report screen has to show it, large,
   before he taps. There is no `GET /auth/me`, so it is filled in from the
   `zoneId` the server returns on his first report and cached per reporter after
   that. Until then the screen says the server will name it, and does not invent
   one. A `GET /auth/me` returning `{ reporterId, zoneId, displayName }` would fix
   this properly.

2. **The current kill switch state.** There is no `GET /admin/kill-switch`, so the
   admin screen deliberately does thisnot render a toggle: a toggle asserts a position,
   and asserting one it cannot read would be a lie about whether warnings are
   reaching people. It shows two explicit commands and reports the state only once
   the operator has set it in this session.

3. **The correction threshold.** `confirmations` and `rejections` come back on the
   zone detail, but the threshold they are moving toward only arrives on a
   correction response. So a row says "1 person confirms this" until the app has
   seen one response, and "1 of 2 people confirm this" after that. The learned
   value is kept in `localStorage`.

Related: no endpoint lists past alerts, so the activity feed holds only what
arrived while the tab was open. It is kept in `sessionStorage`, so a reload does
not wipe it and closing the tab does.

### Things that are deliberate, not oversights

- Inferred edges are drawn, dashed and without an arrowhead. Hiding them would
  hide exactly what residents are being asked to correct.
- `alertable` is read from the server and never recomputed from `confidence` and
  `blocked`.
- `drainBlocked` is sent only when the reporter actually touched the toggle.
  Absent is not the same as saying the drain is clear.
- `observedAt` is stamped when he picks the depth, not when the request succeeds,
  so a report that waited in the offline queue still carries the right time.
- A failed send is written to IndexedDB before the screen tells him anything, so
  the queue is real by the time the app claims it exists.
- Zone labels bind to `displayName`. `name` and `landmark` come from a field
  survey that has not happened.
- All-clears get the same weight as alerts in the activity list.
- The 401 message is shown as the server wrote it, and nothing tries to tell a
  wrong password from an unknown username.

### Verified against a live backend

The whole flow was driven headlessly against the seeded server: login and role
routing, both quorum outcomes, the suppressed-alert acknowledgement and its call
to action, live recolouring over the stream with no reload, one-tap confirmation
through the threshold moment, the offline queue surviving a reload and flushing
on reconnect, the kill switch, reporter suspension, and replay. No horizontal
overflow at 320px on any screen, in either theme.

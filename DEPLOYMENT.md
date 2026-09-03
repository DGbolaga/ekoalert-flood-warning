# Deploying EkoAlert

Three free accounts, about fifteen minutes, no card. Everything the platforms
cannot work out for themselves is written down here so nobody has to guess.

## What runs where

| Piece | Service | Why that one |
|---|---|---|
| PostGIS database | Neon, free | The schema needs PostGIS. Render's free Postgres expires after 30 days, which would take the pilot down before the defence. Neon has no such clock. |
| Backend | Render web service, Docker | Render has no Java runtime, so the backend ships as an image. |
| Frontend | Render static site | Free, always on, and it can rewrite every path to `index.html`, which `BrowserRouter` needs. |

Region is Frankfurt, the closest Render offers to Lagos.

## Why HTTPS is not optional

Naming a place uses `navigator.geolocation`, and the device id uses
`crypto.randomUUID()`. Both are secure-context APIs. They work on `localhost` as a
special case and fail silently on plain `http://` on a real domain, so the place
proposal flow would break in a way that looks like a bug rather than a
misconfiguration. Render terminates TLS for free, so this costs nothing, but do not
serve this over plain HTTP anywhere.

---

## 1. Neon, the database

Neon may offer you a CLI setup flow when you create the project. **You only need
two things from Neon: the PostGIS extension enabled, and a connection string.**
Skip `neon config init`, `neon.ts` and `neon deploy`: those belong to Neon's own
deploy feature and have nothing to do with running this backend on Render.

Either route works.

**Console.** Open the project, run this in the SQL editor, then take the string
from **Connect**:

```sql
CREATE EXTENSION postgis;
```

**CLI.** Same two steps from the terminal:

```bash
npm i -g neon@latest && neon login

# Enable PostGIS on the branch Render will talk to.
neon sql --project-id <your-project-id> --branch production "CREATE EXTENSION postgis;"

# Print the connection string for that branch.
neon connection-string production --project-id <your-project-id>
```

Flyway's `V1__initial.sql` also runs `CREATE EXTENSION IF NOT EXISTS postgis`, but
doing it yourself first proves the role is allowed to, rather than finding out from
a failed migration.

Either way you end up with a URI like:

```
postgresql://myuser:mypassword@ep-cool-name-123456.eu-central-1.aws.neon.tech/neondb?sslmode=require
```

**Spring cannot use that string as-is.** It needs the `jdbc:` form and the
credentials passed separately. Split it into three values:

| Variable | Value |
|---|---|
| `EKOALERT_DB_URL` | `jdbc:postgresql://ep-cool-name-123456.eu-central-1.aws.neon.tech/neondb?sslmode=require` |
| `EKOALERT_DB_USER` | `myuser` |
| `EKOALERT_DB_PASSWORD` | `mypassword` |

Two changes only: `postgresql://` becomes `jdbc:postgresql://`, and the
`myuser:mypassword@` in the middle comes out. Keep `?sslmode=require`.

Nothing else needs appending. The schema uses Postgres enum types
(`edge_confidence`, `severity`) and the driver has to be told to let the server
coerce text into them, but that is set as a driver property in
`application.yml`, not as a query parameter you have to remember. A URL missing
it used to build, start, and then die on the first insert.

> If the driver cannot resolve the Neon endpoint, append
> `&options=endpoint%3Dep-cool-name-123456` to the URL. Recent PostgreSQL drivers
> use SNI and do not need it.

---

## 2. Render, both services

1. Sign up at [render.com](https://render.com) and connect the GitHub account that
   owns `ekoalert-flood-warning`.
2. **New → Blueprint**, pick the repo. Render reads `render.yaml` at the root,
   creates `ekoalert-api` and `ekoalert-web`, and asks for the values marked
   `sync: false`.
3. Fill them in.

   **ekoalert-api**

   | Variable | Value |
   |---|---|
   | `EKOALERT_DB_URL` | the `jdbc:` URL from step 1 |
   | `EKOALERT_DB_USER` | the Neon user |
   | `EKOALERT_DB_PASSWORD` | the Neon password |
   | `EKOALERT_DEMO_PASSWORD` | **a password you choose.** Not the default. See below. |
   | `EKOALERT_CORS_ALLOWED_ORIGINS` | `https://ekoalert-web.onrender.com` |
   | `EKOALERT_JWT_SECRET` | leave it, Render generates one |

   **ekoalert-web**

   | Variable | Value |
   |---|---|
   | `VITE_API_BASE` | `https://ekoalert-api.onrender.com/api/v1` |

4. Deploy. The first backend build takes several minutes while Maven downloads its
   dependency tree; later builds reuse the cached layer.

Two of those values are circular: the API needs the web origin and the web needs
the API origin. If Render assigns hostnames other than the defaults above, correct
both variables and redeploy both services.

### Why `EKOALERT_DEMO_PASSWORD` matters

`--demo-users` creates `admin`, `ada` and `bola`. The built-in password is printed
in a public README in a public repository, and `admin` can flip the kill switch and
suspend reporters. On a laptop that is harmless. On a URL anyone can open, it means
a stranger can silence the system during your defence.

Set your own and share it with the team privately. The startup log prints the
password only while the built-in one is in use; once you set your own, the log says
so without repeating the secret into Render's log viewer.

---

## 3. Stadia Maps, the basemap

Tiles are keyless on `localhost` and start returning 403 once the referrer is a real
domain, which would leave the map as empty grey squares.

1. Sign up at [stadiamaps.com](https://stadiamaps.com). Free for non-commercial and
   academic use, no card.
2. Add a property and register the domain `ekoalert-web.onrender.com`.

**No code change.** Stadia authenticates deployed sites by domain, so the existing
tile URLs keep working once the domain is registered.

If you would rather not sign up, set `VITE_TILE_URL_LIGHT`, `VITE_TILE_URL_DARK` and
`VITE_TILE_ATTRIBUTION` on `ekoalert-web` to another provider. The frontend uses the
keyed pair only when both light and dark are set.

---

## Checking it worked

```bash
HOST=https://ekoalert-api.onrender.com
API=$HOST/api/v1

# Liveness. This is what Render polls, and it deliberately does not touch the
# database: a check that queried Postgres every thirty seconds would hold Neon
# awake around the clock and burn the monthly free compute in days.
curl -s $HOST/actuator/health          # {"status":"UP"}

# The graph is public, so this needs no token. Expect zones 20, edges 17.
# On a free instance the first call after 15 minutes idle takes up to a
# minute while the service wakes.
curl -s $API/graph | head -c 200

# CORS must allow the frontend and refuse everything else.
curl -s -o /dev/null -w 'frontend origin: %{http_code}\n' -X OPTIONS \
  -H 'Origin: https://ekoalert-web.onrender.com' \
  -H 'Access-Control-Request-Method: POST' $API/reports     # expect 200

curl -s -o /dev/null -w 'other origin:    %{http_code}\n' -X OPTIONS \
  -H 'Origin: https://evil.example.com' \
  -H 'Access-Control-Request-Method: POST' $API/reports     # expect 403

# The live stream should send a keepalive comment every 20 seconds.
timeout 25 curl -sN $API/alerts/stream
```

Then open the site and check, in order: the map draws 20 markers over real tiles;
signing in as `ada` works; a report from `ada` and one from `bola` escalates the
zone; and the live indicator stays quiet rather than flapping between connected and
disconnected.

---

## What this deployment cannot do

- **It sleeps.** A free web service spins down after fifteen minutes idle and takes
  about a minute to wake. Open the URL a few minutes before anyone else does. The
  fix is the paid tier, not a code change.
- **One instance, deliberately.** The SSE listener list lives in memory and the
  de-escalation sweep has no locking, so a second instance would mean clients
  missing alerts and residents getting duplicate all-clears. `numInstances: 1` in
  the blueprint is a correctness constraint, not thrift.
- **512 MB is tight** for Spring Boot with JPA and Flyway. The image sets
  `-XX:MaxRAMPercentage=70`, SerialGC and `ExitOnOutOfMemoryError`, so if it does
  run out it restarts cleanly rather than degrading. If that happens under load,
  that is the signal to move to the paid instance.
- **Neon scales to zero**, adding roughly half a second to the first query after a
  quiet spell. That is the intended behaviour and the reason the health check is
  liveness only: the free plan allows 100 compute-hours a month, and a database
  held permanently awake needs closer to 180.

## Rolling back

Render keeps previous deploys. Open the service, find the last good one, choose
**Rollback**. A rollback does not touch the database: Flyway migrations only go
forward, so roll back to a commit whose migrations are a prefix of what has already
run, never to one that expects a migration you have since removed.

## Redeploying

Both services rebuild on a push to `main`. Nothing else to do.

Seeding is idempotent: `SeedLoader` checks each zone and edge before inserting and
`SeedCommand` skips users that already exist, so a redeploy re-runs the checks and
writes nothing.

## Rotating the demo password

`EKOALERT_DEMO_PASSWORD` is read when the accounts are **created**, and seeding is
idempotent, so changing the variable on a database where `admin`, `ada` and `bola`
already exist does nothing. They keep the password they were made with. Set it
before the first deploy and this never comes up.

If you do need to change it later, or you seeded once with the default by mistake,
delete the logins and let the next start recreate them:

```sql
DELETE FROM app_user WHERE username IN ('admin', 'ada', 'bola');
```

Then restart the service. This is safe: `SeedCommand.reporter()` looks the reporter
up by phone number and reuses the existing row, so the reporters are not duplicated
and every report and correction already attached to them survives. Only the logins
are rebuilt, on whatever `EKOALERT_DEMO_PASSWORD` is set to at that moment.

Verified end to end: after deleting the three rows and restarting, the new password
authenticates, the old one returns 401, and the reporter count is unchanged.

## Resetting the pilot graph

To put the graph back to its day-one state (20 zones, 17 edges, nothing confirmed,
no corrections), against the deployed database:

```sql
DELETE FROM edge_correction;
DELETE FROM proposed_place_voice;
DELETE FROM proposed_place;
DELETE FROM edge WHERE inference_basis LIKE 'resident proposal%';
DELETE FROM zone_status WHERE zone_id IN (SELECT id FROM zone WHERE source = 'resident');
DELETE FROM zone WHERE source = 'resident';
UPDATE zone SET landmark = NULL WHERE landmark IS NOT NULL;
UPDATE edge SET confidence = 'inferred', blocked = false;
```

Order matters: corrections reference edges, and edges reference zones.

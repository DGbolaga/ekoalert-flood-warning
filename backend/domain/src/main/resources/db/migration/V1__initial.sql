-- EkoAlert initial schema.
-- Zones are nodes, edges are directed observational claims: water reported in
-- from_zone tends to appear in to_zone travel_minutes later. Not a hydrological
-- claim, which is why every edge carries confidence and a blocked flag.

CREATE EXTENSION IF NOT EXISTS postgis;

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

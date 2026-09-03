-- Residents naming a place the map does not have.
--
-- The seed knows 20 zones along three corridors. Water does not stop at the
-- edge of a pilot, so a resident saying "it goes on to Alapere, which is not on
-- your list" is the most useful thing anyone can tell this system, and the one
-- thing no inference can supply. Junction edges were left blank because
-- inference is worst at them; junction *places* are worse still, because the
-- node itself is missing.
--
-- A proposal is not a zone. The zone table is referenced by alert, subscription
-- and reporter, so a row landing there is immediately subscribable, reportable
-- and alertable. A proposed place therefore waits in its own table until enough
-- separate residents affirm it, and only then is it promoted.

CREATE TABLE proposed_place (
  id            BIGSERIAL PRIMARY KEY,
  landmark      TEXT NOT NULL,                     -- what a resident called it
  location      GEOGRAPHY(POINT,4326),             -- null until somebody stands there
  from_zone     TEXT NOT NULL REFERENCES zone(id), -- where they said the water comes from
  proposed_by   BIGINT REFERENCES reporter(id),
  proposed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  status        TEXT NOT NULL DEFAULT 'pending',   -- 'pending' | 'promoted' | 'rejected'
  promoted_zone TEXT REFERENCES zone(id),
  resolved_at   TIMESTAMPTZ
);
CREATE INDEX ON proposed_place (status);

-- One row per person per place. The unique constraint is the threshold rule made
-- structural: a place counts people, never taps.
CREATE TABLE proposed_place_voice (
  id          BIGSERIAL PRIMARY KEY,
  place_id    BIGINT NOT NULL REFERENCES proposed_place(id) ON DELETE CASCADE,
  reporter_id BIGINT NOT NULL REFERENCES reporter(id),
  located     BOOLEAN NOT NULL DEFAULT FALSE,      -- whether this voice supplied the GPS
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (place_id, reporter_id)
);

-- Where a zone came from. A seeded zone was inferred from OSM waterway geometry.
-- A resident zone was named by somebody standing in it, which is the stronger
-- provenance of the two and worth being able to tell apart.
ALTER TABLE zone ADD COLUMN source TEXT NOT NULL DEFAULT 'seed';

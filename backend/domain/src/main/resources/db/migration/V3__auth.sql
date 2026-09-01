-- Credentials. The build brief specifies JWT auth for reporters and admins but
-- gives no user table, so this is an addition rather than part of the given
-- schema. A reporter row is the field identity; an app_user row is the login.

CREATE TABLE app_user (
  id            BIGSERIAL PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL CHECK (role IN ('REPORTER','ADMIN')),
  reporter_id   BIGINT REFERENCES reporter(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (role <> 'REPORTER' OR reporter_id IS NOT NULL)
);

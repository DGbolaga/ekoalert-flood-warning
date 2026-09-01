-- Residents subscribe to the zones they care about. Alerts are delivered to
-- subscribers of the target zone; the all-clear goes to everyone who received
-- an alert from the origin, which is why alert rows are the delivery record.

CREATE TABLE subscription (
  id          BIGSERIAL PRIMARY KEY,
  zone_id     TEXT NOT NULL REFERENCES zone(id),
  channel     TEXT NOT NULL,                    -- 'sse' for now, 'sms' later
  address     TEXT NOT NULL,                    -- opaque per channel
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (zone_id, channel, address)
);
CREATE INDEX ON subscription (zone_id);

-- Which subscribers an alert actually reached. An all-clear needs this list,
-- and a delivery that never happened must not produce one.
CREATE TABLE alert_delivery (
  id          BIGSERIAL PRIMARY KEY,
  alert_id    BIGINT NOT NULL REFERENCES alert(id),
  subscription_id BIGINT NOT NULL REFERENCES subscription(id),
  delivered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (alert_id, subscription_id)
);

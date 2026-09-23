CREATE TABLE trip_offers (
    id            UUID PRIMARY KEY,
    trip_id       UUID         NOT NULL REFERENCES trips (id),
    driver_id     VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    distance_km   DOUBLE PRECISION NOT NULL,
    eta_seconds   BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    responded_at  TIMESTAMPTZ,
    CONSTRAINT trip_offers_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX trip_offers_trip ON trip_offers (trip_id);

-- A trip can only have one live offer at a time.
CREATE UNIQUE INDEX trip_offers_one_pending_per_trip
    ON trip_offers (trip_id)
    WHERE status = 'PENDING';

-- Used by the timeout sweeper and by drivers polling for offers.
CREATE INDEX trip_offers_pending_expiry ON trip_offers (expires_at) WHERE status = 'PENDING';
CREATE INDEX trip_offers_pending_driver ON trip_offers (driver_id) WHERE status = 'PENDING';

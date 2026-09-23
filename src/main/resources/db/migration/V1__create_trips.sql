CREATE TABLE trips (
    id               UUID PRIMARY KEY,
    rider_id         VARCHAR(64)  NOT NULL,
    driver_id        VARCHAR(64),
    status           VARCHAR(32)  NOT NULL,
    pickup_lat       DOUBLE PRECISION NOT NULL,
    pickup_lng       DOUBLE PRECISION NOT NULL,
    dropoff_lat      DOUBLE PRECISION NOT NULL,
    dropoff_lng      DOUBLE PRECISION NOT NULL,
    idempotency_key  VARCHAR(128) UNIQUE,
    request_hash     VARCHAR(64),
    match_attempts   INT          NOT NULL DEFAULT 0,
    next_match_at    TIMESTAMPTZ,
    cancel_reason    VARCHAR(128),
    requested_at     TIMESTAMPTZ  NOT NULL,
    matched_at       TIMESTAMPTZ,
    arriving_at      TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT trips_status_check CHECK (status IN
        ('REQUESTED', 'MATCHED', 'DRIVER_ARRIVING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

-- Second line of defence against double booking: Postgres itself refuses a
-- second active trip for the same driver, even if Redis somehow lets one through.
CREATE UNIQUE INDEX trips_one_active_trip_per_driver
    ON trips (driver_id)
    WHERE status IN ('MATCHED', 'DRIVER_ARRIVING', 'IN_PROGRESS');

-- The matcher polls for trips that are waiting for a driver.
CREATE INDEX trips_pending_match
    ON trips (next_match_at)
    WHERE status = 'REQUESTED' AND next_match_at IS NOT NULL;

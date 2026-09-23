-- Written by the billing consumer. trip_id as primary key makes the
-- consumer idempotent: a redelivered COMPLETED event inserts nothing.
CREATE TABLE invoices (
    trip_id           UUID PRIMARY KEY,
    rider_id          VARCHAR(64)   NOT NULL,
    driver_id         VARCHAR(64)   NOT NULL,
    distance_km       DOUBLE PRECISION NOT NULL,
    duration_seconds  BIGINT        NOT NULL,
    amount            NUMERIC(10, 2) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL
);

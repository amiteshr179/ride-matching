package com.ridematching.trip;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of a trip at the moment its status changed. Carries enough data
 * for downstream consumers (billing) to work without calling back.
 */
public record TripEvent(
        UUID eventId,
        UUID tripId,
        TripStatus status,
        String riderId,
        String driverId,
        double pickupLat,
        double pickupLng,
        double dropoffLat,
        double dropoffLng,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        String cancelReason,
        Instant occurredAt) {

    public static TripEvent of(Trip t, Instant now) {
        return new TripEvent(UUID.randomUUID(), t.getId(), t.getStatus(), t.getRiderId(), t.getDriverId(),
                t.pickup().lat(), t.pickup().lng(), t.dropoff().lat(), t.dropoff().lng(),
                t.getRequestedAt(), t.getStartedAt(), t.getCompletedAt(), t.getCancelReason(), now);
    }
}

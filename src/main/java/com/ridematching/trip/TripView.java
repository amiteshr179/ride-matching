package com.ridematching.trip;

import java.time.Instant;
import java.util.UUID;

public record TripView(
        UUID id,
        String riderId,
        String driverId,
        TripStatus status,
        double pickupLat,
        double pickupLng,
        double dropoffLat,
        double dropoffLng,
        int matchAttempts,
        String cancelReason,
        Instant requestedAt,
        Instant matchedAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt) {

    public static TripView of(Trip t) {
        return new TripView(t.getId(), t.getRiderId(), t.getDriverId(), t.getStatus(),
                t.pickup().lat(), t.pickup().lng(), t.dropoff().lat(), t.dropoff().lng(),
                t.getMatchAttempts(), t.getCancelReason(), t.getRequestedAt(), t.getMatchedAt(),
                t.getStartedAt(), t.getCompletedAt(), t.getCancelledAt());
    }
}

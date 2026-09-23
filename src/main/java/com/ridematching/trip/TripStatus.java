package com.ridematching.trip;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum TripStatus {
    REQUESTED,
    MATCHED,
    DRIVER_ARRIVING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    private static final Map<TripStatus, Set<TripStatus>> ALLOWED = Map.of(
            REQUESTED, EnumSet.of(MATCHED, CANCELLED),
            MATCHED, EnumSet.of(DRIVER_ARRIVING, CANCELLED),
            DRIVER_ARRIVING, EnumSet.of(IN_PROGRESS, CANCELLED),
            // Once the rider is in the car the trip has to finish; cancelling
            // mid-ride is out of scope here.
            IN_PROGRESS, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(TripStatus.class),
            CANCELLED, EnumSet.noneOf(TripStatus.class));

    public boolean canMoveTo(TripStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Statuses where a driver is tied to the trip. */
    public boolean holdsDriver() {
        return this == MATCHED || this == DRIVER_ARRIVING || this == IN_PROGRESS;
    }
}

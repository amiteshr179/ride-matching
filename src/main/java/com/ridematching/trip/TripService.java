package com.ridematching.trip;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Driver and rider actions on an existing trip. Every change locks the trip
 * row first, so two concurrent actions on one trip run one after the other.
 */
@Service
public class TripService {

    private final TripRepository trips;
    private final TripEvents events;
    private final Clock clock;

    public TripService(TripRepository trips, TripEvents events, Clock clock) {
        this.trips = trips;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Trip get(UUID id) {
        return trips.findById(id).orElseThrow(() -> new TripNotFoundException(id));
    }

    @Transactional
    public Trip markArriving(UUID id, String driverId) {
        Trip t = lockOwnedBy(id, driverId);
        t.markArriving(clock.instant());
        events.statusChanged(t);
        return t;
    }

    @Transactional
    public Trip start(UUID id, String driverId) {
        Trip t = lockOwnedBy(id, driverId);
        t.start(clock.instant());
        events.statusChanged(t);
        return t;
    }

    @Transactional
    public Trip complete(UUID id, String driverId) {
        Trip t = lockOwnedBy(id, driverId);
        t.complete(clock.instant());
        events.statusChanged(t);
        return t;
    }

    @Transactional
    public Trip cancel(UUID id, String reason) {
        Trip t = lock(id);
        t.cancel(reason, clock.instant());
        events.statusChanged(t);
        return t;
    }

    private Trip lock(UUID id) {
        return trips.findByIdForUpdate(id).orElseThrow(() -> new TripNotFoundException(id));
    }

    private Trip lockOwnedBy(UUID id, String driverId) {
        Trip t = lock(id);
        if (!Objects.equals(t.getDriverId(), driverId)) {
            throw new NotYourTripException(driverId);
        }
        return t;
    }
}

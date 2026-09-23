package com.ridematching.trip;

import com.ridematching.config.AfterTransaction;
import com.ridematching.driver.DriverAssignments;
import com.ridematching.matching.OfferService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Driver and rider actions on an existing trip. Every change locks the trip
 * row first, so two concurrent actions on one trip run one after the other.
 */
@Service
public class TripService {

    private final TripRepository trips;
    private final OfferService offers;
    private final DriverAssignments assignments;
    private final TripEvents events;
    private final Clock clock;

    public TripService(TripRepository trips, OfferService offers, DriverAssignments assignments,
                       TripEvents events, Clock clock) {
        this.trips = trips;
        this.offers = offers;
        this.assignments = assignments;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Trip get(UUID id) {
        return trips.findById(id).orElseThrow(() -> new TripNotFoundException(id));
    }

    /** The trip a driver is currently working on, if any. Lets a restarted driver app pick up where it left off. */
    @Transactional(readOnly = true)
    public Optional<Trip> activeTripFor(String driverId) {
        return trips.findFirstByDriverIdAndStatusIn(driverId,
                EnumSet.of(TripStatus.MATCHED, TripStatus.DRIVER_ARRIVING, TripStatus.IN_PROGRESS));
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
        AfterTransaction.onCommit(() -> assignments.release(driverId, id));
        return t;
    }

    @Transactional
    public Trip cancel(UUID id, String reason) {
        Trip t = lock(id);
        t.cancel(reason, clock.instant());
        offers.cancelPendingOffer(id);
        if (t.getDriverId() != null) {
            String driverId = t.getDriverId();
            AfterTransaction.onCommit(() -> assignments.release(driverId, id));
        }
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

package com.ridematching.matching;

import com.ridematching.config.AfterTransaction;
import com.ridematching.driver.DriverAssignments;
import com.ridematching.trip.Trip;
import com.ridematching.trip.TripEvents;
import com.ridematching.trip.TripNotFoundException;
import com.ridematching.trip.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Driver responses to offers, and offer timeouts. Every method locks the
 * trip row first (the same order the matcher uses) so an accept and a
 * timeout for the same offer can not both win.
 */
@Service
public class OfferService {

    public enum AcceptResult { ACCEPTED, OFFER_GONE }

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final TripRepository trips;
    private final OfferRepository offers;
    private final DriverAssignments assignments;
    private final TripEvents events;
    private final Clock clock;

    public OfferService(TripRepository trips, OfferRepository offers, DriverAssignments assignments,
                        TripEvents events, Clock clock) {
        this.trips = trips;
        this.offers = offers;
        this.assignments = assignments;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Offer> pendingOfferFor(String driverId) {
        Instant now = clock.instant();
        return offers.findFirstByDriverIdAndStatusOrderByCreatedAtDesc(driverId, OfferStatus.PENDING)
                .filter(o -> !o.isExpired(now));
    }

    @Transactional
    public AcceptResult accept(UUID tripId, String driverId) {
        Trip trip = lockTrip(tripId);
        Instant now = clock.instant();
        Offer offer = offers.findByTripIdAndStatus(tripId, OfferStatus.PENDING)
                .filter(o -> o.getDriverId().equals(driverId))
                .orElse(null);
        if (offer == null) {
            return AcceptResult.OFFER_GONE;
        }
        if (offer.isExpired(now) || !assignments.confirm(driverId, tripId)) {
            // Too late: treat it like a timeout and move on to the next driver.
            closeAndRematch(trip, offer, OfferStatus.EXPIRED, now);
            return AcceptResult.OFFER_GONE;
        }
        // Redis now says ASSIGNED. If the DB commit fails, undo that.
        AfterTransaction.onRollback(() -> assignments.release(driverId, tripId));
        offer.close(OfferStatus.ACCEPTED, now);
        trip.assignDriver(driverId, now);
        events.statusChanged(trip);
        log.info("Trip {} matched with {}", tripId, driverId);
        return AcceptResult.ACCEPTED;
    }

    @Transactional
    public boolean reject(UUID tripId, String driverId) {
        Trip trip = lockTrip(tripId);
        Offer offer = offers.findByTripIdAndStatus(tripId, OfferStatus.PENDING)
                .filter(o -> o.getDriverId().equals(driverId))
                .orElse(null);
        if (offer == null) {
            return false;
        }
        closeAndRematch(trip, offer, OfferStatus.REJECTED, clock.instant());
        log.info("Trip {} rejected by {}", tripId, driverId);
        return true;
    }

    /** Called by the sweeper for trips whose offer ran out of time. */
    @Transactional
    public void expireIfDue(UUID tripId) {
        Trip trip = lockTrip(tripId);
        Instant now = clock.instant();
        offers.findByTripIdAndStatus(tripId, OfferStatus.PENDING)
                .filter(o -> o.isExpired(now))
                .ifPresent(o -> {
                    closeAndRematch(trip, o, OfferStatus.EXPIRED, now);
                    log.info("Offer for trip {} to {} timed out", tripId, o.getDriverId());
                });
    }

    /** Called when a trip is cancelled while an offer is still out. */
    @Transactional
    public void cancelPendingOffer(UUID tripId) {
        offers.findByTripIdAndStatus(tripId, OfferStatus.PENDING).ifPresent(o -> {
            o.close(OfferStatus.CANCELLED, clock.instant());
            AfterTransaction.onCommit(() -> assignments.release(o.getDriverId(), tripId));
        });
    }

    private void closeAndRematch(Trip trip, Offer offer, OfferStatus outcome, Instant now) {
        offer.close(outcome, now);
        trip.scheduleRematch(now);
        AfterTransaction.onCommit(() -> assignments.release(offer.getDriverId(), trip.getId()));
    }

    private Trip lockTrip(UUID tripId) {
        return trips.findByIdForUpdate(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
    }
}

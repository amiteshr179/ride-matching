package com.ridematching.matching;

import com.ridematching.config.AfterTransaction;
import com.ridematching.config.RideProperties;
import com.ridematching.driver.DriverAssignments;
import com.ridematching.driver.DriverLocationService;
import com.ridematching.driver.NearbyDriver;
import com.ridematching.geo.EtaCalculator;
import com.ridematching.trip.Trip;
import com.ridematching.trip.TripEvents;
import com.ridematching.trip.TripRepository;
import com.ridematching.trip.TripStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One matching round for one trip: search outward in growing circles
 * around the pickup, and offer the trip to the closest free driver who has
 * not already been asked.
 */
@Service
public class MatchingService {

    public enum Outcome { OFFERED, NO_DRIVER_RETRY_LATER, GAVE_UP, SKIPPED }

    /** Extra life on the Redis reservation so the DB timeout always fires first. */
    static final Duration RESERVATION_GRACE = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final TripRepository trips;
    private final OfferRepository offers;
    private final DriverLocationService locations;
    private final DriverAssignments assignments;
    private final EtaCalculator eta;
    private final TripEvents events;
    private final Clock clock;
    private final RideProperties.Matching props;

    public MatchingService(TripRepository trips, OfferRepository offers, DriverLocationService locations,
                           DriverAssignments assignments, EtaCalculator eta, TripEvents events,
                           Clock clock, RideProperties props) {
        this.trips = trips;
        this.offers = offers;
        this.locations = locations;
        this.assignments = assignments;
        this.eta = eta;
        this.events = events;
        this.clock = clock;
        this.props = props.matching();
    }

    @Transactional
    public Outcome runRound(UUID tripId) {
        Trip trip = trips.findByIdForUpdate(tripId).orElse(null);
        Instant now = clock.instant();
        // Re-check under the row lock: another instance may have handled it already.
        if (trip == null || trip.getStatus() != TripStatus.REQUESTED
                || trip.getNextMatchAt() == null || trip.getNextMatchAt().isAfter(now)) {
            return Outcome.SKIPPED;
        }

        Set<String> alreadyAsked = offers.findDriverIdsOfferedForTrip(tripId);
        Set<String> checkedThisRound = new HashSet<>();

        for (double radiusKm : props.radiiKm()) {
            List<NearbyDriver> candidates = locations.findNearby(trip.pickup(),
                    radiusKm, props.candidatesPerRadius() + alreadyAsked.size());
            // Best driver = shortest ETA to the pickup.
            List<NearbyDriver> ranked = candidates.stream()
                    .filter(d -> !alreadyAsked.contains(d.driverId()))
                    .filter(d -> checkedThisRound.add(d.driverId()))
                    .sorted(Comparator.comparing(d -> eta.eta(d.location(), trip.pickup())))
                    .toList();

            for (NearbyDriver candidate : ranked) {
                var result = assignments.reserve(candidate.driverId(), tripId,
                        props.offerTimeout().plus(RESERVATION_GRACE));
                if (result == DriverAssignments.ReserveResult.RESERVED) {
                    makeOffer(trip, candidate, now);
                    log.info("Trip {} offered to {} ({} km, radius {} km)", tripId, candidate.driverId(),
                            String.format("%.2f", candidate.distanceKm()), radiusKm);
                    return Outcome.OFFERED;
                }
            }
        }

        trip.recordFailedMatchAttempt(now.plus(props.retryDelay()));
        if (trip.getMatchAttempts() >= props.maxAttempts()) {
            trip.cancel("NO_DRIVERS_AVAILABLE", now);
            events.statusChanged(trip);
            log.info("Trip {} cancelled: no drivers after {} attempts", tripId, trip.getMatchAttempts());
            return Outcome.GAVE_UP;
        }
        return Outcome.NO_DRIVER_RETRY_LATER;
    }

    private void makeOffer(Trip trip, NearbyDriver driver, Instant now) {
        // If the offer row fails to commit, hand the driver back straight away.
        AfterTransaction.onRollback(() -> assignments.release(driver.driverId(), trip.getId()));
        offers.save(Offer.pending(trip.getId(), driver.driverId(), driver.distanceKm(),
                eta.eta(driver.location(), trip.pickup()).toSeconds(), now, now.plus(props.offerTimeout())));
        trip.pauseMatching();
    }
}

package com.ridematching.matching;

import com.ridematching.trip.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Background loops. The database is the source of truth for what needs to
 * happen next (next_match_at, expires_at), so nothing is lost if the app
 * restarts, and each trip is handled in its own short transaction.
 */
@Component
public class MatchingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MatchingScheduler.class);
    private static final int BATCH = 50;

    private final TripRepository trips;
    private final OfferRepository offers;
    private final MatchingService matching;
    private final OfferService offerService;
    private final Clock clock;

    public MatchingScheduler(TripRepository trips, OfferRepository offers, MatchingService matching,
                             OfferService offerService, Clock clock) {
        this.trips = trips;
        this.offers = offers;
        this.matching = matching;
        this.offerService = offerService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rides.matching.poll-interval}")
    public void expireOffers() {
        for (UUID tripId : offers.findTripIdsWithExpiredOffers(clock.instant())) {
            try {
                offerService.expireIfDue(tripId);
            } catch (RuntimeException e) {
                log.warn("Could not expire offer for trip {}", tripId, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${rides.matching.poll-interval}")
    public void matchWaitingTrips() {
        for (UUID tripId : trips.findTripIdsDueForMatching(clock.instant(), BATCH)) {
            try {
                matching.runRound(tripId);
            } catch (RuntimeException e) {
                log.warn("Matching round failed for trip {}", tripId, e);
            }
        }
    }
}

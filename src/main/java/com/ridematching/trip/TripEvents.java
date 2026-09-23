package com.ridematching.trip;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Raises an in-process event whenever a trip changes status. Listeners that
 * talk to the outside world should use @TransactionalEventListener so they
 * only fire once the status change is committed.
 */
@Component
public class TripEvents {

    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public TripEvents(ApplicationEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void statusChanged(Trip trip) {
        publisher.publishEvent(TripEvent.of(trip, clock.instant()));
    }
}

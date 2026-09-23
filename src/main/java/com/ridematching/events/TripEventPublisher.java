package com.ridematching.events;

import com.ridematching.trip.TripEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends trip events to Kafka once the status change is committed, so
 * consumers never hear about a change that was rolled back. Keyed by trip
 * id so all events for one trip land on one partition, in order.
 */
@Component
public class TripEventPublisher {

    public static final String TOPIC = "trip-events";

    private static final Logger log = LoggerFactory.getLogger(TripEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public TripEventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(TripEvent event) {
        String payload = json.writeValueAsString(event);
        kafka.send(TOPIC, event.tripId().toString(), payload)
                .whenComplete((result, err) -> {
                    if (err != null) {
                        log.error("Failed to publish {} for trip {}", event.status(), event.tripId(), err);
                    }
                });
    }
}

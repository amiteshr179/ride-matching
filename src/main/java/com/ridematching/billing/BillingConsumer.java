package com.ridematching.billing;

import com.ridematching.events.TripEventPublisher;
import com.ridematching.geo.EtaCalculator;
import com.ridematching.trip.TripEvent;
import com.ridematching.trip.TripStatus;
import com.ridematching.geo.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * Stand-in for a billing service. It only reads the Kafka topic, never the
 * trips table, the same way a separate service would.
 */
@Component
public class BillingConsumer {

    private static final Logger log = LoggerFactory.getLogger(BillingConsumer.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final EtaCalculator eta;
    private final Clock clock;
    private final FareCalculator fares = new FareCalculator(
            new BigDecimal("50"), new BigDecimal("12"), new BigDecimal("1.5"), new BigDecimal("80"));

    public BillingConsumer(JdbcClient jdbc, JsonMapper json, EtaCalculator eta, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.eta = eta;
        this.clock = clock;
    }

    @KafkaListener(topics = TripEventPublisher.TOPIC, groupId = "billing")
    public void onTripEvent(String payload) {
        TripEvent event = json.readValue(payload, TripEvent.class);
        if (event.status() != TripStatus.COMPLETED) {
            return;
        }
        double km = eta.roadDistanceKm(new GeoPoint(event.pickupLat(), event.pickupLng()),
                new GeoPoint(event.dropoffLat(), event.dropoffLng()));
        Duration rideTime = Duration.between(event.startedAt(), event.completedAt());
        BigDecimal amount = fares.fare(km, rideTime);

        int inserted = jdbc.sql("""
                        insert into invoices (trip_id, rider_id, driver_id, distance_km, duration_seconds, amount, currency, created_at)
                        values (?, ?, ?, ?, ?, ?, 'INR', ?)
                        on conflict (trip_id) do nothing
                        """)
                .params(event.tripId(), event.riderId(), event.driverId(), km, rideTime.toSeconds(), amount,
                        Timestamp.from(clock.instant()))
                .update();
        if (inserted == 1) {
            log.info("Billed trip {}: INR {} for {} km", event.tripId(), amount, String.format("%.2f", km));
        } else {
            log.info("Trip {} already billed, skipping duplicate event", event.tripId());
        }
    }
}

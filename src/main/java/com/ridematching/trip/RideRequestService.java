package com.ridematching.trip;

import com.ridematching.geo.GeoPoint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Creates trips from ride requests. The idempotency key is stored on the
 * trip row with a unique constraint, so a retried request returns the same
 * trip instead of booking a second ride.
 */
@Service
public class RideRequestService {

    public record Result(Trip trip, boolean created) {
    }

    private final TripRepository trips;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final TripEvents events;

    public RideRequestService(TripRepository trips, TransactionTemplate tx, Clock clock, TripEvents events) {
        this.trips = trips;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    public Result requestRide(String riderId, GeoPoint pickup, GeoPoint dropoff, String idempotencyKey) {
        String hash = hash(riderId, pickup, dropoff);

        var existing = trips.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }

        try {
            Trip created = tx.execute(status -> {
                Trip t = trips.saveAndFlush(Trip.request(riderId, pickup, dropoff, idempotencyKey, hash, clock.instant()));
                events.statusChanged(t);
                return t;
            });
            return new Result(created, true);
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key raced; the other one won the insert.
            Trip winner = trips.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return replay(winner, hash, idempotencyKey);
        }
    }

    private Result replay(Trip trip, String hash, String key) {
        if (!trip.getRequestHash().equals(hash)) {
            throw new IdempotencyConflictException(key);
        }
        return new Result(trip, false);
    }

    static String hash(String riderId, GeoPoint pickup, GeoPoint dropoff) {
        String canonical = riderId + "|" + pickup.lat() + "," + pickup.lng() + "|" + dropoff.lat() + "," + dropoff.lng();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

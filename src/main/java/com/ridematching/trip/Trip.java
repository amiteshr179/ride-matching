package com.ridematching.trip;

import com.ridematching.geo.GeoPoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trips")
public class Trip {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String riderId;

    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status;

    private double pickupLat;
    private double pickupLng;
    private double dropoffLat;
    private double dropoffLng;

    @Column(unique = true)
    private String idempotencyKey;

    private String requestHash;

    private int matchAttempts;
    private Instant nextMatchAt;
    private String cancelReason;

    private Instant requestedAt;
    private Instant matchedAt;
    private Instant arrivingAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant cancelledAt;

    @Version
    private long version;

    protected Trip() {
    }

    public static Trip request(String riderId, GeoPoint pickup, GeoPoint dropoff,
                               String idempotencyKey, String requestHash, Instant now) {
        Trip t = new Trip();
        t.id = UUID.randomUUID();
        t.riderId = riderId;
        t.status = TripStatus.REQUESTED;
        t.pickupLat = pickup.lat();
        t.pickupLng = pickup.lng();
        t.dropoffLat = dropoff.lat();
        t.dropoffLng = dropoff.lng();
        t.idempotencyKey = idempotencyKey;
        t.requestHash = requestHash;
        t.requestedAt = now;
        t.nextMatchAt = now;
        return t;
    }

    public void assignDriver(String driverId, Instant now) {
        transitionTo(TripStatus.MATCHED, now);
        this.driverId = driverId;
        this.nextMatchAt = null;
    }

    public void markArriving(Instant now) {
        transitionTo(TripStatus.DRIVER_ARRIVING, now);
    }

    public void start(Instant now) {
        transitionTo(TripStatus.IN_PROGRESS, now);
    }

    public void complete(Instant now) {
        transitionTo(TripStatus.COMPLETED, now);
    }

    public void cancel(String reason, Instant now) {
        transitionTo(TripStatus.CANCELLED, now);
        this.cancelReason = reason;
        this.nextMatchAt = null;
    }

    /** Called when a matching round found nobody. */
    public void recordFailedMatchAttempt(Instant retryAt) {
        this.matchAttempts++;
        this.nextMatchAt = retryAt;
    }

    /** Called when an offer was rejected or timed out, so matching runs again now. */
    public void scheduleRematch(Instant at) {
        this.nextMatchAt = at;
    }

    /** Called while an offer is outstanding so the matcher leaves the trip alone. */
    public void pauseMatching() {
        this.nextMatchAt = null;
    }

    private void transitionTo(TripStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new IllegalTransitionException(status, next);
        }
        this.status = next;
        switch (next) {
            case MATCHED -> matchedAt = now;
            case DRIVER_ARRIVING -> arrivingAt = now;
            case IN_PROGRESS -> startedAt = now;
            case COMPLETED -> completedAt = now;
            case CANCELLED -> cancelledAt = now;
            default -> {
            }
        }
    }

    public GeoPoint pickup() {
        return new GeoPoint(pickupLat, pickupLng);
    }

    public GeoPoint dropoff() {
        return new GeoPoint(dropoffLat, dropoffLng);
    }

    public UUID getId() {
        return id;
    }

    public String getRiderId() {
        return riderId;
    }

    public String getDriverId() {
        return driverId;
    }

    public TripStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getMatchAttempts() {
        return matchAttempts;
    }

    public Instant getNextMatchAt() {
        return nextMatchAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getMatchedAt() {
        return matchedAt;
    }

    public Instant getArrivingAt() {
        return arrivingAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}

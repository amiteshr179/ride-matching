package com.ridematching.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One attempt to give a trip to one driver. */
@Entity
@Table(name = "trip_offers")
public class Offer {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tripId;

    @Column(nullable = false)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferStatus status;

    private double distanceKm;
    private long etaSeconds;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant respondedAt;

    protected Offer() {
    }

    public static Offer pending(UUID tripId, String driverId, double distanceKm, long etaSeconds,
                                Instant now, Instant expiresAt) {
        Offer o = new Offer();
        o.id = UUID.randomUUID();
        o.tripId = tripId;
        o.driverId = driverId;
        o.status = OfferStatus.PENDING;
        o.distanceKm = distanceKm;
        o.etaSeconds = etaSeconds;
        o.createdAt = now;
        o.expiresAt = expiresAt;
        return o;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void close(OfferStatus outcome, Instant now) {
        if (status != OfferStatus.PENDING) {
            throw new IllegalStateException("Offer " + id + " is already " + status);
        }
        this.status = outcome;
        this.respondedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTripId() {
        return tripId;
    }

    public String getDriverId() {
        return driverId;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public long getEtaSeconds() {
        return etaSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}

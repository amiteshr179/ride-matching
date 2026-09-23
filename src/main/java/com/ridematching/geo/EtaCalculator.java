package com.ridematching.geo;

import java.time.Duration;

/**
 * Rough ETA from straight-line distance. Roads are not straight, so the
 * haversine distance is stretched by a detour factor and divided by an
 * average city speed.
 */
public class EtaCalculator {

    private final double avgSpeedKmh;
    private final double detourFactor;

    public EtaCalculator(double avgSpeedKmh, double detourFactor) {
        if (avgSpeedKmh <= 0) {
            throw new IllegalArgumentException("avgSpeedKmh must be positive");
        }
        if (detourFactor < 1.0) {
            throw new IllegalArgumentException("detourFactor must be >= 1");
        }
        this.avgSpeedKmh = avgSpeedKmh;
        this.detourFactor = detourFactor;
    }

    public double roadDistanceKm(GeoPoint from, GeoPoint to) {
        return Haversine.distanceKm(from, to) * detourFactor;
    }

    public Duration eta(GeoPoint from, GeoPoint to) {
        double hours = roadDistanceKm(from, to) / avgSpeedKmh;
        return Duration.ofSeconds(Math.round(hours * 3600));
    }
}

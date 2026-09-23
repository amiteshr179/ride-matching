package com.ridematching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "rides")
public record RideProperties(Drivers drivers, Matching matching, Eta eta) {

    public record Drivers(Duration staleAfter, Duration sweepInterval) {
    }

    public record Matching(
            List<Double> radiiKm,
            int candidatesPerRadius,
            Duration offerTimeout,
            int maxAttempts,
            Duration retryDelay,
            Duration pollInterval) {
    }

    public record Eta(double avgSpeedKmh, double detourFactor) {
    }
}

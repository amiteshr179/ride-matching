package com.ridematching.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/** Simple metered fare: base + per km + per minute, with a minimum. Amounts in INR. */
public class FareCalculator {

    private final BigDecimal base;
    private final BigDecimal perKm;
    private final BigDecimal perMinute;
    private final BigDecimal minimum;

    public FareCalculator(BigDecimal base, BigDecimal perKm, BigDecimal perMinute, BigDecimal minimum) {
        this.base = base;
        this.perKm = perKm;
        this.perMinute = perMinute;
        this.minimum = minimum;
    }

    public BigDecimal fare(double distanceKm, Duration rideTime) {
        if (distanceKm < 0 || rideTime.isNegative()) {
            throw new IllegalArgumentException("distance and time must not be negative");
        }
        BigDecimal minutes = BigDecimal.valueOf(rideTime.toSeconds()).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        BigDecimal total = base
                .add(perKm.multiply(BigDecimal.valueOf(distanceKm)))
                .add(perMinute.multiply(minutes));
        return total.max(minimum).setScale(2, RoundingMode.HALF_UP);
    }
}

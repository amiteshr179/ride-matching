package com.ridematching.geo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EtaCalculatorTest {

    @Test
    void etaUsesDetourFactorAndSpeed() {
        EtaCalculator calc = new EtaCalculator(30, 1.5);
        GeoPoint a = new GeoPoint(0, 0);
        GeoPoint b = new GeoPoint(1, 0); // ~111.19 km straight line

        assertThat(calc.roadDistanceKm(a, b)).isCloseTo(166.79, within(0.1));
        // 166.79 km at 30 km/h ~= 5.56 h
        Duration eta = calc.eta(a, b);
        assertThat(eta.toMinutes()).isBetween(332L, 335L);
    }

    @Test
    void zeroDistanceIsZeroEta() {
        EtaCalculator calc = new EtaCalculator(25, 1.3);
        GeoPoint p = new GeoPoint(17.385, 78.4867);
        assertThat(calc.eta(p, p)).isEqualTo(Duration.ZERO);
    }

    @Test
    void closerDriverHasShorterEta() {
        EtaCalculator calc = new EtaCalculator(25, 1.3);
        GeoPoint rider = new GeoPoint(17.385, 78.4867);
        GeoPoint near = new GeoPoint(17.390, 78.4867);
        GeoPoint far = new GeoPoint(17.420, 78.4867);
        assertThat(calc.eta(near, rider)).isLessThan(calc.eta(far, rider));
    }

    @Test
    void rejectsBadConfig() {
        assertThatThrownBy(() -> new EtaCalculator(0, 1.3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EtaCalculator(25, 0.9)).isInstanceOf(IllegalArgumentException.class);
    }
}

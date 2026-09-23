package com.ridematching.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FareCalculatorTest {

    private final FareCalculator calc = new FareCalculator(
            new BigDecimal("50"), new BigDecimal("12"), new BigDecimal("1.5"), new BigDecimal("80"));

    @Test
    void addsBaseDistanceAndTime() {
        // 50 + 10 km * 12 + 20 min * 1.5 = 200
        assertThat(calc.fare(10, Duration.ofMinutes(20))).isEqualByComparingTo("200.00");
    }

    @Test
    void shortRidesPayTheMinimum() {
        assertThat(calc.fare(0.5, Duration.ofMinutes(2))).isEqualByComparingTo("80.00");
    }

    @Test
    void roundsToPaise() {
        // 50 + 3.333 * 12 + 0.5 min * 1.5 = 90.746
        assertThat(calc.fare(3.333, Duration.ofSeconds(30))).isEqualByComparingTo("90.75");
    }

    @Test
    void rejectsNegativeInput() {
        assertThatThrownBy(() -> calc.fare(-1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}

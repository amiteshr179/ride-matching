package com.ridematching.trip;

import com.ridematching.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private Trip newTrip() {
        return Trip.request("r-1", new GeoPoint(17.38, 78.48), new GeoPoint(17.44, 78.37), "key-1", "hash", T0);
    }

    @Test
    void happyPathWalksEveryState() {
        Trip trip = newTrip();
        assertThat(trip.getStatus()).isEqualTo(TripStatus.REQUESTED);
        assertThat(trip.getNextMatchAt()).isEqualTo(T0);

        trip.assignDriver("d-1", T0.plusSeconds(5));
        assertThat(trip.getStatus()).isEqualTo(TripStatus.MATCHED);
        assertThat(trip.getDriverId()).isEqualTo("d-1");
        assertThat(trip.getNextMatchAt()).isNull();

        trip.markArriving(T0.plusSeconds(10));
        trip.start(T0.plusSeconds(300));
        trip.complete(T0.plusSeconds(1200));

        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(trip.getMatchedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(trip.getArrivingAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(trip.getStartedAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(trip.getCompletedAt()).isEqualTo(T0.plusSeconds(1200));
    }

    @Test
    void cannotStartBeforeMatch() {
        Trip trip = newTrip();
        assertThatThrownBy(() -> trip.start(T0)).isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("REQUESTED to IN_PROGRESS");
    }

    @Test
    void cannotCompleteTwice() {
        Trip trip = newTrip();
        trip.assignDriver("d-1", T0);
        trip.markArriving(T0);
        trip.start(T0);
        trip.complete(T0);
        assertThatThrownBy(() -> trip.complete(T0)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void cannotCancelInProgressTrip() {
        Trip trip = newTrip();
        trip.assignDriver("d-1", T0);
        trip.markArriving(T0);
        trip.start(T0);
        assertThatThrownBy(() -> trip.cancel("rider", T0)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void cancelRecordsReasonAndStopsMatching() {
        Trip trip = newTrip();
        trip.cancel("NO_DRIVERS", T0.plusSeconds(60));
        assertThat(trip.getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(trip.getCancelReason()).isEqualTo("NO_DRIVERS");
        assertThat(trip.getNextMatchAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = TripStatus.class, names = {"COMPLETED", "CANCELLED"})
    void terminalStatesAllowNothing(TripStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (TripStatus s : TripStatus.values()) {
            assertThat(terminal.canMoveTo(s)).isFalse();
        }
    }

    @Test
    void onlyActiveStatesHoldDriver() {
        assertThat(TripStatus.REQUESTED.holdsDriver()).isFalse();
        assertThat(TripStatus.MATCHED.holdsDriver()).isTrue();
        assertThat(TripStatus.DRIVER_ARRIVING.holdsDriver()).isTrue();
        assertThat(TripStatus.IN_PROGRESS.holdsDriver()).isTrue();
        assertThat(TripStatus.COMPLETED.holdsDriver()).isFalse();
        assertThat(TripStatus.CANCELLED.holdsDriver()).isFalse();
    }

    @Test
    void failedMatchAttemptsAreCounted() {
        Trip trip = newTrip();
        trip.recordFailedMatchAttempt(T0.plusSeconds(3));
        trip.recordFailedMatchAttempt(T0.plusSeconds(6));
        assertThat(trip.getMatchAttempts()).isEqualTo(2);
        assertThat(trip.getNextMatchAt()).isEqualTo(T0.plusSeconds(6));
    }
}

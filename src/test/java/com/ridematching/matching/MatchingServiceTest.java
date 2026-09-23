package com.ridematching.matching;

import com.ridematching.config.RideProperties;
import com.ridematching.driver.DriverAssignments;
import com.ridematching.driver.DriverAssignments.ReserveResult;
import com.ridematching.driver.DriverLocationService;
import com.ridematching.driver.NearbyDriver;
import com.ridematching.geo.EtaCalculator;
import com.ridematching.geo.GeoPoint;
import com.ridematching.trip.Trip;
import com.ridematching.trip.TripEvents;
import com.ridematching.trip.TripRepository;
import com.ridematching.trip.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchingServiceTest {

    private static final GeoPoint PICKUP = new GeoPoint(17.385, 78.4867);
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final TripRepository trips = mock(TripRepository.class);
    private final OfferRepository offers = mock(OfferRepository.class);
    private final DriverLocationService locations = mock(DriverLocationService.class);
    private final DriverAssignments assignments = mock(DriverAssignments.class);
    private final TripEvents events = mock(TripEvents.class);
    private final MutableClock clock = new MutableClock(T0);

    private MatchingService service;
    private Trip trip;

    @BeforeEach
    void setUp() {
        RideProperties props = new RideProperties(null,
                new RideProperties.Matching(List.of(1.0, 2.0, 4.0), 5, Duration.ofSeconds(10), 3,
                        Duration.ofSeconds(3), Duration.ofMillis(500)),
                null);
        service = new MatchingService(trips, offers, locations, assignments,
                new EtaCalculator(25, 1.3), events, clock, props);

        trip = Trip.request("r-1", PICKUP, new GeoPoint(17.44, 78.38), "k", "h", T0);
        when(trips.findByIdForUpdate(trip.getId())).thenReturn(Optional.of(trip));
        when(offers.findDriverIdsOfferedForTrip(trip.getId())).thenReturn(Set.of());
        when(assignments.reserve(any(), any(), any())).thenReturn(ReserveResult.RESERVED);
    }

    @Test
    void widensRadiusUntilADriverIsFound() {
        when(locations.findNearby(eq(PICKUP), eq(2.0), anyInt()))
                .thenReturn(List.of(driver("d-5", 0.0135, 1.5)));

        assertThat(service.runRound(trip.getId())).isEqualTo(MatchingService.Outcome.OFFERED);

        verify(locations).findNearby(eq(PICKUP), eq(1.0), anyInt());
        verify(locations, never()).findNearby(eq(PICKUP), eq(4.0), anyInt());
        assertThat(savedOffer().getDriverId()).isEqualTo("d-5");
        assertThat(savedOffer().getExpiresAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(trip.getNextMatchAt()).isNull();
    }

    @Test
    void offersToTheClosestDriverEvenIfSearchOrderDiffers() {
        when(locations.findNearby(eq(PICKUP), eq(1.0), anyInt()))
                .thenReturn(List.of(driver("far", 0.008, 0.9), driver("near", 0.001, 0.1)));

        service.runRound(trip.getId());

        assertThat(savedOffer().getDriverId()).isEqualTo("near");
    }

    @Test
    void skipsDriversWhoAlreadySawThisTrip() {
        when(offers.findDriverIdsOfferedForTrip(trip.getId())).thenReturn(Set.of("d-1"));
        when(locations.findNearby(eq(PICKUP), eq(1.0), anyInt()))
                .thenReturn(List.of(driver("d-1", 0.001, 0.1), driver("d-2", 0.002, 0.2)));

        service.runRound(trip.getId());

        verify(assignments, never()).reserve(eq("d-1"), any(), any());
        assertThat(savedOffer().getDriverId()).isEqualTo("d-2");
    }

    @Test
    void movesPastBusyAndStaleDrivers() {
        when(locations.findNearby(eq(PICKUP), eq(1.0), anyInt())).thenReturn(List.of(
                driver("busy", 0.001, 0.1), driver("stale", 0.002, 0.2), driver("free", 0.003, 0.3)));
        when(assignments.reserve(eq("busy"), any(), any())).thenReturn(ReserveResult.BUSY);
        when(assignments.reserve(eq("stale"), any(), any())).thenReturn(ReserveResult.STALE);

        service.runRound(trip.getId());

        assertThat(savedOffer().getDriverId()).isEqualTo("free");
    }

    @Test
    void reservationOutlivesTheOfferSoTheDatabaseTimeoutFiresFirst() {
        when(locations.findNearby(eq(PICKUP), eq(1.0), anyInt())).thenReturn(List.of(driver("d-1", 0.001, 0.1)));

        service.runRound(trip.getId());

        verify(assignments).reserve("d-1", trip.getId(), Duration.ofSeconds(10).plus(MatchingService.RESERVATION_GRACE));
    }

    @Test
    void retriesLaterAndThenGivesUp() {
        assertThat(service.runRound(trip.getId())).isEqualTo(MatchingService.Outcome.NO_DRIVER_RETRY_LATER);
        assertThat(trip.getNextMatchAt()).isEqualTo(T0.plusSeconds(3));

        // Not due yet: the round is skipped and nothing changes.
        assertThat(service.runRound(trip.getId())).isEqualTo(MatchingService.Outcome.SKIPPED);

        clock.advance(Duration.ofSeconds(3));
        assertThat(service.runRound(trip.getId())).isEqualTo(MatchingService.Outcome.NO_DRIVER_RETRY_LATER);
        clock.advance(Duration.ofSeconds(3));
        assertThat(service.runRound(trip.getId())).isEqualTo(MatchingService.Outcome.GAVE_UP);

        assertThat(trip.getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(trip.getCancelReason()).isEqualTo("NO_DRIVERS_AVAILABLE");
        verify(events).statusChanged(trip);
    }

    @Test
    void skipsTripsThatAreNoLongerWaiting() {
        trip.assignDriver("d-9", T0);
        assertThat(service.runRound(trip.getId())).isEqualTo(MatchingService.Outcome.SKIPPED);
        verify(locations, never()).findNearby(any(), any(Double.class), anyInt());
    }

    private Offer savedOffer() {
        ArgumentCaptor<Offer> captor = ArgumentCaptor.forClass(Offer.class);
        verify(offers).save(captor.capture());
        return captor.getValue();
    }

    private static NearbyDriver driver(String id, double latOffset, double distanceKm) {
        return new NearbyDriver(id, new GeoPoint(PICKUP.lat() + latOffset, PICKUP.lng()), distanceKm);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

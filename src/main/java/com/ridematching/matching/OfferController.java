package com.ridematching.matching;

import com.ridematching.trip.Trip;
import com.ridematching.trip.TripService;
import com.ridematching.trip.TripView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/drivers/{driverId}")
public class OfferController {

    private final OfferService offers;
    private final TripService trips;

    public OfferController(OfferService offers, TripService trips) {
        this.offers = offers;
        this.trips = trips;
    }

    public record OfferView(UUID tripId, String driverId, double distanceKm, long etaSeconds, Instant expiresAt,
                            double pickupLat, double pickupLng, double dropoffLat, double dropoffLng) {
    }

    /** Drivers poll this to see if a trip is waiting for them. 204 if not. */
    @GetMapping("/offer")
    public ResponseEntity<OfferView> currentOffer(@PathVariable String driverId) {
        return offers.pendingOfferFor(driverId)
                .map(o -> {
                    Trip t = trips.get(o.getTripId());
                    return ResponseEntity.ok(new OfferView(o.getTripId(), driverId, o.getDistanceKm(),
                            o.getEtaSeconds(), o.getExpiresAt(), t.pickup().lat(), t.pickup().lng(),
                            t.dropoff().lat(), t.dropoff().lng()));
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/offers/{tripId}/accept")
    public ResponseEntity<?> accept(@PathVariable String driverId, @PathVariable UUID tripId) {
        if (offers.accept(tripId, driverId) == OfferService.AcceptResult.ACCEPTED) {
            return ResponseEntity.ok(TripView.of(trips.get(tripId)));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Offer is no longer available"));
    }

    @PostMapping("/offers/{tripId}/reject")
    public ResponseEntity<Void> reject(@PathVariable String driverId, @PathVariable UUID tripId) {
        return offers.reject(tripId, driverId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}

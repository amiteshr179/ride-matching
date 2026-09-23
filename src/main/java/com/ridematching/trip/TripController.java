package com.ridematching.trip;

import com.ridematching.geo.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TripController {

    private final RideRequestService rideRequests;
    private final TripService trips;

    public TripController(RideRequestService rideRequests, TripService trips) {
        this.rideRequests = rideRequests;
        this.trips = trips;
    }

    public record RideRequest(
            @NotBlank String riderId,
            @NotNull Double pickupLat, @NotNull Double pickupLng,
            @NotNull Double dropoffLat, @NotNull Double dropoffLng) {
    }

    public record DriverAction(@NotBlank String driverId) {
    }

    public record CancelRequest(String reason) {
    }

    @PostMapping("/api/rides")
    public ResponseEntity<TripView> requestRide(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody RideRequest body) {
        var result = rideRequests.requestRide(body.riderId(),
                new GeoPoint(body.pickupLat(), body.pickupLng()),
                new GeoPoint(body.dropoffLat(), body.dropoffLng()),
                idempotencyKey);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(TripView.of(result.trip()));
    }

    @GetMapping("/api/trips/{id}")
    public TripView get(@PathVariable UUID id) {
        return TripView.of(trips.get(id));
    }

    @PostMapping("/api/trips/{id}/arriving")
    public TripView arriving(@PathVariable UUID id, @Valid @RequestBody DriverAction body) {
        return TripView.of(trips.markArriving(id, body.driverId()));
    }

    @PostMapping("/api/trips/{id}/start")
    public TripView start(@PathVariable UUID id, @Valid @RequestBody DriverAction body) {
        return TripView.of(trips.start(id, body.driverId()));
    }

    @PostMapping("/api/trips/{id}/complete")
    public TripView complete(@PathVariable UUID id, @Valid @RequestBody DriverAction body) {
        return TripView.of(trips.complete(id, body.driverId()));
    }

    @PostMapping("/api/trips/{id}/cancel")
    public TripView cancel(@PathVariable UUID id, @RequestBody(required = false) CancelRequest body) {
        String reason = body == null || body.reason() == null ? "RIDER_CANCELLED" : body.reason();
        return TripView.of(trips.cancel(id, reason));
    }
}

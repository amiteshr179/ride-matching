package com.ridematching.driver;

import com.ridematching.geo.EtaCalculator;
import com.ridematching.geo.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverLocationService locations;
    private final EtaCalculator eta;

    public DriverController(DriverLocationService locations, EtaCalculator eta) {
        this.locations = locations;
        this.eta = eta;
    }

    public record LocationUpdate(@NotNull Double lat, @NotNull Double lng) {
    }

    public record NearbyDriverView(String driverId, double lat, double lng, double distanceKm, long etaSeconds) {
    }

    @PostMapping("/{driverId}/location")
    public ResponseEntity<Void> updateLocation(@PathVariable String driverId, @Valid @RequestBody LocationUpdate body) {
        locations.updateLocation(driverId, new GeoPoint(body.lat(), body.lng()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/nearby")
    public List<NearbyDriverView> nearby(@RequestParam double lat, @RequestParam double lng,
                                         @RequestParam(defaultValue = "3") double radiusKm,
                                         @RequestParam(defaultValue = "10") int limit) {
        GeoPoint center = new GeoPoint(lat, lng);
        return locations.findNearby(center, radiusKm, limit).stream()
                .map(d -> new NearbyDriverView(d.driverId(), d.location().lat(), d.location().lng(),
                        d.distanceKm(), eta.eta(d.location(), center).toSeconds()))
                .toList();
    }
}

package com.ridematching.web;

import com.ridematching.driver.DriverAssignments;
import com.ridematching.driver.DriverLocationService;
import com.ridematching.trip.TripRepository;
import com.ridematching.trip.TripStatus;
import com.ridematching.trip.TripView;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One snapshot of everything the live map needs, polled by index.html. */
@RestController
public class MapController {

    private static final EnumSet<TripStatus> ACTIVE =
            EnumSet.of(TripStatus.REQUESTED, TripStatus.MATCHED, TripStatus.DRIVER_ARRIVING, TripStatus.IN_PROGRESS);

    private final DriverLocationService locations;
    private final DriverAssignments assignments;
    private final TripRepository trips;

    public MapController(DriverLocationService locations, DriverAssignments assignments, TripRepository trips) {
        this.locations = locations;
        this.assignments = assignments;
        this.trips = trips;
    }

    public record DriverMarker(String id, double lat, double lng, String state, String tripId) {
    }

    public record MapState(List<DriverMarker> drivers, List<TripView> activeTrips,
                           List<TripView> recentlyFinished, Map<TripStatus, Long> tripCounts) {
    }

    @GetMapping("/api/map/state")
    @Transactional(readOnly = true)
    public MapState state() {
        var positions = locations.freshDrivers();
        Map<String, String> current = assignments.current(positions.stream().map(p -> p.driverId()).toList());

        List<DriverMarker> drivers = positions.stream().map(p -> {
            String raw = current.get(p.driverId());
            String state = "AVAILABLE";
            String tripId = null;
            if (raw != null) {
                int colon = raw.indexOf(':');
                state = raw.substring(0, colon);
                tripId = raw.substring(colon + 1);
            }
            return new DriverMarker(p.driverId(), p.location().lat(), p.location().lng(), state, tripId);
        }).toList();

        List<TripView> active = trips.findByStatusIn(ACTIVE).stream().map(TripView::of).toList();
        List<TripView> finished = trips.findTop15ByStatusInOrderByRequestedAtDesc(
                EnumSet.of(TripStatus.COMPLETED, TripStatus.CANCELLED)).stream().map(TripView::of).toList();

        Map<TripStatus, Long> counts = new LinkedHashMap<>();
        for (TripStatus s : TripStatus.values()) {
            counts.put(s, 0L);
        }
        trips.countByStatus().forEach(c -> counts.put(c.getStatus(), c.getTotal()));

        return new MapState(drivers, active, finished, counts);
    }
}

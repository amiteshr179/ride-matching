package com.ridematching.driver;

import com.ridematching.geo.GeoPoint;

public record NearbyDriver(String driverId, GeoPoint location, double distanceKm) {
}

package com.ridematching.geo;

public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("lat out of range: " + lat);
        }
        if (lng < -180 || lng > 180) {
            throw new IllegalArgumentException("lng out of range: " + lng);
        }
    }
}

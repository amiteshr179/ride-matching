package com.ridematching.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HaversineTest {

    private static final GeoPoint CHARMINAR = new GeoPoint(17.3616, 78.4747);
    private static final GeoPoint HITEC_CITY = new GeoPoint(17.4435, 78.3772);

    @Test
    void samePointIsZero() {
        assertThat(Haversine.distanceKm(CHARMINAR, CHARMINAR)).isZero();
    }

    @Test
    void charminarToHitecCityIsAbout14Km() {
        // Flat-earth estimate: 9.1 km north-south and 10.4 km east-west -> ~13.8 km
        assertThat(Haversine.distanceKm(CHARMINAR, HITEC_CITY)).isCloseTo(13.78, within(0.05));
    }

    @Test
    void isSymmetric() {
        assertThat(Haversine.distanceKm(CHARMINAR, HITEC_CITY))
                .isCloseTo(Haversine.distanceKm(HITEC_CITY, CHARMINAR), within(1e-9));
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        assertThat(Haversine.distanceKm(new GeoPoint(0, 0), new GeoPoint(1, 0)))
                .isCloseTo(111.19, within(0.05));
    }

    @Test
    void antipodesDoNotProduceNaN() {
        double d = Haversine.distanceKm(new GeoPoint(0, 0), new GeoPoint(0, 180));
        assertThat(d).isCloseTo(Math.PI * Haversine.EARTH_RADIUS_KM, within(0.01));
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThatThrownBy(() -> new GeoPoint(91, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(0, -181)).isInstanceOf(IllegalArgumentException.class);
    }
}

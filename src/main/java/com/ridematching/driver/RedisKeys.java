package com.ridematching.driver;

public final class RedisKeys {

    public static final String DRIVERS_GEO = "drivers:geo";
    public static final String DRIVERS_SEEN = "drivers:seen";

    private RedisKeys() {
    }

    public static String assignment(String driverId) {
        return "driver:" + driverId + ":assignment";
    }
}

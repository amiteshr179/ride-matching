package com.ridematching.driver;

public final class RedisKeys {

    public static final String DRIVERS_GEO = "drivers:geo";
    public static final String DRIVERS_SEEN = "drivers:seen";
    /** Subset of drivers:geo with no offer or trip; this is what matching searches. */
    public static final String DRIVERS_AVAILABLE = "drivers:available";

    private RedisKeys() {
    }

    public static String assignment(String driverId) {
        return "driver:" + driverId + ":assignment";
    }
}

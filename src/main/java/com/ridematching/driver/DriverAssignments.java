package com.ridematching.driver;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is the driver working for right now? Stored as one Redis key per
 * driver ("OFFERED:tripId" or "ASSIGNED:tripId"). All writes go through Lua
 * scripts so check-and-set is atomic.
 */
@Component
public class DriverAssignments {

    public enum ReserveResult { RESERVED, BUSY, STALE }

    private final StringRedisTemplate redis;
    private final DriverLocationService locations;
    private final RedisScript<Long> reserveScript =
            RedisScript.of(new ClassPathResource("scripts/reserve_driver.lua"), Long.class);
    private final RedisScript<Long> confirmScript =
            RedisScript.of(new ClassPathResource("scripts/confirm_driver.lua"), Long.class);
    private final RedisScript<Long> releaseScript =
            RedisScript.of(new ClassPathResource("scripts/release_driver.lua"), Long.class);

    public DriverAssignments(StringRedisTemplate redis, DriverLocationService locations) {
        this.redis = redis;
        this.locations = locations;
    }

    public ReserveResult reserve(String driverId, UUID tripId, Duration ttl) {
        Long r = redis.execute(reserveScript,
                List.of(RedisKeys.assignment(driverId), RedisKeys.DRIVERS_SEEN),
                driverId, tripId.toString(), Long.toString(ttl.toMillis()),
                Long.toString(locations.freshCutoff()));
        if (r == null) {
            throw new IllegalStateException("reserve script returned null");
        }
        return switch (r.intValue()) {
            case 1 -> ReserveResult.RESERVED;
            case 0 -> ReserveResult.BUSY;
            default -> ReserveResult.STALE;
        };
    }

    public boolean confirm(String driverId, UUID tripId) {
        Long r = redis.execute(confirmScript, List.of(RedisKeys.assignment(driverId)), tripId.toString());
        return r != null && r == 1;
    }

    public boolean release(String driverId, UUID tripId) {
        Long r = redis.execute(releaseScript, List.of(RedisKeys.assignment(driverId)), tripId.toString());
        return r != null && r == 1;
    }

    /** Raw assignment values for many drivers at once, for the map view. */
    public Map<String, String> current(List<String> driverIds) {
        if (driverIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(driverIds.size());
        for (String id : driverIds) {
            keys.add(RedisKeys.assignment(id));
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < driverIds.size(); i++) {
            String v = values == null ? null : values.get(i);
            if (v != null) {
                out.put(driverIds.get(i), v);
            }
        }
        return out;
    }
}

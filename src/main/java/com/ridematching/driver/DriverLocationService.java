package com.ridematching.driver;

import com.ridematching.config.RideProperties;
import com.ridematching.geo.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Service
public class DriverLocationService {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationService.class);
    private static final int SWEEP_BATCH = 500;

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final RideProperties.Drivers props;
    private final RedisScript<Long> updateScript =
            RedisScript.of(new ClassPathResource("scripts/update_location.lua"), Long.class);
    private final RedisScript<Long> sweepScript =
            RedisScript.of(new ClassPathResource("scripts/sweep_stale.lua"), Long.class);

    public DriverLocationService(StringRedisTemplate redis, Clock clock, RideProperties props) {
        this.redis = redis;
        this.clock = clock;
        this.props = props.drivers();
    }

    public void updateLocation(String driverId, GeoPoint point) {
        redis.execute(updateScript,
                List.of(RedisKeys.DRIVERS_GEO, RedisKeys.DRIVERS_SEEN, RedisKeys.DRIVERS_AVAILABLE,
                        RedisKeys.assignment(driverId)),
                driverId, Double.toString(point.lng()), Double.toString(point.lat()),
                Long.toString(clock.millis()));
    }

    /**
     * Available drivers within the radius, nearest first. Drivers whose last
     * ping is older than the stale window are skipped even if the sweeper
     * has not removed them yet.
     */
    public List<NearbyDriver> findNearby(GeoPoint center, double radiusKm, int limit) {
        var results = redis.opsForGeo().search(
                RedisKeys.DRIVERS_AVAILABLE,
                GeoReference.fromCoordinate(center.lng(), center.lat()),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeCoordinates().includeDistance().sortAscending().limit(limit));
        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }

        List<GeoResult<GeoLocation<String>>> content = results.getContent();
        Object[] ids = content.stream().map(r -> r.getContent().getName()).toArray();
        List<Double> seen = redis.opsForZSet().score(RedisKeys.DRIVERS_SEEN, ids);
        long cutoff = freshCutoff();

        List<NearbyDriver> out = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            Double ts = seen == null ? null : seen.get(i);
            if (ts == null || ts < cutoff) {
                continue;
            }
            GeoLocation<String> loc = content.get(i).getContent();
            Point p = loc.getPoint();
            out.add(new NearbyDriver(loc.getName(), new GeoPoint(p.getY(), p.getX()),
                    content.get(i).getDistance().getValue()));
        }
        return out;
    }

    public record DriverPosition(String driverId, GeoPoint location) {
    }

    /** Every driver who pinged recently, for the live map. */
    public List<DriverPosition> freshDrivers() {
        var ids = redis.opsForZSet().rangeByScore(RedisKeys.DRIVERS_SEEN, freshCutoff(), Double.POSITIVE_INFINITY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> idList = new ArrayList<>(ids);
        List<Point> points = redis.opsForGeo().position(RedisKeys.DRIVERS_GEO, idList.toArray(String[]::new));
        List<DriverPosition> out = new ArrayList<>(idList.size());
        for (int i = 0; i < idList.size(); i++) {
            Point p = points == null ? null : points.get(i);
            if (p != null) {
                out.add(new DriverPosition(idList.get(i), new GeoPoint(p.getY(), p.getX())));
            }
        }
        return out;
    }

    public GeoPoint locationOf(String driverId) {
        List<Point> pos = redis.opsForGeo().position(RedisKeys.DRIVERS_GEO, driverId);
        if (pos == null || pos.isEmpty() || pos.get(0) == null) {
            return null;
        }
        return new GeoPoint(pos.get(0).getY(), pos.get(0).getX());
    }

    public boolean isFresh(String driverId) {
        Double ts = redis.opsForZSet().score(RedisKeys.DRIVERS_SEEN, driverId);
        return ts != null && ts >= freshCutoff();
    }

    public long freshCutoff() {
        return clock.millis() - props.staleAfter().toMillis();
    }

    @Scheduled(fixedDelayString = "${rides.drivers.sweep-interval}")
    public void sweepStaleDrivers() {
        Long removed = redis.execute(sweepScript,
                List.of(RedisKeys.DRIVERS_GEO, RedisKeys.DRIVERS_SEEN, RedisKeys.DRIVERS_AVAILABLE),
                Long.toString(freshCutoff()), Integer.toString(SWEEP_BATCH));
        if (removed != null && removed > 0) {
            log.info("Removed {} stale drivers", removed);
        }
    }
}

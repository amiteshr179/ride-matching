package com.ridematching.driver;

import com.ridematching.config.RideProperties;
import com.ridematching.geo.GeoPoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Lua scripts against a real Redis, because the whole point is how
 * Redis executes them.
 */
@Testcontainers
class DriverAssignmentsRedisTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private DriverLocationService locations;
    private DriverAssignments assignments;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        RideProperties props = new RideProperties(
                new RideProperties.Drivers(Duration.ofSeconds(15), Duration.ofSeconds(5)), null, null);
        locations = new DriverLocationService(redis, Clock.systemUTC(), props);
        assignments = new DriverAssignments(redis, locations);
        locations.updateLocation("d-1", new GeoPoint(17.385, 78.4867));
    }

    @Test
    void onlyOneOfManyConcurrentReservationsWins() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DriverAssignments.ReserveResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID trip = UUID.randomUUID();
            results.add(pool.submit(() -> {
                start.await();
                return assignments.reserve("d-1", trip, Duration.ofSeconds(30));
            }));
        }
        start.countDown();

        int reserved = 0;
        for (var f : results) {
            if (f.get() == DriverAssignments.ReserveResult.RESERVED) {
                reserved++;
            }
        }
        pool.shutdown();
        assertThat(reserved).isEqualTo(1);
    }

    @Test
    void confirmOnlyWorksForTheTripHoldingTheOffer() {
        UUID tripA = UUID.randomUUID();
        UUID tripB = UUID.randomUUID();
        assertThat(assignments.reserve("d-1", tripA, Duration.ofSeconds(30)))
                .isEqualTo(DriverAssignments.ReserveResult.RESERVED);

        assertThat(assignments.confirm("d-1", tripB)).isFalse();
        assertThat(assignments.confirm("d-1", tripA)).isTrue();
        assertThat(redis.opsForValue().get(RedisKeys.assignment("d-1"))).isEqualTo("ASSIGNED:" + tripA);
        // A confirmed assignment has no TTL; it lasts until the trip ends.
        assertThat(redis.getExpire(RedisKeys.assignment("d-1"))).isEqualTo(-1L);
    }

    @Test
    void releaseFromAnOldTripDoesNotFreeANewerReservation() {
        UUID oldTrip = UUID.randomUUID();
        UUID newTrip = UUID.randomUUID();
        assignments.reserve("d-1", oldTrip, Duration.ofSeconds(30));
        assertThat(assignments.release("d-1", oldTrip)).isTrue();
        assignments.reserve("d-1", newTrip, Duration.ofSeconds(30));

        assertThat(assignments.release("d-1", oldTrip)).isFalse();
        assertThat(assignments.reserve("d-1", UUID.randomUUID(), Duration.ofSeconds(30)))
                .isEqualTo(DriverAssignments.ReserveResult.BUSY);
    }

    @Test
    void expiredOfferCannotBeConfirmed() throws InterruptedException {
        UUID trip = UUID.randomUUID();
        assignments.reserve("d-1", trip, Duration.ofMillis(100));
        Thread.sleep(250);
        assertThat(assignments.confirm("d-1", trip)).isFalse();
        assertThat(assignments.reserve("d-1", UUID.randomUUID(), Duration.ofSeconds(30)))
                .isEqualTo(DriverAssignments.ReserveResult.RESERVED);
    }

    @Test
    void staleOrUnknownDriverCannotBeReserved() {
        redis.opsForZSet().add(RedisKeys.DRIVERS_SEEN, "d-old",
                Instant.now().minusSeconds(60).toEpochMilli());
        assertThat(assignments.reserve("d-old", UUID.randomUUID(), Duration.ofSeconds(30)))
                .isEqualTo(DriverAssignments.ReserveResult.STALE);
        assertThat(assignments.reserve("d-never-seen", UUID.randomUUID(), Duration.ofSeconds(30)))
                .isEqualTo(DriverAssignments.ReserveResult.STALE);
    }

    @Test
    void sweeperRemovesOnlyStaleDrivers() {
        Clock later = Clock.fixed(Instant.now().plusSeconds(20), ZoneOffset.UTC);
        locations.updateLocation("d-2", new GeoPoint(17.39, 78.49));
        RideProperties props = new RideProperties(
                new RideProperties.Drivers(Duration.ofSeconds(15), Duration.ofSeconds(5)), null, null);
        DriverLocationService futureView = new DriverLocationService(redis, later, props);
        futureView.updateLocation("d-2", new GeoPoint(17.39, 78.49));

        futureView.sweepStaleDrivers();

        assertThat(redis.opsForZSet().score(RedisKeys.DRIVERS_SEEN, "d-1")).isNull();
        assertThat(redis.opsForGeo().position(RedisKeys.DRIVERS_GEO, "d-1").get(0)).isNull();
        assertThat(redis.opsForZSet().score(RedisKeys.DRIVERS_SEEN, "d-2")).isNotNull();
    }
}

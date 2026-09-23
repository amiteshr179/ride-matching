package com.ridematching;

import com.ridematching.driver.DriverAssignments;
import com.ridematching.trip.Trip;
import com.ridematching.trip.TripRepository;
import com.ridematching.trip.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Full stack against real Postgres, Redis and Kafka containers, driven
 * through the HTTP API the same way the simulator uses it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rides.matching.offer-timeout=2s",
        "rides.matching.poll-interval=100ms",
        "rides.matching.retry-delay=300ms",
        "rides.matching.max-attempts=3",
})
class RideFlowIntegrationTest {

    private static final double PICKUP_LNG = 78.4867;
    // Each test gets its own pickup ~22 km from the others (beyond the 8 km
    // max search radius), so leftover trips from one test can not steal
    // another test's drivers.
    private static final AtomicInteger AREA = new AtomicInteger();

    private double pickupLat;

    @Autowired
    Environment env;
    @Autowired
    JsonMapper json;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TripRepository trips;
    @Autowired
    DriverAssignments assignments;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        pickupLat = 16.0 + AREA.getAndIncrement() * 0.2;
    }

    @Test
    void rideGoesFromRequestToInvoiceThroughRejectAndTimeout() {
        ping("near", pickupLat + 0.002);
        ping("mid", pickupLat + 0.010);
        ping("far", pickupLat + 0.020);

        Response created = requestRide("rider-1", UUID.randomUUID().toString());
        assertThat(created.status()).isEqualTo(201);
        String tripId = created.body().get("id").asString();

        // Nearest driver gets the first offer, and rejects it.
        awaitOfferFor("near", tripId);
        assertThat(post("/api/drivers/near/offers/" + tripId + "/reject", null).status()).isEqualTo(204);

        // Next closest gets it, but lets it time out.
        awaitOfferFor("mid", tripId);
        ping("near", pickupLat + 0.002);
        ping("far", pickupLat + 0.020);
        awaitOfferFor("far", tripId);
        assertThat(post("/api/drivers/mid/offers/" + tripId + "/accept", null).status()).isEqualTo(409);

        Response accepted = post("/api/drivers/far/offers/" + tripId + "/accept", null);
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().get("status").asString()).isEqualTo("MATCHED");
        assertThat(redis.opsForValue().get("driver:far:assignment")).isEqualTo("ASSIGNED:" + tripId);

        // Out-of-order action is refused.
        assertThat(post("/api/trips/" + tripId + "/complete", "{\"driverId\":\"far\"}").status()).isEqualTo(409);
        // Someone else's trip is refused.
        assertThat(post("/api/trips/" + tripId + "/arriving", "{\"driverId\":\"near\"}").status()).isEqualTo(403);

        for (String step : List.of("arriving", "start", "complete")) {
            assertThat(post("/api/trips/" + tripId + "/" + step, "{\"driverId\":\"far\"}").status()).isEqualTo(200);
        }
        assertThat(get("/api/trips/" + tripId).body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(redis.hasKey("driver:far:assignment")).isFalse();

        // Billing only hears about the trip through Kafka.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(get("/api/invoices/" + tripId).status()).isEqualTo(200));
        Response invoice = get("/api/invoices/" + tripId);
        assertThat(invoice.body().get("driverId").asString()).isEqualTo("far");
        assertThat(invoice.body().get("amount").asDouble()).isGreaterThanOrEqualTo(80.0);

        List<String> offerLog = jdbc.sql("select driver_id || ':' || status from trip_offers where trip_id = ? order by created_at")
                .param(UUID.fromString(tripId)).query(String.class).list();
        assertThat(offerLog).containsExactly("near:REJECTED", "mid:EXPIRED", "far:ACCEPTED");
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameTrip() {
        String key = UUID.randomUUID().toString();
        Response first = requestRide("rider-2", key);
        Response second = requestRide("rider-2", key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(jdbc.sql("select count(*) from trips where idempotency_key = ?").param(key)
                .query(Long.class).single()).isEqualTo(1L);

        Response different = post("/api/rides", rideBody("someone-else"), key);
        assertThat(different.status()).isEqualTo(422);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneTrip() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Response>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return requestRide("rider-3", key);
            }));
        }
        go.countDown();
        List<String> ids = new ArrayList<>();
        for (var f : results) {
            Response r = f.get();
            assertThat(r.status()).isIn(200, 201);
            ids.add(r.body().get("id").asString());
        }
        pool.shutdown();

        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(jdbc.sql("select count(*) from trips where idempotency_key = ?").param(key)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void oneDriverIsNeverOfferedTwoTripsAtOnce() {
        ping("solo", pickupLat + 0.001);
        List<String> tripIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tripIds.add(requestRide("rider-many-" + i, UUID.randomUUID().toString()).body().get("id").asString());
        }

        // Exactly one of the five trips holds the driver.
        String offered = awaitAnyOfferFor("solo");
        long pending = jdbc.sql("select count(*) from trip_offers where driver_id = 'solo' and status = 'PENDING'")
                .query(Long.class).single();
        assertThat(pending).isEqualTo(1L);
        assertThat(tripIds).contains(offered);

        assertThat(post("/api/drivers/solo/offers/" + offered + "/accept", null).status()).isEqualTo(200);

        // The other four can not find a free driver and give up.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (String id : tripIds) {
                if (!id.equals(offered)) {
                    assertThat(get("/api/trips/" + id).body().get("status").asString()).isEqualTo("CANCELLED");
                }
            }
        });
        assertThat(get("/api/trips/" + offered).body().get("driverId").asString()).isEqualTo("solo");
    }

    @Test
    void tripWithNoDriversNearbyIsCancelled() {
        Response created = requestRide("lonely-rider", UUID.randomUUID().toString());
        String tripId = created.body().get("id").asString();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode trip = get("/api/trips/" + tripId).body();
            assertThat(trip.get("status").asString()).isEqualTo("CANCELLED");
            assertThat(trip.get("cancelReason").asString()).isEqualTo("NO_DRIVERS_AVAILABLE");
        });
    }

    @Test
    void cancellingARequestedTripFreesTheOfferedDriver() {
        ping("cancel-driver", pickupLat + 0.001);
        String tripId = requestRide("cancel-rider", UUID.randomUUID().toString()).body().get("id").asString();
        awaitOfferFor("cancel-driver", tripId);

        assertThat(post("/api/trips/" + tripId + "/cancel", "{\"reason\":\"changed my mind\"}").status()).isEqualTo(200);

        assertThat(redis.hasKey("driver:cancel-driver:assignment")).isFalse();
        assertThat(get("/api/drivers/cancel-driver/offer").status()).isEqualTo(204);
    }

    @Test
    void freeDriverBehindManyBusyDriversIsStillFound() {
        // 12 busy drivers packed around the pickup, more than candidates-per-radius.
        for (int i = 0; i < 12; i++) {
            ping("busy-" + i, pickupLat + 0.0005 + i * 0.0001);
            assertThat(assignments.reserve("busy-" + i, UUID.randomUUID(), Duration.ofMinutes(5)))
                    .isEqualTo(DriverAssignments.ReserveResult.RESERVED);
        }
        ping("free-but-further", pickupLat + 0.005);

        String tripId = requestRide("crowded-rider", UUID.randomUUID().toString()).body().get("id").asString();

        awaitOfferFor("free-but-further", tripId);
    }

    @Test
    void databaseRefusesTwoActiveTripsForOneDriver() {
        Instant now = Instant.now();
        Trip a = Trip.request("ra", new com.ridematching.geo.GeoPoint(pickupLat, PICKUP_LNG),
                new com.ridematching.geo.GeoPoint(17.44, 78.38), UUID.randomUUID().toString(), "h", now);
        Trip b = Trip.request("rb", new com.ridematching.geo.GeoPoint(pickupLat, PICKUP_LNG),
                new com.ridematching.geo.GeoPoint(17.44, 78.38), UUID.randomUUID().toString(), "h", now);
        a.assignDriver("db-guard", now);
        b.assignDriver("db-guard", now);
        trips.saveAndFlush(a);

        assertThatThrownBy(() -> trips.saveAndFlush(b)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(trips.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(TripStatus.MATCHED);
    }

    // ---- helpers ----

    private void ping(String driverId, double lat) {
        assertThat(post("/api/drivers/" + driverId + "/location",
                "{\"lat\":" + lat + ",\"lng\":" + PICKUP_LNG + "}").status()).isEqualTo(204);
    }

    private void awaitOfferFor(String driverId, String tripId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Response r = get("/api/drivers/" + driverId + "/offer");
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().get("tripId").asString()).isEqualTo(tripId);
        });
    }

    private String awaitAnyOfferFor(String driverId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> get("/api/drivers/" + driverId + "/offer").status() == 200);
        return get("/api/drivers/" + driverId + "/offer").body().get("tripId").asString();
    }

    private Response requestRide(String riderId, String key) {
        return post("/api/rides", rideBody(riderId), key);
    }

    private String rideBody(String riderId) {
        return """
                {"riderId":"%s","pickupLat":%s,"pickupLng":%s,"dropoffLat":17.44,"dropoffLng":78.38}
                """.formatted(riderId, pickupLat, PICKUP_LNG);
    }

    record Response(int status, JsonNode body) {
    }

    private Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private Response post(String path, String body) {
        return post(path, body, null);
    }

    private Response post(String path, String body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return send(b.build());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = r.body() == null || r.body().isBlank() ? null : json.readTree(r.body());
            return new Response(r.statusCode(), body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }
}

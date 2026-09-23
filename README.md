# Ride matching service

A small Uber-style backend. Drivers send their location every few seconds, riders ask for a ride, and the service finds the nearest free driver, offers them the trip, and moves on to the next driver if they say no or do not answer. Trips go through a proper state machine, and every change is published to Kafka, where a billing consumer picks up completed trips.

Stack: Java 21, Spring Boot 4.1, PostgreSQL 16, Redis 7, Kafka 3.9, Docker Compose. The simulator is plain Python using only the standard library.

## Why I built it

I wanted to work through the parts of a ride-hailing backend that are easy to describe and hard to get right: geo search on fast-moving data, never giving one driver to two riders, timeouts that still work after a restart, and keeping Redis, Postgres and Kafka in agreement. A small working version taught me more than reading about it.

## How it works

1. **Driver locations.** `POST /api/drivers/{id}/location` runs a Lua script that writes the position to a Redis GEO set and the ping time to a sorted set. A sweeper removes drivers who have not pinged for 15 seconds. Drivers with no offer or trip are also kept in a separate `drivers:available` GEO set, and that is the set matching searches.
2. **Ride request.** `POST /api/rides` with an `Idempotency-Key` header creates a trip in `REQUESTED`. Sending the same key again returns the same trip.
3. **Matching.** A background loop picks up waiting trips. It searches 1, 2, 4 and then 8 km around the pickup, ranks drivers by ETA (Haversine distance, a detour factor, average speed), and tries to reserve the best one with an atomic Lua script. If it works, an offer is written to Postgres with a 10 second deadline.
4. **Offers.** The driver polls `GET /api/drivers/{id}/offer` and can accept or reject. On reject or timeout, the driver is freed and the next driver is tried. Drivers who were already asked are skipped. If nobody is found after 10 rounds, the trip is cancelled with `NO_DRIVERS_AVAILABLE`.
5. **Trip lifecycle.** `REQUESTED -> MATCHED -> DRIVER_ARRIVING -> IN_PROGRESS -> COMPLETED`, with `CANCELLED` allowed before the ride starts. Illegal moves get a 409.
6. **Events.** After each status change commits, a `TripEvent` goes to the `trip-events` Kafka topic, keyed by trip id. The billing consumer computes a fare for completed trips and writes an invoice. Duplicate events are ignored.
7. **Map.** `http://localhost:8080/` is a plain HTML page with Leaflet. It polls `/api/map/state` every second and shows drivers coloured by state, waiting riders, and lines for trips in progress.

More detail on the reasoning is in [DESIGN_NOTES.md](DESIGN_NOTES.md).

## Preventing double-booking

I used Redis Lua scripts rather than a lock. Reserving a driver is a single "is this driver free and still online? then mark them as offered to this trip" step, and Redis runs a script without anything else running in between. A lock would need separate lock, read, write and unlock calls, plus a lease that can run out while the holder is paused. The stored value (`OFFERED:{tripId}` or `ASSIGNED:{tripId}`) also records which trip holds the driver, so a late release from an old trip cannot free a driver who now belongs to someone else. As a second safety net, Postgres has a partial unique index allowing only one active trip per driver.

## The hardest problem

The hardest part was keeping Redis and Postgres in agreement about who holds a driver. Redis decides quickly, Postgres is the durable record, and either write can fail after the other has succeeded. I ended up with a strict order. Every action locks the trip row first. On accept, Redis confirms before the DB commit, and a rollback hook undoes the Redis change if the commit fails. Drivers are only released after a commit succeeds. The Redis offer also lives 5 seconds longer than the DB offer, so the database timeout always fires first.

Two real problems came up while building it:

- **Busy drivers hid free ones.** At first, matching searched the GEO set of all drivers and asked Redis for the nearest N. I wrote a test with 12 busy drivers packed around the pickup and one free driver 550 m away. The free driver was never offered the trip, and the trip was cancelled. The fix was a separate `drivers:available` GEO set, kept in sync by the same Lua scripts (reserve removes, release and pings add back), so the search only returns drivers who can actually take a ride.
- **Stopping the simulator left drivers stuck.** After a few simulator runs I started it again, and only 1 of 60 rides got a driver. The earlier runs had been stopped mid-trip, so all 50 driver IDs still had active trips on the server, which quite correctly treated them as busy. I added `GET /api/drivers/{id}/trip` so a driver app can find its current trip when it starts, and the simulator now resumes those trips before doing anything else. On the next run all 50 trips were resumed and finished, and new rides were matched again.

Smaller things: the app failed to start once because my Kafka topic bean was called `tripEvents`, the same name as an existing component. And the Spring Initializr metadata gave me `4.1.1.RELEASE` as the Boot version, which is not the real Maven version (`4.1.1`).

## How to run it

You need Docker Desktop, Java 21 and Python 3.9 or newer. Maven comes through the wrapper.

```bash
cp .env.example .env              # change the password if you like
docker compose up -d --wait       # Postgres, Redis, Kafka

# If you changed values in .env, export them so the app sees them too:
set -a; source .env; set +a

./mvnw spring-boot:run            # app on http://localhost:8080
```

In another terminal:

```bash
python3 scripts/simulator.py      # 50 drivers around Hyderabad, a ride every 3 s
```

Then open http://localhost:8080/ to watch the map. The simulator takes options such as `--drivers 20 --ride-every 5 --duration 120 --seed 1`. Run `python3 scripts/simulator.py --help` to see them all.

To walk through one ride step by step (reject, then timeout, then accept, then complete):

```bash
python3 scripts/smoke_flow.py
```

Tests (Docker must be running, because Testcontainers starts its own Postgres, Redis and Kafka):

```bash
./mvnw test
```

When you are done:

```bash
docker compose down        # add -v to also delete the Postgres volume
```

### Main endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/drivers/{id}/location` | Driver location ping `{lat, lng}` |
| GET | `/api/drivers/{id}/offer` | Current offer for the driver, or 204 |
| POST | `/api/drivers/{id}/offers/{tripId}/accept` | Accept an offer (409 if too late) |
| POST | `/api/drivers/{id}/offers/{tripId}/reject` | Reject an offer |
| GET | `/api/drivers/{id}/trip` | The driver's active trip, or 204 |
| GET | `/api/drivers/nearby?lat=&lng=&radiusKm=` | Available drivers near a point, with ETA |
| POST | `/api/rides` | Request a ride; needs an `Idempotency-Key` header |
| GET | `/api/trips/{id}` | Trip details |
| POST | `/api/trips/{id}/arriving`, `/start`, `/complete` | Driver actions, body `{driverId}` |
| POST | `/api/trips/{id}/cancel` | Cancel before the ride starts |
| GET | `/api/invoices/{tripId}` | Invoice written by the billing consumer |
| GET | `/api/map/state` | Snapshot used by the map page |

## Tests

- Unit tests: Haversine and ETA, the trip state machine, fares, and the matching round (radius widening, skipping busy, stale and already-asked drivers, giving up).
- Redis tests against a real Redis container: 32 threads racing for one driver (exactly one wins), confirm and release only working for the right trip, expired offers, stale drivers, the available set, and the sweeper.
- Integration test with Testcontainers (Postgres, Redis, Kafka) over HTTP: a full ride through reject, timeout, accept and complete, ending in an invoice written from a Kafka event; idempotency, including 8 concurrent requests with one key; five trips competing for one driver; no drivers leading to cancellation; cancelling frees the offered driver; the DB unique index; and the busy-driver crowding case above.

## Known limitations

- **Events can be lost on a crash.** Events are sent after commit, not through an outbox. If the process dies between the commit and the Kafka send, that event never goes out.
- **No handling for drivers who disappear mid-trip.** If a driver goes silent during a trip, the trip stays active until that driver comes back. There is no server-side job to reassign or close such trips, and the state machine does not allow cancelling a trip that is `IN_PROGRESS`.
- **Polling instead of pushing.** Drivers poll for offers, the map polls every second, and the timeout and matching loops poll every 500 ms. So timeouts can fire up to about half a second late.
- **Single Redis node.** The Lua scripts touch several keys, which would need hash tags to work in Redis Cluster. Ping times use the app server's clock.
- **Approximate ETAs.** They are straight-line distance times a fixed factor. There are no roads and no traffic.
- **Greedy matching.** Each trip takes the best free driver at that moment, first come first served. A driver who rejected or ignored a trip is never asked about it again.
- **No authentication.** Anyone can call the API as any driver or rider.
- **Hard-coded fares.** The fare numbers live in code, and billing shares the app's database instead of having its own.

## What I would do next

1. Add a transactional outbox for trip events.
2. Add a reaper for trips whose driver has been silent for too long, plus a rule for what happens to an `IN_PROGRESS` trip in that case.
3. Push offers and map updates over WebSockets or server-sent events instead of polling.
4. Add metrics (time to match, offer acceptance rate, timeouts) and look at them under a heavier simulator load.
5. Try batch matching over a short window instead of first come first served.
6. Shard location data by area, and make the Redis keys cluster-safe.

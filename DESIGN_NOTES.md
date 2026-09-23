# Design notes

These are my notes on the main choices in this project and the questions I expect to be asked about it.

## Five key design decisions

### 1. Redis Lua scripts to stop double-booking, not a distributed lock

Each driver has one Redis key, `driver:{id}:assignment`. It is either missing (free), `OFFERED:{tripId}` (with a TTL), or `ASSIGNED:{tripId}` (no TTL). There are three scripts:

- `reserve_driver.lua` checks that the key is missing and the driver pinged recently. If both are true it sets the key to `OFFERED:{tripId}` and removes the driver from the available set.
- `confirm_driver.lua` changes `OFFERED:{tripId}` to `ASSIGNED:{tripId}`, but only if this exact trip still holds the offer.
- `release_driver.lua` deletes the key, but only if it still belongs to this trip, and puts the driver back in the available set.

Why Lua and not a lock (Redisson `RLock`, or `SET NX` used as a lock):

- The operation really is "check a value, then set it". Redis runs one script at a time, so a script is a check-and-set with no gap in the middle. With a lock I would need lock, read, write, unlock: four round trips and more ways to fail.
- A lock has its own lease. If the holder pauses (GC, network) past the lease, two holders can both think they own the driver. Then I would need fencing tokens. With a script the state is the lock, so there is nothing separate to expire.
- The value records who holds the driver (`OFFERED:tripA`). Releases and confirms compare against it, so a late release from an old trip cannot free a driver who now belongs to a newer trip.
- The offer TTL cleans up after itself. If the app dies after reserving but before writing the offer, the driver frees up once the TTL runs out.

Postgres is the backstop. A partial unique index `trips(driver_id) WHERE status IN ('MATCHED','DRIVER_ARRIVING','IN_PROGRESS')` means the database refuses a second active trip for one driver, even if Redis were flushed or wrong.

Order matters on accept: first confirm in Redis, then commit in Postgres. If the commit fails, a rollback hook releases the driver in Redis. Doing it the other way round (commit first) could leave a MATCHED trip in the DB whose Redis offer had already expired and been given to someone else.

### 2. Redis for live location, Postgres for anything that must survive

- `drivers:geo` (GEO set) holds every driver's last position, for the map.
- `drivers:seen` (sorted set, score = last ping time in ms) tracks staleness. A GEO member cannot have its own TTL, so a sweeper script removes everyone whose last ping is older than 15 s from all three sets in one atomic step. Searches also skip stale drivers, so correctness does not depend on the sweeper having run.
- `drivers:available` (GEO set) holds only drivers with no offer or trip. Matching searches this set. I added it after a test showed that a search for the nearest N drivers could return only busy drivers and miss a free one slightly further away.

Positions change every couple of seconds and losing a few of them costs nothing, so Redis fits. Trips, offers and invoices have to be durable and consistent, so they go in Postgres with Flyway migrations.

### 3. Matching and offer timeouts are driven by the database, not in-memory timers

A trip that needs a driver has `next_match_at` set. A pending offer has `expires_at`. Two `@Scheduled` loops poll every 500 ms:

- `expireOffers` finds pending offers past `expires_at`, marks them EXPIRED, frees the driver and sets the trip's `next_match_at = now`.
- `matchWaitingTrips` finds REQUESTED trips with `next_match_at <= now` and runs one matching round for each.

Each round and each timeout locks the trip row (`SELECT ... FOR UPDATE`) and checks the state again under that lock. Accept, reject, timeout and matching all lock the trip first, in the same order, so an accept and a timeout for the same offer cannot both succeed.

Why not `ScheduledExecutorService.schedule(timeout)` per offer: an in-memory timer disappears on restart and only exists on one instance. Polling the table survives restarts and works with more than one instance. The cost is up to one poll interval of extra latency.

A matching round searches rings of 1, 2, 4 and 8 km around the pickup and ranks candidates by ETA. It skips drivers already asked for this trip and tries to reserve each candidate in order. If no one is found it retries after 3 s, and after 10 empty rounds it cancels the trip with `NO_DRIVERS_AVAILABLE`.

### 4. Idempotency key stored on the trip, with a unique constraint

`POST /api/rides` requires an `Idempotency-Key` header. The key goes into `trips.idempotency_key` (UNIQUE), together with a SHA-256 hash of the request body.

- Same key and same body: return the original trip with 200 instead of 201.
- Same key and different body: 422, because that is a client bug.
- Two requests with the same key at the same moment: one insert wins, the other hits the unique constraint, catches it, re-reads, and returns the winner. The integration test fires 8 requests at once and checks there is exactly one row.

Putting the key on the trip row, rather than in a separate cache, means the key and the trip are created in the same transaction. Nothing can be half-written.

### 5. Kafka events after commit, keyed by trip id, and an idempotent consumer

Every status change raises a `TripEvent` inside the transaction. A `@TransactionalEventListener(AFTER_COMMIT)` sends it to the `trip-events` topic, keyed by trip id, so all events for one trip go to one partition in order. Consumers never see a change that was rolled back.

The billing consumer only reads the topic, never the trips table. On COMPLETED it computes a fare and inserts an invoice with `ON CONFLICT (trip_id) DO NOTHING`, so a redelivered event does not bill twice.

The known gap: if the app crashes after the DB commit but before Kafka accepts the message, that event is lost. The fix is a transactional outbox (write the event to an `outbox` table in the same transaction, then relay it). I did not build that here.

## Ten likely interview questions

**1. How do you stop two riders getting the same driver?**
An atomic Redis Lua script checks that the driver's assignment key is missing and sets it in one step. Redis runs scripts one at a time, so only one caller can win. A test runs 32 threads against one driver and exactly one reservation wins. Postgres also has a partial unique index allowing one active trip per driver.

**2. Why not just use a Redis lock like Redisson?**
A lock guards a read and a write that happen separately, and it has a lease that can expire while the holder is paused. My operation is one check-and-set, which a script does atomically, and the stored value itself shows which trip holds the driver. No lease, no fencing tokens, one round trip.

**3. What happens if a driver never answers an offer?**
The offer row has `expires_at` (10 s). A poller marks it EXPIRED, frees the driver in Redis and makes the trip due for matching again. The next round skips drivers who were already asked. The Redis reservation lives 5 s longer than the offer, so the database timeout always fires first. The TTL only matters if the app crashed.

**4. What if the driver accepts at the same moment the offer times out?**
Both paths lock the trip row first, so they run one after the other. If the timeout runs first, the offer is no longer PENDING and the accept gets 409. If the accept runs first, the offer is ACCEPTED and the timeout finds nothing to expire. The accept also has to pass `confirm_driver.lua`, which fails if the Redis offer has already gone.

**5. How do you expire drivers who stop sending locations?**
Every ping writes the position and a timestamp (in `drivers:seen`) in one script. A sweeper removes drivers whose timestamp is older than 15 s, and searches also filter out stale timestamps. So a driver who went quiet is never offered a trip, even between sweeps.

**6. How does the radius widening work, and how do you choose the "best" driver?**
Search 1 km, then 2, 4 and 8 km around the pickup, only in the available-drivers set. Within a ring, sort by Haversine-based ETA and try to reserve each one in turn. The first successful reservation gets the offer. If every ring is empty, retry in 3 s, and give up after 10 tries.

**7. How is ETA calculated?**
Haversine great-circle distance, times a detour factor of 1.3 because roads are not straight, divided by an average city speed of 25 km/h. It is cheap and good enough for ranking nearby drivers. For real ETAs I would call a routing engine with live traffic.

**8. How do idempotency keys work here?**
The key is stored on the trip with a unique constraint, along with a hash of the body. A retry with the same key returns the same trip. A reused key with a different body gets 422. Concurrent duplicates are settled by the unique constraint.

**9. Can a Kafka consumer see an event for a change that was rolled back? Can it miss one?**
It cannot see a rolled-back change, because publishing happens after commit. It can miss one if the process dies between commit and send. A transactional outbox would fix that. On the consumer side, billing uses the trip id as the invoice primary key, so duplicates are harmless.

**10. How would this scale to a real city?**
Shard the location data by geography (by city, or by geohash prefix), so each Redis shard holds one area. The Lua scripts touch several keys, so in Redis Cluster those keys need the same hash tag. Run several app instances: the pollers are already safe because of row locks, and could move to `SKIP LOCKED` batches. Push offers to drivers over WebSockets instead of polling. Use an outbox for events. Batch matching (solving many riders and drivers together) would give better overall pickup times than first-come first-served.

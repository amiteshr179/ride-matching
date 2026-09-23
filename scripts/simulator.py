"""Simulates drivers moving around Hyderabad and riders requesting rides.

Usage:
    python3 scripts/simulator.py                 # 50 drivers, a ride every 3 s
    python3 scripts/simulator.py --drivers 20 --ride-every 5 --duration 120

Standard library only. Time is sped up: cars move at --speed-kmh (default
180) so a trip finishes in a minute or two instead of twenty.
"""
import argparse
import json
import math
import random
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

# Rough box around central Hyderabad (Secunderabad to Charminar, Gachibowli to Uppal).
CENTER = (17.405, 78.455)
SPREAD_LAT = 0.07
SPREAD_LNG = 0.10


def call(base, method, path, body=None, headers=None, timeout=5):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, method=method, data=data,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            text = r.read()
            return r.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        return e.code, None
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return 0, None


def random_point():
    return (CENTER[0] + random.uniform(-SPREAD_LAT, SPREAD_LAT),
            CENTER[1] + random.uniform(-SPREAD_LNG, SPREAD_LNG))


def km_between(a, b):
    lat1, lng1, lat2, lng2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def step_towards(pos, target, km):
    dist = km_between(pos, target)
    if dist <= km:
        return target, True
    f = km / dist
    return (pos[0] + (target[0] - pos[0]) * f, pos[1] + (target[1] - pos[1]) * f), False


class Driver:
    def __init__(self, driver_id, args):
        self.id = driver_id
        self.args = args
        self.pos = random_point()
        self.waypoint = random_point()
        self.state = "IDLE"          # IDLE, TO_PICKUP, TO_DROPOFF, OFFLINE
        self.trip = None
        self.ignored = set()
        self.offline_until = 0.0

    def resume(self, stats):
        """Like a driver app on restart: ask the server if we were on a trip and carry on."""
        base = self.args.base_url
        status, trip = call(base, "GET", f"/api/drivers/{self.id}/trip")
        if status != 200:
            return
        self.trip = {"id": trip["id"],
                     "pickup": (trip["pickupLat"], trip["pickupLng"]),
                     "dropoff": (trip["dropoffLat"], trip["dropoffLng"])}
        if trip["status"] == "MATCHED":
            call(base, "POST", f"/api/trips/{trip['id']}/arriving", {"driverId": self.id})
        if trip["status"] == "IN_PROGRESS":
            self.pos = self.trip["pickup"]
            self.state = "TO_DROPOFF"
        else:
            self.state = "TO_PICKUP"
        stats.bump("trips_resumed")

    def tick(self, step_km, stats):
        base = self.args.base_url
        now = time.time()

        if self.state == "OFFLINE":
            if now < self.offline_until:
                return
            self.state = "IDLE"
            stats.bump("drivers_back_online")

        if self.state == "IDLE":
            if random.random() < self.args.offline_chance:
                # Stop sending pings for a while; the server should drop us.
                self.state = "OFFLINE"
                self.offline_until = now + random.uniform(30, 60)
                stats.bump("drivers_went_offline")
                return
            self.pos, arrived = step_towards(self.pos, self.waypoint, step_km * 0.4)
            if arrived:
                self.waypoint = random_point()
            self.handle_offer(stats)
        elif self.state == "TO_PICKUP":
            self.pos, arrived = step_towards(self.pos, self.trip["pickup"], step_km)
            if arrived:
                status, _ = call(base, "POST", f"/api/trips/{self.trip['id']}/start", {"driverId": self.id})
                if status == 200:
                    self.state = "TO_DROPOFF"
                else:
                    self.reset()
        elif self.state == "TO_DROPOFF":
            self.pos, arrived = step_towards(self.pos, self.trip["dropoff"], step_km)
            if arrived:
                status, _ = call(base, "POST", f"/api/trips/{self.trip['id']}/complete", {"driverId": self.id})
                if status == 200:
                    stats.bump("trips_completed")
                self.reset()

        call(base, "POST", f"/api/drivers/{self.id}/location", {"lat": self.pos[0], "lng": self.pos[1]})

    def handle_offer(self, stats):
        base = self.args.base_url
        status, offer = call(base, "GET", f"/api/drivers/{self.id}/offer")
        if status != 200 or offer["tripId"] in self.ignored:
            return
        trip_id = offer["tripId"]
        roll = random.random()
        if roll < self.args.accept_prob:
            status, _ = call(base, "POST", f"/api/drivers/{self.id}/offers/{trip_id}/accept")
            if status == 200:
                stats.bump("offers_accepted")
                self.trip = {"id": trip_id,
                             "pickup": (offer["pickupLat"], offer["pickupLng"]),
                             "dropoff": (offer["dropoffLat"], offer["dropoffLng"])}
                call(base, "POST", f"/api/trips/{trip_id}/arriving", {"driverId": self.id})
                self.state = "TO_PICKUP"
            else:
                stats.bump("accepts_too_late")
        elif roll < self.args.accept_prob + self.args.reject_prob:
            call(base, "POST", f"/api/drivers/{self.id}/offers/{trip_id}/reject")
            stats.bump("offers_rejected")
        else:
            # Pretend the driver did not see it; the server should time out and move on.
            self.ignored.add(trip_id)
            stats.bump("offers_ignored")

    def reset(self):
        self.state = "IDLE"
        self.trip = None
        self.waypoint = random_point()


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.counts = {}

    def bump(self, key, n=1):
        with self.lock:
            self.counts[key] = self.counts.get(key, 0) + n

    def snapshot(self):
        with self.lock:
            return dict(sorted(self.counts.items()))


def request_ride(args, stats, rider_no):
    pickup = random_point()
    # Drop-off 1.5 to 5 km away in a random direction.
    dist_km = random.uniform(1.5, 5.0)
    bearing = random.uniform(0, 2 * math.pi)
    dropoff = (pickup[0] + dist_km / 111.32 * math.cos(bearing),
               pickup[1] + dist_km / (111.32 * math.cos(math.radians(pickup[0]))) * math.sin(bearing))
    body = {"riderId": f"rider-{rider_no}", "pickupLat": pickup[0], "pickupLng": pickup[1],
            "dropoffLat": dropoff[0], "dropoffLng": dropoff[1]}
    key = str(uuid.uuid4())
    status, trip = call(args.base_url, "POST", "/api/rides", body, {"Idempotency-Key": key})
    if status == 201:
        stats.bump("rides_requested")
    else:
        stats.bump("ride_request_errors")
        return
    # Now and then, act like a flaky mobile network and send the same request again.
    if random.random() < args.retry_chance:
        status2, trip2 = call(args.base_url, "POST", "/api/rides", body, {"Idempotency-Key": key})
        if status2 == 200 and trip2 and trip2["id"] == trip["id"]:
            stats.bump("duplicate_requests_deduplicated")
        else:
            stats.bump("duplicate_request_problems")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--drivers", type=int, default=50)
    p.add_argument("--ride-every", type=float, default=3.0, help="seconds between ride requests")
    p.add_argument("--tick", type=float, default=2.0, help="seconds between driver location pings")
    p.add_argument("--speed-kmh", type=float, default=180.0, help="simulated driving speed (sped up)")
    p.add_argument("--accept-prob", type=float, default=0.75)
    p.add_argument("--reject-prob", type=float, default=0.15, help="the rest of offers are ignored")
    p.add_argument("--offline-chance", type=float, default=0.002, help="per tick, for idle drivers")
    p.add_argument("--retry-chance", type=float, default=0.1, help="chance a rider resends the same request")
    p.add_argument("--duration", type=float, default=0, help="seconds to run, 0 = until Ctrl+C")
    p.add_argument("--seed", type=int, default=None)
    args = p.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    status, _ = call(args.base_url, "GET", "/actuator/health")
    if status != 200:
        raise SystemExit(f"App is not reachable at {args.base_url} (status {status}). Start it first.")

    drivers = [Driver(f"drv-{i:02d}", args) for i in range(1, args.drivers + 1)]
    stats = Stats()
    pool = ThreadPoolExecutor(max_workers=16)
    step_km = args.speed_kmh / 3600.0 * args.tick
    list(pool.map(lambda d: d.resume(stats), drivers))

    print(f"Simulating {len(drivers)} drivers; a ride every {args.ride_every}s. Open {args.base_url}/ for the map.")
    started = time.time()
    next_ride = started
    next_report = started + 10
    rider_no = 0
    try:
        while args.duration <= 0 or time.time() - started < args.duration:
            tick_start = time.time()
            list(pool.map(lambda d: d.tick(step_km, stats), drivers))

            while time.time() >= next_ride:
                rider_no += 1
                pool.submit(request_ride, args, stats, rider_no)
                next_ride += args.ride_every

            if time.time() >= next_report:
                _, state = call(args.base_url, "GET", "/api/map/state")
                trips = state["tripCounts"] if state else {}
                print(f"[{int(time.time() - started):4d}s] sim={stats.snapshot()}")
                print(f"        server trips={trips}")
                next_report += 10

            time.sleep(max(0.0, args.tick - (time.time() - tick_start)))
    except KeyboardInterrupt:
        pass
    finally:
        pool.shutdown(wait=True)
        print("Final:", stats.snapshot())


if __name__ == "__main__":
    main()

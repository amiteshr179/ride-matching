"""Walks one ride through reject -> timeout -> accept -> complete.

Run with the app up: python3 scripts/smoke_flow.py
Standard library only.
"""
import json
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"


def call(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, method=method, data=data,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r:
            text = r.read()
            return r.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


def main():
    tag = uuid.uuid4().hex[:4]
    drivers = {f"smoke-{tag}-1": (17.387, 78.4867),
               f"smoke-{tag}-2": (17.398, 78.4867),
               f"smoke-{tag}-3": (17.412, 78.4867)}
    d1, d2, d3 = drivers

    def ping():
        for d, (lat, lng) in drivers.items():
            call("POST", f"/api/drivers/{d}/location", {"lat": lat, "lng": lng})

    def offers():
        return {d: call("GET", f"/api/drivers/{d}/offer")[0] for d in drivers}

    ping()
    status, trip = call("POST", "/api/rides",
                        {"riderId": f"rider-{tag}", "pickupLat": 17.385, "pickupLng": 78.4867,
                         "dropoffLat": 17.44, "dropoffLng": 78.38},
                        {"Idempotency-Key": str(uuid.uuid4())})
    trip_id = trip["id"]
    print("ride requested:", status, trip["status"])

    time.sleep(1.5)
    print("offers after first round:", offers())
    print("nearest driver rejects:", call("POST", f"/api/drivers/{d1}/offers/{trip_id}/reject")[0])

    time.sleep(1.5)
    print("offers after reject:", offers())
    print("second driver ignores the offer, waiting for timeout...")
    for _ in range(6):
        ping()
        time.sleep(2)
    print("offers after timeout:", offers())
    print("second driver accepts too late:", call("POST", f"/api/drivers/{d2}/offers/{trip_id}/accept")[0])

    status, trip = call("POST", f"/api/drivers/{d3}/offers/{trip_id}/accept")
    print("third driver accepts:", status, trip["status"], trip["driverId"])
    for step in ["arriving", "start", "complete"]:
        status, trip = call("POST", f"/api/trips/{trip_id}/{step}", {"driverId": d3})
        print(step, status, trip["status"] if status == 200 else trip)
    print("trip id:", trip_id)


if __name__ == "__main__":
    main()

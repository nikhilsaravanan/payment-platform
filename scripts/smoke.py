#!/usr/bin/env python3
"""Exercise the running Compose stack using only Python's standard library."""
import argparse
import json
import subprocess
import time
import uuid
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def request(base, path, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def eventually(check, description, timeout=90):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = check()
            if last:
                return last
        except (OSError, ValueError) as error:
            last = error
        time.sleep(0.2)
    raise AssertionError(f"Timed out waiting for {description}; last result: {last}")


def compose(*args):
    subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--recovery", action="store_true", help="Also stop ledger and restart payment to test durable recovery")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    eventually(lambda: request(base, "/actuator/health")[0] == 200, "healthy API")
    body = {"sourceAccount": "alice", "destinationAccount": "merchant", "amountMinor": 2500, "currency": "USD"}
    key = str(uuid.uuid4())
    status, payment = request(base, "/payments", body, key)
    assert status == 202, (status, payment)
    path = "/payments/" + payment["id"]
    status, replay = request(base, "/payments", body, key)
    assert status == 200 and replay["id"] == payment["id"], (status, replay)
    status, conflict = request(base, "/payments", dict(body, amountMinor=2600), key)
    assert status == 409, (status, conflict)
    eventually(lambda: request(base, path)[1]["status"] == "COMPLETED", "completed payment")
    print(f"PASS create/get/replay/conflict/completion: {payment['id']}")
    if args.recovery:
        compose("stop", "ledger")
        try:
            status, pending = request(base, "/payments", body, str(uuid.uuid4()))
            assert status == 202, (status, pending)
            path = "/payments/" + pending["id"]
            assert request(base, path)[1]["status"] == "PENDING"
            compose("restart", "payment")
            eventually(lambda: request(base, "/actuator/health")[0] == 200, "restarted API")
            assert request(base, path)[1]["status"] == "PENDING"
        finally:
            compose("start", "ledger")
        eventually(lambda: request(base, path)[1]["status"] == "COMPLETED", "recovered payment")
        print(f"PASS ledger outage and payment restart recovery: {pending['id']}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Exercise the running Compose stack using only Python's standard library."""
import argparse
import json
import subprocess
import time
import uuid
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
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
    source, destination = "payer-" + uuid.uuid4().hex, "merchant-" + uuid.uuid4().hex
    for account in (source, destination):
        status, result = request(base, "/accounts", {"accountId": account, "currency": "USD"})
        assert status == 201 and result["balanceMinor"] == 0, (status, result)
    funding = {"accountId": source, "amountMinor": 5000, "currency": "USD"}
    funding_key = str(uuid.uuid4())
    status, funded = request(base, "/funding", funding, funding_key)
    assert status == 201, (status, funded)
    status, replay = request(base, "/funding", funding, funding_key)
    assert status == 200 and replay == funded, (status, replay)
    assert request(base, "/funding", dict(funding, amountMinor=5001), funding_key)[0] == 409
    assert request(base, "/accounts/" + source)[1]["balanceMinor"] == 5000
    print("PASS account creation, balances and idempotent simulated funding", flush=True)
    body = {"sourceAccount": source, "destinationAccount": destination, "amountMinor": 2500, "currency": "USD"}
    key = str(uuid.uuid4())
    status, payment = request(base, "/payments", body, key)
    assert status == 202, (status, payment)
    path = "/payments/" + payment["id"]
    status, replay = request(base, "/payments", body, key)
    assert status == 200 and replay["id"] == payment["id"], (status, replay)
    status, conflict = request(base, "/payments", dict(body, amountMinor=2600), key)
    assert status == 409, (status, conflict)
    eventually(lambda: request(base, path)[1]["status"] == "COMPLETED", "completed payment")
    print(f"PASS create/get/replay/conflict/completion: {payment['id']}", flush=True)
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
        print(f"PASS ledger outage and payment restart recovery: {pending['id']}", flush=True)
    spent = 5000 if args.recovery else 2500
    assert request(base, "/accounts/" + source)[1]["balanceMinor"] == 5000 - spent
    assert request(base, "/accounts/" + destination)[1]["balanceMinor"] == spent
    print("PASS final account balances", flush=True)
    remaining = request(base, "/accounts/" + source)[1]["balanceMinor"]
    status, rejected = request(base, "/payments", dict(body, amountMinor=remaining + 1), str(uuid.uuid4()))
    assert status == 202, (status, rejected)
    rejected_path = "/payments/" + rejected["id"]
    eventually(lambda: request(base, rejected_path)[1]["status"] == "REJECTED", "insufficient-funds rejection")
    assert request(base, rejected_path)[1]["rejectionReason"] == "INSUFFICIENT_FUNDS"
    assert request(base, "/accounts/" + source)[1]["balanceMinor"] == remaining
    print("PASS insufficient-funds terminal rejection", flush=True)

    # Distinct HTTP requests share a limited funded source; all must reach a terminal outcome.
    a, b = "race-" + uuid.uuid4().hex, "receiver-" + uuid.uuid4().hex
    for account in (a, b):
        assert request(base, "/accounts", {"accountId": account, "currency": "USD"})[0] == 201
    assert request(base, "/funding", {"accountId": a, "amountMinor": 1000, "currency": "USD"}, str(uuid.uuid4()))[0] == 201
    competing = dict(body, sourceAccount=a, destinationAccount=b, amountMinor=500)
    with ThreadPoolExecutor(max_workers=8) as pool:
        submissions = list(pool.map(lambda _: request(base, "/payments", competing, str(uuid.uuid4())), range(8)))
    outcomes = []
    for status, result in submissions:
        assert status == 202, (status, result)
        path = "/payments/" + result["id"]
        eventually(lambda: request(base, path)[1]["status"] in ("COMPLETED", "REJECTED"), "concurrent payment outcome")
        outcomes.append(request(base, path)[1])
    assert sum(p["status"] == "COMPLETED" for p in outcomes) == 2, outcomes
    assert sum(p["status"] == "REJECTED" and p["rejectionReason"] == "INSUFFICIENT_FUNDS" for p in outcomes) == 6, outcomes
    assert request(base, "/accounts/" + a)[1]["balanceMinor"] == 0
    assert request(base, "/accounts/" + b)[1]["balanceMinor"] == 1000
    print("PASS 8 distinct payments: 2 completed, 6 rejected; no overspending", flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Exercise signed delivery, receiver outage, restart recovery and durable dedupe."""
import argparse
import hashlib
import hmac
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from smoke import request, eventually

ROOT = Path(__file__).resolve().parents[1]


def compose(*args):
    subprocess.run(["docker", "compose", "--profile", "webhooks-demo", *args], cwd=ROOT, check=True)


def secret():
    value = os.environ.get("WEBHOOK_DEMO_SECRET")
    if value:
        return value
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text().splitlines():
            if line.startswith("WEBHOOK_DEMO_SECRET="):
                return line.split("=", 1)[1].strip().strip("\"'")
    raise SystemExit("Set WEBHOOK_DEMO_SECRET for the demo receiver")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--receiver-url", default="http://localhost:8081")
    args = parser.parse_args()
    base, receiver, key = args.base_url.rstrip("/"), args.receiver_url.rstrip("/"), secret()
    eventually(lambda: request(base, "/actuator/health")[0] == 200, "payment API")
    eventually(lambda: request(receiver, "/health")[0] == 200, "webhook receiver")
    payer, merchant = "webhook-payer-" + uuid.uuid4().hex, "webhook-merchant-" + uuid.uuid4().hex
    for account in (payer, merchant):
        assert request(base, "/accounts", {"accountId": account, "currency": "USD"})[0] == 201
    assert request(base, "/funding", {"accountId": payer, "amountMinor": 1000, "currency": "USD"}, str(uuid.uuid4()))[0] == 201
    status, subscription = request(base, "/webhook-subscriptions", {"accountId": merchant,
        "url": "http://webhook-receiver:8081/webhooks", "signingSecret": key}, str(uuid.uuid4()))
    assert status == 201 and "signingSecret" not in subscription, (status, subscription)
    sub_path = "/webhook-subscriptions/" + subscription["id"]

    def deliveries():
        return request(base, sub_path + "/deliveries?limit=100")[1]

    def payment(amount):
        status, resource = request(base, "/payments", {"sourceAccount": payer, "destinationAccount": merchant,
            "amountMinor": amount, "currency": "USD"}, str(uuid.uuid4()))
        assert status == 202, (status, resource)
        return resource

    compose("stop", "webhook-receiver")
    try:
        first = payment(500)
        eventually(lambda: request(base, "/payments/" + first["id"])[1]["status"] == "COMPLETED", "money completion during receiver outage")
        eventually(lambda: any(d["attemptCount"] >= 1 and d["status"] == "PENDING" for d in deliveries()), "persisted failed delivery attempt")
        compose("restart", "payment")
    finally:
        compose("start", "webhook-receiver")
    eventually(lambda: request(base, "/actuator/health")[0] == 200, "restarted payment service")
    eventually(lambda: any(d["resourceId"] == first["id"] and d["status"] == "DELIVERED" for d in deliveries()), "delivery recovery", timeout=150)
    delivered = next(d for d in deliveries() if d["resourceId"] == first["id"])
    assert delivered["attemptCount"] >= 2
    print("PASS money completes during receiver outage; delivery survives payment restart", flush=True)

    compose("restart", "webhook-receiver")
    eventually(lambda: request(receiver, "/health")[0] == 200, "restarted receiver")
    with urllib.request.urlopen(base + "/webhook-events/" + delivered["eventId"], timeout=5) as response:
        raw = response.read()
    timestamp = int(time.time())
    digest = hmac.new(key.encode(), str(timestamp).encode() + b"." + raw, hashlib.sha256).hexdigest()
    headers = {"Content-Type": "application/json", "Webhook-Id": delivered["eventId"], "Webhook-Signature": f"t={timestamp},v1={digest}"}
    with urllib.request.urlopen(urllib.request.Request(receiver + "/webhooks", data=raw, headers=headers), timeout=5) as response:
        assert json.load(response)["duplicate"] is True
    receipts = request(receiver, "/events")[1]
    matching = [r for r in receipts if r["eventId"] == delivered["eventId"]]
    assert len(matching) == 1 and matching[0]["attemptCount"] >= 2
    try:
        urllib.request.urlopen(urllib.request.Request(receiver + "/webhooks", data=raw + b" ", headers=headers), timeout=5)
        raise AssertionError("Tampered body accepted")
    except urllib.error.HTTPError as error:
        assert error.code == 401
    print("PASS persisted receiver dedupe after restart and tampered-signature rejection", flush=True)

    status, refund = request(base, "/payments/" + first["id"] + "/refunds", {"amountMinor": 100}, str(uuid.uuid4()))
    assert status == 202, (status, refund)
    eventually(lambda: request(base, "/refunds/" + refund["id"])[1]["status"] == "COMPLETED", "completed refund event")
    rejected = payment(1000)
    eventually(lambda: request(base, "/payments/" + rejected["id"])[1]["status"] == "REJECTED", "rejected payment event")
    status, excess = request(base, "/payments/" + first["id"] + "/refunds", {"amountMinor": 500}, str(uuid.uuid4()))
    assert status == 202, (status, excess)
    eventually(lambda: request(base, "/refunds/" + excess["id"])[1]["status"] == "REJECTED", "rejected refund event")
    eventually(lambda: len(deliveries()) == 4 and all(d["status"] == "DELIVERED" for d in deliveries()), "all four event types")
    assert {d["eventType"] for d in deliveries()} == {"payment.completed", "payment.rejected", "refund.completed", "refund.rejected"}
    print("PASS signed payment/refund completion and rejection notifications", flush=True)

    req = urllib.request.Request(base + sub_path, method="DELETE")
    with urllib.request.urlopen(req, timeout=5) as response:
        assert response.status == 204
    last = payment(1)
    eventually(lambda: request(base, "/payments/" + last["id"])[1]["status"] == "COMPLETED", "completion after subscription disable")
    assert len(deliveries()) == 4
    print("PASS disable stops future fanout while retaining delivery audit", flush=True)


if __name__ == "__main__":
    main()

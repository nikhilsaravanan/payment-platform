#!/usr/bin/env python3
"""Reconcile intentionally edited simulated processor postings over real REST money flows."""
import argparse
import copy
import uuid
from collections import Counter
from datetime import datetime, timedelta
from smoke import request, eventually, compose


def created_at(resource):
    return datetime.fromisoformat(resource["createdAt"].replace("Z", "+00:00"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    payer, merchant, other = [name + uuid.uuid4().hex for name in ("recon-payer-", "recon-merchant-", "recon-other-")]
    for account in (payer, merchant, other):
        assert request(base, "/accounts", {"accountId": account, "currency": "USD"})[0] == 201
    assert request(base, "/funding", {"accountId": payer, "amountMinor": 5000, "currency": "USD"}, str(uuid.uuid4()))[0] == 201

    def payment(destination, amount, terminal):
        status, p = request(base, "/payments", {"sourceAccount": payer, "destinationAccount": destination,
            "amountMinor": amount, "currency": "USD"}, str(uuid.uuid4()))
        assert status == 202
        eventually(lambda: request(base, "/payments/" + p["id"])[1]["status"] == terminal, "terminal payment")
        return p

    paid = payment(merchant, 1000, "COMPLETED")
    rejected = payment(merchant, 100000, "REJECTED")
    outside = payment(other, 1, "COMPLETED")
    status, refund = request(base, "/payments/" + paid["id"] + "/refunds", {"amountMinor": 400}, str(uuid.uuid4()))
    assert status == 202
    eventually(lambda: request(base, "/refunds/" + refund["id"])[1]["status"] == "COMPLETED", "terminal refund")

    # This fixture SIMULATES an external processor export; there is no real processor integration.
    payment_row = {"reference": "sim-payment-" + paid["id"], "operationType": "PAYMENT", "operationId": paid["id"], "amountMinor": 1000, "currency": "USD"}
    refund_row = {"reference": "sim-refund-" + refund["id"], "operationType": "REFUND", "operationId": refund["id"], "amountMinor": 400, "currency": "USD"}
    # Use database-origin timestamps, avoiding assumptions about host clock alignment.
    times = [created_at(r) for r in (paid, rejected, refund)]
    clean = {"accountId": merchant, "from": min(times).isoformat(timespec="microseconds"),
        "to": (max(times) + timedelta(microseconds=1)).isoformat(timespec="microseconds"), "records": [payment_row, refund_row]}
    key = str(uuid.uuid4())
    status, report = request(base, "/reconciliation-reports", clean, key)
    assert status == 201 and report["internalCount"] == 3 and report["matchedCount"] == 2 and report["findingCount"] == 0, (status, report, clean)
    assert request(base, "/reconciliation-reports", clean, key) == (200, report)
    print("PASS clean payment/refund reconciliation excludes unposted rejection", flush=True)

    edited = copy.deepcopy(clean)
    wrong = dict(payment_row, amountMinor=999, currency="EUR")
    edited["records"] = [wrong, dict(wrong),
        {"reference": "unknown", "operationType": "PAYMENT", "operationId": str(uuid.uuid4()), "amountMinor": 1, "currency": "USD"},
        {"reference": "other-merchant", "operationType": "PAYMENT", "operationId": outside["id"], "amountMinor": 1, "currency": "USD"},
        {"reference": "false-success", "operationType": "PAYMENT", "operationId": rejected["id"], "amountMinor": 100000, "currency": "USD"}]
    balances = {a: request(base, "/accounts/" + a)[1]["balanceMinor"] for a in (payer, merchant)}
    edited_key = str(uuid.uuid4())
    status, discrepant = request(base, "/reconciliation-reports", edited, edited_key)
    assert status == 201 and discrepant["matchedCount"] == 0 and discrepant["findingCount"] == 10, (status, discrepant)
    path = "/reconciliation-reports/" + discrepant["id"]
    findings, after = [], 0
    while True:
        status, page = request(base, path + f"/findings?limit=2&after={after}")
        assert status == 200
        findings.extend(page["items"])
        after = page["nextAfter"]
        if after is None:
            break
    assert Counter(f["code"] for f in findings) == Counter({"AMOUNT_MISMATCH": 2, "CURRENCY_MISMATCH": 2,
        "DUPLICATE_OPERATION": 1, "DUPLICATE_REFERENCE": 1, "MISSING_INTERNAL": 1, "MISSING_PROCESSOR": 1,
        "OUT_OF_SCOPE": 1, "INTERNAL_NOT_COMPLETED": 1}), findings
    assert [f["ordinal"] for f in findings] == list(range(1, 11))
    assert findings[0]["expected"]["amountMinor"] == 1000 and findings[0]["actual"]["amountMinor"] == 999
    assert request(base, "/reconciliation-reports", clean, edited_key)[0] == 409
    evidence = request(base, path + "/evidence")[1]
    assert evidence["request"]["records"] == edited["records"] and len(evidence["internalSnapshot"]) == 4
    assert {a: request(base, "/accounts/" + a)[1]["balanceMinor"] for a in balances} == balances
    print("PASS all eight discrepancy types, evidence, stable pagination and unchanged balances", flush=True)

    compose("restart", "payment")
    eventually(lambda: request(base, "/actuator/health")[0] == 200, "restarted payment service")
    assert request(base, "/reconciliation-reports", edited, edited_key) == (200, discrepant)
    assert request(base, path + "/evidence")[1] == evidence
    assert request(base, path)[1] == discrepant
    print("PASS report, frozen evidence and idempotent replay survive process restart", flush=True)


if __name__ == "__main__":
    main()

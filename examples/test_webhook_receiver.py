import hashlib
import hmac
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from webhook_receiver import Receiver, Rejected, build_server


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "receipts.sqlite"
        self.key = "fixture-receiver-key-" + "x" * 40
        self.now = 1700000000
        self.receiver = Receiver(self.path, self.key, now=lambda: self.now)
        self.event = {"id": str(uuid.uuid4()), "type": "payment.completed", "accountId": "café", "schemaVersion": 1, "data": {"amountMinor": 100}}
        self.body = json.dumps(self.event, ensure_ascii=False).encode()

    def tearDown(self):
        self.temp.cleanup()

    def signature(self, body=None, timestamp=None):
        timestamp = self.now if timestamp is None else timestamp
        raw = self.body if body is None else body
        digest = hmac.new(self.key.encode(), str(timestamp).encode() + b"." + raw, hashlib.sha256).hexdigest()
        return f"t={timestamp},v1={digest}"

    def test_valid_utf8_event_and_duplicate_have_one_durable_receipt(self):
        self.assertFalse(self.receiver.accept(self.body, self.signature(), self.event["id"]))
        self.assertTrue(self.receiver.accept(self.body, self.signature(), self.event["id"]))
        receipts = self.receiver.receipts()
        self.assertEqual(len(receipts), 1)
        self.assertEqual(receipts[0]["attemptCount"], 2)
        self.assertEqual(receipts[0]["payload"], self.event)

    def test_tampered_or_old_or_future_signatures_are_rejected(self):
        for body, signature in [(self.body + b" ", self.signature()), (self.body, "t=1700000000,v1=" + "0" * 64),
                                (self.body, self.signature(timestamp=self.now - 301)), (self.body, self.signature(timestamp=self.now + 301))]:
            with self.assertRaises(Rejected) as caught:
                self.receiver.accept(body, signature, self.event["id"])
            self.assertEqual(caught.exception.status, 401)
        self.assertEqual(self.receiver.receipts(), [])

    def test_duplicate_id_with_another_authenticated_body_is_rejected(self):
        self.receiver.accept(self.body, self.signature(), self.event["id"])
        changed = json.dumps(dict(self.event, data={"amountMinor": 999})).encode()
        with self.assertRaises(Rejected) as caught:
            self.receiver.accept(changed, self.signature(changed), self.event["id"])
        self.assertEqual(caught.exception.status, 409)
        self.assertEqual(self.receiver.receipts()[0]["attemptCount"], 1)

    def test_reopened_receiver_recognizes_previously_committed_event(self):
        self.receiver.accept(self.body, self.signature(), self.event["id"])
        replacement = Receiver(self.path, self.key, now=lambda: self.now)
        self.assertTrue(replacement.accept(self.body, self.signature(), self.event["id"]))
        self.assertEqual(replacement.receipts()[0]["attemptCount"], 2)

    def test_concurrent_duplicates_apply_one_receipt(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: self.receiver.accept(self.body, self.signature(), self.event["id"]), range(16)))
        self.assertEqual(results.count(False), 1)
        self.assertEqual(self.receiver.receipts()[0]["attemptCount"], 16)

    def test_http_boundary_verifies_signature_and_reports_duplicates(self):
        server = build_server(("127.0.0.1", 0), self.receiver)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            for duplicate in (False, True):
                req = urllib.request.Request(base + "/webhooks", data=self.body,
                    headers={"Webhook-Id": self.event["id"], "Webhook-Signature": self.signature(), "Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=3) as response:
                    self.assertEqual(response.status, 200)
                    self.assertEqual(json.load(response)["duplicate"], duplicate)
            with urllib.request.urlopen(base + "/events", timeout=3) as response:
                self.assertEqual(json.load(response)[0]["attemptCount"], 2)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()

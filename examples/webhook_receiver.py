"""Local webhook receiver: verify exact bytes, then commit receipt before replying.

For a real merchant, business changes must share the receipt's transaction.
The demo's business effect is one durable SQLite receipt per event ID.
"""
import hashlib
import hmac
import json
import os
import re
import sqlite3
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Rejected(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class Receiver:
    def __init__(self, path, secret, now=time.time):
        if not 32 <= len(secret) <= 256 or any(ord(c) < 33 or ord(c) > 126 for c in secret):
            raise ValueError("Configure a printable 32..256 character webhook secret")
        self.path, self.secret, self.now = Path(path), secret.encode(), now
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(self.path, timeout=5) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS receipts (
                event_id TEXT PRIMARY KEY, event_type TEXT NOT NULL, body_hash TEXT NOT NULL,
                payload TEXT NOT NULL, attempt_count INTEGER NOT NULL, received_at TEXT NOT NULL)""")

    def accept(self, body, signature, event_header):
        if len(body) > 65536:
            raise Rejected(413, "Payload too large")
        match = re.fullmatch(r"t=([0-9]{1,12}),v1=([0-9a-f]{64})", signature or "")
        if not match:
            raise Rejected(401, "Invalid signature")
        timestamp = int(match.group(1))
        expected = hmac.new(self.secret, str(timestamp).encode() + b"." + body, hashlib.sha256).hexdigest()
        if abs(self.now() - timestamp) > 300 or not hmac.compare_digest(expected, match.group(2)):
            raise Rejected(401, "Invalid or expired signature")
        try:
            event = json.loads(body)
            event_id = event["id"]
            if str(uuid.UUID(event_id)) != event_id or event_id != event_header:
                raise ValueError("Event identity mismatch")
            if event["type"] not in ("payment.completed", "payment.rejected", "refund.completed", "refund.rejected"):
                raise ValueError("Unknown event type")
            if type(event.get("schemaVersion")) is not int or event["schemaVersion"] != 1 or not isinstance(event.get("data"), dict):
                raise ValueError("Unknown schema")
        except (ValueError, TypeError, KeyError, AttributeError):
            raise Rejected(400, "Invalid event") from None
        digest = hashlib.sha256(body).hexdigest()
        with sqlite3.connect(self.path, timeout=5) as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT body_hash FROM receipts WHERE event_id=?", (event_id,)).fetchone()
            if existing:
                if existing[0] != digest:
                    raise Rejected(409, "Event ID already has another payload")
                db.execute("UPDATE receipts SET attempt_count=attempt_count+1 WHERE event_id=?", (event_id,))
            else:
                db.execute("INSERT INTO receipts VALUES (?,?,?,?,1,?)", (event_id, event["type"], digest, body.decode("utf-8"),
                    datetime.fromtimestamp(self.now(), timezone.utc).isoformat()))
        # SQLite committed before the caller sends the successful HTTP acknowledgment.
        return existing is not None

    def receipts(self):
        with sqlite3.connect(self.path, timeout=5) as db:
            rows = db.execute("SELECT event_id,event_type,payload,attempt_count FROM receipts ORDER BY received_at DESC,event_id DESC LIMIT 100").fetchall()
        return [{"eventId": row[0], "type": row[1], "payload": json.loads(row[2]), "attemptCount": row[3]} for row in rows]


def build_server(address, receiver):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, body):
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path == "/health":
                self.reply(200, {"status": "ok"})
            elif self.path == "/events":
                self.reply(200, receiver.receipts())
            else:
                self.reply(404, {"error": "Not found"})

        def do_POST(self):
            if self.path != "/webhooks":
                self.reply(404, {"error": "Not found"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 1 or length > 65536:
                    raise Rejected(413, "Invalid payload length")
                duplicate = receiver.accept(self.rfile.read(length), self.headers.get("Webhook-Signature"), self.headers.get("Webhook-Id"))
                self.reply(200, {"accepted": True, "duplicate": duplicate})
            except Rejected as error:
                self.reply(error.status, {"error": error.message})
            except ValueError:
                self.reply(400, {"error": "Invalid payload length"})

        def log_message(self, format, *args):
            pass  # Avoid logging payloads, keys, or request details in the demonstration.

    return ThreadingHTTPServer(address, Handler)


if __name__ == "__main__":
    receiver = Receiver(os.environ.get("WEBHOOK_DB", "/data/receiver.sqlite"), os.environ.get("WEBHOOK_DEMO_SECRET", ""))
    build_server(("0.0.0.0", int(os.environ.get("WEBHOOK_RECEIVER_PORT", "8081"))), receiver).serve_forever()

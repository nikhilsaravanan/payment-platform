# Payment Platform

A simulated payment platform for studying transaction correctness, concurrency,
and recovery. Java 21 / Spring Boot 3.5, REST, gRPC, and PostgreSQL 17.
No real money is moved.

The platform creates accounts, funds them with simulated money, and processes
idempotent payments through a balanced immutable ledger. Account locks prevent
distinct concurrent payments from spending the same funds. Durable posting decisions
preserve both successful and rejected outcomes across retries and lost responses.
Refunds, merchant webhooks, and reconciliation are [later milestones](docs/roadmap.md).

## Run locally

Install Docker with Compose. Host Java and Maven are optional for running the stack.

```sh
docker compose up --build -d --wait
python3 scripts/smoke.py
# Exercise a ledger outage and a payment-service restart as well:
python3 scripts/smoke.py --recovery
```

The API is at `http://localhost:8080`. Override the host port with
`PAYMENT_PORT=8081 docker compose up --build -d --wait`; pass
`--base-url http://localhost:8081` to the smoke script. PostgreSQL and gRPC are
internal to the Compose network. Services run as a non-root user. Compose health
checks verify open service ports; `/actuator/health` additionally checks the payment
database, but deliberately does not require the ledger to be available for acceptance.

```sh
curl -i http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"alice","currency":"USD"}'
curl -i http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"merchant","currency":"USD"}'
curl -i http://localhost:8080/funding \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-funding-001' \
  -d '{"accountId":"alice","amountMinor":10000,"currency":"USD"}'

curl -i http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-payment-001' \
  -d '{"sourceAccount":"alice","destinationAccount":"merchant","amountMinor":2500,"currency":"USD"}'

curl http://localhost:8080/payments/REPLACE_WITH_RETURNED_UUID
curl http://localhost:8080/accounts/alice
```

`amountMinor: 2500` means USD 25.00. The API accepts USD only and integer amounts
from 1 through 1,000,000,000,000 minor units. Account IDs must be nonblank, distinct,
and at most 100 characters. Names are case-sensitive and preserved as supplied.
The required `Idempotency-Key` supports up to 200 printable ASCII characters;
use a UUID or another opaque token without surrounding whitespace. Keys are global
in this unauthenticated sandbox and retained indefinitely. Funding and payments
use separate key namespaces. The internal ID `__simulation_issuer__` is reserved.

| Request | Response |
| --- | --- |
| `POST /accounts` | `201` new or `200` for the same account ID; starts at zero |
| `GET /accounts/{accountId}` | `200` with exact integer `balanceMinor`, or `404 ACCOUNT_NOT_FOUND` |
| `GET /accounts?accountId=...` | Equivalent lookup supporting IDs containing slashes |
| `POST /funding` with a new key | `201`, immutable funding receipt |
| Funding replay / changed fields | `200` same receipt / `409 IDEMPOTENCY_CONFLICT` |
| Funding a missing account | `404 ACCOUNT_NOT_FOUND`; that key retains its rejected outcome |
| New `POST /payments` | `202`, resource and `Location` header |
| Same key and same validated fields | `200`, same payment ID and current status |
| Same key with different fields | `409 IDEMPOTENCY_CONFLICT` |
| `GET /payments/{id}` | `200`, or `404 PAYMENT_NOT_FOUND` |
| Invalid fields, JSON, key, or UUID | `400 INVALID_REQUEST` |
| Database unavailable | `503 SERVICE_UNAVAILABLE` |
| Ledger unavailable for account/funding calls | `503 LEDGER_UNAVAILABLE`; retry the same operation/key |

```json
{
  "id": "594a5ce0-7c7b-4fe0-9ac1-932cf176725f",
  "sourceAccount": "alice",
  "destinationAccount": "merchant",
  "amountMinor": 2500,
  "currency": "USD",
  "status": "PENDING",
  "createdAt": "2026-11-09T05:00:00Z",
  "completedAt": null,
  "rejectedAt": null,
  "rejectionReason": null
}
```

Creation acknowledges durable acceptance. Poll until `status` is `COMPLETED` or
`REJECTED`. Rejections include `rejectedAt` and `rejectionReason`:
`INSUFFICIENT_FUNDS` or `ACCOUNT_NOT_FOUND`. Both are terminal; later funding or
account creation cannot change a rejected payment. A new attempt needs a new key.
Replays return the current resource, so a pending response can change as it resolves.

Funding is synchronous and idempotent: a timeout may occur after it commits, so
retry with the original key. It is an unrestricted simulation faucet, not a deposit
integration or proof that external money was received. Account/funding endpoints
require the ledger; payment acceptance can continue during a ledger outage.

## Design

```mermaid
flowchart LR
    Client -->|REST create/get| Payment[Payment service]
    Payment --> PDB[(Payment database)]
    Payment -->|gRPC account/funding| Ledger[Ledger service]
    Worker[Scheduled worker] -->|claim pending row| PDB
    Worker -->|gRPC SubmitTransfer| Ledger
    Ledger --> LDB[(Ledger database)]
    Worker -->|persist terminal outcome| PDB
```

Each service owns a separate database and role. A single local PostgreSQL container
hosts both. Flyway applies migrations on startup in service-specific locations.
The protobuf contract lives in `ledger-contract`; it exposes account/funding RPCs
and typed `SubmitTransfer` outcomes. Legacy `PostTransfer`/`GetTransfer` remain
available; the former reports business rejection with `FAILED_PRECONDITION`.
gRPC uses plaintext inside the local network; a production deployment
would need authentication and transport security.

**Acceptance:** a unique PostgreSQL constraint arbitrates concurrent idempotency
keys. `INSERT ... ON CONFLICT DO NOTHING` followed by a separate READ COMMITTED
query sees the winning request and compares its fields. No process-local lock or
in-memory idempotency cache is involved.

**Processing:** the committed PENDING row is also the durable work queue. A worker
claims one due row with `FOR UPDATE SKIP LOCKED`, calls gRPC with a two-second
deadline, and commits completion or terminal rejection. Each batch handles at most ten rows, with a
250 ms delay between batches. The default single scheduler uses one of ten database
connections, leaving capacity for the API. Multiple service instances can claim
different rows. Holding the row lock during the bounded RPC is a deliberate MVP
tradeoff; higher throughput could justify leases or a broker.

**Ledger:** one transaction inserts a unique journal keyed by payment ID and two
entries: source DEBIT and destination CREDIT, each for the same positive amount and
currency. Deferred database constraints reject missing, mismatched, or unbalanced
entries at commit. Update/delete triggers protect posted records from application
mutations. These are debit/credit recording conventions, not a full bank chart of
accounts. Database administrators can still alter schema or bypass protections.

**Recovery:** delivery is at least once; ledger effects occur once per payment ID.
This does not claim exactly-once delivery or a distributed transaction.

**Balances and concurrency:** the ledger owns provisioned USD accounts. Balances
are exact sums of immutable credits minus debits, using PostgreSQL numeric and Java
BigInteger; the gRPC representation is an integer string and REST returns an integer.
No mutable balance cache can drift from the journal. Funding records an issuer
debit and customer credit; only the internal simulation issuer may create new funds.
All funding and transfer paths lock both account rows in the same bytewise ID order
before checking funds and inserting entries. A separate READ COMMITTED balance query
then sees entries from any transaction that just released those locks. This follows
[PostgreSQL's lock-ordering guidance](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-DEADLOCKS).
An index supports per-account balance aggregation; its cost grows with account history.
This is a correctness-first implementation, with no throughput benchmark claimed.

**Posting decisions:** a unique operation ID stores immutable request fields and
the final outcome. The decision and successful journal/entries commit atomically;
deferred constraints reject unfinished decisions, successful decisions without a
matching journal, and rejected decisions with a journal. Replaying a rejection
returns that same rejection even if funds or accounts have since become available.
Unknown or mismatched RPC results remain pending for investigation.

| Failure | Recovery |
| --- | --- |
| Payment service stops after accepting | Restarted worker reads the durable PENDING row |
| Ledger fails between entry inserts | Its transaction rolls back both journal and entries |
| Ledger commits but its response is lost | Retry finds and returns the same journal |
| Rejection commits but its response is lost | Retry returns the same rejection, even after funding |
| Local completion write fails after ledger commits | Local transaction rolls back; retry reuses the journal |
| Ledger remains unavailable | Payment remains PENDING; persisted retry backoff grows from 1 to at most 60 seconds |

Retries have no maximum count in this increment. Internal contract errors such as
changed fields under an existing ledger ID are logged as errors and retained pending
for investigation. They are not mislabeled as a failed transfer after an ambiguous
outcome. `attempt_count`, `next_attempt_at`, and `last_error` are stored in the
payment database. No operator dashboard or alerting is implemented yet.

## Test and inspect

With Java 21, Maven 3.9+, and a running Docker daemon:

```sh
mvn -B -ntp verify
```

Testcontainers starts actual PostgreSQL instances. Tests cover 24 concurrent matching
and mismatching REST requests, concurrent ledger posting, validation, database
immutability and balance constraints, second-insert rollback, competing workers,
ledger outages, lost success/rejection/funding responses, and a reopened payment
database pool recovering durable work. A limited-balance test races 24 distinct
transfers: ten spend the available 5,000 minor units and fourteen are rejected.
Opposing transfers test lock ordering; V1 upgrade tests preserve legacy debt and
idempotency. Failure injection is confined to test fixtures.
The Compose smoke test checks funding, balances, terminal rejection, eight distinct
HTTP payments sharing a limited source, and a real payment-process restart during
a ledger outage. GitHub Actions runs the suites and recovery smoke test.

Inspect ledger records without exposing a PostgreSQL host port:

```sh
docker compose exec postgres psql -U ledger -d ledger \
  -c 'SELECT * FROM journals ORDER BY created_at DESC LIMIT 10'
docker compose exec postgres psql -U ledger -d ledger \
  -c 'SELECT * FROM entries ORDER BY payment_id, side'
docker compose logs -f payment ledger
```

## Upgrade from the recording-only MVP

V2 migrations preserve existing journals and backfill accounts and successful
decisions. Legacy balances remain exactly as recorded, including negative balances
that v0.1 permitted. No automatic grants or synthetic entries erase that debt;
fund an indebted account enough to cover its debt before it can spend again.
Pending old payments now undergo the new account/funds checks and may be rejected.
Only IDs appearing in old posted entries are backfilled automatically; provision
other accounts explicitly. Migration stops with a clear error if old history used
the now-reserved issuer ID, rather than silently reinterpreting it.

For newly created accounts, spending cannot make the balance negative. Transfers
and funding always preserve a zero net sum across all ledger entries, including
the issuer. Funded-account changes do not implement refunds or spend reservations.

`docker compose down` stops services and retains records. **Destructive reset:**
`docker compose down -v` removes the project's database volume and all simulated
payments. The SQL initialization runs only when that volume is first created.
The checked-in passwords are local simulation defaults. There is no authentication,
authorization, rate limiting, or real processor integration.

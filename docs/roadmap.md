# Delivery milestones

Each feature ships with tests and a focused commit. Working increments accumulate
on top of the MVP rather than replacing it with a larger rewrite.

| Milestone | Behavior | Evidence required |
| --- | --- | --- |
| 0.1 Payment and ledger MVP | Create/get simulated payments, idempotency, balanced postings, durable retries | Concurrent duplicate requests and recovery after lost responses and restarts |
| 0.2 Funded accounts | Create simulated accounts, explicit balanced funding, retrieve balances, persist final business rejections | Concurrent distinct transfers cannot spend the same funds; deterministic account locking; rejection replay after later funding |
| 0.3 Refunds and history | Durable idempotent full/partial refunds and cursor-paginated posted account history | Concurrent refunds cannot exceed the original; linked reversals, stable rejection replay and restart recovery |
| 0.4 Merchant webhooks | Persist notifications, sign payloads, retry deliveries | Receiver outage recovery, duplicate recognition, and bounded delivery attempts |
| Reconciliation | Import simulated processor records and compare against internal state | Missing, duplicate, amount and currency mismatches produce explainable findings |

Version 0.1 permitted negative balances while recording transfers. Version 0.2
preserves legacy history and debt, requires provisioned/funded accounts for new
spending, and separates terminal business rejection from ambiguous transport
failures. Version 0.3 adds explicit-amount refunds and posted transaction history.
Version 0.4 adds signed webhooks, bounded leased retries, durable attempt audit
and a receiver with persistent duplicate recognition. Reconciliation is next.

Potential later operational work: authentication and merchant isolation, metrics
and alerts for old pending work, load testing, retention, and higher-throughput money workers.
These are not implemented guarantees of the current sandbox.

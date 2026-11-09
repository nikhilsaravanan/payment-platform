# Delivery milestones

Each feature ships with tests and a focused commit. Working increments accumulate
on top of the MVP rather than replacing it with a larger rewrite.

| Milestone | Behavior | Evidence required |
| --- | --- | --- |
| 0.1 Payment and ledger MVP | Create/get simulated payments, idempotency, balanced postings, durable retries | Concurrent duplicate requests and recovery after lost responses and restarts |
| Funded accounts | Create simulated accounts, explicit balanced funding, retrieve balances, reject insufficient funds | Concurrent distinct transfers cannot spend the same funds; deterministic account locking prevents deadlocks |
| Refunds and history | Idempotent full/partial refunds and paginated merchant transaction history | Concurrent refunds cannot exceed the original payment; reversal entries preserve audit history |
| Merchant webhooks | Persist notifications, sign payloads, retry deliveries | Receiver outage recovery, duplicate recognition, and bounded delivery attempts |
| Reconciliation | Import simulated processor records and compare against internal state | Missing, duplicate, amount and currency mismatches produce explainable findings |

The MVP permits negative account balances because it only records transfers.
The funded-account milestone changes that contract explicitly and will introduce
terminal business rejection separately from ambiguous transport failures.

Potential later operational work: authentication and merchant isolation, metrics
and alerts for old pending work, load testing, retention, and a lease-based worker.
These are not implemented guarantees of the current sandbox.

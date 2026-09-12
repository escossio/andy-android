# Sync boundary

`sync/` owns durable outbox, pull/push synchronization, freshness, retry/idempotency coordination, and offline reconciliation. Stale/offline data must never be promoted to current server authority.

# rtp-proxy-ADR-009 — `GenericSqlNetworkStateBinding`

**Status:** Proposed
**Date:** 2026-05-13
**Refines:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md), [rtp-proxy-ADR-007](rtp-proxy-ADR-007-postgres-binding.md)

## Context

Phase 4 of `MULTI_SERVER_PLAN.md` adds a generic SQL binding as the fallback for operators on MySQL/MariaDB/H2/SQLite who cannot run Redis or Postgres. Generic-SQL lacks Postgres's `LISTEN/NOTIFY` and `SKIP LOCKED` semantics, so the binding must work around them with **polling** and **portable atomic primitives** (REQ-RTP-PROXY-COMMON-007).

This binding is the **last resort**; it is the slowest and the least scalable, and `docs/admin/proxies/TRANSPORTS.md` will document it as such.

## Decision

`GenericSqlNetworkStateBinding implements NetworkTransport`, package `io.github.dailystruggle.rtp.proxy.common.transport.sql`. Built on HikariCP, reusing `AbstractSQLDatabaseAccessor`'s dialect detection (`MYSQL`, `MARIADB`, `H2`, `SQLITE`).

### Schema

Same logical schema as ADR-007, with portable type substitutions:

- `TEXT[]` → JSON-encoded `TEXT` (parsed by the binding).
- `BYTEA` → `VARBINARY(64)` or dialect equivalent.
- `JSONB` → `TEXT` with application-side JSON.
- `BIGSERIAL` → `BIGINT AUTO_INCREMENT` (MySQL/MariaDB), `IDENTITY` (H2), `INTEGER PRIMARY KEY AUTOINCREMENT` (SQLite).

A small dialect adapter under `transport.sql.dialect` selects the right DDL at boot. Migrations bump `schemaVersion` and refuse to silently re-shape an existing table (REQ-RTP-NET-009).

### Atomic Claim

Conditional `UPDATE` is portable and remains the primitive:

```sql
UPDATE rtp_reservation_token
   SET state = 'CLAIMED', claimed_by_proxy = ?
 WHERE token_id = ?
   AND state = 'PENDING'
   AND expires_at_ms > ?;
```

Row-count = winner/loser signal. No vendor-specific lock hints; transaction isolation defaults are sufficient for `READ_COMMITTED` (which MySQL/InnoDB and MariaDB use by default; H2/SQLite are serialised).

### Wait Queue Drain (no `SKIP LOCKED`)

Replaced with **optimistic claim**:

```sql
-- 1. SELECT the head row's enqueue_seq.
SELECT enqueue_seq, player_id FROM rtp_wait_queue ORDER BY enqueue_seq LIMIT 1;
-- 2. DELETE conditionally; row-count = winner.
DELETE FROM rtp_wait_queue WHERE enqueue_seq = ?;
```

Contention is bounded by the number of proxies; even under stampede the loser simply retries the SELECT on the next interval. No correctness issue, just throughput.

### Heartbeat Fan-Out via Polling

- No `LISTEN/NOTIFY`. Each proxy holds a `Subscription` that polls `rtp_backend_state` on a `heartbeat.intervalMs / 2` cadence (default 500ms).
- Each poll selects rows whose `last_seen_epoch_ms > lastPollEpochMs` and dispatches to subscribers.
- This adds a fixed write-amplification of `N_proxies * (writes/sec) / pollInterval` on the read side. For 2 proxies × 16 backends × 1Hz heartbeat × 500ms polling, that's ~64 row reads/sec — trivial.

### Orphan Reanimation

Same conditional `UPDATE` as ADR-007, gated by an **advisory lock surrogate**: an explicit row in `rtp_config_version` keyed `sweep_lock` with `value = <proxyId>:<expiresAtMs>`. A proxy claims the lock via:

```sql
UPDATE rtp_config_version SET v = ?
 WHERE k = 'sweep_lock' AND (v LIKE '%:0' OR substr(v, instr(v,':')+1) < ?);
```

Row-count = lock winner. Locks expire on a 4.5s TTL (slightly under the 5s sweep cadence) so a crashed proxy releases its sweep within one cycle.

### Performance Envelope

- Heartbeat write rate: same as Postgres (1/s).
- Token claim latency target: < 20ms median on co-located MySQL/MariaDB; SQLite is single-writer and is only supported in `developerMode: true` profiles.
- Wait-queue drain throughput: ~50 ops/s/proxy under contention; sufficient for the cross-network fairness use case.

### SQLite Caveat

SQLite is **dev-only**. The binding emits a `WARNING` when `dialect = SQLITE` and refuses to enable outside `developerMode: true`. Rationale: SQLite's single-writer model serialises every claim across the whole network, which defeats the multi-proxy concurrency goal (REQ-RTP-NET-014).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Use MySQL's `GET_LOCK` for the sweep lock | Vendor-specific; defeats "generic" by definition. |
| Polling-only heartbeats with no row-version column | Forces a full-table scan; the `last_seen_epoch_ms` filter is portable and cheap. |
| Drop SQLite entirely | The single-server `AbstractSQLDatabaseAccessor` already supports it; allowing it under a developer flag preserves continuity for plugin integration tests. |
| Require explicit triggers (`CREATE TRIGGER … AFTER INSERT`) for fan-out | Dialect-specific syntax; harder to install idempotently. |

## Consequences

- **Positive:** any HikariCP-supported dialect works; operators with existing MySQL/MariaDB get network mode without new infra.
- **Negative:** polling is wasteful at high heartbeat frequencies; recommend Postgres or Redis for ≥ 8-backend deployments. Documentation in `docs/admin/proxies/TRANSPORTS.md` (Phase 4) sets explicit guidance.

## References

- ADR-036; `MULTI_SERVER_PLAN.md` Phase 4.
- `REQ-RTP-NET-007`, `-009`, `-011`, `-012`, `-013`, `-014`.
- `REQ-RTP-PROXY-COMMON-007`.

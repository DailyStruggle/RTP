# rtp-proxy-ADR-007 — `PostgresNetworkStateBinding`

**Status:** Proposed
**Date:** 2026-05-13
**Refines:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md)

## Context

Phase 3 of `MULTI_SERVER_PLAN.md` adds Postgres as a transport. Postgres is the **preferred** option for operators who already run it as the persistence backend (D3: network-state lives adjacent to `AbstractSQLDatabaseAccessor`) and who do not want to operate Redis alongside it. Postgres has two features that make it competitive with Redis for this workload:

- `LISTEN/NOTIFY` for push-based heartbeat fan-out (no polling treadmill).
- `SELECT … FOR UPDATE SKIP LOCKED` for atomic, contention-free reservation claims across N proxies.

## Decision

`PostgresNetworkStateBinding implements NetworkTransport`, package `io.github.dailystruggle.rtp.proxy.common.transport.postgres`. Connects via HikariCP-backed `DataSource`, reusing the pool sizing conventions from `AbstractSQLDatabaseAccessor`.

### Schema (created idempotently on enable)

```sql
CREATE TABLE IF NOT EXISTS rtp_backend_state (
  server_id          TEXT PRIMARY KEY,
  schema_version     INT  NOT NULL,
  plugin_state       TEXT NOT NULL,
  accepting_requests BOOLEAN NOT NULL,
  regions_available  TEXT[] NOT NULL,
  worlds_loaded      TEXT[] NOT NULL,
  player_count       INT,
  soft_cap           INT,
  tps_1m             DOUBLE PRECISION,
  mspt               DOUBLE PRECISION,
  queue_depth        INT,
  pending_teleports  INT,
  avg_pipeline_ms    DOUBLE PRECISION,
  chunk_load_backlog INT,
  memory_tracker_entries INT,
  heap_used          BIGINT,
  heap_max           BIGINT,
  database_latency_ms DOUBLE PRECISION,
  last_seen_epoch_ms BIGINT NOT NULL,
  hmac               BYTEA NOT NULL
);

CREATE TABLE IF NOT EXISTS rtp_proxy_state (
  proxy_id           TEXT PRIMARY KEY,
  schema_version     INT  NOT NULL,
  player_count       INT  NOT NULL,
  connected_backends TEXT[] NOT NULL,
  pending_tokens     INT  NOT NULL,
  last_seen_epoch_ms BIGINT NOT NULL,
  hmac               BYTEA NOT NULL
);

CREATE TABLE IF NOT EXISTS rtp_reservation_token (
  token_id         UUID PRIMARY KEY,
  state            TEXT NOT NULL CHECK (state IN ('PENDING','CLAIMED','CONSUMED','EXPIRED')),
  server_id        TEXT NOT NULL,
  player_id        UUID NOT NULL,
  location_json    JSONB NOT NULL,
  schema_version   INT  NOT NULL,
  hmac             BYTEA NOT NULL,
  created_at_ms    BIGINT NOT NULL,
  expires_at_ms    BIGINT NOT NULL,
  claimed_by_proxy TEXT
);

CREATE INDEX IF NOT EXISTS rtp_tok_state_idx ON rtp_reservation_token(state, expires_at_ms);

CREATE TABLE IF NOT EXISTS rtp_wait_queue (
  enqueue_seq BIGSERIAL PRIMARY KEY,
  player_id   UUID UNIQUE NOT NULL,
  enqueued_at_ms BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS rtp_config_version (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);
```

### Atomic Claim (`PENDING → CLAIMED`)

```sql
UPDATE rtp_reservation_token
   SET state = 'CLAIMED', claimed_by_proxy = $1
 WHERE token_id = $2
   AND state = 'PENDING'
   AND expires_at_ms > $3;
```

`UPDATE` row-count is the winner/loser signal (REQ-RTP-PROXY-004). For batch claim flows (single-player + single-token), `SELECT … FOR UPDATE SKIP LOCKED` is **not** needed; the equality predicate is already atomic. The skip-locked variant is reserved for the **wait queue drain**:

```sql
DELETE FROM rtp_wait_queue
 WHERE enqueue_seq = (
   SELECT enqueue_seq FROM rtp_wait_queue
    ORDER BY enqueue_seq
    FOR UPDATE SKIP LOCKED
    LIMIT 1
 )
 RETURNING player_id;
```

This lets multiple proxies drain the queue concurrently without serialising on a single row lock.

### Heartbeat Fan-Out via `LISTEN/NOTIFY`

- Backends/proxies issue `NOTIFY rtp_hb, '<serverId>|<lastSeenEpochMs>'` after their UPSERT.
- Proxies hold a **single dedicated listener connection** (HikariCP exempts it from pooling) and process `PGNotification` payloads on a Lettuce-equivalent async hop.
- Payload kept small (no metric body); subscribers `SELECT` the changed row.
- Reconnect logic: on connection drop, the listener thread reissues `LISTEN rtp_hb` and triggers a full snapshot read to catch missed notifications.

### Orphan Reanimation

A periodic task per proxy issues:

```sql
UPDATE rtp_reservation_token
   SET state = 'PENDING', claimed_by_proxy = NULL
 WHERE state = 'CLAIMED'
   AND claimed_by_proxy IN (
     SELECT proxy_id FROM rtp_proxy_state
      WHERE last_seen_epoch_ms < $1
   );
```

with `$1 = now - claimReanimateMs`. Uses a per-task advisory lock (`pg_try_advisory_lock`) so only one proxy runs the sweep at a time.

### Performance Envelope

- Connection pool: `transport.poolSize` default 4 (consistent with single-server accessor defaults). One **additional** connection reserved for `LISTEN`.
- Heartbeat write rate per host: 1/s default → ≤ 16 INSERTs+NOTIFYs/s for 16-backend deployments; well within Postgres write bandwidth.
- Token claim latency target: < 5ms median on co-located Postgres. Acceptable degradation: < 30ms p99 under 100 concurrent claims (Phase 3 acceptance benchmark).

### Vacuum & Retention

- `rtp_reservation_token` rows in `CONSUMED` or `EXPIRED` state for > 1 hour are deleted by a periodic proxy-side task (one proxy at a time via advisory lock).
- `rtp_backend_state` / `rtp_proxy_state` rows older than `heartbeat.staleAfterMs * 10` are deleted on a 5-minute cadence (covers operator-renamed servers).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| MySQL with the same schema | No native `LISTEN/NOTIFY`; falls into the generic-SQL polling binding (ADR-009). |
| Postgres logical replication for heartbeat fan-out | Heavy operational dependency for an event stream Postgres already supports natively. |
| Connection-per-listener | Postgres connection cost is non-trivial; one shared listener with subscriber multiplexing keeps the pool small. |
| Embed in the existing `AbstractSQLDatabaseAccessor` Postgres dialect | Mixes single-server and network-state concerns; D3 calls for a network-state member, not a merged schema. |

## Consequences

- **Positive:** operators with Postgres get a zero-new-infra deployment; durable by default; supports D2 (durable reservations survive proxy restart).
- **Negative:** higher latency than Redis (typically 2–5x); `LISTEN/NOTIFY` reconnect semantics need explicit handling; schema migrations require an ADR bump on `schemaVersion`.

## References

- ADR-036; `MULTI_SERVER_PLAN.md` Phase 3; D2, D3.
- `REQ-RTP-NET-007`, `-009`, `-011`, `-012`, `-013`, `-014`.
- `REQ-RTP-PROXY-COMMON-007` (transport pluggability).

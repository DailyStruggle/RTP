# rtp-proxy-ADR-011 - `SqlNetworkStateBinding` (DB-as-Bus, Phase 2b Default)

**Status:** Accepted (2026-05-18)
**Date:** 2026-05-18 (originally Proposed; accepted same day with Phase 2e-SQL slice landing)
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md), [rtp-proxy-ADR-005](rtp-proxy-ADR-005-redis-binding.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md), [rtp-proxy-ADR-010](rtp-proxy-ADR-010-security-hardening.md)

## Context

`MULTI_SERVER_PLAN.md` and ADR-005 frame Redis as the **preferred** Phase 2 transport, with Postgres / generic-SQL bindings listed as parallel options (ADR-007, ADR-009). The original Phase 2 ordering implied Phase 2b shipped against `InMemoryNetworkStateBinding` and Phase 2e turned on Redis.

Two observations reorder this:

1. The project already has a mature `AbstractSQLDatabaseAccessor` family (H2 / SQLite / MySQL / PostgreSQL) with HikariCP pooling. Most networks that run RTP across multiple backends already share a SQL database for region storage. Standing up an additional Redis instance just to enable network mode is a deployment tax those networks should not have to pay to ship Phase 2b.
2. The SPI surface in `rtp-proxy-common` (`NetworkTransport`, `ReservationClient`, heartbeats, tokens) was deliberately shaped to admit any binding. A SQL-backed binding is a peer of Redis-backed, not a degraded substitute.

Treating Redis as a *latency optimisation* rather than a transport gate keeps Phase 2b shippable without the Lettuce + Lua dependency chain, while preserving ADR-005's role as the high-performance binding for networks that want it.

## Decision

`SqlNetworkStateBinding implements NetworkTransport`, package `io.github.dailystruggle.rtp.proxy.common.transport.sql`. It is the **Phase 2b default** transport when `network.enabled: true`. ADR-005's Redis binding remains the **Phase 2e** opt-in for networks that need sub-poll-interval latency.

### Sketch table layout

The exact DDL is finalised when the binding lands in code; the shape below is a target for the implementation turn and is non-binding:

| Table | Purpose | Primary key |
|---|---|---|
| `rtp_network_backends` | One row per backend, mirrors `BackendHeartbeat` fields. UPSERTed every `heartbeat.intervalMs`. | `(serverId)` |
| `rtp_network_proxies` | One row per proxy, mirrors `ProxyHeartbeat`. UPSERTed every `heartbeat.intervalMs`. | `(proxyId)` |
| `rtp_network_tokens` | One row per reservation token, fields mirror the Redis HASH columns minus `hmac`/`schemaVersion` (HMAC stays on the wire per ADR-005 amendment 2026-05-18; SQL binding relies on DB connection auth + TLS instead). | `(tokenId)` |
| `rtp_network_wait_queue` | UUID FIFO of players awaiting a coordinate (REQ-RTP-NET-008). | `(seq) AUTOINCREMENT`, `playerId UNIQUE` |
| `rtp_network_control` | Single row: `schemaVersion`, `killSwitch`, `lastChangedEpochMs`. | singleton |

### SPI method mapping (1:1)

- `readSnapshot()` -> `SELECT * FROM rtp_network_backends; SELECT * FROM rtp_network_proxies; SELECT killSwitch FROM rtp_network_control;`
- `claim(serverId, playerId, ttl)` -> `INSERT INTO rtp_network_tokens (...) RETURNING tokenId;` then `UPDATE rtp_network_tokens SET state='CLAIMED', claimedBy=? WHERE tokenId=? AND state='PENDING' RETURNING tokenId` (atomic via `UPDATE ... WHERE`; portable across MySQL / Postgres / SQLite without vendor-specific syntax).
- `release(tokenId, reason)` -> `UPDATE rtp_network_tokens SET state='RELEASED', releaseReason=? WHERE tokenId=?`.
- `publishBackendHeartbeat(...)` / `publishProxyHeartbeat(...)` -> single-row UPSERT.
- `subscribeBackendHeartbeats(consumer)` -> background `ScheduledExecutorService` polling `rtp_network_backends` every `heartbeat.intervalMs / 2`, diffing against the last snapshot, invoking the consumer per changed row. Subscription cancel stops the poll task.
- `close()` -> stop the poll task, return any owned `PENDING` tokens to `RELEASED`, release HikariCP connections.

### Orphan reanimation

Equivalent to ADR-005 section Orphan Reanimation. A periodic sweeper on the proxy runs every `claimReanimateMs`:

```sql
UPDATE rtp_network_tokens
   SET state = 'PENDING', claimedBy = NULL, claimedAt = NULL
 WHERE state = 'CLAIMED'
   AND claimedAt + ? < NOW()
   AND claimedBy NOT IN (SELECT proxyId FROM rtp_network_proxies WHERE lastSeenEpochMs > ?)
```

Sweeper lock uses an advisory `SELECT ... FOR UPDATE SKIP LOCKED` on `rtp_network_control` so only one proxy sweeps at a time (Postgres / MySQL 8+); SQLite degrades to a best-effort retry on busy errors.

### HikariCP pool

The binding **shares** `AbstractSQLDatabaseAccessor`'s pool rather than owning its own. Network-mode traffic is additive to existing plugin DB traffic; documentation guidance bumps `maximumPoolSize` by +2 when network mode is enabled. Rationale: one less moving part, one less connection pool to monitor, and the same accessor type already serves H2 / SQLite / MySQL / Postgres uniformly.

## Engine support matrix

| Engine | Phase 2b support | Notes |
|---|---|---|
| PostgreSQL >= 13 | Recommended | `SELECT ... FOR UPDATE SKIP LOCKED`, `LISTEN/NOTIFY` (future optimisation), proven HikariCP integration. |
| MySQL >= 8.0 | Recommended | `SELECT ... FOR UPDATE SKIP LOCKED` since 8.0. |
| MariaDB >= 10.6 | Supported | Same locking primitives as MySQL 8. |
| H2 | Dev/test only | Embedded; not realistic for multi-backend networks. |
| SQLite | Dev/test only | Single-writer; works for 1-2-backend home setups but not production proxy-fronted networks. Documented as "dev/test only" in admin docs. |

## Config implications

`network.yml` gains `transport.type: sql` as the **default**:

```yaml
transport:
  type: sql                     # was: in-memory; redis is now opt-in
  url: ""                       # falls through to the existing rtp database accessor URL if blank
  poolSize: 4                   # advisory; binding shares AbstractSQLDatabaseAccessor's pool in v1
  connectTimeoutMs: 2000
  readTimeoutMs: 5000
```

When `transport.url` is blank the binding piggy-backs on the existing `database.url` from `config.yml`, so a network that already shares a SQL database for region storage gets network-mode functionality with zero new config.

## Replay resistance without HMAC

ADR-010's HMAC envelope is a wire-layer concern for Redis. The SQL binding leans on DB connection authentication + TLS for replay resistance; the replay-nonce / `schemaVersion` discussion in ADR-010 is **N/A** for this binding. Admin docs (`docs/admin/proxies/TRANSPORTS.md`) shall document this distinction explicitly so operators do not expect HMAC-grade wire protection from the SQL transport.

## Open questions

1. **Heartbeat polling at scale.** A 1s poll on `rtp_network_backends` from N backends is `N` SELECTs/sec network-wide. Acceptable for `N <= 32`; networks larger than that should consider Postgres `LISTEN/NOTIFY` (a follow-up ADR if it bites) or migrate to Redis.
2. **HikariCP pool exhaustion under burst.** If network mode dramatically increases concurrent queries, the +2 documentation bump may be insufficient. Mitigated by `connectTimeoutMs` (binding fails individual calls fast rather than blocking the proxy event loop).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Keep Redis as Phase 2b default | Forces every Phase 2b operator to stand up Redis even if they already run MySQL/Postgres; large deployment tax for a "network mode default-on" milestone. |
| Ship `InMemoryNetworkStateBinding` as Phase 2b default | Operationally useless: only valid for single-JVM tests. Cannot satisfy any cross-server scenario. |
| Build a separate `PostgresNetworkStateBinding` (per ADR-007) and a separate `MySqlNetworkStateBinding` | Doubles binding surface for negligible benefit; portable SQL covers both engines via HikariCP. ADR-007 / ADR-009 are folded into this binding and can be re-split later if engine-specific optimisations justify it. |

## Consequences

- **Positive:** Phase 2b ships against the most common existing operator footprint (shared SQL); zero new infra required; Redis becomes a documented latency upsell rather than a gate.
- **Negative:** Heartbeat fan-out is polling rather than pub/sub (30-200ms typical LAN latency vs sub-ms Redis). Documented as the trade-off for the simpler deployment.
- **Neutral:** ADR-005 unchanged; `RedisNetworkStateBinding` remains the recommended high-performance transport.

## References

- ADR-036 (umbrella); `MULTI_SERVER_PLAN.md` Phase 2.
- ADR-005 (`RedisNetworkStateBinding`); ADR-007 (Postgres binding, superseded by this); ADR-009 (generic SQL binding, superseded by this).
- ADR-010 (security hardening; HMAC is N/A for SQL binding per section Replay resistance).
- REQ-RTP-NET-007, -008, -009, -011, -012, -014.
- `docs/dev/scratch/PROPOSAL-sql-binding-first.md` rev 2.

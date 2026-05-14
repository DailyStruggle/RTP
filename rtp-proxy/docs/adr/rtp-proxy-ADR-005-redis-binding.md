# rtp-proxy-ADR-005 — `RedisNetworkStateBinding` (Lettuce)

**Status:** Proposed
**Date:** 2026-05-13
**Refines:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md)

## Context

`MULTI_SERVER_PLAN.md` names Redis as the **preferred** Phase 2 transport, with DragonflyDB and KeyDB covered by the same binding via URL-only configuration (RESP-compatible). Redis is preferred because:

- Sub-millisecond round trips on a colocated LAN.
- Native pub/sub for heartbeat fan-out (no polling).
- `EVAL`/Lua and `SET … NX EX` give atomic claim primitives without server-side schema.
- Lettuce is a well-maintained async client with `CompletionStage` integration.

## Decision

`RedisNetworkStateBinding implements NetworkTransport`, package `io.github.dailystruggle.rtp.proxy.common.transport.redis`. Built on **Lettuce 6.x** (async API only; no `RedisCommands` sync wrapper).

### Key Layout

| Concern | Key pattern | Type | TTL |
|---|---|---|---|
| Backend heartbeat | `rtp:backend:<serverId>` | HASH | `heartbeat.staleAfterMs * 3` |
| Proxy heartbeat | `rtp:proxy:<proxyId>` | HASH | `heartbeat.staleAfterMs * 3` |
| Heartbeat fan-out | `rtp:hb` | PUBSUB channel | — |
| Reservation token | `rtp:tok:<tokenId>` | HASH (fields: state, serverId, playerId, location, schemaVersion, hmac) | `reservation.ttlMs` |
| Token state index | `rtp:tok:state:<state>` | SET | aligned to token |
| Wait queue | `rtp:wait` | LIST (LPUSH/RPOP) | — |
| Config hash check | `rtp:config:hash` | STRING | — |

Namespace prefix `rtp:` is configurable via `transport.keyPrefix` (ADR-002 reserved key) for shared-instance deployments.

### Atomic Claim (`PENDING → CLAIMED`)

Implemented as a Lua script (`EVAL`) returning row-count semantics:

```
-- KEYS[1] = rtp:tok:<tokenId>
-- ARGV[1] = expected current state ("PENDING")
-- ARGV[2] = new state ("CLAIMED")
-- ARGV[3] = claimingProxyId
local s = redis.call('HGET', KEYS[1], 'state')
if s == ARGV[1] then
  redis.call('HSET', KEYS[1], 'state', ARGV[2], 'claimedBy', ARGV[3])
  return 1
end
return 0
```

The future returned by `claim` resolves to `1` (winner) or `0` (loser). Loser triggers the capped-retry chain (ADR-004).

### Orphan Reanimation (`claimReanimateMs`)

A periodic sweeper running on the proxy hops every `claimReanimateMs` (default 5s) and runs a second Lua script:

```
-- find tokens in CLAIMED whose claimingProxy heartbeat is stale, flip back to PENDING
```

The sweeper itself uses `SET rtp:sweep:lock <proxyId> NX PX 4500` so only one proxy sweeps at a time; loss of the lock is harmless idempotent retry on the next interval.

### Heartbeat Fan-Out

- Backend `publishBackendHeartbeat` → `HSET rtp:backend:<id> …` then `PUBLISH rtp:hb backend:<id>`.
- Subscribers `SUBSCRIBE rtp:hb`; on each message do a single `HGETALL` for the changed key.
- This trades one extra `HGETALL` per change for a tiny pub/sub payload (avoids stuffing the entire row into the channel; DragonflyDB has different pub/sub fairness).

### DragonflyDB / KeyDB Parity

The binding declares parity with:

- **DragonflyDB** ≥ 1.20 (confirmed: Lua, SUBSCRIBE, HSET, EXPIRE).
- **KeyDB** ≥ 6.3.x.

Parity is validated by **running the Phase 2 reservation regression suite** against a DragonflyDB container with **only the `transport.url` changed**. A failing parity test is a Phase 2 release blocker.

### Reconnect & Backpressure

- Lettuce's auto-reconnect is enabled with `ClientOptions.disconnectedBehavior = REJECT_COMMANDS`. Commands issued while disconnected complete exceptionally; dispatcher surfaces `Failed(reason=TRANSPORT_DOWN)` rather than blocking.
- Heartbeat publishes during disconnect are dropped (snapshot semantics — the next interval will publish anyway).
- Subscription resumes on reconnect with a fresh `SUBSCRIBE`; subscribers re-read their target keys to catch missed events.

### Security Hooks

- HMAC verification (ADR-010) wraps every payload that crosses the wire. The binding refuses payloads whose HMAC or `schemaVersion` is invalid (REQ-RTP-PROXY-007), audited under S-004.
- Shared-instance deployments (Redis used by other plugins) **rely on HMAC**, not key isolation — see ADR-010 for the kill-switch contract.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Jedis (sync) with thread pool | Forces blocking I/O on the proxy event loop, requires a custom pool layer; violates REQ-RTP-NET-007 spirit. |
| Redisson | Brings opinionated distributed-object abstractions we don't need; ~MB of transitive deps. |
| Native pub/sub only, no key polling | Pub/sub is best-effort during reconnects; the key layout above is the source of truth. |
| Redis Streams (`XADD`/`XREADGROUP`) for heartbeats | Better semantics, but adds consumer-group state we'd have to GC; pub/sub + snapshot HSET is sufficient. |

## Consequences

- **Positive:** lowest-latency option; satisfies the "DragonflyDB drop-in" promise; small binding (Lettuce only).
- **Negative:** Lua scripts add an opaque-to-operator step; mitigated by shipping the scripts in plaintext under `rtp-proxy-common/src/main/resources/redis/` and documenting them in `docs/admin/proxies/TRANSPORTS.md`.

## References

- ADR-036; `MULTI_SERVER_PLAN.md` Phase 2; storage section.
- `REQ-RTP-NET-007`, `-009`, `-011`, `-012`, `-014`.
- ADR-010 (security hardening).

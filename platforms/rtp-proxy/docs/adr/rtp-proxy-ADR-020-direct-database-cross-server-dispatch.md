# rtp-proxy-ADR-020 — Direct Database Cross-Server Dispatch Mode ("Direct DB Mode")

**Status:** Proposed
**Date:** 2026-10-02
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md), [rtp-proxy-ADR-014](rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md), [rtp-proxy-ADR-016](rtp-proxy-ADR-016-plugin-message-default-transport.md)
**Related:** [rtp-proxy-ADR-005](rtp-proxy-ADR-005-redis-binding.md), [rtp-proxy-ADR-011](rtp-proxy-ADR-011-sql-network-state-binding.md)
**Source Proposal:** `docs/dev/scratch/PROPOSAL-direct-database-cross-server-dispatch.md`

---

## Context

In L6 network mode ([rtp-proxy-ADR-014](rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md)), cross-server dispatch delegates request routing and reservation brokering to a proxy plugin (`rtp-proxy-velocity` or `rtp-proxy-bungee`). The backend enqueues an `EnrolmentEnvelope` into `NetworkRequestQueue` in the shared store, and a proxy worker thread (`TransportRequestTriggerSource`) dequeues it, evaluates `BackendSelector`, claims a `ReservationToken` on the target backend, and routes the player via `ServerPreConnectEvent`.

However, on networks where a shared database (MySQL, PostgreSQL) or Redis instance is active:
1. All heartbeat telemetry (`backend_state`), cluster health (`NetworkSnapshot`), and reservation records (`ReservationTokenTable`) already live in the central datastore.
2. The originating backend can evaluate `BackendSelector` and claim reservations in `NetworkTransport` directly without an intermediary proxy worker.
3. Modern proxies (Velocity, BungeeCord, Waterfall, Gate) support native player transfer commands (`Connect <server>`) over the standard `bungeecord:main` plugin messaging channel without requiring custom proxy plugins.

Requiring `rtp-proxy-*` JAR installation on proxies creates operational overhead and blocks networks that forbid proxy-layer plugins or use non-Java proxy implementations.

---

## Decision

We introduce **Direct Database Cross-Server Dispatch Mode ("Direct DB Mode")**:
1. **Direct Backend-to-Backend Coordination:** When a shared store (SQL/Redis) is available, the origin backend evaluates `BackendSelector` and reserves coordinates in `ReservationTokenTable` directly.
2. **Native Proxy Messaging:** Player transfer is initiated by emitting a standard BungeeCord `Connect <destinationServer>` message on the `bungeecord:main` plugin messaging channel over the player connection. No RTP code runs on the proxy.
3. **Arrival Redemption:** Destination backend's `JoinTriggerSource` listens on `PlayerJoinEvent`, looks up the pending reservation token directly from the database, redeems it, and executes teleportation immediately.
4. **Configuration Control:** A new setting `routing.dispatchMode` in `network.yml` selects between `direct-db`, `proxy-broker`, and `auto`.

---

## Architecture & Lifecycle

### Request Sequence

```
1. Player runs /rtp on Backend A
   └── NetworkRouter evaluates mode and decides cross-server dispatch
2. Backend A queries NetworkTransport.readSnapshot() (backed by SQL/Redis)
   └── BackendSelector.choose(request, snapshot) selects Destination Backend B
3. Backend A calls NetworkTransport.claim(targetServerId=B, playerId, ttl)
   └── Token persisted in ReservationTokenTable with state CLAIMED
4. Backend A sends plugin message to proxy:
   └── Channel: bungeecord:main
   └── Subchannel: Connect
   └── Argument: Destination Backend B serverId
5. Proxy transfers player connection to Backend B
6. Player connects to Backend B (PlayerJoinEvent)
   └── JoinTriggerSource checks NetworkTransport.findReservation(playerId)
   └── Token found in state CLAIMED for this server
   └── JoinTriggerSource calls NetworkTransport.redeem(tokenId, serverId=B)
   └── RegionQueueManager.acceptRedeemedReservation pins coordinate to personal queue
   └── Player immediately teleports to the reserved coordinate
```

### Distributed Token Reaping & Edge Cases

- **Token Expiry Without Proxy:** In proxy-brokered mode, `ReservationTokenReaper` runs on the proxy. In Direct DB Mode, `ReservationTokenReaper` runs locally on each backend as a periodic async task (default interval 30s) calling `transport.reapExpired(now)`. The SQL/Redis implementations use atomic queries with state guards (`WHERE state = 'CLAIMED' AND expires_at < ?`), making multi-backend concurrent reaping completely safe and idempotent.
- **Mid-Transfer Disconnect:** If the player disconnects before arriving on Backend B, Backend B never triggers `redeem`. The token expires after its TTL (default 60s) and is reclaimed by `ReservationTokenReaper`. If the player quits while still on Backend A, Backend A's quit hook cleans up via `transport.release(..., PLAYER_DISCONNECTED)`.
- **Target Server Offline or Full:** If Backend B is offline or full, the proxy refuses the `Connect` transfer and retains the player on Backend A. The unredeemed token expires safely without causing coordinate leakage.

---

## Configuration (`network.yml`)

```yaml
routing:
  mode: "auto"
  # Dispatch mode options:
  #   direct-db:    Backend-to-backend dispatch over SQL/Redis + native Connect.
  #   proxy-broker: Enqueue to NetworkRequestQueue; proxy companion brokers transfer.
  #   auto:         Selects proxy-broker if proxy heartbeats are active or proxy-direct
  #                 transport is used; selects direct-db when SQL/Redis is configured
  #                 without active proxy companion heartbeats.
  dispatchMode: "auto"
```

---

## Consequences

### Positive
- **Zero Proxy Footprint:** Networks can use cross-server RTP with durable state without installing any plugin on the proxy.
- **Lower Latency:** Eliminates the proxy queue polling hop (`NetworkRequestQueue` enqueue/dequeue).
- **Compatible with Non-JVM Proxies:** Works with Gate, HAProxy/Bungee pipelines, and minimal proxy setups.
- **Full Traceability & Durability:** Retains all guarantees of REQ-RTP-NET-001 through REQ-RTP-NET-014.

### Trade-offs / Limitations
- **Requires Player Connection for Move:** Like all plugin-messaging transfers, the player must be connected to the originating backend when the `Connect` packet is sent.
- **No Proxy-Side Tab-Completion without Heartbeats:** Cross-server tab-completion relies on backends sharing snapshots via the database.

---

## References
- [`docs/architecture/12-network-model.md`](../../../../docs/architecture/12-network-model.md)
- [`docs/dev/MULTI_SERVER_PLAN.md`](../../../../docs/dev/MULTI_SERVER_PLAN.md)
- [rtp-proxy-ADR-014](rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md)
- [rtp-proxy-ADR-016](rtp-proxy-ADR-016-plugin-message-default-transport.md)
- [REQ-RTP-NET-011](../../../../docs/dev/REQUIREMENTS.md), [REQ-RTP-NET-012](../../../../docs/dev/REQUIREMENTS.md), [REQ-RTP-NET-014](../../../../docs/dev/REQUIREMENTS.md)

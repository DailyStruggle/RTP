# rtp-proxy-ADR-014 - Backend-Owned `/rtp` with Network Wait-Queue (L6)

**Status:** Accepted (2026-05-21)
**Refines:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-005](rtp-proxy-ADR-005-redis-binding.md), [rtp-proxy-ADR-011](rtp-proxy-ADR-011-sql-network-state-binding.md)
**Supersedes (in part):** [rtp-proxy-ADR-006](rtp-proxy-ADR-006-velocity-bootstrap.md) §Command Hosting - the Velocity adapter no longer registers a Brigadier `/rtp`; the proxy's role is reduced to dispatch and reservation lifecycle.
**Depends on:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md), [`MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md) L6 row.

## Context

The L1-L5 phases of the multi-server work assumed the proxy hosts `/rtp` directly: a `CommandTriggerSource` in `rtp-proxy-common` parsed the slash-command, the proxy chose a backend via `BackendSelector`, claimed a `ReservationToken`, and pushed the player through `ServerPreConnectEvent`. This shape produced four operational problems that L6 is written to fix:

1. **Backends own the cache, the proxy owns the request.** All pre-warmed coordinates live in `RegionQueueManager` on the backend (`keptLocations`, `unkeptLocations`, `backlogLocations`). The proxy can route a player to a backend, but it has no way to earmark "this specific coordinate from that backend's pool is mine for the next 30 seconds". The result is a race: the proxy claims, the backend keeps serving its local `/rtp` queue, the player arrives, and by the time `JoinTriggerSource` fires the warmed coord is already gone.
2. **Two writers to the same store.** Both the proxy (claim/release) and the local backend `/rtp` (poll) mutate `keptLocations`. There is no protocol for "this slot is yours for the cross-server case, hands off". A backend cannot tell, from its own perspective, which of its kept coords are spoken for.
3. **Per-region partitioning has no proxy expression.** Region selection (`/rtp [region]`) is a backend concept enforced by `RegionVerbosePermission` and the per-region cache; on a multi-backend network there is no shared schema for "backend A serves region `default`, backend B serves region `nether`, both serve `mining`". The selector's `regionsAvailable: List<String>` is best-effort, with no per-region kept-count visibility.
4. **`/rtp` on the proxy is a duplicate command surface.** Operators and addons already integrate against the backend's `/rtp` (cooldown, economy, claim-plugin hooks, `commands-api` Brigadier bridge, locale messages). Reproducing this surface on the proxy duplicates code and creates two divergent permission/policy paths.

## Decision

L6 inverts the model: **the backend retains ownership of `/rtp`**, the proxy is reduced to a dispatch and reservation broker, and a new transport-backed FIFO carries cross-server requests from the originating backend through the proxy to the destination backend.

### Architecture

```
Player runs /rtp on backend A
  -> backend A NetworkRouter decides: local-serve | cross-server | reject
       (gates: kill-switch, killSwitchSafetyTtl, requestsPerSecond+burst,
        regionAvailability, region collision policy, mode=auto+local-able,
        backend-A keptCount > 0)
  -> if local: existing local pipeline (unchanged)
  -> if cross-server:
       backend A enqueues EnrolmentEnvelope into NetworkEnrolmentBuffer
       NetworkRequestQueue.flushPending(...) -> proxy-visible FIFO
       proxy worker (TransportRequestTriggerSource) BLPOPs envelope
       proxy DefaultRtpDispatcher:
         - picks backend B via BackendSelector (region + keptCount aware)
         - calls ReservationClient.claim on backend B's earmarked coord
         - fires StatusSink: RESERVED -> ROUTING
         - transferPlayer(playerId, B)
         - fires StatusSink: COMPLETED on ServerPreConnectEvent.success
       backend B JoinTriggerSource:
         - on PlayerJoinEvent, redeemReserved(networkTokenId)
         - if non-null, acceptRedeemedReservation -> personal queue gets the coord
         - existing /rtp dispatch on backend B picks up that exact coord
         - on PlayerQuitEvent: releaseToNetworkKept + transport.release(PLAYER_DISCONNECTED)
       ReservationTokenReaper (proxy-side):
         - periodically reaps expired tokens
         - ReleaseSink fires per-token (in-process for co-located dev; cross-process channel
           deferred to a future ADR - backends learn TTL_EXPIRED via their existing claim flow
           in steady state)
```

### Data Structures (backend, `rtp-core`)

`RegionQueueManager` gains two sibling pools alongside `keptLocations`:

| Field | Type | Lifecycle |
|-------|------|-----------|
| `networkKeptLocations` | `LockFreeLocationBuffer` (nullable) | Cross-server reserve carved from `cacheCap`; capped at `min(networkReserveSize, cacheCap)`. `0` disables. |
| `networkReservedLocations` | `ConcurrentHashMap<UUID, RTPLocation>` | In-flight reservations keyed by token UUID. |

Six new public APIs on `RegionQueueManager`:

- `reserveFromNetworkKept(UUID tokenId): RTPLocation` - pops from `networkKeptLocations`, parks in `networkReservedLocations`, idempotent per token (re-reserves return the parked value).
- `redeemReserved(UUID tokenId): RTPLocation` - removes from `networkReservedLocations`, hands coord to caller (consumes the reservation).
- `releaseToNetworkKept(UUID tokenId): boolean` - returns the coord to `networkKeptLocations`; on bounded-pool full, falls through to `unkeptLocations` (S-004 attribution: never silently dropped).
- `acceptRedeemedReservation(UUID playerId, RTPLocation loc): void` - re-offers a redeemed coord into the player's personal queue so the immediately-following local `/rtp` dispatch picks that exact coord (the "teleport directly" semantics from the checklist, implemented via the existing personal-queue mechanism rather than a parallel teleport pipeline).
- `keptCount(): int`, `networkKeptCount(): int`, `networkReservedCount(): int` - observability for `BackendHeartbeat` payloads and `BackendSelector` decisions.

### Per-Region Split Knob (decided during implementation)

The size of `networkKeptLocations` is set per-region via a new `RegionSettings` record component **`networkReserveSize`** (Long, default `0`, clamped to `cacheCap`). `0` means "feature off for this region" and is the default on existing servers - Slice A ships with no user-visible behavior change until an operator explicitly opts in via `regions.yml`. The alternative `networkReservePercent` (ratio) was considered and rejected: absolute slot counts mirror the existing `cacheCap` / `activeChunkCap` / `backlogCacheCap` style, are simpler to validate (`<= cacheCap`), and are immune to surprising auto-rescale when `cacheCap` changes. A `networkReserveSizeMode = ABSOLUTE | PERCENT_OF_CACHE_CAP` knob can be layered on later without breaking the field name.

### Proxy SPI Additions (`rtp-proxy-common`)

- `NetworkRequestQueue` - new SPI with six methods (`enrol`, `flushPending`, `pollStatus`, `dequeueReady`, `transition`, `cancel`), three impls (`InMemoryNetworkRequestQueue`, `RedisNetworkRequestQueue`, `SqlNetworkRequestQueue`). All async via single-thread daemon executors; correlationId-keyed idempotency; terminal-state cleanup of envelope rows.
- Four Redis Lua scripts under `src/main/resources/redis/` with `.sha1` sidecars: `enqueue_batch.lua` (atomic SADD+RPUSH+HSET), `pollStatus.lua` (HGETALL + LPOS-derived `positionInQueue`), `dequeueReady.lua` (LPOP + envelope HGETALL + status -> ROUTING), `transition.lua` (state UPSERT; on terminal: DEL env HASH + SREM seen).
- `StatusSink` - `@FunctionalInterface` hook on `DefaultRtpDispatcher` firing at every terminal outcome (RESERVED / COMPLETED / FAILED / CANCELLED). The originating backend's `NetworkStatusCache` reads via `NetworkRequestQueue.pollStatus`; this sink is the proxy-side write half.
- `ReleaseSink` - `@FunctionalInterface` hook on `ReservationTokenReaper` firing per reaped token (in-process; backend co-located with reaper in dev, cross-process channel deferred to a future ADR - the steady-state path is covered by `JoinTriggerSource`-on-quit calling `transport.release(..., PLAYER_DISCONNECTED)` directly).
- `BackendHeartbeat` extended with four L6 fields: `keptCount`, `networkReservedCount`, `regions: Set<String>`, `regionKeptCounts: Map<String,Integer>`. Legacy 13-arg / 14-arg ctors retained for back-compat.
- `BackendSelector` extended with `choose(req, snap, serverIdFilter)` for exact-match pinning. `WeightedAverageBackendSelector.qualifies(...)` now excludes kill-switched peers; region-aware filtering prefers `regions: Set` with `regionsAvailable: List` fallback, and gates on `regionKeptCounts.getOrDefault(key, 0) > 0` only when the map is populated.
- `NetworkTransport.listActiveForServer(serverId)` - new default-empty SPI method returning `CompletableFuture<List<ReservationToken>>`. Used at backend boot to repopulate `networkReservedLocations` from the shared store after a crash, releasing stale tokens via `BACKEND_REJECTED`. One-shot reconcile; no steady-state pulse (the steady-state hot path is fully covered by `redeemReserved` on join + `releaseToNetworkKept` on quit).

### Backend Wiring (`rtp-plugin`)

A new `io.github.dailystruggle.rtp.bukkit.network` package hosts:

- `NetworkRouter` - 7-gate decision matrix (kill-switch, kill-switch safety TTL, rate limit, region availability, region collision, mode=auto+local-able, backend keptCount > 0). Lazy-refill `TokenBucket` for the rate-limit gate. `Mode.parse` and region-arg parsing reject `=` / `:` per the cross-server arg-shape contract.
- `NetworkEnrolmentBuffer` - `ConcurrentLinkedDeque` dirty-write buffer; on sink failure, head-re-enqueues the failed batch per S-004 (no silently dropped enrolments).
- `NetworkStatusCache` - 8-state wholesale-replace cache; on supplier failure, the last-known state is preserved per S-004.
- `NetworkRegionCollisionWarner` - boot-time `C-warn` for ambiguous region names across backends; forwards-compatible `Policy.parse`.
- `NetworkModeBootstrap` - wires the four components above, opens the `NetworkRequestQueue` via `NetworkBindings.openRequestQueue`, runs `reconcileNetworkReservations(transport, myServerId)` once at boot, and adds forward-order teardown. Failure-mode policy: on wiring exceptions the bootstrap fails open (router/buffer/cache nulled, transport stays up, local `/rtp` stays functional).
- `JoinTriggerSource` - `REDEEMED` outcome from the proxy's `ServerPreConnectEvent` redeem hook now calls `RegionQueueManager.redeemReserved(...)` across `permRegionLookup` + `tempRegions`, then `acceptRedeemedReservation(playerId, coord)` to pin the redeemed coord onto the player's personal queue, with S-004 release-on-accept-failure. `PlayerQuitEvent` handler releases the bound coord and CAS-transitions the proxy-side token to `PLAYER_DISCONNECTED`.

### Velocity Adapter (`rtp-proxy-velocity`)

The Brigadier `/rtp` command is **deleted** (`registerRtpCommand`, `CommandTriggerSource`, `onCommandTrigger`, `rtpCommandMeta`). It is replaced by a `TransportRequestTriggerSource` in `rtp-proxy-common` running N (`network.queue.workerThreads`, default 1) daemon BLPOP workers that hand each `QueueEnvelope` to `DefaultRtpDispatcher.dispatch`. Shutdown is bounded to 2000ms (interrupt + join + log-on-timeout). The `ServerPreConnectEvent` reservation-redeem path is preserved.

## Consequences

**Positive:**

- The hot path (already-online player runs `/rtp` on their current backend) is unchanged - no proxy round-trip for the common case, no new failure modes for single-backend deployments.
- Cross-server requests respect per-region kept-count visibility; the selector can refuse a peer that has the region but a starved cache.
- Backend retains the existing `/rtp` policy surface (cooldown, economy, claim-plugin hooks, `commands-api` Brigadier bridge, locale messages) - operators see one `/rtp` permission tree, not two.
- The proxy command surface shrinks: one fewer Brigadier registration, one fewer permission key (`rtp.cross`), one fewer locale message file on the proxy side.
- `S-004` discipline is preserved through every new code path: enrolment-buffer head re-enqueue, status-cache last-known preservation, release-fall-through to `unkeptLocations`, sink throws caught at the boundary.

**Negative:**

- One more SPI surface (`NetworkRequestQueue`) and one more keyspace (`rtp:net:wq:*`) for operators to learn. Mitigated by the in-memory reference impl being the default (`transport.kind = memory` works out of the box for dev/test).
- The proxy is no longer the single source of truth for "who has been served"; that lives across the proxy's `rtp:net:wq:status:*` HASHes and each backend's `networkReservedLocations`. Reconciliation on backend boot (`listActiveForServer`) handles crash-recovery; steady-state divergence is bounded by the reservation TTL.
- `BackendHeartbeat` payload grows by four fields. Wire-format compatibility maintained via legacy ctors.

**Risk-mitigated:**

- A backend that opts in to `networkReserveSize > 0` but is the only backend on the network sees no change in observable behavior - local `/rtp` continues to poll `keptLocations` (the unsplit pool), and `networkKeptLocations` slots simply sit idle. The split is feature-off-by-default.

## Alternatives Considered

- **Keep `/rtp` on the proxy, add a "snapshot the backend's cache through the heartbeat" channel.** Rejected: the heartbeat is a steady-state poll, not a transactional store; coordinates would be stale by the time the proxy claimed against them, recreating the original race.
- **Push-based reservation channel (proxy -> backend on every claim).** Considered for `ReleaseSink`. The cross-process push needs either a new per-backend inbound list (`rtp:net:claimed:<serverId>`) and a backend BLPOP worker, or a transport-specific notification primitive (Redis pubsub, Postgres `LISTEN/NOTIFY`). Deferred to a future ADR; the in-process `ReleaseSink` is the correct interface, and the steady-state path is covered today by `JoinTriggerSource.onQuit` calling `transport.release(...)` directly.
- **Steady-state `NetworkReservationPulse` periodic poll.** Considered for backend-side reconciliation. Replaced by one-shot boot reconcile (`listActiveForServer` -> reserve-or-release): the steady-state hot path is fully covered by `redeemReserved` on join + `releaseToNetworkKept` on quit; the pulse only earns its keep on backend restart with outstanding reservations.

## References

- [`docs/dev/MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md) - L6 row.
- [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md) - umbrella network-mode decision.
- [rtp-proxy-ADR-006](rtp-proxy-ADR-006-velocity-bootstrap.md) - Velocity bootstrap (partially superseded: §Command Hosting).
- [rtp-proxy-ADR-005](rtp-proxy-ADR-005-redis-binding.md) - Redis binding keyspace conventions reused for `rtp:net:wq:*`.
- [rtp-proxy-ADR-011](rtp-proxy-ADR-011-sql-network-state-binding.md) - SQL pool sharing for `SqlNetworkRequestQueue`.
- [REQ-RTP-NET-008](../../../docs/dev/REQUIREMENTS.md) - network wait queue.
- [REQ-RTP-NET-011](../../../docs/dev/REQUIREMENTS.md), [REQ-RTP-NET-012](../../../docs/dev/REQUIREMENTS.md), [REQ-RTP-NET-014](../../../docs/dev/REQUIREMENTS.md) - reservation tokens.

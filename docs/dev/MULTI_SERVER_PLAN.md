# Multi-Server (Proxy) Support Roadmap

This document outlines the plan for RTP's multi-server (proxy / network) expansion. It is **distinct from** [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md): that plan covers running on additional Minecraft server flavours (Spigot/Paper/Folia/Fabric); *this* plan covers coordinating RTP across **multiple concurrent backend servers** â€” and, as of 2026-05-07, **multiple concurrent proxy instances** â€” sitting in front of those backends (Velocity, BungeeCord, Waterfall).

> Status: **Phase 0 (Scope Unlock) and Phase 1 (Core SPI) both complete; Phase 2 (Velocity adapter + Redis/SQL transports) in flight as of 2026-05-19.** Requirements authored (REQ-RTP-NET-001â€¦014); GLOSSARY entries (`backend`, `proxy`, `reservation token`, `transport`, `network snapshot`, `backend selector`) live in [`GLOSSARY.md`](GLOSSARY.md); umbrella decision captured in [ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md) (Accepted 2026-05-14) and refined by ten subproject ADRs under [`rtp-proxy/docs/adr/`](../../rtp-proxy/docs/adr/); [`INDEX.md`](INDEX.md) and [`AGENTS.md`](../../.junie/AGENTS.md) updated to co-list network mode as an active frontier. Phase 2 landed slices: 2a (Velocity no-op shell), 2b (participant skeleton + `network.yml` loader), 2c-Î± (`ServerPreConnectEvent` redemption), 2c-Î² / 2d (Brigadier `/rtp` + `CommandTriggerSource`), 2e-SQL (`SqlNetworkStateBinding`), 2e-Redis A1 (heartbeats + snapshot + pub/sub). Open Phase 2 work: Redis A2-A4, reservation-token TTL reaper, D4 HMAC distribution, regression suite, 2-proxy + 2-backend devstack acceptance.

> Cross-references: [rtp-fabric-ADR-002 (Fabric in scope; formerly ADR-022)](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) is **orthogonal** to this plan and is **not** superseded. [ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md) is the ratified umbrella for multi-server proxy support; subproject refinements live under [`rtp-proxy/docs/adr/`](../../rtp-proxy/docs/adr/). Visual companion (mermaid topology / sequence / state-machine diagrams): [`../architecture/12-network-model.md`](../architecture/12-network-model.md).

> L6 update (2026-05-21): Backend-Owned `/rtp` with Network Wait-Queue is **landed** ([rtp-proxy-ADR-014](../../rtp-proxy/docs/adr/rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md), Accepted). Slices A-F complete (backend `networkKeptLocations`/`networkReservedLocations` pools + `NetworkRouter` + `NetworkEnrolmentBuffer` + `NetworkStatusCache`; `NetworkRequestQueue` SPI with InMemory/Redis/SQL impls and 4 Lua scripts; `DefaultRtpDispatcher` `StatusSink`; `ReservationTokenReaper` `ReleaseSink`; Velocity `TransportRequestTriggerSource` BLPOP worker replacing the proxy-side `/rtp` command; backend `JoinTriggerSource` redeem-first with `acceptRedeemedReservation` coord-pin + disconnect release; one-shot boot reconcile via `NetworkTransport.listActiveForServer`). Open follow-ups (Slice G remainder): baseline `network.yml`/`regions.yml`/`messages.yml` additions + locale TSV pipeline run, devstack acceptance (2 proxy + 2 backend), final full build. **Prerequisite for unlocking (B) trigger-config replication**: the project-wide colon-to-equals command-argument migration (separate D-005-gated slice; see L6 checklist row D7 and `NetworkRouter.parseRegionArg`) must land first; until it does, qualified region arguments use `=` rather than `:` to avoid colliding with the legacy parser.

> Veracity audit (2026-05-01): codebase-anchored claims in this plan have been spot-checked against the repo. Confirmed present: `AbstractSQLDatabaseAccessor` (+ `H2`/`SQLite`/`MySQL`/`PostgreSQL` concrete accessors), HikariCP 5.1.0, `RegionQueueManager`, `TeleportPipelineTask`, `MemoryTracker`, `RTP.scheduler.runTaskTimer` / `runTaskTimerAsynchronously`, `BrigadierBridgeContext` + `BrigadierCommandAdapter` in `commands-api/`, `messages.yml`, `REQ-RTP-F-013`. Unverifiable here (external APIs): Velocity `ServerPreConnectEvent`, Lettuce, Postgres `LISTEN/NOTIFY` / `SKIP LOCKED` semantics â€” these are documented as items for ADR-036. Note: the once-flagged `loadBalancer.backends.<serverId>.weight` key has since been drafted into the *Config Surface* below.

---

## Headline Feature

**Cross-server load-balanced RTP** â€” a player request originating anywhere on the network is dispatched to the most appropriate backend, the destination is generated using that backend's existing async pipeline, and the player is transferred. The trigger source (command, server-join, addon event) is **configurable** so operators decide whether to call RTP via `/rtp` or pass the player through on join (mirroring the existing Bukkit join-event hook).

---

## Intended Usage & Deployment Model

The plan is designed around a single explicit operator workflow. Anything that complicates the steps below should be treated as a regression of this plan, not a feature.

### Goals

- **One artifact, every target.** The same RTP JAR drops into a Spigot/Paper/Folia backend, a Fabric backend, a Velocity proxy, or a BungeeCord/Waterfall proxy. The runtime detects the host platform and activates the relevant entry point â€” extending the single-JAR / multi-loader pattern already established by [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) to the proxy axis as well. Operators never pick between "the proxy build" and "the backend build".
- **Multi-proxy by default.** Operators may run any number of proxy instances concurrently â€” for HA failover, geo-distributed front-doors, or capacity scaling â€” and the same `network.yml` (with a per-host `proxyId`, analogous to `serverId`) drops onto every proxy. Coordination between proxies is achieved entirely through the durable shared store (D2/D3); proxies do **not** talk to each other directly. See *Multi-Proxy Deployment* below.
- **Minimal proxy-side configuration.** A proxy's job is to *route* RTP requests, not to own world data. Its configuration shall reduce to: a transport / database reference (Redis or JDBC URL), a shared secret, and the trigger / load-balancer policy. No region definitions, no world tables, no per-backend mirrors of backend-side config.
- **Verbatim copy across backends and proxies.** An operator shall be able to author `network.yml` once, copy it byte-for-byte to every backend **and every proxy**, and only have to change a single per-host field â€” `network.serverId` on backends, `network.proxyId` on proxies. This rules out config sprawl: anything that *must* differ between hosts is either auto-derived (e.g. heartbeat timestamps), centralised (proxy-side trigger config per D1, replicated through the shared store so all proxies see the same view), or limited to that one identifying field.
- **Zero behaviour change when disabled.** With `network.enabled: false` (the shipping default), the artifact behaves byte-identically to today's single-server build. This is REQ-RTP-NET-002 (*Behavioural Parity When Disabled*) and is the gate for any release.

### What an operator does (target workflow)

1. Drop the same JAR onto every backend and onto the proxy.
2. Provision the shared store (Redis instance â€” or any RESP-compatible drop-in such as DragonflyDB / KeyDB â€” or a JDBC database; both reuse `AbstractSQLDatabaseAccessor` per D3).
3. Author one `network.yml` with the transport endpoint, shared secret, trigger policy, and load-balancer policy.
4. Copy that file to every host. On each backend, set `network.serverId` to a unique value; on each proxy, set `network.proxyId` to a unique value. Backends leave `proxyId` unset; proxies leave `serverId` unset. Running a single proxy is just the degenerate case of running one with `proxyId: "proxy-1"`.
5. Set `network.enabled: true`. Restart. Done.

### Non-goals of this section

- Proxy-side region authoring, world tables, or claim-plugin integration. The proxy is intentionally a thin coordinator; world-truth lives on the backend, where it already does.
- Per-platform forks of the artifact. Loader divergence is handled by the existing single-JAR bootstrap; the proxy adapter modules (`rtp-proxy-velocity`, `rtp-proxy-bungee`) ship inside the same JAR, not as separate downloads.
- Anything that forces an operator to maintain a different file per backend beyond the `serverId` field. Audit any new `network.yml` key against this rule before adding it.

### Implications carried into the rest of this plan

- The **Config Surface (`network.yml`)** section below shall stay flat and short; long-form region/world content stays in the existing per-backend `regions.yml` / world configs.
- The **Backend Telemetry Publication** payload is *self-describing* (it carries `serverId`, `platform`, `mcVersion`, `regionsAvailable[]`) precisely so the proxy can run with no inventory of backends declared up-front. New backends register simply by writing their first heartbeat row.
- The **Trigger Abstraction** keeps trigger configuration proxy-side (D1) so adding a new backend requires zero proxy-config changes.
- The **load-balancer** consumes published telemetry rather than a static backend list â€” same rationale; copy-paste-friendly fleet management.

These constraints are part of the acceptance criteria for ADR-036; any deviation must be justified there.

### Non-Goals (v1)

- No proxy-side chunk logic, world data, or entity manipulation. The proxy never owns world state.
- No replacement of the existing single-server pipeline. With `network.enabled: false`, behaviour is byte-identical to today.
- No Forge / NeoForge proxy support. (Out of scope until Fabric platform stabilises â€” see `MULTI_PLATFORM_PLAN.md` Phase 4.)
- No cross-version protocol breakage without a `schemaVersion` bump.
- **No post-arrival coordinate resolution.** Coordinates are resolved on the destination *before* the player transfers; see *Coordinate Resolution Timing* below.

---

## Multi-Proxy Deployment *(decision locked 2026-05-07)*

RTP's network mode treats **multiple concurrent proxy instances** as a first-class deployment, not an edge case. The single-proxy case is just `N = 1`.

### Use cases

- **HA / failover.** Two or more proxies fronted by an L4 load balancer (HAProxy, nginx stream, cloud LB, anycast). One proxy may drop without the network losing `/rtp` capability; in-flight reservations issued by the dead proxy are reanimated by any surviving proxy via the existing `claimReanimateMs` path under *Reservation Tokens*.
- **Geo-distributed proxies.** Per-region proxy instances (e.g. NA / EU / APAC) sharing the same backend fleet. Players connect to the geographically closest proxy; `proxyMeasuredRttMs` is computed per-proxy so the load balancer naturally biases each proxy toward backends with low RTT *from that proxy*.
- **Capacity scaling.** Large networks where a single proxy is the netty / connection bottleneck. Adding a proxy is a copy-paste of `network.yml` plus a new `proxyId`.
- **Blue/green proxy upgrades.** Roll out a new proxy version alongside the old one, drain the old one, drop it. The shared store sees both as ordinary participants.

### Design rules

1. **No proxy-to-proxy chatter.** Proxies never directly call other proxies. All cross-proxy coordination is mediated by the durable shared store (D3) â€” the same Redis / Postgres / generic-SQL binding that backends already use. This keeps the topology a hub-and-spokes star around the store rather than a mesh, and avoids re-introducing proxy-platform-specific RPC.
2. **No proxy singleton assumption.** Anywhere this plan says "the proxy" it shall be read as "some proxy". A request enters one proxy, that proxy claims a reservation token, and the destination consumes it. No code path may assume the same proxy that issued a side effect is the proxy that observes its consequence.
3. **Idempotent proxy operations.** Every proxy-initiated state mutation (token claim, wait-queue enroll, hot-spot decay update) shall be safe to retry from a *different* proxy without producing duplicate work. The `PENDING â†’ CLAIMED` transition's `WHERE state='PENDING'` guard already enforces this for tokens; the wait queue's UUID-keyed FIFO already enforces it for enrollment.
4. **Per-proxy local state is advisory only.** A proxy may keep local caches (tab-completion results, snapshot-freshness counters, the `recentPicks` decaying counter â€” see *Hot-Spot Avoidance Across Proxies* below) for performance, but those caches must never be load-bearing for correctness. Anything required for safety lives in the shared store.
5. **Proxy heartbeat row.** Each proxy publishes its own row to a `proxy_state` table â€” the proxy-side analogue of `backend_state` â€” keyed by `proxyId` (see *Proxy Telemetry Publication* below). This lets operators observe the proxy fleet, lets the reservation reaper detect dead proxies for `claimReanimateMs`, and lets ADR-036-acceptance tests discover proxies dynamically rather than hard-coding an inventory.
6. **Trigger config replication.** Per D1, proxy-side trigger / load-balancer config is authoritative over the backend equivalent. With multiple proxies, the *same* `network.yml` lands on each, so the trigger view is identical by construction. Operators who want runtime-mutable, network-wide trigger config (rather than file-and-restart) shall use the optional `ConfigVersionTable` member of the network-state accessor (D3) â€” already drafted in the storage section â€” and read it on each `/rtp` request rather than at startup. v1 ships file-only; runtime sync is a Phase 3 hardening item.

### Hot-Spot Avoidance Across Proxies

The `recentPicks` metric documented under *Load-Balancing Heuristics* is a per-proxy decaying counter (default halflife 10s). With multiple proxies, each proxy's counter is **local**, which leaves a theoretical hot-spotting window: two proxies can independently pick the same low-score backend in the same heartbeat interval before either's `recentPicks` rises.

This is *largely* self-correcting via the published telemetry: a backend that just absorbed picks from N proxies sees its `mspt`, `pendingTeleports`, `queueDepth`, and `chunkLoadBacklog` rise within one heartbeat, which all proxies observe in the next snapshot. The `recentPicks` row exists to dampen *intra-heartbeat* stampedes, not inter-heartbeat ones.

For operators who require strict cross-proxy coordination (e.g. very low `staleAfterMs` paired with bursty traffic), v2 may add an optional **shared `recentPicks`** mode that writes the bump to the network-state member rather than to a local map, paying one round-trip per pick in exchange for proxy-fleet-wide visibility. This is **deferred** out of v1 â€” the telemetry-driven path is sufficient for the typical 1â€“4 proxy deployments we expect, and adding round-trips to the hot path is the kind of regression D-005 exists to prevent without explicit approval. Tracked in *Open Items / Follow-Ups*.

### Reservation tokens under multiple proxies

The state machine in *Reservation Tokens â€” Lifecycle ownership matrix* already covers the multi-proxy race (REQ-RTP-NET-014): the `PENDING â†’ CLAIMED` transition uses `UPDATE â€¦ WHERE state='PENDING'` row-count atomicity, so two proxies racing for the same token observe row-count `1` and row-count `0` respectively; the loser surfaces `messages.yml` failure (REQ-RTP-NET-006) and falls back to the next-lowest-score candidate via the existing capped-retry chain. The `claimReanimateMs` path, originally written for proxy *restart*, is the same primitive used for proxy *death* in a multi-proxy fleet â€” a token claimed by a dead proxy is re-opened to `PENDING` after the reanimation window so a surviving proxy can pick it up. No additional state is required.

### Network wait queue under multiple proxies

The UUID-keyed network wait queue lives in the network-state member (per D3), which means all proxies observe the same FIFO. A player who enrolls via proxy A and reconnects through proxy B sees the same queue position. Drain happens on the destination backend and is therefore proxy-agnostic by construction â€” the destination writes the reservation token, *some* proxy commits the transfer, and the player arrives. Idempotent UUID-keyed enroll (already specified) means a proxy A failure mid-enroll followed by a retry on proxy B does not produce duplicate queue rows.

### Acceptance tests

The Phase 2 devstack acceptance baseline is **2 proxies + 2 backends + 1 transport** (was: 1 proxy + 2 backends). The added proxy exercises the multi-proxy guarantees above without doubling test infrastructure cost â€” the Velocity adapter is the only new component, and the L4 in front of the proxy fleet is a one-line `nginx stream` block in the devstack compose file. Existing single-proxy tests remain valid as the `N = 1` degenerate case.

---

## Coordinate Resolution Timing *(decision locked 2026-05-01)*

**Coordinates are resolved on the destination backend before the player's server change**, not after arrival. The reservation token issued to the proxy carries the final `worldKey` + `x/y/z/yaw/pitch`, and the destination's join handler simply *consumes* the token rather than running a fresh pipeline.

### Why this is trivial in practice

The destination's existing **kept cache** (`RegionQueueManager.keptLocations` â€” the *Hot Queue* `LockFreeLocationBuffer` of pre-verified safe locations whose chunks are currently loaded with `keep(true)` applied) already produces ready-to-use coordinates as part of normal operation. A network teleport request becomes:

1. Selector picks destination based on telemetry.
2. Destination polls one location from `keptLocations` (the hot queue) â€” falling back to `unkeptLocations` (the cold queue, also pre-verified; chunks must be re-loaded) only if hot is empty.
3. The polled `RTPLocation` is written into a **reservation token** row (under the network-state member of `AbstractSQLDatabaseAccessor`, per D3) â€” i.e. an exclusive cross-network allocation of a coordinate that was already going to be produced anyway.
4. Proxy commits the transfer; destination's join handler consumes the token and teleports the player to the reserved location.

In other words: the network-mode reservation token table is a **thin allocation layer over the existing kept-cache pool** â€” it earmarks one of the buffer's entries as "already promised to a cross-network player" so no other code path can hand out the same coordinates. No new safety-pipeline code paths are introduced.

> Note: the per-player cache (`RegionQueueManager.fastLocations` â€” `ConcurrentHashMap<UUID, CompletableFuture<RTPLocation>>`) and the ADR-023 Login Reserve Cache (`loginLocations`) are *not* the source for cross-network allocations. They serve **already-online players on the local backend** and are intentionally left untouched by network-mode bookkeeping. Cross-network allocations draw from the general region pool (`keptLocations` â†’ `unkeptLocations`).

### Why post-arrival was rejected

| Concern | Pre-resolve (chosen) | Post-arrival (rejected) |
|--------|---------------------|------------------------|
| S-001 / S-003 / S-005 obligations | Stay on destination's existing async pipeline. No re-litigation over the wire. | Run after transfer commits. Failure leaves the player on the wrong server with no clean recovery. |
| S-004 attribution | Failure surfaces on origin via `messages.yml` (REQ-RTP-F-013 / REQ-RTP-NET-006) before any transfer. | Failure surfaces *after* a successful transfer; either silent (S-004 violation) or requires a second transfer to recover. |
| Player UX | One transfer; spawn frame is the final location. | Spawn-flash at destination's spawn, then a teleport. |
| Selector honesty | Selector pays the resolve cost on the chosen backend; mid-flight rejection retries the next-lowest-score candidate. | Selector commits before destination knows it can deliver; rejection means a re-transfer. |
| Reservation tokens | Required (state machine: `PENDING â†’ CLAIMED â†’ CONSUMED`). | Avoidable, but only by paying the cost in failure UX. |

The latency-on-tail downside of pre-resolve is real but additive: it is **softened by the existing cache** (most resolves are O(map lookup), not a full pipeline run), and a future network-wide pre-warmed queue (deferred F2) would close the remaining gap. Post-arrival's failure UX, by contrast, is structural and cannot be retrofitted without re-introducing a token.

### Network Wait Queue (cache miss + no bypass perm)

If both `keptLocations` and `unkeptLocations` are exhausted on the chosen destination (or cannot deliver within the request's deadline) and the player **lacks the bypass permission**, the request shall enroll into a **network-mode UUID wait queue**: a UUID-keyed FIFO that mirrors the existing per-user `playerQueue` pattern in `RegionQueueManager`, but lives in the network-state member so the *proxy* and *destination* can both observe it.

Behaviour:

- Enrollment is idempotent on UUID â€” a player who re-issues `/rtp` while waiting does not double-queue.
- The destination's region cache replenishes asynchronously through the existing deficit loop in `Region.execute()`; as new entries land in `keptLocations`/`unkeptLocations`, the network wait queue drains in FIFO order, each drain pulling a coordinate, issuing a reservation token, and transferring the player.
- The proxy may surface a configurable "you are #N in the network queue" message (REQ-RTP-F-013 / REQ-RTP-NET-006 / REQ-RTP-NET-008), reusing the existing single-server queue-position UX.
- Bypass permission **reuses the existing `rtp.unqueued` node** (no new permission). When `true`, the player skips the network wait queue and the destination generates a fresh location immediately; if no backend can deliver immediately, the request fails fast with a configurable message. Use is expected to be rare â€” implementation is **low priority**, may land in Phase 2 or be deferred to Phase 3 without blocking acceptance.
- The wait-queue table is **purely transient**: rows live as long as the player is connected and waiting; the reservation-token reaper also reaps stale wait-queue rows on the same TTL clock. This keeps it consistent with single-server semantics where the wait queue lives only in memory.

This preserves the single-server fairness model across the network without inventing a new one: the same "hot kept-cache first, cold cache next, otherwise wait your turn" contract that exists today, just with the wait queue allocated globally instead of per-backend.

### Summary

- **Decision**: pre-resolve coordinates on destination, transfer with reservation token. ADR-036 acceptance criterion.
- **Reservation token table**: thin allocation layer over the existing region kept-cache (`keptLocations`, fallback `unkeptLocations`). New code is bookkeeping, not safety-pipeline.
- **Per-player caches stay local-only**: `fastLocations` and `loginLocations` (ADR-023) are not consumed by cross-network allocations.
- **Cache miss + no bypass permission**: enroll into a UUID-keyed network wait queue mirroring the existing per-region `playerQueue`. Bypass permission skips the queue.
- **No new prohibitions cross the wire**; S-001â€¦S-006 stay attributed exactly where they are today.

---

## Decisions Recorded (from brainstorm 2026-05-01)

These answers are taken from the issue thread that produced this document. They lock in scope; any change requires explicit user approval per Rule D-005.

| # | Question | Decision |
|---|----------|----------|
| D1 | Network-mode default world resolution on join | **Proxy-side config.** `JoinTriggerSource` reads region/world mappings from the proxy plugin's config, not per-backend. |
| D2 | Reservation persistence on proxy restart | **Required.** Transport must be durable. `plugin-message` is therefore a **degraded / dev-only** mode and not supported in production. |
| D3 | Network state storage location | **Reuse `AbstractSQLDatabaseAccessor` where possible.** If a separate `AbstractNetworkStateAccessor` proves necessary, it must live **adjacent to** or **as a member of** the existing accessor â€” not a parallel hierarchy. |
| D4 | HMAC key distribution | **Env var for v1** (`RTP_NET_SECRET`). Operators set the same value on every host; matches the copy-paste deployment model. Other mechanisms (config file with restrictive perms, per-backend keypair, OS keyring) are deferred research items â€” may revisit before public release without blocking Phase 2. |

Additional locked-in decisions:

- **Proxy primary**: Velocity. **Secondary**: BungeeCord/Waterfall. Both eventually required.
- **Transport preference order**: Redis (most responsive â€” and any RESP-compatible drop-in such as DragonflyDB or KeyDB; Redis is the reference implementation), Postgres (co-equal candidate, needs analysis), generic SQL (MySQL/MariaDB) for universal fallback, `plugin-message` for dev only.
- **Commands**: extend `commands-api` rather than fork. Brigadier bridge work (Step G of `MULTI_PLATFORM_PLAN.md`) carries over for Velocity.

---

## Architecture Overview

```
                              â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
                              â”‚  rtp-proxy-velocity (1Â°)   â”‚
   /rtp / on-join trigger â”€â”€â”€â–¶â”‚  rtp-proxy-bungee   (2Â°)   â”‚â”€â”€â–¶ ServerPreConnectEvent
                              â”‚     uses rtp-proxy-common  â”‚   (rewrite target)
                              â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                            â”‚ NetworkTransport SPI
                              â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
                              â”‚  Durable shared state      â”‚
                              â”‚   Redis  |  Postgres  |    â”‚
                              â”‚   generic SQL (poll)       â”‚
                              â”‚  (via AbstractSQLDatabase- â”‚
                              â”‚   Accessor + adjacent      â”‚
                              â”‚   network-state member)    â”‚
                              â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                            â”‚
   â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¼â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
   â–¼                        â–¼               â–¼               â–¼                        â–¼
 backend-1               backend-2       backend-3       backend-N            (each runs
 rtp-bukkit/paper/       â€¦               â€¦               â€¦                   the existing
 folia/fabric                                                                 single-server
 + NetworkBridge                                                              pipeline,
   (rtp-core, optional)                                                       unchanged)
```

Module shape proposed (Phase 0 will formalise via ADR-036):

```
rtp-proxy/
â”œâ”€â”€ rtp-proxy-common/      # SPI, dispatcher, BackendSelector, transport interface â€” no proxy imports
â”œâ”€â”€ rtp-proxy-velocity/    # Velocity adapter â€” primary
â””â”€â”€ rtp-proxy-bungee/      # BungeeCord/Waterfall adapter â€” secondary, lands Phase 3
```

Backend-side glue (`NetworkBridge`) lives in **`rtp-core`** as an optional, default-disabled subsystem. It contains zero proxy-platform imports; transport implementations are loaded reflectively or via service-loader.

---

## Trigger Abstraction â€” `RtpTriggerSource`

One internal entry point, many configurable producers. Defined in `rtp-core`:

```java
interface RtpTriggerSource {
    String id();                 // "command", "join", "portal", "addon-foo"
    boolean enabled();           // from config
    void register(RtpDispatcher dispatcher);
}
```

Shipped sources, each toggled in `network.yml`:

- `CommandTriggerSource` â€” `/rtp` (existing path, routed through the dispatcher when network mode is on).
- `JoinTriggerSource` â€” generalised join hook so Velocity proxy-side join *and* backend-side join both flow through the same dispatcher. **Per D1, region/world mapping is read proxy-side.**
- `EventTriggerSource` â€” fires on a named addon-facing event so third parties wire their own triggers without forking.

Adding new triggers later is config-only.

---

## Load Balancer â€” `BackendSelector`

The new core component. Lives in `rtp-core` (no platform deps).

```java
interface BackendSelector {
    CompletableFuture<BackendChoice> choose(RtpRequest req, NetworkSnapshot snap);
}
```

All strategies must be **pure functions of `NetworkSnapshot`** â€” no I/O during `choose()`. This preserves S-005 spirit (no blocking on a tick or netty thread).

### Load-Balancing Heuristics â€” Configurable Weighted Average *(direction set 2026-05-01)*

User direction: **v1 ships a single configurable strategy â€” a weighted average over published telemetry metrics, with a per-metric response curve.** No discrete strategy zoo (no `ROUND_ROBIN` / `LEAST_LOADED` / etc. as separate selectors); those collapse to special cases of the weighted-average configuration. The proxy owns this configuration so admins tune the network from one place.

#### Model

For each candidate backend `b` passing the availability filter, compute:

```
score(b) = Î£_i  weight_i  *  curve_i( normalize_i( metric_i(b) ) )
```

- `metric_i(b)` â€” a single field from the **Backend Telemetry Publication** payload (e.g. `playerCount / softCap`, `mspt`, `queueDepth`, `avgPipelineMs`, `chunkLoadBacklog`, `1 - tps20Ratio`, `latencyMs`, â€¦).
- `normalize_i` â€” maps the raw metric into `[0, 1]` where `0` = "cheapest / best" and `1` = "most expensive / worst". Configurable per metric (`min`, `max`, `clamp`).
- `curve_i` â€” the response curve applied to the normalized value (see catalogue below).
- `weight_i` â€” non-negative scalar from config; `0` disables the metric.

Selection picks the backend with the **lowest** score (cost-minimization framing â€” keeps "0 = best" intuitive across all metrics). Ties broken by `serverId` ascending for determinism in tests.

#### Curve catalogue (config-selectable per metric)

All curves take a normalized input `x âˆˆ [0, 1]` and return `y âˆˆ [0, 1]`.

| `curve` | Formula (straw-man) | Shape | When to use |
|--------|--------------------|-------|-------------|
| `linear` | `y = x` | straight | metric is roughly proportional to cost (e.g. `queueDepth`) |
| `exponential` | `y = (e^(kÂ·x) âˆ’ 1) / (e^k âˆ’ 1)`, default `k = 3` | flat then sharp ramp | metric is fine until it's *very* bad (e.g. `mspt`, `chunkLoadBacklog`) |
| `logarithmic` | `y = log(1 + kÂ·x) / log(1 + k)`, default `k = 9` | sharp then flattens | metric saturates quickly (e.g. `playerCount` near `softCap`) |
| `sigmoid` | `y = 1 / (1 + e^(âˆ’kÂ·(x âˆ’ 0.5)))`, default `k = 8` (renormalised to [0,1]) | "steep in the middle" â€” the user's request | smooth on/off threshold around the midpoint (e.g. `tps` dropoff, `heapUsedRatio`) |
| `step` | `y = 0` if `x < threshold`, `y = 1` otherwise | hard cliff | binary fences (e.g. `acceptingRequests`, `pluginState != READY`) |
| `power` | `y = x^p`, default `p = 2` | mild curvature | conservative quadratic for symmetry with linear |

Curve params (`k`, `threshold`, `p`) are per-metric in config; defaults above. Curves must be **monotonic non-decreasing** so the score is well-ordered; the publisher's "snapshot, not deltas" contract guarantees clean inputs.

#### Config surface (replaces the prior `loadBalancer.strategy: TBD` straw-man)

Lives **proxy-side** (matches D1 â€” proxy owns trigger/selection config so admins tune the network in one place):

```yaml
network:
  loadBalancer:
    # Single strategy: weighted average over telemetry. No 'strategy:' enum.
    staleAfterMs: 3000          # exclude backend if last_seen_epoch_ms older than now - this
    tieBreaker: serverIdAsc     # deterministic
    metrics:
      playerLoad:
        source: "playerCount / softCap"   # supports a small expression DSL or a fixed enum
        weight: 1.0
        normalize: { min: 0.0, max: 1.0, clamp: true }
        curve:    { type: logarithmic, k: 9 }
      mspt:
        source: mspt
        weight: 1.5
        normalize: { min: 0.0, max: 100.0, clamp: true }   # 50ms = 1 tick budget
        curve:    { type: exponential, k: 3 }
      queueDepth:
        source: queueDepth
        weight: 0.5
        normalize: { min: 0, max: 64, clamp: true }
        curve:    { type: linear }
      pipelineMs:
        source: avgPipelineMs
        weight: 0.7
        normalize: { min: 0, max: 2000, clamp: true }
        curve:    { type: sigmoid, k: 8 }
      proxyLatency:
        source: proxyMeasuredRttMs        # proxy-side, not published by backend
        weight: 0.3
        normalize: { min: 0, max: 200, clamp: true }
        curve:    { type: linear }
      heap:
        source: "heapUsedMb / heapMaxMb"
        weight: 0.4
        normalize: { min: 0.0, max: 1.0, clamp: true }
        curve:    { type: sigmoid, k: 10 }
      regionAffinity:
        source: stickyRegionMatch         # 0 if backend serves requested region preferentially, 1 otherwise
        weight: 0.2
        normalize: { min: 0, max: 1, clamp: true }
        curve:    { type: step, threshold: 0.5 }
```

`source` is either a published-field name from the telemetry table (Backend Telemetry Publication section) or one of a small fixed set of proxy-computed values (`proxyMeasuredRttMs`, `stickyRegionMatch`, â€¦). A full expression DSL is **out of scope for v1** â€” start with the field-name + small enum approach; revisit only if real configs demand it.

Special cases collapse cleanly:
- *Round-robin equivalent*: zero out all weights; selector falls back to `tieBreaker`.
- *Least-loaded*: weight only `playerLoad` and/or `mspt`.
- *Lowest-latency*: weight only `proxyLatency`.
- *Sticky region*: weight `regionAffinity` heavily; everything else light.
- *Weighted (admin-set per backend)*: not represented as a per-metric weight â€” admins set a per-backend multiplier (`loadBalancer.backends.<serverId>.weight`, included in the *Config Surface* below) that divides the final score so a higher backend weight makes that backend preferred while keeping "lowest wins".

#### Defaults shipped with v1

The example block above is the **shipped default**. Rationale per metric noted inline. Operators can disable any line by setting `weight: 0`. The defaults must be benchmarked against a reference Velocity + 2Ã— Paper devstack before Phase 2 release; tuning notes will land in `LESSONS_LEARNED.md`.

#### Documentation follow-up

- **Curve visualizations** â€” add rendered plots of each curve (`linear`, `exponential`, `logarithmic`, `sigmoid`, `step`, `power`) at their default parameters to `docs/admin/proxies/` (e.g. `LOAD_BALANCING.md` with embedded SVG/PNG) so admins can pick a curve by shape, not by formula. Generation script lives under `scripts/` (matplotlib or similar). Tracked as a Phase 3 documentation item â€” block on it before the first public proxy beta.

#### Resolved items (formerly open)

- **Hot-spot avoidance** â€” *confirmed*. Implemented as a per-proxy decaying counter of recent picks, added to the score as another metric row (`recentPicks`) with its own `weight`/`curve`. Lives in the same model; no special-case code path. **Default halflife: 10s** (decay constant `Î» = ln(2) / 10s â‰ˆ 0.0693 sâ»Â¹`); the counter is bumped by `+1` on each pick and decays exponentially between heartbeats. The 10s figure matches the operator-experience target (a single low-score backend stops being preferred within roughly two heartbeat windows after a stampede starts). Default weight ships at a moderate value so it tempers but does not dominate the cost signal.
- **Tie-breaking** â€” *resolved*. `serverIdAsc` is final; ties between weighted-average scores are exceedingly rare and `serverId` ordering is sufficient for determinism. No tie-breaker enum.
- **Curve param ranges** â€” *confirmed*. Validation at config load enforces sane bounds for `k`, `p`, `threshold` so a malformed config cannot produce NaN scores. Concrete bounds: `k âˆˆ [0.1, 20]`, `p âˆˆ [0.1, 8]`, `threshold âˆˆ [0.0, 1.0]` (subject to ratification in ADR-036).
- **Per-backend weight key** â€” *added*. `loadBalancer.backends.<serverId>.weight` is now part of the config surface (see *Config Surface* below). Acts as a multiplier: final score is `rawScore / backendWeight`, so a higher weight makes a backend preferred while keeping "lowest wins".
- **Player-count weighting** â€” *resolved as `weight: 0` for v1*. Player count is still **published** in telemetry (operators want it for dashboards) but is not consumed by the selector by default. Re-evaluate **after live-player testing** of the Phase 2 devstack â€” if `mspt` and `pendingTeleports` already capture the relevant strain under real load, the weight stays at zero permanently; if a population-driven signal proves additive, raise it then. No further design work is required before Phase 2.

#### Failure / fallback chain (informed by Linux scheduler best practice)

Adapted from CFS / kernel load-balancer conventions â€” cheap to evaluate, expensive to mis-pick, biased toward stability over reactivity:

- **Capped retries**: on chosen-backend rejection or timeout, retry with the next-lowest score, capped at `loadBalancer.maxRetries` (default `3`). Beyond the cap, fail fast with a configurable `messages.yml` entry (REQ-RTP-F-013 / REQ-RTP-NET-006).
- **Per-attempt timeout**: `loadBalancer.attemptTimeoutMs` (default `1500`). The selector treats a timeout identically to a rejection.
- **Hysteresis on re-pick**: after a rejection, the rejected backend is excluded from selection for `loadBalancer.cooldownMs` (default `2000`) â€” the same idea as CFS's `imbalance_pct` / `nr_balance_failed` debounce. Prevents the proxy from re-picking the same struggling backend on the next request.
- **Score sticking ('idle balance'-style)**: don't migrate already-pending requests to a freshly-cheaper backend mid-flight. Once a request is dispatched, it stays with the chosen backend until success, timeout, or rejection â€” mirrors how CFS prefers not to migrate a running task unless the imbalance is significant.
- **Snapshot freshness**: the selector reads `NetworkSnapshot` once at request entry and uses that snapshot for the entire retry chain (analogous to a single `rebalance_domains` pass). Keeps retry decisions internally consistent.

All four knobs (`maxRetries`, `attemptTimeoutMs`, `cooldownMs`, `recentPicks` half-life/weight) live in the `loadBalancer` block of `network.yml` and ship with the defaults above. Concrete tuning notes will land in `LESSONS_LEARNED.md` after the Phase 2 devstack benchmark.

#### Still-open items (smaller list)

- **Expression DSL vs. fixed `source:` enum** â€” start fixed-enum; revisit if/when configs ask for compound expressions beyond the two ratios above.

This section is now **direction-locked**, not a placeholder. Phase 1 may implement against it, with the v1 default block above as the test fixture.

---

## Backend Telemetry Publication *(NEW, 2026-05-01)*

Each backend running RTP **shall publish to its configured database** a periodic record describing two distinct concerns:

1. **Plugin state â€” availability.** Is this backend usable as an RTP destination *right now*? This is a binary-plus-context signal: the backend is up, RTP is loaded, the pipeline is responsive, and the requested regions exist.
2. **Server state â€” performance cost.** How expensive is it to serve another teleport from this backend *right now*? This is a continuous signal feeding the load balancer (see *Load-Balancing Heuristics â€” Configurable Weighted Average* above).

These are intentionally separated so a healthy-but-overloaded backend can be filtered out of selection (availability=OK, cost=high) distinctly from a stale or degraded backend (availability=stale, cost=N/A).

> Per D3, this telemetry lives **adjacent to** or **as a member of** `AbstractSQLDatabaseAccessor`, not in a parallel hierarchy. Each backend writes to **its own configured database** â€” the same accessor that already persists cooldowns. If transport is Redis, the same payload is also published to a pub/sub channel for push semantics; the SQL row is the durable record.

### What to publish â€” Availability fields

- `serverId` â€” unique, from `network.yml`.
- `schemaVersion` â€” for cross-version negotiation (REQ-RTP-NET protocol).
- `rtpVersion` â€” plugin version string.
- `platform` â€” `spigot` | `paper` | `folia` | `fabric`.
- `mcVersion` â€” Minecraft version string.
- `pluginState` â€” enum: `STARTING` | `READY` | `DEGRADED` | `SHUTTING_DOWN`.
- `regionsAvailable[]` â€” names of regions this backend can currently serve (drawn from `regions.yml` minus any disabled or in-error regions).
- `worldsLoaded[]` â€” world keys the backend has loaded (so the selector can avoid asking a backend to RTP into a world it hasn't loaded yet).
- `acceptingRequests` â€” boolean kill switch: ops can flip a backend out of rotation without stopping the server (mirrors `/rtp pause`-style semantics).
- `lastSeenEpochMs` â€” write timestamp; the staleness filter is computed against this.

### What to publish â€” Performance / cost fields

- `playerCount` / `softCap` â€” current population vs. configured comfortable capacity.
- `tps1m` / `tps5m` / `tps15m` â€” rolling tick-rate. (Folia: per-region TPS aggregated; document the aggregation choice in ADR-036.)
- `mspt` â€” average milliseconds per tick over the last sample window.
- `queueDepth` â€” pending entries in `RegionQueueManager`.
- `pendingTeleports` â€” in-flight `TeleportPipelineTask` count.
- `avgPipelineMs` â€” rolling mean pipeline duration over the last N completed teleports.
- `chunkLoadBacklog` â€” count of async chunk-load futures not yet completed (S-005-relevant signal).
- `memoryTrackerEntries` â€” count of registered allocations (early warning of leaks; surfaces the same data `rtp test full` already reports).
- `heapUsedMb` / `heapMaxMb` â€” coarse JVM memory pressure.
- `databaseLatencyMs` â€” last write/read round-trip to this backend's own DB (cheap and indicative; if the backend can't talk to its own DB, it can't reliably serve reservations either).

All counters are **snapshots**, not deltas â€” the consumer (selector) does the math. This keeps the publisher trivially idempotent.

### Cadence and write strategy

- Default heartbeat interval: **1 second** (configurable via `network.heartbeat.intervalTicks`, default `20`). Aligns with the heartbeat/staleness pattern already drafted under `NetworkSnapshot`.
- Writes happen on `RTP.scheduler.runTaskTimerAsynchronously` â€” **never on a tick / region thread** (REQ-RTP-NET-007, S-005 spirit).
- Single-row UPSERT keyed on `serverId` (no historical retention by default â€” keeps the table tiny). A separate optional `BackendHeartbeatHistoryTable` is a Phase 4 hardening item if ops want longitudinal data.
- On Folia, the publisher is async-only; per-region TPS is sampled via the platform's `RegionScheduler` API and folded into the row before write.
- On graceful shutdown, the backend writes a final row with `pluginState=SHUTTING_DOWN` and `acceptingRequests=false` so the selector drops it immediately rather than waiting for the staleness window. Best-effort â€” a hard crash relies on the staleness filter.
- Failure to publish must **not** abort RTP operation locally; it shall log under S-004 attribution rules and the selector treats the backend as stale until publication resumes.

### Table sketch (subject to ADR-036)

```
backend_state                     -- one row per backend, UPSERT keyed by server_id
  server_id              VARCHAR PK
  schema_version         INT
  rtp_version            VARCHAR
  platform               VARCHAR
  mc_version             VARCHAR
  plugin_state           VARCHAR    -- STARTING|READY|DEGRADED|SHUTTING_DOWN
  accepting_requests     BOOLEAN
  regions_available      JSON / TEXT (CSV fallback for SQLite)
  worlds_loaded          JSON / TEXT
  player_count           INT
  soft_cap               INT
  tps_1m                 DOUBLE
  tps_5m                 DOUBLE
  tps_15m                DOUBLE
  mspt                   DOUBLE
  queue_depth            INT
  pending_teleports      INT
  avg_pipeline_ms        DOUBLE
  chunk_load_backlog     INT
  memory_tracker_entries INT
  heap_used_mb           INT
  heap_max_mb            INT
  database_latency_ms    INT
  last_seen_epoch_ms     BIGINT
```

JSON columns become `JSONB` on Postgres; TEXT/CSV on SQLite (which is dev-only anyway per the storage section).

### Module placement

- The publisher (`BackendStatePublisher`) lives in **`rtp-core`** as part of the `NetworkBridge` optional subsystem. Default-disabled when `network.enabled: false` â€” REQ-RTP-NET-002 must remain green.
- The accessor member (per D3) exposes `writeBackendState(BackendStateRow)` and `readNetworkSnapshot()`. Concrete bindings (Redis / Postgres / generic SQL / in-memory) implement both.
- No platform imports in the publisher â€” TPS/MSPT/heap/region data come through `RTP.serverAccessor` extensions (a small additive surface to spec in ADR-036; the *April 2026 gap analysis* in `MULTI_PLATFORM_PLAN.md` does **not** cover these new methods).

### Open items folded into existing placeholders

- The **Load-Balancing Heuristics â€” Configurable Weighted Average** section is the single place that decides *how* these fields are weighted. The publisher commits to providing them; the selector decides which it uses.
- **Spigot TPS source** â€” *resolved*. Minimum supported Spigot is 1.20.1; raw Spigot's `Bukkit.Server` does not expose `getTPS()` on that version (it is a Paper-only addition). For Spigot-only stacks, sample tick duration locally via a 1-tick scheduled task and compute MSPT/TPS from the elapsed-nanos differential. On Paper/Folia, use `Bukkit.getTPS()` directly. Module: `rtp-bukkit` adapter for the fallback sampler; see also the new metrics plan (`METRICS_PLAN.md`) for the canonical implementation.
- **Per-region TPS aggregation on Folia** â€” lives in [`METRICS_PLAN.md`](METRICS_PLAN.md), not this plan. Player-count-weighting is **out** (D-confirmed 2026-05-01); the choice is between `max` and `mean`, with leaning toward `max` so a single struggling region surfaces. Final call deferred to the metrics plan.

---

## Storage â€” Reuse `AbstractSQLDatabaseAccessor` (per D3)

Per D3, the existing `AbstractSQLDatabaseAccessor` is the primary storage abstraction. Network-shared state (heartbeats, reservation tokens, network cooldowns, config versions) lives **as a member of, or adjacent to**, that accessor â€” not in a parallel tree.

Sketch (subject to ADR-036 ratification):

```
AbstractSQLDatabaseAccessor                        (existing â€” per-backend persistence)
  â”œâ”€â”€ PlayerCooldownTable, â€¦                       (existing)
  â””â”€â”€ networkState  : NetworkStateMember           (NEW â€” adjacent member, optional)
        â”œâ”€â”€ BackendHeartbeatTable
        â”œâ”€â”€ ReservationTokenTable
        â”œâ”€â”€ NetworkCooldownTable
        â””â”€â”€ ConfigVersionTable
```

Transport implementations bind to the same accessor:

- `RedisNetworkStateBinding` â€” Lettuce, async, optional dependency. Pub/sub for heartbeats + reservation events. Preferred for responsiveness. **RESP-compatible drop-ins (DragonflyDB, KeyDB) are supported by the same binding** â€” the connection URL is the only thing that changes; no flavour sniffing, no `transport.flavour` knob. Redis is the reference implementation; Dragonfly is exercised by a Phase 2 acceptance container alongside Redis (see *Phase 2*). Any RESP behavioural divergence observed in practice (Lua/`EVAL` edge cases, `XADD/XREAD` consumer-group semantics, persistence model) is captured in `LESSONS_LEARNED.md` rather than as a code branch.
- `PostgresNetworkStateBinding` â€” `LISTEN/NOTIFY` for push semantics; `SELECT â€¦ FOR UPDATE SKIP LOCKED` for race-free reservation claim. Shares HikariCP with the existing accessor â€” zero new pool surface.
- `GenericSqlNetworkStateBinding` â€” MySQL/MariaDB with polling fallback. Universal but higher latency.
- `InMemoryNetworkStateBinding` â€” single-JVM tests and the no-op default when `network.enabled: false`.

### Postgres Analysis Items (for ADR-036)

- `LISTEN/NOTIFY` viability and payload-size limits in our heartbeat cadence.
- `SKIP LOCKED` race characteristics under contention from N backends.
- `JSONB` vs. normalised columns for the snapshot blob.
- HikariCP reuse vs. dedicated pool.

These items must be answered in ADR-036 before Phase 3.

---

## Reservation Tokens

The reservation token exists *because* of the *Coordinate Resolution Timing* decision above: it is the thin allocation layer that earmarks one already-resolved coordinate (drawn from the destination's region kept-cache â€” `RegionQueueManager.keptLocations`, falling back to `unkeptLocations`) as "promised to a cross-network player," so no local code path can hand it out twice. The per-player `fastLocations` cache and the ADR-023 `loginLocations` reserve are deliberately untouched by this layer (they serve already-local players). New code here is bookkeeping; safety-pipeline code is not duplicated.

Single shared keyspace owned by the network-state member of the accessor:

- Fields: `token (UUID PK)`, `playerUuid`, `targetServerId`, `worldKey`, `x/y/z/yaw/pitch`, `issuedAt`, `expiresAt`, `state âˆˆ {PENDING, CLAIMED, CONSUMED, EXPIRED}`.
- **Issued** by the destination backend after its pipeline produces a safe location.
- **Claimed** atomically when the proxy commits to a transfer.
- **Consumed** by the destination backend on player join (PaperMC `PlayerJoinEvent` / Fabric server-join event / equivalent). Idempotent: `UPDATE â€¦ WHERE state='CLAIMED'` returning row-count; >1 â‡’ replay, refuse and log under S-004.
- **Reaped** by a scheduled `RTP.scheduler.runTaskTimerAsynchronously` on each backend, which releases `MemoryTracker` entries on local rows it owns.
- TTL: configurable, default 30s.

Per D2, tokens **must survive a proxy restart**, which is why `plugin-message` transport is dev-only â€” it has no durability guarantee.

### Lifecycle ownership matrix

| State transition | Initiator | Atomicity primitive | Failure handling |
|------------------|-----------|---------------------|------------------|
| `â€”` â†’ `PENDING` | Destination backend (after pipeline produces safe location) | INSERT under unique `(playerUuid, state=PENDING)` partial index; conflict â‡’ reuse existing row | If conflict happens during a retry, the destination returns the existing token rather than minting a new one |
| `PENDING` â†’ `CLAIMED` | Proxy (just before issuing the transfer) | `UPDATE â€¦ SET state='CLAIMED', claimedAtEpochMs=now WHERE token=? AND state='PENDING'` returning row-count | row-count `= 0` â‡’ race lost (token expired or was claimed by a parallel proxy instance); proxy aborts the transfer and surfaces a `messages.yml` failure (REQ-RTP-NET-006) |
| `CLAIMED` â†’ `CONSUMED` | Destination backend (in the join handler) | `UPDATE â€¦ SET state='CONSUMED' WHERE token=? AND state='CLAIMED'` returning row-count | row-count `> 1` â‡’ replay attempt; reject the duplicate join, log under S-004 |
| `PENDING` \| `CLAIMED` â†’ `EXPIRED` | Reaper (each backend, async timer) | `UPDATE â€¦ SET state='EXPIRED' WHERE expiresAtEpochMs<now AND state IN ('PENDING','CLAIMED')` | Token is no longer valid; if it was `CLAIMED`, the destination releases the underlying `keptLocations` entry back to its source buffer and emits a `MemoryTracker` release; an audit row is logged |

Proxy-restart recovery: on startup the proxy runs `UPDATE â€¦ SET state='PENDING' WHERE state='CLAIMED' AND claimedAtEpochMs < now - claimReanimateMs` (default `claimReanimateMs = 5000`). This re-opens any token claimed by a proxy that died before completing the transfer, letting the next proxy instance pick it up rather than orphaning it until TTL expiry.

Destination-restart recovery: on startup the destination runs the local reaper at half its normal interval for the first `2 * heartbeatInterval` so any tokens it issued just before crashing are aged out promptly. Tokens issued by a *different* destination are out of scope â€” only that backend can release the underlying `keptLocations` entry, so a permanently-dead backend's tokens age out via TTL.

### Required regression coverage

A dedicated regression suite analogous to `ReqRtpS004NullChunkAttributionTest` is required before Phase 2 acceptance, covering at minimum:

- Replay protection: a `CLAIMED â†’ CONSUMED` transition that races itself across two backend instances must succeed exactly once.
- TTL expiry: a `PENDING` token whose `expiresAt < now` must transition to `EXPIRED` and release its `MemoryTracker` entry within one reaper interval.
- Orphaned-allocation prevention: a backend crash mid-issue (`PENDING` written but no proxy ever claims) must not leak a `keptLocations` entry beyond TTL.
- Proxy-restart reanimation: a `CLAIMED` token whose proxy died is observed in `PENDING` again after `claimReanimateMs`, and the next proxy instance can claim it.
- Schema-version mismatch: a token written under an older `schemaVersion` is rejected (or upgraded, depending on the version-skew policy ratified in ADR-036).
- HMAC reject: a token whose envelope HMAC fails verification is dropped and an S-004 audit log is emitted; the player request fails through the configured `messages.yml` entry, not silently.

---

## `commands-api` Extension

- `NetworkAwareCommand` mixin â€” when present, execution is routed through `RtpDispatcher` instead of run locally. Single-server commands stay untouched.
- `ProxySender` abstraction in `commands-api` â€” adapts both Velocity's `CommandSource` and BungeeCord's `CommandSender` so `/rtp` works identically whether issued on a backend or on the proxy itself.
- Tab-completion routing: proxy queries any backend on the transport, merges results, applies a local cache TTL.
- Brigadier bridge (`BrigadierCommandAdapter` / `BrigadierBridgeContext` from Step G of `MULTI_PLATFORM_PLAN.md`) carries over for Velocity, which uses Brigadier internally.

---

## Config Surface (`network.yml` â€” straw-man)

```yaml
network:
  enabled: false                  # hard kill switch, default off â†’ zero behavioural change
  # Identity. Exactly one of serverId / proxyId is set per host.
  # Backends set serverId; proxies set proxyId. Both fields are unique
  # within their respective tables in the shared store.
  serverId: "survival-1"          # unique per backend (omit on proxies)
  proxyId:  "proxy-1"             # unique per proxy   (omit on backends)
  schemaVersion: 1

  transport:
    type: redis                   # redis | postgres | sql | plugin-message (dev only, per D2)
                                  # `redis` covers any RESP-compatible server: Redis, DragonflyDB, KeyDB.
                                  # No separate `dragonfly` / `keydb` types â€” only the URL differs.
    redis:    { host, port, password, channelPrefix }
    postgres: { jdbcUrl, user, password, listenChannel }
    sql:      { jdbcUrl, user, password, pollIntervalMs }
    pluginMessage: { channel: "rtp:net" }   # NOT for production (D2)

  loadBalancer:
    # Direction-locked: configurable weighted average over telemetry. See
    # "Load-Balancing Heuristics â€” Configurable Weighted Average" above.
    staleAfterMs:       3000
    maxRetries:         3       # capped fallback chain (Linux-scheduler-inspired)
    attemptTimeoutMs:   1500    # per-attempt timeout; treated as rejection
    cooldownMs:         2000    # hysteresis: rejected backend excluded this long
    metrics:
      # ... see heuristics section for full per-metric block (source/weight/
      # normalize/curve). Includes a `recentPicks` row for hot-spot avoidance
      # and `playerLoad: { weight: 0 }` (telemetry published, not weighted).
    backends:
      # Optional per-backend multiplier; final score is rawScore / weight.
      # Higher weight â†’ preferred. Omitted entries default to weight 1.0.
      # survival-1: { weight: 1.0 }
      # survival-2: { weight: 0.5 }   # capacity-asymmetric host, picked half as often

  triggers:
    command: { enabled: true }
    join:    { enabled: false }   # region/world map sourced PROXY-SIDE per D1
    event:   { enabled: false }

  reservation:
    ttlSeconds: 30
    reaperIntervalTicks: 40

  security:
    sharedSecret: "${RTP_NET_SECRET}"   # env var (D4 v1); same value on every host
```

Acceptance contract: with `network.enabled: false`, all single-server tests must remain byte-identical green. A dedicated no-op test is required.

---

## Requirements â€” Cross-Reference

The canonical wording for every `REQ-RTP-NET-NNN` requirement lives in [`REQUIREMENTS.md` Â§1.6 *Network / Proxy Support*](REQUIREMENTS.md). The table below maps the plan's internal topics to the authored requirement IDs so in-body citations elsewhere in this document have a single lookup. Traceability rows (currently all *unimplemented*) live in [`TRACEABILITY.md` â€” *Network / Proxy Requirements*](TRACEABILITY.md).

| Authored ID | Topic | Plan section(s) that rely on it |
|---|---|---|
| `REQ-RTP-NET-001` | Optional network mode (off by default) | *Config Surface* (`network.enabled`) |
| `REQ-RTP-NET-002` | Behavioural parity when disabled | *Non-Goals (v1)*; *Phase 1 no-op test*; *Risk & Pitfall Inventory* |
| `REQ-RTP-NET-003` | Single distribution artifact (backend / proxy role auto-select) | *Intended Usage & Deployment Model* |
| `REQ-RTP-NET-004` | Safety preservation across the network (S-001â€¦S-006) | *Coordinate Resolution Timing*; *Why post-arrival was rejected* |
| `REQ-RTP-NET-005` | Authoritative world state on backends | *Architecture Overview*; *Non-Goals (v1)* |
| `REQ-RTP-NET-006` | Configurable network messaging (extends REQ-RTP-F-013) | *Reservation Tokens â€” Lifecycle ownership matrix*; *Network Wait Queue*; *Capped retries* |
| `REQ-RTP-NET-007` | Non-blocking network I/O (extends REQ-RTP-F-008, REQ-RTP-S-005) | *Backend Telemetry Publication â€” Cadence*; *Risk & Pitfall Inventory* |
| `REQ-RTP-NET-008` | Cross-network fairness (UUID wait queue, bypass semantics) | *Network Wait Queue*; queue-position message |
| `REQ-RTP-NET-009` | Authenticated, versioned inter-server data relay | *Sufficiency Audit*; *Risk & Pitfall Inventory â€” Security* |
| `REQ-RTP-NET-010` | Proxy load-balancing policy (configurable, with disable option) | *Load-Balancing Heuristics* (weights of `0` collapse to round-robin / fixed routing) |
| `REQ-RTP-NET-011` | Reservation token deterministic expiry; no orphaned allocations | *Reservation Tokens â€” Lifecycle ownership matrix* (TTL/`EXPIRED` row); *Required regression coverage* |
| `REQ-RTP-NET-012` | Exactly-once reservation claim | *Reservation Tokens â€” Lifecycle ownership matrix* (`CLAIMED â†’ CONSUMED` row); *Sufficiency Audit* |
| `REQ-RTP-NET-013` | Multi-flavour persistence compatibility | *Storage â€” Reuse `AbstractSQLDatabaseAccessor`*; *Sufficiency Audit* |
| `REQ-RTP-NET-014` | Multi-proxy concurrency and reanimation | *Multi-Proxy Deployment*; *Reservation tokens under multiple proxies*; *Phase 2 acceptance* |

> Authoring rules for any future amendments: see [`docs/dev/RULES.md`](RULES.md) and the *Requirement Documentation Rules* section of [`AGENTS.md`](../../.junie/AGENTS.md) (use `shall` / `shall not`, no implementation actions, no temporal framing).

---

## Phased Roadmap

Mirrors the structure of [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) so contributors can navigate either plan with the same mental model.

### Phase 0 â€” Scope Unlock *(docs only; D-005 gate)*

- [x] **ADR-036 â€” Multi-Server Proxy Support** â€” accepted 2026-05-14: Velocity-first, load-balancing headline, durable-transport requirement (D2), reuse of `AbstractSQLDatabaseAccessor` (D3). Does not supersede rtp-fabric-ADR-002 (formerly ADR-022). See [`docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md`](../adr/ADR-036-network-mode-multi-server-multi-proxy.md).
- [x] **`REQUIREMENTS.md`** â€” REQ-RTP-NET-001â€¦014 authored with `shall` phrasing (see `REQUIREMENTS.md` Â§1.6). Plan-internal citations re-threaded against canonical IDs; cross-reference table above replaces the prior stub table.
- [x] **`GLOSSARY.md`** â€” *backend*, *proxy*, *reservation token*, *transport*, *network snapshot*, *backend selector* are all authored in [`GLOSSARY.md`](GLOSSARY.md) (2026-05-19 audit).
- [x] **`AGENTS.md` Current Development Focus** â€” flipped 2026-05-19: network mode and Fabric now co-listed as active frontiers (Phase 1 SPI + `InMemoryNetworkStateBinding` landed). Safe-to-modify module list expanded to include `rtp-proxy-common` / `rtp-proxy-velocity` / `rtp-proxy-bungee`.
- [x] **`INDEX.md`** â€” plan row present in [`docs/dev/INDEX.md`](INDEX.md); ADR-036 task-router row added 2026-05-19 ("Why network mode (multi-server, multi-proxy) is in scope").
- [x] **Fill the load-balancing heuristics placeholder** in this document â€” see *Load-Balancing Heuristics â€” Configurable Weighted Average* above (direction-locked, defaults serve as test fixture).
- [x] **Resolve D4** â€” env-var `RTP_NET_SECRET` selected for v1 (see *Decisions Recorded* row D4 and *Open Items* for deferred alternatives).

### Phase 1 â€” Core SPI *(no proxy adapter yet)*

Status audited 2026-05-18 (initial sweep) and again 2026-05-18 (closure pass). All six items are now ticked. Item 1 is satisfied with an amendment: `RtpTriggerSource` lives in `rtp-core` as a producer-side abstraction (`io.github.dailystruggle.rtp.common.network.RtpTriggerSource`); `RtpDispatcher` is retained in `rtp-proxy-common/spi/` as the consumer-side SPI. The amendment is intentional: dragging the proxy-shaped `RtpDispatcher` into `rtp-core` would force `rtp-core` to depend on proxy SPI types (`DispatchOutcome`, `RtpRequest`), violating the platform-/proxy-agnostic boundary of `rtp-core` (`Architecture Boundaries`, `AGENTS.md`). The producer/consumer split is honest about what each module owns.

- [x] `RtpTriggerSource` (producer-side abstraction) in `rtp-api`; `RtpDispatcher` (consumer-side SPI) retained in `rtp-proxy-common`. *(`rtp-api/.../api/network/RtpTriggerSource.java` defines a self-contained `Trigger` record + `Kind` enum and lifecycle (`start`/`stop`); `rtp-proxy-common/.../spi/RtpDispatcher.java` is unchanged. Plan amendment 2026-05-18; promoted from `rtp-core` to `rtp-api` 2026-05-19 so proxy-side producers (e.g. `CommandTriggerSource`) can implement it without crossing the `rtp-proxy-common` -> `rtp-core` module-boundary block.)*
- [x] `BackendSelector` interface; ship at least one concrete strategy approved in the heuristics placeholder. *(`rtp-proxy-common/.../spi/BackendSelector.java` + `selector/WeightedAverageBackendSelector.java` per rtp-proxy-ADR-004; covered by `WeightedAverageBackendSelectorTest`.)*
- [x] `NetworkTransport` interface + `InMemoryNetworkStateBinding` reference impl. *(`rtp-proxy-common/.../spi/NetworkTransport.java` + `transport/memory/InMemoryNetworkStateBinding.java` per rtp-proxy-ADR-001/-003.)*
- [x] Network-state member adjacent to `AbstractSQLDatabaseAccessor` per D3. *(`AbstractSQLDatabaseAccessor.networkStateBinding` field + `getNetworkStateBinding()` / `setNetworkStateBinding(...)`; opaque marker `io.github.dailystruggle.rtp.common.network.NetworkStateBinding` in `rtp-core` keeps the accessor proxy-agnostic. Default is `null` (network disabled).)*
- [x] Single-JVM tests with two simulated backends. *(`InMemoryNetworkStateBindingTest` and `WeightedAverageBackendSelectorTest` exercise multi-backend snapshots, heartbeat fan-out, selector choice, and concurrent claim idempotency in-JVM.)*
- [x] No-op test proving `network.enabled: false` is byte-identical. *(`ReqRtpNet002NetworkDisabledNoOpTest` in `rtp-core/src/test/.../common/network/` (REQ-RTP-NET-002 *Behavioural Parity When Disabled*): asserts the default binding is `null`, construction spawns no `network`/`redis`/`lettuce`-named threads, and the setter is plumbing-only.)*

### Phase 2 â€” Velocity adapter + Redis transport (incl. DragonflyDB validation)

> **Phase 2 entry unblocked 2026-05-18.** [rtp-proxy-ADR-006](../../rtp-proxy/docs/adr/rtp-proxy-ADR-006-velocity-bootstrap.md) (Velocity Bootstrap) is Accepted, ratifying the plugin-identity, activation-order, `ServerPreConnectEvent` flow, Brigadier-hosting, telemetry-scheduling, and bundled-resources decisions for the Velocity adapter.
>
> **Phase 2a landed 2026-05-18.** The `rtp-proxy-velocity` module is now included in `settings.gradle` and ships a no-op shell: `RtpVelocityPlugin` with `@Plugin(id="rtp")`, `ProxyInitializeEvent` / `ProxyShutdownEvent` handlers that log a single banner line and register nothing else. No `network.yml` read, no `ServerPreConnectEvent` interception, no Brigadier registration, no `ProxyStatePublisher`, no transport open. Satisfies REQ-RTP-PROXY-VELOCITY-001 and preserves REQ-RTP-NET-002 (byte-identical no-op when disabled) by structural means. Guarded by `ReqRtpProxyVelocity001SmokeTest` (5 tests: `@Plugin` id, both lifecycle handlers, the "only two `@Subscribe` methods exist" no-op guard, and the entry-point-is-final guard). Next concrete step is Phase 2b (`VelocityProxySender` + `ProxyStatePublisher` skeleton, REQ-RTP-PROXY-VELOCITY-006), pending its own D-005 proposal.
>
> **Phase 2b design pinned 2026-05-18.** Five blocking design questions resolved this turn (see `docs/dev/scratch/PROPOSAL-velocity-redis-startup.md` and `PROPOSAL-sql-binding-first.md` rev 2):
>
> - Phase 2b leads with **`SqlNetworkStateBinding`** (DB-as-bus) rather than `InMemoryNetworkStateBinding`. Redis (ADR-005) is demoted to a Phase 2e opt-in latency optimisation. See [rtp-proxy-ADR-011](../../rtp-proxy/docs/adr/rtp-proxy-ADR-011-sql-network-state-binding.md) (Proposed). Most networks that run RTP across multiple backends already share a SQL database for region storage; standing up Redis just to enable network mode would be a deployment tax we no longer require.
> - Proxy role is **participant by default**, router opt-out, toggled implicitly by which SPI components the adapter wires (no new config knob). See [rtp-proxy-ADR-012](../../rtp-proxy/docs/adr/rtp-proxy-ADR-012-proxy-role-participant-default.md) (Proposed).
> - `role: auto` resolution moved off classpath reflection onto a registered `RTPProxyAccessor` (mirrors `RTP.serverAccessor`). `network.proxyId` empty/missing when role resolves to proxy is now an explicit `fail-fast`. `network.role` is proxy-side-only. See [rtp-proxy-ADR-013](../../rtp-proxy/docs/adr/rtp-proxy-ADR-013-proxy-accessor-registration.md) (Proposed) and the 2026-05-18 amendment in [rtp-proxy-ADR-002](../../rtp-proxy/docs/adr/rtp-proxy-ADR-002-network-yml-schema.md).
> - ADR-005 (Redis binding) and ADR-010 (security hardening) flipped to **Accepted (2026-05-18)** with two clarifications: HMAC-stays-on-wire (the `ReservationToken` SPI value class does not carry `hmac` / `schemaVersion`; the transport materialises the verifier when assembling wire payloads) and Lua scripts ship with checked-in SHA1 sidecars verified on `transport.open()`.
> - `ProxyHeartbeat` and `BackendHeartbeat` gained an additive `boolean killSwitch` field (defaults `false`) so the ADR-010 kill switch has a typed propagation channel rather than the prior "first byte of every heartbeat payload" prose.
>
> **Phase 2b participant skeleton landed 2026-05-18.** `rtp-proxy-common` gains the `RTPProxyAccessor` abstraction (mirrors `RTP.serverAccessor`), the `RtpProxy.proxyAccessor` static slot with S-006 null-guard contract, the `Role` enum, the `NetworkConfig` value class + `fromMap` loader (fail-fast on missing `proxyId`, on unset `secretEnv` when enabled), and the `ProxyStatePublisher` cadence skeleton. `rtp-proxy-velocity` grows: `VelocityProxyAccessor` registers `Role.PROXY_VELOCITY` during `ProxyInitializeEvent` BEFORE config load, the adapter parses `network.yml` via SnakeYAML, and when `enabled:true` opens an `InMemoryNetworkStateBinding` and starts the heartbeat publisher. REQ-RTP-NET-002 byte-identical no-op is preserved when `enabled:false` (publisher and transport are not constructed). Phase 2b deliberately omits `ServerPreConnectEvent` (Phase 2c) and Brigadier `/rtp` (Phase 2d); the Redis / Sql transport bindings land in Phase 2e. Guarded by `RtpProxyTest`, `NetworkConfigTest`, `ProxyStatePublisherTest`, and `RtpVelocityPluginPhase2bTest`. Existing `ReqRtpProxyVelocity001SmokeTest` still green (only two `@Subscribe` methods on the plugin entry-point).

> **Phase 2e-SQL slice landed 2026-05-18.** `rtp-proxy-common` gains `SqlNetworkStateSchema` (idempotent DDL for `rtp_network_proxies` / `rtp_network_backends` / `rtp_network_tokens`), `SqlNetworkStateBinding` (NetworkTransport with H2/MySQL/Postgres/SQLite UPSERTs + atomic claim via SQLSTATE 23xxx race-loss translation + 1s poll loop), and `NetworkBindings` factory. `NetworkTransport` SPI grows a `default publishBackendHeartbeat(...)` method so backend-side publishers and bindings share one entry point. `rtp-core` adds `BackendStateSampler` SPI + `BackendStatePublisher` cadence loop (mirrors the proxy-side publisher); `rtp-bukkit-common` adds `BukkitBackendStateSampler` that pulls TPS/MSPT/players from `RTP.metrics`; `rtp-plugin` ships a `network.yml` template and wires the lifecycle via a self-contained `NetworkModeBootstrap` helper called from `RTPBukkitPlugin.onEnable` (after DB setup) and `onDisable` (before DB drain). REQ-RTP-NET-002 byte-identical no-op is preserved when `network.yml` is absent or `enabled:false`. Velocity adapter switches to the `NetworkBindings.open(cfg, null)` factory; proxies graceful-fall-back to in-memory when `transport.type=sql` is requested without a proxy-side JDBC config (Phase 2e-SQL-Proxy adds that). Guarded by `SqlNetworkStateBindingH2Test` (6/6 - covers cross-instance snapshot, peer-subscriber fan-out, idempotent UPSERT, atomic claim race, release-then-reclaim, close-terminal) and `BackendStatePublisherTest` (3/3 - manual tick, throwing-sampler isolation, start/stop idempotency). ADR-011 (`rtp-proxy-ADR-011-sql-network-state-binding`) flipped to **Accepted (2026-05-18)** by this slice.

> **Phase 2e-Redis A1 slice landed 2026-05-19.** `rtp-proxy-common` gains `transport/redis/RedisNetworkStateBinding` (Jedis 5.1.2, reused from `rtp-core`'s existing `RedisManager` to avoid a second redis-client dependency). A1 scope is intentionally minimum-viable per ADR-005: heartbeats (`HSET rtp:net:backend:{serverId}` / `rtp:net:proxy:{proxyId}` + TTL `3 * heartbeatIntervalMs`), snapshot read via `SCAN MATCH rtp:net:backend:* + HGETALL`, subscriber fan-out via Redis `PUBLISH/SUBSCRIBE` on `rtp:net:backend`, with a dedicated daemon thread holding the blocking `subscribe` call and reconnecting on disconnect. `claim` / `release` throw `UnsupportedOperationException` and `findReservation` returns `Optional.empty()`; reservation-token-dependent flows (Velocity `ServerPreConnectEvent` redemption) continue to require `transport.type: sql` or `in-memory` until **A2** lands the atomic-claim Lua scripts under ADR-005's key layout. **A3** adds HMAC envelope + `killSwitch` propagation per ADR-010; **A4** adds backpressure hardening and snapshot re-prime after pool reconnect. `NetworkConfig` gains additive `redisHost` / `redisPort` / `redisPassword` fields (fail-fast on empty `redisHost` when `transport.type=redis` and `enabled=true`); `NetworkBindings.open(cfg, ...)` and backend-side `NetworkModeBootstrap.openTransport` both wire the redis case. `network.yml` template documents the new `transport.redis` subsection. Wire format is private to the binding (handwritten `key=value` lines to keep `rtp-proxy-common` JSON-free). **No automated tests this slice** per user direction (defer to in-game `rtp test network` against a real Redis); single-JVM Shape A simulator already exercises the publish/snapshot/subscribe loop against any binding the JVM has open, including this one. REQ-RTP-NET-002 byte-identical no-op preserved when `enabled:false`. Full multi-module `gradlew build` green.

### Proxy Roles *(decision pinned 2026-05-18)*

Two architectures for a proxy adapter are supported and toggleable per deployment; **participant** is the default.

- **Participant** (default). The proxy is an ordinary network peer (consistent with line 73's framing): it publishes a `ProxyHeartbeat` via `ProxyStatePublisher`, runs a local `BackendSelector` against the same `NetworkSnapshot` every backend sees, holds an active `NetworkTransport` connection, originates `/rtp` requests as a first-class citizen, and contributes to the network wait queue. Best fit when the proxy and backends are operated by the same team.
- **Router** (opt-out). The proxy intercepts `ServerPreConnectEvent` and redeems `ReservationToken`s that a backend produced; it does not publish heartbeats, does not run a selector, and does not host `/rtp`. Best fit for managed-hosting or multi-tenant deployments where the proxy operator wants the role narrowed to the one thing only the proxy can do (cross-server transfers).

The toggle is **implicit-via-wiring**: the adapter (`rtp-proxy-velocity`, future `rtp-proxy-bungee`) registers different SPI components on bootstrap. There is no `proxy.role:` knob in `network.yml`. Operator visibility is served by a single startup INFO line listing the wired components (per [rtp-proxy-ADR-012](../../rtp-proxy/docs/adr/rtp-proxy-ADR-012-proxy-role-participant-default.md)). Mixed-mode networks (some participant proxies + some router proxies on the same backend fleet) are explicitly well-defined; see ADR-012 Â§"Mixed-mode networks are well-defined".

> **Phase 2e-Redis A2 slice landed 2026-05-19.** `RedisNetworkStateBinding.claim` / `release` / `findReservation` now wire through Lua scripts shipped at `rtp-proxy-common/src/main/resources/redis/{claim,release}.lua` with checked-in `.sha1` sidecars verified at construction time (ADR-005 Amendment 2026-05-18). A new package-private `RedisLuaScripts` loader LF-normalises script bytes for cross-OS deterministic hashing, refuses to enable on a sidecar mismatch (build-time defect), pre-loads via `SCRIPT LOAD`, and dispatches via `EVALSHA` with `NOSCRIPT` re-load fallback. Claim is a single-call create-and-lock against `rtp:net:tok:{tokenId}` + `rtp:net:tokactive:{playerId}`; race-loss surfaces as `IllegalStateException` (mirrors `SqlNetworkStateBinding.claimSync`). Release is idempotent and cleans the active-player index. Reservation-token-dependent Velocity flows (`ServerPreConnectEvent` redemption, `CommandTriggerSource` -> `claim` -> `createConnectionRequest`) now work end-to-end against `transport.type: redis`, closing the gap that previously forced Redis deployments to fall back to `sql` or `in-memory`. Guarded by `RedisLuaScriptsTest` (3/3 - sidecar parity for each script + missing-script error path); no live Redis required for the offline suite. **A3** (HMAC envelope + `killSwitch` propagation per ADR-010) and **A4** (backpressure hardening + snapshot re-prime after pool reconnect) remain open. Live-Redis integration tests (race-loss, NOSCRIPT recovery, TTL expiry) deferred to the Phase 2 acceptance devstack (2 proxy + 2 backend + Redis / DragonflyDB). Full multi-module `gradlew build` green.

> **Phase 2e-Redis A3 (heartbeats + reservation tokens) landed 2026-05-20.** `HmacVerifier` (HMAC-SHA-256 envelope per rtp-proxy-ADR-010 section "HMAC Envelope") is threaded through `NetworkBindings.open` and `RedisNetworkStateBinding` for both heartbeats and reservation-token rows. Heartbeat side: `encodeBackend` / `encodeProxy` sign over the canonical flattened payload and append an `hmac=<hex>` field; `decodeBackend` (and the pub/sub deliver path on `rtp:net:backend`) strips the `hmac` key, verifies via `MessageDigest.isEqual`, drops the row with a REQ-RTP-S-004 WARNING on mismatch. Token side: `claim.lua` accepts a Java-precomputed `hmacHex` as ARGV[7] and stores it opaquely in the row's `hmac` HSET field; Java's `claimSync` builds the canonical token payload (`tokenId|serverId|playerId|expiresAtMs|createdAtMs|state=CLAIMED`) and signs it pre-EVALSHA; `findReservationSync` rebuilds the same canonical from the HSET fields on non-terminal rows and verifies before returning a `ReservationToken`, dropping tampered rows (forged claim, replayed row, state flipped from `RELEASED` back to `CLAIMED`) with the same WARNING. Terminal-state rows are filtered ahead of verification so `release.lua` / `reap.lua` need no changes. The `claim.lua.sha1` sidecar updates to `b0937f1020b145e5efd45e9e4ca16621feb3eb9b`; the other two sidecars are unchanged. Verifier construction is the single fail-fast on the security path (REQ-RTP-PROXY-007); other Redis-side faults (connect, `SCRIPT LOAD`, pub/sub) continue to degrade-to-disabled per PROPOSAL-velocity-redis-startup section 6. Guarded by `HmacVerifierTest` (10/10); wire roundtrip is exercised operator-side by `/rtp test network all` in the same JVM (single-backend self-loop) which now covers both signed heartbeats and signed token claim/find/release/reap. **SQL transport HMAC** remains the next discrete A3 follow-up. `gradlew build` green; **wire-format breaking change vs any pre-A3 Redis state**: operators upgrading from a Redis instance holding pre-A3 (un-HMAC'd) heartbeat or token rows must `redis-cli FLUSHDB` once.


> **Phase 2e-SQL A3 envelope landed 2026-05-20.** Parity with Phase 2e-Redis A3: SqlNetworkStateBinding now signs every published ProxyHeartbeat / BackendHeartbeat / ReservationToken row with HmacVerifier over the same canonical field-order strings the Redis transport uses, and verifies on every read site (
eadSnapshotSync -> 
owToBackend, indReservationSync) with constant-time compare; tampered or legacy-NULL hmac rows drop with REQ-RTP-S-004 WARNING. SqlNetworkStateSchema gains a nullable hmac VARCHAR(128) column on 
tp_network_proxies / 
tp_network_backends / 
tp_network_tokens, plus idempotent ALTER TABLE ... ADD COLUMN IF NOT EXISTS hmac VARCHAR(128) migration that runs on every bootstrap and tolerates the dialect quirk (MySQL/MariaDB lacks IF NOT EXISTS on ADD COLUMN; portable form is attempted first, then bare ADD COLUMN is retried, with SQLSTATE 42S21 / 42xxx swallowed as duplicate-column). NetworkBindings.open SQL branch now constructs HmacVerifier.loadFromEnv the same way as the Redis branch and degrades-to-disabled (InMemory fallback) on loader failure per PROPOSAL-velocity-redis-startup §6. Verifier-null ctor preserved for back-compat (tests + deployments that have not yet set 
etwork.secretEnv). Guarded by two new cases in SqlNetworkStateBindingH2Test (10/10 - signed roundtrip across peers + tampered-row drop on both token and backend; legacy NULL-hmac row dropped under signed mode); full multi-module gradlew build green. Pre-A3 deployments self-heal on the next heartbeat tick once RTP_NET_SECRET is set; operators may DELETE FROM rtp_network_tokens once for a clean cut on the short-TTL token table but the heartbeat tables migrate transparently. killSwitch propagation tightening + Redis A4 reconnect hardening remain the open A3/A4 items.
- [x] `rtp-proxy-common` + `rtp-proxy-velocity` modules. (Common: Phase 1; Velocity: Phase 2a no-op shell at `rtp-proxy/rtp-proxy-velocity/`, REQ-RTP-PROXY-VELOCITY-001 satisfied, `ReqRtpProxyVelocity001SmokeTest` green.)
- [x] `RedisNetworkStateBinding` A1+A2+A3 slices (Jedis-backed; heartbeats + snapshot + pub/sub fan-out + atomic claim/release via Lua with checked-in SHA1 sidecars per ADR-005; HMAC envelope on heartbeats and reservation tokens per ADR-010, 2026-05-20). SQL transport HMAC envelope parity landed 2026-05-20 (heartbeat + reservation-token sign/verify on `SqlNetworkStateBinding` with nullable `hmac VARCHAR(128)` column across H2/MySQL/MariaDB/Postgres/SQLite; idempotent `ALTER TABLE` migration of pre-A3 DBs). *`killSwitch` propagation tightening tracked as the next discrete A3 follow-up; reconnect hardening + snapshot re-prime (A4) remain open; see Phase 2e-Redis A1, A2, A3 and Phase 2e-SQL A3 callouts below.*

> **Phase 2 reservation-token TTL reaper landed 2026-05-19.** `rtp-proxy-common` gains a `NetworkTransport.reapExpired(Instant now)` SPI default (returns empty list for legacy bindings) and a shared `transport/ReservationTokenReaper` component that schedules a single-thread daemon sweep, invokes `transport.reapExpired(now)` to bulk-transition expired tokens to `RELEASED` under row-count atomicity (REQ-RTP-PROXY-004), then dispatches `transport.release(tokenId, TTL_EXPIRED)` per winner so the existing release plumbing notifies the originating backend's region buffer (REQ-RTP-NET-011). All three bindings override: `InMemoryNetworkStateBinding` CAS-transitions each expired token, `SqlNetworkStateBinding.reapExpiredSync` runs a portable ANSI-SQL bulk UPDATE + sentinel-SELECT pair under one transaction (no `RETURNING` / `FETCH FIRST` / `SKIP LOCKED`, so H2 / Postgres / MySQL / SQLite all work), and `RedisNetworkStateBinding` adds a `reap.lua` script + SHA1 sidecar that SCANs the token keyspace, transitions matching rows, and drops their `rtp:net:tokactive:{playerId}` index entries in a single Lua block. `NetworkConfig` gains additive `reservation.reapIntervalMs` knob (default 30000; non-positive clamps to default so a misconfigured zero cannot silently disable reaping). Guarded by `ReservationTokenReaperTest` (5/5 - reaps-expired-only, idempotent second pass, close-without-start, bad-interval rejection, transport-failure survival, scheduled-sweep autonomy), a new `reapLuaSidecarMatches` case in `RedisLuaScriptsTest` (4/4), and a new `reapExpiredDropsOnlyExpiredRows` case in `SqlNetworkStateBindingH2Test` (8/8). REQ-RTP-NET-002 byte-identical no-op preserved (no reaper is constructed when `enabled:false`). Full multi-module `gradlew build` green.

> **Phase 2 reservation-token TTL reaper adapter wiring landed 2026-05-19.** Follow-on to the reaper component slice above. `RtpVelocityPlugin` (proxy) and `NetworkModeBootstrap` (backend, `rtp-plugin`) both construct a `ReservationTokenReaper` alongside the active `NetworkTransport` when `enabled:true`, start it after the heartbeat publisher, and `close()` it before transport teardown in reverse-order shutdown. Velocity reads cadence from `NetworkConfig.reservationReapIntervalMs()`; backend reads it inline from `network.yml` `reservation.reapIntervalMs` with the same default (30000) and non-positive clamp. REQ-RTP-NET-002 byte-identical no-op preserved on both adapters (no reaper is constructed when `enabled:false` or `network.yml` is absent). Existing `RtpVelocityPluginPhase2bTest`/`2c`/`2d` suites still green (23/23 in `:rtp-proxy-velocity:test`); no new automated test this slice (lifecycle is structurally identical to the existing publisher wiring, already covered by the Phase 2b/2c/2d structural guards). Live multi-proxy reaper contention exercised on the deferred Phase 2 acceptance devstack.
- [x] Reservation tokens end-to-end with TTL reaper. (Token *redemption* at connect time landed in Phase 2c; the periodic TTL reaper sweep landed 2026-05-19 - see *Phase 2 reservation-token TTL reaper landed 2026-05-19* callout below.)
- [x] `ServerPreConnectEvent` hook (Velocity) for backend rewrite. *(Phase 2c-Î± landed 2026-05-19: `NetworkTransport.findReservation(playerId)` SPI default; in-memory and SQL bindings override; `RtpVelocityPlugin.onServerPreConnect` rewrites the target to the token's `serverId`, transitions `CLAIMED -> CONSUMED`, S-004 WARNING + fall-through on miss/expiry/unknown-server/lookup-error. Guarded by `RtpVelocityPluginPhase2cTest` (4/4 - disabled-no-op, no-reservation-fallthrough, active-reservation-rewrites, unknown-target-fallthrough) and the new `findReservationCrossesInstances` case in `SqlNetworkStateBindingH2Test`. `CommandTriggerSource` adapter intentionally deferred to Phase 2d so it lands next to Brigadier `/rtp`.)*
- [x] `CommandTriggerSource` wired through dispatcher. *(Phase 2c-Î² / Phase 2d landed 2026-05-19. `RtpTriggerSource` promoted from `rtp-core` to `rtp-api` (`io.github.dailystruggle.rtp.api.network.RtpTriggerSource`) so both producer sides (`rtp-proxy-common`, `rtp-core` platform adapters) implement it without module-boundary crossings. New `CommandTriggerSource` in `rtp-proxy-common/.../trigger/`. Brigadier `/rtp` registered on the Velocity proxy in `RtpVelocityPlugin.registerRtpCommand()` (REQ-RTP-PROXY-VELOCITY-002); executor fires the trigger source; consumer (`onCommandTrigger`) runs selector -> `transport.claim` -> `player.createConnectionRequest`, with `ServerPreConnectEvent` (Phase 2c-Î±) redeeming the token at the connect boundary. Guarded by `CommandTriggerSourceTest` (6/6 - lifecycle, fire-before-start no-op, double-start rejection, stop idempotency, null guard) and `RtpVelocityPluginPhase2dTest` (3/3 - structural guards on `registerRtpCommand`, `onCommandTrigger`, test accessors). The proxy `/rtp` is player-only; console invocations emit a hint and return. Token TTL is 30s by default. Phase 2d does not add a config knob; world argument is parsed-and-ignored pending a richer `Trigger` record.)*
- [ ] Resolve D4 (HMAC key distribution) before security review.
- [ ] **Acceptance**: cross-server `/rtp` round-trip on **2Ã— Velocity (behind L4) + 2Ã— Paper** devstack with Redis. The two-proxy baseline exercises REQ-RTP-NET-014 (multi-proxy concurrency + reanimation); single-proxy is the `N=1` degenerate case and remains green by construction.
- [ ] **Acceptance (RESP compatibility)**: same cross-server `/rtp` round-trip green against **DragonflyDB** as the shared store, using the unmodified `RedisNetworkStateBinding`. One extra container in the devstack compose; no new code paths. Any observed RESP divergence (Lua/`EVAL`, Streams consumer groups, persistence semantics) is recorded in `LESSONS_LEARNED.md`, not branched on in code.
- [x] Regression suite for reservation token replay / TTL / orphaned-MemoryTracker scenarios. *(Landed 2026-05-19 as `ReservationTokenRegressionTest` in `rtp-proxy-common`: 9/9 cases covering double-redeem rejection, expired-token invisibility to `findReservation`, orphan release via `ReservationTokenReaper.reapNow`, reaper idempotency across passes, reaper-skips-terminal, concurrent-release idempotency, claim race-loss surface, reaper-on-empty no-op, reaper-races-manual-release safety. Driven against `InMemoryNetworkStateBinding` with a virtual clock; SQL-binding parity is covered by `SqlNetworkStateBindingH2Test`; live-Redis parity deferred to the Phase 2 acceptance devstack alongside Redis A3/A4.)*

### Phase 3 â€” Postgres transport + Join trigger + BungeeCord adapter

- [ ] Postgres `LISTEN/NOTIFY` push + `SKIP LOCKED` claim optimization (latency win over polling). Postgres is already supported end-to-end via `SqlNetworkStateBinding`'s `POSTGRES` dialect (UPSERT + portable polling per ADR-011); this remaining box is scoped to the push-channel / lock-hint optimization originally specified by [rtp-proxy-ADR-007](../../rtp-proxy/docs/adr/rtp-proxy-ADR-007-postgres-binding.md). The portable polling path stays as the cross-dialect baseline.
- [ ] `JoinTriggerSource` wired on proxy-side per D1.
- [ ] `rtp-proxy-bungee` adapter (BungeeCord + Waterfall).
- [ ] **Acceptance**: same scenarios green on BungeeCord + Postgres.

### Phase 4 â€” Generic SQL + Hardening + Release

- [x] Generic-SQL (MySQL/MariaDB) polling binding. Delivered as a dialect branch of `SqlNetworkStateBinding` (Phase 2e-SQL slice, 2026-05-18) rather than a separate class per [rtp-proxy-ADR-009](../../rtp-proxy/docs/adr/rtp-proxy-ADR-009-generic-sql-binding.md); ADR-011 (Accepted 2026-05-18) `Supersedes` ADR-009. `Dialect.MYSQL` covers MySQL 8+ and aliases MariaDB; UPSERT + portable claim + reap + heartbeat are exercised by `SqlNetworkStateBindingH2Test` and route via `NetworkBindings.open`. A live MySQL/MariaDB container integration test remains an open follow-up but is not a separate plan box.
- [ ] Security audit: HMAC, replay protection, schema-version negotiation, kill switch verification.
- [x] `rtp test network` Shape A simulator (single-JVM): publishes N synthetic backend peers through the live `NetworkTransport`, asserts snapshot + subscriber fan-out, cleans up with `SHUTTING_DOWN` + far-past `lastSeenEpochMs` so the reaper drops them. Self-skips with `NOT-CONFIGURED` under REQ-RTP-NET-002 (no binding / no transport on the D3 slot). Lives in `rtp-plugin` and registers in `TestCmd` so Bukkit + Fabric (and future platforms) both see it. Extended 2026-05-20 with a reservation-token slice via a `heartbeat | tokens | all` mode selector (default `heartbeat`, back-compat preserved): `tokens` mode runs N `claim` -> `findReservation` (present) -> `release` (`ReleaseReason.TEST_PROBE`) -> `findReservation` (empty) round-trips against the live transport, then provisions a 1ms-TTL token and asserts `reapExpired` surfaces it within the observe window; audit row reports per-step microsecond timings and the transport class so operators see `(InMemoryNetworkStateBinding)` / `(SqlNetworkStateBinding)` / `(RedisNetworkStateBinding)` inline. Guarded by `NetworkSimulationTestJobTest` (6/6 - resolver cases, heartbeat round-trip, parseMode grammar lock-in, in-memory token round-trip with reap). Cross-process aggregator (`rtp test full network`) still deferred to Phase 4 once a real devstack proves out.
- [ ] `docs/admin/` install/config notes for proxy mode.
- [ ] `CHANGELOG.md` entries per phase under *Unreleased*.
- [ ] `LESSONS_LEARNED.md` â€” proxy-specific pitfalls.
- [ ] `COVERAGE_PLAN.md` â€” add proxy column.
- [ ] First public proxy-mode beta release (gated on full audit green).

### Deferred / Out of Scope (this plan)

- F2 â€” pre-warmed teleport queue across the network.
- F7 â€” region-availability discovery beyond static config.
- HTTP/gRPC transport.
- Forge / NeoForge proxies.

---

## Failure-Mode Policy (network-mode bootstrap)

Canonical policy for what "fail to enable network mode" looks like at startup, on both the proxy adapter (`rtp-proxy-velocity`, future `rtp-proxy-bungee`) and the backend bootstrap (`NetworkModeBootstrap` in `rtp-plugin`). Migrated 2026-05-20 from the now-deleted `PROPOSAL-velocity-redis-startup.md` §6; cited from code comments in `NetworkBindings.open` (Redis + SQL branches) and from `HmacVerifier.loadFromEnv` failure paths.

Definitions:

- `fail-fast` - refuse to enable network mode and log a configurable WARNING; do **not** crash the host.
- `degrade-to-disabled` - log the delta at WARNING and run as if `network.enabled:false` (no transport, no listeners, no scheduler); `/rtp reload` and `/rtp test` admin commands stay functional so operators can recover without a restart.
- `crash` - propagate to the host (Velocity / Bukkit), which unloads the plugin. Reserved for host-level scheduler refusal only.

| Class of failure | Policy | Rationale |
|------------------|--------|-----------|
| Malformed `network.yml`, unknown top-level key, schema version out of range | `degrade-to-disabled` | Operator-typo recovery path; matches REQ-RTP-PROXY-008. |
| `RTP_NET_SECRET` unset or `< 32` bytes after Base64 decode | `fail-fast` | A network mode without authentication is worse than no network mode (shared-tenant risk per [rtp-proxy-ADR-010](../../rtp-proxy/docs/adr/rtp-proxy-ADR-010-security-hardening.md)). Refuse to enable; log; do not crash. |
| `transport.type: redis` but `transport.url` (or `transport.redis.*`) empty / unparseable | `fail-fast` | Operator error; fail loudly. |
| Initial Redis connect times out at transport open | `degrade-to-disabled` | Redis can be in cold-boot; bringing the host down would amplify a partial outage. Surface via `/rtp test network`; operator runs `/rtp reload` once Redis is up. |
| Lua `SCRIPT LOAD` fails or SHA1 sidecar mismatch on `claim.lua` / `release.lua` / `reap.lua` | `degrade-to-disabled` for transient `SCRIPT LOAD`; **build-time defect** for sidecar mismatch (refuse to enable, no recovery) | Sidecar mismatch means the shipped script does not match the pinned SHA - a binary defect, not a runtime condition. |
| PUBSUB subscribe fails at transport open | `degrade-to-disabled` | Same posture as connect failure. |
| SQL transport DDL / migration failure on `ALTER TABLE ... ADD COLUMN hmac` | `degrade-to-disabled` | Schema rollouts are operator-coordinated; the pre-A3 path still runs. |
| Host scheduler refuses to schedule the heartbeat task | `crash` | Host-level failure; let Velocity / Bukkit surface it. |
| `network.killSwitch: true` observed at startup or mid-run | Operational state, not a startup failure | Plugin stays loaded, returns `Failed(reason=KILL_SWITCH)` for every request per ADR-010. |

**Headline rule.** An unconfigured / mistyped / unreachable network deployment shall never bring the host down. Authentication failure (missing or short secret) is the **only** `fail-fast` for the security-critical path; every other transient or operator-error failure shall `degrade-to-disabled` so the host can serve single-server `/rtp` while the operator recovers.

---

## Risk & Pitfall Inventory

- **Thread-context map for cross-wire callbacks** â€” the SPI must explicitly document on which thread each callback fires:
  - Transport publisher writes â€” always async (`runTaskTimerAsynchronously`).
  - Transport listener delivery â€” netty / Lettuce / Postgres-driver thread; consumers must hop via `RTP.scheduler.runTaskTimer` (or the entity scheduler on Folia) before touching world or player state.
  - Selector `choose()` â€” invoked by the dispatcher; pure-function contract; safe to call from any thread.
  - Reservation reaper â€” always async; releases `MemoryTracker` entries.
  - HMAC verify â€” same thread as the inbound packet; cheap (`Mac.doFinal`); never blocks.
- **Folia + proxy interaction** â€” proxy reply lands on a netty thread on the backend; consumers must hop to the right region scheduler before touching the player (`Bukkit.isOwnedByCurrentRegion`). Same discipline as `AGENTS.md > Folia Threading`, just over the wire.
- **Reservation tokens are a distributed-systems problem** â€” TTL, idempotency, replay protection are easy to get subtly wrong. Treat the regression suite as a Phase 2 acceptance gate, not a "nice to have".
- **Velocity vs. BungeeCord API divergence** â€” too large to share a runtime; share only the SPI. Don't water down the Velocity design to match Bungee.
- **Version skew** â€” backend running RTP `X` talking to a proxy plugin running `X+1`. Requires `schemaVersion` negotiation on first packet, with graceful degrade ("falls back to single-server behaviour").
- **Security** â€” Redis (and any RESP-compatible drop-in such as DragonflyDB / KeyDB) especially: any other plugin sharing the same store can spoof requests. HMAC + a kill switch in config are mandatory. D4 must be resolved before Phase 2 ships.
- **Existing single-server tests must not regress** â€” REQ-RTP-NET-002 makes this explicit; the Phase 1 no-op test is the gate.
- **Plugin-message transport is dev-only** (D2) â€” must be loudly documented and emit a startup warning when selected outside a dev profile.

---

## Sufficiency Audit (2026-05-01)

This plan has been reviewed for implementer-sufficiency against `AGENTS.md`, `RULES.md`, and the existing S-001â€¦S-007 prohibitions. The items below were identified as gaps and either filled in this revision or explicitly deferred:

- **Reservation token state machine** â€” explicit ownership matrix added (who initiates each transition, atomicity primitive, failure handling, proxy-restart reanimation).
- **Thread-context map** â€” added to *Risk & Pitfall Inventory* so each callback's expected thread is documented.
- **Wire-protocol envelope** â€” captured as REQ-RTP-NET-009 (schemaVersion + HMAC). Final wire format (CBOR / JSON / length-prefixed bytes) deferred to ADR-036.
- **Exactly-once claim semantics** â€” captured as REQ-RTP-NET-012.
- **Multi-DB compatibility** â€” captured as REQ-RTP-NET-013 (any of H2/SQLite/MySQL/PostgreSQL must be acceptable for backend-side telemetry).
- **Required regression coverage** â€” enumerated under *Reservation Tokens* (replay, TTL, orphan, reanimation, schema-version, HMAC reject) so the Phase 2 acceptance suite is unambiguous.
- **Test fixture provenance** â€” the v1 default `loadBalancer` block is now explicitly the test fixture (no separate fixture file).

**Items deliberately left open** (tracked in *Open Items / Follow-Ups* below):

- Wire-format choice (CBOR vs. JSON vs. binary) â€” ADR-036.
- Postgres-vs-Redis(/Dragonfly) benchmark â€” post-implementation.
- `commands-api` proxy surface concrete shapes â€” early Phase 1 design.
- HMAC distribution beyond env-var â€” deferred research.
- Player-count weighting â€” awaits Phase 2 live-player evidence.

---

## Open Items / Follow-Ups

- **D4 â€” HMAC key distribution beyond env var** â€” v1 ships env-var (`RTP_NET_SECRET`). Research alternatives (config file with restrictive perms, per-backend keypair, OS keyring) before public release; not a Phase 2 blocker.
- **Shared `recentPicks` across proxies** â€” v1 keeps `recentPicks` per-proxy and relies on backend telemetry to dampen inter-heartbeat stampedes (see *Hot-Spot Avoidance Across Proxies*). A v2 opt-in mode that writes `recentPicks` bumps to the network-state member would close the intra-heartbeat window at the cost of one round-trip per pick. Revisit only if Phase 2+ devstack data shows multi-proxy stampedes that telemetry feedback fails to absorb.
- **Runtime-mutable proxy trigger/load-balancer config replication** â€” v1 is file-and-restart on every proxy. A Phase 3 hardening item is to read the optional `ConfigVersionTable` row on each `/rtp` request so a single edit propagates across the proxy fleet without a restart sweep.
- **Proxy telemetry table (`proxy_state`)** â€” sketched under *Multi-Proxy Deployment* but not yet table-level specified the way `backend_state` is. Concrete column list lands in ADR-036 alongside the backend table; expected fields: `proxyId`, `schemaVersion`, `rtpVersion`, `proxyPlatform` (`velocity` | `bungee` | `waterfall`), `proxyState`, `connectedPlayers`, `lastSeenEpochMs`. No performance fields â€” proxies are not selection candidates.
- **Postgres-vs-Redis comparative benchmark** â€” *to be performed after each transport's individual implementation and testing has stabilised*. Not a prerequisite for ADR-036 ratification (their selection rationale stands on responsiveness characteristics); benchmark drives ops guidance and the eventual `LESSONS_LEARNED.md` entry. **DragonflyDB is a third row in the same benchmark matrix** â€” same `RedisNetworkStateBinding`, different server â€” to give operators evidence-based guidance on when its multi-threaded single-node design beats vanilla Redis (typically: high reservation-claim contention, single large host) and when it doesn't (typically: small fleets, where the difference is in the noise).
- **`commands-api` proxy-side surface** â€” **early TODO for Phase 1 design**. Concrete shapes needed: `ProxySender` (adapts Velocity `CommandSource` and Bungee `CommandSender`), `NetworkAwareCommand` mixin (routes execution through `RtpDispatcher`), tab-completion routing across the transport. Resolve before any proxy adapter module is opened.
- **Player-count weighting** â€” published in telemetry; selector weight stays `0` until live-player testing on the Phase 2 devstack provides evidence either way. No design action required before Phase 2.
- **`rtp.unqueued` bypass implementation** â€” low priority; expected use is rare. Acceptable to defer past Phase 2 acceptance.
- **Folia per-region TPS aggregation** â€” owned by [`METRICS_PLAN.md`](METRICS_PLAN.md); this plan consumes whatever the metrics plan publishes.

---

## Lobby Load Balancing v1: Backend-Side No-Arg `/rtp` on Lobbies (Slices I + J, shipped)

Landed in beta.4. Documents the **A** half of lobby load balancing: a player on a lobby (a backend with 0 local regions, advertising `acceptingRequests=false` and an empty `regionsAvailable=[]`) typing bare `/rtp` is dispatched cross-server to the peer backend with the largest `keptCount`. The **B** half (proxy-side `ServerPreConnectEvent` lobby picker on login, which decides *which lobby* a fresh connection lands on) is a separate slot, documented under *Forward Concept: Proxy-Side Join Routing* below, and is sequenced after Phase 2 acceptance + Phase 3 row `D1`.

**Trigger.** `network.yml::routing.lobbyMode: true`. Read twice during boot: once via `NetworkModeBootstrap.readLobbyModeEarly(File)` so the gates in `Region` and the sampler are armed before any `/rtp` can be issued, and once during `NetworkModeBootstrap.boot()` so the same value threads into `BukkitBackendStateSampler` (forces `acceptingRequests=false` and `regionsAvailable=Set.of()` regardless of locally configured regions) and `BukkitNetworkCommandHook` (enables the no-arg interception path). The two reads share the same `RtpYamlConfig.load(networkYml) -> getConfigurationSection("routing") -> getBoolean("lobbyMode", false)` chain; there is no separate `NetworkKeys` enum and no `ConfigParser` / `/rtp config` participation, by design (the value is baked into long-lived field references at boot and is not runtime-mutable).

**Selector v1 (most-kept).** `PeerRegionRegistry.pickMostKept()` returns the peer backend + region with the largest `keptCount`, with:

- Self-exclusion (the lobby never picks itself; an empty `regionsAvailable` would already disqualify it, but the explicit guard is defensive against operator misconfiguration where lobby mode is enabled while regions are still configured).
- Kill-switch filtering (peers with `killSwitch=true` per [rtp-proxy-ADR-010](../../rtp-proxy/docs/adr/rtp-proxy-ADR-010-security-hardening.md) are excluded).
- Deterministic tiebreak (lexicographic `serverId` then `region`) so two lobby JVMs picking against the same snapshot agree.
- Legacy-peer fall-through (peers that publish only the pre-Slice-I `regions: Set<String>` field with no per-region kept counts are not synthesised into a destination by this path; they participate only when the player names them explicitly via `rtp region=<server>:<region>`).
- Empty-snapshot and no-peers cases return `Optional.empty()`; the hook responds with the configurable `networkRegionUnavailable` message (REQ-RTP-F-013) rather than swallowing the failure (S-004).

**Selector v2 (deferred).** A dynamic weighted-average per-server heuristic will replace `pickMostKept` for the no-arg path. v1 lives behind one named call site (`peerRegionRegistry.pickMostKept()` in `BukkitNetworkCommandHook`), so v2 lands as either (a) an additional method on `PeerRegionRegistry` plus a one-line swap, or (b) a `LobbyBackendSelector` SPI sibling to the existing `BackendSelector` interface, depending on how much shared logic emerges. Input signals already published in `BackendHeartbeat` and available to v2 without a wire-protocol bump: `keptCount`, `unkeptCount`, `playerCount`, `maxPlayers`, `mspt` (when published per `METRICS_PLAN.md`), `acceptingRequests`. No design action required before live evidence on Phase 2 + beta.4 devstack.

**What does not change with lobby mode on.** Explicitly-targeted `rtp region=<server>:<region>` requests still route through the existing `RegionParameter` validator (peer-aware via `PeerRegionRegistry.isReachableHardPin`), bypass `pickMostKept`, and dispatch to the named destination unchanged. Tab-completion still surfaces `peerEntries()` so operators can discover available remote regions. The `JoinTriggerSource` redeem path on the destination backend is unchanged: it consumes whatever `ReservationToken` was claimed (via `pickMostKept` or via explicit name), runs `/rtp` against the local kept/unkept cache, and releases the token.

**Coverage.** `LobbyModeTest` (10 cases on `pickMostKept`: largest-count, self-exclusion, kill-switch exclusion, no-peers, null-snapshot, deterministic tiebreak, legacy-peer fall-through, no-arg synthesises `crossServer` to most-kept, explicit region still routes via `parseRegionArgQualified`, ctor null-registry rejection; plus 2 sampler cases). `LobbyModeEarlyReadTest` (3 cases: absent block, explicit false, true).

**Failure modes.** Per *Failure-Mode Policy* above: a malformed `network.yml::routing.lobbyMode` value degrades-to-disabled (lobby mode off); empty `peerEntries()` after a `pickMostKept` returns the configurable `networkRegionUnavailable` (REQ-RTP-S-007); a kill-switched-only peer set is the same as empty.

---

## Forward Concept: Proxy-Side Join Routing via `rtp.onevent.*` (Lobby Load Balancing)

Not in scope for Phases 1-3 and not on the current critical path. Recorded here so the design is anchored when in-game `/rtp` (Phase 2) and the proxy-side `JoinTriggerSource` slot (Phase 3, row `D1`) have both landed.

**Concept.** Extend the existing backend-side join-RTP gate (the `rtp.onevent.firstjoin` and `rtp.onevent.join` permissions, today consumed by `OnEventTeleports#onPlayerJoin` against the per-player `loginLocations` reserve, see [ADR-023](../adr/ADR-023-login-reserve-cache.md)) into a **proxy-side trigger**: when a player connects to the proxy and would normally be routed to a default lobby, the proxy instead issues an `RtpRequest` through `DefaultRtpDispatcher`, lets `BackendSelector` pick the lowest-loaded backend from the live `NetworkSnapshot`, claims a `ReservationToken` there, and uses the resulting `serverId` as the `ServerPreConnectEvent` target. The `JoinTriggerSource` on the destination backend redeems the token on arrival and runs `/rtp` against the local kept/unkept cache. Net effect: lobby-to-backend balancing and join-time `/rtp` are the same operation, gated by the same permission nodes that already exist.

**Permission semantics carry over.** The proxy-side trigger shall respect the same two permission nodes already shipped in `plugin.yml`:

- `rtp.onevent.firstjoin` - fire for the player's first connection to the network (proxy-observed, not per-backend `hasPlayedBefore`).
- `rtp.onevent.join` - fire on every subsequent connection.

Both nodes default to the same values as today (`firstjoin` true, `join` opt-in). A player without the relevant node falls through to the proxy's normal initial-server policy unchanged. This preserves operator muscle memory: the lever that today says "auto-RTP this player on join" becomes the lever that says "auto-RTP this player on join, **and** let RTP pick which backend absorbs the load".

**Permission resolution on the proxy.** Proxy-side permission lookup is a known gap (Velocity does not ship a LuckPerms-equivalent by default; BungeeCord is in a similar spot). Options to evaluate when this lands:

1. Read the player's permission set from a network-state row populated by each backend's `LuckPerms` (or equivalent) on join elsewhere in the network. Stale-by-one-session but cheap; matches the existing `backend_state` telemetry pattern.
2. Defer the decision to the destination backend after a speculative selector pick: claim, route, then have the backend's `JoinTriggerSource` re-check the permission against its local `LuckPerms` and either redeem or release the token. Adds one transport round-trip on permission-miss but keeps the proxy stateless.
3. Operator-provided proxy-side permission provider via `commands-api`'s proxy surface (`ProxySender` / `NetworkAwareCommand`, see *Open Items / Follow-Ups*). Most flexible; most work.

Choice is deferred until the proxy-side `commands-api` surface is concrete (an existing open item) and the Phase 2 acceptance devstack has live evidence on the permission-resolution latency budget.

**What does not change.**

- `loginLocations` (the backend-side login reserve from ADR-023) remains a backend-local cache; the proxy does not read or write it. The reserve still pre-warms coordinates per *backend* per `rtp.onevent.firstjoin`/`join` slots; the proxy's role is only to pick **which** backend's reserve gets hit.
- `JoinTriggerSource` stays the single backend-side consumer of redemption (`REDEEMED` -> `/rtp` against the local kept/unkept cache). The proxy-side trigger introduced here is a **producer** that publishes the reservation; it does not duplicate the backend redeem logic.
- `BackendSelector.choose` contract (pure function of `(RtpRequest, NetworkSnapshot)`, no I/O) is unchanged. Lobby balancing reuses the existing weighted-average selector or any operator-provided implementation without an SPI bump.

**Sequencing.** Land after Phase 2 acceptance (2x Velocity + 2x Paper devstack green for in-game `/rtp`) and after Phase 3 row `D1` (`JoinTriggerSource` wired on proxy-side). At that point this concept is additive: a new `JoinTriggerSource` producer next to the existing `CommandTriggerSource`, plus a small `ServerPreConnectEvent` listener on the Velocity adapter (and the BungeeCord equivalent once Phase 3 lands `rtp-proxy-bungee`).

**Lobby-less topology (primary motivating case).** The concept above is written for networks where a default lobby exists and the proxy-side trigger steals the join away from it. The more interesting deployment, and the one this note is primarily aimed at, is a **lobby-less network**: no hub server, multiple gameplay backends, every fresh login must land somewhere playable. In that topology there is no "normal initial-server policy" to fall through to, so the proxy-side trigger is not an *override* of lobby routing - it **is** the routing decision. Specifics:

- The selector pick is mandatory, not opportunistic. `BackendSelector.choose` returning `Optional.empty()` cannot fall through to a lobby because there is none; the proxy shall instead apply the operator-configured `loadBalancer.onNoCandidate` policy (queue the player on the network wait queue per REQ-RTP-NET-008, or disconnect with a configurable `messages.yml` string per REQ-RTP-F-013 and S-007). Silently dropping the connection is a S-004 violation.
- `rtp.onevent.firstjoin` / `rtp.onevent.join` still gate whether the join *triggers an `RtpRequest`*, but the backend choice happens regardless. A player without either permission still needs a backend; in lobby-less mode the proxy picks one via `BackendSelector` and routes them there **without** claiming a `ReservationToken`, so the destination backend's `JoinTriggerSource` finds no token to redeem and the player spawns at that backend's normal join point. This keeps load balancing decoupled from the auto-RTP opt-in.
- The reservation token is therefore only allocated for permission-holders. Non-permission joins are load-balanced by the same `BackendSelector` call but skip the claim path entirely - one less round-trip, no token TTL to reap, no `JoinTriggerSource` redeem on arrival.
- Operators running this topology should expect the proxy to be the load-balancing bottleneck under join storms (server-list ping spike, restart reconnect). The existing `loadBalancer.recentPicks` hot-spot dampener (see *Hot-Spot Avoidance Across Proxies*) is the relevant lever; the v2 cross-proxy `recentPicks` mode in *Open Items / Follow-Ups* becomes more valuable here than in the lobby-fronted case.

**Out of scope for this note.** Per-region join balancing (which backend hosts which biome / claim-plugin region), join-time queue-vs-fail policy when no backend qualifies, and the proxy telemetry table column list that would feed a richer selector. All three are tracked elsewhere (region filters in `WeightedAverageBackendSelector`, *Failure-Mode Policy* above, *Open Items / Follow-Ups* `proxy_state` row).

---

*Self-update note*: any durable engineering lesson discovered while executing this plan goes to [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md); incidental potential bugs go to [`POTENTIAL_BUGS.md`](POTENTIAL_BUGS.md); architecturally significant decisions get their own ADR. Do not bloat this file with implementation lore â€” it is a roadmap, not an encyclopaedia.

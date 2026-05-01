# Multi-Server (Proxy) Support Roadmap

This document outlines the plan for RTP's multi-server (proxy / network) expansion. It is **distinct from** [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md): that plan covers running on additional Minecraft server flavours (Spigot/Paper/Folia/Fabric); *this* plan covers coordinating RTP across **multiple concurrent backend servers** sitting behind a proxy (Velocity, BungeeCord, Waterfall).

> Status: **Draft — Phase 0 (Scope Unlock) not yet started.** No code changes have been made; no ADR has been accepted. This document is gated by Rule D-005 (Propose Before Implementation, see [`AGENTS.md`](../../AGENTS.md)).

> Cross-references: [ADR-022 (Fabric in scope)](../adr/ADR-022-fabric-platform-in-scope.md) is **orthogonal** to this plan and is **not** superseded. A new ADR-025 (multi-server proxy support) is required before Phase 1 work begins.

---

## Headline Feature

**Cross-server load-balanced RTP** — a player request originating anywhere on the network is dispatched to the most appropriate backend, the destination is generated using that backend's existing async pipeline, and the player is transferred. The trigger source (command, server-join, addon event) is **configurable** so operators decide whether to call RTP via `/rtp` or pass the player through on join (mirroring the existing Bukkit join-event hook).

### Non-Goals (v1)

- No proxy-side chunk logic, world data, or entity manipulation. The proxy never owns world state.
- No replacement of the existing single-server pipeline. With `network.enabled: false`, behaviour is byte-identical to today.
- No Forge / NeoForge proxy support. (Out of scope until Fabric platform stabilises — see `MULTI_PLATFORM_PLAN.md` Phase 4.)
- No cross-version protocol breakage without a `schemaVersion` bump.

---

## Decisions Recorded (from brainstorm 2026-05-01)

These answers are taken from the issue thread that produced this document. They lock in scope; any change requires explicit user approval per Rule D-005.

| # | Question | Decision |
|---|----------|----------|
| D1 | Network-mode default world resolution on join | **Proxy-side config.** `JoinTriggerSource` reads region/world mappings from the proxy plugin's config, not per-backend. |
| D2 | Reservation persistence on proxy restart | **Required.** Transport must be durable. `plugin-message` is therefore a **degraded / dev-only** mode and not supported in production. |
| D3 | Network state storage location | **Reuse `AbstractSQLDatabaseAccessor` where possible.** If a separate `AbstractNetworkStateAccessor` proves necessary, it must live **adjacent to** or **as a member of** the existing accessor — not a parallel hierarchy. |
| D4 | HMAC key distribution | **Deferred.** User flagged this as needing more investigation before deciding between env-var, config-file, or per-backend keypair. Treat as an open item in Phase 2 design. |

Additional locked-in decisions:

- **Proxy primary**: Velocity. **Secondary**: BungeeCord/Waterfall. Both eventually required.
- **Transport preference order**: Redis (most responsive), Postgres (co-equal candidate, needs analysis), generic SQL (MySQL/MariaDB) for universal fallback, `plugin-message` for dev only.
- **Commands**: extend `commands-api` rather than fork. Brigadier bridge work (Step G of `MULTI_PLATFORM_PLAN.md`) carries over for Velocity.

---

## Architecture Overview

```
                              ┌────────────────────────────┐
                              │  rtp-proxy-velocity (1°)   │
   /rtp / on-join trigger ───▶│  rtp-proxy-bungee   (2°)   │──▶ ServerPreConnectEvent
                              │     uses rtp-proxy-common  │   (rewrite target)
                              └─────────────┬──────────────┘
                                            │ NetworkTransport SPI
                              ┌─────────────┴──────────────┐
                              │  Durable shared state      │
                              │   Redis  |  Postgres  |    │
                              │   generic SQL (poll)       │
                              │  (via AbstractSQLDatabase- │
                              │   Accessor + adjacent      │
                              │   network-state member)    │
                              └─────────────┬──────────────┘
                                            │
   ┌────────────────────────┬───────────────┼───────────────┬────────────────────────┐
   ▼                        ▼               ▼               ▼                        ▼
 backend-1               backend-2       backend-3       backend-N            (each runs
 rtp-spigot/paper/       …               …               …                   the existing
 folia/fabric                                                                 single-server
 + NetworkBridge                                                              pipeline,
   (rtp-core, optional)                                                       unchanged)
```

Module shape proposed (Phase 0 will formalise via ADR-025):

```
rtp-proxy/
├── rtp-proxy-common/      # SPI, dispatcher, BackendSelector, transport interface — no proxy imports
├── rtp-proxy-velocity/    # Velocity adapter — primary
└── rtp-proxy-bungee/      # BungeeCord/Waterfall adapter — secondary, lands Phase 3
```

Backend-side glue (`NetworkBridge`) lives in **`rtp-core`** as an optional, default-disabled subsystem. It contains zero proxy-platform imports; transport implementations are loaded reflectively or via service-loader.

---

## Trigger Abstraction — `RtpTriggerSource`

One internal entry point, many configurable producers. Defined in `rtp-core`:

```java
interface RtpTriggerSource {
    String id();                 // "command", "join", "portal", "addon-foo"
    boolean enabled();           // from config
    void register(RtpDispatcher dispatcher);
}
```

Shipped sources, each toggled in `network.yml`:

- `CommandTriggerSource` — `/rtp` (existing path, routed through the dispatcher when network mode is on).
- `JoinTriggerSource` — generalised join hook so Velocity proxy-side join *and* backend-side join both flow through the same dispatcher. **Per D1, region/world mapping is read proxy-side.**
- `EventTriggerSource` — fires on a named addon-facing event so third parties wire their own triggers without forking.

Adding new triggers later is config-only.

---

## Load Balancer — `BackendSelector`

The new core component. Lives in `rtp-core` (no platform deps).

```java
interface BackendSelector {
    CompletableFuture<BackendChoice> choose(RtpRequest req, NetworkSnapshot snap);
}
```

All strategies must be **pure functions of `NetworkSnapshot`** — no I/O during `choose()`. This preserves S-005 spirit (no blocking on a tick or netty thread).

### Load-Balancing Heuristics — *PLACEHOLDER*

> ⚠️ **This section is intentionally unfinished.** The concrete heuristics, weights, default strategy, and tuning knobs require user input and benchmarking before they can be specified. Do **not** implement against this section until it has been filled out and approved per Rule D-005.

Open items to resolve here:

- **Strategy catalogue** — which of `ROUND_ROBIN` / `LEAST_LOADED` / `LOWEST_LATENCY` / `WEIGHTED` / `STICKY_REGION` / `COMPOSITE` ship in v1, and which (if any) are deferred.
- **Default strategy** — what ships out-of-the-box when an admin enables network mode without configuring a strategy.
- **Inputs to `NetworkSnapshot`** — confirm the heartbeat payload (`playerCount`, `softCap`, `queueDepth`, `pendingTeleports`, `avgPipelineMs`, `regionsAvailable[]`, …). Add or drop fields based on which heuristics are kept.
- **Stale-backend filter** — multiplier on `heartbeatInterval` after which a backend is excluded from selection (current straw-man: `3×`, mirroring the stale-chunk guard pattern from [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md)).
- **Weight tuning** — if `COMPOSITE` is shipped, the default weight vector and the rationale for each component.
- **Region-affinity rules** — how `STICKY_REGION` interacts with player permissions, region whitelists/blacklists, and the existing `RegionQueueManager`.
- **Tie-breaking** — deterministic order on equal scores (matters for tests).
- **Hot-spot avoidance** — short-window memory of recent picks so a single low-load backend isn't stampeded between heartbeats.
- **Failure / fallback chain** — what happens when the chosen backend rejects, times out, or its heartbeat goes stale mid-flight.
- **Configuration surface** — exact `network.yml` keys the heuristics expose (placeholder appears in the Config Surface section below).

Until this section is filled out, Phase 1 work may stub `BackendSelector` with `ROUND_ROBIN` only, **for tests only**, and must not be released.

---

## Backend Telemetry Publication *(NEW, 2026-05-01)*

Each backend running RTP **shall publish to its configured database** a periodic record describing two distinct concerns:

1. **Plugin state — availability.** Is this backend usable as an RTP destination *right now*? This is a binary-plus-context signal: the backend is up, RTP is loaded, the pipeline is responsive, and the requested regions exist.
2. **Server state — performance cost.** How expensive is it to serve another teleport from this backend *right now*? This is a continuous signal feeding the load balancer (see *Load-Balancing Heuristics — PLACEHOLDER*).

These are intentionally separated so a healthy-but-overloaded backend can be filtered out of selection (availability=OK, cost=high) distinctly from a stale or degraded backend (availability=stale, cost=N/A).

> Per D3, this telemetry lives **adjacent to** or **as a member of** `AbstractSQLDatabaseAccessor`, not in a parallel hierarchy. Each backend writes to **its own configured database** — the same accessor that already persists cooldowns. If transport is Redis, the same payload is also published to a pub/sub channel for push semantics; the SQL row is the durable record.

### What to publish — Availability fields

- `serverId` — unique, from `network.yml`.
- `schemaVersion` — for cross-version negotiation (REQ-RTP-NET protocol).
- `rtpVersion` — plugin version string.
- `platform` — `spigot` | `paper` | `folia` | `fabric`.
- `mcVersion` — Minecraft version string.
- `pluginState` — enum: `STARTING` | `READY` | `DEGRADED` | `SHUTTING_DOWN`.
- `regionsAvailable[]` — names of regions this backend can currently serve (drawn from `regions.yml` minus any disabled or in-error regions).
- `worldsLoaded[]` — world keys the backend has loaded (so the selector can avoid asking a backend to RTP into a world it hasn't loaded yet).
- `acceptingRequests` — boolean kill switch: ops can flip a backend out of rotation without stopping the server (mirrors `/rtp pause`-style semantics).
- `lastSeenEpochMs` — write timestamp; the staleness filter is computed against this.

### What to publish — Performance / cost fields

- `playerCount` / `softCap` — current population vs. configured comfortable capacity.
- `tps1m` / `tps5m` / `tps15m` — rolling tick-rate. (Folia: per-region TPS aggregated; document the aggregation choice in ADR-025.)
- `mspt` — average milliseconds per tick over the last sample window.
- `queueDepth` — pending entries in `RegionQueueManager`.
- `pendingTeleports` — in-flight `TeleportPipelineTask` count.
- `avgPipelineMs` — rolling mean pipeline duration over the last N completed teleports.
- `chunkLoadBacklog` — count of async chunk-load futures not yet completed (S-005-relevant signal).
- `memoryTrackerEntries` — count of registered allocations (early warning of leaks; surfaces the same data `rtp test full` already reports).
- `heapUsedMb` / `heapMaxMb` — coarse JVM memory pressure.
- `databaseLatencyMs` — last write/read round-trip to this backend's own DB (cheap and indicative; if the backend can't talk to its own DB, it can't reliably serve reservations either).

All counters are **snapshots**, not deltas — the consumer (selector) does the math. This keeps the publisher trivially idempotent.

### Cadence and write strategy

- Default heartbeat interval: **1 second** (configurable via `network.heartbeat.intervalTicks`, default `20`). Aligns with the heartbeat/staleness pattern already drafted under `NetworkSnapshot`.
- Writes happen on `RTP.scheduler.runTaskTimerAsynchronously` — **never on a tick / region thread** (REQ-RTP-NET-004, S-005 spirit).
- Single-row UPSERT keyed on `serverId` (no historical retention by default — keeps the table tiny). A separate optional `BackendHeartbeatHistoryTable` is a Phase 4 hardening item if ops want longitudinal data.
- On Folia, the publisher is async-only; per-region TPS is sampled via the platform's `RegionScheduler` API and folded into the row before write.
- On graceful shutdown, the backend writes a final row with `pluginState=SHUTTING_DOWN` and `acceptingRequests=false` so the selector drops it immediately rather than waiting for the staleness window. Best-effort — a hard crash relies on the staleness filter.
- Failure to publish must **not** abort RTP operation locally; it shall log under S-004 attribution rules and the selector treats the backend as stale until publication resumes.

### Table sketch (subject to ADR-025)

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

- The publisher (`BackendStatePublisher`) lives in **`rtp-core`** as part of the `NetworkBridge` optional subsystem. Default-disabled when `network.enabled: false` — REQ-RTP-NET-005 must remain green.
- The accessor member (per D3) exposes `writeBackendState(BackendStateRow)` and `readNetworkSnapshot()`. Concrete bindings (Redis / Postgres / generic SQL / in-memory) implement both.
- No platform imports in the publisher — TPS/MSPT/heap/region data come through `RTP.serverAccessor` extensions (a small additive surface to spec in ADR-025; the *April 2026 gap analysis* in `MULTI_PLATFORM_PLAN.md` does **not** cover these new methods).

### Open items folded into existing placeholders

- The **Load-Balancing Heuristics — PLACEHOLDER** section is the single place that decides *how* these fields are weighted. The publisher commits to providing them; the selector decides which it uses.
- Whether `tps`/`mspt` is required on Spigot (which lacks a public TPS API on older versions) is a Phase 1 design item; on Folia and Paper there are first-class APIs.
- Per-region TPS aggregation strategy on Folia (max / mean / weighted-by-player-count) — to be decided in ADR-025.

---

## Storage — Reuse `AbstractSQLDatabaseAccessor` (per D3)

Per D3, the existing `AbstractSQLDatabaseAccessor` is the primary storage abstraction. Network-shared state (heartbeats, reservation tokens, network cooldowns, config versions) lives **as a member of, or adjacent to**, that accessor — not in a parallel tree.

Sketch (subject to ADR-025 ratification):

```
AbstractSQLDatabaseAccessor                        (existing — per-backend persistence)
  ├── PlayerCooldownTable, …                       (existing)
  └── networkState  : NetworkStateMember           (NEW — adjacent member, optional)
        ├── BackendHeartbeatTable
        ├── ReservationTokenTable
        ├── NetworkCooldownTable
        └── ConfigVersionTable
```

Transport implementations bind to the same accessor:

- `RedisNetworkStateBinding` — Lettuce, async, optional dependency. Pub/sub for heartbeats + reservation events. Preferred for responsiveness.
- `PostgresNetworkStateBinding` — `LISTEN/NOTIFY` for push semantics; `SELECT … FOR UPDATE SKIP LOCKED` for race-free reservation claim. Shares HikariCP with the existing accessor — zero new pool surface.
- `GenericSqlNetworkStateBinding` — MySQL/MariaDB with polling fallback. Universal but higher latency.
- `InMemoryNetworkStateBinding` — single-JVM tests and the no-op default when `network.enabled: false`.

### Postgres Analysis Items (for ADR-025)

- `LISTEN/NOTIFY` viability and payload-size limits in our heartbeat cadence.
- `SKIP LOCKED` race characteristics under contention from N backends.
- `JSONB` vs. normalised columns for the snapshot blob.
- HikariCP reuse vs. dedicated pool.

These items must be answered in ADR-025 before Phase 3.

---

## Reservation Tokens

Single shared keyspace owned by the network-state member of the accessor:

- Fields: `token (UUID PK)`, `playerUuid`, `targetServerId`, `worldKey`, `x/y/z/yaw/pitch`, `issuedAt`, `expiresAt`, `state ∈ {PENDING, CLAIMED, CONSUMED, EXPIRED}`.
- **Issued** by the destination backend after its pipeline produces a safe location.
- **Claimed** atomically when the proxy commits to a transfer.
- **Consumed** by the destination backend on player join (PaperMC `PlayerJoinEvent` / Fabric server-join event / equivalent). Idempotent: `UPDATE … WHERE state='CLAIMED'` returning row-count; >1 ⇒ replay, refuse and log under S-004.
- **Reaped** by a scheduled `RTP.scheduler.runTaskTimerAsynchronously` on each backend, which releases `MemoryTracker` entries on local rows it owns.
- TTL: configurable, default 30s.

Per D2, tokens **must survive a proxy restart**, which is why `plugin-message` transport is dev-only — it has no durability guarantee.

A dedicated regression suite analogous to `ReqRtpS004NullChunkAttributionTest` is required before Phase 2 acceptance.

---

## `commands-api` Extension

- `NetworkAwareCommand` mixin — when present, execution is routed through `RtpDispatcher` instead of run locally. Single-server commands stay untouched.
- `ProxySender` abstraction in `commands-api` — adapts both Velocity's `CommandSource` and BungeeCord's `CommandSender` so `/rtp` works identically whether issued on a backend or on the proxy itself.
- Tab-completion routing: proxy queries any backend on the transport, merges results, applies a local cache TTL.
- Brigadier bridge (`BrigadierCommandAdapter` / `BrigadierBridgeContext` from Step G of `MULTI_PLATFORM_PLAN.md`) carries over for Velocity, which uses Brigadier internally.

---

## Config Surface (`network.yml` — straw-man)

```yaml
network:
  enabled: false                  # hard kill switch, default off → zero behavioural change
  serverId: "survival-1"          # unique per backend
  schemaVersion: 1

  transport:
    type: redis                   # redis | postgres | sql | plugin-message (dev only, per D2)
    redis:    { host, port, password, channelPrefix }
    postgres: { jdbcUrl, user, password, listenChannel }
    sql:      { jdbcUrl, user, password, pollIntervalMs }
    pluginMessage: { channel: "rtp:net" }   # NOT for production (D2)

  loadBalancer:
    # See "Load-Balancing Heuristics — PLACEHOLDER" above.
    # Concrete keys deferred until that section is filled out.
    strategy: TBD

  triggers:
    command: { enabled: true }
    join:    { enabled: false }   # region/world map sourced PROXY-SIDE per D1
    event:   { enabled: false }

  reservation:
    ttlSeconds: 30
    reaperIntervalTicks: 40

  security:
    sharedSecret: TBD             # per D4 — distribution mechanism not yet decided
```

Acceptance contract: with `network.enabled: false`, all single-server tests must remain byte-identical green. A dedicated no-op test is required.

---

## Requirements (Stubs — to be authored in REQUIREMENTS.md as part of Phase 0)

| ID | Statement |
|----|-----------|
| `REQ-RTP-NET-001` | Cross-server teleport shall preserve S-001 through S-006 end-to-end. |
| `REQ-RTP-NET-002` | Reservation tokens shall expire deterministically; no orphaned `MemoryTracker` entries shall remain after expiry. |
| `REQ-RTP-NET-003` | All proxy-mediated user-facing messages shall route through `messages.yml` (extends REQ-RTP-F-013). |
| `REQ-RTP-NET-004` | Network transport shall not perform synchronous I/O on a tick or netty thread. |
| `REQ-RTP-NET-005` | When `network.enabled` is false, behaviour shall be byte-identical to single-server operation. |

Authoring rules: see [`docs/dev/RULES.md`](RULES.md) and the *Requirement Documentation Rules* section of [`AGENTS.md`](../../AGENTS.md). The statements above are placeholders — final wording must use `shall` / `shall not`, no implementation actions.

---

## Phased Roadmap

Mirrors the structure of [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) so contributors can navigate either plan with the same mental model.

### Phase 0 — Scope Unlock *(docs only; D-005 gate)*

- [ ] **ADR-025 — Multi-Server Proxy Support** — accept Velocity-first, load-balancing headline, durable-transport requirement (D2), reuse of `AbstractSQLDatabaseAccessor` (D3). Do not supersede ADR-022.
- [ ] **`REQUIREMENTS.md`** — add REQ-RTP-NET-001…005 with final phrasing.
- [ ] **`GLOSSARY.md`** — add: *backend*, *proxy*, *reservation token*, *transport*, *network snapshot*, *backend selector*.
- [ ] **`AGENTS.md` Current Development Focus** — only after Phase 0 acceptance; until then, Fabric remains the active frontier.
- [ ] **`INDEX.md`** — add ADR-025 row + this plan.
- [ ] **Fill the load-balancing heuristics placeholder** in this document.
- [ ] **Resolve D4** — pick HMAC key distribution mechanism.

### Phase 1 — Core SPI *(no proxy adapter yet)*

- [ ] `RtpTriggerSource` + `RtpDispatcher` in `rtp-core`.
- [ ] `BackendSelector` interface; ship at least one concrete strategy approved in the heuristics placeholder.
- [ ] `NetworkTransport` interface + `InMemoryNetworkStateBinding` reference impl.
- [ ] Network-state member adjacent to `AbstractSQLDatabaseAccessor` per D3.
- [ ] Single-JVM tests with two simulated backends.
- [ ] No-op test proving `network.enabled: false` is byte-identical.

### Phase 2 — Velocity adapter + Redis transport

- [ ] `rtp-proxy-common` + `rtp-proxy-velocity` modules.
- [ ] `RedisNetworkStateBinding` (Lettuce, async).
- [ ] Reservation tokens end-to-end with TTL reaper.
- [ ] `ServerPreConnectEvent` hook (Velocity) for backend rewrite.
- [ ] `CommandTriggerSource` wired through dispatcher.
- [ ] Resolve D4 (HMAC key distribution) before security review.
- [ ] **Acceptance**: cross-server `/rtp` round-trip on Velocity + 2× Paper devstack with Redis.
- [ ] Regression suite for reservation token replay / TTL / orphaned-MemoryTracker scenarios.

### Phase 3 — Postgres transport + Join trigger + BungeeCord adapter

- [ ] `PostgresNetworkStateBinding` (`LISTEN/NOTIFY` + `SKIP LOCKED`).
- [ ] `JoinTriggerSource` wired on proxy-side per D1.
- [ ] `rtp-proxy-bungee` adapter (BungeeCord + Waterfall).
- [ ] **Acceptance**: same scenarios green on BungeeCord + Postgres.

### Phase 4 — Generic SQL + Hardening + Release

- [ ] `GenericSqlNetworkStateBinding` (MySQL/MariaDB polling).
- [ ] Security audit: HMAC, replay protection, schema-version negotiation, kill switch verification.
- [ ] `rtp test full network` aggregator across the network.
- [ ] `docs/admin/` install/config notes for proxy mode.
- [ ] `CHANGELOG.md` entries per phase under *Unreleased*.
- [ ] `LESSONS_LEARNED.md` — proxy-specific pitfalls.
- [ ] `COVERAGE_PLAN.md` — add proxy column.
- [ ] First public proxy-mode beta release (gated on full audit green).

### Deferred / Out of Scope (this plan)

- F2 — pre-warmed teleport queue across the network.
- F7 — region-availability discovery beyond static config.
- HTTP/gRPC transport.
- Forge / NeoForge proxies.

---

## Risk & Pitfall Inventory

- **Folia + proxy interaction** — proxy reply lands on a netty thread on the backend; consumers must hop to the right region scheduler before touching the player (`Bukkit.isOwnedByCurrentRegion`). Same discipline as `AGENTS.md > Folia Threading`, just over the wire.
- **Reservation tokens are a distributed-systems problem** — TTL, idempotency, replay protection are easy to get subtly wrong. Treat the regression suite as a Phase 2 acceptance gate, not a "nice to have".
- **Velocity vs. BungeeCord API divergence** — too large to share a runtime; share only the SPI. Don't water down the Velocity design to match Bungee.
- **Version skew** — backend running RTP `X` talking to a proxy plugin running `X+1`. Requires `schemaVersion` negotiation on first packet, with graceful degrade ("falls back to single-server behaviour").
- **Security** — Redis especially: any other plugin on the same Redis can spoof requests. HMAC + a kill switch in config are mandatory. D4 must be resolved before Phase 2 ships.
- **Existing single-server tests must not regress** — REQ-RTP-NET-005 makes this explicit; the Phase 1 no-op test is the gate.
- **Plugin-message transport is dev-only** (D2) — must be loudly documented and emit a startup warning when selected outside a dev profile.

---

## Open Items / Follow-Ups

- **Load-balancing heuristics section** — currently a placeholder. Must be filled out before Phase 1 ships any non-trivial selector.
- **D4 — HMAC key distribution** — user flagged as needing more investigation. Candidates to evaluate: env var, file with restrictive perms, per-backend keypair, OS keyring. Block Phase 2 release on resolution.
- **Postgres-vs-Redis benchmark** — required input for ADR-025 to justify the "Redis preferred, Postgres co-equal" framing.
- **`commands-api` proxy-side surface** — exact shape of `ProxySender` and `NetworkAwareCommand` to be ratified during Phase 1 design.

---

*Self-update note*: any durable engineering lesson discovered while executing this plan goes to [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md); incidental potential bugs go to [`POTENTIAL_BUGS.md`](POTENTIAL_BUGS.md); architecturally significant decisions get their own ADR. Do not bloat this file with implementation lore — it is a roadmap, not an encyclopaedia.

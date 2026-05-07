# Multi-Server (Proxy) Support Roadmap

This document outlines the plan for RTP's multi-server (proxy / network) expansion. It is **distinct from** [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md): that plan covers running on additional Minecraft server flavours (Spigot/Paper/Folia/Fabric); *this* plan covers coordinating RTP across **multiple concurrent backend servers** sitting behind a proxy (Velocity, BungeeCord, Waterfall).

> Status: **Draft — Phase 0 (Scope Unlock) not yet started.** No code changes have been made; no ADR has been accepted. This document is gated by Rule D-005 (Propose Before Implementation, see [`AGENTS.md`](../../.junie/AGENTS.md)).

> Cross-references: [rtp-fabric-ADR-002 (Fabric in scope)](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) is **orthogonal** to this plan and is **not** superseded. A new ADR-025 (multi-server proxy support) is required before Phase 1 work begins.

> Veracity audit (2026-05-01): codebase-anchored claims in this plan have been spot-checked against the repo. Confirmed present: `AbstractSQLDatabaseAccessor` (+ `H2`/`SQLite`/`MySQL`/`PostgreSQL` concrete accessors), HikariCP 5.1.0, `RegionQueueManager`, `TeleportPipelineTask`, `MemoryTracker`, `RTP.scheduler.runTaskTimer` / `runTaskTimerAsynchronously`, `BrigadierBridgeContext` + `BrigadierCommandAdapter` in `commands-api/`, `messages.yml`, `REQ-RTP-F-013`. Unverifiable here (external APIs): Velocity `ServerPreConnectEvent`, Lettuce, Postgres `LISTEN/NOTIFY` / `SKIP LOCKED` semantics — these are documented as items for ADR-025. Note: `loadBalancer.backends.<serverId>.weight` is a *proposed* key, not yet drafted in the config surface; flagged inline.

---

## Headline Feature

**Cross-server load-balanced RTP** — a player request originating anywhere on the network is dispatched to the most appropriate backend, the destination is generated using that backend's existing async pipeline, and the player is transferred. The trigger source (command, server-join, addon event) is **configurable** so operators decide whether to call RTP via `/rtp` or pass the player through on join (mirroring the existing Bukkit join-event hook).

---

## Intended Usage & Deployment Model

The plan is designed around a single explicit operator workflow. Anything that complicates the steps below should be treated as a regression of this plan, not a feature.

### Goals

- **One artifact, every target.** The same RTP JAR drops into a Spigot/Paper/Folia backend, a Fabric backend, a Velocity proxy, or a BungeeCord/Waterfall proxy. The runtime detects the host platform and activates the relevant entry point — extending the single-JAR / multi-loader pattern already established by [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) to the proxy axis as well. Operators never pick between "the proxy build" and "the backend build".
- **Minimal proxy-side configuration.** A proxy's job is to *route* RTP requests, not to own world data. Its configuration shall reduce to: a transport / database reference (Redis or JDBC URL), a shared secret, and the trigger / load-balancer policy. No region definitions, no world tables, no per-backend mirrors of backend-side config.
- **Verbatim copy across backends.** An operator shall be able to author `network.yml` once, copy it byte-for-byte to every backend, and only have to change a single per-host field (`network.serverId`). This rules out config sprawl: anything that *must* differ between backends is either auto-derived (e.g. heartbeat timestamps), centralised (proxy-side trigger config per D1), or limited to that one identifying field.
- **Zero behaviour change when disabled.** With `network.enabled: false` (the shipping default), the artifact behaves byte-identically to today's single-server build. This is REQ-RTP-NET-005 and is the gate for any release.

### What an operator does (target workflow)

1. Drop the same JAR onto every backend and onto the proxy.
2. Provision the shared store (Redis instance or a JDBC database — both reuse `AbstractSQLDatabaseAccessor` per D3).
3. Author one `network.yml` with the transport endpoint, shared secret, trigger policy, and load-balancer policy.
4. Copy that file to every host. On each backend, set `network.serverId` to a unique value. Proxy uses the same file with no `serverId` (or a reserved value).
5. Set `network.enabled: true`. Restart. Done.

### Non-goals of this section

- Proxy-side region authoring, world tables, or claim-plugin integration. The proxy is intentionally a thin coordinator; world-truth lives on the backend, where it already does.
- Per-platform forks of the artifact. Loader divergence is handled by the existing single-JAR bootstrap; the proxy adapter modules (`rtp-proxy-velocity`, `rtp-proxy-bungee`) ship inside the same JAR, not as separate downloads.
- Anything that forces an operator to maintain a different file per backend beyond the `serverId` field. Audit any new `network.yml` key against this rule before adding it.

### Implications carried into the rest of this plan

- The **Config Surface (`network.yml`)** section below shall stay flat and short; long-form region/world content stays in the existing per-backend `regions.yml` / world configs.
- The **Backend Telemetry Publication** payload is *self-describing* (it carries `serverId`, `platform`, `mcVersion`, `regionsAvailable[]`) precisely so the proxy can run with no inventory of backends declared up-front. New backends register simply by writing their first heartbeat row.
- The **Trigger Abstraction** keeps trigger configuration proxy-side (D1) so adding a new backend requires zero proxy-config changes.
- The **load-balancer** consumes published telemetry rather than a static backend list — same rationale; copy-paste-friendly fleet management.

These constraints are part of the acceptance criteria for ADR-025; any deviation must be justified there.

### Non-Goals (v1)

- No proxy-side chunk logic, world data, or entity manipulation. The proxy never owns world state.
- No replacement of the existing single-server pipeline. With `network.enabled: false`, behaviour is byte-identical to today.
- No Forge / NeoForge proxy support. (Out of scope until Fabric platform stabilises — see `MULTI_PLATFORM_PLAN.md` Phase 4.)
- No cross-version protocol breakage without a `schemaVersion` bump.
- **No post-arrival coordinate resolution.** Coordinates are resolved on the destination *before* the player transfers; see *Coordinate Resolution Timing* below.

---

## Coordinate Resolution Timing *(decision locked 2026-05-01)*

**Coordinates are resolved on the destination backend before the player's server change**, not after arrival. The reservation token issued to the proxy carries the final `worldKey` + `x/y/z/yaw/pitch`, and the destination's join handler simply *consumes* the token rather than running a fresh pipeline.

### Why this is trivial in practice

The destination's existing **kept cache** (`RegionQueueManager.keptLocations` — the *Hot Queue* `LockFreeLocationBuffer` of pre-verified safe locations whose chunks are currently loaded with `keep(true)` applied) already produces ready-to-use coordinates as part of normal operation. A network teleport request becomes:

1. Selector picks destination based on telemetry.
2. Destination polls one location from `keptLocations` (the hot queue) — falling back to `unkeptLocations` (the cold queue, also pre-verified; chunks must be re-loaded) only if hot is empty.
3. The polled `RTPLocation` is written into a **reservation token** row (under the network-state member of `AbstractSQLDatabaseAccessor`, per D3) — i.e. an exclusive cross-network allocation of a coordinate that was already going to be produced anyway.
4. Proxy commits the transfer; destination's join handler consumes the token and teleports the player to the reserved location.

In other words: the network-mode reservation token table is a **thin allocation layer over the existing kept-cache pool** — it earmarks one of the buffer's entries as "already promised to a cross-network player" so no other code path can hand out the same coordinates. No new safety-pipeline code paths are introduced.

> Note: the per-player cache (`RegionQueueManager.fastLocations` — `ConcurrentHashMap<UUID, CompletableFuture<RTPLocation>>`) and the ADR-023 Login Reserve Cache (`loginLocations`) are *not* the source for cross-network allocations. They serve **already-online players on the local backend** and are intentionally left untouched by network-mode bookkeeping. Cross-network allocations draw from the general region pool (`keptLocations` → `unkeptLocations`).

### Why post-arrival was rejected

| Concern | Pre-resolve (chosen) | Post-arrival (rejected) |
|--------|---------------------|------------------------|
| S-001 / S-003 / S-005 obligations | Stay on destination's existing async pipeline. No re-litigation over the wire. | Run after transfer commits. Failure leaves the player on the wrong server with no clean recovery. |
| S-004 attribution | Failure surfaces on origin via `messages.yml` (REQ-RTP-F-013 / REQ-RTP-NET-003) before any transfer. | Failure surfaces *after* a successful transfer; either silent (S-004 violation) or requires a second transfer to recover. |
| Player UX | One transfer; spawn frame is the final location. | Spawn-flash at destination's spawn, then a teleport. |
| Selector honesty | Selector pays the resolve cost on the chosen backend; mid-flight rejection retries the next-lowest-score candidate. | Selector commits before destination knows it can deliver; rejection means a re-transfer. |
| Reservation tokens | Required (state machine: `PENDING → CLAIMED → CONSUMED`). | Avoidable, but only by paying the cost in failure UX. |

The latency-on-tail downside of pre-resolve is real but additive: it is **softened by the existing cache** (most resolves are O(map lookup), not a full pipeline run), and a future network-wide pre-warmed queue (deferred F2) would close the remaining gap. Post-arrival's failure UX, by contrast, is structural and cannot be retrofitted without re-introducing a token.

### Network Wait Queue (cache miss + no bypass perm)

If both `keptLocations` and `unkeptLocations` are exhausted on the chosen destination (or cannot deliver within the request's deadline) and the player **lacks the bypass permission**, the request shall enroll into a **network-mode UUID wait queue**: a UUID-keyed FIFO that mirrors the existing per-user `playerQueue` pattern in `RegionQueueManager`, but lives in the network-state member so the *proxy* and *destination* can both observe it.

Behaviour:

- Enrollment is idempotent on UUID — a player who re-issues `/rtp` while waiting does not double-queue.
- The destination's region cache replenishes asynchronously through the existing deficit loop in `Region.execute()`; as new entries land in `keptLocations`/`unkeptLocations`, the network wait queue drains in FIFO order, each drain pulling a coordinate, issuing a reservation token, and transferring the player.
- The proxy may surface a configurable "you are #N in the network queue" message (REQ-RTP-F-013 / REQ-RTP-NET-003), reusing the existing single-server queue-position UX.
- Bypass permission **reuses the existing `rtp.unqueued` node** (no new permission). When `true`, the player skips the network wait queue and the destination generates a fresh location immediately; if no backend can deliver immediately, the request fails fast with a configurable message. Use is expected to be rare — implementation is **low priority**, may land in Phase 2 or be deferred to Phase 3 without blocking acceptance.
- The wait-queue table is **purely transient**: rows live as long as the player is connected and waiting; the reservation-token reaper also reaps stale wait-queue rows on the same TTL clock. This keeps it consistent with single-server semantics where the wait queue lives only in memory.

This preserves the single-server fairness model across the network without inventing a new one: the same "hot kept-cache first, cold cache next, otherwise wait your turn" contract that exists today, just with the wait queue allocated globally instead of per-backend.

### Summary

- **Decision**: pre-resolve coordinates on destination, transfer with reservation token. ADR-025 acceptance criterion.
- **Reservation token table**: thin allocation layer over the existing region kept-cache (`keptLocations`, fallback `unkeptLocations`). New code is bookkeeping, not safety-pipeline.
- **Per-player caches stay local-only**: `fastLocations` and `loginLocations` (ADR-023) are not consumed by cross-network allocations.
- **Cache miss + no bypass permission**: enroll into a UUID-keyed network wait queue mirroring the existing per-region `playerQueue`. Bypass permission skips the queue.
- **No new prohibitions cross the wire**; S-001…S-006 stay attributed exactly where they are today.

---

## Decisions Recorded (from brainstorm 2026-05-01)

These answers are taken from the issue thread that produced this document. They lock in scope; any change requires explicit user approval per Rule D-005.

| # | Question | Decision |
|---|----------|----------|
| D1 | Network-mode default world resolution on join | **Proxy-side config.** `JoinTriggerSource` reads region/world mappings from the proxy plugin's config, not per-backend. |
| D2 | Reservation persistence on proxy restart | **Required.** Transport must be durable. `plugin-message` is therefore a **degraded / dev-only** mode and not supported in production. |
| D3 | Network state storage location | **Reuse `AbstractSQLDatabaseAccessor` where possible.** If a separate `AbstractNetworkStateAccessor` proves necessary, it must live **adjacent to** or **as a member of** the existing accessor — not a parallel hierarchy. |
| D4 | HMAC key distribution | **Env var for v1** (`RTP_NET_SECRET`). Operators set the same value on every host; matches the copy-paste deployment model. Other mechanisms (config file with restrictive perms, per-backend keypair, OS keyring) are deferred research items — may revisit before public release without blocking Phase 2. |

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

### Load-Balancing Heuristics — Configurable Weighted Average *(direction set 2026-05-01)*

User direction: **v1 ships a single configurable strategy — a weighted average over published telemetry metrics, with a per-metric response curve.** No discrete strategy zoo (no `ROUND_ROBIN` / `LEAST_LOADED` / etc. as separate selectors); those collapse to special cases of the weighted-average configuration. The proxy owns this configuration so admins tune the network from one place.

#### Model

For each candidate backend `b` passing the availability filter, compute:

```
score(b) = Σ_i  weight_i  *  curve_i( normalize_i( metric_i(b) ) )
```

- `metric_i(b)` — a single field from the **Backend Telemetry Publication** payload (e.g. `playerCount / softCap`, `mspt`, `queueDepth`, `avgPipelineMs`, `chunkLoadBacklog`, `1 - tps20Ratio`, `latencyMs`, …).
- `normalize_i` — maps the raw metric into `[0, 1]` where `0` = "cheapest / best" and `1` = "most expensive / worst". Configurable per metric (`min`, `max`, `clamp`).
- `curve_i` — the response curve applied to the normalized value (see catalogue below).
- `weight_i` — non-negative scalar from config; `0` disables the metric.

Selection picks the backend with the **lowest** score (cost-minimization framing — keeps "0 = best" intuitive across all metrics). Ties broken by `serverId` ascending for determinism in tests.

#### Curve catalogue (config-selectable per metric)

All curves take a normalized input `x ∈ [0, 1]` and return `y ∈ [0, 1]`.

| `curve` | Formula (straw-man) | Shape | When to use |
|--------|--------------------|-------|-------------|
| `linear` | `y = x` | straight | metric is roughly proportional to cost (e.g. `queueDepth`) |
| `exponential` | `y = (e^(k·x) − 1) / (e^k − 1)`, default `k = 3` | flat then sharp ramp | metric is fine until it's *very* bad (e.g. `mspt`, `chunkLoadBacklog`) |
| `logarithmic` | `y = log(1 + k·x) / log(1 + k)`, default `k = 9` | sharp then flattens | metric saturates quickly (e.g. `playerCount` near `softCap`) |
| `sigmoid` | `y = 1 / (1 + e^(−k·(x − 0.5)))`, default `k = 8` (renormalised to [0,1]) | "steep in the middle" — the user's request | smooth on/off threshold around the midpoint (e.g. `tps` dropoff, `heapUsedRatio`) |
| `step` | `y = 0` if `x < threshold`, `y = 1` otherwise | hard cliff | binary fences (e.g. `acceptingRequests`, `pluginState != READY`) |
| `power` | `y = x^p`, default `p = 2` | mild curvature | conservative quadratic for symmetry with linear |

Curve params (`k`, `threshold`, `p`) are per-metric in config; defaults above. Curves must be **monotonic non-decreasing** so the score is well-ordered; the publisher's "snapshot, not deltas" contract guarantees clean inputs.

#### Config surface (replaces the prior `loadBalancer.strategy: TBD` straw-man)

Lives **proxy-side** (matches D1 — proxy owns trigger/selection config so admins tune the network in one place):

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

`source` is either a published-field name from the telemetry table (Backend Telemetry Publication section) or one of a small fixed set of proxy-computed values (`proxyMeasuredRttMs`, `stickyRegionMatch`, …). A full expression DSL is **out of scope for v1** — start with the field-name + small enum approach; revisit only if real configs demand it.

Special cases collapse cleanly:
- *Round-robin equivalent*: zero out all weights; selector falls back to `tieBreaker`.
- *Least-loaded*: weight only `playerLoad` and/or `mspt`.
- *Lowest-latency*: weight only `proxyLatency`.
- *Sticky region*: weight `regionAffinity` heavily; everything else light.
- *Weighted (admin-set per backend)*: not represented as a per-metric weight — admins set a per-backend multiplier (proposed key `loadBalancer.backends.<serverId>.weight`, **not yet drafted in the config surface below — to be added during Phase 1 design**) that divides the final score so a higher backend weight makes that backend preferred while keeping "lowest wins".

#### Defaults shipped with v1

The example block above is the **shipped default**. Rationale per metric noted inline. Operators can disable any line by setting `weight: 0`. The defaults must be benchmarked against a reference Velocity + 2× Paper devstack before Phase 2 release; tuning notes will land in `LESSONS_LEARNED.md`.

#### Documentation follow-up

- **Curve visualizations** — add rendered plots of each curve (`linear`, `exponential`, `logarithmic`, `sigmoid`, `step`, `power`) at their default parameters to `docs/admin/proxies/` (e.g. `LOAD_BALANCING.md` with embedded SVG/PNG) so admins can pick a curve by shape, not by formula. Generation script lives under `scripts/` (matplotlib or similar). Tracked as a Phase 3 documentation item — block on it before the first public proxy beta.

#### Resolved items (formerly open)

- **Hot-spot avoidance** — *confirmed*. Implemented as a per-proxy decaying counter of recent picks, added to the score as another metric row (`recentPicks`) with its own `weight`/`curve`. Lives in the same model; no special-case code path. **Default halflife: 10s** (decay constant `λ = ln(2) / 10s ≈ 0.0693 s⁻¹`); the counter is bumped by `+1` on each pick and decays exponentially between heartbeats. The 10s figure matches the operator-experience target (a single low-score backend stops being preferred within roughly two heartbeat windows after a stampede starts). Default weight ships at a moderate value so it tempers but does not dominate the cost signal.
- **Tie-breaking** — *resolved*. `serverIdAsc` is final; ties between weighted-average scores are exceedingly rare and `serverId` ordering is sufficient for determinism. No tie-breaker enum.
- **Curve param ranges** — *confirmed*. Validation at config load enforces sane bounds for `k`, `p`, `threshold` so a malformed config cannot produce NaN scores. Concrete bounds: `k ∈ [0.1, 20]`, `p ∈ [0.1, 8]`, `threshold ∈ [0.0, 1.0]` (subject to ratification in ADR-025).
- **Per-backend weight key** — *added*. `loadBalancer.backends.<serverId>.weight` is now part of the config surface (see *Config Surface* below). Acts as a multiplier: final score is `rawScore / backendWeight`, so a higher weight makes a backend preferred while keeping "lowest wins".
- **Player-count weighting** — *resolved as `weight: 0` for v1*. Player count is still **published** in telemetry (operators want it for dashboards) but is not consumed by the selector by default. Re-evaluate **after live-player testing** of the Phase 2 devstack — if `mspt` and `pendingTeleports` already capture the relevant strain under real load, the weight stays at zero permanently; if a population-driven signal proves additive, raise it then. No further design work is required before Phase 2.

#### Failure / fallback chain (informed by Linux scheduler best practice)

Adapted from CFS / kernel load-balancer conventions — cheap to evaluate, expensive to mis-pick, biased toward stability over reactivity:

- **Capped retries**: on chosen-backend rejection or timeout, retry with the next-lowest score, capped at `loadBalancer.maxRetries` (default `3`). Beyond the cap, fail fast with a configurable `messages.yml` entry (REQ-RTP-F-013 / REQ-RTP-NET-003).
- **Per-attempt timeout**: `loadBalancer.attemptTimeoutMs` (default `1500`). The selector treats a timeout identically to a rejection.
- **Hysteresis on re-pick**: after a rejection, the rejected backend is excluded from selection for `loadBalancer.cooldownMs` (default `2000`) — the same idea as CFS's `imbalance_pct` / `nr_balance_failed` debounce. Prevents the proxy from re-picking the same struggling backend on the next request.
- **Score sticking ('idle balance'-style)**: don't migrate already-pending requests to a freshly-cheaper backend mid-flight. Once a request is dispatched, it stays with the chosen backend until success, timeout, or rejection — mirrors how CFS prefers not to migrate a running task unless the imbalance is significant.
- **Snapshot freshness**: the selector reads `NetworkSnapshot` once at request entry and uses that snapshot for the entire retry chain (analogous to a single `rebalance_domains` pass). Keeps retry decisions internally consistent.

All four knobs (`maxRetries`, `attemptTimeoutMs`, `cooldownMs`, `recentPicks` half-life/weight) live in the `loadBalancer` block of `network.yml` and ship with the defaults above. Concrete tuning notes will land in `LESSONS_LEARNED.md` after the Phase 2 devstack benchmark.

#### Still-open items (smaller list)

- **Expression DSL vs. fixed `source:` enum** — start fixed-enum; revisit if/when configs ask for compound expressions beyond the two ratios above.

This section is now **direction-locked**, not a placeholder. Phase 1 may implement against it, with the v1 default block above as the test fixture.

---

## Backend Telemetry Publication *(NEW, 2026-05-01)*

Each backend running RTP **shall publish to its configured database** a periodic record describing two distinct concerns:

1. **Plugin state — availability.** Is this backend usable as an RTP destination *right now*? This is a binary-plus-context signal: the backend is up, RTP is loaded, the pipeline is responsive, and the requested regions exist.
2. **Server state — performance cost.** How expensive is it to serve another teleport from this backend *right now*? This is a continuous signal feeding the load balancer (see *Load-Balancing Heuristics — Configurable Weighted Average* above).

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

- The **Load-Balancing Heuristics — Configurable Weighted Average** section is the single place that decides *how* these fields are weighted. The publisher commits to providing them; the selector decides which it uses.
- **Spigot TPS source** — *resolved*. Minimum supported Spigot is 1.20.1; raw Spigot's `Bukkit.Server` does not expose `getTPS()` on that version (it is a Paper-only addition). For Spigot-only stacks, sample tick duration locally via a 1-tick scheduled task and compute MSPT/TPS from the elapsed-nanos differential. On Paper/Folia, use `Bukkit.getTPS()` directly. Module: `rtp-spigot` adapter for the fallback sampler; see also the new metrics plan (`METRICS_PLAN.md`) for the canonical implementation.
- **Per-region TPS aggregation on Folia** — lives in [`METRICS_PLAN.md`](METRICS_PLAN.md), not this plan. Player-count-weighting is **out** (D-confirmed 2026-05-01); the choice is between `max` and `mean`, with leaning toward `max` so a single struggling region surfaces. Final call deferred to the metrics plan.

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

The reservation token exists *because* of the *Coordinate Resolution Timing* decision above: it is the thin allocation layer that earmarks one already-resolved coordinate (drawn from the destination's region kept-cache — `RegionQueueManager.keptLocations`, falling back to `unkeptLocations`) as "promised to a cross-network player," so no local code path can hand it out twice. The per-player `fastLocations` cache and the ADR-023 `loginLocations` reserve are deliberately untouched by this layer (they serve already-local players). New code here is bookkeeping; safety-pipeline code is not duplicated.

Single shared keyspace owned by the network-state member of the accessor:

- Fields: `token (UUID PK)`, `playerUuid`, `targetServerId`, `worldKey`, `x/y/z/yaw/pitch`, `issuedAt`, `expiresAt`, `state ∈ {PENDING, CLAIMED, CONSUMED, EXPIRED}`.
- **Issued** by the destination backend after its pipeline produces a safe location.
- **Claimed** atomically when the proxy commits to a transfer.
- **Consumed** by the destination backend on player join (PaperMC `PlayerJoinEvent` / Fabric server-join event / equivalent). Idempotent: `UPDATE … WHERE state='CLAIMED'` returning row-count; >1 ⇒ replay, refuse and log under S-004.
- **Reaped** by a scheduled `RTP.scheduler.runTaskTimerAsynchronously` on each backend, which releases `MemoryTracker` entries on local rows it owns.
- TTL: configurable, default 30s.

Per D2, tokens **must survive a proxy restart**, which is why `plugin-message` transport is dev-only — it has no durability guarantee.

### Lifecycle ownership matrix

| State transition | Initiator | Atomicity primitive | Failure handling |
|------------------|-----------|---------------------|------------------|
| `—` → `PENDING` | Destination backend (after pipeline produces safe location) | INSERT under unique `(playerUuid, state=PENDING)` partial index; conflict ⇒ reuse existing row | If conflict happens during a retry, the destination returns the existing token rather than minting a new one |
| `PENDING` → `CLAIMED` | Proxy (just before issuing the transfer) | `UPDATE … SET state='CLAIMED', claimedAtEpochMs=now WHERE token=? AND state='PENDING'` returning row-count | row-count `= 0` ⇒ race lost (token expired or was claimed by a parallel proxy instance); proxy aborts the transfer and surfaces a `messages.yml` failure (REQ-RTP-NET-003) |
| `CLAIMED` → `CONSUMED` | Destination backend (in the join handler) | `UPDATE … SET state='CONSUMED' WHERE token=? AND state='CLAIMED'` returning row-count | row-count `> 1` ⇒ replay attempt; reject the duplicate join, log under S-004 |
| `PENDING` \| `CLAIMED` → `EXPIRED` | Reaper (each backend, async timer) | `UPDATE … SET state='EXPIRED' WHERE expiresAtEpochMs<now AND state IN ('PENDING','CLAIMED')` | Token is no longer valid; if it was `CLAIMED`, the destination releases the underlying `keptLocations` entry back to its source buffer and emits a `MemoryTracker` release; an audit row is logged |

Proxy-restart recovery: on startup the proxy runs `UPDATE … SET state='PENDING' WHERE state='CLAIMED' AND claimedAtEpochMs < now - claimReanimateMs` (default `claimReanimateMs = 5000`). This re-opens any token claimed by a proxy that died before completing the transfer, letting the next proxy instance pick it up rather than orphaning it until TTL expiry.

Destination-restart recovery: on startup the destination runs the local reaper at half its normal interval for the first `2 * heartbeatInterval` so any tokens it issued just before crashing are aged out promptly. Tokens issued by a *different* destination are out of scope — only that backend can release the underlying `keptLocations` entry, so a permanently-dead backend's tokens age out via TTL.

### Required regression coverage

A dedicated regression suite analogous to `ReqRtpS004NullChunkAttributionTest` is required before Phase 2 acceptance, covering at minimum:

- Replay protection: a `CLAIMED → CONSUMED` transition that races itself across two backend instances must succeed exactly once.
- TTL expiry: a `PENDING` token whose `expiresAt < now` must transition to `EXPIRED` and release its `MemoryTracker` entry within one reaper interval.
- Orphaned-allocation prevention: a backend crash mid-issue (`PENDING` written but no proxy ever claims) must not leak a `keptLocations` entry beyond TTL.
- Proxy-restart reanimation: a `CLAIMED` token whose proxy died is observed in `PENDING` again after `claimReanimateMs`, and the next proxy instance can claim it.
- Schema-version mismatch: a token written under an older `schemaVersion` is rejected (or upgraded, depending on the version-skew policy ratified in ADR-025).
- HMAC reject: a token whose envelope HMAC fails verification is dropped and an S-004 audit log is emitted; the player request fails through the configured `messages.yml` entry, not silently.

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
    # Direction-locked: configurable weighted average over telemetry. See
    # "Load-Balancing Heuristics — Configurable Weighted Average" above.
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
      # Higher weight → preferred. Omitted entries default to weight 1.0.
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

## Requirements (Stubs — to be authored in REQUIREMENTS.md as part of Phase 0)

| ID | Statement |
|----|-----------|
| `REQ-RTP-NET-001` | Cross-server teleport shall preserve S-001 through S-006 end-to-end. |
| `REQ-RTP-NET-002` | Reservation tokens shall expire deterministically; no orphaned `MemoryTracker` entries shall remain after expiry. |
| `REQ-RTP-NET-003` | All proxy-mediated user-facing messages shall route through `messages.yml` (extends REQ-RTP-F-013). |
| `REQ-RTP-NET-004` | Network transport shall not perform synchronous I/O on a tick or netty thread. |
| `REQ-RTP-NET-005` | When `network.enabled` is false, behaviour shall be byte-identical to single-server operation. |
| `REQ-RTP-NET-006` | Every transport packet shall carry a `schemaVersion` and an HMAC envelope; packets failing verification shall be dropped and audited under S-004. |
| `REQ-RTP-NET-007` | A reservation token claim shall be exactly-once across the network; a duplicate `CLAIMED → CONSUMED` transition shall be refused and audited under S-004. |
| `REQ-RTP-NET-008` | Backend telemetry shall be writable to *any* `AbstractSQLDatabaseAccessor` flavour the project ships (H2/SQLite/MySQL/PostgreSQL). |

Authoring rules: see [`docs/dev/RULES.md`](RULES.md) and the *Requirement Documentation Rules* section of [`AGENTS.md`](../../.junie/AGENTS.md). The statements above are placeholders — final wording must use `shall` / `shall not`, no implementation actions.

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

- **Thread-context map for cross-wire callbacks** — the SPI must explicitly document on which thread each callback fires:
  - Transport publisher writes — always async (`runTaskTimerAsynchronously`).
  - Transport listener delivery — netty / Lettuce / Postgres-driver thread; consumers must hop via `RTP.scheduler.runTaskTimer` (or the entity scheduler on Folia) before touching world or player state.
  - Selector `choose()` — invoked by the dispatcher; pure-function contract; safe to call from any thread.
  - Reservation reaper — always async; releases `MemoryTracker` entries.
  - HMAC verify — same thread as the inbound packet; cheap (`Mac.doFinal`); never blocks.
- **Folia + proxy interaction** — proxy reply lands on a netty thread on the backend; consumers must hop to the right region scheduler before touching the player (`Bukkit.isOwnedByCurrentRegion`). Same discipline as `AGENTS.md > Folia Threading`, just over the wire.
- **Reservation tokens are a distributed-systems problem** — TTL, idempotency, replay protection are easy to get subtly wrong. Treat the regression suite as a Phase 2 acceptance gate, not a "nice to have".
- **Velocity vs. BungeeCord API divergence** — too large to share a runtime; share only the SPI. Don't water down the Velocity design to match Bungee.
- **Version skew** — backend running RTP `X` talking to a proxy plugin running `X+1`. Requires `schemaVersion` negotiation on first packet, with graceful degrade ("falls back to single-server behaviour").
- **Security** — Redis especially: any other plugin on the same Redis can spoof requests. HMAC + a kill switch in config are mandatory. D4 must be resolved before Phase 2 ships.
- **Existing single-server tests must not regress** — REQ-RTP-NET-005 makes this explicit; the Phase 1 no-op test is the gate.
- **Plugin-message transport is dev-only** (D2) — must be loudly documented and emit a startup warning when selected outside a dev profile.

---

## Sufficiency Audit (2026-05-01)

This plan has been reviewed for implementer-sufficiency against `AGENTS.md`, `RULES.md`, and the existing S-001…S-007 prohibitions. The items below were identified as gaps and either filled in this revision or explicitly deferred:

- **Reservation token state machine** — explicit ownership matrix added (who initiates each transition, atomicity primitive, failure handling, proxy-restart reanimation).
- **Thread-context map** — added to *Risk & Pitfall Inventory* so each callback's expected thread is documented.
- **Wire-protocol envelope** — captured as REQ-RTP-NET-006 (schemaVersion + HMAC). Final wire format (CBOR / JSON / length-prefixed bytes) deferred to ADR-025.
- **Exactly-once claim semantics** — captured as REQ-RTP-NET-007.
- **Multi-DB compatibility** — captured as REQ-RTP-NET-008 (any of H2/SQLite/MySQL/PostgreSQL must be acceptable for backend-side telemetry).
- **Required regression coverage** — enumerated under *Reservation Tokens* (replay, TTL, orphan, reanimation, schema-version, HMAC reject) so the Phase 2 acceptance suite is unambiguous.
- **Test fixture provenance** — the v1 default `loadBalancer` block is now explicitly the test fixture (no separate fixture file).

**Items deliberately left open** (tracked in *Open Items / Follow-Ups* below):

- Wire-format choice (CBOR vs. JSON vs. binary) — ADR-025.
- Postgres-vs-Redis benchmark — post-implementation.
- `commands-api` proxy surface concrete shapes — early Phase 1 design.
- HMAC distribution beyond env-var — deferred research.
- Player-count weighting — awaits Phase 2 live-player evidence.

---

## Open Items / Follow-Ups

- **D4 — HMAC key distribution beyond env var** — v1 ships env-var (`RTP_NET_SECRET`). Research alternatives (config file with restrictive perms, per-backend keypair, OS keyring) before public release; not a Phase 2 blocker.
- **Postgres-vs-Redis comparative benchmark** — *to be performed after each transport's individual implementation and testing has stabilised*. Not a prerequisite for ADR-025 ratification (their selection rationale stands on responsiveness characteristics); benchmark drives ops guidance and the eventual `LESSONS_LEARNED.md` entry.
- **`commands-api` proxy-side surface** — **early TODO for Phase 1 design**. Concrete shapes needed: `ProxySender` (adapts Velocity `CommandSource` and Bungee `CommandSender`), `NetworkAwareCommand` mixin (routes execution through `RtpDispatcher`), tab-completion routing across the transport. Resolve before any proxy adapter module is opened.
- **Player-count weighting** — published in telemetry; selector weight stays `0` until live-player testing on the Phase 2 devstack provides evidence either way. No design action required before Phase 2.
- **`rtp.unqueued` bypass implementation** — low priority; expected use is rare. Acceptable to defer past Phase 2 acceptance.
- **Folia per-region TPS aggregation** — owned by [`METRICS_PLAN.md`](METRICS_PLAN.md); this plan consumes whatever the metrics plan publishes.

---

*Self-update note*: any durable engineering lesson discovered while executing this plan goes to [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md); incidental potential bugs go to [`POTENTIAL_BUGS.md`](POTENTIAL_BUGS.md); architecturally significant decisions get their own ADR. Do not bloat this file with implementation lore — it is a roadmap, not an encyclopaedia.

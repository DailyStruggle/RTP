# ADR-036 — Network Mode: Multi-Server, Multi-Proxy RTP

**Status:** Accepted
**Date:** 2026-05-13
**Accepted:** 2026-05-14

## Context

`docs/dev/MULTI_SERVER_PLAN.md` has accumulated the full design intent for cross-backend, cross-proxy RTP — Phase 0 is partially complete (`REQ-RTP-NET-001…014` authored; GLOSSARY entries pending) but the plan itself is a *plan*, not a decision record. Per D-005 (Propose Before Implementation) and the project's ADR conventions, every architecturally significant decision must be captured in an ADR before code lands. Phase 1 of the plan is gated on this ADR existing.

The plan covers concerns at very different layers — wire format, transport bindings, reservation-token semantics, load-balancer math, multi-proxy idempotency, deployment topology, single-artifact activation, security. Folding every one of them into a single ADR would produce a document larger than the plan it ratifies and obscure the decisions that matter most. The pragmatic path is to use **this ADR as the network-mode umbrella** — ratifying scope, headline goals, locked-in decisions, and module shape — and to spin out **subproject ADRs under `platforms/rtp-proxy/docs/adr/`** for narrower concerns (SPI shape, transport bindings, reservation-token state machine, load-balancer scoring, multi-proxy idempotency contract) as Phases 1–4 land them. This mirrors how `effects-api`, `commands-api`, and `rtp-fabric` already maintain their own per-subproject ADR sequences alongside the global one.

Cross-references this ADR ratifies in absolute-state form:

- `MULTI_SERVER_PLAN.md` — full design narrative, phased roadmap, open items.
- `REQUIREMENTS.md §1.6` — `REQ-RTP-NET-001…014`.
- `platforms/rtp-proxy/REQUIREMENTS.md` — `REQ-RTP-PROXY-001…011`.
- `platforms/rtp-proxy/rtp-proxy-common/REQUIREMENTS.md` — `REQ-RTP-PROXY-COMMON-001…008`.
- `platforms/rtp-proxy/rtp-proxy-velocity/REQUIREMENTS.md` — `REQ-RTP-PROXY-VELOCITY-001…007`.
- `platforms/rtp-proxy/rtp-proxy-bungee/REQUIREMENTS.md` — `REQ-RTP-PROXY-BUNGEE-001…007`.
- `docs/admin/proxies/INDEX.md` — admin-facing roster (stub until Phase 3).

## Decision

RTP shall ship an optional **network mode** that coordinates teleport requests across multiple backends fronted by one or more proxies. The decision has eight ratified components.

### 1. Scope & Headline Goals

- **Cross-server load-balanced RTP.** A request issued anywhere on the network shall be dispatched to a backend selected by the configured load-balancer policy. The destination shall be resolved on that backend using its existing async pipeline. The player shall be transferred without disconnect.
- **One distribution artifact.** The same `rtp-plugin` JAR shall activate the correct entry point at runtime based on detected host (Spigot/Paper/Folia/Fabric backend, Velocity proxy, BungeeCord/Waterfall proxy). Per REQ-RTP-NET-003 / REQ-RTP-PROXY-009.
- **Multi-proxy by default.** The design shall not assume a singleton proxy. Single-proxy is the `N=1` degenerate case of the multi-proxy design. Per REQ-RTP-NET-014.
- **Zero behaviour change when disabled.** `network.enabled: false` (default) shall be byte-identical to single-server RTP. This is REQ-RTP-NET-002, the release gate.
- **Minimal proxy config.** `network.yml` shall be copy-paste-identical across all hosts; only `network.serverId` (backends) and `network.proxyId` (proxies) shall differ per host.

### 2. Non-Goals (v1)

- No proxy-side chunk logic, world data, or entity manipulation.
- No replacement of the single-server pipeline; network mode is additive.
- No Forge/NeoForge proxies; revisited only after Fabric stabilises (`MULTI_PLATFORM_PLAN.md` Phase 4).
- No protocol-translation or version-bridging proxies as RTP adapters (Geyser, ViaProxy, custom MCProtocolLib-based proxies). Supported topology places these *in front of* a Velocity/Bungee proxy that hosts RTP.
- No post-arrival coordinate resolution. Coordinates are resolved on the destination *before* transfer (reservation-token model).
- No cross-version protocol breakage without a `schemaVersion` bump.

### 3. Locked-In Decisions

- **D1.** Join-trigger region/world mapping lives proxy-side.
- **D2.** *Durable* reservation tokens (atomic cross-server coordinate claims) shall survive proxy restart and therefore require a durable transport (SQL/Redis). **Amended 2026-06-12** (see *Amendment* below): the `plugin-message` transport is promoted from dev-only to the **tier-1 default, non-durable** transport. It deliberately does not mint durable reservation tokens; it provides connectivity plus region-availability gossip and degrades to a re-issued `/rtp` on a miss. Durable reservation semantics remain exclusive to the SQL/Redis tiers.
- **D3.** Network state lives as a **member of `AbstractSQLDatabaseAccessor`**, not in a parallel hierarchy. The network-state member exposes `BackendHeartbeatTable`, `ReservationTokenTable`, `NetworkCooldownTable`, `ConfigVersionTable`, and a `ProxyStateTable` for proxy heartbeats.
- **D4.** HMAC key distribution shall use the environment variable `RTP_NET_SECRET` for v1. Alternative mechanisms (vault, file, k8s secret bridge) are deferred.
- **Primary proxy:** Velocity. **Secondary proxy:** BungeeCord (with Waterfall covered by the same artifact via runtime detection).
- **Transport tiers (amended 2026-06-12):** `plugin-message` is the **tier-1 non-durable default** (lite + Pro), with `transport.type: auto` resolving to it on proxy auto-detect. Durable Pro tiers, in preference order for large/atomic-claim deployments: Redis (Lettuce; covers RESP-compatible Dragonfly / KeyDB by URL alone) -> Postgres (`LISTEN/NOTIFY` + `SELECT ... FOR UPDATE SKIP LOCKED`) -> generic SQL polling. `in-memory` remains dev/test only.
- **Commands.** `commands-api` and the Brigadier bridge shall be reused; proxy adapters shall not fork a parallel command surface.

### 4. Coordinate Resolution Timing

The destination shall be selected and a coordinate reserved **before** transfer. The sequence is:

1. Backend selector reads one `NetworkSnapshot` and picks a backend.
2. The destination polls one `RTPLocation` from `keptLocations` (L1), falling back to `unkeptLocations` (L2).
3. The location is written into a `ReservationToken` row in state `PENDING`.
4. The proxy executes `PENDING → CLAIMED` atomically (row-count semantics) and triggers the backend rewrite.
5. The destination's join handler consumes the token; the consume operation releases the cache slot.

`fastLocations` and `loginLocations` (ADR-023) shall **not** be consumed for cross-network allocations — they remain per-online-player local on the resident backend.

Cache miss with no `rtp.unqueued` permission shall enroll the player in the UUID-keyed **Network Wait Queue** living in the network-state member; the queue mirrors single-server `playerQueue` FIFO semantics and is drained on the destination backend (proxy-agnostic).

### 5. Multi-Proxy Idempotency Contract

- **No proxy-to-proxy chatter.** All coordination shall flow through the shared store (hub-and-spoke).
- **Idempotent mutations.** `PENDING → CLAIMED` is decided by row-count atomicity; the losing proxy shall surface a `messages.yml` failure and fall through the capped-retry chain.
- **Reanimation.** `CLAIMED` tokens orphaned by a dead proxy shall be re-opened after `claimReanimateMs` (default 5000 ms) and re-claimed by the next surviving proxy.
- **Proxy heartbeat.** Each proxy shall publish a `proxy_state` row keyed by `proxyId` (analogue of `backend_state`). Per REQ-RTP-PROXY-005.
- **Local state is advisory.** Per-proxy state (e.g., `recentPicks` decaying counter) shall not be relied on for safety-critical decisions. A shared `recentPicks` mode is deferred to v2.

### 6. Load Balancer

A single configurable weighted-average policy shall be shipped (no discrete strategy enum):

```
score(b) = Σ_i  weight_i  *  curve_i( normalize_i( metric_i(b) ) )
```

Lowest score wins; ties broken by `serverIdAsc`. Supported curves: `linear`, `exponential`, `logarithmic`, `sigmoid`, `step`, `power`, with bounded parameters (`k ∈ [0.1, 20]`, `p ∈ [0.1, 8]`, `threshold ∈ [0.0, 1.0]`).

Fallback chain (Linux CFS-inspired): `maxRetries: 3`, `attemptTimeoutMs: 1500`, `cooldownMs: 2000` hysteresis on rejected backends, score-sticking on in-flight requests, single snapshot read per request.

`playerCount` shall be published in telemetry but ship with `weight: 0`; the weight shall be re-evaluated after Phase 2 live testing. Concrete scoring math, curve plots, and tuning guide are captured in subproject ADR `rtp-proxy-ADR-004-weighted-average-selector`.

### 7. Module Shape

```
platforms/rtp-proxy/
├── rtp-proxy-common/      # SPI, dispatcher, BackendSelector, transport iface
├── rtp-proxy-velocity/    # Phase 2 (primary)
└── rtp-proxy-bungee/      # Phase 3 (secondary; BungeeCord + Waterfall)
```

Backend-side glue (`NetworkBridge`, `BackendStatePublisher`, `ReservationTokenTable`, etc.) shall live in **`rtp-core`** as optional, default-disabled subsystems exposed through the network-state member of `AbstractSQLDatabaseAccessor`. **No proxy imports in `rtp-core` or `rtp-api`.** Proxy adapters shall not import each other and shall not depend on backend-side platform classes. Per REQ-RTP-PROXY-010.

Single-artifact activation shall use loader-detection (analogue of `rtp-fabric-ADR-002`): presence of `com.velocitypowered.api.proxy.ProxyServer` activates the Velocity entry point; presence of `net.md_5.bungee.api.ProxyServer` activates the Bungee entry point; otherwise the backend entry point loads.

### 8. Phased Delivery & Subproject ADR Plan

Phases are inherited from `MULTI_SERVER_PLAN.md`; this ADR ratifies the *order* and the *spin-out points* for subproject ADRs.

| Phase | Scope | Subproject ADRs to author (under `platforms/rtp-proxy/docs/adr/`) |
|---|---|---|
| **0** | Scope unlock (docs only) | `rtp-proxy-ADR-001-spi-shape` |
| **1** | Core SPI, in-memory binding, weighted-average selector, no-op contract test | `rtp-proxy-ADR-002-network-yml-schema`, `rtp-proxy-ADR-003-in-memory-binding`, `rtp-proxy-ADR-004-weighted-average-selector` |
| **2** | Velocity + Redis end-to-end | `rtp-proxy-ADR-005-redis-binding`, `rtp-proxy-ADR-006-velocity-bootstrap` |
| **3** | Postgres + join trigger + BungeeCord adapter | `rtp-proxy-ADR-007-postgres-binding`, `rtp-proxy-ADR-008-bungee-bootstrap` |
| **4** | Generic SQL + hardening + release | `rtp-proxy-ADR-009-generic-sql-binding`, `rtp-proxy-ADR-010-security-hardening` |

Subproject ADR numbering restarts at `001` inside `platforms/rtp-proxy/docs/adr/` per the per-directory naming rule in `AGENTS.md > Self-Updating Protocol`. The global `docs/adr/` sequence shall not be used for narrower network-mode concerns once this umbrella ADR is accepted; only umbrella-level reversals or successors (e.g., a future ADR that supersedes this one) shall consume global ADR numbers.

## Amendment (2026-06-12): Plugin-Message Promoted to Tier-1 Default

Ratified by repo owner leaf from the approved D-005 proposal `docs/dev/scratch/PROPOSAL-plugin-message-network-default.md`. This amends D2 and the transport ordering above; the eight ratified components and all safety/non-goal clauses are otherwise unchanged.

- **Default transport.** The `plugin-message` transport is promoted from dev-only to the **tier-1, non-durable default**, shipped in both lite and Pro. It carries the existing `BackendHeartbeat` (region availability, warm-cache counts, load) over the proxy's built-in plugin-messaging vocabulary (`Connect` for the player move, `Forward` for heartbeat gossip), so a database is **not required** for cross-server RTP on most networks. EzRTP parity-and-beyond: it transmits real region availability rather than a bare up/down ping.
- **Auto-detection (`transport.type: auto`).** A passive `spigot.yml` / `paper-global.yml` probe arms network mode; an active `GetServer` / `GetServers` handshake on first player join confirms the proxy and learns the topology, so no hand-typed `servers:` list is required. Re-probe on first join and on proxy reconnect.
- **No SPI change.** Tier-1 is a peer implementation of `NetworkTransport`; the dispatcher, `BackendSelector`, reservation, and command/tab-complete layers are unchanged and transport-agnostic. The non-durable `claim`/`redeem`/`release`/`reapExpired` methods are best-effort / no-op in tier-1.
- **Honest limits (durable-tier upgrade boundary).** Everything rides an online player's connection (a player-empty or idle-self-paused Fabric/NeoForge backend cannot broadcast, so its availability goes stale; treat unknown/stale as accept and let the destination decide); single-proxy `Forward` fan-out only; no durable reservation across a proxy restart. Multi-proxy, always-fresh-availability, and atomic-claim deployments move to the SQL/Redis tiers.
- **Safety unchanged.** The destination always runs the normal local pipeline (spiral -> chunk -> safety), so S-001/S-004/S-005 hold; tier-1 transports *intent*, not a coordinate. Move failures are logged, never swallowed (S-004); busy/invalid messages are configurable (S-007).
- **Contract detail:** subproject ADR [`rtp-proxy-ADR-016-plugin-message-default-transport`](../../platforms/rtp-proxy/docs/adr/rtp-proxy-ADR-016-plugin-message-default-transport.md). Lite-assembly impact: [ADR-024](ADR-024-rtp-lite-assembly-variant.md). Plan amendment: `docs/dev/MULTI_SERVER_PLAN.md` *Amendment: Plugin-Message Default Tier*.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| One mega-ADR covering wire format, transports, scoring, security, deployment | Would exceed the plan in size; obscures the decisions that matter most; violates ADR template intent (one decision per record). |
| Inline every subproject ADR's content here and forgo `platforms/rtp-proxy/docs/adr/` | Loses the per-subproject ADR convention already used by `effects-api`, `commands-api`, `rtp-fabric`; couples narrow technical reversals (e.g., swapping Lettuce for Redisson) to an umbrella reversal. |
| Postpone umbrella ratification until every subproject ADR is drafted | Blocks Phase 1 indefinitely; `REQ-RTP-NET-001…014` and the four `REQUIREMENTS.md` files would remain D-005-pending with no upper-bound. The umbrella exists precisely to unblock incremental subproject work. |
| Skip the umbrella; promote each subproject ADR to a global ADR | Pollutes the global ADR sequence with narrow proxy concerns; breaks the established per-subproject convention; makes it impossible to point external readers at "the network-mode decision" with a single link. |
| Treat proxy-side and backend-side as two separate umbrella ADRs | The reservation-token contract, schema negotiation, and HMAC are inseparable across the wire; splitting the umbrella along the proxy/backend boundary produces two ADRs that mutually reference each other on every clause. |

## Consequences

- **Positive:**
  - Phase 1 can begin: `rtp-proxy-common` SPI, in-memory binding, no-op contract test (REQ-RTP-NET-002 release gate).
  - The four already-authored `REQUIREMENTS.md` files lose their "D-005-gated on ADR-036" qualifier and become enforceable module contracts.
  - Subproject ADRs `rtp-proxy-ADR-001…010` have a clear umbrella to defer to for headline goals, non-goals, and locked decisions — they only need to ratify their narrow concern.
  - Multi-proxy is a first-class constraint from Phase 1 onward; no retrofitting a singleton-proxy assumption later.
  - `TRACEABILITY.md` rows for `REQ-RTP-NET-*` and `REQ-RTP-PROXY-*` can be opened (unimplemented status) and linked to this ADR.
- **Negative / Trade-offs:**
  - The umbrella becomes a single point of revision for headline-goal changes; reversing a locked decision (e.g., dropping Bungee) requires a superseding global ADR rather than a local subproject ADR.
  - Readers must traverse two levels (umbrella + subproject ADR) to understand a single concern end-to-end. Mitigated by a cross-reference table in `platforms/rtp-proxy/docs/adr/README.md` (to be created in Phase 1).
  - Subproject ADRs that touch backend-side state (e.g., the network-state member schema formalised under `rtp-proxy-ADR-003-in-memory-binding`) live under `platforms/rtp-proxy/docs/adr/` for cohesion with the proxy story even though they technically describe a `rtp-core` member. The trade-off is consistency-of-narrative over strict directory-by-owner; documented here so future contributors don't re-litigate the placement.
  - Acceptance of this ADR is *not* a green light for code: each phase still requires its subproject ADR(s) under D-005 before implementation in that phase begins.

## References

- `docs/dev/MULTI_SERVER_PLAN.md` — full plan narrative and phased roadmap.
- `docs/dev/REQUIREMENTS.md §1.6` — `REQ-RTP-NET-001…014`.
- `platforms/rtp-proxy/REQUIREMENTS.md`, `platforms/rtp-proxy/rtp-proxy-common/REQUIREMENTS.md`, `platforms/rtp-proxy/rtp-proxy-velocity/REQUIREMENTS.md`, `platforms/rtp-proxy/rtp-proxy-bungee/REQUIREMENTS.md`.
- `docs/admin/proxies/INDEX.md` — admin-facing roster (stub).
- `docs/adr/ADR-018-agents-md-public-release-structure.md` — public release / docs structure.
- `docs/adr/ADR-026-external-hook-api-surface.md` — external integration boundaries (claim / economy / PAPI / world border / anvil prefilter).
- `platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md` — loader-detection precedent for single-artifact activation.
- `effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md` — per-subproject ADR numbering precedent.
- `commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md` — Brigadier-bridge reuse rationale.
- `.junie/AGENTS.md` — D-005, Self-Updating Protocol, per-subproject ADR naming rule.

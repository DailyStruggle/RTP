# rtp-proxy-ADR-006 — Velocity Adapter Bootstrap

**Status:** Accepted
**Date:** 2026-05-13 (Accepted 2026-05-18)
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md), [rtp-proxy-ADR-005](rtp-proxy-ADR-005-redis-binding.md)

## Context

Velocity is the primary proxy target (Phase 2). It is the only proxy in the v1 plan that hosts a Brigadier command manager, supports `ServerPreConnectEvent` mid-session backend rewrites, and ships a public `Scheduler` async pool. This ADR pins how `rtp-proxy-velocity` plugs into the host runtime.

## Decision

### Plugin Identity (REQ-RTP-PROXY-VELOCITY-007)

- `@Plugin(id = "rtp", name = "RTP", version = <umbrella>, authors = …, dependencies = {})`.
- Version coupled to the `rtp-plugin` umbrella version. Mismatched proxy/backend versions are detected at first heartbeat via `schemaVersion` (ADR-010) and produce a configurable warning.
- Target: **Velocity 3.3.x or later, Java 21+** (REQ-RTP-PROXY-VELOCITY-001).

### Activation (REQ-RTP-PROXY-009)

The plugin's `@Subscribe ProxyInitializeEvent` handler:

1. Loads `network.yml` (ADR-002).
2. If `network.enabled: false` → **no listeners registered, no scheduler tasks, no transport opened.** Plugin remains loaded so `/rtp` admin commands (`reload`, `test`) still function, but every operational path no-ops (REQ-RTP-PROXY-008).
3. If enabled → boot in this order: `NetworkTransport` (per `transport.type`) → `BackendSelector` → `ReservationClient` → `ProxyStatePublisher` → trigger sources → command registration.

Shutdown (`@Subscribe ProxyShutdownEvent`) tears down in reverse order; `NetworkTransport.close()` is awaited with a 2s deadline.

### Server Rewrite via `ServerPreConnectEvent` (REQ-RTP-PROXY-VELOCITY-003)

Network teleports flow through the `ServerPreConnectEvent` listener:

1. Listener intercepts; if the event already targets the player's RTP-resolved destination (tagged via a `correlationId` map keyed by `playerId`), pass through.
2. Otherwise, **do not block** the event. Call `event.setResult(ServerPreConnectEvent.ServerResult.denied())` only when the dispatcher has surfaced a `Failed` outcome.
3. The dispatcher's coordination work runs entirely on `proxyServer.getScheduler().buildTask(plugin, …).schedule()` (async pool). The listener returns immediately.
4. Once `ReservationClient` resolves `Routed(serverId, tokenId)`, the listener emits a fresh `player.createConnectionRequest(target).fireAndForget()` (or re-fires the original via `ServerResult.allowed(target)` if still in flight).

### Player Session Continuity (REQ-RTP-PROXY-VELOCITY-004)

- A failed transfer **never** disconnects the player. The dispatcher's `Failed` outcome calls `ProxySender.sendMessage(playerId, messageKey, placeholders)` and leaves the player on their current backend.
- `isConnected(playerId)` is checked before every transport claim attempt; disconnected players have their pending claim released immediately (REQ-RTP-NET-011 TTL is the secondary safety net).

### Command Hosting (REQ-RTP-PROXY-VELOCITY-002)

- `/rtp` registered through `proxyServer.getCommandManager().register(meta, new BrigadierCommand(rootNode))`.
- Root node built by the **shared `commands-api` Brigadier bridge** (`commands-api-ADR-001`); the adapter contributes only the Velocity `BrigadierBridgeContext` (sender adapter, permission lookup, locale resolver).
- Tab-completion goes through `BackendTabCompletionRouter`, which fan-outs a bounded query (REQ-RTP-PROXY-011) and serves from a per-proxy time-bounded cache.

### Telemetry (REQ-RTP-PROXY-VELOCITY-006)

`ProxyStatePublisher`:

- Built with `proxyServer.getScheduler().buildTask(plugin, runnable).repeat(heartbeat.intervalMs, TimeUnit.MILLISECONDS).schedule()`.
- Writes `proxy_state` row keyed by `proxyId` (UPSERT semantics on the transport).
- Snapshots include: `proxyId`, `schemaVersion`, `playerCount`, `connectedBackends[]`, `pendingTokens`, `lastSeenEpochMs`. **No** netty-internal stats in v1.
- Runs **only** on the async pool; a runtime check (`Thread.currentThread().getName().startsWith("Netty")`) fails fast in development with an `IllegalStateException`.

### Threading Discipline

- All consumer-side hops from transport listeners use `scheduler.buildTask(plugin, runnable).schedule()`, **never** `eventManager.fireAndForget` from a transport thread.
- The plugin maintains zero per-tick state; all caches use `Caffeine` with explicit `expireAfterWrite`.

### Bundled Resources

- `src/main/resources/velocity-plugin.json` declares the entry class.
- `src/main/resources/network.yml` ships the canonical default (ADR-002) so a new deployment is one copy.
- `src/main/resources/messages/network.<locale>.yml` carries the configurable strings (REQ-RTP-PROXY-006).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Register a Velocity-only `RawCommand` and skip Brigadier | Loses argument-level tab-completion; diverges from Paper/Folia behaviour; defeats `commands-api` consolidation. |
| Block in `ServerPreConnectEvent` until claim resolves | Stalls the netty event loop; trivially DoS-able by sending many transfer requests. |
| Issue `player.createConnectionRequest` directly without `ServerPreConnectEvent` integration | Plugins that hook the event (anti-abuse, audit) miss our transfer; ecosystem-unfriendly. |
| Per-player `ScheduledTask` for claim work | Memory/CPU overhead at scale; one shared async pool is sufficient (Velocity sizes it appropriately). |

## Consequences

- **Positive:** the Velocity adapter is small (entry + listener + publisher + tab router); most logic lives in `rtp-proxy-common`. Operators get a single-JAR install.
- **Negative:** Brigadier coupling means `commands-api` regressions can break the proxy too; mitigated by the bridge's own contract tests in Phase 1.

## References

- ADR-036; `MULTI_SERVER_PLAN.md` Phase 2 acceptance.
- `REQ-RTP-PROXY-VELOCITY-001…007`; `REQ-RTP-PROXY-003`, `-005`, `-006`, `-009`, `-011`.
- `commands-api-ADR-001` (Brigadier bridge).

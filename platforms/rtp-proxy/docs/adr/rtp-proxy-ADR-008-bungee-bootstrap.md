# rtp-proxy-ADR-008 — BungeeCord / Waterfall Adapter Bootstrap

**Status:** Proposed
**Date:** 2026-05-13
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md), [rtp-proxy-ADR-006](rtp-proxy-ADR-006-velocity-bootstrap.md)

## Context

BungeeCord is the secondary proxy target (Phase 3). Waterfall (and tolerated forks Hexacord/FlameCord/Travertine) share the BungeeCord API surface — a **single artifact** must work on all of them (REQ-RTP-PROXY-BUNGEE-001, -007). BungeeCord differs from Velocity in three ways that drive this ADR:

- **No Brigadier.** BungeeCord ships a `Command`/`TabExecutor` API; `commands-api`'s non-Brigadier surface must be used.
- **No async event chain.** `ServerConnectEvent` is the closest analogue to Velocity's `ServerPreConnectEvent` but is synchronous (handlers run on the upstream-bridge thread).
- **Different scheduler.** `ProxyServer#getScheduler()` returns a `TaskScheduler` with `runAsync` semantics; there is no public-stable async pool size knob.

## Decision

`RTPBungeePlugin extends net.md_5.bungee.api.plugin.Plugin` in `rtp-proxy-bungee`, single Maven coordinate, runs unchanged on BungeeCord and Waterfall.

### Activation (REQ-RTP-PROXY-009)

`onEnable()`:

1. Load `network.yml` (ADR-002).
2. If `network.enabled: false` → register only the admin command surface (reload, test); no listeners, no transport (REQ-RTP-PROXY-008).
3. If enabled → boot the same component stack as the Velocity adapter (transport → selector → reservation client → heartbeat publisher → triggers → commands).

`onDisable()` tears down in reverse order. BungeeCord's plugin shutdown is best-effort during proxy stop; transport `close()` is awaited with a 1s deadline (BungeeCord kills the JVM aggressively).

### Server Rewrite via `ServerConnectEvent` (REQ-RTP-PROXY-BUNGEE-002)

The listener subscribes to `ServerConnectEvent` with `@EventHandler(priority = HIGHEST)` and uses `event.registerIntent(plugin)` + `event.completeIntent(plugin)` to **defer** the connection without blocking the upstream-bridge thread:

1. On intercept, the listener registers an intent and dispatches the claim work to `proxy.getScheduler().runAsync(plugin, …)`.
2. When `ReservationClient` resolves `Routed(serverId, tokenId)`, the listener calls `event.setTarget(serverInfo)` and completes the intent.
3. On `Failed`, it cancels the event (`event.setCancelled(true)`), surfaces the configurable message, and completes the intent so the player remains on their current backend (REQ-RTP-PROXY-VELOCITY-004 analogue).

This is the BungeeCord-idiomatic "async event continuation" pattern and is necessary because synchronous handlers cannot block on transport I/O without stalling every reconnect across the proxy.

### Command Hosting (REQ-RTP-PROXY-BUNGEE-003)

- `/rtp` registered via `proxy.getPluginManager().registerCommand(plugin, new RtpBungeeCommand(...))`.
- The command class extends BungeeCord's `Command` + implements `TabExecutor`.
- Argument parsing and dispatch reuse the **non-Brigadier `commands-api` surface** (the same surface Spigot already targets). No Velocity-only classes are dragged in.
- Tab-completion routes through `BackendTabCompletionRouter` (shared with Velocity, REQ-RTP-PROXY-BUNGEE-006).

### Scheduler Discipline (REQ-RTP-PROXY-BUNGEE-004)

- All persistence and transport calls invoked from event handlers go through `proxy.getScheduler().runAsync(plugin, runnable)`.
- A runtime guard inspects `Thread.currentThread().getName()` and throws `IllegalStateException` if the SPI is invoked from the upstream-bridge thread (caught in development; logged + recovered in production).

### Plugin-Message Transport Eligibility (REQ-RTP-PROXY-BUNGEE-005)

When `transport.type: plugin-message`:

- The plugin emits a startup `WARNING` ("plugin-message transport is dev-only; enable a durable transport for production").
- A `developerMode: true` config knob (default false, **not** in the canonical schema — a guarded extension) is required to actually open the transport. Without it, network mode refuses to enable.
- This satisfies D2 (durable transport required for production) without removing the developer affordance.

### Waterfall / Fork Tolerance (REQ-RTP-PROXY-BUNGEE-007)

- No Waterfall-specific imports. The adapter uses only `net.md_5.bungee.api.*` types.
- A runtime detection (`Class.forName("io.github.waterfallmc.waterfall.event.…")`) enables Waterfall-only event hooks **defensively** — if present, use them; if absent, fall back to BungeeCord behaviour.
- Forks (Hexacord, FlameCord, Travertine) are not separately tested; the contract is "if BungeeCord API works, the adapter works".

### Bundled Resources

- `src/main/resources/bungee.yml` declares the entry class (BungeeCord plugin descriptor).
- `src/main/resources/network.yml` is the byte-identical canonical default (ADR-002).
- `src/main/resources/messages/network.<locale>.yml` carries configurable strings (REQ-RTP-PROXY-006).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Block `ServerConnectEvent` synchronously while claim resolves | Stalls every other reconnect on the upstream-bridge thread; trivially DoS-able. |
| Use `ProxiedPlayer#connect(serverInfo)` outside the event | Bypasses event listeners other plugins rely on (anti-cheat, audit); ecosystem-unfriendly. |
| Ship Waterfall-specific build | Violates REQ-RTP-NET-003 (single artifact); operators would have to choose. |
| Inline a Brigadier shim for BungeeCord | BungeeCord's command framework is incompatible with Mojang Brigadier without protocol-level surgery; out of scope. |

## Consequences

- **Positive:** Phase 3 is a thin adapter (entry + listener + command + publisher), all reusing `rtp-proxy-common`. Waterfall is free via API parity.
- **Negative:** intent-based deferred connect requires careful handling to avoid leaking intents on player disconnect (a `PlayerDisconnectEvent` listener cancels pending intents); this is the primary regression-test target for Phase 3.

## References

- ADR-036; `MULTI_SERVER_PLAN.md` Phase 3.
- `REQ-RTP-PROXY-BUNGEE-001…007`; `REQ-RTP-PROXY-003`, `-005`, `-006`, `-009`, `-011`.
- D2 (durable transport required outside dev).

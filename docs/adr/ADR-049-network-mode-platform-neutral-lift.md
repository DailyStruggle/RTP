# ADR-049 - Network-mode plumbing in `rtp-core`, platform-specific via `PlayerLifecycleHook` SPI

**Status:** Accepted
**Date:** 2026-05-23
**Supersedes:** [rtp-fabric-ADR-013](../../rtp-fabric/docs/adr/rtp-fabric-ADR-013-network-mode-bootstrap-parity.md) (was Proposed 2026-05-22; the rejected-alternatives analysis in that ADR is based on an incorrect coupling estimate, see Context below)
**Related:** [ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md) (umbrella network-mode ADR), [rtp-proxy-ADR-014](../../rtp-proxy/docs/adr/rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md), [rtp-proxy-ADR-015](../../rtp-proxy/docs/adr/rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md), [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) (Fabric in scope), [`MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md), [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md) Step J.

## Context

Network-mode multi-server support landed end-to-end on Paper in this cycle (user-confirmed working 2026-05-22). The Phase 1 SPI in `rtp-proxy-common` is platform-agnostic and already reachable from any backend JVM, but the Phase 2 backend integration currently lives under `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/network/` (`NetworkModeBootstrap` and twelve neighbouring classes). A Velocity-routed player arriving on a Fabric backend with a reservation token has no listener to redeem it: the lookup happens but the redeem-side handler is Bukkit-only. The cross-server flow therefore silently degrades on Fabric, violating the Phase 2 acceptance criterion in `MULTI_SERVER_PLAN.md` that says "a backend that advertises `RtpTriggerSource` MUST redeem reservation tokens on join".

[rtp-fabric-ADR-013](../../rtp-fabric/docs/adr/rtp-fabric-ADR-013-network-mode-bootstrap-parity.md) proposed solving this by reimplementing `NetworkModeBootstrap` as a parallel `FabricNetworkModeBootstrap`. Its central rejection of the refactor path leaned on the claim that the Bukkit class has "eight independent coupling points to `org.bukkit.*` (`Plugin`, `BukkitScheduler`, `PluginManager`, `Listener`, `PlayerJoinEvent`, `EventHandler`, `EventPriority`, `Bukkit.getOnlinePlayers()`)" and that a base-class refactor would produce "a near-equal amount of Fabric-side code anyway".

This claim was re-audited on 2026-05-23 and is incorrect. A precise count of `org.bukkit.*` imports in every file under `rtp-plugin/.../bukkit/network/`:

| Class | `org.bukkit.*` imports |
|---|---|
| `NetworkModeBootstrap` (1108 lines) | 0 |
| `NetworkRouter` | 0 |
| `NetworkStatusCache` | 0 |
| `NetworkEnrolmentBuffer` | 0 |
| `PeerRegionRegistry` | 0 |
| `LobbyDispatchRetryQueue` | 0 |
| `NetworkRegionCollisionWarner` | 0 |
| `RoutingDecision` | 0 |
| `BukkitNetworkCommandHook` | 0 |
| `JoinTriggerSource` | 7 (`Bukkit`, `Player`, `EventHandler`, `EventPriority`, `Listener`, `PlayerJoinEvent`, `PlayerQuitEvent`) |
| `NetworkWaitlistQuitListener` | 4 (`EventHandler`, `EventPriority`, `Listener`, `PlayerQuitEvent`) |
| `NetworkWaitlistNotifier` | 2 (`Bukkit`, `Player`) |
| `NetworkWaitlistGuard` | 2 (`CommandSender`, `Player`) |

Of thirteen classes, nine have zero Bukkit imports. The four that do touch the Bukkit API touch it for one of two reasons:

1. Subscribing to player join / quit events (`Listener`, `@EventHandler`, `PlayerJoinEvent`, `PlayerQuitEvent`).
2. Looking up a player or sender by UUID (`Bukkit.getPlayer(uuid)`, `org.bukkit.entity.Player`, `org.bukkit.command.CommandSender`).

`RTPServerAccessor` already covers (2) end-to-end: `getPlayer(UUID)`, `getSender(UUID)`, `sendMessage`, `getScheduler`. The only primitive missing from the SPI is a player-join / player-quit lifecycle hook for (1).

The Bukkit class's "platform coupling surface" is therefore one missing SPI method, not eight, and the surface is shared with every other backend platform that already routes player operations through `RTPServerAccessor`. The cost calculus that drove ADR-013 toward reimplementation no longer holds.

## Decision

Lift the network-mode plumbing into `rtp-core` and add a single new platform SPI primitive (`PlayerLifecycleHook`) accessed via `RTPServerAccessor`. Each backend platform supplies a shim that registers a join/quit subscription and routes the resulting UUIDs to the platform-neutral handler. There is no second backend bootstrap class.

### 1. New SPI: `PlayerLifecycleHook`

A new interface in `rtp-api`:

```
package io.github.dailystruggle.rtp.api.server;

public interface PlayerLifecycleHook {
    AutoCloseable onPlayerJoin(java.util.function.Consumer<java.util.UUID> handler);
    AutoCloseable onPlayerQuit(java.util.function.Consumer<java.util.UUID> handler);
}
```

`RTPServerAccessor` gains:

```
default PlayerLifecycleHook getPlayerLifecycleHook() {
    return NoopPlayerLifecycleHook.INSTANCE;
}
```

The default returns a no-op that ignores subscriptions. Existing `RTPServerAccessor` implementations (`AbstractServerAccessor` for Bukkit/Paper/Folia, `FabricServerAccessor`, `MockRTPServerAccessor`) do not need to be touched to compile; each backend platform overrides only when it wants real lifecycle hooks (Bukkit and Fabric will; the mock does not).

### 2. Lifted classes in `rtp-core`

Moved verbatim (zero behavioural change) from `rtp-plugin/.../bukkit/network/` to `rtp-core/.../common/network/`:

- `NetworkModeBootstrap`
- `NetworkRouter`
- `NetworkStatusCache`
- `NetworkEnrolmentBuffer`
- `PeerRegionRegistry`
- `LobbyDispatchRetryQueue`
- `NetworkRegionCollisionWarner`
- `RoutingDecision`

These nine classes (counting `NetworkModeBootstrap`) have no Bukkit imports today and require only a package rename plus updates to their import sites elsewhere in the codebase.

### 3. Lifted listener classes (signature change)

Moved to `rtp-core/.../common/network/` with their event-registration surface replaced by `PlayerLifecycleHook` calls:

- `JoinTriggerSource`. Removes `implements Listener` and the two `@EventHandler` methods. Gains `register(PlayerLifecycleHook hook)` that calls `hook.onPlayerJoin(uuid -> handleJoin(uuid))` and `hook.onPlayerQuit(uuid -> handleQuit(uuid))`. The body of `handleJoin` / `handleQuit` is the existing event-handler body with `event.getPlayer().getUniqueId()` replaced by the inbound `uuid` and `Bukkit.getPlayer(uuid)` replaced by `RTP.serverAccessor.getPlayer(uuid)`.
- `NetworkWaitlistQuitListener`. Same treatment: lose `Listener` + `@EventHandler`, gain a `register(PlayerLifecycleHook)` that subscribes to `onPlayerQuit`.
- `NetworkWaitlistNotifier`. Replace `Bukkit.getPlayer(uuid)` with `RTP.serverAccessor.getPlayer(uuid)`; replace `Player.isOnline()` with a `null`-or-`isOnline` check via `RTPPlayer`.
- `NetworkWaitlistGuard`. Change `Predicate<CommandSender>` to `Predicate<RTPCommandSender>`. The single caller is `RTPCmdBukkit`; that caller wraps its incoming `CommandSender` with the existing `RTPCommandSender` adapter before invoking the predicate.

### 4. `BukkitNetworkCommandHook` and `BukkitBackendStateSampler`

> **Addendum (2026-05-23, post-acceptance audit):** the original Decision text under-counted the platform coupling at this step. While neither class imports `org.bukkit.*`, both are **directly instantiated by name** inside `NetworkModeBootstrap` (`new BukkitBackendStateSampler(lobbyMode)` at line ~234; `new BukkitNetworkCommandHook(...)` at line ~551, exposed via `commandHook()`). A literal lift to `rtp-core` is therefore impossible without further SPI extraction. This addendum supersedes the original step 4 as the authoritative guidance.
>
> **Resolution.** Introduce two additional platform-injected types so the bootstrap depends only on `rtp-core` interfaces:
>
> - `BackendStateSampler` interface in `rtp-core` (or `rtp-api`). Methods cover the small surface today's `BukkitBackendStateSampler` exposes (current player count, TPS, MSPT sample, world list, lobby-mode flag). Concrete implementations: `BukkitBackendStateSampler` (existing, moves only if its package changes) and a new `FabricBackendStateSampler` that pulls equivalent metrics via `RTPServerAccessor` / `FabricVersionAdapter`.
> - `NetworkCommandHook` interface in `rtp-core`. Existing `BukkitNetworkCommandHook` becomes one implementation; new `FabricNetworkCommandHook` mounts the network sub-commands into the Fabric Brigadier tree (via the existing `BrigadierCommandAdapter` per [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)).
>
> Accessed through new `RTPServerAccessor` factory / getter methods:
>
> ```java
> default BackendStateSampler createBackendStateSampler(boolean lobbyMode) {
>     throw new IllegalStateException(
>         "Platform does not provide a BackendStateSampler; network mode requires one.");
> }
>
> default NetworkCommandHook getNetworkCommandHook() {
>     return NoopNetworkCommandHook.INSTANCE;
> }
> ```
>
> `NetworkModeBootstrap` replaces its two direct `new` calls with `RTP.serverAccessor.createBackendStateSampler(lobbyMode)` and `RTP.serverAccessor.getNetworkCommandHook()`. The `commandHook()` getter on the bootstrap stays but now returns the SPI type.
>
> **Scope impact.** This raises the lift estimate from "mechanical move + 1 new SPI" to "mechanical move + 3 new SPIs + 1 new Fabric metric sampler + 1 new Fabric command hook". The Fabric command hook in particular is non-trivial work that may overlap with other Fabric command-tree gaps; estimate it separately before scheduling. The session that authored this addendum did not start the lift — see *Implementation plan* below for the updated checklist.

### 5. Platform shims

- `BukkitPlayerLifecycleHook` in `rtp-bukkit-common`. Implements `PlayerLifecycleHook`. Constructs a `Listener` with `@EventHandler` methods on `PlayerJoinEvent` and `PlayerQuitEvent`, registers it via `Bukkit.getPluginManager().registerEvents(...)` on first subscription, and routes events to the registered `Consumer<UUID>` handlers. Each `AutoCloseable` returned unsubscribes the handler; closing the last handler unregisters the listener. `AbstractServerAccessor.getPlayerLifecycleHook()` returns a lazily-created singleton.
- `FabricPlayerLifecycleHook` in `rtp-fabric-common`. Implements `PlayerLifecycleHook` by hooking `ServerPlayConnectionEvents.JOIN` and `ServerPlayConnectionEvents.DISCONNECT`. On the obf carrier the same events are reached through the intermediary surface per [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md); on the unobf carrier through the deobf API directly. The dispatch can go through `FabricVersionAdapter` so the shim class itself does not need an obf / unobf split. `FabricServerAccessor.getPlayerLifecycleHook()` returns the singleton.

### 6. Wiring

- `RTPBukkitPlugin.onEnable`: import `NetworkModeBootstrap` from its new package and call `boot(networkYml)`. The bootstrap pulls its lifecycle hook from `RTP.serverAccessor.getPlayerLifecycleHook()` internally. No `Plugin` reference passes through the bootstrap entry point.
- `RTPCmdBukkit`: update the waitlist guard call site to wrap `CommandSender` in `RTPCommandSender` once at the predicate boundary.
- Fabric mod entrypoint (`RTPFabricMod.onInitialize` or equivalent): construct `NetworkModeBootstrap`, call `boot(networkYml)` after the version adapter installs and before the command tree wires. `ServerLifecycleEvents.SERVER_STOPPING` calls `bootstrap.shutdown()`. The path mirrors the Bukkit entrypoint line-for-line.

### 7. `LIVE` singleton

The `LIVE` static on `NetworkModeBootstrap` stays exactly as it is. It is a per-process singleton today; lifted to `rtp-core` it remains a per-process singleton, and works the same on either platform. Command-side code (`RTPCmdBukkit`, future `RTPCmdFabricRoot`) reads `NetworkModeBootstrap.LIVE` identically.

### 8. Test moves

These tests move with the code:

- `LobbyModeEarlyReadTest`
- `ReqRtpNet015NetworkWaitlistTest`
- `NetworkRouterTest`
- `NetworkStatusCacheTest`

They lose their Bukkit imports (where any exist) and exercise the lifted classes against `MockRTPServerAccessor` plus a synthetic `PlayerLifecycleHook` test fixture. All existing assertions remain.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Reimplement `NetworkModeBootstrap` as a parallel `FabricNetworkModeBootstrap` (rtp-fabric-ADR-013) | Predicated on an "8 Bukkit coupling points in the bootstrap" estimate that does not hold: the bootstrap has zero Bukkit imports, and the actual surface is one missing SPI primitive (player join / quit). The reimplement path would duplicate ~1108 lines of platform-neutral bootstrap plus ~300 lines of helpers, on a code path that will keep evolving through `MULTI_SERVER_PLAN.md` Phase 3 (Postgres `LISTEN/NOTIFY`, BungeeCord transport, multi-transport composition). Each future phase would land twice. |
| Refactor `NetworkModeBootstrap` into a platform-neutral abstract base class with a Bukkit subclass and a Fabric subclass | Abstract-base inheritance is heavier than this case warrants. The only platform-varying primitive is "subscribe to player join / quit"; a one-method interface plus composition is simpler than an abstract base with subclass-only overrides, and lets `MockRTPServerAccessor` plug a synthetic hook in for tests without subclassing. |
| Defer Fabric network mode until Phase 3 | Step J of `MULTI_PLATFORM_PLAN.md` is the missing acceptance criterion that downgrades Fabric from "first-class platform" to "single-server only" backend. Deferring violates [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md). |
| Share `JoinTriggerSource` between platforms via a `Listener` shim on Fabric | Forces a non-trivial event-system shim on Fabric (Fabric has no `Listener` concept). The `PlayerLifecycleHook` SPI is the cleaner inversion: platform owns event wiring, core owns the handler body. |
| Place the lifted classes in `rtp-network-common` (new module) | A new Gradle module costs setup overhead and adds a build edge for no benefit; `rtp-core` already depends on `rtp-proxy-common` (this is established by the existing Bukkit class's imports from `io.github.dailystruggle.rtp.proxy.common.*`). The classes belong in `rtp-core/.../common/network/` next to the existing `network/` subpackage. |
| Defer the `NetworkWaitlistGuard` signature change | `Predicate<CommandSender>` is unsafe to keep once the class is in `rtp-core` (which forbids `org.bukkit.*` imports). The single caller in `RTPCmdBukkit` is a 1-line adapter wrap. |

## Consequences

### Positive

- Closes Step J in `MULTI_PLATFORM_PLAN.md`. Cross-server RTP arrival on a Fabric backend redeems reservation tokens, matching the documented Phase 2 contract, with zero duplicate code.
- Future `MULTI_SERVER_PLAN.md` phases (BungeeCord adapter, Postgres `LISTEN/NOTIFY`, multi-transport composition) land once in `rtp-core` and ship on every platform simultaneously.
- `PlayerLifecycleHook` is reusable for any future cross-cutting concern that needs "do X when a player joins / leaves" without leaking platform-specific event types into core (e.g. `MULTI_SERVER_PLAN.md` lobby-side UX work, `metrics-api` per-player heartbeats).
- The Bukkit class moves verbatim apart from one line (`registerEvents` to `lifecycleHook.onPlayerJoin`), preserving the user-confirmed-working Paper code path. All existing Paper / Folia tests move with the code and stay green.
- `MockRTPServerAccessor` can supply a synthetic `PlayerLifecycleHook` that drives test-controlled join / quit events, replacing the per-platform listener test harnesses that exist today.

### Negative / Trade-offs

- `NetworkWaitlistGuard`'s public predicate signature changes from `Predicate<CommandSender>` to `Predicate<RTPCommandSender>`. Internal-only API, one caller (`RTPCmdBukkit`), one-line wrap adapter at the call site. Acceptable.
- `RTPServerAccessor` gains a new default method. Existing implementations compile without changes; platforms that want real lifecycle hooks override (Bukkit, Fabric). Mock / Folia / tests inherit the no-op.
- `LIVE` singleton is now reachable from a wider set of call sites (any backend platform's command tree). This is intentional but means the existing volatile-publish contract is the only memory-visibility guarantee for cross-thread reads; no change from today's Bukkit behaviour.
- Test fixtures that previously plugged into a Bukkit `Listener` registry now plug into `PlayerLifecycleHook`. New tests are needed for the platform shims (`BukkitPlayerLifecycleHookTest`, `FabricPlayerLifecycleHookTest`); these are straightforward harnesses and small.

### Limitations

1. No proxy adapter for Fabric. This ADR concerns the backend side only. Proxies remain Velocity (working) and BungeeCord (Phase 3). A Fabric process can be a backend; it cannot be a proxy.
2. No Phase 3 hooks. Postgres `LISTEN/NOTIFY`, BungeeCord transport, and the `JoinTriggerSource` rework are explicitly out of scope. The lifted code implements Phase 2 only.
3. Single transport per process. Same constraint as today's Bukkit class. Multi-transport composition (`network.yml::transport.type` listed as a sequence) is a `MULTI_SERVER_PLAN.md` Phase 3 follow-up.
4. No live `rtp test full` for the network suite on Fabric in this ADR. A Fabric equivalent harness is a follow-up; the lift unblocks it but does not deliver it.

## Implementation plan (post-acceptance)

The implementation is a separate change after this ADR is accepted. Updated 2026-05-23 after the step-4 addendum widened the SPI surface. Live progress tracking lives in [`docs/dev/scratch/CHECKLIST-adr049-network-mode-lift.md`](../dev/scratch/CHECKLIST-adr049-network-mode-lift.md).

**Done (prior sessions):**

1. ✅ `PlayerLifecycleHook` interface in `rtp-api`; `NoopPlayerLifecycleHook`; `DispatchingPlayerLifecycleHook` skeleton; `RTPServerAccessor.getPlayerLifecycleHook()` default.
2. ✅ `BukkitPlayerLifecycleHook` in `rtp-bukkit-common`; `AbstractServerAccessor` owns and registers the singleton.
3. ✅ `FabricPlayerLifecycleHook` in `rtp-fabric-common`; `FabricServerAccessor.getPlayerLifecycleHook()` override; `FabricEventBridge` fans JOIN / DISCONNECT into the hook.

**Open (in order):**

4. **New SPI: `BackendStateSampler`.** Interface in `rtp-core` (or `rtp-api`) covering player count, TPS, MSPT, world list, lobby-mode flag. `RTPServerAccessor.createBackendStateSampler(boolean lobbyMode)` factory method (no default — throw `IllegalStateException` until a platform provides one, per S-006). `BukkitBackendStateSampler` becomes an implementation; new `FabricBackendStateSampler` reads from `MinecraftServer` via `FabricVersionAdapter` (TPS/MSPT may need a Fabric-side sampler that does not exist yet — scope this before estimating).
5. **New SPI: `NetworkCommandHook`.** Interface in `rtp-core` covering the surface today's `BukkitNetworkCommandHook` exposes (mount `/rtp server:<x>` and network sub-verbs, expose `commandHook()` getter on the bootstrap). `RTPServerAccessor.getNetworkCommandHook()` default returns a no-op. `BukkitNetworkCommandHook` becomes an implementation. New `FabricNetworkCommandHook` mounts into the Fabric Brigadier tree via `BrigadierCommandAdapter` (commands-api-ADR-001). **Estimate separately** — this is the heaviest unknown in the lift.
6. **Lift the nine zero-Bukkit-import classes** (`NetworkModeBootstrap`, `NetworkRouter`, `NetworkStatusCache`, `NetworkEnrolmentBuffer`, `PeerRegionRegistry`, `LobbyDispatchRetryQueue`, `NetworkRegionCollisionWarner`, `RoutingDecision`) from `rtp-plugin/.../bukkit/network/` to `rtp-core/.../common/network/`. Replace the two `new BukkitBackendStateSampler(...)` / `new BukkitNetworkCommandHook(...)` call sites with `RTP.serverAccessor.createBackendStateSampler(...)` / `getNetworkCommandHook()`. Update import sites across the codebase.
7. **Move and rewrite the 4 listener classes** (`JoinTriggerSource`, `NetworkWaitlistQuitListener`, `NetworkWaitlistNotifier`, `NetworkWaitlistGuard`) per Decision §3. Update `RTPCmdBukkit` waitlist-guard call site (`Predicate<CommandSender>` -> `Predicate<RTPCommandSender>`).
8. **Move the 4 tests** (`LobbyModeEarlyReadTest`, `ReqRtpNet015NetworkWaitlistTest`, `NetworkRouterTest`, `NetworkStatusCacheTest`) and adjust to `MockRTPServerAccessor` + synthetic `PlayerLifecycleHook` fixture.
9. **Wire Fabric mod entrypoint** (`RTPFabricMod.onInitialize` or equivalent) to construct `NetworkModeBootstrap`, call `boot(networkYml)` after version-adapter install and before command-tree wiring, and call `shutdown()` on `ServerLifecycleEvents.SERVER_STOPPING`.
10. **New tests**: `BukkitPlayerLifecycleHookTest`, `FabricPlayerLifecycleHookTest`, Fabric bootstrap smoke test.
11. **CHANGELOG** entry under the current Unreleased cycle.
12. **Devstack acceptance** (user-driven per session direction): cross-server round-trip with Fabric backend, reservation-token redeem, Fabric-lobby dispatch.
13. **Full `.\gradlew build`**.

## References

- [ADR-036 - Network mode (multi-server, multi-proxy)](ADR-036-network-mode-multi-server-multi-proxy.md)
- [rtp-fabric-ADR-013 - Network-mode backend parity on Fabric (superseded by this ADR)](../../rtp-fabric/docs/adr/rtp-fabric-ADR-013-network-mode-bootstrap-parity.md)
- [rtp-proxy-ADR-014 - Backend-owned RTP with network queue](../../rtp-proxy/docs/adr/rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md)
- [rtp-proxy-ADR-015 - Shared network waitlist and dynamic batched dispatch](../../rtp-proxy/docs/adr/rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md)
- [rtp-fabric-ADR-002 - Platform in scope](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md)
- [rtp-fabric-ADR-009 - Obf / unobf common split](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)
- [`MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md)
- [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md) Step J

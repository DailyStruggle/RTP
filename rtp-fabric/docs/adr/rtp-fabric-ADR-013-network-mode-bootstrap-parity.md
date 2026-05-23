# rtp-fabric-ADR-013 - Network-mode backend parity on Fabric (reimplementation, not refactor)

- **Status:** Superseded by [ADR-049](../../../docs/adr/ADR-049-network-mode-platform-neutral-lift.md) (2026-05-23). The "8 `org.bukkit.*` coupling points in `NetworkModeBootstrap`" estimate that drove this ADR's rejection of the refactor path was re-audited on 2026-05-23 and is incorrect: the bootstrap has zero Bukkit imports, and the platform surface reduces to a single missing SPI primitive (player join / quit) plus a `Predicate<CommandSender>` generic. The lift-to-`rtp-core` path is the cleaner choice. This ADR is preserved for context; do not implement the parallel `FabricNetworkModeBootstrap` / `FabricJoinTriggerSource` it proposes.
- **Scope:** `rtp-fabric` (`rtp-fabric-common`, optional touch on per-version carriers for join-event sources) and `rtp-plugin/.../fabric/`. **Does not** modify `rtp-proxy-common`, `rtp-proxy-velocity`, or `rtp-proxy-bungee`; those layers are already platform-agnostic.
- **Supersedes:** none. Implements the Fabric clause of [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md) and the backend-side contract of the `rtp-proxy-*` subproject ADRs.
- **Related:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md) (umbrella network-mode ADR), [rtp-proxy-ADR-014](../../../rtp-proxy/docs/adr/rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md) (backend-owned RTP with network queue; defines the `NetworkModeBootstrap` contract this ADR mirrors), [rtp-proxy-ADR-015](../../../rtp-proxy/docs/adr/rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md) (shared waitlist + dynamic dispatch; consumed by the join-trigger source this ADR specifies), [rtp-fabric-ADR-011](rtp-fabric-ADR-011-effective-permissions-enumeration.md) (reservation-token authorization leans on `hasPermission`), [`MULTI_SERVER_PLAN.md`](../../../docs/dev/MULTI_SERVER_PLAN.md) (phase status; backend integration is Phase 2 work).

## Context

Network-mode multi-server support landed end-to-end on Paper in this cycle (user-confirmed working 2026-05-22). The Phase 1 SPI in `rtp-proxy-common` is platform-agnostic and already reachable from Fabric, but the Phase 2 backend integration is wired in Bukkit-only classes:

- `rtp-plugin/.../bukkit/network/NetworkModeBootstrap.java` (~800 lines). Selects a transport (`InMemoryNetworkStateBinding`, `RedisNetworkStateBinding`, `SqlNetworkStateBinding`), installs the binding, constructs a `BackendStatePublisher` driven by `BukkitBackendStateSampler`, constructs a `ReservationTokenReaper`, constructs a `JoinTriggerSource`, and exposes a `LIVE` singleton so the `/rtp` command can probe live state. Deeply coupled to `org.bukkit.plugin.Plugin`, `org.bukkit.scheduler.BukkitScheduler`, and `org.bukkit.event.Listener` registration (lines 610-615: `Bukkit.getPluginManager().registerEvents(joinTriggerSource, plugin)`). Lifecycle reads `network.yml` twice via `readLobbyModeEarly(File)` and a full load.
- `rtp-plugin/.../bukkit/network/JoinTriggerSource.java`. `implements org.bukkit.event.Listener`. Drains reservation tokens against the backend `keptLocations` / `unkeptLocations` pool on `PlayerJoinEvent`.

A Velocity-routed player arriving on a Fabric backend with a reservation token has no listener to redeem it: the lookup happens but the redeem-side handler is Bukkit-only. The cross-server flow therefore silently degrades on Fabric (the player joins normally; no RTP is performed; no error is surfaced to the proxy). This violates the Phase 2 acceptance criterion in `MULTI_SERVER_PLAN.md` that says "a backend that advertises `RtpTriggerSource` MUST redeem reservation tokens on join".

Two paths are available:

1. **Refactor `NetworkModeBootstrap` into a platform-neutral base class** with a Fabric subclass overriding the `Plugin` / `Listener` hooks. Estimated touch: every `org.bukkit.*` call in `NetworkModeBootstrap` and `JoinTriggerSource` becomes a deferred SPI method, the class moves from `rtp-plugin/bukkit/network/` to a new package shared between platforms, and the ArchUnit guard ([Step H](../../../docs/dev/MULTI_PLATFORM_PLAN.md)) needs an exception or the class lives outside both `bukkit/` and `fabric/`.
2. **Reimplement as `FabricNetworkModeBootstrap`** in `rtp-plugin/.../fabric/network/`, mirroring the Bukkit class line-by-line but binding to Fabric event hooks. The platform-agnostic logic (transport selection, publisher install, reaper install, `network.yml` schema) is already minimal in the Bukkit class because the heavy lifting is in `rtp-proxy-common`. The duplication is bounded.

The Bukkit class has eight independent coupling points to `org.bukkit.*` (`Plugin`, `BukkitScheduler`, `PluginManager`, `Listener`, `PlayerJoinEvent`, `EventHandler`, `EventPriority`, `Bukkit.getOnlinePlayers()`) and a `LIVE` static singleton consumed by command-side code. A base-class refactor would create an awkward "platform-neutral base that exposes seven abstract hooks" with a near-equal amount of Fabric-side code anyway. The reimplementation path is cleaner and isolates Fabric platform risk from the working Paper code.

## Decision

Reimplement, do not refactor. Add a parallel `FabricNetworkModeBootstrap` and `FabricJoinTriggerSource` under `rtp-plugin/.../fabric/network/`, structurally mirroring the Bukkit class but binding to Fabric Loader event APIs.

### 1. `FabricNetworkModeBootstrap`

A new class under `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/fabric/network/FabricNetworkModeBootstrap.java`:

- Static `LIVE` singleton, exposed to the Fabric command tree (`RTPCmdFabricRoot`) so `/rtp` probes work whether network mode is on or off, exactly as on Paper.
- Static `readLobbyModeEarly(File networkYml)` and `ensureNetworkYml(File dataDir)` are **reused** from the Bukkit class. They are pure I/O on a YAML file and do not import `org.bukkit.*`. Refactor them up into a new `NetworkModeBootstrapSupport` utility in `rtp-core` (or a small shared package in `rtp-api` if cleaner; ArchUnit guard does not block this because the file is under neither `bukkit/` nor `fabric/`). The Bukkit class then delegates to the shared utility, which is a *minor* refactor on the Bukkit side and the only place this ADR touches platform-neutral code.
- `boot()` selects transport, opens binding, constructs publisher, installs reaper, constructs join trigger source - identical sequence to Bukkit.
- `registerJoinTriggerSource(ModContainer)` (mirrors `registerJoinTriggerSource(Plugin)`): hooks `ServerPlayConnectionEvents.JOIN` via Fabric API. On the obf carrier the same event is reached through the intermediary surface per [rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md); on the unobf carrier through the deobf API directly. The dispatch goes through `FabricVersionAdapter` so the bootstrap class itself stays in `rtp-plugin` and does not need an obf/unobf split.
- `shutdownDrain()` is wired to `ServerLifecycleEvents.SERVER_STOPPING`, parallel to the Bukkit `onDisable` drain path. Same drain sequence: stop publisher, close reservation reaper, flush in-flight reservation tokens, close binding.

### 2. `FabricJoinTriggerSource`

A new class under `rtp-plugin/.../fabric/network/FabricJoinTriggerSource.java`:

- Does **not** implement `org.bukkit.event.Listener`. Instead, exposes an `onPlayerJoin(UUID playerId)` method that `FabricNetworkModeBootstrap` registers as a `ServerPlayConnectionEvents.JOIN` callback at boot.
- Reservation-token redeem logic is reused from `rtp-proxy-common` (the `NetworkStateBinding.consumeReservationToken(UUID, String serverId)` call). The Fabric-side wrapper only translates the platform-specific join callback to the platform-neutral SPI call.
- Authorization mirrors the Bukkit path: the resolved `RTPPlayer` is fetched via `RTP.serverAccessor.getPlayer(uuid)` and the same `hasPermission("rtp.onevent.join")` check is applied. The check now resolves correctly on Fabric because [rtp-fabric-ADR-011](rtp-fabric-ADR-011-effective-permissions-enumeration.md) shipped the LP-primary + closed-namespace probe resolver, which covers the `rtp.onevent.*` namespace.

### 3. `RtpTriggerSource` install marker

`RtpTriggerSource` is the network-state marker that tells a proxy "this backend speaks RTP network mode and will redeem reservation tokens". On Bukkit it is registered through `NetworkModeBootstrap.boot()` against the active `NetworkStateBinding`. The Fabric class registers the identical marker through the identical SPI call; no Fabric-specific marker type is introduced.

### 4. `network.yml` extraction on Fabric

`FabricNetworkModeBootstrap.ensureNetworkYml(dataDir)` reuses `FabricJarUtils.extractDocs` from Step E3 to seed the operator's `config/rtp/network.yml` from the bundled resource on first boot, identical to the Bukkit behavior. **Gating:** this is the only sub-item gated on a Step that is not yet complete; if Step E3 has not landed when this ADR is implemented, the implementation should add a minimal local copy of the extraction utility to `rtp-fabric-common` and merge it into the shared helper when Step E3 closes. The ADR does not pre-decide that micro-question.

### 5. Lifecycle and singleton parity

The `LIVE` singleton on `FabricNetworkModeBootstrap` and `NetworkModeBootstrap` is a **per-process** singleton (one per JVM). On a unified Bukkit+Fabric process this is impossible, but the project never ships such a target: the unified `rtp-plugin` shadow JAR loads exactly one of the two adapter packages at startup (per the Step H ArchUnit guard from [Step H](../../../docs/dev/MULTI_PLATFORM_PLAN.md), the `bukkit/` and `fabric/` package trees are disjoint and only one is loaded). Therefore the singletons are effectively a single global instance per process and the cross-singleton invariant "at most one is non-null" holds by class-loading. The command tree fetches whichever singleton is non-null.

## Consequences

### Positive

- Closes Step J in [MULTI_PLATFORM_PLAN.md](../../../docs/dev/MULTI_PLATFORM_PLAN.md). Cross-server RTP arrival on a Fabric backend now redeems reservation tokens, matching the documented Phase 2 contract.
- The reimplementation path keeps the working Paper class untouched apart from a minor pull-up of two pure-I/O methods to a shared utility; zero functional risk to Paper.
- The Fabric class is testable independently because it does not extend the Bukkit class; mockable transports + a synthetic event-firing harness suffice.
- Reservation-token authorization reuses the [rtp-fabric-ADR-011](rtp-fabric-ADR-011-effective-permissions-enumeration.md) resolver, so cross-server permissions are not a second authorization path.

### Negative

- Two `*NetworkModeBootstrap` classes must be kept in sync against future `MULTI_SERVER_PLAN.md` phases (Phase 3 `JoinTriggerSource` rework, Postgres `LISTEN/NOTIFY`, BungeeCord adapter). The duplication is bounded to ~300 lines of glue per side; the transport plumbing remains shared.
- A "shared utility" tier emerges in `rtp-core` (or a small new `rtp-network-common` package) for the pure-I/O `network.yml` helpers. This is a small architectural concession to avoid duplicating YAML parse code; it does not cross any existing module boundary.
- `RTPCmdFabricRoot` gains a dependency on `FabricNetworkModeBootstrap.LIVE` analogous to the Bukkit dependency. Acceptable; identical shape on both sides.

### Limitations

1. **No proxy adapter for Fabric.** This ADR concerns the backend side only. Proxies remain Velocity (working) and BungeeCord (Phase 3). A Fabric process can be a backend; it cannot be a proxy.
2. **No Phase 3 hooks.** `JoinTriggerSource` rework, Postgres `LISTEN/NOTIFY`, and the BungeeCord transport are explicitly out of scope. The Fabric class implements Phase 2 only.
3. **Single transport per process.** Same as Bukkit. Multi-transport composition (`network.yml::transport.type` listed as a sequence) is a `MULTI_SERVER_PLAN.md` Phase 3 follow-up and lands on both sides simultaneously when it ships.
4. **No live `/rtp test full` on Fabric for the network suite.** The Bukkit `/rtp test full` harness runs network-side scenarios through `BukkitBackendStateSampler` and `org.bukkit.event.*`. A Fabric equivalent is a follow-up; ADR-013 unblocks it but does not deliver it.

## Implementation plan (post-acceptance)

The actual implementation is a separate change after this ADR is accepted. Sketch:

1. Move `readLobbyModeEarly(File)` and `ensureNetworkYml(File)` from `rtp-plugin/.../bukkit/network/NetworkModeBootstrap.java` to a new `rtp-core/.../common/network/NetworkBootstrapSupport.java`. Update Bukkit class to delegate. Add a regression test that the pulled-up methods preserve the existing seven-case behavior covered by `LobbyModeEarlyReadTest`.
2. New `rtp-plugin/.../fabric/network/FabricNetworkModeBootstrap.java`. Mirror the Bukkit `boot()` / `shutdownDrain()` / `LIVE` shape; bind to Fabric API events.
3. New `rtp-plugin/.../fabric/network/FabricJoinTriggerSource.java`. Plain object with `onPlayerJoin(UUID)`; registered as a `ServerPlayConnectionEvents.JOIN` callback by the bootstrap.
4. Wire `FabricNetworkModeBootstrap` from `RTPFabricMod.onInitialize()` (boot order: after the version adapter installs, before the command tree wires).
5. Wire `FabricNetworkModeBootstrap.LIVE` lookups into `RTPCmdFabricRoot` lines analogous to `RTPCmdBukkit:88-89, 103-104`.
6. New `FabricNetworkModeBootstrapTest` + `FabricJoinTriggerSourceTest` analogous to `LobbyModeEarlyReadTest` and `ReqRtpNet015NetworkWaitlistTest`: synthetic transport, simulated player join, assert reservation-token consumption.
7. `ServerLifecycleEvents.SERVER_STOPPING` callback hooks the shutdown drain.
8. `TRACEABILITY.md` rows for any new `REQ-RTP-NET-*` tests authored on the Fabric path.
9. CHANGELOG entry under `[3.0.0-beta.4] - Unreleased` (or the cycle current at implementation time).
10. Devstack acceptance: spin up a 2-proxy + 2-backend devstack with one Fabric backend, run the existing cross-server round-trip suite, confirm reservation-token redeem succeeds on the Fabric backend.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Refactor `NetworkModeBootstrap` into a platform-neutral base class with a Fabric subclass | Eight independent `org.bukkit.*` coupling points produce a near-equal amount of subclass code; introduces an awkward "base class with seven abstract hooks" while saving little code and adding risk to the working Paper path. Reimplementation is cleaner. |
| Place `FabricNetworkModeBootstrap` in `rtp-fabric-common` | The class consumes `RTP.serverAccessor` and the `rtp-core` SPI; it does not need carrier-specific obf/unobf binding because event registration is dispatched through `FabricVersionAdapter`. Keeping it in `rtp-plugin/.../fabric/network/` matches the Bukkit shape and keeps Step H's ArchUnit guard (disjoint `bukkit/` + `fabric/` packages in `rtp-plugin`) cleanly satisfied. |
| Share `JoinTriggerSource` between platforms via an interface | `JoinTriggerSource` is a `Listener` on Bukkit; it cannot share an interface with a plain Fabric callback without breaking the Bukkit shape or forcing a non-trivial Listener-shim on Fabric. A shared SPI exists at a lower level (`RtpTriggerSource` marker); the per-platform `JoinTriggerSource` wrappers stay distinct. |
| Defer Fabric network mode until Phase 3 | Step J is the missing acceptance criterion that downgrades Fabric from "first-class platform" to "single-server only" backend. Deferring violates [rtp-fabric-ADR-002](rtp-fabric-ADR-002-platform-in-scope.md). |
| Skip `FabricJoinTriggerSource` and rely on the cold-cache `unkeptLocations` fallback for cross-server arrivals | Loses the per-player coordinate hand-off that reservation tokens enable; the proxy-selected coordinate becomes a hint at best. Defeats the point of the Phase 2 reservation-token mechanism. |

## References

- [ADR-036 - Network mode (multi-server, multi-proxy)](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
- [rtp-proxy-ADR-014 - Backend-owned RTP with network queue](../../../rtp-proxy/docs/adr/rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md)
- [rtp-proxy-ADR-015 - Shared network waitlist and dynamic batched dispatch](../../../rtp-proxy/docs/adr/rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md)
- [rtp-fabric-ADR-011 - Effective-permission enumeration on Fabric](rtp-fabric-ADR-011-effective-permissions-enumeration.md)
- [rtp-fabric-ADR-002 - Platform in scope](rtp-fabric-ADR-002-platform-in-scope.md)
- [MULTI_SERVER_PLAN.md](../../../docs/dev/MULTI_SERVER_PLAN.md)
- [MULTI_PLATFORM_PLAN.md Step J](../../../docs/dev/MULTI_PLATFORM_PLAN.md)

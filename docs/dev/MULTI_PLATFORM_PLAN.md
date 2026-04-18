# Multi-Platform Support Roadmap

This document outlines the comprehensive plan for transitioning RTP to a multi-platform project, starting with Fabric and potentially expanding to Forge in the future.

## Phase 1: Infrastructure & Core Consolidation (COMPLETED/IN-PROGRESS)

- [x] **Consolidate APIs**: Pull `CommandsAPI` and `EffectsAPI` back into the main repository as sub-modules.
- [x] **Refactor Dependencies**: Update `rtp-core` and `rtp-plugin` to use local project dependencies for APIs.
- [x] **Initial Fabric Skeleton**: Create `rtp-fabric` module with basic server, player, and world wrappers.
- [ ] **Fix Fabric Build System**: Resolve Minecraft dependency resolution issues in Gradle by correctly integrating Fabric Loom.

## Phase 2: Fabric Feature Parity

The goal of this phase is to ensure the Fabric version has all the essential features available in the Bukkit/Paper/Folia versions.

### Abstraction Gap Summary

The table below tracks the current implementation status of each cross-platform abstraction. "Critical" gaps block the teleport pipeline from functioning at all; "High" gaps cause data loss or incorrect behaviour at runtime.

| Abstraction | Bukkit Status | Fabric Status | Gap Severity |
|---|---|---|---|
| `RTPServerAccessor` | Full (`AbstractServerAccessor`) | Partial — `getLocationGenerator()` returns `null` | **Critical** |
| `RTPWorld` (async chunk load) | Full (`BukkitRTPWorld` / Paper override) | `getChunkFutureSyncOnMainThread` — violates REQ-RTP-S-005 | **Critical** |
| `RTPPlayer` | Full (`BukkitRTPPlayer`) | `hasPermission` hardcoded to op-level; `getEffectivePermissions()` empty | High |
| `RTPScheduler` | Full (`BukkitSchedulerImpl`) | Skeleton only | High |
| Database / Persistence | Full (`DatabaseProcessing`) | Missing entirely | High |
| Event mapping | Full (Bukkit listeners) | Only `FabricPlayerJoin` | Medium |
| Command system | Full Bukkit tree | Partial Brigadier bridge | Medium |
| Permissions | Bukkit permissions API | Hardcoded op-check | Medium |

### Recommended Implementation Order

Work items are ordered to deliver a runnable Fabric build as early as possible while respecting safety requirements.

1. **Fix REQ-RTP-S-005 violation** — Replace `getChunkFutureSyncOnMainThread` in `FabricWorld.getChunkAt()` with a truly async dispatch (Minecraft worker pool or Fabric chunk ticket API). This is safety-critical and must be done first.
2. **Wire `getLocationGenerator()`** in `FabricServerAccessor` — Return `RTP.getInstance().locationGenerator` (same pattern as `AbstractServerAccessor`). Unblocks the entire teleport pipeline.
3. **Complete `FabricScheduler`** — Implement `scheduleAsync`, `scheduleSync` (via `ServerTickEvents.END` + `server.execute()`), and `cancelTask` (backed by a `ConcurrentHashMap<Integer, Future<?>>`).
4. **Add `FabricDatabaseHandler`** — Locate config dir via `FabricLoader.getInstance().getConfigDir().resolve("rtp")` and instantiate `rtp-core`'s platform-agnostic `DatabaseHandler`. No new `rtp-api` abstraction needed.
5. **Complete event mapping** — Add `ServerPlayConnectionEvents.DISCONNECT` (queue cleanup), `ServerWorldEvents.LOAD/UNLOAD` (region registration/cleanup). Register all in `RTPFabric.onInitialize()`.
6. **Permissions via `fabric-permissions-api`** — Add `me.lucko:fabric-permissions-api` as `compileOnly`. Implement `Permissions.check(source, node, opFallback)` in `FabricPlayer.hasPermission()`. LuckPerms-Fabric hooks in automatically.
7. **Brigadier bridge in `commands-api`** — See Phase 3 and ADR-014.

### 1. Database & Persistence
- [ ] **Fabric Database Handler**: Implement `FabricDatabaseHandler` using `FabricLoader.getInstance().getConfigDir()` to locate the data directory, then delegate to `rtp-core`'s platform-agnostic `DatabaseHandler`.
- [ ] **Config Migration**: Ensure configuration files are correctly located in the Fabric `config/rtp` directory.

### 2. Permissions & Integration
- [ ] **LuckPerms-Fabric Integration**: Add `me.lucko:fabric-permissions-api` as a `compileOnly` soft-dependency. Implement `Permissions.check()` in `FabricPlayer.hasPermission()` with an op-level fallback. LuckPerms-Fabric satisfies this API automatically when present.
- [ ] **PlaceholderAPI Alternative**: Investigate and implement support for a Fabric-native placeholder system (e.g., PlaceholderAPI for Fabric).

### 3. Event Mapping
- [ ] **Complete Lifecycle Events**: Map all critical events to Fabric's event hooks:
    - `PlayerQuitEvent` → `ServerPlayConnectionEvents.DISCONNECT`
    - `WorldLoadEvent` → `ServerWorldEvents.LOAD`
    - `WorldUnloadEvent` → `ServerWorldEvents.UNLOAD`
- [ ] **Cancelable Events**: Map `PlayerTeleportEvent` via `EntityTeleportCallback` or a mixin to allow RTP to intercept and cancel teleports when necessary.

### 4. Asynchronous Chunk Loading
- [ ] **Fix REQ-RTP-S-005 violation**: Replace `getChunkFutureSyncOnMainThread` in `FabricWorld.getChunkAt()` with a dispatch to `Util.getMainWorkerExecutor()` (Minecraft's off-main worker pool) combined with the Fabric chunk ticket API. Mirror the pattern used in `rtp-paper-common`'s `getChunkAtAsync` override.

## Phase 3: Command System Refinement

- [ ] **Brigadier Bridge** (see [ADR-014](../adr/ADR-014-brigadier-bridge-via-commands-api.md)): Implement a `BrigadierCommandAdapter` inside `commands-api` that converts the `commands-api` tree into Brigadier `LiteralArgumentBuilder` nodes. `RTPCmdFabric` then registers the adapted tree via `CommandRegistrationCallback.EVENT` — no platform-specific command logic duplication.
- [ ] **Tab Completion**: Implement advanced tab completion that mirrors the Bukkit experience but leverages Brigadier's client-side capabilities.
- [ ] **Command Feedback**: Ensure all RTP messages and command feedback are correctly sent to Fabric players/console.

## Phase 4: Stabilization & Testing

- [ ] **Fabric Test Suite**: Adapt existing unit and integration tests to run in a Fabric environment.
- [ ] **Memory Leak Audit**: Perform a thorough audit for memory leaks, specifically targeting the new Fabric-specific wrappers.
- [ ] **Concurrency Verification**: Ensure that the region-based task scheduling is safe on Fabric's threading model.

## Phase 5: Documentation & Release

- [ ] **Admin Documentation**: Update the `docs/admin` files to include Fabric-specific installation and configuration instructions.
- [ ] **Developer Documentation**: Update `docs/dev` to reflect the multi-platform architecture and how to contribute to platform-specific modules.
- [ ] **Beta Release**: Release the first public beta of RTP for Fabric.

## What Does NOT Need to Change in `rtp-api` or `rtp-core`

The existing abstractions are sufficient for full Fabric support. Specifically:
- `RTPServerAccessor`, `RTPWorld`, `RTPPlayer`, `RTPScheduler` interfaces require no new methods.
- `DatabaseHandler` in `rtp-core` is already platform-agnostic.
- `LocationGenerator`, `TeleportPipelineTask`, and `MemoryTracker` are untouched — Fabric wires into them via `RTP.getInstance()`.

The only potential future `rtp-api` addition is a `RTPPermissionProvider` interface to formalize the soft-depend pattern, but this is deferred until Phase 2 permissions work is complete and the pattern is proven.

## Future: Forge Support

- [ ] **Evaluation**: Once Fabric is stable, evaluate the effort to add a `rtp-forge` module using the established abstraction patterns.
- [ ] **Architectury?**: Re-evaluate if moving to a common abstraction layer like Architectury is beneficial for long-term maintenance of both Fabric and Forge versions.

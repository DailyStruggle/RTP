# RTP Fabric Adapter Requirements

This document outlines the strict requirements for the `rtp-fabric` module. Fabric is a non-Bukkit mod-loader platform brought into scope by [rtp-fabric-ADR-002](docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) (renumbered from ADR-022 on 2026-05-05); the adapter mediates between Mojang-mapped server APIs and the platform-agnostic abstractions in `rtp-api` / `rtp-core`.

For design and implementation details that satisfy these requirements, see [`docs/dev/DESIGN.md`](../../docs/dev/DESIGN.md) and the per-step plan in [`docs/dev/MULTI_PLATFORM_PLAN.md`](../../docs/dev/MULTI_PLATFORM_PLAN.md).

## 1. Functional Requirements

### 1.1 Platform Abstraction Conformance
- **REQ-FABRIC-F-001 — Server Accessor Implementation:** The adapter shall provide a complete implementation of `RTPServerAccessor` backed by `MinecraftServer`, `ServerLevel`, `ServerPlayer`, and `FabricLoader` APIs.
- **REQ-FABRIC-F-002 — World Adapter:** The adapter shall provide a `RTPWorld<ServerLevel>` implementation that exposes dimension identity, biome lookup, height bounds, and chunk operations through the platform-agnostic interface.
- **REQ-FABRIC-F-003 — Player Adapter:** The adapter shall provide an `RTPPlayer` implementation backed by `ServerPlayer` that supports identity, online status, location read, asynchronous teleport, and message dispatch.
- **REQ-FABRIC-F-004 — Scheduler Implementation:** The adapter shall provide an `RTPScheduler` implementation honoring the contract for synchronous, asynchronous, delayed, and repeating task dispatch.

### 1.2 Single-JAR Multi-Loader Distribution
- **REQ-FABRIC-F-005 — Co-located Metadata:** The single distribution JAR shall contain both `plugin.yml` (Bukkit-family) and `fabric.mod.json` (Fabric) at its root, each referencing a runtime-specific entry point in a disjoint package (per ADR-022 §2).
- **REQ-FABRIC-F-006 — Disjoint Entry Points:** The Bukkit entry point shall not transitively reach Fabric platform classes, and the Fabric entry point shall not transitively reach Bukkit platform classes. Shared state shall pass exclusively through `rtp-core` / `rtp-api` / `commands-api` / `effects-api`.

### 1.3 Lifecycle Integration
- **REQ-FABRIC-F-007 — Server Lifecycle Wiring:** The adapter shall bind to `ServerLifecycleEvents.SERVER_STARTED` for accessor activation and `ServerLifecycleEvents.SERVER_STOPPING` for shutdown flush, mirroring the Bukkit `onEnable`/`onDisable` pair.
- **REQ-FABRIC-F-008 — Tick-Driven Scheduling:** The adapter's scheduler queue shall advance on `ServerTickEvents.END_SERVER_TICK`.
- **REQ-FABRIC-F-009 — World Cache Maintenance:** The adapter shall register `RTPWorld` entries on `ServerWorldEvents.LOAD` and remove them on `ServerWorldEvents.UNLOAD`.
- **REQ-FABRIC-F-010 — Player Session Tracking:** The adapter shall create `RTPPlayer` wrappers on `ServerPlayConnectionEvents.JOIN` and release them on `ServerPlayConnectionEvents.DISCONNECT`.
- **REQ-FABRIC-F-011 — Login Reserve Cache Wiring (ADR-023):** When `loginCacheEnabled` is `true`, the adapter shall allocate `RegionQueueManager.loginLocations` on the default-world region at server start (sized to `loginCacheCap`, or to `MinecraftServer.getMaxPlayers()` when `loginCacheCap` is `0`), dispatch the startup burst via `LoginCacheTask`, top up the buffer by one entry on every `ServerPlayConnectionEvents.DISCONNECT`, and at `ServerPlayConnectionEvents.JOIN` consume one entry into `fastLocations` for any player holding `rtp.onevent.firstjoin` (when the player has not joined before) or `rtp.onevent.join` (subject to cooldown), where "joined before" shall be determined by the presence of the player's `<worldRoot>/playerdata/<uuid>.dat` file.

## 2. Strict Architectural Requirements

### 2.1 Architectural Invariants (ADR-022 §4)
- **REQ-FABRIC-ARCH-001 — No Bukkit Imports:** No source file under `platforms/rtp-fabric/**` shall import from `org.bukkit.*`, `io.papermc.*`, or `dev.folia.*`.
- **REQ-FABRIC-ARCH-002 — No Core Pollution:** Fabric-specific patterns, types, or imports shall not be introduced into `rtp-core` or `rtp-api`.
- **REQ-FABRIC-ARCH-003 — Loom Plugin Scope:** The `fabric-loom` Gradle plugin shall be applied only within `platforms/rtp-fabric/**`, `rtp-plugin` (the latter solely for single-JAR remap), and `effects-api` (per `effects-api-ADR-003`, to support the in-module `effectsapi/fabric` subpackage), and shall remap only classes under `io/github/dailystruggle/rtp/fabric/**` and `io/github/dailystruggle/effectsapi/fabric/**`.

### 2.2 Safety-Critical Compliance
- **REQ-FABRIC-ARCH-004 — Asynchronous Chunk Loading (S-005):** Chunk loads triggered from the teleport pipeline shall not synchronously block the calling thread; the adapter shall dispatch via `MinecraftServer#submit` (or a documented equivalent) so the calling thread receives a `CompletableFuture` that completes off-tick.
- **REQ-FABRIC-ARCH-005 — Failure Attribution (S-004):** Chunk-load and teleport futures shall complete exceptionally on torn-down state (null `MinecraftServer`, removed entity, unloaded world); failures shall not be silently discarded.
- **REQ-FABRIC-ARCH-006 — Memory Hygiene:** Player and world wrappers shall be released on disconnect / unload events; wrappers retaining native handles shall expose an unbind operation invoked by the event bridge.

### 2.3 API Entry-Point Contract (REQ-RTP-S-006)
- **REQ-FABRIC-ARCH-007 — Fail-Loud on Unimplemented Surface:** Methods of `RTPServerAccessor` not yet implemented shall throw `UnsupportedOperationException` with the owning Phase 2 step identifier, rather than returning `null` or silently no-op'ing.
- **REQ-FABRIC-ARCH-008 — Pre-Init Guard:** Public API entry points dependent on `RTP.getInstance()` shall throw `IllegalStateException` when invoked before `rtp-core` is initialized.

### 2.4 Configuration & Persistence
- **REQ-FABRIC-ARCH-009 — Config Directory Resolution:** The adapter shall locate the plugin configuration directory via `FabricLoader.getInstance().getConfigDir().resolve("rtp")` and shall create it if absent.
- **REQ-FABRIC-ARCH-010 — Database Delegation:** Persistence shall delegate to `rtp-core`'s `DatabaseHandler` without introducing a Fabric-specific persistence abstraction.

# RTP NeoForge Adapter Requirements

This document outlines the strict requirements for the `rtp-neoforge` module group. NeoForge is a non-Bukkit mod-loader platform brought into scope by [ADR-033](../../docs/adr/ADR-033-neoforge-platform-in-scope.md) and ratified structurally by [rtp-neoforge-ADR-001](docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md); the adapter mediates between Mojang-mapped server APIs and the platform-agnostic abstractions in `rtp-api` / `rtp-core`.

For design and implementation details that satisfy these requirements, see [`docs/dev/DESIGN.md`](../../docs/dev/DESIGN.md), the landscape/reuse analysis in [`docs/dev/NEOFORGE_NOTES.md`](../../docs/dev/NEOFORGE_NOTES.md), and the per-phase plan in [`docs/dev/MULTI_PLATFORM_PLAN.md`](../../docs/dev/MULTI_PLATFORM_PLAN.md).

## 1. Functional Requirements

### 1.1 Platform Abstraction Conformance
- **REQ-NEOFORGE-F-001 — Server Accessor Implementation:** The adapter shall provide a complete implementation of `RTPServerAccessor` backed by `MinecraftServer`, `ServerLevel`, `ServerPlayer`, and the NeoForge mod-loader APIs.
- **REQ-NEOFORGE-F-002 — World Adapter:** The adapter shall provide a `RTPWorld<ServerLevel>` implementation that exposes dimension identity, biome lookup, height bounds, and chunk operations through the platform-agnostic interface.
- **REQ-NEOFORGE-F-003 — Player Adapter:** The adapter shall provide an `RTPPlayer` implementation backed by `ServerPlayer` that supports identity, online status, location read, asynchronous teleport, and message dispatch.
- **REQ-NEOFORGE-F-004 — Scheduler Implementation:** The adapter shall provide an `RTPScheduler` implementation honoring the contract for synchronous, asynchronous, delayed, and repeating task dispatch, with main-thread work routed through `MinecraftServer#submit` / the server-tick executor.

### 1.2 Mod Distribution
- **REQ-NEOFORGE-F-005 — Mod Metadata:** The NeoForge distribution artifact shall contain a `META-INF/neoforge.mods.toml` descriptor referencing a `@Mod`-annotated entry point in a package disjoint from the Bukkit-family and Fabric entry points.
- **REQ-NEOFORGE-F-006 — Disjoint Entry Points:** The NeoForge entry point shall not transitively reach Bukkit or Fabric platform classes, and those entry points shall not transitively reach NeoForge platform classes. Shared state shall pass exclusively through `rtp-core` / `rtp-api` / `commands-api` / `effects-api`.

### 1.3 Lifecycle Integration
- **REQ-NEOFORGE-F-007 — Server Lifecycle Wiring:** The adapter shall bind to the NeoForge server-started lifecycle event for accessor activation and the server-stopping event for shutdown flush, mirroring the Bukkit `onEnable`/`onDisable` pair.
- **REQ-NEOFORGE-F-008 — Tick-Driven Scheduling:** The adapter's scheduler queue shall advance on the server-tick (end) event.
- **REQ-NEOFORGE-F-009 — World Cache Maintenance:** The adapter shall register `RTPWorld` entries on level-load and remove them on level-unload events.
- **REQ-NEOFORGE-F-010 — Player Session Tracking:** The adapter shall create `RTPPlayer` wrappers on player-login and release them on player-logout events.
- **REQ-NEOFORGE-F-011 — Command Registration:** The adapter shall register the unified `commands-api` command tree via the `BrigadierCommandAdapter` ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)) on the NeoForge `RegisterCommandsEvent`, without duplicating the command tree per platform.

## 2. Strict Architectural Requirements

### 2.1 Architectural Invariants (rtp-neoforge-ADR-001 §7)
- **REQ-NEOFORGE-ARCH-001 — No Bukkit/Fabric Imports:** No source file under `platforms/rtp-neoforge/**` shall import from `org.bukkit.*`, `io.papermc.*`, `dev.folia.*`, or `net.fabricmc.*`.
- **REQ-NEOFORGE-ARCH-002 — No Core Pollution:** NeoForge-specific patterns, types, or imports shall not be introduced into `rtp-core` or `rtp-api`.
- **REQ-NEOFORGE-ARCH-003 — Mod Plugin Scope:** The NeoForge Gradle plugin (ModDevGradle) shall be applied only within `platforms/rtp-neoforge/**` (and `rtp-plugin` solely if a combined multi-loader artifact is pursued).
- **REQ-NEOFORGE-ARCH-004 — Version Carrier Isolation:** `net.minecraft.*` and NeoForge runtime-mapping symbols shall reside only in per-MC-version carrier modules (`rtp-neoforge-v<ver>`); `rtp-neoforge-common` shall depend solely on `rtp-api` / `rtp-core` / stable NeoForge API surfaces. Carrier modules shall not cross-reference one another.

### 2.2 Safety-Critical Compliance
- **REQ-NEOFORGE-ARCH-005 — Asynchronous Chunk Loading (S-005):** Chunk loads triggered from the teleport pipeline shall not synchronously block the calling thread; the adapter shall dispatch via `MinecraftServer#submit` (or a documented equivalent) so the calling thread receives a `CompletableFuture` that completes off-tick.
- **REQ-NEOFORGE-ARCH-006 — Failure Attribution (S-004):** Chunk-load and teleport futures shall complete exceptionally on torn-down state (null `MinecraftServer`, removed entity, unloaded world); failures shall not be silently discarded.
- **REQ-NEOFORGE-ARCH-007 — Non-Persistent Chunk Tickets (S-002):** Chunk tickets acquired for teleport verification shall be non-persistent and released on every exit path, porting the Fabric ticket pattern ([rtp-fabric-ADR-003](../rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md)); no permanently force-loaded chunks.
- **REQ-NEOFORGE-ARCH-008 — Memory Hygiene:** Player and world wrappers shall be released on logout / unload events; wrappers retaining native handles shall expose an unbind operation invoked by the event bridge.

### 2.3 API Entry-Point Contract (REQ-RTP-S-006)
- **REQ-NEOFORGE-ARCH-009 — Fail-Loud on Unimplemented Surface:** Methods of `RTPServerAccessor` not yet implemented shall throw `UnsupportedOperationException` with the owning phase identifier, rather than returning `null` or silently no-op'ing.
- **REQ-NEOFORGE-ARCH-010 — Pre-Init Guard:** Public API entry points dependent on `RTP.getInstance()` shall throw `IllegalStateException` when invoked before `rtp-core` is initialized.

### 2.4 Configuration & Persistence
- **REQ-NEOFORGE-ARCH-011 — Config Directory Resolution:** The adapter shall locate the plugin configuration directory under the NeoForge config directory (`<gameDir>/config/rtp`) and shall create it if absent.
- **REQ-NEOFORGE-ARCH-012 — Database Delegation:** Persistence shall delegate to `rtp-core`'s `DatabaseHandler` without introducing a NeoForge-specific persistence abstraction.

### 2.5 External Hooks
- **REQ-NEOFORGE-ARCH-013 — Reflection-Gated Claim Hooks (S-003):** NeoForge-side claim integrations (FTB Chunks, OpenPartiesAndClaims, Argonauts, and similar) shall be reflection-gated soft hooks cataloged in [`EXTERNAL_HOOKS.md`](../../docs/dev/EXTERNAL_HOOKS.md) per [ADR-026](../../docs/adr/ADR-026-external-hook-api-surface.md); no claim-mod code shall reside in the teleport pipeline.

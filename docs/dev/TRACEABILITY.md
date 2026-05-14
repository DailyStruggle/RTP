# RTP Requirements Traceability Matrix

This document connects each requirement to the design decision that motivated it and the source code that implements it. Where automated tests exist, they are linked as well.

> **How to read this table**
> - **Req ID** â€” unique identifier from the relevant `REQUIREMENTS.md` file.
> - **Design Ref** â€” section in [`DESIGN.md`](DESIGN.md) or [`ARCHITECTURE.md`](ARCHITECTURE.md) that describes the decision.
> - **Implementing Class(es)** â€” primary source file(s) that satisfy the requirement.
> - **Test(s)** â€” automated test(s) that verify the requirement, if any.

---

## Root / System Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-F-001 | 0â€“2 tick response | DESIGN.md Â§1 | `RegionQueueManager` | `SLATest`, `RegionPipelineTest` |
| REQ-RTP-F-002 | Geometry (Circle/Square/Rect) | DESIGN.md Â§3 | `Circle`, `Square`, `Rectangle` | `MemoryShapeTest` |
| REQ-RTP-F-003 | Distributions (Flat/Normal) | DESIGN.md Â§3 | `Circle_Normal`, `Square_Normal` | `MemoryShapeTest` |
| REQ-RTP-F-004 | Multi-region management | DESIGN.md Â§5 | `Region`, `RegionQueueManager` | `RegionConfigLoaderTest` |
| REQ-RTP-F-005 | O(log n) complexity | DESIGN.md Â§3 | `MemoryShape` | `MemoryShapeTest` |
| REQ-RTP-F-006 | No unbounded rerolling | DESIGN.md Â§3 | `MemoryShape` | `RegionPipelineTest` |
| REQ-RTP-F-007 | Uniform distribution | DESIGN.md Â§3 | `Circle`, `Square` | `DeterministicShapeTest` |
| REQ-RTP-F-008 | Non-blocking execution | DESIGN.md Â§1 | `RTPTaskPipe`, adapters | `RTPArchitectureTest` |
| REQ-RTP-F-009 | Redundancy elimination | DESIGN.md Â§3 | `MemoryTracker` | `MemoryShapeTest` |
| REQ-RTP-F-010 | External API hooks | ARCHITECTURE.md | `SelectionAPI` | â€” |
| REQ-RTP-F-011 | Claim integrations | ADR-019 | `GlobalRegionVerifiers` | â€” |
| REQ-RTP-F-012 | Admin scan lifecycle | DESIGN.md Â§1 | `ScanCmd`, `ScanTask` | `ScanCmdTest` |
| REQ-RTP-F-013 | Configurable messages | ARCHITECTURE.md | `ConfigParser`, `MessagesKeys`, `LanguageCmd` | `ConfigParserLanguageTest`, `LanguageCmdTest` |
| REQ-RTP-NF-001 | Persistent state | DESIGN.md Â§4 | `DatabaseAccessor` | `CachedLocationRoundTripTest` |
| REQ-RTP-NF-002 | Thread safety | DESIGN.md Â§2 | `RTPTaskPipe` | `RTPArchitectureTest` |
| REQ-RTP-NF-003 | Logic isolation | ADR-003 | `RTPBukkitPlugin` | `RTPArchitectureTest` |
| REQ-RTP-SYS-001 | Java 21+ | build.gradle | â€” | â€” |
| REQ-RTP-SYS-002 | Platform compatibility | ARCHITECTURE.md | `rtp-spigot`, `rtp-paper`, `rtp-folia` | `TestApiCompatCmdTest` |
| REQ-RTP-S-001 | No lethal destination | DESIGN.md Â§3 | `SafetyCheck` | â€” |
| REQ-RTP-S-002 | No chunk leaks | DESIGN.md Â§6 | `ChunkReservation`, `MemoryTracker` | `ChunkTicketLifecycleTest` |
| REQ-RTP-S-003 | Respect claims | ADR-019 | `GlobalRegionVerifiers` | â€” |
| REQ-RTP-S-004 | No silent failure | DESIGN.md §1 | TeleportPipelineTask | FailureModeTest, RegionPipelineTest |
| REQ-RTP-S-005 | No sync chunk I/O | ADR-015/016 | RTPTaskPipe, adapters | AnvilPrefilterTest, RTPArchitectureTest |
| REQ-RTP-S-006 | No undefined behaviour on early API access | ARCHITECTURE.md â€” rtp-api | `RTPAPI.addShape()`, `RTPAPI.addVerticalAdjustor()`, `RTPAPI.setServerAccessor()` | `RTPAPIGuardTest` (pre-init ISE, write-once guard) |

---

## rtp-api Requirements

| Req ID | Summary | Design Ref | Implementing Class | Test(s) |
|---|---|---|---|---|
| REQ-API-F-001 | Shape registration | ARCH.md | SelectionAPI | - |
| REQ-API-F-002 | Vertical adjustors | ARCH.md | VerticalAdjustor | - |
| REQ-API-F-003 | Validation hooks | DESIGN.md Â§2 | GlobalRegionVerifiers | - |
| REQ-API-F-004 | Agnostic models | ARCH.md | RTPLocation | - |
| REQ-API-F-005 | Command contract | commands-api-ADR-001 | commands-api | - |
| REQ-API-NF-001 | Semantic versioning | `build.gradle` version declarations | â€” | â€” |
| REQ-API-NF-002 | Decoupling | ARCH.md | RTPServerAccessor | RTPArchitectureTest |
| REQ-API-ARCH-001 | Thread-safe API | DESIGN.md Â§2 | FactoryValue | - |
| REQ-API-ARCH-002 | Non-blocking API | DESIGN.md Â§1 | ILocationGenerator | RTPArchitectureTest |
| REQ-API-ARCH-003 | API exception handling | DESIGN.md Â§6 | TeleportPipelineTask | RTPAPIGuardTest |
| REQ-API-ARCH-004 | Lock-free reads | DESIGN.md Â§1 | ConfigParser | ConfigParserLanguageTest |
| REQ-API-ARCH-005 | Brigadier boundary | commands-api-ADR-001 (+ Addendum 2026-05-06, incl. Silent-failure isolation follow-up) | `BrigadierCommandAdapter`, `BrigadierBridgeContext` (`commands-api`); `RTPCmdFabric` (`rtp-fabric-common`) | `ReqApiArch005BrigadierBridgeTest` (4 tests), `BrigadierTreeShapeTest` (9 tests — recursion + sibling-chain + cycle-guard + per-subcommand/parameter/suggestion/requires throw isolation, 2026-05-06) |

---

## rtp-core Requirements

| Req ID | Summary | Design Ref | Implementing Class | Test(s) |
|---|---|---|---|---|
| REQ-CORE-F-001 | Async queue | DESIGN.md Â§1 | RegionQueueManager | FailureModeTest |
| REQ-CORE-F-002 | Bounded refill | DESIGN.md Â§1 | RegionCacheTask | - |
| REQ-CORE-F-003 | Uniform distribution | DESIGN.md Â§3 | MemoryShape | MemoryShapeTest |
| REQ-CORE-F-004 | Deterministic execution | DESIGN.md Â§3 | MemoryShape | RegionPipelineTest |
| REQ-CORE-F-005 | Spatial memory | DESIGN.md Â§3 | MemoryShape | MemoryShapeTest |
| REQ-CORE-F-006 | DB integration | DESIGN.md Â§4 | DatabaseAccessor | - |
| REQ-CORE-F-007 | Task lifespan | DESIGN.md Â§6 | MemoryTracker | - |
| REQ-CORE-F-008 | Orphaned chunk recovery | DESIGN.md Â§6 | ChunkUnloadProcessor | - |
| REQ-CORE-F-009 | L3 backlog cache (order-preserving FIFO; head-blocking promotion on `UNVERIFIED`; head-drop on `INVALIDATED`; one `.mca` bin verified per `Region.execute()` pulse) | DESIGN.md Â§1.1, [ADR-028](../adr/ADR-028-l3-backlog-cache.md) | `BacklogLocationBuffer`, `WorldBacklogBinIndex`, `RegionQueueManager.backlogLocations`, `Region.processBacklog` | `BacklogLocationBufferTest`, `WorldBacklogBinIndexTest` |
| REQ-CORE-ARCH-001 | Lock-free config | DESIGN.md Â§1 | FactoryValue | ConfigParserLanguageTest |
| REQ-CORE-ARCH-002 | Data access pattern | DESIGN.md Â§1 | FactoryValue.getData() | MultiConfigParserIsolationTest |
| REQ-CORE-ARCH-003 | Fault encapsulation | DESIGN.md Â§6 | TeleportPipelineTask | - |
| REQ-CORE-ARCH-004 | runCleanup() | DESIGN.md Â§6 | TeleportPipelineTask | - |
| REQ-CORE-ARCH-005 | Pulse maintenance | DESIGN.md Â§1 | AsyncTaskProcessing | - |
| REQ-CORE-ARCH-006 | Time-bounded tasks | DESIGN.md Â§1 | TimeBoundTaskPipe | - |
| REQ-CORE-ARCH-007 | Tracker registration | DESIGN.md Â§6 | MemoryTracker | - |
| REQ-CORE-ARCH-008 | Max lifespan | DESIGN.md Â§6 | MemoryTracker | - |
| REQ-CORE-ARCH-009 | Accessor injection | ARCH.md | RTP.java | RTPArchitectureTest |
| REQ-CORE-ARCH-010 | No platform imports | ARCH.md | rtp-core package | RTPArchitectureTest |
| REQ-CORE-NF-001 | Shutdown flush | DESIGN.md Â§4 | RTP.stop() | MemoryShapeShutdownTest |

---

## rtp-spigot Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-SPIGOT-F-001 | Synchronous chunk loading | DESIGN.md Â§2 â€” rtp-spigot | `rtp-spigot` adapter (Bukkit chunk API) | `BukkitSchedulerImplTest` (`runTask_onPrimaryThread_executesImmediately`, `runTaskAsynchronously_dispatchesTask_andCompletes`) |
| REQ-SPIGOT-F-002 | Rate-limited sync tasks | DESIGN.md Â§1 â€” Bounded Computation Overhead | `TimeBoundTaskPipe` (spigot binding) | Manual verification required (TimeBoundTaskPipe core logic covered by `SLATest`) |
| REQ-SPIGOT-F-003 | Platform event routing | ARCHITECTURE.md â€” rtp-spigot | Spigot event listeners â†’ `rtp-core` handlers | Manual verification required |
| REQ-SPIGOT-ARCH-001 | `addPluginChunkTicket` over `setForceLoaded` | DESIGN.md Â§6 â€” Chunk Allocation Management | `rtp-spigot` chunk reservation impl | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`) |
| REQ-SPIGOT-ARCH-002 | Plugin-owned ticket leak prevention | DESIGN.md Â§6 â€” Chunk Allocation Management | `rtp-spigot` chunk reservation impl | Manual verification required |
| REQ-SPIGOT-ARCH-003 | `TimeBoundTaskPipe` for main-thread ops | DESIGN.md Â§1 â€” Bounded Computation Overhead | `TimeBoundTaskPipe` | `BukkitSchedulerImplTest` (`runTask_onPrimaryThread_executesImmediately`) |
| REQ-SPIGOT-ARCH-004 | Wall-clock time bounding | DESIGN.md Â§1 â€” Bounded Computation Overhead | `TimeBoundTaskPipe` (nanosecond budget) | `BukkitSchedulerImplTest` (`runTaskTimer_returnsNonNullBukkitTaskHandle`, `cancelTask_preventsScheduledTaskFromRunning`) |
| REQ-SPIGOT-ARCH-005 | Ticket lifecycle pairing | DESIGN.md Â§6 â€” Chunk Allocation Management | `rtp-spigot` `removePluginChunkTicket` call sites | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`, `ticket_is_released_even_when_validator_throws`) |
| REQ-SPIGOT-ARCH-006 | Forced reclamation on disconnect | DESIGN.md Â§6 â€” Orphaned Allocation Recovery | `MemoryTracker` + spigot cleanup listener | Manual verification required |

---

## rtp-paper Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-PAPER-F-001 | `getChunkAtAsync` for non-blocking load | DESIGN.md Â§2 â€” rtp-paper | `rtp-paper` async chunk impl | â€” |
| REQ-PAPER-F-002 | Callback/future-only, no sync waits | DESIGN.md Â§2 â€” rtp-paper | `rtp-paper` async chunk impl | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`) |
| REQ-PAPER-F-003 | Paper-specific API prioritization | DESIGN.md Â§2 â€” rtp-paper | `rtp-paper` adapter | `ServerAccessorImplTest` (`getBiomes_returnsNonEmptyUpperCaseSet` â€” asserts `Registry.BIOME` path) |
| REQ-PAPER-ARCH-001 | `addPluginChunkTicket` over `setForceLoaded` | DESIGN.md Â§6 â€” Chunk Allocation Management | `rtp-paper` chunk reservation impl | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`) |
| REQ-PAPER-ARCH-002 | Plugin-owned ticket leak prevention | DESIGN.md Â§6 â€” Chunk Allocation Management | `rtp-paper` chunk reservation impl | â€” |
| REQ-PAPER-ARCH-003 | `TimeBoundTaskPipe` for main-thread ops | DESIGN.md Â§1 â€” Bounded Computation Overhead | `TimeBoundTaskPipe` (paper binding) | `ServerAccessorImplTest` (`paperScheduler_runTask_executesOnPrimaryThread`) |
| REQ-PAPER-ARCH-004 | Wall-clock time bounding | DESIGN.md Â§1 â€” Bounded Computation Overhead | `TimeBoundTaskPipe` (nanosecond budget) | â€” |
| REQ-PAPER-ARCH-005 | Ticket lifecycle pairing | DESIGN.md Â§6 â€” Chunk Allocation Management | `rtp-paper` `removePluginChunkTicket` call sites | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`, `ticket_is_released_even_when_validator_throws`) |
| REQ-PAPER-ARCH-006 | Forced reclamation on disconnect | DESIGN.md Â§6 â€” Orphaned Allocation Recovery | `MemoryTracker` + paper cleanup listener | â€” |

---

## rtp-folia Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-FOLIA-F-001 | Regional thread isolation | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` regional task dispatcher | â€” |
| REQ-FOLIA-F-002 | `RegionScheduler` / `EntityScheduler` usage | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` scheduler impl | `RTPArchitectureTest` (`scheduler_implementations_must_not_reside_in_core`) |
| REQ-FOLIA-F-003 | Concurrent state mutation safety | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` adapter, `LockFreeLocationBuffer` | â€” |
| REQ-FOLIA-F-004 | Cross-region teleport pipelining | DESIGN.md Â§2 â€” rtp-folia | `TeleportPipelineTask` + folia scheduler | â€” |
| REQ-FOLIA-ARCH-001 | `isOwnedByCurrentRegion` pre-check | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` regional dispatcher | â€” |
| REQ-FOLIA-ARCH-002 | Immediate execution if region owned | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` regional dispatcher | â€” |
| REQ-FOLIA-ARCH-003 | `RegionScheduler.run()` only when not owned | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` regional dispatcher | â€” |
| REQ-FOLIA-ARCH-004 | No `System.nanoTime()` in regional threads | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` (uses `CountBoundTaskPipe`) | â€” |
| REQ-FOLIA-ARCH-005 | `CountBoundTaskPipe` for iterative ops | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` queue replenishment | â€” |
| REQ-FOLIA-ARCH-006 | Count-based task slicing | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` `CountBoundTaskPipe` binding | â€” |
| REQ-FOLIA-ARCH-007 | No Vault calls on region threads | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` economy delegation | â€” |
| REQ-FOLIA-ARCH-008 | Economy delegated to `GlobalRegionScheduler` | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` economy delegation | â€” |
| REQ-FOLIA-ARCH-009 | Async economy result pipelining | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` economy delegation | â€” |
| REQ-FOLIA-ARCH-010 | Native Folia async chunk APIs | DESIGN.md Â§2 â€” rtp-folia | `rtp-folia` chunk impl | â€” |

---

## rtp-fabric Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-FABRIC-F-011 | Login reserve cache wiring (ADR-023): default-world bootstrap + Disconnect refill + Join consumption (firstjoin via `playerdata/<uuid>.dat`) | [ADR-023](../adr/ADR-023-login-reserve-cache.md) *Fabric port*, [MULTI_PLATFORM_PLAN.md](MULTI_PLATFORM_PLAN.md) E3-5 | `FabricEventBridge.initLoginReserveCache` / `refillLoginReserveOnQuit` / `dispatchJoinRtp`; `FabricOnEventTeleports.onJoin` / `primeFromLoginCache` / `hasPlayedBefore`; perm gate via `FabricRTPPlayer.hasPermission` | `ReqFabricAdr023HasPlayedBeforeTest` (6 tests — covers the first-join branch of the consumption path; promotion + region attachment paths are exercised via `rtp-core`'s `RegionPipelineTest`) |

---

## Network / Proxy Requirements

All rows below are **unimplemented**; the multi-server / proxy subsystem is gated by Rule D-005 and ADR-025 (not yet drafted). Design source: [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md). Rows exist so that implementing classes and tests can be filled in as Phase 1+ work lands.

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-NET-001 | Optional network mode (disabled by default) | MULTI_SERVER_PLAN.md — *Config Surface*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-002 | Behavioural parity when disabled | MULTI_SERVER_PLAN.md — *Non-Goals (v1)*, *Phase 1 no-op test*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-003 | Single distribution artifact (backend / proxy role auto-select) | MULTI_SERVER_PLAN.md — *Intended Usage & Deployment Model*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-004 | Safety preservation across the network (S-001…S-006 end-to-end) | MULTI_SERVER_PLAN.md — *Coordinate Resolution Timing*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-005 | Authoritative world state on backends | MULTI_SERVER_PLAN.md — *Architecture Overview*, *Non-Goals (v1)*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-006 | Configurable network messaging (extends REQ-RTP-F-013) | MULTI_SERVER_PLAN.md — *Network Wait Queue*, *Reservation Tokens*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-007 | Non-blocking network I/O (extends REQ-RTP-F-008, REQ-RTP-S-005) | MULTI_SERVER_PLAN.md — *Backend Telemetry Publication*, *Risk & Pitfall Inventory*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-008 | Cross-network fairness (UUID-keyed wait queue, bypass permission semantics) | MULTI_SERVER_PLAN.md — *Network Wait Queue*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-009 | Authenticated, versioned inter-server data relay | MULTI_SERVER_PLAN.md — *Sufficiency Audit*, *Risk & Pitfall Inventory*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-010 | Proxy load-balancing policy (configurable, with disable option) | MULTI_SERVER_PLAN.md — *Load-Balancing Heuristics*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-011 | Reservation token deterministic expiry; no orphaned allocations | MULTI_SERVER_PLAN.md — *Reservation Tokens — Lifecycle ownership matrix*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-012 | Exactly-once reservation claim across the network | MULTI_SERVER_PLAN.md — *Reservation Tokens — Lifecycle ownership matrix*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-013 | Multi-flavour persistence compatibility (any shipped SQL accessor) | MULTI_SERVER_PLAN.md — *Storage — Reuse `AbstractSQLDatabaseAccessor`*; ADR-025 (pending) | — (unimplemented) | — |
| REQ-RTP-NET-014 | Multi-proxy concurrency and reanimation (no singleton proxy assumption) | MULTI_SERVER_PLAN.md — *Multi-Proxy Deployment*; ADR-025 (pending) | — (unimplemented) | — |

---

## Coverage Summary

| Module | Total Reqs | Automated Test Coverage |
|---|---|---|
| Root / System | 24 | 12 (REQ-RTP-F-001 `SLATest`+`RegionPipelineTest`, REQ-RTP-F-006/007 `RegionPipelineTest`, REQ-RTP-F-008, REQ-RTP-F-012 `ScanCmdTest`+`ScanTaskProcessingTest`, REQ-RTP-F-013 `ConfigParserLanguageTest`, REQ-RTP-NF-002, REQ-RTP-NF-003 via `RTPArchitectureTest`, REQ-RTP-SYS-001 via build, REQ-RTP-S-004 `FailureModeTest`+`RegionPipelineTest`, REQ-RTP-S-005, REQ-RTP-S-006 `RTPAPIGuardTest`) |
| rtp-api | 10 | 4 (REQ-API-NF-002, REQ-API-ARCH-002, REQ-API-ARCH-003 `RTPAPIGuardTest`, REQ-API-ARCH-004) |
| rtp-core | 20 | 13 (REQ-CORE-F-001 `FailureModeTest`+`RegionPipelineTest`, REQ-CORE-F-003â€“005, REQ-CORE-F-009 `BacklogLocationBufferTest`+`WorldBacklogBinIndexTest`, REQ-CORE-ARCH-001â€“002, REQ-CORE-ARCH-009â€“010, REQ-CORE-NF-001 `MemoryShapeShutdownTest`+`CachedLocationRoundTripTest`; REQ-CORE-F-003/004 also covered end-to-end by `RegionPipelineTest`) |
| rtp-spigot | 9 | 4 (REQ-SPIGOT-F-001, REQ-SPIGOT-ARCH-001/005 via `ChunkTicketLifecycleTest`, REQ-SPIGOT-ARCH-003/004 via `BukkitSchedulerImplTest`) |
| rtp-paper | 9 | 5 (REQ-PAPER-F-002 via architecture rule, REQ-PAPER-F-003 and REQ-PAPER-ARCH-003 via `ServerAccessorImplTest`, REQ-PAPER-ARCH-001/005 via `ChunkTicketLifecycleTest`) |
| rtp-folia | 14 | 1 (REQ-FOLIA-F-002 via architecture rule) |
| rtp-fabric | 1 | 1 (REQ-FABRIC-F-011 via `ReqFabricAdr023HasPlayedBeforeTest`) |
| Network / Proxy | 14 | 0 (subsystem unimplemented; gated by ADR-025) |
| **Total** | **90** | **~44** |

> **Deterministic RNG seam:** `MemoryShape.setRng(Random)`, `LocationGenerator.setRng(Random)`, and `RTPCmd.setRng(Random)` allow any test to inject a seeded `java.util.Random` and eliminate RNG as a source of flakiness. `DeterministicShapeTest` (12 tests) exercises this seam for `Circle`, `Square`, and `Rectangle`. The biome-recall path in `LocationGenerator` uses the same seam.

> **Real-region pipeline tests (`RegionPipelineTest`, 12 tests):** Exercises the full `LocationGenerator.getLocation(Region, biomeNames)` pipeline using actual `Region`/`MockRTPWorld`/`MockRTPChunk` mock components â€” no `MockLocationGenerator` stub. Covers: queue promotion (`unkeptLocations` â†’ `keptLocations`), `hasLocation` state transitions, queue-length APIs, biome filter acceptance (PLAINS) and rejection (DEEP_OCEAN), coords-inside-shape bounds assertion, and end-to-end determinism (same/different seed). `RTPTestSetup.install()` now calls `Configs.reloadConfigs()` so all core parsers (`SafetyKeys`, `PerformanceKeys`, `LoggingKeys`) are available to every test that needs them. `MockRTPChunk` (new testFixture) provides an all-safe, all-air chunk; `MockRTPWorld` now encodes chunk coords into the cache key so `getCachedChunk()` returns the correct chunk at any (cx, cz). `MockRTPServerAccessor.getWorldBorder()` now returns an always-inside `WorldBorder` and exposes `setLocationGenerator()` to swap in the real pipeline.

> **Gap:** The adapter modules (spigot, paper, folia) have low but growing automated test coverage.
> MockBukkit is now integrated into `rtp-spigot-common` and `rtp-paper-v1_20_R1`; the mock support classes have been promoted to a shared `java-test-fixtures` source set in `rtp-core`.
> The remaining highest-value automation steps are:
> 1. ~~Automate chunk ticket lifecycle (REQ-SPIGOT-ARCH-001/005, REQ-PAPER-ARCH-001/005)~~ **Done** â€” `ChunkTicketLifecycleTest` (2 tests) uses `TrackedMockWorld` to assert tickets are held during validation and released (including on exception) via `ChunkReservation` try-with-resources.
> 2. Extend MockBukkit coverage to `rtp-spigot-v1_20_R1`, `rtp-paper-v1_21_R1`, and `rtp-paper-v26_1_R1` using the same `testFixtures` pattern.
> 3. Folia mock infrastructure and economy delegation (REQ-FOLIA-ARCH-007â€“009) require a dedicated Folia mock server or a Folia-compatible MockBukkit fork.

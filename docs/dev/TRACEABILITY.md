# RTP Requirements Traceability Matrix

This document connects each requirement to the design decision that motivated it and the source code that implements it. Where automated tests exist, they are linked as well.

> **How to read this table**
> - **Req ID** — unique identifier from the relevant `REQUIREMENTS.md` file.
> - **Design Ref** — section in [`DESIGN.md`](DESIGN.md) or [`ARCHITECTURE.md`](ARCHITECTURE.md) that describes the decision.
> - **Implementing Class(es)** — primary source file(s) that satisfy the requirement.
> - **Test(s)** — automated test(s) that verify the requirement, if any.

---

## Root / System Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-F-001 | 0–2 tick response time | DESIGN.md §1 — Async Queue-Based Pre-Generation | `RegionQueueManager`, `TeleportPipelineTask` | `SLATest` (cache-hit 0-tick, deferred ≤2-tick, 100 ms wall-clock ceiling); `RegionPipelineTest` (`execute_promotes_unkept_location_to_kept`, `hasLocation_false_before_any_locations_added`) |
| REQ-RTP-F-002 | Configurable geometry (circle, square, rectangle) | DESIGN.md §3 — Deterministic Spatial Algorithms | `Circle`, `Square`, `Rectangle` | `MemoryShapeTest` |
| REQ-RTP-F-003 | Statistical distributions (Flat, Normal, Exponential) | DESIGN.md §3 — Mathematical Distributions | `Circle_Normal`, `Square_Normal`, `MemoryShape` | `MemoryShapeTest` |
| REQ-RTP-F-004 | Multi-region management | DESIGN.md §5 — Isolated Regional Contexts | `Region`, `RegionQueueManager`, `RegionSettings` | `RegionConfigLoaderTest` |
| REQ-RTP-F-005 | O(log n) selection complexity | DESIGN.md §3 — Archimedean Spirals / 1D mapping | `MemoryShape` | `MemoryShapeTest` |
| REQ-RTP-F-006 | No unbounded rerolling | DESIGN.md §3 — Non-Deterministic Execution | `MemoryShape` (bad-sector subtraction) | `MemoryShapeTest`; `RegionPipelineTest` (`full_pipeline_with_impossible_biome_returns_null_coords` — exhausts attempts without infinite loop) |
| REQ-RTP-F-007 | Uniform spatial distribution | DESIGN.md §3 — Distribution Skew | `MemoryShape`, `Circle`, `Square` | `MemoryShapeTest`, `DeterministicShapeTest` (`circle/square_randOutputIsWithinRange` — decoded coords always inside shape bounds); `RegionPipelineTest` (`full_pipeline_result_coords_lie_inside_circle_bounds` — end-to-end coords inside shape) |
| REQ-RTP-F-008 | Non-blocking execution | DESIGN.md §1 — Constant-Time Execution | `TimeBoundTaskPipe`, `AsyncTaskProcessing` | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`) |
| REQ-RTP-F-009 | Redundant calculation elimination | DESIGN.md §3 — Stateful Memory Tracking | `MemoryShape`, `MemoryTracker` | `MemoryShapeTest` |
| REQ-RTP-F-010 | External API for custom shapes/validators | ARCHITECTURE.md — rtp-api | `SelectionAPI`, `rtp-api` interfaces | — |
| REQ-RTP-F-011 | Claim/protection plugin integration | ARCHITECTURE.md — Addons | `GlobalRegionVerifiers`, `addons/RTP_ClaimPluginIntegrations` | — |
| REQ-RTP-NF-001 | State persistence across restarts | DESIGN.md §4 — Persistent State and Fault Tolerance | `DatabaseAccessor`, `H2DatabaseAccessor`, `SQLiteDatabaseAccessor` | — |
| REQ-RTP-NF-002 | Cross-platform thread safety | DESIGN.md §2 — Concurrency and Platform-Specific Thread Safety | `RTPTaskPipe`, `TimeBoundTaskPipe`, platform adapters | `RTPArchitectureTest` (`scheduler_implementations_must_not_reside_in_core`) |
| REQ-RTP-SYS-001 | Java 21 runtime | `build.gradle` (`sourceCompatibility = 21`) | — | — |
| REQ-RTP-SYS-002 | Bukkit/Spigot/Paper/Folia compatibility | ARCHITECTURE.md — Platform Adapters | `rtp-spigot`, `rtp-paper`, `rtp-folia` modules | — |
| REQ-RTP-S-001 | No lethal teleport destination | DESIGN.md §3 — Safety Check Layer | `RegionVerifier`, `SafetyCheck`, `GlobalRegionVerifiers` | — |
| REQ-RTP-S-002 | No persistent force-loaded chunks | DESIGN.md §6 — Active Task and Resource Tracking | `ChunkReservation`, `MemoryTracker` | — |
| REQ-RTP-S-003 | No teleport into protected territory | ARCHITECTURE.md — Addons | `GlobalRegionVerifiers`, `addons/RTP_ClaimPluginIntegrations` | — |
| REQ-RTP-S-004 | No silent failure | DESIGN.md §1 — Fault Tolerance | `TeleportPipelineTask`, `MessagesKeys`, `RegionQueueManager`, `MemoryShape` | `FailureModeTest` (FM-001 deferral/replenishment, FM-002 all-sectors-bad); `RegionPipelineTest` (`full_pipeline_with_impossible_biome_returns_null_coords`) |
| REQ-RTP-S-005 | No synchronous chunk I/O on main thread | DESIGN.md §2 — Platform-Specific Thread Safety | `RTPTaskPipe`, platform adapters | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`) |
| REQ-RTP-S-006 | No undefined behaviour on early API access | ARCHITECTURE.md — rtp-api | `RTPAPI.addShape()`, `RTPAPI.addVerticalAdjustor()`, `RTPAPI.setServerAccessor()` | `RTPAPIGuardTest` (pre-init ISE, write-once guard) |

---

## rtp-api Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-API-F-001 | Custom shape registration | ARCHITECTURE.md — Extensibility and API Boundaries | `SelectionAPI`, `Shape` (interface) | — |
| REQ-API-F-002 | Custom vertical adjustors | ARCHITECTURE.md — Extensibility and API Boundaries | `VerticalAdjustor`, `JumpAdjustor`, `LinearAdjustor` | — |
| REQ-API-F-003 | Async validation hooks | DESIGN.md §2 — Platform-Specific Thread Safety | `GlobalRegionVerifiers`, `ILocationGenerator` | — |
| REQ-API-F-004 | Platform-agnostic models | ARCHITECTURE.md — rtp-api | `RTPLocation`, `RTPWorld`, `RTPPlayer` (rtp-api) | — |
| REQ-API-NF-001 | Semantic versioning | `build.gradle` version declarations | — | — |
| REQ-API-NF-002 | Implementation decoupling | ARCHITECTURE.md — Core Modules | `RTPServerAccessor` (interface only in api) | `RTPArchitectureTest` (`core_must_not_depend_on_platform_apis`) |
| REQ-API-ARCH-001 | Thread-safe API interfaces | DESIGN.md §2 — Concurrency | `FactoryValue` (`EnumMap`/`ConcurrentHashMap` backing) | — |
| REQ-API-ARCH-002 | Non-blocking API contracts | DESIGN.md §1 — Constant-Time Execution | `ILocationGenerator`, `RTPTaskPipe` | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`) |
| REQ-API-ARCH-003 | Exception handling at API boundary | DESIGN.md §6 — Active Task and Resource Tracking | `TeleportPipelineTask` (try-finally blocks), `RTPAPI.setServerAccessor()` | `RTPAPIGuardTest` (`addShape` pre-init ISE, null-accessor IAE, double-init ISE) |
| REQ-API-ARCH-004 | Lock-free config reads | DESIGN.md §1 — Bounded Computation Overhead | `FactoryValue.getData()`, `ConfigParser` | `ConfigParserLanguageTest`, `MultiConfigParserIsolationTest` |

---

## rtp-core Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-CORE-F-001 | Async pre-generation queue | DESIGN.md §1 — Async Queue-Based Pre-Generation | `RegionQueueManager`, `LockFreeLocationBuffer` | `FailureModeTest` (FM-001: poll returns null, deferral, replenishment fulfillment); `RegionPipelineTest` (`execute_promotes_unkept_location_to_kept`, `public_queue_length_counts_both_kept_and_unkept`, `total_queue_length_includes_per_player_locations`) |
| REQ-CORE-F-002 | Bounded queue replenishment | DESIGN.md §1 — Bounded Computation Overhead | `RegionCacheTask`, `TimeBoundTaskPipe` | — |
| REQ-CORE-F-003 | Uniform distribution guarantee | DESIGN.md §3 — Distribution Skew | `MemoryShape`, `Circle`, `Square` | `MemoryShapeTest`, `DeterministicShapeTest` (`*_sameSeed_producesSameSequence`, `*_differentSeeds_produceDifferentSequences`); `RegionPipelineTest` (`same_seed_produces_identical_result`, `different_seeds_produce_different_results`) |
| REQ-CORE-F-004 | Deterministic execution, no rerolling | DESIGN.md §3 — Non-Deterministic Execution | `MemoryShape` (1D spiral mapping) | `MemoryShapeTest`, `DeterministicShapeTest` (`*_randOutputIsWithinRange` — verifies `rand()` ∈ `[0,range)` under fixed seed); `RegionPipelineTest` (`same_seed_produces_identical_result` — full pipeline reproducible end-to-end) |
| REQ-CORE-F-005 | Persistent spatial memory | DESIGN.md §3 — Stateful Memory Tracking | `MemoryShape` (persisted bad-sector map) | `MemoryShapeTest` |
| REQ-CORE-F-006 | Database integration | DESIGN.md §4 — Database Integration | `DatabaseAccessor`, `H2DatabaseAccessor`, `SQLiteDatabaseAccessor`, `YamlFileDatabase` | — |
| REQ-CORE-F-007 | Task lifespan enforcement | DESIGN.md §6 — Task Pipeline Monitoring | `MemoryTracker`, `TrackedObject` | — |
| REQ-CORE-F-008 | Orphaned chunk recovery | DESIGN.md §6 — Orphaned Allocation Recovery | `MemoryTracker`, `ChunkUnloadProcessor` | — |
| REQ-CORE-ARCH-001 | Lock-free config storage | DESIGN.md §1 — Bounded Computation Overhead | `FactoryValue` (`EnumMap` backing) | `ConfigParserLanguageTest` |
| REQ-CORE-ARCH-002 | `FactoryValue.getData()` access pattern | DESIGN.md §1 — Bounded Computation Overhead | `FactoryValue.getData(E key)` | `ConfigParserLanguageTest`, `MultiConfigParserIsolationTest` |
| REQ-CORE-ARCH-003 | Pipeline fault encapsulation (try-finally) | DESIGN.md §6 — Task Pipeline Monitoring | `TeleportPipelineTask` | — |
| REQ-CORE-ARCH-004 | `runCleanup()` in finally block | DESIGN.md §6 — Chunk Allocation Management | `TeleportPipelineTask.runCleanup()` | — |
| REQ-CORE-ARCH-005 | Pulse-driven maintenance | DESIGN.md §1 — Bounded Computation Overhead | `SyncTaskProcessing`, `AsyncTaskProcessing` | — |
| REQ-CORE-ARCH-006 | Time-bounded pulse tasks | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe`, `MemoryTracker.runDiagnostics()` | — |
| REQ-CORE-ARCH-007 | MemoryTracker registration | DESIGN.md §6 — Active Task and Resource Tracking | `MemoryTracker`, `TeleportData`, `RTPRunnable` | — |
| REQ-CORE-ARCH-008 | Max lifespan enforcement | DESIGN.md §6 — Task Pipeline Monitoring | `MemoryTracker` (120 000 ms teleport cap) | — |
| REQ-CORE-ARCH-009 | Core uses only `RTPServerAccessor` | ARCHITECTURE.md — rtp-core | `RTP.java` (accessor injection point) | `RTPArchitectureTest` (`core_must_not_depend_on_platform_apis`) |
| REQ-CORE-ARCH-010 | No platform imports in core | ARCHITECTURE.md — rtp-core | Entire `rtp-core` package | `RTPArchitectureTest` (`core_must_not_depend_on_platform_apis`) |

---

## rtp-spigot Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-SPIGOT-F-001 | Synchronous chunk loading | DESIGN.md §2 — rtp-spigot | `rtp-spigot` adapter (Bukkit chunk API) | `BukkitSchedulerImplTest` (`runTask_onPrimaryThread_executesImmediately`, `runTaskAsynchronously_dispatchesTask_andCompletes`) |
| REQ-SPIGOT-F-002 | Rate-limited sync tasks | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` (spigot binding) | Manual verification required (TimeBoundTaskPipe core logic covered by `SLATest`) |
| REQ-SPIGOT-F-003 | Platform event routing | ARCHITECTURE.md — rtp-spigot | Spigot event listeners → `rtp-core` handlers | Manual verification required |
| REQ-SPIGOT-ARCH-001 | `addPluginChunkTicket` over `setForceLoaded` | DESIGN.md §6 — Chunk Allocation Management | `rtp-spigot` chunk reservation impl | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`) |
| REQ-SPIGOT-ARCH-002 | Plugin-owned ticket leak prevention | DESIGN.md §6 — Chunk Allocation Management | `rtp-spigot` chunk reservation impl | Manual verification required |
| REQ-SPIGOT-ARCH-003 | `TimeBoundTaskPipe` for main-thread ops | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` | `BukkitSchedulerImplTest` (`runTask_onPrimaryThread_executesImmediately`) |
| REQ-SPIGOT-ARCH-004 | Wall-clock time bounding | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` (nanosecond budget) | `BukkitSchedulerImplTest` (`runTaskTimer_returnsNonNullBukkitTaskHandle`, `cancelTask_preventsScheduledTaskFromRunning`) |
| REQ-SPIGOT-ARCH-005 | Ticket lifecycle pairing | DESIGN.md §6 — Chunk Allocation Management | `rtp-spigot` `removePluginChunkTicket` call sites | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`, `ticket_is_released_even_when_validator_throws`) |
| REQ-SPIGOT-ARCH-006 | Forced reclamation on disconnect | DESIGN.md §6 — Orphaned Allocation Recovery | `MemoryTracker` + spigot cleanup listener | Manual verification required |

---

## rtp-paper Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-PAPER-F-001 | `getChunkAtAsync` for non-blocking load | DESIGN.md §2 — rtp-paper | `rtp-paper` async chunk impl | — |
| REQ-PAPER-F-002 | Callback/future-only, no sync waits | DESIGN.md §2 — rtp-paper | `rtp-paper` async chunk impl | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`) |
| REQ-PAPER-F-003 | Paper-specific API prioritization | DESIGN.md §2 — rtp-paper | `rtp-paper` adapter | `ServerAccessorImplTest` (`getBiomes_returnsNonEmptyUpperCaseSet` — asserts `Registry.BIOME` path) |
| REQ-PAPER-ARCH-001 | `addPluginChunkTicket` over `setForceLoaded` | DESIGN.md §6 — Chunk Allocation Management | `rtp-paper` chunk reservation impl | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`) |
| REQ-PAPER-ARCH-002 | Plugin-owned ticket leak prevention | DESIGN.md §6 — Chunk Allocation Management | `rtp-paper` chunk reservation impl | — |
| REQ-PAPER-ARCH-003 | `TimeBoundTaskPipe` for main-thread ops | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` (paper binding) | `ServerAccessorImplTest` (`paperScheduler_runTask_executesOnPrimaryThread`) |
| REQ-PAPER-ARCH-004 | Wall-clock time bounding | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` (nanosecond budget) | — |
| REQ-PAPER-ARCH-005 | Ticket lifecycle pairing | DESIGN.md §6 — Chunk Allocation Management | `rtp-paper` `removePluginChunkTicket` call sites | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`, `ticket_is_released_even_when_validator_throws`) |
| REQ-PAPER-ARCH-006 | Forced reclamation on disconnect | DESIGN.md §6 — Orphaned Allocation Recovery | `MemoryTracker` + paper cleanup listener | — |

---

## rtp-folia Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-FOLIA-F-001 | Regional thread isolation | DESIGN.md §2 — rtp-folia | `rtp-folia` regional task dispatcher | — |
| REQ-FOLIA-F-002 | `RegionScheduler` / `EntityScheduler` usage | DESIGN.md §2 — rtp-folia | `rtp-folia` scheduler impl | `RTPArchitectureTest` (`scheduler_implementations_must_not_reside_in_core`) |
| REQ-FOLIA-F-003 | Concurrent state mutation safety | DESIGN.md §2 — rtp-folia | `rtp-folia` adapter, `LockFreeLocationBuffer` | — |
| REQ-FOLIA-F-004 | Cross-region teleport pipelining | DESIGN.md §2 — rtp-folia | `TeleportPipelineTask` + folia scheduler | — |
| REQ-FOLIA-ARCH-001 | `isOwnedByCurrentRegion` pre-check | DESIGN.md §2 — rtp-folia | `rtp-folia` regional dispatcher | — |
| REQ-FOLIA-ARCH-002 | Immediate execution if region owned | DESIGN.md §2 — rtp-folia | `rtp-folia` regional dispatcher | — |
| REQ-FOLIA-ARCH-003 | `RegionScheduler.run()` only when not owned | DESIGN.md §2 — rtp-folia | `rtp-folia` regional dispatcher | — |
| REQ-FOLIA-ARCH-004 | No `System.nanoTime()` in regional threads | DESIGN.md §2 — rtp-folia | `rtp-folia` (uses `CountBoundTaskPipe`) | — |
| REQ-FOLIA-ARCH-005 | `CountBoundTaskPipe` for iterative ops | DESIGN.md §2 — rtp-folia | `rtp-folia` queue replenishment | — |
| REQ-FOLIA-ARCH-006 | Count-based task slicing | DESIGN.md §2 — rtp-folia | `rtp-folia` `CountBoundTaskPipe` binding | — |
| REQ-FOLIA-ARCH-007 | No Vault calls on region threads | DESIGN.md §2 — rtp-folia | `rtp-folia` economy delegation | — |
| REQ-FOLIA-ARCH-008 | Economy delegated to `GlobalRegionScheduler` | DESIGN.md §2 — rtp-folia | `rtp-folia` economy delegation | — |
| REQ-FOLIA-ARCH-009 | Async economy result pipelining | DESIGN.md §2 — rtp-folia | `rtp-folia` economy delegation | — |
| REQ-FOLIA-ARCH-010 | Native Folia async chunk APIs | DESIGN.md §2 — rtp-folia | `rtp-folia` chunk impl | — |

---

## Coverage Summary

| Module | Total Reqs | Automated Test Coverage |
|---|---|---|
| Root / System | 21 | 9 (REQ-RTP-F-001 `SLATest`+`RegionPipelineTest`, REQ-RTP-F-006/007 `RegionPipelineTest`, REQ-RTP-F-008, REQ-RTP-NF-002, REQ-RTP-SYS-001 via build, REQ-RTP-S-004 `FailureModeTest`+`RegionPipelineTest`, REQ-RTP-S-005, REQ-RTP-S-006 `RTPAPIGuardTest`) |
| rtp-api | 8 | 4 (REQ-API-NF-002, REQ-API-ARCH-002, REQ-API-ARCH-003 `RTPAPIGuardTest`, REQ-API-ARCH-004) |
| rtp-core | 18 | 11 (REQ-CORE-F-001 `FailureModeTest`+`RegionPipelineTest`, REQ-CORE-F-003–005, REQ-CORE-ARCH-001–002, REQ-CORE-ARCH-009–010; REQ-CORE-F-003/004 also covered end-to-end by `RegionPipelineTest`) |
| rtp-spigot | 9 | 4 (REQ-SPIGOT-F-001, REQ-SPIGOT-ARCH-001/005 via `ChunkTicketLifecycleTest`, REQ-SPIGOT-ARCH-003/004 via `BukkitSchedulerImplTest`) |
| rtp-paper | 9 | 5 (REQ-PAPER-F-002 via architecture rule, REQ-PAPER-F-003 and REQ-PAPER-ARCH-003 via `ServerAccessorImplTest`, REQ-PAPER-ARCH-001/005 via `ChunkTicketLifecycleTest`) |
| rtp-folia | 14 | 1 (REQ-FOLIA-F-002 via architecture rule) |
| **Total** | **69** | **~39** |

> **Deterministic RNG seam:** `MemoryShape.setRng(Random)`, `LocationGenerator.setRng(Random)`, and `RTPCmd.setRng(Random)` allow any test to inject a seeded `java.util.Random` and eliminate RNG as a source of flakiness. `DeterministicShapeTest` (12 tests) exercises this seam for `Circle`, `Square`, and `Rectangle`. The biome-recall path in `LocationGenerator` uses the same seam.

> **Real-region pipeline tests (`RegionPipelineTest`, 12 tests):** Exercises the full `LocationGenerator.getLocation(Region, biomeNames)` pipeline using actual `Region`/`MockRTPWorld`/`MockRTPChunk` mock components — no `MockLocationGenerator` stub. Covers: queue promotion (`unkeptLocations` → `keptLocations`), `hasLocation` state transitions, queue-length APIs, biome filter acceptance (PLAINS) and rejection (DEEP_OCEAN), coords-inside-shape bounds assertion, and end-to-end determinism (same/different seed). `RTPTestSetup.install()` now calls `Configs.reloadConfigs()` so all core parsers (`SafetyKeys`, `PerformanceKeys`, `LoggingKeys`) are available to every test that needs them. `MockRTPChunk` (new testFixture) provides an all-safe, all-air chunk; `MockRTPWorld` now encodes chunk coords into the cache key so `getCachedChunk()` returns the correct chunk at any (cx, cz). `MockRTPServerAccessor.getWorldBorder()` now returns an always-inside `WorldBorder` and exposes `setLocationGenerator()` to swap in the real pipeline.

> **Gap:** The adapter modules (spigot, paper, folia) have low but growing automated test coverage.
> MockBukkit is now integrated into `rtp-spigot-common` and `rtp-paper-v1_20_R1`; the mock support classes have been promoted to a shared `java-test-fixtures` source set in `rtp-core`.
> The remaining highest-value automation steps are:
> 1. ~~Automate chunk ticket lifecycle (REQ-SPIGOT-ARCH-001/005, REQ-PAPER-ARCH-001/005)~~ **Done** — `ChunkTicketLifecycleTest` (2 tests) uses `TrackedMockWorld` to assert tickets are held during validation and released (including on exception) via `ChunkReservation` try-with-resources.
> 2. Extend MockBukkit coverage to `rtp-spigot-v1_20_R1`, `rtp-paper-v1_21_R1`, and `rtp-paper-v26_1_R1` using the same `testFixtures` pattern.
> 3. Folia mock infrastructure and economy delegation (REQ-FOLIA-ARCH-007–009) require a dedicated Folia mock server or a Folia-compatible MockBukkit fork.

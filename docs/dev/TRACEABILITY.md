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
| REQ-RTP-F-004 | Multi-region management | DESIGN.md §5 — Isolated Regional Contexts | `Region`, `RegionQueueManager`, `RegionSettings`, `Region.rebindWorld` + `RegionConfigLoader.detectFallbackConfiguredWorld` + `OnWorldLoadUnload.onWorldLoad` (auto-world-gen race: regions whose configured world isn't loaded at plugin enable defer the DB hydrate and rebind on `WorldLoadEvent`, protecting REQ-RTP-NF-001 cached-location rows from seed-mismatch deletion on every restart) | `RegionConfigLoaderTest`; `ReqRtpWorldFallbackRebindTest` (7 tests: 4 detection cases for `detectFallbackConfiguredWorld`, normal-bound hydrate path, fallback-bound skips hydrate, `rebindWorld` swaps world + clears flag + hydrates) |
| REQ-RTP-F-005 | O(log n) selection complexity | DESIGN.md §3 — Archimedean Spirals / 1D mapping | `MemoryShape` | `MemoryShapeTest` |
| REQ-RTP-F-006 | No unbounded rerolling | DESIGN.md §3 — Non-Deterministic Execution | `MemoryShape` (bad-sector subtraction) | `MemoryShapeTest`; `RegionPipelineTest` (`full_pipeline_with_impossible_biome_returns_null_coords` — exhausts attempts without infinite loop) |
| REQ-RTP-F-007 | Uniform spatial distribution | DESIGN.md §3 — Distribution Skew | `MemoryShape`, `Circle`, `Square` | `MemoryShapeTest`, `DeterministicShapeTest` (`circle/square_randOutputIsWithinRange` — decoded coords always inside shape bounds); `RegionPipelineTest` (`full_pipeline_result_coords_lie_inside_circle_bounds` — end-to-end coords inside shape) |
| REQ-RTP-F-008 | Non-blocking execution | DESIGN.md §1 — Constant-Time Execution | `TimeBoundTaskPipe`, `AsyncTaskProcessing` | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`) |
| REQ-RTP-F-009 | Redundant calculation elimination | DESIGN.md §3 — Stateful Memory Tracking | `MemoryShape`, `MemoryTracker` | `MemoryShapeTest` |
| REQ-RTP-F-010 | External API for custom shapes/validators | ARCHITECTURE.md — rtp-api | `SelectionAPI`, `rtp-api` interfaces | — |
| REQ-RTP-F-011 | Claim/protection plugin integration | ARCHITECTURE.md — Addons; ADR-019 | `GlobalRegionVerifiers`, `ClaimIntegrations` + `io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims.*Checker` (bundled in `rtp-plugin`) | — |
| REQ-RTP-F-012 | Administrative world-scan lifecycle (`start`/`pause`/`resume`/`reset`/`cancel`) | DESIGN.md §1 — Async Queue-Based Pre-Generation | `ScanCmd`, `ScanStartCmd`, `ScanPauseCmd`, `ScanResumeCmd`, `ScanResetCmd`, `ScanCancelCmd`, `ScanSubCmd`, `ScanTask`, `ScanTaskProcessing` (`rtp-core` and `rtp-spigot-common` bindings) | `ScanCmdTest`, `ScanTaskProcessingTest` |
| REQ-RTP-F-013 | Configurable user messages | ARCHITECTURE.md — Configuration Layer | `ConfigParser`, `MessagesKeys`, `messages.yml`, `BaseRTPCmd.msgBadParameter`, `BaseRTPCmd.msgInvalidCommand` | `ConfigParserLanguageTest`, `InvalidCommandTest` |
| REQ-RTP-NF-001 | State persistence across restarts | DESIGN.md §4 — Persistent State and Fault Tolerance | `DatabaseAccessor.saveCachedLocation`/`deleteCachedLocation`/`loadCachedLocations`, `RegionQueueManager` (save/delete callbacks on both `keptLocations` and `unkeptLocations`), `Region.execute` (deletes from DB for private locations), `Region.hydrateCacheFromDatabase` (shuffles rows on load — order does not matter since hydrated locations always re-seed as unkept stubs and the `RegionCacheTask` deficit loop re-reserves chunks async per REQ-RTP-S-005), `H2DatabaseAccessor`, `SQLiteDatabaseAccessor`, `MySQLDatabaseAccessor`, `PostgreSQLDatabaseAccessor`, `YamlFileDatabase` | `CachedLocationRoundTripTest` (3 tests: full `saveCachedLocation → flushDirtyCache → processQueries → loadCachedLocations` round-trip; multi-row persistence irrespective of kept/unkept origin; `deleteCachedLocation` removes the row); `ReqRtpS004PerPlayerDeletionTest` (verifies consumed locations — both private and public — are deleted from DB during `Region.execute`) |
| REQ-RTP-NF-002 | Cross-platform thread safety | DESIGN.md §2 — Concurrency and Platform-Specific Thread Safety | `RTPTaskPipe`, `TimeBoundTaskPipe`, platform adapters | `RTPArchitectureTest` (`scheduler_implementations_must_not_reside_in_core`) |
| REQ-RTP-NF-003 | Entry-point logic isolation (bundle plugin decomposition) | ADR-003 — rtp-plugin as Separate Bridge Module; ARCHITECTURE.md — Module Breakdown | `RTPBukkitPlugin` (slimmed entry point), `BukkitDatabaseHandler`, `BukkitEffectsHandler`, `BukkitServerProvider`, `JarUtils` | `RTPArchitectureTest` (`core_must_not_depend_on_platform_apis`) |
| REQ-RTP-SYS-001 | Java 21 runtime | `build.gradle` (`sourceCompatibility = 21`) | — | — |
| REQ-RTP-SYS-002 | Bukkit/Spigot/Paper/Folia compatibility | ARCHITECTURE.md — Platform Adapters | `rtp-spigot`, `rtp-paper`, `rtp-folia` modules, `TestApiCompatCmd` (runtime reflective probe of every Bukkit/Paper/Folia method RTP calls; WARN on missing-method, skipped on platform-conditional missing-class or mismatched platform) | `TestApiCompatCmdTest` (ok / missing-class / missing-method / missing-param-type / primitive-param / curated-list well-formed) |
| REQ-RTP-S-001 | No lethal teleport destination | DESIGN.md §3 — Safety Check Layer | `RegionVerifier`, `SafetyCheck`, `GlobalRegionVerifiers` | — |
| REQ-RTP-S-002 | No persistent force-loaded chunks | DESIGN.md §6 — Active Task and Resource Tracking | `ChunkReservation`, `MemoryTracker`, `TestChunkTicketCmd` (runtime positive-path probe of every `MemoryTracker` release path: `untrack(UUID)`, `untrack(Object)`, `runDiagnostics()` against a live non-leaking entry), `TestDisconnectMidflightCmd` (synthetic-UUID probe mirroring `OnPlayerQuit` + `RTPTeleportCancel.refund`; asserts `processingPlayers` / `invulnerablePlayers` / `latestTeleportData` cleared and `nextTask.cancelled` true) | `TestChunkTicketCmdTest` (clean-tracker pass, label-isolation from unrelated registry traffic, repeat-invocation idempotence); `TestDisconnectMidflightCmdTest` (clean-state pass, probe self-containment, collision-avoidance against pre-seeded UUIDs, idempotence across repeats) |
| REQ-RTP-S-003 | No teleport into protected territory | ARCHITECTURE.md — Addons; ADR-019 | `GlobalRegionVerifiers`, `ClaimIntegrations` + `io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims.*Checker` (bundled in `rtp-plugin`) | — |
| REQ-RTP-S-004 | No silent failure | DESIGN.md §1 — Fault Tolerance | `TeleportPipelineTask`, `MessagesKeys`, `RegionQueueManager`, `MemoryShape`, `LocationGenerator.FailTypes.nullChunk` (attribution + `reason=asyncLoadNull` / `reason=neighborNull` sub-keys at both `chunk == null` exit sites in `getLocation(Region, Set)`; `Level.FINE` per-candidate breadcrumb for offline debugging), `TestStressCmd` (hard-cap iteration/interval clamps; WARN on unknown player), `TestCancelCmd` + `ActiveTestJobs` (WARN on denied `all`; every cancel outcome logged), `TestSchedulerCmd` (WARN on tier timeout/failure), `TestReloadSafetyCmd` (WARN on `configs.reload()` returning false or throwing), `TestCommandsCmd` (WARN on every command-tree audit finding: blank name, blank permission, key/name mismatch, null children, traversal errors), `LiveCommandDispatcherTestJob` (WARN on any Throwable escaping `Bukkit.dispatchCommand` during malformed-input dispatch; WARN on any malformed input that produces neither a sender message nor a WARN log record), `TestFullCmd` (umbrella sweep; WARN on any shipped subcommand missing from the parent lookup or throwing during dispatch; S-004 continuity contract per RUNTIME_TEST_SUITE_PLAN.md §3.2) | `FailureModeTest` (FM-001 deferral/replenishment, FM-002 all-sectors-bad); `RegionPipelineTest` (`full_pipeline_with_impossible_biome_returns_null_coords`); `ReqRtpS004NullChunkAttributionTest` (`null_key_candidates_are_attributed_to_nullChunk_bucket` — simulates Anvil-prefilter REJECT via `MockRTPWorld.nullChunkKeyPredicate`, asserts the pregen summary contains `cause=nullChunk reason=asyncLoadNull`; baseline asserts no false attribution on healthy chunk loads); `TestStressCmdClampTest` (hard caps on `iterations`/`intervalTicks`; fallback-parse behaviour); `ActiveTestJobsTest` (cancel registry isolation, throwing-canceller isolation, unregister hook, sweep-all); `TestCommandsCmdAuditTest` (clean tree, blank-perm, key/name mismatch, null child, `test`-subtree skip, `findRoot` climb, cycle termination); `TestFullCmdTest` (shipped-list well-formedness, clean coverage, missing-subcommand flagged, unexpected-sibling flagged, null-parent fallback, null-child flagged, parity against real `TestCmd` registration) |
| REQ-RTP-S-005 | No synchronous chunk I/O on main thread | DESIGN.md §2 — Platform-Specific Thread Safety; ADR-015 — Stale-Chunk Guard for Count-Bound Pipes; ADR-016 — Anvil Read-Only Subsystem | `RTPTaskPipe`, platform adapters, `RTPWorld.isChunkLoaded`, `LocationGenerator` (stale-chunk guards at the two `safetyCheck` entry points and pre-`vert.adjust`), `FoliaLocationGenerator.LocationSearchTask` (bounded re-queue via `SafetyKeys.staleChunkRetryLimit`), `BukkitRTPWorld.isChunkLoaded`, `FoliaRTPWorld.isChunkLoaded`, `BukkitRTPWorld.getChunkAt` (Anvil pre-filter wire-in), `io.github.dailystruggle.rtp.anvil.AnvilPrefilter` (off-tick probe on `ForkJoinPool.commonPool()`), `AnvilReader`, `AnvilChunkView`, `DataVersionSupport`, `PaletteNormalizer`, `io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer`, `SafetyKeys.anvilPrefilterEnabled` | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`); `ReqRtpS005StaleChunkGuardTest` (`stale_center_chunk_bypasses_block_evaluation` — asserts `MockRTPChunk.isSafe` is never invoked when `isChunkLoadedPredicate` simulates Folia native GC eviction; `loaded_chunk_allows_block_evaluation` — baseline); `AnvilPrefilterTest` (8 cases — REJECT on synthetic lava / namespace-split REJECT / ACCEPT on stone / empty-unsafe ACCEPT / UNKNOWN on missing region file / UNKNOWN on unsupported DataVersion / negative-coord floorMod resolution / real-fixture ACCEPT); `AnvilFixtureParityTest` (§8.2 parity gate, 7 cases); `AnvilChunkViewTest` (palette semantics, 10 cases); `PaletteNormalizerTest` (split-normalization, 8 cases); `PaletteIdentifierNormalizerTest` (pure-string canonical form, 11 cases); `AnvilPackageBoundaryArchTest` (2 ArchUnit rules — spigot-only boundary + no `org.bukkit.Chunk` import); `AnvilPrefilterTest#adr018_rejectRetainsView` (ADR-016 regression - `probeSyncDetailed` retains the decoded `AnvilChunkView` on `Verdict.REJECT` so `BukkitRTPWorld.getChunkAt` mints a source-union `BukkitRTPChunk` instead of short-circuiting with a null key) |
| REQ-RTP-S-006 | No undefined behaviour on early API access | ARCHITECTURE.md — rtp-api | `RTPAPI.addShape()`, `RTPAPI.addVerticalAdjustor()`, `RTPAPI.setServerAccessor()` | `RTPAPIGuardTest` (pre-init ISE, write-once guard) |

---

## rtp-api Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-API-F-001 | Custom shape registration | ARCHITECTURE.md — Extensibility and API Boundaries | `SelectionAPI`, `Shape` (interface) | — |
| REQ-API-F-002 | Custom vertical adjustors | ARCHITECTURE.md — Extensibility and API Boundaries | `VerticalAdjustor`, `JumpAdjustor`, `LinearAdjustor` | — |
| REQ-API-F-003 | Async validation hooks | DESIGN.md §2 — Platform-Specific Thread Safety | `GlobalRegionVerifiers`, `ILocationGenerator` | — |
| REQ-API-F-004 | Platform-agnostic models | ARCHITECTURE.md — rtp-api | `RTPLocation`, `RTPWorld`, `RTPPlayer` (rtp-api) | — |
| REQ-API-F-005 | Unified command-tree contract (single source of truth across platforms) | ADR-014 — Brigadier Bridge via commands-api | `commands-api` tree (`CommandsAPICommand`, `TreeCommand`), `BukkitTreeCommand`, `FabricTreeCommand` (registration shim) | — |
| REQ-API-NF-001 | Semantic versioning | `build.gradle` version declarations | — | — |
| REQ-API-NF-002 | Implementation decoupling | ARCHITECTURE.md — Core Modules | `RTPServerAccessor` (interface only in api) | `RTPArchitectureTest` (`core_must_not_depend_on_platform_apis`) |
| REQ-API-ARCH-001 | Thread-safe API interfaces | DESIGN.md §2 — Concurrency | `FactoryValue` (`EnumMap`/`ConcurrentHashMap` backing) | — |
| REQ-API-ARCH-002 | Non-blocking API contracts | DESIGN.md §1 — Constant-Time Execution | `ILocationGenerator`, `RTPTaskPipe` | `RTPArchitectureTest` (`no_blocking_future_calls_in_core_or_api`) |
| REQ-API-ARCH-003 | Exception handling at API boundary | DESIGN.md §6 — Active Task and Resource Tracking | `TeleportPipelineTask` (try-finally blocks), `RTPAPI.setServerAccessor()` | `RTPAPIGuardTest` (`addShape` pre-init ISE, null-accessor IAE, double-init ISE) |
| REQ-API-ARCH-004 | Lock-free config reads | DESIGN.md §1 — Bounded Computation Overhead | `FactoryValue.getData()`, `ConfigParser` | `ConfigParserLanguageTest`, `MultiConfigParserIsolationTest` |
| REQ-API-ARCH-005 | Platform-neutral command adapter boundary (Brigadier `compileOnly`, no re-export) | ADR-014 — Brigadier Bridge via commands-api | `commands-api/build.gradle` (`compileOnly` Brigadier), `BrigadierCommandAdapter` (future, `commands-api`) | — |

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
| REQ-CORE-NF-001 | Deterministic shutdown persistence (spatial-memory flush before stop flag) | DESIGN.md §4 — Persistent State and Fault Tolerance | `MemoryShape` (persist-on-shutdown path), `DatabaseAccessor.processQueries(Long.MAX_VALUE)`, `RTP.stop()` ordering (`flushDirtyCache → processQueries → stop.set(true)`) | `MemoryShapeShutdownTest`, `CachedLocationRoundTripTest` |

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
| Root / System | 24 | 12 (REQ-RTP-F-001 `SLATest`+`RegionPipelineTest`, REQ-RTP-F-006/007 `RegionPipelineTest`, REQ-RTP-F-008, REQ-RTP-F-012 `ScanCmdTest`+`ScanTaskProcessingTest`, REQ-RTP-F-013 `ConfigParserLanguageTest`, REQ-RTP-NF-002, REQ-RTP-NF-003 via `RTPArchitectureTest`, REQ-RTP-SYS-001 via build, REQ-RTP-S-004 `FailureModeTest`+`RegionPipelineTest`, REQ-RTP-S-005, REQ-RTP-S-006 `RTPAPIGuardTest`) |
| rtp-api | 10 | 4 (REQ-API-NF-002, REQ-API-ARCH-002, REQ-API-ARCH-003 `RTPAPIGuardTest`, REQ-API-ARCH-004) |
| rtp-core | 19 | 12 (REQ-CORE-F-001 `FailureModeTest`+`RegionPipelineTest`, REQ-CORE-F-003–005, REQ-CORE-ARCH-001–002, REQ-CORE-ARCH-009–010, REQ-CORE-NF-001 `MemoryShapeShutdownTest`+`CachedLocationRoundTripTest`; REQ-CORE-F-003/004 also covered end-to-end by `RegionPipelineTest`) |
| rtp-spigot | 9 | 4 (REQ-SPIGOT-F-001, REQ-SPIGOT-ARCH-001/005 via `ChunkTicketLifecycleTest`, REQ-SPIGOT-ARCH-003/004 via `BukkitSchedulerImplTest`) |
| rtp-paper | 9 | 5 (REQ-PAPER-F-002 via architecture rule, REQ-PAPER-F-003 and REQ-PAPER-ARCH-003 via `ServerAccessorImplTest`, REQ-PAPER-ARCH-001/005 via `ChunkTicketLifecycleTest`) |
| rtp-folia | 14 | 1 (REQ-FOLIA-F-002 via architecture rule) |
| **Total** | **74** | **~42** |

> **Deterministic RNG seam:** `MemoryShape.setRng(Random)`, `LocationGenerator.setRng(Random)`, and `RTPCmd.setRng(Random)` allow any test to inject a seeded `java.util.Random` and eliminate RNG as a source of flakiness. `DeterministicShapeTest` (12 tests) exercises this seam for `Circle`, `Square`, and `Rectangle`. The biome-recall path in `LocationGenerator` uses the same seam.

> **Real-region pipeline tests (`RegionPipelineTest`, 12 tests):** Exercises the full `LocationGenerator.getLocation(Region, biomeNames)` pipeline using actual `Region`/`MockRTPWorld`/`MockRTPChunk` mock components — no `MockLocationGenerator` stub. Covers: queue promotion (`unkeptLocations` → `keptLocations`), `hasLocation` state transitions, queue-length APIs, biome filter acceptance (PLAINS) and rejection (DEEP_OCEAN), coords-inside-shape bounds assertion, and end-to-end determinism (same/different seed). `RTPTestSetup.install()` now calls `Configs.reloadConfigs()` so all core parsers (`SafetyKeys`, `PerformanceKeys`, `LoggingKeys`) are available to every test that needs them. `MockRTPChunk` (new testFixture) provides an all-safe, all-air chunk; `MockRTPWorld` now encodes chunk coords into the cache key so `getCachedChunk()` returns the correct chunk at any (cx, cz). `MockRTPServerAccessor.getWorldBorder()` now returns an always-inside `WorldBorder` and exposes `setLocationGenerator()` to swap in the real pipeline.

> **Gap:** The adapter modules (spigot, paper, folia) have low but growing automated test coverage.
> MockBukkit is now integrated into `rtp-spigot-common` and `rtp-paper-v1_20_R1`; the mock support classes have been promoted to a shared `java-test-fixtures` source set in `rtp-core`.
> The remaining highest-value automation steps are:
> 1. ~~Automate chunk ticket lifecycle (REQ-SPIGOT-ARCH-001/005, REQ-PAPER-ARCH-001/005)~~ **Done** — `ChunkTicketLifecycleTest` (2 tests) uses `TrackedMockWorld` to assert tickets are held during validation and released (including on exception) via `ChunkReservation` try-with-resources.
> 2. Extend MockBukkit coverage to `rtp-spigot-v1_20_R1`, `rtp-paper-v1_21_R1`, and `rtp-paper-v26_1_R1` using the same `testFixtures` pattern.
> 3. Folia mock infrastructure and economy delegation (REQ-FOLIA-ARCH-007–009) require a dedicated Folia mock server or a Folia-compatible MockBukkit fork.

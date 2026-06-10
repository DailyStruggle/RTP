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
| REQ-RTP-F-001 | 0–2 tick response | DESIGN.md §1 | `RegionQueueManager` | `SLATest`, `RegionPipelineTest` |
| REQ-RTP-F-002 | Geometry (Circle/Square/Rect) | DESIGN.md §3 | `Circle`, `Square`, `Rectangle` | `MemoryShapeTest` |
| REQ-RTP-F-003 | Distributions (Flat/Normal) | DESIGN.md §3 | `Circle_Normal`, `Square_Normal` | `MemoryShapeTest` |
| REQ-RTP-F-004 | Multi-region management | DESIGN.md §5 | `Region`, `RegionQueueManager` | `RegionConfigLoaderTest` |
| REQ-RTP-F-005 | O(log n) complexity | DESIGN.md §3 | `MemoryShape` | `MemoryShapeTest` |
| REQ-RTP-F-006 | No unbounded rerolling | DESIGN.md §3 | `MemoryShape` | `RegionPipelineTest` |
| REQ-RTP-F-007 | Uniform distribution | DESIGN.md §3 | `Circle`, `Square` | `DeterministicShapeTest` |
| REQ-RTP-F-008 | Non-blocking execution | DESIGN.md §1 | `RTPTaskPipe`, adapters | `RTPArchitectureTest` |
| REQ-RTP-F-009 | Redundancy elimination | DESIGN.md §3 | `MemoryTracker` | `MemoryShapeTest` |
| REQ-RTP-F-010 | External API hooks | ARCHITECTURE.md | `SelectionAPI` | — |
| REQ-RTP-F-011 | Claim integrations | ADR-019 | `GlobalRegionVerifiers` | — |
| REQ-RTP-F-012 | Admin scan lifecycle | DESIGN.md §1 | `ScanCmd`, `ScanTask` | `ScanCmdTest` |
| REQ-RTP-F-013 | Configurable messages | ARCHITECTURE.md | `ConfigParser`, `MessagesKeys`, `LanguageCmd`; menu reject paths preserved across the concrete-command surface ([ADR-050](../adr/ADR-050-concrete-menu-commands-supersede-tokens.md) Stage 1a) by routing `MenuConcreteCommandLeaves` into the existing `MenuRedeemSubcommand.dispatch*` helpers | `ConfigParserLanguageTest`, `LanguageCmdTest`, `ReqRtpMenuConcreteCommandsTest` (ADR-050 Stage 1a: 24 cases - registration / routing / permission gating across `/rtp menu open` `/ admin` `/ front` `/ visualizations` and the root `/rtp visualization` sibling) |
| REQ-RTP-NF-001 | Persistent state | DESIGN.md §4 | `DatabaseAccessor` | `CachedLocationRoundTripTest` |
| REQ-RTP-NF-002 | Thread safety | DESIGN.md §2 | `RTPTaskPipe` | `RTPArchitectureTest` |
| REQ-RTP-NF-003 | Logic isolation | ADR-003 | `RTPBukkitPlugin` | `RTPArchitectureTest` |
| REQ-RTP-SYS-001 | Java 21+ | build.gradle | — | — |
| REQ-RTP-SYS-002 | Platform compatibility | ARCHITECTURE.md | `rtp-bukkit`, `rtp-paper`, `rtp-folia` | `TestApiCompatCmdTest` |
| REQ-RTP-S-001 | No lethal destination | DESIGN.md §3 | `SafetyCheck` | — |
| REQ-RTP-S-002 | No chunk leaks | DESIGN.md §6 | `ChunkReservation`, `MemoryTracker` | `ChunkTicketLifecycleTest` |
| REQ-RTP-S-003 | Respect claims | ADR-019, [ADR-058](../adr/ADR-058-region-specific-schematic-paste.md) (schematic-paste footprint guard) | `GlobalRegionVerifiers`; `TeleportPipelineTask#schematicFootprintClear` (region schematic paste runs the whole footprint through the verifier registry and suppresses the paste on any claim intersection, falling back to the default platform) | `ReqRtpS003SchematicFootprintClaimTest` |
| REQ-RTP-S-004 | No silent failure | DESIGN.md �1 | TeleportPipelineTask | FailureModeTest, RegionPipelineTest |
| REQ-RTP-S-005 | No sync chunk I/O | ADR-015/016 | RTPTaskPipe, adapters | AnvilPrefilterTest, RTPArchitectureTest |
| REQ-RTP-S-006 | No undefined behaviour on early API access | ARCHITECTURE.md — rtp-api | `RTPAPI.hooks()`, `RTPAPI.teleport()`, `RTPAPI.setServerAccessor()` (contract surface). Shape/vert registration moved to `RTP.addShape()`/`RTP.addVerticalAdjustor()` extension tier ([ADR-051](../adr/ADR-051-two-tier-api-extension-model.md)) | `RTPAPIGuardTest` (write-once guard), `RtpApiTeleportSurfaceTest` (pre-init ISE), `RtpApiMenuDataSurfaceTest` (menu-data reads: pre-init ISE, null-arg rejection, safe non-null degradation) |
| REQ-RTP-MAP-001 | Require-by-contract `MapBinding` (extends REQ-RTP-S-006 to maps surface) | [ADR-046](../adr/ADR-046-maps-api-module.md), `maps-api/docs/adr/maps-api-ADR-001-bootstrap.md` | `NoopMapBinding` (`maps-api`) | `ReqRtpMap001RequireByContractTest` (Stage 1) |
| REQ-RTP-MAP-002 | No chunk I/O / no blocking `.get()` / `.join()` in `ChartRenderer` (extends REQ-RTP-F-008, REQ-RTP-S-005 to maps surface) | [ADR-046](../adr/ADR-046-maps-api-module.md) | `ChartRenderer`, `HeatmapRenderer` (`maps-api`) | `ReqRtpMap002NoChunkIoTest` (Stage 1; ArchUnit) |
| REQ-RTP-MAP-003 | `MemoryTracker` release on cancel / viewer disconnect / plugin disable | [ADR-046](../adr/ADR-046-maps-api-module.md) | `MapBindingLifecycle` (`maps-api`, Stage 2.2, live), `BukkitMapBinding` (Stage 2.1/2.2, live; implements `MapBindingLifecycle`), `MapDispatch.registerLifecycle` / `firePlayerQuit` / `fireDisable` + `MemoryTracker.track`/`untrack` pair around `paint()` (`rtp-core`, Stage 2.2, live), `OnPlayerQuit` bridge + `RTPBukkitPlugin#onDisable` `fireDisable` call (`rtp-plugin`, Stage 2.2, live); `FoliaMapBinding` (`rtp-folia-common`, Stage 2.3, live; extends `BukkitMapBinding`, overrides `bindLive` with Folia-specific message, adds `dispatchToViewerRegion` hook for Stage 3 live-chart `EntityScheduler` dispatch). `RTPBukkitPlugin#onEnable` installs `FoliaMapBinding` on Folia and plain `BukkitMapBinding` elsewhere via `isFolia()` branch | `BukkitMapBindingTest` (10 cases incl. onPlayerQuit / unknown-quit no-op / onDisable refusal), `MapDispatchTest` (10 cases incl. setMapBinding auto-register, firePlayerQuit fan-out + exception isolation, fireDisable clears registry); `FoliaMapBindingTest` (5 cases: type-hierarchy, Folia-specific `bindLive` message, offline-viewer dispatch no-op, null-arg rejection, unknown-quit no-op) |
| REQ-RTP-MAP-004 | Lite assembly ships the platform-backed `MapBinding` (map rendering is not excluded from Lite); `NoopMapBinding` is the fallback only | [ADR-046](../adr/ADR-046-maps-api-module.md) (Amendment 2026-06-04), [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md) | `BukkitMapBinding` installed unconditionally by `RTPBukkitLitePlugin#onEnable`; `FabricMapBinding` (`RTPFabricMod`) and `NeoForgeMapBinding` (`RTPNeoForgeMod`) on their platforms; `NoopMapBinding` (`maps-api`) fallback only when no platform binding is installed | `liteJarStructureCheck` (asserts `mapsapi/**` is *not* excluded from the lite jar); `RTPBukkitLitePlugin#onEnable` install |
| REQ-RTP-MAP-005 | Mermaid subset acceptance + no external runtime / scripting / layout library | [ADR-046](../adr/ADR-046-maps-api-module.md) *Mermaid output* | `MermaidRenderer`, `MermaidParser`, `MermaidLayout`, `MermaidRasterizer` (Stage 4) | `MermaidParserTest`, `MermaidRasterizerTest` (Stage 4) |
| REQ-RTP-MAP-006 | Declarative chart composition bridge (`ChartSpec` + `ChartSpecResolver` + `MapDispatch`; extends REQ-RTP-F-008 / REQ-RTP-S-005 to chart-composition surface; failure paths under REQ-RTP-F-013 / REQ-RTP-S-004) | [ADR-047](../adr/ADR-047-declarative-chart-composition-bridge.md), [`CHECKLIST-metrics-to-maps.md`](scratch/CHECKLIST-metrics-to-maps.md) | `ChartSpec` (`rtp-api`, Stage 1.A); `ChartSpecResolver`, `ChartSpecResolvers`, `BadPointsHeatmapResolver`, `MapDispatch` (`rtp-core`, Stage 1.B); `ChartSpecTokens`, `MenuAction.OpenMap` (sealed-record permit), `MenuRedeemSubcommand.dispatchOpenMap`, `InfoBookBuilder` map row, `BookMenuRenderer` `OpenMap` case arm (Stage 2 landed 2026-05-21) | `ChartSpecSurfaceTest` (Stage 1.A); `BadPointsHeatmapResolverTest`, `MapDispatchTest`, `ReqRtpMap006CoreNoPlatformImportTest` (Stage 1.B); `ChartSpecTokensTest` (Stage 2.B, 21/21 green); `MenuActionOpenMapBackcompatTest` (Stage 2.A, deferred — sealed-record shape guarantees back-compat structurally) |
| REQ-RTP-OBS-001 | Non-blocking metrics snapshot (extends REQ-RTP-F-008 to metrics surface; M2 adds `FoliaRegionSample` per-region detail) | [METRICS_PLAN.md](METRICS_PLAN.md), [metrics-api-ADR-001](../../metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md) | `Metrics`, `MetricsSnapshot`, `MetricsBinding`, `FoliaRegionSample`, `MetricsExtension` (all in `metrics-api`, `io.github.dailystruggle.metrics.api.*`); `CoreMetrics`, `RTPMetricsExtension` (`rtp-core`) | `ReqRtpObs001CoreMetricsTest`, `ReqRtpObs001MetricsSnapshotTest`, `FoliaRegionSampleTest`, `InfoCmdTest` (Folia-table render/suppress cases) |
| REQ-RTP-OBS-002 | Single-sample pipeline recording (one sample per `TeleportPipelineTask` on every exit path) | [METRICS_PLAN.md](METRICS_PLAN.md) | `PipelineHistogram`, `TeleportPipelineTask.runCleanup` | `ReqRtpObs002PipelineHistogramTest`, `TeleportPipelineTaskPhaseTest#runCleanup_records_one_sample_into_pipeline_histogram` |
| REQ-RTP-OBS-003 | Bounded heap / TPS / MSPT sampling cost (M2 extends to Folia per-region samplers and Fabric server-tick sampler; bStats *Runtime health* charts consume the same snapshot) | [METRICS_PLAN.md](METRICS_PLAN.md), [metrics-api-ADR-001](../../metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md) | `HeapSampler` (`rtp-core`), `PaperMetricsBinding`, `BukkitTpsSampler`, `FoliaMetricsBinding`, `FoliaRegionTpsSampler`, `FabricMetricsBinding` (platform adapters, implementing `metrics-api` `MetricsBinding`), `MetricsBindingDispatcher`, `RTPCostMetricsCharts` (bStats *Runtime health* chart group) | `ReqRtpObs003HeapSamplerTest`, `PaperMetricsBindingTest`, `BukkitTpsSamplerTest`, `FoliaMetricsBindingTest`, `FabricMetricsBindingTest`, `RuntimeHealthBucketsTest` |
| REQ-RTP-OBS-004 | Pipeline-latency percentile readout (P50/P75/P90/P95/P99, on-demand, non-mutating, bounded cost) | [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md), [METRICS_PLAN.md](METRICS_PLAN.md) | `PipelineHistogram.percentile`/`percentiles`/`Percentiles` (`rtp-core`); `PlaceholderProvider` (`[pipelineMsP50..P99]`, `[pipelineSampleCount]`); `InfoCmd` `infoPipelinePercentiles` | `ReqRtpObs004PipelinePercentilesTest` |
| REQ-RTP-OBS-005 | Slow-teleport audit (immediate/unqueued only; opt-out threshold; WARN + cumulative `slowPipelineCount`) | [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md) §2a, [METRICS_PLAN.md](METRICS_PLAN.md) | `CoreMetrics.auditImmediateTeleport`/`slowPipelineCount`/`slowPipelineThresholdMs`, `TeleportPipelineTask` (`immediateTeleport` flag + `runCleanup` audit call), `PerformanceKeys.slowPipelineThresholdMs`, `RTPMetricsExtension.slowPipelineCount/slowPipelineThresholdMs`; `InfoCmd` `infoSlowPipeline` | `ReqRtpObs005SlowTeleportAuditTest` |
| REQ-RTP-OBS-006 | Queue-growth audit (edge-triggered on `queueDepth`; opt-in threshold; WARN + cumulative `queueGrowthWarnCount`) | [ADR-053](../adr/ADR-053-pipeline-latency-percentiles-and-slow-teleport-audit.md) §2b, [METRICS_PLAN.md](METRICS_PLAN.md) | `CoreMetrics.evaluateQueueGrowth`/`queueGrowthWarnCount`/`queueGrowthWarnThreshold`, `PerformanceKeys.queueGrowthWarnThreshold`, `RTPMetricsExtension.queueGrowthWarnCount/queueGrowthWarnThreshold`; `InfoCmd` `infoQueueGrowth` | `ReqRtpObs005SlowTeleportAuditTest` (queue-growth disabled-by-default + snapshot wiring cases) |
| REQ-RTP-OBS-007 | Generation-outcome operator surface (success/failure rate + per-cause rejection breakdown in `/rtp info`; the analogue of a competitor's `/rtp unsafe-stats`) | [ADR-052](../adr/ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md), [METRICS_PLAN.md](METRICS_PLAN.md) M1 | `RtpOutcomeStats` (`rtp-core`, process-global accumulator); `PlaceholderProvider` (`[genSuccessRate]`/`[genFailureRate]`/`[genOutcomeTotal]`/`[genFailureBreakdown]`/`[genTopRejectionCause]`/`[genTopRejectionShare]`); `InfoCmd` `infoFailureRate`/`infoTopRejectionCause`/`infoFailureBreakdown` | `RtpOutcomeStatsInfoPlaceholderTest`, `RtpOutcomeStatsAndCauseTaggingTest` |

---

## rtp-api Requirements

| Req ID | Summary | Design Ref | Implementing Class | Test(s) |
|---|---|---|---|---|
| REQ-API-F-001 | Shape registration | ARCH.md | SelectionAPI | - |
| REQ-API-F-002 | Vertical adjustors | ARCH.md | VerticalAdjustor | - |
| REQ-API-F-003 | Validation hooks | DESIGN.md §2 | GlobalRegionVerifiers | - |
| REQ-API-F-004 | Agnostic models | ARCH.md | RTPLocation | - |
| REQ-API-F-005 | Command contract | commands-api-ADR-001 | commands-api | - |
| REQ-API-F-006 | Bare-/rtp root action | ADR-056 | RootActionRegistry / DefaultRTPHooks / RTPCmd | ReqApiF006RootActionTest |
| REQ-API-NF-001 | Semantic versioning | `build.gradle` version declarations | — | — |
| REQ-API-NF-002 | Decoupling | ARCH.md | RTPServerAccessor | RTPArchitectureTest |
| REQ-API-ARCH-001 | Thread-safe API | DESIGN.md §2 | FactoryValue | - |
| REQ-API-ARCH-002 | Non-blocking API | DESIGN.md §1 | ILocationGenerator | RTPArchitectureTest |
| REQ-API-ARCH-003 | API exception handling | DESIGN.md §6 | TeleportPipelineTask | RTPAPIGuardTest |
| REQ-API-ARCH-004 | Lock-free reads | DESIGN.md §1 | ConfigParser | ConfigParserLanguageTest |
| REQ-API-ARCH-005 | Brigadier boundary | commands-api-ADR-001 (+ Addendum 2026-05-06, incl. Silent-failure isolation follow-up) | `BrigadierCommandAdapter`, `BrigadierBridgeContext` (`commands-api`); `RTPCmdFabric` (`rtp-fabric-common`) | `ReqApiArch005BrigadierBridgeTest` (4 tests), `BrigadierTreeShapeTest` (9 tests � recursion + sibling-chain + cycle-guard + per-subcommand/parameter/suggestion/requires throw isolation, 2026-05-06) |

---

## rtp-core Requirements

| Req ID | Summary | Design Ref | Implementing Class | Test(s) |
|---|---|---|---|---|
| REQ-CORE-F-001 | Async queue | DESIGN.md §1 | RegionQueueManager | FailureModeTest |
| REQ-CORE-F-002 | Bounded refill | DESIGN.md §1 | RegionCacheTask | - |
| REQ-CORE-F-003 | Uniform distribution | DESIGN.md §3 | MemoryShape | MemoryShapeTest |
| REQ-CORE-F-004 | Deterministic execution | DESIGN.md §3 | MemoryShape | RegionPipelineTest |
| REQ-CORE-F-005 | Spatial memory | DESIGN.md §3 | MemoryShape | MemoryShapeTest |
| REQ-CORE-F-006 | DB integration | DESIGN.md §4 | DatabaseAccessor | - |
| REQ-CORE-F-007 | Task lifespan | DESIGN.md §6 | MemoryTracker | - |
| REQ-CORE-F-008 | Orphaned chunk recovery | DESIGN.md §6 | ChunkUnloadProcessor | - |
| REQ-CORE-F-009 | L3 backlog cache (order-preserving FIFO; head-blocking promotion on `UNVERIFIED`; head-drop on `INVALIDATED`; one `.mca` bin verified per `Region.execute()` pulse) | DESIGN.md §1.1, [ADR-028](../adr/ADR-028-l3-backlog-cache.md) | `BacklogLocationBuffer`, `WorldBacklogBinIndex`, `RegionQueueManager.backlogLocations`, `Region.processBacklog` | `BacklogLocationBufferTest`, `WorldBacklogBinIndexTest` |
| REQ-CORE-F-010 | Emergency-platform block-restoration timeout (chunk-loaded countdown, no force-load per REQ-RTP-S-005; DB-persisted resume; row removed on completion; restore failure audited per REQ-RTP-S-004) | [ADR-060](../adr/ADR-060-emergency-platform-block-restoration-timeout.md) | `PlatformRestoreManager`, `PlatformRestoreSqlStore` (`rtp-core`); `BlockDelta`, `PendingPlatformRestore`, `RTPWorld.restoreBlocks` (`rtp-api`); `BukkitRTPWorld.platform`/`restoreBlocks`, `FoliaRTPWorld.platform`/`restoreBlocks`; `SafetyKeys.platformRestoreSeconds` | `ReqRtpAdr060PlatformRestoreTest` (7 cases) |
| REQ-CORE-ARCH-001 | Lock-free config | DESIGN.md §1 | FactoryValue | ConfigParserLanguageTest |
| REQ-CORE-ARCH-002 | Data access pattern | DESIGN.md §1 | FactoryValue.getData() | MultiConfigParserIsolationTest |
| REQ-CORE-ARCH-003 | Fault encapsulation | DESIGN.md §6 | TeleportPipelineTask | - |
| REQ-CORE-ARCH-004 | runCleanup() | DESIGN.md §6 | TeleportPipelineTask | - |
| REQ-CORE-ARCH-005 | Pulse maintenance | DESIGN.md §1 | AsyncTaskProcessing | - |
| REQ-CORE-ARCH-006 | Time-bounded tasks | DESIGN.md §1 | TimeBoundTaskPipe | - |
| REQ-CORE-ARCH-007 | Tracker registration | DESIGN.md §6 | MemoryTracker | - |
| REQ-CORE-ARCH-008 | Max lifespan | DESIGN.md §6 | MemoryTracker | - |
| REQ-CORE-ARCH-009 | Accessor injection | ARCH.md | RTP.java | RTPArchitectureTest |
| REQ-CORE-ARCH-010 | No platform imports | ARCH.md | rtp-core package | RTPArchitectureTest |
| REQ-CORE-NF-001 | Shutdown flush | DESIGN.md §4 | RTP.stop() | MemoryShapeShutdownTest |

---

## rtp-bukkit Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-SPIGOT-F-001 | Synchronous chunk loading | DESIGN.md §2 — rtp-bukkit | `rtp-bukkit` adapter (Bukkit chunk API) | `BukkitSchedulerImplTest` (`runTask_onPrimaryThread_executesImmediately`, `runTaskAsynchronously_dispatchesTask_andCompletes`) |
| REQ-SPIGOT-F-002 | Rate-limited sync tasks | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` (spigot binding) | Manual verification required (TimeBoundTaskPipe core logic covered by `SLATest`) |
| REQ-SPIGOT-F-003 | Platform event routing | ARCHITECTURE.md — rtp-bukkit | Spigot event listeners → `rtp-core` handlers | Manual verification required |
| REQ-SPIGOT-ARCH-001 | `addPluginChunkTicket` over `setForceLoaded` | DESIGN.md §6 — Chunk Allocation Management | `rtp-bukkit` chunk reservation impl | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`) |
| REQ-SPIGOT-ARCH-002 | Plugin-owned ticket leak prevention | DESIGN.md §6 — Chunk Allocation Management | `rtp-bukkit` chunk reservation impl | Manual verification required |
| REQ-SPIGOT-ARCH-003 | `TimeBoundTaskPipe` for main-thread ops | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` | `BukkitSchedulerImplTest` (`runTask_onPrimaryThread_executesImmediately`) |
| REQ-SPIGOT-ARCH-004 | Wall-clock time bounding | DESIGN.md §1 — Bounded Computation Overhead | `TimeBoundTaskPipe` (nanosecond budget) | `BukkitSchedulerImplTest` (`runTaskTimer_returnsNonNullBukkitTaskHandle`, `cancelTask_preventsScheduledTaskFromRunning`) |
| REQ-SPIGOT-ARCH-005 | Ticket lifecycle pairing | DESIGN.md §6 — Chunk Allocation Management | `rtp-bukkit` `removePluginChunkTicket` call sites | `ChunkTicketLifecycleTest` (`ticket_is_held_during_reservation_and_released_on_close`, `ticket_is_released_even_when_validator_throws`) |
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

## rtp-fabric Requirements

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-FABRIC-F-011 | Login reserve cache wiring (ADR-023): default-world bootstrap + Disconnect refill + Join consumption (firstjoin via `playerdata/<uuid>.dat`) | [ADR-023](../adr/ADR-023-login-reserve-cache.md) *Fabric port*, [MULTI_PLATFORM_PLAN.md](MULTI_PLATFORM_PLAN.md) E3-5 | `FabricEventBridge.initLoginReserveCache` / `refillLoginReserveOnQuit` / `dispatchJoinRtp`; `FabricOnEventTeleports.onJoin` / `primeFromLoginCache` / `hasPlayedBefore`; perm gate via `FabricRTPPlayer.hasPermission` | `ReqFabricAdr023HasPlayedBeforeTest` (6 tests � covers the first-join branch of the consumption path; promotion + region attachment paths are exercised via `rtp-core`'s `RegionPipelineTest`) |

---

## rtp-neoforge Requirements

NeoForge is in-scope per [ADR-033](../adr/ADR-033-neoforge-platform-in-scope.md) and [rtp-neoforge-ADR-001](../../platforms/rtp-neoforge/docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md) (Accepted). Per ADR-033 §3.4 the S-005 and S-006 guards are authored as part of the Phase N2 platform-adapter bring-up; both rows below are now **implemented**. Remaining NeoForge functional parity (the `/rtp` round-trip exit gate and the final `-PincludeNeoforge` build) is tracked in [`scratch/CHECKLIST-neoforge-phase-n2.md`](scratch/CHECKLIST-neoforge-phase-n2.md).

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-NEOFORGE-ARCH-S005 | No synchronous chunk I/O on the main thread (`NeoForgeRTPWorld.getChunkAt` returns `CompletableFuture`; anvil-backed `NeoForgeRTPChunk` answers block queries from the decoded view without a live load) | ADR-033 §3.4; rtp-neoforge-ADR-001 §6; REQ-RTP-S-005 | `NeoForgeRTPChunk` (anvil dual-mode dispatch); `NeoForgeVersionAdapter#requestFullChunkAsync` (async path) | `ReqRtpNeoforgeS005ChunkLoadingTest` (6 tests: anvil-mode flags, null-view rejection, view-routed block queries, isSafe short-circuit + delegation, keep/unload no-ops) |
| REQ-NEOFORGE-ARCH-S006 | Fail-loud on early API access (`IllegalStateException`, never null / no-op) pre-init | ADR-033 §3.4; rtp-neoforge-ADR-001 §6; REQ-RTP-S-006 | `NeoForgeVersionAdapterRegistry#require`; `NeoForgeServerAccessor#getLocationGenerator` | `ReqRtpNeoforgeS006ApiBeforeCoreTest` (3 tests: registry not-installed, `require()` throws, `getLocationGenerator()` throws pre-core) |

---

## Network / Proxy Requirements

Most rows below are **unimplemented**; the multi-server / proxy subsystem is governed by the ratified umbrella [ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md) (Accepted 2026-05-14) and ten subproject ADRs under [`platforms/rtp-proxy/docs/adr/`](../../platforms/rtp-proxy/docs/adr/) (Phase 1 set `rtp-proxy-ADR-001..004` Accepted; `005..010` Proposed). Design source: [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md). Phase 1 D3 plumbing (network-state member of `AbstractSQLDatabaseAccessor`) and the parity-when-disabled gate landed 2026-05-18 (REQ-RTP-NET-002, REQ-RTP-NET-013).

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-NET-001 | Optional network mode (disabled by default) | MULTI_SERVER_PLAN.md � *Config Surface*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-002 | Behavioural parity when disabled | MULTI_SERVER_PLAN.md *Non-Goals (v1)*, Phase 1 no-op test; ADR-036 | `AbstractSQLDatabaseAccessor.networkStateBinding` (default `null`); `NetworkStateBinding` marker | `ReqRtpNet002NetworkDisabledNoOpTest` (3 tests: default-null, no network-named threads on construction, setter plumbing) |
| REQ-RTP-NET-003 | Single distribution artifact (backend / proxy role auto-select) | MULTI_SERVER_PLAN.md � *Intended Usage & Deployment Model*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-004 | Safety preservation across the network (S-001�S-006 end-to-end) | MULTI_SERVER_PLAN.md � *Coordinate Resolution Timing*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-005 | Authoritative world state on backends | MULTI_SERVER_PLAN.md � *Architecture Overview*, *Non-Goals (v1)*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-006 | Configurable network messaging (extends REQ-RTP-F-013) | MULTI_SERVER_PLAN.md � *Network Wait Queue*, *Reservation Tokens*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-007 | Non-blocking network I/O (extends REQ-RTP-F-008, REQ-RTP-S-005) | MULTI_SERVER_PLAN.md � *Backend Telemetry Publication*, *Risk & Pitfall Inventory*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-008 | Cross-network fairness (UUID-keyed wait queue, bypass permission semantics) | MULTI_SERVER_PLAN.md � *Network Wait Queue*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-009 | Authenticated, versioned inter-server data relay | MULTI_SERVER_PLAN.md � *Sufficiency Audit*, *Risk & Pitfall Inventory*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-010 | Proxy load-balancing policy (configurable, with disable option) | MULTI_SERVER_PLAN.md � *Load-Balancing Heuristics*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-011 | Reservation token deterministic expiry; no orphaned allocations | MULTI_SERVER_PLAN.md � *Reservation Tokens � Lifecycle ownership matrix*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-012 | Exactly-once reservation claim across the network | MULTI_SERVER_PLAN.md � *Reservation Tokens � Lifecycle ownership matrix*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-013 | Multi-flavour persistence compatibility (any shipped SQL accessor) | MULTI_SERVER_PLAN.md *Storage - Reuse `AbstractSQLDatabaseAccessor`*; ADR-036 §D3 | `AbstractSQLDatabaseAccessor.networkStateBinding` slot is inherited by every concrete accessor (`H2`, `SQLite`, `MySQL`, `PostgreSQL`) | (covered transitively by accessor tests; binding plumbing exercised by `ReqRtpNet002NetworkDisabledNoOpTest`) |
| REQ-RTP-NET-014 | Multi-proxy concurrency and reanimation (no singleton proxy assumption) | MULTI_SERVER_PLAN.md � *Multi-Proxy Deployment*; ADR-036 | � (unimplemented) | � |
| REQ-RTP-NET-015 | Shared network waitlist for cross-server `/rtp` (parks unservable enrolments, per-player point-remove, batch drain sized by per-backend `networkKeptCount`) | `rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch`; `CHECKLIST-network-waitlist.md` | `io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist` (SPI); `InMemoryNetworkWaitlist` (reference impl, Slice 1) | `InMemoryNetworkWaitlistTest` (15/15: lifecycle, idempotency, duplicate-player rejection, full-capacity rejection, FIFO drain, per-backend cap, global cap, point-remove, TTL reap) |

---

## rtp-proxy Requirements (umbrella module)

All rows below are **unimplemented**. Umbrella: [ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md) (Accepted). Subproject ADRs: [`platforms/rtp-proxy/docs/adr/`](../../platforms/rtp-proxy/docs/adr/).

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-PROXY-001 | Adapter SPI Conformance | ADR-036 §7; `platforms/rtp-proxy/REQUIREMENTS.md` | — (unimplemented) | — |
| REQ-RTP-PROXY-002 | No World State | ADR-036 §2 Non-Goals; `platforms/rtp-proxy/REQUIREMENTS.md` | — (unimplemented) | — |
| REQ-RTP-PROXY-003 | Non-Blocking Event Loop | ADR-036 §7; `platforms/rtp-proxy/REQUIREMENTS.md` | — (unimplemented) | — |
| REQ-RTP-PROXY-004 | Reservation Claim Idempotency | ADR-036 §5; `platforms/rtp-proxy/REQUIREMENTS.md` | `InMemoryNetworkStateBinding` (reference) | `InMemoryNetworkStateBindingTest` (claim atomicity / per-player idempotency) |
| REQ-RTP-PROXY-005 | Proxy Heartbeat Publication | ADR-036 §5; `platforms/rtp-proxy/REQUIREMENTS.md` | — (unimplemented) | — |
| REQ-RTP-PROXY-006 | Configurable Messaging | ADR-036 §3 Locked-In; `platforms/rtp-proxy/REQUIREMENTS.md` | — (unimplemented) | — |
| REQ-RTP-PROXY-007 | Authenticated Transport | ADR-036 §3 D4; `rtp-proxy-ADR-010` (Proposed) | — (unimplemented) | — |
| REQ-RTP-PROXY-008 | Disabled-Mode No-Op | ADR-036 §1 (REQ-RTP-NET-002 release gate) | — (unimplemented) | — |
| REQ-RTP-PROXY-009 | Single-Artifact Activation | ADR-036 §7; `rtp-fabric-ADR-002` precedent | — (unimplemented) | — |
| REQ-RTP-PROXY-010 | Adapter Isolation | ADR-036 §7 | — (unimplemented) | — |
| REQ-RTP-PROXY-011 | Bounded Tab-Completion Cost | ADR-036 §3 Locked-In (Brigadier reuse) | — (unimplemented) | — |

---

## rtp-proxy-common Requirements

All rows below are **unimplemented**. Phase 1 subproject ADRs `rtp-proxy-ADR-001..004` are Accepted (2026-05-14).

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-PROXY-COMMON-001 | Host-Independent SPI | `rtp-proxy-ADR-001-spi-shape` (Accepted) | `io.github.dailystruggle.rtp.proxy.common.spi.*` (5 SPI ifaces + 8 records/enums) | `NoVendorImportsTest` |
| REQ-RTP-PROXY-COMMON-002 | Pure-Function Selector | `rtp-proxy-ADR-004-weighted-average-selector` (Accepted) | `WeightedAverageBackendSelector` | `WeightedAverageBackendSelectorTest` (purity, lowest-score wins, tie-break) |
| REQ-RTP-PROXY-COMMON-003 | Snapshot Freshness Filter | `rtp-proxy-ADR-004` §scoring; ADR-036 §6 | `WeightedAverageBackendSelector#qualifies` | `WeightedAverageBackendSelectorTest` (stale filter, all-stale ⇒ empty) |
| REQ-RTP-PROXY-COMMON-004 | Capped Retry Chain | ADR-036 §6 Fallback chain | — (unimplemented) | — |
| REQ-RTP-PROXY-COMMON-005 | Trigger Source Plurality | ADR-036 §3 D1; MULTI_SERVER_PLAN.md Phase 1 item 1 (amended 2026-05-18; promoted to `rtp-api` 2026-05-19) | `io.github.dailystruggle.rtp.api.network.RtpTriggerSource` (producer-side abstraction in `rtp-api` with `Kind`/`Trigger` and `start`/`stop` lifecycle); `io.github.dailystruggle.rtp.proxy.common.trigger.CommandTriggerSource` (Phase 2c-β / Phase 2d concrete producer); consumer-side `RtpDispatcher` lives in `rtp-proxy-common/spi/` | `CommandTriggerSourceTest` (6/6: lifecycle, fire-before-start, double-start rejection, stop idempotency, null guard) |
| REQ-RTP-PROXY-COMMON-006 | Single Config Schema | `rtp-proxy-ADR-002-network-yml-schema` (Accepted) | — (unimplemented) | — |
| REQ-RTP-PROXY-COMMON-007 | Transport Binding Pluggability | `rtp-proxy-ADR-003-in-memory-binding` (Accepted); `-005`/`-007`/`-009` (Proposed) | `NetworkTransport` SPI + `InMemoryNetworkStateBinding` (reference) | `InMemoryNetworkStateBindingTest` (snapshot, subscribe, lifecycle) |
| REQ-RTP-PROXY-COMMON-008 | Hot-Spot Counter Locality | ADR-036 §5 Local state is advisory | — (unimplemented) | — |

---

## rtp-proxy-velocity Requirements

Subproject ADR `rtp-proxy-ADR-006-velocity-bootstrap` Accepted 2026-05-18. REQ-RTP-PROXY-VELOCITY-001 is implemented (Phase 2a no-op shell); the remaining rows are unimplemented and land in Phase 2b - 2f.

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-PROXY-VELOCITY-001 | Velocity Runtime | `rtp-proxy-ADR-006` (Accepted 2026-05-18) | `platforms/rtp-proxy/rtp-proxy-velocity/src/main/java/io/github/dailystruggle/rtp/proxy/velocity/RtpVelocityPlugin.java` (Phase 2a no-op shell) | `platforms/rtp-proxy/rtp-proxy-velocity/src/test/java/io/github/dailystruggle/rtp/proxy/velocity/ReqRtpProxyVelocity001SmokeTest.java` |
| REQ-RTP-PROXY-VELOCITY-002 | Brigadier Command Hosting | `commands-api-ADR-001`; ADR-036 §3 Locked-In; MULTI_SERVER_PLAN.md *Phase 2d* (2026-05-19) | `RtpVelocityPlugin#registerRtpCommand` (Velocity `BrigadierCommand` for `/rtp` with optional `<world>` literal; player-only; fires `CommandTriggerSource`); `RtpVelocityPlugin#onCommandTrigger` (selector -> `transport.claim` -> `player.createConnectionRequest`, with `ServerPreConnectEvent` redeeming at the connect boundary) | `RtpVelocityPluginPhase2dTest` (3/3: `registerRtpCommand` shape, `onCommandTrigger` shape, test-accessor presence); behavioural pipeline covered indirectly by `RtpVelocityPluginPhase2cTest` + `CommandTriggerSourceTest` |
| REQ-RTP-PROXY-VELOCITY-003 | Server Rewrite via `ServerPreConnectEvent` | `rtp-proxy-ADR-006`; MULTI_SERVER_PLAN.md *Coordinate Resolution Timing*, *Phase 2 - Velocity adapter* (Phase 2c-α 2026-05-19) | `RtpVelocityPlugin#onServerPreConnect` (rewrites target via `NetworkTransport#findReservation`, transitions `CLAIMED -> CONSUMED`, S-004 fall-through on miss/expiry/unknown-server); `NetworkTransport#findReservation` SPI default + `InMemoryNetworkStateBinding`/`SqlNetworkStateBinding` overrides | `RtpVelocityPluginPhase2cTest` (4/4: disabled-no-op, no-reservation-fallthrough, active-reservation-rewrites, unknown-target-fallthrough); `SqlNetworkStateBindingH2Test#findReservationCrossesInstances` |
| REQ-RTP-PROXY-VELOCITY-004 | Player Session Continuity | ADR-036 §1 Headline Goals | — (unimplemented) | — |
| REQ-RTP-PROXY-VELOCITY-005 | Tab-Completion Routing | REQ-RTP-PROXY-011; `commands-api-ADR-001` | — (unimplemented) | — |
| REQ-RTP-PROXY-VELOCITY-006 | Telemetry Scheduling | ADR-036 §5 Proxy heartbeat | — (unimplemented) | — |
| REQ-RTP-PROXY-VELOCITY-007 | Plugin Identity and Version Coupling | REQ-RTP-NET-009 schemaVersion negotiation | — (unimplemented) | — |

---

## rtp-proxy-bungee Requirements

All rows below are **unimplemented**. Subproject ADR `rtp-proxy-ADR-008-bungee-bootstrap` (Proposed; Phase 3).

| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |
|---|---|---|---|---|
| REQ-RTP-PROXY-BUNGEE-001 | BungeeCord and Waterfall Runtime | `rtp-proxy-ADR-008` (Proposed) | — (unimplemented) | — |
| REQ-RTP-PROXY-BUNGEE-002 | Server Rewrite via `ServerConnectEvent` | `rtp-proxy-ADR-008` | — (unimplemented) | — |
| REQ-RTP-PROXY-BUNGEE-003 | No Brigadier Dependency | `commands-api-ADR-001` (non-Brigadier path) | — (unimplemented) | — |
| REQ-RTP-PROXY-BUNGEE-004 | Scheduler Discipline | ADR-036 §7 Adapter isolation | — (unimplemented) | — |
| REQ-RTP-PROXY-BUNGEE-005 | Plugin-Message Transport Eligibility | ADR-036 §3 D2 (dev-only) | — (unimplemented) | — |
| REQ-RTP-PROXY-BUNGEE-006 | Tab-Completion Parity | REQ-RTP-PROXY-011 | — (unimplemented) | — |
| REQ-RTP-PROXY-BUNGEE-007 | Fork Compatibility Statement | `rtp-proxy-ADR-008` (Proposed) | — (unimplemented) | — |

---

## Coverage Summary

| Module | Total Reqs | Automated Test Coverage |
|---|---|---|
| Root / System | 24 | 12 (REQ-RTP-F-001 `SLATest`+`RegionPipelineTest`, REQ-RTP-F-006/007 `RegionPipelineTest`, REQ-RTP-F-008, REQ-RTP-F-012 `ScanCmdTest`+`ScanTaskProcessingTest`, REQ-RTP-F-013 `ConfigParserLanguageTest`, REQ-RTP-NF-002, REQ-RTP-NF-003 via `RTPArchitectureTest`, REQ-RTP-SYS-001 via build, REQ-RTP-S-004 `FailureModeTest`+`RegionPipelineTest`, REQ-RTP-S-005, REQ-RTP-S-006 `RTPAPIGuardTest`) |
| rtp-api | 10 | 4 (REQ-API-NF-002, REQ-API-ARCH-002, REQ-API-ARCH-003 `RTPAPIGuardTest`, REQ-API-ARCH-004) |
| rtp-core | 20 | 13 (REQ-CORE-F-001 `FailureModeTest`+`RegionPipelineTest`, REQ-CORE-F-003–005, REQ-CORE-F-009 `BacklogLocationBufferTest`+`WorldBacklogBinIndexTest`, REQ-CORE-ARCH-001–002, REQ-CORE-ARCH-009–010, REQ-CORE-NF-001 `MemoryShapeShutdownTest`+`CachedLocationRoundTripTest`; REQ-CORE-F-003/004 also covered end-to-end by `RegionPipelineTest`) |
| rtp-bukkit | 9 | 4 (REQ-SPIGOT-F-001, REQ-SPIGOT-ARCH-001/005 via `ChunkTicketLifecycleTest`, REQ-SPIGOT-ARCH-003/004 via `BukkitSchedulerImplTest`) |
| rtp-paper | 9 | 5 (REQ-PAPER-F-002 via architecture rule, REQ-PAPER-F-003 and REQ-PAPER-ARCH-003 via `ServerAccessorImplTest`, REQ-PAPER-ARCH-001/005 via `ChunkTicketLifecycleTest`) |
| rtp-folia | 14 | 1 (REQ-FOLIA-F-002 via architecture rule) |
| rtp-fabric | 1 | 1 (REQ-FABRIC-F-011 via `ReqFabricAdr023HasPlayedBeforeTest`) |
| Network / Proxy | 14 | 0 (subsystem unimplemented; umbrella `ADR-036` Accepted, implementation pending) |
| rtp-proxy | 11 | 1 (REQ-RTP-PROXY-004 via `InMemoryNetworkStateBindingTest#concurrentClaimsAreIdempotent`) |
| rtp-proxy-common | 8 | 4 (REQ-RTP-PROXY-COMMON-001 via `NoVendorImportsTest`; -002, -003 via `WeightedAverageBackendSelectorTest`; -007 via `InMemoryNetworkStateBindingTest`) |
| rtp-proxy-velocity | 7 | 0 (Phase 2 — pending `rtp-proxy-ADR-005`/`-006`) |
| rtp-proxy-bungee | 7 | 0 (Phase 3 — pending `rtp-proxy-ADR-008`) |
| **Total** | **123** | **~49** |

> **Deterministic RNG seam:** `MemoryShape.setRng(Random)`, `LocationGenerator.setRng(Random)`, and `RTPCmd.setRng(Random)` allow any test to inject a seeded `java.util.Random` and eliminate RNG as a source of flakiness. `DeterministicShapeTest` (12 tests) exercises this seam for `Circle`, `Square`, and `Rectangle`. The biome-recall path in `LocationGenerator` uses the same seam.

> **Real-region pipeline tests (`RegionPipelineTest`, 12 tests):** Exercises the full `LocationGenerator.getLocation(Region, biomeNames)` pipeline using actual `Region`/`MockRTPWorld`/`MockRTPChunk` mock components — no `MockLocationGenerator` stub. Covers: queue promotion (`unkeptLocations` → `keptLocations`), `hasLocation` state transitions, queue-length APIs, biome filter acceptance (PLAINS) and rejection (DEEP_OCEAN), coords-inside-shape bounds assertion, and end-to-end determinism (same/different seed). `RTPTestSetup.install()` now calls `Configs.reloadConfigs()` so all core parsers (`SafetyKeys`, `PerformanceKeys`, `LoggingKeys`) are available to every test that needs them. `MockRTPChunk` (new testFixture) provides an all-safe, all-air chunk; `MockRTPWorld` now encodes chunk coords into the cache key so `getCachedChunk()` returns the correct chunk at any (cx, cz). `MockRTPServerAccessor.getWorldBorder()` now returns an always-inside `WorldBorder` and exposes `setLocationGenerator()` to swap in the real pipeline.

> **Gap:** The adapter modules (spigot, paper, folia) have low but growing automated test coverage.
> MockBukkit is now integrated into `rtp-bukkit-common` and `rtp-paper-v1_20_R1`; the mock support classes have been promoted to a shared `java-test-fixtures` source set in `rtp-core`.
> The remaining highest-value automation steps are:
> 1. ~~Automate chunk ticket lifecycle (REQ-SPIGOT-ARCH-001/005, REQ-PAPER-ARCH-001/005)~~ **Done** — `ChunkTicketLifecycleTest` (2 tests) uses `TrackedMockWorld` to assert tickets are held during validation and released (including on exception) via `ChunkReservation` try-with-resources.
> 2. Extend MockBukkit coverage to `rtp-bukkit-v1_20_R1`, `rtp-paper-v1_21_R1`, and `rtp-paper-v26_1_R1` using the same `testFixtures` pattern.
> 3. Folia mock infrastructure and economy delegation (REQ-FOLIA-ARCH-007–009) require a dedicated Folia mock server or a Folia-compatible MockBukkit fork.

# RTP Architecture Catalog & Diagram Directory

This directory contains the canonical visual architecture specifications, state-machine diagrams, and lifecycle flows for the LeafRTP engine. Each document models an end-to-end subsystem or critical invariant using client-rendered Mermaid diagrams accompanied by threading rules, failure attribution vocabulary, and requirement traceability.

For a narrative walkthrough of how these diagrams connect to diagnose runtime issues, see [`docs/dev/CODE_TOUR.md`](../dev/CODE_TOUR.md). For module import boundaries and package separation, see [`docs/dev/ARCHITECTURE.md`](../dev/ARCHITECTURE.md).

---

## Architectural Diagram Catalog

| # | Document | Primary Scope | Entry Point / Core Classes | Governing ADRs | S-00x Rules |
|---|----------|---------------|----------------------------|----------------|-------------|
| **01** | [`01-teleport-execution-pipeline.md`](01-teleport-execution-pipeline.md) | End-to-end single `/rtp` execution (Setup → Load → Teleport → Cleanup) | `TeleportPipelineTask`, `LocationGenerator` | [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md), [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-110](../adr/ADR-110-purpose-gated-live-loads.md), [ADR-112](../adr/ADR-112-per-region-work-division-on-folia.md), [ADR-114](../adr/ADR-114-cost-governed-on-demand-chunk-reads.md) | S-001, S-002, S-004, S-005 |
| **02** | [`02-budgeted-cache-generator.md`](02-budgeted-cache-generator.md) | Background speculative queue refill (`keptLocations`) and budget pacing | `SelectionAPI.compute()`, `RegionQueueManager`, `RegionCacheTask` | [ADR-010](../adr/ADR-010-budgeted-cache-refill-and-dynamic-pregen.md), [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-016](../adr/ADR-016-anvil-subsystem.md), [ADR-110](../adr/ADR-110-purpose-gated-live-loads.md) | S-002, S-005 |
| **03** | [`03-chunk-ticket-lifecycle.md`](03-chunk-ticket-lifecycle.md) | Chunk reservation lifecycle, ticket registration, and guaranteed teardown | `ChunkReservation`, `ChunkSet`, `MemoryTracker` | [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-112](../adr/ADR-112-per-region-work-division-on-folia.md) | S-002, S-005 |
| **04** | [`04-active-gc-sweep.md`](04-active-gc-sweep.md) | Periodic background audit and reclamation of stale tasks and orphaned tickets | `ActiveGCSweepTask`, `MemoryTracker` | [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md) | S-002 |
| **05** | [`05-scan-task-crawler.md`](05-scan-task-crawler.md) | Long-lived admin `/rtp scan` crawler, rate throttling, and `.scan` checkpoints | `ScanTask`, `ScanTaskProcessing`, `MemoryShape` | [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md), [ADR-016](../adr/ADR-016-anvil-subsystem.md) | S-004, S-005 |
| **06** | [`06-plugin-setup-lifecycle.md`](06-plugin-setup-lifecycle.md) | Plugin bootstrap ordering, database initialization, and adapter detection | `RTPBukkitPlugin.onEnable()`, `Configs.reload()` | [ADR-020](../adr/ADR-020-language-bootstrap-and-locale-aware-configparser.md), [ADR-024](../adr/ADR-024-driver-isolation-and-shaded-relocation.md) | S-006 |
| **07** | [`07-rtp-command-region-selection.md`](07-rtp-command-region-selection.md) | Command parsing, world/region overrides, permission evaluation, and unqueued branch | `SelectionAPI.getRegion()`, `BukkitBaseRTPCmd` | [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md), [ADR-009](../adr/ADR-009-configurable-spatial-distributions.md), [ADR-110](../adr/ADR-110-purpose-gated-live-loads.md), [ADR-114](../adr/ADR-114-cost-governed-on-demand-chunk-reads.md) | S-004, S-007 |
| **08** | [`08-location-selection-per-attempt.md`](08-location-selection-per-attempt.md) | Core candidate selection attempt: shape → probe-first → chunk → vert → biome → safety | `PregenTask.runAttempt()`, `LocationGenerator` | [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md), [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-016](../adr/ADR-016-anvil-subsystem.md), [ADR-109](../adr/ADR-109-measured-cost-probe-first-gate.md), [ADR-110](../adr/ADR-110-purpose-gated-live-loads.md), [ADR-112](../adr/ADR-112-per-region-work-division-on-folia.md), [ADR-114](../adr/ADR-114-cost-governed-on-demand-chunk-reads.md) | S-001, S-003, S-004, S-005 |
| **09** | [`09-configuration-load-and-reload.md`](09-configuration-load-and-reload.md) | Configuration loading, locale bootstrap, atomic parser swap, and dormant regions | `Configs.reloadAction()`, `ConfigParser`, `LanguageBootstrap` | [ADR-020](../adr/ADR-020-language-bootstrap-and-locale-aware-configparser.md), [ADR-041](../adr/ADR-041-config-command-and-save-implementation.md) | S-007 |
| **10** | [`10-shutdown-and-flush-lifecycle.md`](10-shutdown-and-flush-lifecycle.md) | Shutdown flush ordering: task drainage → DB commit → region teardown → ticket release | `RTP.stop()`, `RTPBukkitPlugin.onDisable()`, `DatabaseAccessor` | [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-024](../adr/ADR-024-driver-isolation-and-shaded-relocation.md) | S-002, S-004 |
| **11** | [`11-configuration-write-and-persist.md`](11-configuration-write-and-persist.md) | `/rtp config set/add/remove` persistence pipeline, atomic rename, and rollback | `ConfigWritePipeline`, `ConfigYamlDumper` | [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md), [ADR-041](../adr/ADR-041-config-command-and-save-implementation.md) | S-007 |
| **12** | [`12-network-model.md`](12-network-model.md) | Multi-server and multi-proxy network protocol, packet sequence, and reservation tokens | `RTPNetworkManager`, `ServerTokenManager`, `ProxyProtocolBridge` | [ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md) | S-002, S-004 |
| **13** | [`13-dual-layer-shape-architecture-and-l3-selection.md`](13-dual-layer-shape-architecture-and-l3-selection.md) | Dual-layer shape coordinate mapping, Chebyshev tiles, Hilbert curve, and L3 backlog | `CircleOptimizedDualLayer`, `SquareOptimizedDualLayer`, `SegmentedKeyRunTable` | [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md), [ADR-028](../adr/ADR-028-l3-backlog-cache.md), [ADR-034](../adr/ADR-034-memory-shape-catalog.md), [ADR-085](../adr/ADR-085-spiral-addressed-hilbert-key-space.md), [ADR-088](../adr/ADR-088-configurable-downsampling-stride-filter.md) | S-001, S-004 |
| **14** | [`14-group-placement-anchor-flow.md`](14-group-placement-anchor-flow.md) | Multi-participant action placement, group anchor draw, and separated slot allocation | `GroupPlacementDispatcher`, `SubspaceAnchorResolver`, `ActionManager` | [ADR-093](../adr/ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md), [ADR-095](../adr/ADR-095-subspace-anchor-providers-and-near-teleport-primitives.md), [ADR-097](../adr/ADR-097-per-action-pre-validated-caches-and-bounded-retries.md) | S-002, S-004 |
| **15** | [`15-cost-governed-chunk-reads-and-native-admission.md`](15-cost-governed-chunk-reads-and-native-admission.md) | Purpose-gated chunk resolution, thread-weighted deduplication, and in-flight admission | `ImmediateLoadGovernor`, `LiveLoadGate`, `ProbeFirstGovernor`, `AnvilRegionOccupancyCache` | [ADR-016](../adr/ADR-016-anvil-subsystem.md), [ADR-067](../adr/ADR-067-adaptive-scan-rate-and-mca-header-generation-check.md), [ADR-109](../adr/ADR-109-measured-cost-probe-first-gate.md), [ADR-110](../adr/ADR-110-purpose-gated-live-loads.md), [ADR-112](../adr/ADR-112-per-region-work-division-on-folia.md), [ADR-114](../adr/ADR-114-cost-governed-on-demand-chunk-reads.md) | S-001, S-002, S-004, S-005 |
| **16** | [`16-package-dag-and-decision-preservation-graph.md`](16-package-dag-and-decision-preservation-graph.md) | Core package hierarchy, 4-tier DAG architecture, and decision traceability hypergraph | `RTPArchitectureTest`, `RTP`, `MemoryTracker`, `CoreCommandTreeBuilder` | [ADR-000](../adr/ADR-000-development-workflow.md), [ADR-008](../adr/ADR-008-memory-tracker-active-gc.md), [ADR-094](../adr/ADR-094-quality-engineering-gates.md), [ADR-115](../adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md) | S-001..S-007 |

---

## Subsystem Functional Map

The diagrams form an interconnected state machine covering distinct architectural concerns:

### 1. Teleport Orchestration & Command Dispatch
- **[`07`](07-rtp-command-region-selection.md) `/rtp` Command Region Selection:** Evaluates player arguments, world/region permissions, override loops, and forks between hot-cache pop, public queue wait, or ad-hoc unqueued search (`LoadPurpose.IMMEDIATE`).
- **[`01`](01-teleport-execution-pipeline.md) Teleport Execution Pipeline:** Orchestrates the complete player lifecycle (Setup → Load → Teleport → Cleanup), binding chunk reservations and managing regional handoffs.
- **[`14`](14-group-placement-anchor-flow.md) Group Placement Flow:** Manages multi-player challenge, scatter, and arena placement by drawing a single anchor and distributing safe separated slots.

### 2. Candidate Selection & Algorithmic Geometry
- **[`08`](08-location-selection-per-attempt.md) Location Selection Per Attempt:** The decision core. Samples coordinates, evaluates world borders, passes probe-first gating, resolves chunks, adjusts elevation, filters biomes, and verifies environmental safety.
- **[`13`](13-dual-layer-shape-architecture-and-l3-selection.md) Dual-Layer Shape Architecture & L3 Backlog:** Eliminates random rerolls using coarse Chebyshev macro-tiles, fine Hilbert space-filling curves, dyadic downsampling, and segmented run tables.

### 3. Chunk Resolution, Deduplication & Lifecycle Governance
- **[`15`](15-cost-governed-chunk-reads-and-native-admission.md) Cost-Governed Chunk Reads & Native Admission:** Implements three-tier resolution (Resident Cache, Governed Deduplication, Cost-Governed Native Load Admission), zero-decode MCA occupancy checks, and thread-weighted cost balancing ($P_{\text{reject}} > \frac{C_{\text{file, async}}}{C_{\text{native, async}} + W_{\text{tick}}(\text{MSPT}) \cdot C_{\text{native, sync}}}$).
- **[`02`](02-budgeted-cache-generator.md) Budgeted Cache Generator:** Manages periodic background queue refill pulses (`LoadPurpose.SPECULATIVE`), constrained by `LiveLoadGate` and `HeapPressureMonitor`.
- **[`03`](03-chunk-ticket-lifecycle.md) Chunk Ticket Lifecycle:** Tracks allocation and guaranteed release of platform chunk tickets (`ChunkReservation`) through `MemoryTracker`.
- **[`04`](04-active-gc-sweep.md) Active GC Sweep:** Provides the active safety net that catches and reclaims stalled tasks, expired tickets, and orphaned native tickets.

### 4. Configuration, Persistence & Lifecycle Ordering
- **[`06`](06-plugin-setup-lifecycle.md) Plugin Setup Lifecycle:** Governs `onEnable` sequencing, database connection pools, config initialization, and platform adapter binding.
- **[`09`](09-configuration-load-and-reload.md) Configuration Load and Reload:** Manages `/rtp reload` mechanics, language bootstrapping, atomic parser swaps, and dormant region rebinding.
- **[`10`](10-shutdown-and-flush-lifecycle.md) Shutdown and Flush Lifecycle:** Enforces strict four-phase shutdown ordering to prevent data loss or leaked chunk tickets on `/stop` and `/reload`.
- **[`11`](11-configuration-write-and-persist.md) Configuration Write and Persist:** Manages transactional `/rtp config` mutations with atomic temporary file swapping and rollback.

### 5. Multi-Server Networking & Admin Tools
- **[`12`](12-network-model.md) Network Model:** Specifies multi-server proxy communication, heartbeat synchronization, reservation token lifecycle, and packet flows.
- **[`05`](05-scan-task-crawler.md) Scan Task Crawler:** Drives asynchronous background pre-exploration (`/rtp scan`) to populate persistent spatial memory with bounded tick budgets.

### 6. Package Architecture, Modularity & Decision Traceability
- **[`16`](16-package-dag-and-decision-preservation-graph.md) Core Package Architecture & Decision Traceability Map:** Visual specification of the 4-tier package DAG in `rtp-core`, acyclic dependency rules (0.0% SonarQube package tangles), decoupled structural seams, and class-to-ADR decision traceability.

---

## Absolute Prohibition Requirements (S-00x) Reference

Every architectural flow is designed to enforce the non-negotiable prohibition requirements codified in [`docs/dev/REQUIREMENTS.md section 3`](../dev/REQUIREMENTS.md):

- **S-001 (Destination Safety):** Verified authoritatively in [`08`](08-location-selection-per-attempt.md) and [`15`](15-cost-governed-chunk-reads-and-native-admission.md) prior to player teleportation. No secondary checks in adapters.
- **S-002 (Chunk Tickets):** Framed in [`03`](03-chunk-ticket-lifecycle.md) and audited in [`04`](04-active-gc-sweep.md) and [`10`](10-shutdown-and-flush-lifecycle.md). Zero permanent force-loaded chunks; reservations closed on every exit path.
- **S-003 (Claim Protection):** Executed via asynchronous verifier chains in [`08`](08-location-selection-per-attempt.md). Zero teleports into protected land.
- **S-004 (Failure Visibility):** Enforced across [`01`](01-teleport-execution-pipeline.md), [`07`](07-rtp-command-region-selection.md), [`08`](08-location-selection-per-attempt.md), and [`15`](15-cost-governed-chunk-reads-and-native-admission.md). Futures always complete; errors attribute to `FailTypes` vocabulary without silent suppression.
- **S-005 (Async I/O):** Zero synchronous chunk I/O on tick threads. Off-thread region reads (`AnvilIoPool`), async chunk futures, and regional handoffs are modeled in [`01`](01-teleport-execution-pipeline.md), [`08`](08-location-selection-per-attempt.md), and [`15`](15-cost-governed-chunk-reads-and-native-admission.md).
- **S-006 (Fail-Closed Contract):** Initialized in [`06`](06-plugin-setup-lifecycle.md). Public APIs throw `IllegalStateException` if invoked pre-initialization.
- **S-007 (Configurable Messages):** Handled in [`07`](07-rtp-command-region-selection.md), [`09`](09-configuration-load-and-reload.md), and [`11`](11-configuration-write-and-persist.md). All failure and busy notifications originate from `messages.yml`.

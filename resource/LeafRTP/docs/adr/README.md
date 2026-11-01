# LeafRTP Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) scoped to the `LeafRTP` plugin (`../..`). Repo-wide workflow and directory-structure ADRs live in [`docs/adr/`](../../../../docs/adr/README.md), and standalone library/platform module ADRs live under their respective `api/*/docs/adr/` and `platforms/*/docs/adr/` directories.

## Index

| # | Title | Status |
|---|-------|--------|
| [leafrtp-ADR-001](leafrtp-leafrtp-ADR-001-archimedean-spiral-1d-mapping.md) | Archimedean Spiral 1D Mapping for Location Selection | Accepted |
| [leafrtp-ADR-002](leafrtp-leafrtp-ADR-002-h2-sqlite-over-flat-file-cache.md) | H2/SQLite for Spatial Memory Persistence Over Flat-File Cache | Accepted |
| [leafrtp-ADR-003](leafrtp-leafrtp-ADR-003-rtp-plugin-bridge-module.md) | `rtp-plugin` as a Separate Bridge Module from `rtp-core` | Accepted |
| [leafrtp-ADR-004](leafrtp-rtp-folia-ADR-001-async-queue-pre-generation.md) | Async Queue Pre-Generation Over On-Demand Async Selection | Accepted |
| [leafrtp-ADR-005](leafrtp-rtp-paper-ADR-001-per-user-isolated-queues.md) | Per-User Isolated Queues Alongside the Global Queue | Accepted (operational details superseded by leafrtp-ADR-028) |
| [leafrtp-ADR-006](leafrtp-leafrtp-ADR-004-memory-tracker-active-gc.md) | `MemoryTracker` as Active Task GC with `WeakReference` Deallocation | Accepted |
| [leafrtp-ADR-007](leafrtp-leafrtp-ADR-005-configurable-spatial-distributions.md) | Configurable Spatial Distributions: Flat, Normal, Exponential | Accepted |
| [leafrtp-ADR-008](leafrtp-leafrtp-ADR-006-rtp-api-separate-module.md) | `rtp-api` as a Separately Published Addon Interface | Accepted |
| [leafrtp-ADR-009](leafrtp-leafrtp-ADR-007-chunk-reservation-abstraction.md) | `ChunkReservation` as an Internal Chunk Ticket Abstraction | Accepted |
| [leafrtp-ADR-010](leafrtp-ADR-010-addons-as-external-gradle-projects.md) | Addons as External Gradle Projects Rather Than Built-In Optional Modules | Accepted (partially superseded by leafrtp-ADR-012) |
| [leafrtp-ADR-011](leafrtp-leafrtp-ADR-008-stale-chunk-guard-countbound-pipes.md) | Stale-Chunk Guard for Count-Bound Pipes | Accepted |
| [leafrtp-ADR-012](leafrtp-leafrtp-ADR-009-claim-plugin-integrations-folded-into-plugin.md) | Claim-Plugin Integrations Folded Into `rtp-plugin`; Example Addon Retained | Superseded by [leafrtp-ADR-051](leafrtp-leafrtp-ADR-034-claim-integrations-extracted-to-bundled-addon.md) |
| [leafrtp-ADR-013](leafrtp-leafrtp-ADR-010-language-bootstrap-and-locale-aware-configparser.md) | Language Bootstrap and Locale-Aware `ConfigParser` | Accepted |
| [leafrtp-ADR-014](leafrtp-ADR-014-shape-cache-key-seed-plus-config-hash.md) | Region Shape Cache Key: Seed + Canonical Config Hash | Accepted |
| [leafrtp-ADR-015](leafrtp-leafrtp-ADR-011-login-reserve-cache.md) | Login Reserve Cache for Join-Time RTP | Accepted |
| [leafrtp-ADR-016](leafrtp-anvil-api-ADR-001-rtp-lite-assembly-variant.md) | RTP Lite Assembly Variant | Superseded by [leafrtp-ADR-078](leafrtp-leafrtp-ADR-059-superseding-adr-024-sla-and-support-tier.md) |
| [leafrtp-ADR-017](leafrtp-tags-api-ADR-001-external-hook-api-surface.md) | External Hook API Surface | Accepted |
| [leafrtp-ADR-018](leafrtp-ADR-018-l3-backlog-cache.md) | L3 Backlog Cache (`backlogLocations`) | Accepted |
| [leafrtp-ADR-019](leafrtp-leafrtp-ADR-012-teleport-pipeline-latency-histogram.md) | Teleport Pipeline Latency Histogram: 256-Sample Wait-Free Ring, Never Reset | Accepted (extended by leafrtp-ADR-036) |
| [leafrtp-ADR-020](leafrtp-leafrtp-ADR-013-memory-shape-catalog.md) | Memory Shape Catalog and Polygon Shape | Accepted |
| [leafrtp-ADR-021](leafrtp-ADR-021-interactive-menus-book-first.md) | Interactive Menus via Written Book (Book-First, Chat Fallback; Optional User GUI via Addon) | Accepted (token-registry sections superseded by leafrtp-ADR-033) |
| [leafrtp-ADR-022](leafrtp-leafrtp-ADR-014-network-mode-multi-server-multi-proxy.md) | Network Mode: Multi-Server, Multi-Proxy RTP | Accepted |
| [leafrtp-ADR-023](leafrtp-leafrtp-ADR-015-harden-rtp-config-commands.md) | Harden RTP Config Commands (Prerequisite for `/rtpadmin` Setup Wizards) | Accepted |
| [leafrtp-ADR-024](leafrtp-leafrtp-ADR-016-rtpadmin-setup-wizards.md) | `/rtpadmin` Setup Wizards (Book-Menu Driven, Config-Transaction Backed) | Accepted |
| [leafrtp-ADR-025](leafrtp-yaml-api-ADR-001-rtpadmin-diagnostic-surfaces.md) | `/rtpadmin` Diagnostic Surfaces (Biome Map, Bad-Selection Heatmap, Metrics Readouts) | Accepted |
| [leafrtp-ADR-026](leafrtp-leafrtp-ADR-017-cross-backend-metric-time-series-publication.md) | Cross-Backend Metric Time-Series Publication via `MemoryTracker` Tier Promotion | Accepted |
| [leafrtp-ADR-027](leafrtp-ADR-027-config-command-and-save-implementation.md) | `/rtp config` Command and Save Mechanics: Implementation Strategy (companion to leafrtp-ADR-023) | Accepted |
| [leafrtp-ADR-028](leafrtp-leafrtp-ADR-018-personal-queue-permission-semantics.md) | `rtp.personalqueue` Permission Semantics: Bucket-Only Opt-In (supersedes operational details of leafrtp-ADR-005) | Accepted |
| [leafrtp-ADR-029](leafrtp-ADR-029-rtp-docs-menu-consumer.md) | `/rtp docs` Menu Consumer (Markdown -> `MenuModel`, converted at config load) | Withdrawn (superseded by [leafrtp-ADR-080](leafrtp-leafrtp-ADR-061-ephemeral-web-editor-and-packed-docs-integration.md)) |
| [leafrtp-ADR-030](leafrtp-ADR-030-declarative-chart-composition-bridge.md) | Declarative Chart Composition Bridge (`ChartSpec` + `ChartSpecResolver` + `MapDispatch`) | Accepted |
| [leafrtp-ADR-031](leafrtp-ADR-031-menu-builders-behind-server-accessor.md) | Move menu page builders into `rtp-api` behind `RTPServerAccessor` | Accepted |
| [leafrtp-ADR-032](leafrtp-leafrtp-ADR-019-network-mode-platform-neutral-lift.md) | Lift network-mode plumbing (`NetworkModeBootstrap` + 12 helpers) from `rtp-plugin/bukkit/network/` to `rtp-core` | Accepted |
| [leafrtp-ADR-033](leafrtp-rtp-neoforge-ADR-002-concrete-menu-commands-supersede-tokens.md) | Concrete Menu Commands Supersede Token Registries | Accepted |
| [leafrtp-ADR-034](leafrtp-leafrtp-ADR-020-two-tier-api-extension-model.md) | Two-Tier API Extension Model | Accepted |
| [leafrtp-ADR-035](leafrtp-leafrtp-ADR-021-outcome-metrics-and-cause-tagged-bad-locations.md) | Outcome Metrics and Cause-Tagged Bad Locations | Accepted |
| [leafrtp-ADR-036](leafrtp-leafrtp-ADR-022-pipeline-latency-percentiles-and-slow-teleport-audit.md) | Pipeline Latency Percentiles and Slow-Teleport Audit (extends leafrtp-ADR-019; `/rtp info` readout) | Accepted |
| [leafrtp-ADR-037](leafrtp-leafrtp-ADR-023-rtprunnable-self-scheduling-thread-routing.md) | `RTPRunnable` Self-Scheduling Thread Routing (`target`/`location` + `schedule()` entity/region/async dispatch) | Accepted |
| [leafrtp-ADR-038](leafrtp-leafrtp-ADR-024-pvp-combat-gate.md) | PvP Combat Gate | Accepted |
| [leafrtp-ADR-039](leafrtp-leafrtp-ADR-025-bare-rtp-root-action.md) | Pluggable bare-`/rtp` root action (enables optional user GUI via example addon) | Accepted |
| [leafrtp-ADR-040](leafrtp-leafrtp-ADR-026-platform-agnostic-addon-spi.md) | Platform-agnostic addon SPI (`RTPAddon` + `AddonRegistry` ServiceLoader discovery) | Accepted |
| [leafrtp-ADR-041](leafrtp-leafrtp-ADR-027-region-specific-schematic-paste.md) | Region-Specific Schematic (`.schem`) Paste at the Arrival Location (`SchematicPaster` SPI) | Accepted |
| [leafrtp-ADR-042](leafrtp-yaml-api-ADR-002-relative-ground-distance-safety-predicate.md) | Relative Ground-Distance Safety Predicate (`_groundDistance`) | Accepted |
| [leafrtp-ADR-043](leafrtp-leafrtp-ADR-028-emergency-platform-block-restoration-timeout.md) | Emergency-Platform Block-Restoration Timeout (`safety.yml` `platformRestoreSeconds`) | Accepted |
| [leafrtp-ADR-044](leafrtp-menu-api-ADR-001-open-core-dual-licensing.md) | Open-Core Dual Licensing | Superseded by [ADR-108](../../../../docs/adr/ADR-108-all-mit-licensing-pro-as-support.md) |
| [leafrtp-ADR-045](leafrtp-leafrtp-ADR-029-biome-probability-weighting.md) | Biome-Probability Weighting for Location Selection | Accepted |
| [leafrtp-ADR-046](leafrtp-maps-api-ADR-003-biome-first-menu-and-auto-region-by-biome.md) | Biome-First Menu with Auto-Region Selection by Biome Availability | Accepted |
| [leafrtp-ADR-047](leafrtp-leafrtp-ADR-030-world-override-region-and-world-menu.md) | World-Override Regions and the `/rtp` World Menu | Accepted |
| [leafrtp-ADR-048](leafrtp-leafrtp-ADR-031-foreign-config-importer.md) | Foreign Config Importer (`rtp config import <plugin>`) | Accepted |
| [leafrtp-ADR-049](leafrtp-leafrtp-ADR-032-adaptive-scan-rate-and-mca-header-generation-check.md) | Automatic PRESCAN, Adaptive Scan Rate Control, and `.mca`-Header Generation Check | Accepted |
| [leafrtp-ADR-050](leafrtp-leafrtp-ADR-033-cross-server-persisted-teleport-limits.md) | Cross-Server Persisted Teleport Limits | Accepted |
| [leafrtp-ADR-051](leafrtp-leafrtp-ADR-034-claim-integrations-extracted-to-bundled-addon.md) | Claim Integrations Extracted to Bundled Addon (`LeafRTPClaimAddon`) | Accepted (supersedes leafrtp-ADR-012) |
| [leafrtp-ADR-052](leafrtp-leafrtp-ADR-035-platform-neutral-rtp-command-root.md) | Platform-neutral `/rtp` command root (`CoreRtpRoot`) for Brigadier-bridged platforms | Accepted |
| [leafrtp-ADR-053](leafrtp-leafrtp-ADR-036-config-organization-and-discoverability.md) | Config Organization and Discoverability | Accepted (top-level layout superseded by leafrtp-ADR-058) |
| [leafrtp-ADR-054](leafrtp-leafrtp-ADR-037-teleport-view-distance-clamp-and-restore.md) | Teleport View-Distance Clamp and Steady Restore | Accepted |
| [leafrtp-ADR-055](leafrtp-leafrtp-ADR-038-config-default-inheritance-references.md) | Config Default Inheritance via `@<file>` References | Accepted |
| [leafrtp-ADR-056](leafrtp-leafrtp-ADR-039-operator-facing-throughput-and-cost-metrics.md) | Operator-Facing RTP Throughput and Cost Metrics | Accepted |
| [leafrtp-ADR-057](leafrtp-leafrtp-ADR-040-platform-neutral-player-move-event-spi.md) | Platform-Neutral Player-Move Event SPI | Accepted |
| [leafrtp-ADR-058](leafrtp-leafrtp-ADR-041-config-folder-consolidation.md) | Config Folder Consolidation | Accepted |
| [leafrtp-ADR-059](leafrtp-leafrtp-ADR-042-composable-cache-pipeline-stages.md) | Composable Cache Pipeline Stages, Domain Stage Nomenclature, and Dynamic Hot Quota Allocation | Accepted |
| [leafrtp-ADR-060](leafrtp-leafrtp-ADR-043-cause-based-ttl-and-staged-expiration.md) | Cause-Based TTL and Staged Expiration on Spatial Memory Segments | Accepted |
| [leafrtp-ADR-061](leafrtp-leafrtp-ADR-044-opt-in-simulation-benchmark-tier.md) | Opt-In Simulation Benchmark Tier | Accepted |
| [leafrtp-ADR-062](leafrtp-leafrtp-ADR-045-unified-blocked-biome-run-table.md) | Unified Blocked Biome Run Table in `MemoryShape` | Accepted |
| [leafrtp-ADR-063](leafrtp-leafrtp-ADR-046-cache-conscious-b-plus-tree-primitive-arrays.md) | Cache-Conscious B+ Tree of Primitive Array Pages for Spatial Run Memory | Not adopted - alternative considered |
| [leafrtp-ADR-064](leafrtp-yaml-api-ADR-003-two-tier-probabilistic-region-routing.md) | Two-Tier Hierarchical Cell Routing | Superseded by leafrtp-ADR-065, declined by leafrtp-ADR-066 |
| [leafrtp-ADR-065](leafrtp-leafrtp-ADR-047-layered-spiral-hilbert-rle-index.md) | Layered Learned-State Index: Coarse Spiral Directory over Per-Cell Hilbert RLE Blobs | Rejected - superseded by [leafrtp-ADR-066](leafrtp-leafrtp-ADR-048-spiral-addressed-hilbert-key-space.md) |
| [leafrtp-ADR-066](leafrtp-leafrtp-ADR-048-spiral-addressed-hilbert-key-space.md) | Spiral-Addressed Hilbert Key Space for Learned State | Accepted |
| [leafrtp-ADR-067](leafrtp-leafrtp-ADR-049-external-web-map-integration.md) | External Web Map Integration via `anvil-api`, `maps-api`, and Spatial Memory | Accepted |
| [leafrtp-ADR-068](leafrtp-leafrtp-ADR-050-adaptive-compute-governor.md) | Adaptive Compute Governor: Demand-Driven Backoff, Memory Backpressure, and Multi-Region Concurrency | Accepted |
| [leafrtp-ADR-069](leafrtp-leafrtp-ADR-051-configurable-downsampling-stride-filter.md) | Configurable Downsampling Stride Filter for Spatial Candidate Selection | Accepted |
| [leafrtp-ADR-070](leafrtp-leafrtp-ADR-052-universal-mapcanvas-bridge-biome-hazard-overlays-and-pipeline-gauges.md) | Universal `MapCanvas` Bridge, Biome-Hazard Composite Overlays, and Visual Pipeline Gauges | Accepted |
| [leafrtp-ADR-071](leafrtp-leafrtp-ADR-053-test-driver-and-synthetic-mca-architecture.md) | Test-Driver, Synthetic MCA Generator, and Dimension Benchmark Dataset | Accepted |
| [leafrtp-ADR-072](leafrtp-leafrtp-ADR-054-hybrid-bit-packed-and-16-bit-run-container-hazard-table.md) | Hybrid Bit-Packed and 16-Bit Run Container Hazard Table | Accepted |
| [leafrtp-ADR-073](leafrtp-leafrtp-ADR-055-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md) | Declarative Scripted Actions via Core Confinement, Subspace Placement, and Minecraft Command Lifecycle | Accepted |
| [leafrtp-ADR-074](leafrtp-leafrtp-ADR-056-subspace-anchor-providers-and-near-teleport-primitives.md) | Subspace Anchor Providers, Near-Teleport Primitives, and External Verifier Perimeter Extraction | Accepted |
| [leafrtp-ADR-075](leafrtp-leafrtp-ADR-057-per-action-pre-validated-caches-and-bounded-retries.md) | Per-Action Pre-Validated Caches and Bounded Subspace Placement Retries | Accepted |
| [leafrtp-ADR-076](leafrtp-leafrtp-ADR-058-declarative-command-parameters-permissions-and-defaults.md) | Declarative Command Parameters, Per-Parameter Permissions, and Authoritative Defaults | Accepted |
| [leafrtp-ADR-077](leafrtp-anvil-api-ADR-002-visual-region-creation-and-interactive-cartography-bridges.md) | Zero-Overhead Visual Region Creation via Ecosystem Selection Bridges and Cartography Models | Accepted |
| [leafrtp-ADR-078](leafrtp-leafrtp-ADR-059-superseding-adr-024-sla-and-support-tier.md) | Superseding leafrtp-ADR-016: Technical Parity and Pro as SLA & Support Tier | Accepted |
| [leafrtp-ADR-079](leafrtp-leafrtp-ADR-060-in-house-sql-connection-pool-and-hikaricp-retirement.md) | In-House SQL Connection Pool and Retirement of HikariCP | Accepted |
| [leafrtp-ADR-080](leafrtp-leafrtp-ADR-061-ephemeral-web-editor-and-packed-docs-integration.md) | Ephemeral Web Editor and In-Game Packed Docs Integration | Accepted |
| [leafrtp-ADR-081](leafrtp-leafrtp-ADR-062-intent-based-documentation-architecture-and-operator-routing.md) | Intent-Based Documentation Architecture, Operator-Centric Routing, and Documentation Lifecycle Refinement | Accepted |
| [leafrtp-ADR-082](leafrtp-leafrtp-ADR-063-shape-curve-helpers-and-signed-editor-channel.md) | Shape Curve Helpers, Curve-Space Run Layers, and the Signed Two-Way Editor Channel | Accepted |
| [leafrtp-ADR-083](leafrtp-leafrtp-ADR-064-addon-editor-extensions-and-protocol-negotiation.md) | Addon Editor Extensions: Shared Data, Addon Tabs and Protocol Negotiation | Accepted |
| [leafrtp-ADR-084](leafrtp-leafrtp-ADR-065-measured-cost-probe-first-gate.md) | Measured-Cost Probe-First Gate | Accepted |
| [leafrtp-ADR-085](leafrtp-leafrtp-ADR-066-purpose-gated-live-loads.md) | Purpose-Gated Live Loads | Accepted |
| [leafrtp-ADR-086](leafrtp-leafrtp-ADR-067-cost-governed-on-demand-chunk-reads.md) | Cost-Governed On-Demand Chunk Reads and Native Fallback Admission | Accepted |
| [leafrtp-ADR-087](leafrtp-leafrtp-ADR-068-release-cadence-contractual-pro-discretionary-lite.md) | Release Cadence: Contractual Pro Patches, Discretionary Lite Releases, One Version Line | Accepted |
| [leafrtp-ADR-088](leafrtp-leafrtp-ADR-069-claim-checkers-compile-against-published-apis.md) | Claim Checkers Compile Against Published Plugin APIs; Reflection Only Where None Exists | Accepted |
| [leafrtp-ADR-089](leafrtp-leafrtp-ADR-070-slope-detection-and-elevation-memory.md) | Slope Detection at the Landing Spot; Elevation and Slope Memory Recorded as Alternatives | Accepted |
| [leafrtp-ADR-090](leafrtp-leafrtp-ADR-071-mountainside-check-over-pinned-square.md) | Mountainside and Cliff-Edge Check over the Resident 3x3 Square | Accepted |
| [leafrtp-ADR-091](leafrtp-ADR-091-built-in-test-for-configuration-and-the-action-engine.md) | Built-In Test for Configuration and the Action Engine | Accepted |
| [leafrtp-ADR-092](leafrtp-leafrtp-ADR-072-folia-tick-region-allocation.md) | Folia Tick-Region Allocation: Work Placement, Probed Shares, Steering and Lending | Accepted |
| [leafrtp-ADR-093](leafrtp-leafrtp-ADR-073-consumer-attribution-for-background-refill.md) | Consumer Attribution for Constrained Background Refill | Accepted |

## Template

Use [ADR-TEMPLATE.md](ADR-TEMPLATE.md) when recording a new `LeafRTP` decision.

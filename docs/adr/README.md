# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the RTP project.

An ADR captures a significant architectural decision: the context that forced it, the decision made, the alternatives considered, and the consequences. They are numbered sequentially and never deleted — superseded records are marked as such.

## Index

| # | Title | Status |
|---|-------|--------|
| [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) | Archimedean Spiral 1D Mapping for Location Selection | Accepted |
| [ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md) | H2/SQLite for Spatial Memory Persistence Over Flat-File Cache | Accepted |
| [ADR-003](ADR-003-rtp-plugin-bridge-module.md) | rtp-plugin as a Separate Bridge Module from rtp-core | Accepted |
| [ADR-004](ADR-004-countbound-taskpipe-on-folia.md) | CountBoundTaskPipe Instead of TimeBoundTaskPipe on Folia Regional Threads | Accepted |
| [ADR-005](ADR-005-paperlib-removal.md) | Removal of PaperLib in Favour of Native Paper APIs | Accepted |
| [ADR-006](ADR-006-async-queue-pre-generation.md) | Async Queue Pre-Generation Over On-Demand Async Selection | Accepted |
| [ADR-007](ADR-007-per-user-isolated-queues.md) | Per-User Isolated Queues Alongside the Global Queue | Accepted (operational details superseded by ADR-043) |
| [ADR-008](ADR-008-memory-tracker-active-gc.md) | MemoryTracker as Active Task GC with WeakReference Deallocation | Accepted |
| [ADR-009](ADR-009-configurable-spatial-distributions.md) | Configurable Spatial Distributions: Flat, Normal, Exponential | Accepted |
| [ADR-010](ADR-010-versioned-platform-adapter-submodules.md) | Versioned Platform Adapter Submodules | Accepted |
| [ADR-011](ADR-011-rtp-api-separate-module.md) | `rtp-api` as a Separately Published Addon Interface | Accepted |
| [ADR-012](ADR-012-chunk-reservation-abstraction.md) | `ChunkReservation` as an Internal Chunk Ticket Abstraction | Accepted |
| [ADR-013](ADR-013-addons-as-external-gradle-projects.md) | Addons as External Gradle Projects Rather Than Built-In Optional Modules | Accepted (partially superseded by ADR-019) |
| [ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md) | Stale-Chunk Guard for Count-Bound Pipes | Accepted |
| [ADR-016](ADR-016-anvil-subsystem.md) | Anvil Read-Only Subsystem (Prefilter, Backed Chunk View, Shared Module) | Accepted |
| [ADR-017](ADR-017-block-tags-and-state-predicates-in-safety-lists.md) | Block Tags and Block-State Predicates in Safety Lists | Accepted |
| [ADR-018](ADR-018-agents-md-public-release-structure.md) | `AGENTS.md` Public Release Structure: Thin Router + Canonical Sources | Accepted |
| [ADR-019](ADR-019-claim-plugin-integrations-folded-into-plugin.md) | Claim-Plugin Integrations Folded Into `rtp-plugin`; Example Addon Retained | Accepted |
| [ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md) | Language Bootstrap and Locale-Aware ConfigParser | Accepted |
| [ADR-021](ADR-021-legacy-mc-and-java-support-scope.md) | Legacy Minecraft and Java Support Are Out of Scope | Accepted |
| [ADR-022](ADR-022-shape-cache-key-seed-plus-config-hash.md) | Region Shape Cache Key: Seed + Canonical Config Hash | Accepted |
| [ADR-023](ADR-023-login-reserve-cache.md) | Login Reserve Cache for Join-Time RTP | Accepted |
| [ADR-024](ADR-024-rtp-lite-assembly-variant.md) | RTP Lite Assembly Variant | Accepted |
| [ADR-025](ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md) | Replace SimpleYaml with an Internal SnakeYAML-Backed YAML Wrapper | Proposed |
| [ADR-026](ADR-026-external-hook-api-surface.md) | External Hook API Surface | Accepted |
| [ADR-028](ADR-028-l3-backlog-cache.md) | L3 Backlog Cache (`backlogLocations`) | Accepted |
| [ADR-032](ADR-032-teleport-pipeline-latency-histogram.md) | Teleport Pipeline Latency Histogram: 256-Sample Wait-Free Ring, Never Reset | Proposed |
| [ADR-034](ADR-034-memory-shape-catalog.md) | Memory Shape Catalog and Polygon Shape | Accepted |
| [ADR-035](ADR-035-interactive-menus-book-first.md) | Interactive Menus via Written Book (Book-First, Chat Fallback) | Proposed |
| [ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md) | Network Mode: Multi-Server, Multi-Proxy RTP | Proposed |
| [ADR-037](ADR-037-harden-rtp-config-commands.md) | Harden RTP Config Commands (Prerequisite for `/rtpadmin` Setup Wizards) | Accepted |
| [ADR-038](ADR-038-rtpadmin-setup-wizards.md) | `/rtpadmin` Setup Wizards (Book-Menu Driven, Config-Transaction Backed) | Proposed |
| [ADR-039](ADR-039-rtpadmin-diagnostic-surfaces.md) | `/rtpadmin` Diagnostic Surfaces (Biome Map, Bad-Selection Heatmap, Metrics Readouts) | Proposed |
| [ADR-040](ADR-040-cross-backend-metric-time-series-publication.md) | Cross-Backend Metric Time-Series Publication via `MemoryTracker` Tier Promotion | Proposed |
| [ADR-041](ADR-041-config-command-and-save-implementation.md) | `/rtp config` Command and Save Mechanics: Implementation Strategy (companion to ADR-037) | Proposed |
| [ADR-043](ADR-043-personal-queue-permission-semantics.md) | `rtp.personalqueue` Permission Semantics: Bucket-Only Opt-In (supersedes operational details of ADR-007) | Accepted |

## Subproject ADRs

ADRs whose scope is confined to a single subproject (module / addon) live next to that subproject's source under `<subproject>/docs/adr/` rather than in this directory. They follow the same numbering sequence and template as the project-wide ADRs above. Catalog of known subproject ADR directories:

| Subproject | Path | ADRs |
|------------|------|------|
| `commands-api` | [`commands-api/docs/adr/`](../../commands-api/docs/adr/) | [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) Brigadier Bridge via `commands-api` Adapter Layer (Proposed) |
| `effects-api` | [`effects-api/docs/adr/`](../../effects-api/docs/adr/) | [effects-api-ADR-001](../../effects-api/docs/adr/effects-api-ADR-001-glide-effect.md) Glide Effect in `effects-api` (Proposed); [effects-api-ADR-002](../../effects-api/docs/adr/effects-api-ADR-002-type-driven-reading-order.md) Type-Driven Adaptive Reading Order (Accepted; amended by ADR-004); [effects-api-ADR-003](../../effects-api/docs/adr/effects-api-ADR-003-platform-split-bukkit-fabric.md) Platform Split Bukkit/Fabric (Accepted); [effects-api-ADR-004](../../effects-api/docs/adr/effects-api-ADR-004-value-coercer-spi.md) `ValueCoercer` SPI: Per-Platform Type Binding for the Adaptive Reading Order (Accepted) |
| `rtp-fabric` | [`rtp-fabric/docs/adr/`](../../rtp-fabric/docs/adr/) | [rtp-fabric-ADR-001](../../rtp-fabric/docs/adr/rtp-fabric-ADR-001-multiversion-submodule-layout.md) Fabric Multiversion Submodule Layout (Accepted); [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) Fabric Platform Is In Scope (Accepted; renumbered from ADR-022 on 2026-05-05 to resolve a numbering collision); [rtp-fabric-ADR-003](../../rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md) Non-persistent chunk tickets via `DistanceManager.addRegionTicket` (Accepted); [rtp-fabric-ADR-004](../../rtp-fabric/docs/adr/rtp-fabric-ADR-004-distance-manager-api-split.md) DistanceManager API split at MC 1.21.5 (Accepted); [rtp-fabric-ADR-005](../../rtp-fabric/docs/adr/rtp-fabric-ADR-005-anvil-prefilter-parity.md) Fabric anvil pre-filter parity (Accepted); [rtp-fabric-ADR-006](../../rtp-fabric/docs/adr/rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md) Chunk-ticket radius is `3`, not `31` (Accepted); [rtp-fabric-ADR-007](../../rtp-fabric/docs/adr/rtp-fabric-ADR-007-mojmap-name-decoupling.md) Decouple `FabricVersionAdapter` from Mojmap type names (Accepted); [rtp-fabric-ADR-008](../../rtp-fabric/docs/adr/rtp-fabric-ADR-008-non-blocking-chunk-generation.md) Non-blocking chunk generation via `ServerChunkCache#getChunkFuture` (Accepted); [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) Obf/unobf common module split (Accepted); [rtp-fabric-ADR-010](../../rtp-fabric/docs/adr/rtp-fabric-ADR-010-typed-block-tag-snapshot.md) Typed block-tag snapshot via per-version SPI; reflection demoted to fallback (Accepted) |

When adding a new subproject ADR, also add a row here so the global index remains a one-stop catalog.

## Template

Use [ADR-TEMPLATE.md](ADR-TEMPLATE.md) when recording a new decision.

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
| [ADR-007](ADR-007-per-user-isolated-queues.md) | Per-User Isolated Queues Alongside the Global Queue | Accepted |
| [ADR-008](ADR-008-memory-tracker-active-gc.md) | MemoryTracker as Active Task GC with WeakReference Deallocation | Accepted |
| [ADR-009](ADR-009-configurable-spatial-distributions.md) | Configurable Spatial Distributions: Flat, Normal, Exponential | Accepted |
| [ADR-010](ADR-010-versioned-platform-adapter-submodules.md) | Versioned Platform Adapter Submodules | Accepted |
| [ADR-011](ADR-011-rtp-api-separate-module.md) | `rtp-api` as a Separately Published Addon Interface | Accepted |
| [ADR-012](ADR-012-chunk-reservation-abstraction.md) | `ChunkReservation` as an Internal Chunk Ticket Abstraction | Accepted |
| [ADR-013](ADR-013-addons-as-external-gradle-projects.md) | Addons as External Gradle Projects Rather Than Built-In Optional Modules | Accepted |
| [ADR-014](ADR-014-brigadier-bridge-via-commands-api.md) | Brigadier Bridge via `commands-api` Adapter Layer | Proposed |
| [ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md) | Stale-Chunk Guard for Count-Bound Pipes | Accepted |
| [ADR-016](ADR-016-anvil-readonly-prefilter.md) | Anvil Read-Only Pre-Filter for Vanilla Spigot Safety Checks | Proposed |

## Template

Use [ADR-TEMPLATE.md](ADR-TEMPLATE.md) when recording a new decision.

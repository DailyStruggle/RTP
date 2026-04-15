# Changelog

All notable changes to RTP are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- `SECURITY.md` — private vulnerability disclosure policy and response timeline.
- `CHANGELOG.md` — this file.
- `TRACEABILITY.md` — full requirements-to-code traceability matrix (63 REQ-IDs).
- `GLOSSARY.md` — 40+ domain term definitions for contributors and addon developers.
- `STAKEHOLDERS.md` — actor definitions and stakeholder goals.
- Unique requirement IDs (`REQ-{MODULE}-{CATEGORY}-{NNN}`) across all `REQUIREMENTS.md` files.
- Scope / Out-of-Scope section to root `REQUIREMENTS.md`.
- `check_traceability.sh` — CI script that fails if any REQ-ID lacks a traceability row.
- Traceability Check stage in `Jenkinsfile` and GitHub Actions workflow.
- GitHub Actions workflow updated to Java 21, action v4, Spotless check, JUnit result publishing, and Jacoco coverage upload.
- `MIGRATION.md` — operator upgrade guide covering config compatibility, cache handling, and PaperLib removal for 3.0.0-beta+.
- `docs/adr/` — Architecture Decision Records directory with template and ADR-001 through ADR-009:
  - ADR-001: Archimedean spiral 1D mapping rationale
  - ADR-002: H2/SQLite over flat-file cache for spatial memory persistence
  - ADR-003: rtp-plugin as a separate bridge module from rtp-core
  - ADR-004: CountBoundTaskPipe instead of TimeBoundTaskPipe on Folia regional threads
  - ADR-005: Removal of PaperLib in favour of native Paper APIs
  - ADR-006: Async queue pre-generation over on-demand async selection
  - ADR-007: Per-user isolated queues alongside the global queue
  - ADR-008: MemoryTracker as active task GC with WeakReference deallocation
  - ADR-009: Configurable spatial distributions — Flat, Normal, and Exponential — and the operator use-cases each serves
  - ADR-010: Versioned platform adapter submodules — compile-time NMS version separation over runtime reflection or version guards
  - ADR-011: `rtp-api` as a separately published addon interface — clear supported contract, prevents reflection-based coupling to internals
  - ADR-012: `ChunkReservation` as an internal `AutoCloseable` chunk ticket abstraction — eliminates per-call-site leak risk and centralises the reservation lookup table
  - ADR-013: Addons as external Gradle projects — extension-by-example model, independent release cadence, lean core jar
- Module dependency graph (Mermaid diagram) added to `ARCHITECTURE.md`.
- ADR index link added to `ARCHITECTURE.md` header.
- `rtp fill reset` subcommand: clears a region's MemoryShape bad-sector data without starting a new fill operation.
- Improved origin and goal references across user-facing docs: `README.md §Why RTP?` and `docs/CONCEPTS.md §Bounded Selection` now link to the [original mathematical proof](https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/) and [ADR-001](docs/adr/ADR-001-archimedean-spiral-1d-mapping.md); `docs/CONCEPTS.md §Where to Go Next` adds the [SpigotMC resource page](https://www.spigotmc.org/resources/rtp.94812/) and the mathematical writeup link.
- `docs/HAZARDS.md` — hazard register listing 10 known hazards (H-001 through H-010) across player, server stability, and API categories, each with severity, mitigation, and governing REQ-ID or ADR.
- `docs/FAILURE_MODES.md` — failure mode catalog listing 9 failure modes (FM-001 through FM-009) with component, failure, effect, detection mechanism, defined response, and requirement cross-reference.
- `docs/RUNBOOK.md` — operator incident-response guide covering 7 scenarios: TPS drops, dangerous landing locations, plugin startup failure, fill task stall, database growth, addon load error, and reload not applying.
- `docs/REQUIREMENTS.md §3 Prohibition Requirements` — new `REQ-RTP-S-NNN` series (REQ-RTP-S-001 through REQ-RTP-S-006) defining what the system shall never do, cross-linked to `HAZARDS.md`.
- `docs/TRACEABILITY.md` — REQ-RTP-S-001 through REQ-RTP-S-006 rows added; Root/System total updated from 15 to 21; overall total updated from 63 to 69.
- Precondition, postcondition, invariant, and thread-safety Javadoc added to `RTPAPI` (class + all public methods), `ILocationGenerator` (interface + all four method overloads), and `ChunkReservation` (class-level contract block).

---

## [3.0.0-beta] — Unreleased

> ⚠️ **MAJOR version — breaking `rtp-api` changes.** See [MIGRATION.md](docs/admin/MIGRATION.md#upgrading-to-300-beta) for addon developer and operator upgrade instructions.

### Added
- `ChunkReservation` class (`rtp-api`) for explicit, `AutoCloseable` chunk ticket lifecycle management.
- `rtp-folia` adapter: full regional-thread scheduling (`rtp-folia-v1_20_R1`, `rtp-folia-v1_21_R1`, `rtp-folia-v26_1_R1`).
- `rtp fill reset [region]` subcommand: clears a region's MemoryShape bad-sector data without starting a new fill. Use when region geometry changes and an immediate cache rebuild is not desired.

### Changed
- `CachedLocation` refactored to an immutable Java record (breaking change for addons that mutated fields directly).
- `rtp-paper` adapter: PaperLib dependency removed in favour of native Paper async chunk loading APIs.
- Platform version targets upgraded to 26.1 (Spigot, Paper, Folia).
- `sendMessage` call-site tracking migrated to stack trace source for improved diagnostics.
- Garbage collection improvements for expired queue entries.

### Fixed
- Chunk leak in region queue replenishment.
- Memory leak in active task tracking.
- Immediate teleport timing regression.
- Duplicate task execution on queue drain.
- Caching inconsistencies in `MemoryShape`.
- Locational edge-case bugs in region boundary calculations.

---

## [2.0.18] — 2024-01-01

> This release's changes are now tracked under `[3.0.0-beta]` above. The 2.0.18 tag marks the last 2.x release.
>
> **Upgrading from 2.0.18?** See [MIGRATION.md](docs/admin/MIGRATION.md#upgrading-to-300-beta) for step-by-step instructions.

---

## [2.0.x] — Historical

> Detailed per-commit history is available via `git log`. This CHANGELOG tracks releases from 2.0.18 onward.
> Earlier versions covered initial multi-module split (`rtp-api` / `rtp-core` / platform adapters), introduction of the asynchronous queue system, Archimedean spiral shape algorithms, and the `MemoryShape` persistent caching system.

---

[Unreleased]: https://github.com/DailyStruggle/RTP/compare/v3.0.0-beta...HEAD
[3.0.0-beta]: https://github.com/DailyStruggle/RTP/compare/v2.0.18...v3.0.0-beta
[2.0.18]: https://github.com/DailyStruggle/RTP/releases/tag/v2.0.18

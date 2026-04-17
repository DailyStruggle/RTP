# Changelog

All notable changes to RTP are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.0.0-beta] — Unreleased

> ⚠️ **MAJOR version — breaking `rtp-api` changes.** See [MIGRATION.md](docs/admin/MIGRATION.md#upgrading-to-300-beta) for addon developer and operator upgrade instructions.

### Architectural Overview

Six architectural shifts define this release:

1. **Versioned platform submodules** — compile-time NMS separation via `common` + per-version submodules for Spigot, Paper, and Folia (ADR-010).

2. **Expanded public API (`rtp-api`)** — pre-existing module broadened into a stable, separately published addon surface; addons no longer need reflection-based coupling to internals (ADR-011).

3. **Multi-backend persistence** — flat-file sector cache replaced by SQL-backed storage (H2 default, SQLite, MySQL, PostgreSQL) with Redis caching and YAML fallback; H2 and SQLite are production-ready (ADR-002).

4. **Platform-specific scheduling pipelines** — dedicated pipeline per platform; Folia uses regional-thread scheduling, Paper uses native async chunk loading with PaperLib removed (ADR-004–006).

5. **Lock-free location buffer and state-based tasks** — `LockFreeLocationBuffer` decouples pre-generation from teleport dispatch; explicit task states enable pause/resume/cancellation and concurrent region fills.

6. **`ChunkReservation` lifecycle management** — `AutoCloseable` abstraction centralises chunk ticket tracking and eliminates the 2.x ticket leak (ADR-012).

7. **Renamed `fill` to `scan`** — `rtp.fill` permission and `/rtp fill` command have been renamed to `rtp.scan` and `/rtp scan` for clarity and consistency with the scanning nature of the task.
8. **Multi-platform expansion** — transition to a true multi-platform project with the introduction of the `rtp-fabric` adapter and the consolidation of `commands-api` and `effects-api` as sub-modules.

For the detailed bullet-level changes see the sections below. For the rationale behind each decision see [`docs/adr/`](docs/adr/README.md).

---

### Added
- `ChunkReservation` class (`rtp-api`) for explicit, `AutoCloseable` chunk ticket lifecycle management.
- `rtp-api` public surface expanded (breaking change — replaces reflection-based coupling to internals, see ADR-011). New types:
  - `RTPAPI` — primary addon entry point (pre-existing; Javadoc contracts and guard tests added in this release)
  - `ILocationGenerator` — location generation contract with four overloads
  - `GenerationContext` / `GenerationResult` — structured input/output types for location selection
  - `RTPScheduler` / `TrackedRTPTask` — scheduling API for cross-platform task submission
  - `RTPServerAccessor` — server-level abstraction (world list, TPS, online players)
  - `RTPEconomy` — economy integration interface *(experimental; not guaranteed stable in 3.0.0-beta)*
  - `RTPCommandSender` / `RTPPlayer` — platform-agnostic command sender and player abstractions
  - `RTPWorld` / `RTPChunk` / `ChunkSet` — world and chunk handle types
  - `RTPCoords` (record) / `MutableRTPCoords` — coordinate pair types; `RTPCoords` is the immutable record reducing hot-path allocation
  - `RTPLocation` — full location value type (world + coords + yaw/pitch)
  - `MessagesKeys` — enum of all translatable message keys
  - `RTPRunnable` — base runnable for all platform task submissions
  - `PerformanceTracker` — per-operation timing instrumentation (accessible to addons)
- All three platform adapters restructured into versioned submodules, each with a shared `common` module (see ADR-010):
  - `rtp-folia`: `rtp-folia-common`, `rtp-folia-v1_20_R1`, `rtp-folia-v1_21_R1`, `rtp-folia-v26_1_R1` — full regional-thread scheduling
  - `rtp-paper`: `rtp-paper-common`, `rtp-paper-v1_20_R1`, `rtp-paper-v1_21_R1`, `rtp-paper-v26_1_R1` — native Paper async chunk loading (PaperLib removed)
  - `rtp-spigot`: `rtp-spigot-common`, `rtp-spigot-v1_20_R1`, `rtp-spigot-v1_21_R1`, `rtp-spigot-v26_1_R1` — Bukkit scheduler adapter
- `rtp scan reset [region]` subcommand: clears a region's MemoryShape bad-sector data without starting a new scan. Use when region geometry changes and an immediate cache rebuild is not desired.
- Multi-backend database layer for spatial memory persistence, replacing the flat-file cache (see ADR-002). `AbstractSQLDatabaseAccessor` provides a shared SQL foundation; concrete implementations shipped:
  - `H2DatabaseAccessor` — embedded H2 (default, zero-config)
  - `SQLiteDatabaseAccessor` — embedded SQLite (lightweight alternative)
  - `MySQLDatabaseAccessor` — external MySQL / MariaDB
  - `PostgreSQLDatabaseAccessor` — external PostgreSQL
  - `RedisManager` — Redis-backed caching layer
  - `YamlFileDatabase` — YAML flat-file fallback for environments where a database is unavailable
  > ⚠️ MySQL, PostgreSQL, Redis, and YAML backends are present in the codebase but not yet fully validated for production use. Operators are advised to use H2 or SQLite until these backends are marked stable.
- Scan task optimization: configurable step distance for spiral traversal, reducing redundant sector checks during region pre-generation.
- Spatial resolution tuning and `badSum` reduction: finer-grained sector scoring lowers the rate of false-bad-sector classifications.
- `LockFreeLocationBuffer` class: pre-computed, lock-free location pool decoupling chunk selection from teleport dispatch.
- State-based task system: scan and teleport tasks now progress through explicit states, enabling clean pause/resume and cancellation.
- Per-region Folia rate limiting: regional threads are throttled independently to prevent scheduler starvation on high-region servers.
- Platform-specific scheduling pipelines: Spigot, Paper, and Folia each use a dedicated pipeline rather than a shared fallback path.
- Performance tracker: lightweight per-operation timing instrumentation for scan and teleport hot paths.
- `RegionCacheTask` async callbacks (`processResult` and chunk-loading completion) now report their CPU time to `PerformanceTracker`, so caching task computational cost is included in `pluginMSPT`.
- Chunk-loading wall-clock time is used as a rough proxy for chunk-load cost in `pluginMSPT`: `chunkLoadStart` is captured immediately before `chunkSet.complete().thenAccept(...)` and the elapsed nanoseconds are added to `PerformanceTracker` when the future resolves, giving a conservative upper-bound estimate for chunk I/O attributed to the plugin.
- Coordinate pair represented as a Java record, reducing allocation on the hot path.
- Integration test suite covering command execution, queue drain, and config reload scenarios.
- Requirement documentation (`docs/dev/REQUIREMENTS.md`) introduced with full REQ-ID coverage.
- `SECURITY.md` — private vulnerability disclosure policy and response timeline.
- `docs/dev/MULTI_PLATFORM_PLAN.md` — roadmap for Fabric and future multi-platform support.
- `commands-api` and `effects-api` integrated as local sub-modules to simplify cross-platform development.
- `rtp-fabric` module introduced for native Fabric mod support.
- `CHANGELOG.md` — this file.
- `docs/dev/TRACEABILITY.md` — full requirements-to-code traceability matrix (63 REQ-IDs).
- `docs/dev/GLOSSARY.md` — 40+ domain term definitions for contributors and addon developers.
- `docs/dev/STAKEHOLDERS.md` — actor definitions and stakeholder goals.
- Unique requirement IDs (`REQ-{MODULE}-{CATEGORY}-{NNN}`) across all `REQUIREMENTS.md` files.
- Scope / Out-of-Scope section to root `REQUIREMENTS.md`.
- `check_traceability.sh` — CI script that fails if any REQ-ID lacks a traceability row. Operators and contributors extending the addon surface should verify this script scans `addons/REQUIREMENTS.md` in addition to `docs/dev/REQUIREMENTS.md` to keep traceability totals accurate.
- Traceability Check stage in `Jenkinsfile` and GitHub Actions workflow.
- GitHub Actions workflow updated to Java 21, action v4, Spotless check, JUnit result publishing, and Jacoco coverage upload. Maven CI workflow (`maven.yml`) removed and replaced by Gradle (`gradle.yml`).
- `.github/dependabot.yml` — automated dependency update configuration added.
- `.github/PULL_REQUEST_TEMPLATE.md` — pull request checklist template added.
- `.github/ISSUE_TEMPLATE/config.yml` — issue template contact links configured.
- `MIGRATION.md` — operator upgrade guide covering config compatibility, cache handling, and PaperLib removal for 3.0.0-beta+.
- `docs/adr/` — Architecture Decision Records directory with template and ADR-001 through ADR-013:
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
- Module dependency graph (Mermaid diagram) added to `docs/dev/ARCHITECTURE.md`.
- ADR index link added to `docs/dev/ARCHITECTURE.md` header.
- Improved origin and goal references across user-facing docs: `README.md §Why RTP?` and `docs/dev/CONCEPTS.md §Bounded Selection` now link to the [original mathematical proof](https://www.reddit.com/r/admincraft/comments/owgvzz/too_much_math/) and [ADR-001](docs/adr/ADR-001-archimedean-spiral-1d-mapping.md); `docs/dev/CONCEPTS.md §Where to Go Next` adds the [SpigotMC resource page](https://www.spigotmc.org/resources/rtp.94812/) and the mathematical writeup link.
- `docs/admin/HAZARDS.md` — hazard register listing 10 known hazards (H-001 through H-010) across player, server stability, and API categories, each with severity, mitigation, and governing REQ-ID or ADR.
- `docs/admin/FAILURE_MODES.md` — failure mode catalog listing 9 failure modes (FM-001 through FM-009) with component, failure, effect, detection mechanism, defined response, and requirement cross-reference.
- `docs/admin/RUNBOOK.md` — operator incident-response guide covering 7 scenarios: TPS drops, dangerous landing locations, plugin startup failure, fill task stall, database growth, addon load error, and reload not applying.
- `docs/dev/REQUIREMENTS.md §3 Prohibition Requirements` — new `REQ-RTP-S-NNN` series (REQ-RTP-S-001 through REQ-RTP-S-006) defining what the system shall never do, cross-linked to `docs/admin/HAZARDS.md`.
- `docs/dev/TRACEABILITY.md` — REQ-RTP-S-001 through REQ-RTP-S-006 rows added; Root/System total updated from 15 to 21; overall total updated from 63 to 69. Note: REQ-IDs defined in `addons/REQUIREMENTS.md` are not yet reflected in this total; a follow-up audit is needed to determine the true count.
- Precondition, postcondition, invariant, and thread-safety Javadoc added to `RTPAPI` (class + all public methods), `ILocationGenerator` (interface + all four method overloads), and `ChunkReservation` (class-level contract block).
- `CONTRIBUTING.md` — contributor guide covering build setup, code style, PR process, and commit conventions.
- `docs/dev/CONCEPTS.md` — conceptual overview of bounded selection, the spiral algorithm, and the queue system for new contributors.
- `docs/dev/DESIGN.md` — internal design document covering module responsibilities and key architectural decisions.
- `docs/dev/COVERAGE_PLAN.md` — test coverage plan and gap tracker for the integration test suite.
- `docs/admin/COMMANDS.md` — full command reference for server operators.
- `docs/admin/CONFIGURATION.md` — configuration file reference with field descriptions and default values.
- `docs/admin/FAQ.md` — frequently asked questions for server operators.
- `docs/admin/QUICK_START.md` — operator quick-start guide for initial installation and basic region setup.
- `docs/FOR_ADDON_DEVELOPERS.md` — landing page for addon developers linking to `rtp-api` Javadoc, ADRs, and example addons.
- `docs/FOR_CONTRIBUTORS.md` — landing page for contributors linking to `CONTRIBUTING.md`, `docs/dev/DESIGN.md`, and coverage plan.
- `docs/FOR_SERVER_ADMINS.md` — landing page for server operators linking to quick-start, commands, configuration, and runbook.
- `docs/adr/README.md` — ADR index with status, title, and date for all 13 decision records.
- `addons/REQUIREMENTS.md` — requirements document scoped to the addon integration surface.

### Changed
- `CachedLocation` refactored to an immutable Java record (breaking change for addons that mutated fields directly).
- `rtp-paper` adapter: PaperLib dependency removed in favour of native Paper async chunk loading APIs.
- Platform version targets upgraded to 26.1 (Spigot, Paper, Folia).
- `sendMessage` call-site tracking migrated to stack trace source for improved diagnostics.
- Garbage collection improvements for expired queue entries.
- Active task tracking refactored with `MemoryTracker` using `WeakReference` deallocation to eliminate long-lived task retention.
- Caching logic flow updated: write-through path consolidated to remove redundant reads on queue replenishment.
- Java target upgraded to **Java 21**; build toolchain updated accordingly.
- Parallelism improved: independent region queues now fill concurrently without a shared lock on the global queue.
- Java object allocation reduced on hot paths (vectors removed, primitives preferred, records used for value types).
- `MemoryShape` pending-write storage refactored to use sets instead of maps, reducing per-entry memory overhead.
- `Region` class decomposed: responsibilities split to reduce file size and improve cohesion.

### Fixed
- Chunk leak in region queue replenishment.
- Memory leak in active task tracking.
- Immediate teleport timing regression.
- Duplicate task execution on queue drain.
- Caching inconsistencies in `MemoryShape`.
- Locational edge-case bugs in region boundary calculations.
- Erroneous 2D coordinate check that incorrectly rejected valid surface locations.
- Initial command execution failure on first plugin load.
- Chunk ticket leak introduced during early `ChunkReservation` integration.

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

[3.0.0-beta]: https://github.com/DailyStruggle/RTP/compare/v2.0.18...v3.0.0-beta
[2.0.18]: https://github.com/DailyStruggle/RTP/releases/tag/v2.0.18

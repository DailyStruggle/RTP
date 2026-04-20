# Changelog

All notable changes to RTP are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versioning follows [SemVer](https://semver.org/spec/v2.0.0.html).

---

## [3.0.0-beta.1] — Unreleased

> ⚠️ **Major release — breaking `rtp-api` changes.** See [MIGRATION.md](docs/admin/MIGRATION.md#upgrading-to-300-beta) for upgrade instructions.

### Highlights

1. **Versioned platform submodules** — compile-time NMS separation via a `common` module plus per-version submodules for Spigot, Paper, and Folia (ADR-010).
2. **Stable public `rtp-api`** — broadened addon surface; no more reflection into internals (ADR-011).
3. **Multi-backend persistence** — flat-file sector cache replaced by SQL-backed storage (H2 default, SQLite, MySQL, PostgreSQL) with Redis caching and YAML fallback (ADR-002). *H2 and SQLite are production-ready; MySQL/PostgreSQL/Redis/YAML remain experimental.*
4. **Per-platform scheduling pipelines** — Folia uses regional-thread scheduling; Paper uses native async chunk loading (PaperLib removed); Spigot uses the Bukkit scheduler (ADR-004–006).
5. **Lock-free location buffer & state-based tasks** — `LockFreeLocationBuffer` decouples pre-generation from teleport dispatch; explicit task states enable pause/resume/cancel and concurrent region fills.
6. **`ChunkReservation` lifecycle** — `AutoCloseable` abstraction eliminates the 2.x ticket leak (ADR-012).
7. **Multi-platform expansion** — `rtp-fabric` adapter (in progress) and `commands-api` / `effects-api` consolidated as sub-modules.
8. **Anvil read-only pre-filter** — off-tick safety probing of unloaded chunks on pure Spigot (ADR-016).

Rationale for each decision lives in [`docs/adr/`](docs/adr/README.md).

### Added

**Public API (`rtp-api`)** — new types include `RTPAPI`, `ILocationGenerator`, `GenerationContext` / `GenerationResult`, `RTPScheduler` / `TrackedRTPTask`, `RTPServerAccessor`, `RTPEconomy` *(experimental)*, `RTPCommandSender` / `RTPPlayer`, `RTPWorld` / `RTPChunk` / `ChunkSet`, `RTPCoords` (record) / `MutableRTPCoords`, `RTPLocation`, `MessagesKeys`, `RTPRunnable`, `PerformanceTracker`, and `ChunkReservation`. Precondition / postcondition / thread-safety Javadoc added to `RTPAPI`, `ILocationGenerator`, and `ChunkReservation`.

**Platform adapters** — restructured into versioned submodules with a shared `common` module (ADR-010):
- `rtp-folia`, `rtp-paper`, `rtp-spigot` each ship `-common`, `-v1_20_R1`, `-v1_21_R1`, `-v26_1_R1`.
- New `rtp-fabric` adapter (work in progress; see `docs/dev/MULTI_PLATFORM_PLAN.md`).

**Persistence** — `AbstractSQLDatabaseAccessor` foundation with concrete `H2`, `SQLite`, `MySQL`, `PostgreSQL` accessors, plus `RedisManager` and `YamlFileDatabase` fallback.

**Performance & scheduling** — `LockFreeLocationBuffer`, state-based scan/teleport tasks, per-region Folia rate limiting, per-platform pipelines, and `PerformanceTracker` instrumentation. Region-cache async callbacks and chunk-load wall-clock time are now attributed to `pluginMSPT`. `RTPCoords` is a record to reduce hot-path allocation.

**Commands** — `/rtp scan reset [region]` clears a region's `MemoryShape` bad-sector data without restarting a scan.

**Documentation & governance**
- `docs/dev/REQUIREMENTS.md` with full REQ-ID coverage, including new `REQ-RTP-S-001..S-006` prohibitions cross-linked to `docs/admin/HAZARDS.md`.
- `docs/dev/TRACEABILITY.md` (69 REQ-IDs; addon requirements pending audit), `docs/dev/GLOSSARY.md`, `docs/dev/STAKEHOLDERS.md`, `docs/dev/CONCEPTS.md`, `docs/dev/DESIGN.md`, `docs/dev/COVERAGE_PLAN.md`, `docs/dev/MULTI_PLATFORM_PLAN.md`.
- `docs/adr/` — ADR-001 through ADR-013 and index (`README.md`); ADR-014 (Brigadier bridge), ADR-015 (stale-chunk guard), ADR-016 (Anvil pre-filter) added later in the beta cycle.
- `docs/admin/` — `MIGRATION.md`, `QUICK_START.md`, `COMMANDS.md`, `CONFIGURATION.md`, `FAQ.md`, `HAZARDS.md` (H-001..H-010), `FAILURE_MODES.md` (FM-001..FM-009), `RUNBOOK.md` (7 incident scenarios).
- Audience landing pages: `docs/FOR_ADDON_DEVELOPERS.md`, `docs/FOR_CONTRIBUTORS.md`, `docs/FOR_SERVER_ADMINS.md`.
- Root-level `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, and `addons/REQUIREMENTS.md`.
- Module dependency diagram and ADR index link in `docs/dev/ARCHITECTURE.md`.

**CI / tooling** — Gradle workflow (`gradle.yml`) replaces Maven (`maven.yml`) on Java 21 with Spotless, JUnit publishing, and Jacoco upload; `check_traceability.sh` gate in Jenkins and GitHub Actions; `.github/dependabot.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, and `.github/ISSUE_TEMPLATE/config.yml`.

### Changed

- `/rtp fill` → `/rtp scan` (permission `rtp.fill` → `rtp.scan`) for clarity.
- `CachedLocation` is now an immutable Java record (breaking for addons that mutated fields directly).
- `rtp-paper`: PaperLib dropped in favour of native Paper async chunk loading.
- Platform version targets upgraded to 26.1 (Spigot, Paper, Folia); Java target raised to **Java 21**.
- Active task tracking moved to `MemoryTracker` with `WeakReference` deallocation; write-through caching consolidated.
- Independent region queues now fill concurrently without a shared global-queue lock.
- `sendMessage` call-site tracking now uses stack trace source for diagnostics.
- Allocation reduced on hot paths (records, primitives, vectors removed); `MemoryShape` pending-write storage switched from maps to sets.
- `Region` decomposed for cohesion.

### Fixed

- **Emergency landing platform spam** on kept-queue / DB-rehydrated teleports (Folia in particular). `TeleportPipelineTask.runTeleport` previously gated `RTPWorld.platform(...)` on `reservation == null`, which was always true for those paths. Gate is now a read-only safety check against the already-loaded landing chunk (`RTPWorld.getCachedChunk(...)` + `SafetyKeys.unsafeBlocks`); no chunk loads are triggered (REQ-RTP-S-005 preserved). `Region.execute` synthesizes a `ChunkReservation` for kept-queue pairs so REQ-RTP-S-002 stays intact. Default `safety.yml` now ships `platformRadius: -1` (disabled); set `platformRadius: 0` or higher to restore legacy behaviour.
- Chunk leak in region queue replenishment and in early `ChunkReservation` integration.
- Memory leak in active task tracking.
- Immediate-teleport timing regression and duplicate task execution on queue drain.
- `MemoryShape` caching inconsistencies and region-boundary locational edge cases.
- Erroneous 2D coordinate check that rejected valid surface locations.
- Initial command execution failure on first plugin load.

---

## [2.0.18] — 2024-01-01

> Last 2.x release. All changes since this tag are captured under `[3.0.0-beta.1]` above.
>
> **Upgrading from 2.0.18?** See [MIGRATION.md](docs/admin/MIGRATION.md#upgrading-to-300-beta).

---

## [2.0.x] — Historical

Earlier versions introduced the multi-module split (`rtp-api` / `rtp-core` / platform adapters), the asynchronous queue system, the Archimedean spiral shape algorithm, and the `MemoryShape` persistent cache. Per-commit history is available via `git log`.

---

[3.0.0-beta.1]: https://github.com/DailyStruggle/RTP/compare/v2.0.18...v3.0.0-beta.1
[2.0.18]: https://github.com/DailyStruggle/RTP/releases/tag/v2.0.18

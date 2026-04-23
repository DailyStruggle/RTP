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

**Commands** — `/rtp scan reset [region]` clears a region's `MemoryShape` bad-sector data without restarting a scan. New operator diagnostic `/rtp test chunk-probe-perf [samples=<n>]` A/B-times `RTPWorld.probeChunkColumn` against `RTPWorld.getChunkAtAsync` over a random sample of pregenerated chunks discovered by scanning the world's `region/` folder; reports pool size, totals, per-sample averages, the full/probe ratio, and the probe-null rate (BIOME_LOOKUP_PERF_PLAN phase 0). Now also times full-anvil `AnvilReader.readChunk` for pairwise comparison.

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

- **Claim-plugin integrations folded into `rtp-plugin`** (ADR-019). The `RTP_ClaimPluginIntegrations` addon is removed; the eight checkers (Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard) now register at plugin startup in `ClaimIntegrations`. `integrations.yml` ships with the plugin jar; behaviour for REQ-RTP-S-003 is unchanged (verifiers still live behind `GlobalRegionVerifiers`). A new `addons/RTP_ExampleAddon` replaces the claim addon as the canonical "how to write an addon" template.
- `/rtp fill` → `/rtp scan` (permission `rtp.fill` → `rtp.scan`) for clarity.
- `CachedLocation` is now an immutable Java record (breaking for addons that mutated fields directly).
- `rtp-paper`: PaperLib dropped in favour of native Paper async chunk loading.
- Platform version targets upgraded to 26.1 (Spigot, Paper, Folia); Java target raised to **Java 21**.
- Active task tracking moved to `MemoryTracker` with `WeakReference` deallocation; write-through caching consolidated.
- Independent region queues now fill concurrently without a shared global-queue lock.
- `sendMessage` call-site tracking now uses stack trace source for diagnostics.
- Allocation reduced on hot paths (records, primitives, vectors removed); `MemoryShape` pending-write storage switched from maps to sets.
- `Region` decomposed for cohesion.
- **ScanTask diagnostics (BIOME_LOOKUP_PERF_PLAN.md PR-16).** Extended the `[DEBUG_LOG] ScanTask concurrency ...` line with `avgColdMissMs` (average `Files.readAllBytes` wall time per cache miss) and `gcDeltaMs` (cumulative GC time since previous log window) so operators can discriminate between OS page-cache pressure, GC churn, and driver-loop bottlenecks when scan throughput degrades with scan progress. Diagnostic-only, no behavior change.
- **ScanTask per-bin drain removed + stage-2 instrumentation (BIOME_LOOKUP_PERF_PLAN.md PR-17).** PR-16 runtime data ruled out I/O and GC as causes of cps degradation (both metrics stayed flat while `peakInFlight` dropped 50 → ~25 over three minutes), pointing at driver-side pipeline bubbles. Removed the `inFlightGate.acquire(MAX_PENDING_CHUNKS)` drain between region-file bins in `ScanTask.run`: PR-15 coalescing already makes cross-bin cache interference cheap, so the barrier was costing saturation without earning correctness. Added `fullLoads=N fullLoadAvgMs=X.XX` to the gauge log (AtomicLong counters wrapped around `runFullLoadPath`'s result future) so operators can confirm whether stage-2 cost (loaded-chunk safety scan) grows with scan progress; if `fullLoadAvgMs` climbs while `avgColdMissMs` stays flat, the next optimization target is the full-load path, not the probe.
- **Biome-lookup performance plan (BIOME_LOOKUP_PERF_PLAN.md PR-1 through PR-12).** A sequence of commits retired `Region.maxBiomeChecksPerGen` + `PregenState.maxBiomeChecks` in favor of a single `maxAttempts` knob; added probe-first fast paths in `PregenTask` and `ScanTask` backed by `RTPWorld.probeChunkColumn` (overridden on Bukkit / Paper / Folia to read the chunk's center column directly from the persisted `r.X.Z.mca` via `AnvilReader.readColumnProbe`); wrapped the result through `AnvilColumnProbeAdapter` (applies `PaletteNormalizer::reconcile`); gated on `SafetyKeys.anvilPrefilterEnabled` + `isChunkLoaded`. New `LocationGenerator.FailTypes.prefilterBiome / prefilterBlock / prefilterRange` buckets attribute the rejections. `VerticalAdjustor` gained `requiresSkyLight()`. ScanTask reframes the probe as a load-replacement: on cache-miss the probe is authoritative (skip `getOrLoadChunk`); on cache-hit the safety radius upgrades to `max(configured, 2)` for free. Blocking I/O moved to a dedicated `AnvilIoPool` (`max(8, 2 * CPU)` daemon threads) to avoid `ForkJoinPool.commonPool` starvation. Added `AnvilRegionByteCache` (LRU of raw `.mca` bytes, mtime-invalidated) to eliminate redundant per-chunk `Files.readAllBytes` of the same ~4 MB region file; PR-11 added hit/miss/stale diagnostics surfaced in `ScanTask`'s existing `[DEBUG_LOG] ScanTask concurrency ...` line; PR-12 raised cache capacity from 4 to 16 (~64 MB steady-state) after PR-11 measurement showed the 4-entry LRU thrashing at ~0.31 hit rate because the scan frontier spans 6-10 distinct region files simultaneously. Measured `full/probe` ratio is 16.46x on Bukkit and 20.70x on Folia via `/rtp test chunk-probe-perf`.

### Fixed

- **ScanTask probe-first fast path rejected passable non-air blocks (BIOME_LOOKUP_PERF_PLAN.md follow-up).** After the `isAirAt` reconciliation fix unblocked the anvil fast path, live scans still showed a residual `adjustNull` tail (~1300-1600 per gauge tick) whenever the center-column body/head cells held configured-passable blocks like tall grass, flowers, or snow layer. `JumpAdjustor.acceptProbeY` required strict vanilla-air (`isAirAt`) for Y and Y+1, so any chunk whose candidate Y had grass above stone was routed back through the full-load path. `safety.yml`'s `airBlocks` list documents exactly those passable materials but was wired only into the config enum - no code consumed it. Fix: `JumpAdjustor` now caches `SafetyKeys.airBlocks` next to `unsafeBlocks` (shared 5-second refresh in `refreshSafetySets`) and `acceptProbeY` accepts body/head as passable when vanilla air OR present in `airBlocks`, with `unsafeBlocks` taking precedence on conflicts. Tag-grammar tokens like `#minecraft:flowers` are stored verbatim and remain inert until the config reader's tag expansion slice lands (tracked in `docs/dev/SAFETY_TAGS_AND_STATES_PLAN.md` Slice 3). Regression guarded by `JumpAdjustorProbeTest.airBlocksFromConfig_acceptsTallGrassHeadSpace`.
- **ScanTask probe-first fast path silently vetoed by case-sensitive `isAirAt` (BIOME_LOOKUP_PERF_PLAN.md follow-up).** `AnvilColumnProbeAdapter.blockAt` reconciles every palette identifier through `PaletteNormalizer::reconcile`, which converts `"minecraft:air"` to `Material.AIR.name()` = `"AIR"` so that downstream `unsafeBlocks.contains(...)` checks (populated as `Material#name()` upper-case tokens like `LAVA`) match symmetrically. The default `ChunkColumnProbe.isAirAt` then compared the path segment against lowercase `"air"` / `"cave_air"` / `"void_air"` and returned false for every air block, making `JumpAdjustor.acceptProbeY` and `LinearAdjustor.acceptY` find no acceptable Y on any center column. Live runs showed `adjustNull == activeChecks` every gauge tick with `fullLoads == activeChecks`, negating the anvil pre-filter entirely and leaving scan throughput at the PR-17 baseline. Fix: override `isAirAt` in `AnvilColumnProbeAdapter` to recognize the reconciled `AIR` / `CAVE_AIR` / `VOID_AIR` forms in addition to the raw `minecraft:*` path segments. Regression guarded by `AnvilColumnProbeAdapterIsAirAtTest`.
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

# Changelog

All notable changes to RTP are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versioning follows [SemVer](https://semver.org/spec/v2.0.0.html).

---

## [3.0.0-beta.2] - Unreleased

### Added

- **Initial Fabric (`rtp-fabric`) platform support for Minecraft 1.21.x.** Beta.1 shipped a non-functional Fabric source tree marked out of scope; beta.2 turns it into a working adapter with `/rtp` teleporting end-to-end: async chunk path (no synchronous main-thread loads), Brigadier command tree (`RTPCmdFabricRoot`) with parameter and subcommand parity matching Bukkit (5 parameters, 5 of 6 subcommands), tab-complete at the `/rtp` root, anvil pre-filter parity, multi-version support (1.20.x / 1.21.x including 1.21.11), and permissions via `fabric-permissions-api` with an `ops.json` fallback. Covered by `rtp-fabric-ADR-001` through `rtp-fabric-ADR-007`. **Known Fabric limitations** (see *Known Issues* below): permission-based effects do not fire, and SQL database backends are not wired (SQLite fallback only; MySQL/PostgreSQL/H2 are unplanned on Fabric). Deferred to a later release: `TestCmd` lift, event-bus wiring, online-player tab-complete.
- **`GlideEffect` in `effects-api`** (effects-api-ADR-001) — folds `addons/RTP_Glide` into `effects-api` as a first-class effect with a shutdown-time safe-landing fallback and a new `PlayerLandEvent.Reason`.
- **`FixedAdjustor` vertical adjustor (`vert: FIXED`).** Third engine alongside `LINEAR` / `JUMP`; places the player at a single configured Y in mid-air for skyblock-style worlds (requires the platform tool). Covered by `FixedAdjustorTest`.
- **Declarative effect-group configuration via `effects/<group>.yml`** (effects-api-ADR-005). Each file under `<pluginDir>/effects/` declares a named group with fields `when:` (pipeline stage), optional `permission:` / `players:` / `inherit:`, and a required `effects:` token list (same grammar as `rtp.effect.<stage>.*` permission suffixes). Loaded via the existing `MultiConfigParser<EffectsGroupKeys>` plumbing — no bespoke loader class — so per-locale `effects.lang.yml` field-key remap and `/rtp reload` hot-swap come for free. The plugin now registers an `effects/` parser in `Configs.reloadConfigs()`; `BukkitEffectsHandler` and `FabricEffectsHandler` re-resolve groups per teleport via a new `EffectsResolver.resolveUnioned(...)` helper that unions permission-derived nodes with config-driven tokens before feeding `EffectFactory.buildEffects(prefix, ...)`. Fabric admins now have a usable effect-configuration surface without `fabric-permissions-api`; Bukkit admins gain a permission-less complement to the existing `rtp.effect.*` permission tree.
- **`/rtp info` health block + bStats RTP cost-metric charts** ([`METRICS_PLAN.md`](docs/dev/METRICS_PLAN.md), Phase M1+M2 vertical slice). `/rtp info` now appends four operator-facing live-state lines sourced from a single `RTP.metrics.snapshot()` call: players waiting in region queues (`[queueDepth]`), in-flight teleport pipelines (`[pendingTeleports]`), rolling-mean pipeline latency (`[avgPipelineMs]`, ms), and JVM heap usage (`[heapUsedMb]` / `[heapMaxMb]`). Seven new placeholder tokens — `queueDepth`, `pendingTeleports`, `avgPipelineMs`, `heapUsedMb`, `heapMaxMb`, `memoryEntries`, `chunkLoadBacklog` — are usable in any `messages.yml` line and via PlaceholderAPI. The bStats integration (full id 30865, lite id 12277) registers eleven new charts via `RTPCostMetricsCharts`: configuration adoption (`platform`, `assembly_variant`, `database_backend`), `region_count` (single-line), `cache_pool_health` (multi-line, L1 / L2 % fill), and bucketised cost histograms (`tps_buckets`, `mspt_buckets`, `pipeline_latency_buckets`, `memory_tracker_pressure`, `chunk_load_backlog_pressure`, `queue_depth_pressure`). All bucketing is privacy-safe (no raw scalars, no server / region / world identifiers); chart lambdas read pre-computed snapshot fields and never call platform APIs inline. Per-platform `MetricsBinding` for TPS (Folia, Fabric, Spigot fallback) and the runtime-health charts that depend on it (e.g. `s005_violations_recent`) remain deferred to a follow-up — TPS lambdas use a reflective `Bukkit.getServer().getTPS()` lookup as an interim probe and report `unknown` when unavailable.

### Changed

- **`effects-api` platform-split for Fabric readiness** (effects-api-ADR-003; effects-api-ADR-004 amends ADR-002). `Effect` and `EffectFactory` moved to `effectsapi.common` and decoupled from `org.bukkit.*` (now `implements Runnable, Cloneable`); Bukkit-side concretes, listeners, commands, and events relocated under `effectsapi.bukkit.*`; token coercion routed through a per-platform `ValueCoercer` SPI; Bukkit registration and scheduling extracted to `BukkitEffectsInitializer`. **Addon migration:** import prefix changes from `io.github.dailystruggle.effectsapi.{LocalEffects,SpigotListeners,commands,events}.*` to `io.github.dailystruggle.effectsapi.bukkit.*`; subclasses that called inherited `BukkitRunnable` methods (`runTaskTimer`, `cancel`, `isCancelled`, etc.) directly will no longer compile; subclasses that only override `run()` are unaffected.
- **Bundled `docs/` tree inside release jars trimmed to admin- and addon-author-facing material.** `rtp-plugin`'s `copyDocs` no longer ships internal dev docs.
- **`MULTI_SERVER_PLAN.md` updated to treat multi-proxy deployments as first-class** (docs only; D-005 gated, no code changes). Adds a *Multi-Proxy Deployment* section covering HA / failover, geo-distributed proxies, capacity scaling, and blue/green proxy upgrades; introduces a per-proxy `proxyId` field in the `network.yml` straw-man (analogous to per-backend `serverId`); locks in design rules for proxies (no proxy-to-proxy chatter, no singleton assumption, idempotent operations, advisory-only local state, per-proxy heartbeat row); documents hot-spot avoidance under multiple proxies and reservation-token / network-wait-queue behaviour under proxy races and proxy death; adds REQ-RTP-NET-009 / REQ-RTP-NET-010 stubs; bumps the Phase 2 acceptance baseline to `2× Velocity (behind L4) + 2× Paper`.
- **`MULTI_SERVER_PLAN.md` documents DragonflyDB (and other RESP-compatible servers) as supported Redis-transport drop-ins** (docs only; D-005 gated, no code changes). The `redis` transport type covers Redis, DragonflyDB, and KeyDB through the same `RedisNetworkStateBinding` — only the connection URL differs, no `transport.flavour` knob, no per-flavour code branches. Redis remains the reference implementation; Phase 2 acceptance gains a parallel container exercising the cross-server `/rtp` round-trip against DragonflyDB on the unmodified binding, with any observed RESP divergence (Lua/`EVAL`, Streams consumer groups, persistence semantics) recorded in `LESSONS_LEARNED.md` rather than branched on. The post-implementation Postgres-vs-Redis comparative benchmark grows a third row for Dragonfly to give operators evidence-based guidance on when its multi-threaded single-node design is worth choosing.
- **Fabric is supported by the RTP-lite assembly variant** ([ADR-024](docs/adr/ADR-024-rtp-lite-assembly-variant.md), 2026-05-07 amendment). The lite jar now ships `RTPFabricMod`, the `rtp-fabric-common` classes, and `fabric.mod.json`, so Fabric Loader can discover RTP from the unclassified `RTP-<version>.jar` instead of requiring `RTP-Pro`. JDBC drivers (H2 / SQLite / MySQL / PostgreSQL) remain stripped from lite, so Fabric+lite operators land on `FabricDatabaseHandler.setupDatabase`'s flat-file YAML fallback (a loud warning is logged); operators who want a SQL backend on Fabric must run the Pro jar. Folia remains full-only.

### Fixed

- **Effect permission tokens with skipped earlier inputs no longer wreck the rest of the chain** (effects-api-ADR-002). `Effect.setData(String...)` now walks a per-effect `KEY_ORDER` with a non-rewinding cursor, assigning each token to the first remaining key whose default type accepts it. Removes a `printStackTrace` path. Covered by `EffectsApiAdaptiveReadingOrderTest`.
- **Per-permission effects did not fire under RTP-lite.** Lite bootstrap now schedules `BukkitEffectsHandler.setupEffects` at tick+1 and re-introduces `effectParsing: true` in the lite `performance.yml`.

### Known Issues

**Fabric-only:**

- **Permission-based effects do not fire on Fabric.** Use the global effect configuration; per-permission effect tokens are Bukkit-only in this beta.
- **SQL database backends are not available on Fabric.** Only the SQLite fallback is wired. MySQL, PostgreSQL, H2, Redis, and YAML accessors are **unplanned** for Fabric. Bukkit-family platforms are unaffected.
- **Block-tag registry occasionally logs `Block-tag registry yielded no usable tags` during Fabric startup.** Tag resolution races mod / datapack registry population on some load orders; RTP retries resolution after server startup completes, so the warning is informational and tag-driven safety predicates remain in effect once startup finishes. Bukkit-family platforms are unaffected.

**All platforms:**

- **Translations do not apply to dynamic config directories** (`worlds/`, `regions/`, `effects/`). Localized `lang/<locale>/*.lang.yml` overlays cover the static config files (e.g. `config.yml`, `safety.yml`, `performance.yml`) but per-world, per-region, and per-effect files generated under those directories remain in the default language regardless of the configured locale. Affects Bukkit-family platforms (Spigot, Paper, Folia) and Fabric.

---

## [3.0.0-beta.1] - 2026-05-03

> ?? **Major release - breaking `rtp-api` changes.** See [MIGRATION.md](docs/admin/MIGRATION.md#upgrading-to-300-beta) for upgrade instructions.

### Beta Scope

This is a **beta** release. The following surface is considered **in scope** and supported:

- **Platforms:** Spigot, Paper, Folia (Minecraft 1.20.x / 1.21.x / 26.1, Java 21+).
- **Persistence:** H2 (default) and SQLite. MySQL, PostgreSQL, Redis, and YAML fallback ship but are **experimental** - production use not recommended.
- **Public API:** the `rtp-api` types listed under [Added](#added). Internals (`rtp-core`, platform adapters) may still change between betas.
- **Claim integrations** (folded into the plugin per ADR-019): Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard.
- **Anvil pre-filter** on Spigot (ADR-016).
- **Brigadier bridge** for Paper/Folia (commands-api-ADR-001).

**Out of scope for this beta** (do not file as bugs):

- **Fabric (`rtp-fabric`)** - present in the source tree but **not functional**. `/rtp` does not yet teleport on Fabric (scheduled-task processors not wired; permissions, teleport-cancel callback, and full Brigadier command tree pending). Tracking: [`docs/dev/MULTI_PLATFORM_PLAN.md`](docs/dev/MULTI_PLATFORM_PLAN.md). Do not deploy on Fabric.
- **Native Forge / NeoForge adapter** - not planned, and **lower priority than multi-server / proxy support**. Forge-based servers are supported via Bukkit-compatibility layers such as **Arclight** or **Mohist** (run RTP as the Spigot/Paper build on those launchers). A dedicated Forge/NeoForge adapter remains deferred (ADR-022, MULTI_PLATFORM_PLAN Phase 4) and may not materialize if compatibility layers continue to cover the use case.
- **Multi-server / proxy mode** (Velocity, BungeeCord cross-server queues, reservation tokens) - design only. See [`docs/dev/MULTI_SERVER_PLAN.md`](docs/dev/MULTI_SERVER_PLAN.md).
- **Legacy Minecraft (< 1.20) and legacy Java (< 21)** - see [ADR-021](docs/adr/ADR-021-legacy-mc-and-java-support-scope.md).
- **`RTPEconomy`** API - present but marked *experimental*; signature may change before `3.0.0` final.
- **`/rtp config` command** - present but **experimental**; subcommand surface, argument grammar, and write-back semantics may change before `3.0.0` final. Prefer editing config files directly for production use.
- **Lite assembly variant** ([ADR-024](docs/adr/ADR-024-rtp-lite-assembly-variant.md)) - produced from this build but not yet published on a fixed cadence. From `3.0.0` final onward, the Lite jar will be published as **`RTP`** (the **free tier**) a few hours-to-days after the corresponding **`RTP-Pro`** (full / paid tier) jar of the same version.

### Known Issues

User-visible issues that exist in this beta and are **not** considered release-blockers. File a new bug only if your symptom differs from the descriptions below.

- **Fabric adapter is non-functional.** See *Out of scope* above. Bukkit-family platforms are unaffected.
- **MySQL / PostgreSQL / Redis / YAML accessors are experimental.** Schema and migration semantics may change before `3.0.0` final. Stick with H2 or SQLite for production.
- **`safety.yml` and biome-filter edits do not invalidate the persisted shape cache** ([`POTENTIAL_BUGS.md`](docs/dev/POTENTIAL_BUGS.md), 2026-04-30 entry). Geometry and vertical-adjustor edits are caught (ADR-022); changes to `unsafeBlocks`, `platform`, `requireSkyLight`, ADR-017 tag/state predicates, or per-region biome whitelists/blacklists may leave stale "bad sector" flags in `<region>_<seed>_<hash>.bin`. **Workaround:** `/rtp scan reset <region>` after changing safety predicates.
- **Tag-grammar tokens in `safety.yml`** (e.g. `#minecraft:flowers`) are accepted but inert until the config-reader tag-expansion slice lands (`SAFETY_TAGS_AND_STATES_PLAN.md` Slice 3). Use explicit material names for now.
- **Two ADR files share the number `ADR-022`** ([`POTENTIAL_BUGS.md`](docs/dev/POTENTIAL_BUGS.md), 2026-05-01). Documentation only; no runtime effect. Cross-references in this changelog disambiguate by filename.
- **Pre-release cache invalidation:** legacy 2.x `.bin` files and existing `rtp_cached_locations` rows from any pre-`3.0.0-beta.1` build are abandoned by ADR-022's hash-keyed naming scheme. Expect a one-time re-scan on first launch.
- **Emergency landing platform default changed.** `safety.yml` now ships `platformRadius: -1` (disabled). Set `platformRadius: 0` or higher to restore legacy 2.x behaviour.

For a live backlog of incidental bugs spotted during development (not necessarily user-visible), see [`docs/dev/POTENTIAL_BUGS.md`](docs/dev/POTENTIAL_BUGS.md).

### Planned for `3.0.0` final and beyond

Roadmap visibility for users and addon authors. Order is intent, not commitment.

- **Fabric platform** to first-class (`rtp-fabric`) - **targeted for `3.0.0` final**. Active frontier - see [`MULTI_PLATFORM_PLAN.md`](docs/dev/MULTI_PLATFORM_PLAN.md). Remaining: scheduled-task processor parity (Step E3), permissions via `fabric-permissions-api` (Step F), full Brigadier command tree (Step G2), stabilization smoke test (Step H).
- **Fold `safety.yml` validity fields and biome filters into the region cache hash** ([`POTENTIAL_BUGS.md`](docs/dev/POTENTIAL_BUGS.md) follow-up to ADR-022).
- **Multi-server / proxy mode** (Velocity, BungeeCord) - phased rollout per [`MULTI_SERVER_PLAN.md`](docs/dev/MULTI_SERVER_PLAN.md). D-005 gated; admin docs stub at [`docs/admin/proxies/INDEX.md`](docs/admin/proxies/INDEX.md).
- **Runtime metrics** (TPS / MSPT / heap / queue depth / pipeline samples) - [`METRICS_PLAN.md`](docs/dev/METRICS_PLAN.md).
- **External hook API surface** ([ADR-026](docs/adr/ADR-026-external-hook-api-surface.md), [`EXTERNAL_HOOKS.md`](docs/dev/EXTERNAL_HOOKS.md)) - formal registry for third-party reflection / soft-depend integrations.
- **Lite assembly variant** ([ADR-024](docs/adr/ADR-024-rtp-lite-assembly-variant.md)) - trimmed jar for resource-constrained servers, **published as `RTP` (the free tier)** a few hours-to-days after the corresponding **`RTP-Pro` (full / paid tier)** jar of the same version. The Pro jar ships first; the free Lite jar (`RTP`) follows once the trimmed assembly is verified against the same release tag.
- **Promote MySQL / PostgreSQL / Redis / YAML** accessors out of *experimental*.
- **Stabilize `RTPEconomy`** API.
- **Stabilize `/rtp config` command** - finalize subcommand surface, argument grammar, and write-back semantics, then drop the *experimental* label.
- **Forge / NeoForge** - **lower priority than multi-server / proxy support.** No native adapter planned for the foreseeable future; Forge-based servers should use a Bukkit-compatibility launcher (Arclight, Mohist, or equivalent) and run the Spigot/Paper build. A dedicated adapter is only re-evaluated if those compatibility layers stop covering the platform ([rtp-fabric-ADR-002](rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md)).

### Highlights

1. **Versioned platform submodules** - compile-time NMS separation via a `common` module plus per-version submodules for Spigot, Paper, and Folia (ADR-010).
2. **Stable public `rtp-api`** - broadened addon surface; no more reflection into internals (ADR-011).
3. **Multi-backend persistence** - flat-file sector cache replaced by SQL-backed storage (H2 default, SQLite, MySQL, PostgreSQL) with Redis caching and YAML fallback (ADR-002). *H2 and SQLite are production-ready; MySQL/PostgreSQL/Redis/YAML remain experimental.*
4. **Per-platform scheduling pipelines** - Folia uses regional-thread scheduling; Paper uses native async chunk loading (PaperLib removed); Spigot uses the Bukkit scheduler (ADR-004 - 006).
5. **Lock-free location buffer & state-based tasks** - `LockFreeLocationBuffer` decouples pre-generation from teleport dispatch; explicit task states enable pause/resume/cancel and concurrent region fills.
6. **`ChunkReservation` lifecycle** - `AutoCloseable` abstraction eliminates the 2.x ticket leak (ADR-012).
7. **Multi-platform expansion** - `rtp-fabric` adapter (in progress) and `commands-api` / `effects-api` consolidated as sub-modules.
8. **Anvil read-only pre-filter** - off-tick safety probing of unloaded chunks on pure Spigot (ADR-016).

Rationale for each decision lives in [`docs/adr/`](docs/adr/README.md).

### Added

**Public API (`rtp-api`)** - new types include `RTPAPI`, `ILocationGenerator`, `GenerationContext` / `GenerationResult`, `RTPScheduler` / `TrackedRTPTask`, `RTPServerAccessor`, `RTPEconomy` *(experimental)*, `RTPCommandSender` / `RTPPlayer`, `RTPWorld` / `RTPChunk` / `ChunkSet`, `RTPCoords` (record) / `MutableRTPCoords`, `RTPLocation`, `MessagesKeys`, `RTPRunnable`, `PerformanceTracker`, and `ChunkReservation`. Precondition / postcondition / thread-safety Javadoc added to `RTPAPI`, `ILocationGenerator`, and `ChunkReservation`.

**Platform adapters** - restructured into versioned submodules with a shared `common` module (ADR-010):
- `rtp-folia`, `rtp-paper`, `rtp-spigot` each ship `-common`, `-v1_20_R1`, `-v1_21_R1`, `-v26_1_R1`.
- New `rtp-fabric` adapter (work in progress; see `docs/dev/MULTI_PLATFORM_PLAN.md`).

**Persistence** - `AbstractSQLDatabaseAccessor` foundation with concrete `H2`, `SQLite`, `MySQL`, `PostgreSQL` accessors, plus `RedisManager` and `YamlFileDatabase` fallback.

**Performance & scheduling** - `LockFreeLocationBuffer`, state-based scan/teleport tasks, per-region Folia rate limiting, per-platform pipelines, and `PerformanceTracker` instrumentation. Region-cache async callbacks and chunk-load wall-clock time are now attributed to `pluginMSPT`. `RTPCoords` is a record to reduce hot-path allocation.

**Commands** - `/rtp scan reset [region]` clears a region's `MemoryShape` bad-sector data without restarting a scan. New operator diagnostic `/rtp test chunk-probe-perf [samples=<n>]` A/B-times `RTPWorld.probeChunkColumn` against `RTPWorld.getChunkAtAsync` over a random sample of pregenerated chunks discovered by scanning the world's `region/` folder; reports pool size, totals, per-sample averages, the full/probe ratio, and the probe-null rate (BIOME_LOOKUP_PERF_PLAN phase 0). Now also times full-anvil `AnvilReader.readChunk` for pairwise comparison.

**Brigadier bridge in `commands-api` (commands-api-ADR-001, REQ-API-ARCH-005).** `BrigadierCommandAdapter` and `BrigadierBridgeContext<S>` (under `commands-api/.../brigadier/`) walk a `commands-api` `TreeCommand` and emit a Brigadier `LiteralArgumentBuilder` with literal sub-command nodes, typed argument nodes for `IntegerParameter` / `FloatParameter` / `BooleanParameter`, string-with-suggestions for `EnumParameter` / `CoordinateParameter` / unknowns, and `requires(...)`-gated permissions. Brigadier is `compileOnly` on `commands-api`, so Bukkit-family runtimes never load the adapter. New `RTPCmdFabric.register(...)` shim in `rtp-fabric-common` exposes a one-call hookup for the eventual Fabric `CommandRegistrationCallback`. Purely additive - `BukkitTreeCommand` remains the production dispatch path on Bukkit-family platforms. Covered by `ReqApiArch005BrigadierBridgeTest` (3 tests). The Fabric `CommandRegistrationCallback` wiring itself stays deferred to Phase 3 / 4 per `MULTI_PLATFORM_PLAN.md`.

**Documentation & governance**
- `docs/dev/REQUIREMENTS.md` with full REQ-ID coverage, including new `REQ-RTP-S-001..S-006` prohibitions cross-linked to `docs/admin/HAZARDS.md`.
- `docs/dev/TRACEABILITY.md` (69 REQ-IDs; addon requirements pending audit), `docs/dev/GLOSSARY.md`, `docs/dev/STAKEHOLDERS.md`, `docs/dev/CONCEPTS.md`, `docs/dev/DESIGN.md`, `docs/dev/COVERAGE_PLAN.md`, `docs/dev/MULTI_PLATFORM_PLAN.md`.
- `docs/adr/` - ADR-001 through ADR-013 and index (`README.md`); commands-api-ADR-001 (Brigadier bridge), ADR-015 (stale-chunk guard), ADR-016 (Anvil pre-filter) added later in the beta cycle.
- `docs/admin/` - `MIGRATION.md`, `QUICK_START.md`, `COMMANDS.md`, `CONFIGURATION.md`, `FAQ.md`, `HAZARDS.md` (H-001..H-010), `FAILURE_MODES.md` (FM-001..FM-009), `RUNBOOK.md` (7 incident scenarios).
- Audience landing pages: `docs/FOR_ADDON_DEVELOPERS.md`, `docs/FOR_CONTRIBUTORS.md`, `docs/FOR_SERVER_ADMINS.md`.
- Root-level `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, and `addons/REQUIREMENTS.md`.
- Module dependency diagram and ADR index link in `docs/dev/ARCHITECTURE.md`.

**CI / tooling** - Gradle workflow (`gradle.yml`) replaces Maven (`maven.yml`) on Java 21 with Spotless, JUnit publishing, and Jacoco upload; `check_traceability.sh` gate in Jenkins and GitHub Actions; `.github/dependabot.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, and `.github/ISSUE_TEMPLATE/config.yml`.

### Changed

- **Claim-plugin integrations folded into `rtp-plugin`** (ADR-019). The `RTP_ClaimPluginIntegrations` addon is removed; the eight checkers (Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard) now register at plugin startup in `ClaimIntegrations`. `integrations.yml` ships with the plugin jar; behaviour for REQ-RTP-S-003 is unchanged (verifiers still live behind `GlobalRegionVerifiers`). A new `addons/RTP_ExampleAddon` replaces the claim addon as the canonical "how to write an addon" template.
- `/rtp fill` ? `/rtp scan` (permission `rtp.fill` ? `rtp.scan`) for clarity.
- `CachedLocation` is now an immutable Java record (breaking for addons that mutated fields directly).
- `rtp-paper`: PaperLib dropped in favour of native Paper async chunk loading.
- Platform version targets upgraded to 26.1 (Spigot, Paper, Folia); Java target raised to **Java 21**.
- Active task tracking moved to `MemoryTracker` with `WeakReference` deallocation; write-through caching consolidated.
- Independent region queues now fill concurrently without a shared global-queue lock.
- `sendMessage` call-site tracking now uses stack trace source for diagnostics.
- Allocation reduced on hot paths (records, primitives, vectors removed); `MemoryShape` pending-write storage switched from maps to sets.
- `Region` decomposed for cohesion.
- **ScanTask diagnostics (BIOME_LOOKUP_PERF_PLAN.md PR-16).** Extended the `[DEBUG_LOG] ScanTask concurrency ...` line with `avgColdMissMs` (average `Files.readAllBytes` wall time per cache miss) and `gcDeltaMs` (cumulative GC time since previous log window) so operators can discriminate between OS page-cache pressure, GC churn, and driver-loop bottlenecks when scan throughput degrades with scan progress. Diagnostic-only, no behavior change.
- **ScanTask per-bin drain removed + stage-2 instrumentation (BIOME_LOOKUP_PERF_PLAN.md PR-17).** PR-16 runtime data ruled out I/O and GC as causes of cps degradation (both metrics stayed flat while `peakInFlight` dropped 50 ? ~25 over three minutes), pointing at driver-side pipeline bubbles. Removed the `inFlightGate.acquire(MAX_PENDING_CHUNKS)` drain between region-file bins in `ScanTask.run`: PR-15 coalescing already makes cross-bin cache interference cheap, so the barrier was costing saturation without earning correctness. Added `fullLoads=N fullLoadAvgMs=X.XX` to the gauge log (AtomicLong counters wrapped around `runFullLoadPath`'s result future) so operators can confirm whether stage-2 cost (loaded-chunk safety scan) grows with scan progress; if `fullLoadAvgMs` climbs while `avgColdMissMs` stays flat, the next optimization target is the full-load path, not the probe.
- **Biome-lookup performance plan (BIOME_LOOKUP_PERF_PLAN.md PR-1 through PR-12).** A sequence of commits retired `Region.maxBiomeChecksPerGen` + `PregenState.maxBiomeChecks` in favor of a single `maxAttempts` knob; added probe-first fast paths in `PregenTask` and `ScanTask` backed by `RTPWorld.probeChunkColumn` (overridden on Bukkit / Paper / Folia to read the chunk's center column directly from the persisted `r.X.Z.mca` via `AnvilReader.readColumnProbe`); wrapped the result through `AnvilColumnProbeAdapter` (applies `PaletteNormalizer::reconcile`); gated on `SafetyKeys.anvilPrefilterEnabled` + `isChunkLoaded`. New `LocationGenerator.FailTypes.prefilterBiome / prefilterBlock / prefilterRange` buckets attribute the rejections. `VerticalAdjustor` gained `requiresSkyLight()`. ScanTask reframes the probe as a load-replacement: on cache-miss the probe is authoritative (skip `getOrLoadChunk`); on cache-hit the safety radius upgrades to `max(configured, 2)` for free. Blocking I/O moved to a dedicated `AnvilIoPool` (`max(8, 2 * CPU)` daemon threads) to avoid `ForkJoinPool.commonPool` starvation. Added `AnvilRegionByteCache` (LRU of raw `.mca` bytes, mtime-invalidated) to eliminate redundant per-chunk `Files.readAllBytes` of the same ~4 MB region file; PR-11 added hit/miss/stale diagnostics surfaced in `ScanTask`'s existing `[DEBUG_LOG] ScanTask concurrency ...` line; PR-12 raised cache capacity from 4 to 16 (~64 MB steady-state) after PR-11 measurement showed the 4-entry LRU thrashing at ~0.31 hit rate because the scan frontier spans 6-10 distinct region files simultaneously. Measured `full/probe` ratio is 16.46x on Bukkit and 20.70x on Folia via `/rtp test chunk-probe-perf`.

- **Region shape cache key now folds shape and vertical-adjustor config (ADR-022).** Persisted `MemoryShape` data and `ScanTask` progress files were keyed only by `<regionName>_<worldSeed>`, so edits to `centerX/centerZ`, `minRadius/maxRadius`, `spatialResolution`, the shape class, or the vertical adjustor silently reused stale per-cell "bad" flags whose spiral mapping no longer matched the configured region. Filenames are now `<regionName>_<seed>_<12hex>.bin` / `.scan`, where the 12-hex suffix is a stable `SHA-256` of `(SCHEMA_VERSION, seed, shape FQCN + sorted shape data, vert FQCN + sorted vert data)`. The same hash, truncated to 64 bits, is written into the existing `rtp_cached_locations.seed BIGINT` column, so cached `RTPLocation` rows are invalidated under the same predicate without a database schema migration. `Region.setSettings(...)` deletes orphaned `.bin` / `.scan` files when the hash changes; `ScanTask.delete(regionName)` now sweeps every `<regionName>_*.scan` file. Region cache hydration moved off the calling thread onto `RTP.scheduler.runTaskAsynchronously` so DB I/O can no longer stall plugin init. Pre-release: legacy `.bin` files and existing rows are abandoned (no migration). `safety.yml` validity fields and biome filters are **not** yet folded into the hash; tracked as a follow-up entry in `docs/dev/POTENTIAL_BUGS.md`.

### Fixed

- **ScanTask probe-first fast path rejected passable non-air blocks (BIOME_LOOKUP_PERF_PLAN.md follow-up).** After the `isAirAt` reconciliation fix unblocked the anvil fast path, live scans still showed a residual `adjustNull` tail (~1300-1600 per gauge tick) whenever the center-column body/head cells held configured-passable blocks like tall grass, flowers, or snow layer. `JumpAdjustor.acceptProbeY` required strict vanilla-air (`isAirAt`) for Y and Y+1, so any chunk whose candidate Y had grass above stone was routed back through the full-load path. `safety.yml`'s `airBlocks` list documents exactly those passable materials but was wired only into the config enum - no code consumed it. Fix: `JumpAdjustor` now caches `SafetyKeys.airBlocks` next to `unsafeBlocks` (shared 5-second refresh in `refreshSafetySets`) and `acceptProbeY` accepts body/head as passable when vanilla air OR present in `airBlocks`, with `unsafeBlocks` taking precedence on conflicts. Tag-grammar tokens like `#minecraft:flowers` are stored verbatim and remain inert until the config reader's tag expansion slice lands (tracked in `docs/dev/SAFETY_TAGS_AND_STATES_PLAN.md` Slice 3). Regression guarded by `JumpAdjustorProbeTest.airBlocksFromConfig_acceptsTallGrassHeadSpace`.
- **ScanTask probe-first fast path silently vetoed by case-sensitive `isAirAt` (BIOME_LOOKUP_PERF_PLAN.md follow-up).** `AnvilColumnProbeAdapter.blockAt` reconciles every palette identifier through `PaletteNormalizer::reconcile`, which converts `"minecraft:air"` to `Material.AIR.name()` = `"AIR"` so that downstream `unsafeBlocks.contains(...)` checks (populated as `Material#name()` upper-case tokens like `LAVA`) match symmetrically. The default `ChunkColumnProbe.isAirAt` then compared the path segment against lowercase `"air"` / `"cave_air"` / `"void_air"` and returned false for every air block, making `JumpAdjustor.acceptProbeY` and `LinearAdjustor.acceptY` find no acceptable Y on any center column. Live runs showed `adjustNull == activeChecks` every gauge tick with `fullLoads == activeChecks`, negating the anvil pre-filter entirely and leaving scan throughput at the PR-17 baseline. Fix: override `isAirAt` in `AnvilColumnProbeAdapter` to recognize the reconciled `AIR` / `CAVE_AIR` / `VOID_AIR` forms in addition to the raw `minecraft:*` path segments. Regression guarded by `AnvilColumnProbeAdapterIsAirAtTest`.
- **Emergency landing platform spam** on kept-queue / DB-rehydrated teleports (Folia in particular). `TeleportPipelineTask.runTeleport` previously gated `RTPWorld.platform(...)` on `reservation == null`, which was always true for those paths. Gate is now a read-only safety check against the already-loaded landing chunk (`RTPWorld.getCachedChunk(...)` + `SafetyKeys.unsafeBlocks`); no chunk loads are triggered (REQ-RTP-S-005 preserved). `Region.execute` synthesizes a `ChunkReservation` for kept-queue pairs so REQ-RTP-S-002 stays intact. Default `safety.yml` now ships `platformRadius: -1` (disabled); set `platformRadius: 0` or higher to restore legacy behaviour.
- Chunk leak in region queue replenishment and in early `ChunkReservation` integration.
- Memory leak in active task tracking.
- Immediate-teleport timing regression and duplicate task execution on queue drain.
- `MemoryShape` caching inconsistencies and region-boundary locational edge cases.
- Erroneous 2D coordinate check that rejected valid surface locations.
- **On-event teleports granted to operators by default (Paper + LuckPerms).** `ParsePermissions.hasPerm` called `sender.hasPermission(...)` directly for each candidate child node. On Paper with LuckPerms (and any permission manager that defers to op state for unset nodes), `hasPermission` short-circuits to `true` for ops regardless of the registered `default: false` we declared in `plugin.yml`. The result was that every registered on-event handler (`firstjoin`, `join`, `respawn`, `changeworld`, `move`, `teleport`) auto-fired for any op the moment they triggered the event, even when the admin had explicitly not granted the corresponding `rtp.onevent.*` node. Fix: rewrote `ParsePermissions.hasPerm` to iterate the sender's *effective* permissions (`sender.getEffectivePermissions()`, which only contains explicitly granted nodes) and match each candidate child against that set - the same pattern already used by `ParsePermissions.getInt` for numeric permission tiers. This bypasses the permission manager's op-default short-circuit entirely, restoring the documented opt-in `default: false` behaviour. The earlier removal of the `permissionPrefix + "*"` wildcard fast-path is retained on general principles but was not load-bearing on its own.
- **`Lifetime Chunks Loaded` (`/rtp info`) double-counted every live chunk load.** `RTPWorld.getOrLoadChunk` (added in the ADR-016 Â§13.1 follow-up) calls `getChunkAt` (probe entry) and, on UNKNOWN, `getChunkAtAsync` (live entry) for the same logical attempt. Every adapter incremented `RTPWorld.totalChunkLoads` from `getChunkAt`, and on Spigot/Fabric `getChunkAtAsync` delegated back through `getChunkAt`, so each cache-miss load was counted twice (~2.09Ã— ratio observed in the field: `cached: 110`, `Lifetime Chunks Loaded: 230`). The counter is now incremented exactly once per live load, in the live-load path itself: `BukkitRTPWorld#loadChunkFuture`, `FoliaRTPWorld#loadLiveChunk` and `FoliaRTPWorld#getChunkAtAsync`, and inside the `MinecraftServer#submit` body of `FabricRTPWorld#getChunkAt`. Probe-only paths (anvil cache hits) and kept-cache replays no longer contribute. Operators upgrading will see `infoTotalLoads` drop to roughly half its previous value - this is the corrected reading, not a regression. The `leakRate` placeholder is unaffected (still divided by `lifetimeTicketsIssued`). Javadoc on `RTPWorld.totalChunkLoads` updated to document the new "actual live loads only" semantics.
- Initial command execution failure on first plugin load.

---

## [2.0.18] - 2024-01-01

> Last 2.x release. All changes since this tag are captured under `[3.0.0-beta.1]` above.
>
> **Upgrading from 2.0.18?** See [MIGRATION.md](docs/admin/MIGRATION.md#upgrading-to-300-beta).

---

## [2.0.x] - Historical

Earlier versions introduced the multi-module split (`rtp-api` / `rtp-core` / platform adapters), the asynchronous queue system, the Archimedean spiral shape algorithm, and the `MemoryShape` persistent cache. Per-commit history is available via `git log`.

---

[3.0.0-beta.1]: https://github.com/DailyStruggle/RTP/compare/v2.0.18...v3.0.0-beta.1
[2.0.18]: https://github.com/DailyStruggle/RTP/releases/tag/v2.0.18

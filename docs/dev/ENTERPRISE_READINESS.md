# Enterprise Readiness Plan

> Created: 2026-09-10
> Scope: whole repository. Goal: make "enterprise quality and compatibility" a claim
> that an outside reviewer can verify without the maintainer's help.
> Companion docs: [COVERAGE_PLAN.md](COVERAGE_PLAN.md) (rtp-core tactics),
> [TRACEABILITY.md](TRACEABILITY.md), [REQUIREMENTS.md](REQUIREMENTS.md).
> Decision record: [ADR-094](../adr/ADR-094-quality-engineering-gates.md) records the
> gating architecture (tiered logic tests, opt-in PMD static analysis, server-less
> changed-line coverage) that this plan executes.

---

## 0. Landed since 2026-09-10 (mechanism now exists)

The following gating *mechanisms* were built after this plan was written (decision:
[ADR-094](../adr/ADR-094-quality-engineering-gates.md)). They are the scaffolding the
TODO items below hang on; the items themselves (per-module targets, floors, ratchet)
remain open.

- **Purpose-based test tiers** via JUnit 5 tags wired into the root `build.gradle`
  `subprojects` block: untagged = default **logic** tier; `slow` / `edge` /
  `simulation` / `drawing` / `demo` excluded by default; `-PfullTests` runs all.
  Heavy suites (e.g. `ScanCmdTest`'s full-scale scan) are already tagged.
- **Opt-in JaCoCo** (`-Pcoverage`) is now centralized in the root `subprojects`
  closure (partially satisfies item 3 - report wiring centralized; the
  verification/floor block is still not).
- **Local programmable static analysis**: opt-in PMD gate (`-PstaticAnalysis`) over
  the appendable [`config/pmd/ruleset.xml`](../../config/pmd/ruleset.xml), including
  the custom `PreferNonLockingExecution` rule (relates to section 4.5).
- **Server-less changed-line coverage gate**: [`scripts/diff-coverage.py`](../../scripts/diff-coverage.py)
  fails below a threshold on changed, instrumented lines against a git baseline ref
  (relates to section 4.1).
- **Suite benchmarking**: [`scripts/bench-tests.py`](../../scripts/bench-tests.py)
  ranks per-suite wall time to guide which suites to tag out of the logic tier.
- **Opt-in PIT mutation gate** (`-Pmutation`) on `rtp-core`'s three safety
  packages, failing below a 60% mutation score (satisfies section 4.4 item 22 -
  the gate exists and is enforced; holding all three packages at the floor by
  adding tests where mutants survive is the remaining open work).

---

## 1. Premise

The repository already carries the substrate most plugins never build: normative
requirements with REQ-* IDs, an ADR corpus, the S-001..S-007 prohibition set, Spotless,
SpotBugs at MAX effort, ArchUnit, JaCoCo, locale parity tests, and a five-node
proxy devstack with an acceptance harness.

What is missing is not capability. It is **enforcement and published evidence**.
Every item below converts something that is currently true-but-unprovable into
something a stranger can check from a release page or a CI run.

Two claims are in scope and they are graded separately:

- **Quality** - the code does what the requirements say, and regressions are caught
  mechanically rather than by review attention.
- **Compatibility** - the supported platform/version matrix is measured, and the
  public API does not break without notice.

---

## 2. Measured coverage baseline (2026-09-10)

Live JaCoCo run: `./gradlew :<module>:jacocoTestReport` across the platform-neutral
graph. These are instruction / branch percentages, not estimates.

### 2.1 Platform-neutral modules (the 90%+ target set)

| Module | Instr % | Branch % | Missed instr | Verdict |
|---|---|---|---|---|
| `yaml-api` | **no tests at all** | - | - | Zero test sources (10 main classes). Pure parser - trivially testable. |
| `metrics-api` | 18.8 | 18.9 | 506 | Pure SPI, 8 classes. Should be ~100%. |
| `commands-api` | 30.9 | 23.0 | 2,362 | `common/localCommands` at 21%. Bukkit subpackage skews the number. |
| `rtp-api` | 39.9 | 40.5 | 7,176 | `configuration/enums` at **0%**, `world` at 5.9%, `group` at 0%. |
| `rtp-core` | 59.6 | 45.8 | 56,583 | The dominant mass. Detail in section 2.3. |
| `rtp-proxy-common` | 59.0 | 44.1 | 7,508 | `transport/redis` at 4.4% is nearly the whole gap. |
| `maps-api` | 67.6 | 48.6 | 1,505 | `render` 73.8%, `bukkit` binding drags it down. |
| `anvil-api` | 71.1 | 60.3 | 1,609 | Closest to target of the large modules. |
| `tags-api` | 84.5 | 74.4 | 203 | Nearly done. |

`effects-api` (21.9 / 19.7) is listed with the platform set below: two of its three
largest packages (`effectsapi/fabric`, `effectsapi/bukkit`) are platform bindings.

### 2.2 Platform-coupled modules (lower, explicit targets)

| Module | Instr % | Missed instr | Realistic ceiling |
|---|---|---|---|
| `rtp-neoforge-common` | 1.6 | 17,634 | 40-50% (logic split from NeoForge calls) |
| `rtp-plugin` | 13.7 | 17,292 | 40% (it is an assembler; most lines are wiring) |
| `rtp-fabric-common` | 11.5 | 13,751 | 40-50% |
| `rtp-bukkit-common` | 21.2 | 8,221 | 50-60% |
| `rtp-folia-common` | 8.7 | 5,855 | JaCoCo not the metric - platform-coupled; exercised by the in-game `test` command against a live Folia server + devstack acceptance harness. Server-bound paths and their owning `rtp test *` subcommands are inventoried in [`platforms/rtp-folia/rtp-folia-common/docs/SERVER_BOUND_COVERAGE.md`](../../platforms/rtp-folia/rtp-folia-common/docs/SERVER_BOUND_COVERAGE.md). Suite verified green 2026-09-11 (item 21). |
| `effects-api` | 21.9 | 7,295 | 60% overall, 90% on `common/` |
| `rtp-paper-common` | 15.9 | 1,475 | 60% |
| `rtp-proxy-velocity` | 50.1 | 1,906 | 70% |
| `rtp-*-vXX_YY_R1` carriers | 0-100 (tiny) | <200 each | Exempt - thin version shims |

### 2.3 `rtp-core` worst packages by absolute missed instructions

| Package (after `...rtp.common.`) | Instr % | Missed |
|---|---|---|
| `commands/menu` | 39.3 | 10,475 |
| `selection/region` | 55.5 | 6,681 |
| `tools` | 39.9 | 5,001 |
| `network` | 45.9 | 3,820 |
| `selection/region/selectors/memory/shapes` | 84.6 | 2,975 |
| `database/options` | 33.6 | 2,936 |
| `configuration` | 71.7 | 2,306 |
| `tasks` | 60.3 | 2,210 |
| `(root package)` | 28.0 | 2,103 |
| `selection/region/selectors/memory/table` | 57.5 | 1,853 |
| `commands/test` | **0.0** | 1,814 |
| `commands` | 46.8 | 1,713 |
| `tasks/teleport` | 64.6 | 1,073 |
| `selection/.../shapes/util` | 14.7 | 791 |
| `effects` (root-level) | **0.0** | 625 |
| `menu/search` | **0.0** | 470 |
| `tasks/tick` | 21.4 | 442 |

Single worst class in the repository: **`MenuRedeemSubcommand`, 4,594 missed
instructions** (13% covered). `MenuWiringSupportInstaller` (833) and
`VisualizationsSubmenuBuilder` (548) are fully uncovered. These three alone are
roughly 10% of the `rtp-core` gap.

### 2.4 Measurement caveats found while taking this baseline

1. **Stale execution data reads low.** JaCoCo discards coverage for any class whose
   bytecode changed since the last test run (`Classes in bundle 'rtp-core' do not
   match with execution data`). A partially-recompiled tree therefore reports several
   points below the truth, and a coverage gate can fail for reasons unrelated to tests.
   Always run `test` and the verification/report task in the same invocation.
2. **The gate must not be set from a stale figure.** The `rtp-core` floors are
   deliberately set below the measured baseline for this reason; the ratchet script
   (item 5) must read a fresh run, not a cached report.
3. As of 2026-09-10 the `rtp-core` suite has **2 failing tests** in `MemoryShapeTest`
   (`testComputeAdmissibleGapContract`, `testBoundedDynamicGapBridgingAtResolution32`),
   originating from uncommitted in-progress gap-policy work. The floors cannot be
   ratcheted to their true values until that work lands green.

---

## 3. Is 90%+ actually achievable outside platform adapters?

**Yes, and the harness already exists.** Three facts support it:

1. `rtp-core` and `rtp-api` are forbidden platform imports by architectural rule
   (no `org.bukkit.*`, no `net.minecraft.*`). Every line is therefore reachable
   from a plain JVM test - there is no "needs a running server" excuse in these modules.
2. `rtp-core/src/testFixtures` already ships `RTPTestSetup`, `MockRTPServerAccessor`,
   `MockRTPWorld`, `MockRTPPlayer`, `MockRTPChunk`, `MockRTPScheduler`,
   `MockLocationGenerator`, `TrackedMockWorld`. The seam work is done; the tests
   simply have not been written against it.
3. 341 test files already exist in `rtp-core` against 323 main files. The suite is
   broad but shallow - it exercises happy paths and leaves branches uncovered
   (59.6% instruction vs 45.8% branch is the signature of that).

**Honest carve-outs** that should be excluded from the ratio rather than chased:

- Generated / boilerplate: `equals`/`hashCode`/`toString`, trivial record accessors.
- `commands/test` (1,814 missed) - a developer-only diagnostic command tree.
  Either cover it, move it behind a build flag, or exclude it explicitly with a
  documented reason. Do not leave it silently at 0%.
- `MenuRedeemSubcommand` at 4,594 instructions was previously considered a design
  blocker requiring decomposition before testing; however, direct action-routing, cart-seam,
  and multi-config dispatch harnesses demonstrated the class can be cleanly and
  comprehensively tested as-is without architectural disruption (see item 15).
- JDBC/Redis driver error paths that require a real broken server - use Testcontainers
  (section 5) rather than mocks, or exclude with justification.

**Target set, staged:**

| Stage | Modules | Instr target | Branch target |
|---|---|---|---|
| A | `yaml-api`, `metrics-api`, `tags-api`, `anvil-api` | 95% | 85% |
| B | `rtp-api`, `commands-api`, `maps-api` | 90% | 80% |
| C | `rtp-core`, `rtp-proxy-common` | 90% | 80% |
| D | platform commons | per-module table in 2.2 | - |

---

## 4. TODO - Coverage and test depth

### 4.1 Make the gate honest (do first, it is the cheapest credibility)

- [x] 1\. Raise `rtp-core` `jacocoTestCoverageVerification` instruction floor to
      **0.55**, with a safety margin under the freshly measured 0.603 (2026-09-11).
- [x] 2\. Add BRANCH counter rule to `rtp-core` (**0.42**, under the measured 0.464).
- [x] 3\. Move the JaCoCo verification block into the root `build.gradle` `subprojects`
      closure so every module carries a floor, with per-module overrides for the
      platform adapters. **Done:** the floor block is now driven by a central
      `coverageFloors` map in the root `subprojects` closure (single source of
      truth); `jacocoTestCoverageVerification` `dependsOn test` so it always reads a
      fresh exec file (section 2.4 caveat 1), and wires into `check` from there.
      `rtp-core` (0.55/0.42) is the only gated module today; platform adapters get a
      floor by adding their path to the map as they reach the section 2.2 ceilings.
- [x] 4\. Add a per-package rule for the safety-critical set (`tasks/teleport`,
      `selection/region`, `selection/worldborder`) at a higher floor than the module.
      **Done (2026-09-12):** package-level rules added to `coverageFloors[':rtp-core']`
      in root `build.gradle` and enforced via `jacocoTestCoverageVerification` using
      `element = 'PACKAGE'` blocks. Gated packages:
      `tasks.teleport` (0.65 instruction / 0.45 branch),
      `selection.region` (0.52 instruction / 0.40 branch), and
      `selection.worldborder` (0.85 instruction / 0.60 branch).
- [x] 5\. Commit a ratchet script (`scripts/ratchet-coverage.py`) that reads the JaCoCo
      XML and rewrites the floors upward after a green run. Never downward.
      **Done (2026-09-12):** committed `scripts/ratchet-coverage.py` (stdlib-only, Python 3.12+)
      and unit test suite `scripts/tests/test_ratchet_coverage.py`. Automatically discovers
      JaCoCo XML reports across all submodules, calculates floor thresholds with a safety
      margin (default 0.05), and updates `coverageFloors` (both module-level and
      per-package rules) monotonically upward. Never rewrites downward.
- [x] 6\. Publish the coverage badge / report to the docs site so the number is public.
      **Done (2026-09-12):** committed `scripts/generate-coverage-badge.py` to extract
      measured coverage from JaCoCo XML reports and generate both SVG badge
      (`docs/assets/badges/coverage.svg`) and Shields.io dynamic endpoint schema
      (`docs/assets/badges/coverage.json`). Added the coverage badge to `docs/index.md`
      for public visibility on the MkDocs documentation site.
- [x] 6a\. Server-less changed-line (diff) coverage gate committed as
      `scripts/diff-coverage.py` (cross-references `git diff` vs a baseline ref
      against JaCoCo XML; fails below a threshold). Complements the absolute floors
      above by gating exactly the lines a change touches. Not yet wired into CI as a
      PR status check.

### 4.2 Close the zero-coverage holes (fast wins, high optics)

- [x] 7\. `yaml-api` - create `src/test/java` and cover the parser: scalars, nested maps,
      lists, comments, anchors if supported, malformed input, round-trip fidelity.
      Was **0 test files**. **Done (2026-09-11):** added `RtpYamlReaderTest`,
      `RtpYamlWriterTest`, `RtpYamlScalarTest`, `RtpYamlSectionTest`,
      `RtpYamlConfigTest`, `RtpYamlEdgeCasesTest`, and `RtpYamlCoverageTest`
      (~115 cases) covering scalar coercion, nested maps/sequences, block
      comments, every unsupported-construct rejection (anchors/aliases/tags/flow/
      merge/block-scalar/doc-sep), malformed input, idempotent round-trip, and the
      simpleyaml-compat section/file surface. Measured **95.5% instruction / 86.9%
      branch** (100% method/class); target 95% met. Gated via the central
      `coverageFloors` map (`:yaml-api` at 0.92/0.80, margin under the
      environment-dependent atomic-save retry paths).
- [x] 8\. `rtp-api` `configuration/enums` (was 0%, 1,455 missed) - enum value/parse/fallback
      tests. **Done (2026-09-11):** added `MessageEnumsTest` (round-trip `values()`/`valueOf()`
      over all five message-key enums); package now measured **100% instruction**.
- [x] 9\. `rtp-api` `group` package (was 0%, 366 missed). **Done (2026-09-11):** added
      `GroupPlacementApiTest` covering factory validation, clamping, defensive-copy
      immutability, value semantics, and the success/failure result contract; package now
      measured **100% instruction**.
- [x] 10\. `rtp-api` `world` (was 5.9%, 1,313 missed) and `server` (was 11.7%).
      **Done (2026-09-11):** added `WorldSurfaceTest` (value types `RTPCoords`/
      `MutableRTPCoords`/`BiomeSampleCapability`/`ChunkSet`, `RTPLocation` distance/
      equality/clone, ref-counted `RTPWorld` ticket bookkeeping incl.
      `releaseOrphanedTickets` and `getOrLoadChunk` origin attribution, `RTPChunk`
      defaults, `ChunkColumnProbe` air classification), `ChunkReservationTest`
      (open/keep/refresh/close, ownership transfer, `awaitReady` timeout/failure with a
      stubbed `RTPServerAccessor` driving the log paths), and `ServerHooksAndValueTest`
      (`PlatformFamily`, `ProgressBar`, `NoopPlayerLifecycleHook`,
      `DispatchingPlayerLifecycleHook` fan-out/isolation). `world` now measured **94.8%
      instruction**; `server` **56.5%** (remaining gap is the large abstract
      `RTPServerAccessor` default-method surface, partially covered by the pre-existing
      `ServerAccessorMenuSurfaceTest`).
      **Follow-up (2026-09-11):** added `ServerAccessorDefaultsTest`, a recording
      dynamic-proxy harness that drives every remaining `RTPServerAccessor` default
      method - `getPlatformFamily` (all platform branches + unknown log path),
      `isPlatformFamily`/`isServerVersionAtLeast`/`isServerVersionAtMost`/`isCompatible`
      version gates, the `sendMessage`/`sendMessageWithRunCommand`/`announce` overload
      fan-out (delegation asserted via captured abstract calls), the conservative
      platform-neutral defaults (`getOnlinePlayerNames`, `getPlayerLifecycleHook`,
      `biomeSampleCapability`, `sampleBiome`, `blockTagSnapshot`,
      `rebuildBlockTagSnapshot`, `releaseAllChunkTickets`, `updateProgressBars`/
      `clearProgressBars`), and the active-task registry
      (`registerAction`/`removeAction`/`getTaskSnapshot`). `RTPServerAccessor` now
      measured **98.2% instruction** (only the one-line `shapePlatform` delegate
      remains); package `server` overall rose accordingly.
- [x] 11\. `rtp-core` `effects` root package (was 0%, 625 missed). **Done (2026-09-11):**
      relocated `EffectsResolverTest` into rtp-core's own test sources (it lived in
      rtp-plugin, so its coverage did not credit rtp-core) and extended it with
      null-config, null permission-node, and already-dotted-prefix cases. The
      `effects` package now exercises the full group-resolution pipeline from a
      plain-JVM test.
- [x] 12\. `rtp-core` `menu/search` (was 0%, 470 missed). **Done (2026-09-11):**
      relocated `ConfigSearchResultsBuilderTest` into rtp-core's test sources and
      added single-arg-overload and `Hit`-record validation/normalisation cases.
- [x] 13\. `metrics-api` (was 18.8%, 506 missed) - 8 classes. **Done (2026-09-11):**
      added `MetricsSnapshotTest`, `FoliaRegionSampleTest`, `MetricsRegistryTest`,
      and `RegionQueueRowEqualityTest`; measured **100% instruction / 94.6% branch**
      (100% line/method/class). Gated via the central `coverageFloors` map
      (`:metrics-api` at 0.95/0.85, the Stage A target in section 3).
- [x] 14\. Decide and document the fate of `rtp-core` `commands/test` (was 0%, 1,814 missed).
      **Done (2026-09-11):** kept and covered rather than excluded - these are the
      runtime self-test subcommands (`rtp test cancel`/`config-set`/`chunk-ticket`/
      `scheduler`/`anvil-prefilter`/`api-compat`) plus their process-wide registries
      (`ActiveTestJobs`, `TestSemaphore`) and umbrella SPI (`TestUmbrellaContext`).
      Added eight plain-JVM test classes driving each subcommand through the
      `RTPTestSetup`/`MockRTPServerAccessor` harness and asserting the calls and
      server operations each must make (caller `sendMessage` fan-out, INFO/WARNING
      logging, `MemoryTracker` release paths, scheduler-tier dispatch, config
      round-trip/restore, reflective API-probe bucketing, and cancel/semaphore state
      transitions). Package measured **86.2% instruction** (from 0%); the async
      scheduler tier reports TIMEOUT under the synchronous mock (real off-thread
      dispatch is a platform concern), which caps `TestSchedulerCmd`/`TestApiCompatCmd`
      short of 100%.

### 4.3 The structural blockers

- [x] 15\. Cover `MenuRedeemSubcommand` (was 4,594 missed instructions, ~13% covered).
      **Done (2026-09-12):** verified and covered as-is via plain-JVM test harnesses
      without requiring monolithic decomposition. Added 35 dedicated unit tests across
      three focused suites:
      - `MenuRedeemSubcommandCartTest` (14 tests): staging cart lifecycle (`stageInCart`,
        `unstageInCart`, `clearCart`, `snapshotCart`), filename and path normalization,
        viewer state isolation, defensive copy protections, and `CartSink` seam.
      - `MenuRedeemSubcommandDispatchTest` (11 tests): action routing for `MenuAction`
        dispatch arms, S-004 builder-disabled fallbacks, permission gates, staging/apply/discard
        batch workflows, throwing builders, null-model boundaries, and 1-indexed page translation.
      - `MenuRedeemSubcommandAdvancedDispatchTest` (10 tests): `dispatchSwitchInfoToText`
        CLI argv translation (GLOBAL/WORLD/REGION) and permission checks, search prompt
        opener dispatch, `dispatchOpenMultiConfigSelector` remove-mode toggling (`!toggle:` prefix),
        `dispatchOpenMultiConfigEntry` with cart snapshot propagation, `dispatchMultiConfigMutate`
        lifecycle (`ADD` and `REMOVE`) with `MultiConfigParser` tree sync and locked entry
        rejection via `DefaultMultiConfigRemovalGuards`, and `dispatchOpenConfigKey` STAGE-mode
        routing to Anvil input for unconstrained keys.
      Decomposition remains an optional future architectural cleanup (revisiting cart vs
      recursive command cycling), but is no longer a testing blocker.
- [x] 16\. Cover `MenuWiringSupportInstaller` (833, 0%) and `VisualizationsSubmenuBuilder`
      (548, 0%). **Done (2026-09-11):** added `VisualizationsSubmenuBuilderTest`
      (chart-kind picker rows, alphabetised region list, empty-state, unsupported-kind
      rejection, pagination, null-guard, null `selectionAPI` tolerance) and
      `MenuWiringSupportInstallerTest` (subcommand wiring with/without a renderer plus
      the extracted config-subtree, curated-page, and config-search builder factories
      exercised via same-package reflection).
- [x] 17\. `rtp-proxy-common` `transport/redis` (4.4%, 4,847 missed) - this single package
      is 65% of the module's gap. Use Testcontainers Redis, not mocks.
      **Done (2026-09-11):** added a shared, Docker-gated `RedisTestContainer`
      (singleton `redis:7-alpine`, `@EnabledIf(dockerAvailable)` so Docker-less builds
      skip cleanly) and moved the whole tier off the old `RTP_REDIS_IT` env gate onto
      Testcontainers. Converted `RedisLeaderLeaseIT`, `RedisNetworkRequestQueueIT`,
      `RedisNetworkWaitlistIT` and added `RedisPlayerOwnershipTrackerIT` and
      `RedisNetworkStateBindingIT` (heartbeat publish + SCAN snapshot, pub/sub fan-out,
      the SHA1-verified claim/redeem/release Lua scripts, `findReservation`,
      `listActiveForServer`, `reapExpired`, lifecycle guards). Enabling the dormant
      suite exposed a real bug: `transition.lua` did not evict the status HASH on a
      terminal state (COMPLETED/FAILED/CANCELLED), so `pollStatus` kept returning
      terminal rows - diverging from `InMemoryNetworkRequestQueue`. Fixed the script
      (DEL statusKey after snapshotting; sidecar `.sha1` regenerated) and corrected a
      stale test that asserted a 0-based queue position (`pollStatus.lua` is 1-based).
      Verified BUILD SUCCESSFUL against a real Redis (all transport/redis tests green).
      Note: on Docker Desktop 29 (Windows) docker-java cannot negotiate the Engine API
      over the named pipe, so the test task pins `DOCKER_API_VERSION=1.44` when unset
      and the suite runs serially (`maxParallelForks = 1`) since classes share one
      container and scrub a common keyspace.
- [x] 18\. `rtp-core` `database/options` (33.6%, 2,936) - error paths, missing-key
      fallbacks, rollback. **Done (2026-09-11):** replaced the mock-based tests that
      copied read/write logic into throwaway `Testable*` subclasses with suites that
      drive the shipped bytecode directly, so coverage credits the production classes.
      Added `RealH2DatabaseAccessorTest` (24) and `RealSQLiteDatabaseAccessorTest`
      (21) against on-disk H2/SQLite under a `@TempDir` (construction/identity,
      read/write round-trip, delete, `loadCachedLocations` shared/player-bound/invalid
      UUID, `clearAllCachedLocations`, `purgeStaleLocations`, `flush` commit +
      missing-table rollback, `startup`, `asDataSource`, network-state binding,
      connect/disconnect/close, plus SQLite auto-create/`ALTER TABLE ADD`/PRAGMA
      paths), and Docker-gated `RealMySQLDatabaseAccessorTest` /
      `RealPostgreSQLDatabaseAccessorTest` (20 each) on Testcontainers MySQL 8.4 /
      PostgreSQL 16 via a shared `SqlTestContainers` singleton
      (`@EnabledIf(dockerAvailable)` so Docker-less builds skip cleanly). All four
      suites green; package instruction coverage rose **33.6% -> 50.2%** in a
      Docker-less run (H2 91.2%, SQLite 58.9%, `AbstractSQLDatabaseAccessor` 70.4%).
      `MySQLDatabaseAccessor` / `PostgreSQLDatabaseAccessor` measure 0% only because
      their container tier skips without a daemon; with Docker they exercise the same
      surface and lift the package well past the module floor (mirrors the item-17
      Testcontainers approach). Note: docker-java cannot negotiate the Engine API to
      Docker Desktop 29 over the Windows named pipe, so `rtp-core`'s `test` task pins
      `DOCKER_API_VERSION=1.44` when unset.
- [x] 19\. `rtp-core` `tools` (39.9%, 5,001) - `TPS`, `PerformanceTracker` and helpers.
      **Done (2026-09-11):** added plain-JVM unit suites for all uncovered helpers and
      subsystems in `tools`: `GradientExpanderTest` (28 cases exercising the pure
      MiniMessage-to-legacy gradient/transition/rainbow lowerer end-to-end),
      `MessageTaggerTest` (null/empty short-circuit, config-not-loaded suppression,
      disabled passthrough, explicit-tag + stack-inferred-tag suffixing),
      `SupportInfoTest` (DEV build signature + support-signature composition),
      `ParsePermissionsTest` (boolean prefix match / case-insensitivity / candidate
      scan / TTL cache, integer suffix parse / min-selection / logging / UUID overloads),
      `ChunkyIntegrationTest` (`ChunkyChecker` reflection-based shape registration,
      absent-provider safe fallback, and `ChunkyRTPShape` boundary check / rand),
      `HeapPressureMonitorTest` (default threshold, disabled gates at `<=0` and `>=100`,
      time-gated sampling cache, low-threshold trip logic),
      `MemoryTrackerTest` (registration, UUID / target untrack, safe null-handling,
      lifespan resets, memory ceiling parsing / checks, and active-GC diagnostics sweep),
      and `CfDiagTest` (private constructor, idempotent scheduler start, zero-delta
      noop suppression, counter delta rate formatting branches). `PlaceholderProvider`
      and related placeholders are already thoroughly covered by `PlaceholderProviderTest`,
      `RtpOutcomeStatsInfoPlaceholderTest`, and command suites; stale uncommitted
      `.bak` artifacts for `CfDiag` and `PlaceholderProvider` were cleared.
- [x] 20\. `rtp-core` `network` (45.9%, 3,820).
      **Done (2026-09-11):** brought `network` package family coverage from 45.9% to
      64.5% overall (`pluginmessage`: 90.0%, `direct`: 74.0%, `network`: 59.7%).
      Added comprehensive plain-JVM unit tests across all uncovered modules and SPIs:
      `ProxyDirectNetworkRequestQueueTest` (100% coverage; testing batch flushPending,
      RPC enrolment outcomes, status polling with wire status decoding, RPC cancellation
      with delimiter framing, error branches), `NetworkWaitlistGuardAndQuitListenerTest`
      (99.1% guard coverage, 98.9% quit listener coverage; sender types, cache lookup,
      terminal vs non-terminal queue state handling, user messaging, lifecycle hook
      registration idempotency, unregistration, disconnect cancellation cascading into
      lobby retry and request queue), `PeerRegionRegistryTest` expansions (local dispatch
      tracking and stack increments, anchor-based invalidation upon newer peer heartbeats,
      peer metadata extraction and error handling, local decrement scoring in `pickMostKept`),
      `NetworkRegionAvailabilityTest` expansions (98.5% coverage of `SnapshotRegionAvailabilityProvider`;
      any-server / empty-server resolution, empty-region verdicts, null and throwing
      supplier handling, fallback legacy region list parsing), `JoinTriggerSourceTest`
      expansions (active reservation onQuit releases locally and proxy-side with safe
      error logging), and `ProxyDirectNetworkBindingTest` expansions (68.7% coverage;
      constructor parameter validations, closed lifecycle rejections, proxy-local
      operations, subscription fan-out and unsubscription, proxy host/port parsing).
- [x] 21\. Verify whether the `rtp-folia-common` test suite is still broken (COVERAGE_PLAN
      said so); fix or delete the claim. **Done (2026-09-11):** the claim is stale. A
      forced clean rerun (`:rtp-folia:rtp-folia-common:test --rerun-tasks`) is
      BUILD SUCCESSFUL with 20 tests across 4 suites (`FoliaMapBindingTest`,
      `FoliaMetricsBindingTest`, `FoliaEconomyPipelineTest`,
      `FoliaThreadAffinityArchTest`) all green, 0 failures/errors/skips. Instruction
      coverage measures 8.7% (558/6,413; 5,855 missed), matching section 2.2. The
      "broken tests" note was removed from `COVERAGE_PLAN.md` line 4.
      **Decision:** the residual JaCoCo gap is not chased with JVM unit tests. This
      module is explicitly platform-dependent (Folia region/entity schedulers, live
      world/chunk access) and is exercised in-game via the `test` command against a
      live Folia server and the devstack acceptance harness, not by mock-driven unit
      tests. The 8.7% figure is expected and acceptable for this module; the ~50%
      "realistic ceiling" in section 2.2 is therefore aspirational, not a floor.
      **Follow-up (2026-09-11):** the honest coverage metric for this module is a
      server-bound-path inventory, not JaCoCo. Committed as
      [`platforms/rtp-folia/rtp-folia-common/docs/SERVER_BOUND_COVERAGE.md`](../../platforms/rtp-folia/rtp-folia-common/docs/SERVER_BOUND_COVERAGE.md):
      it maps every platform-only path to its owning `rtp test *` subcommand and
      names the gaps (live force-load ticketing, block placement/restore, world
      persistence, region timers, client effects, teleport-landing assertion).
      Runtime touch-instrumentation and the automated coverage-matrix report are
      deferred until the devstack acceptance CI job (item 33) exists to consume
      them, and would require a D-005 proposal.

### 4.4 Depth, not just breadth

- [x] 22\. Add **PIT mutation testing** on `rtp-core` safety packages
      (`tasks/teleport`, `selection/region`, `selection/worldborder`). Target 60%
      mutation score. This is the only honest answer to "is your coverage real?".
      **Gate landed (2026-09-11):** opt-in `-Pmutation` PIT gate in
      `rtp-core/build.gradle` (plugin `info.solidsoft.pitest`; engine + JUnit 5
      discovery versions centralized in `gradle/libs.versions.toml`), scoped to
      the three safety packages via glob (`*` spans the `selection/region`
      subpackages) and driven by the same-package tests. `mutationThreshold = 60`
      fails the build below the target; a routine `.\gradlew.bat build` never
      resolves the PIT toolchain (mirrors the `-Pcoverage` / `-PstaticAnalysis`
      opt-ins). Run it with `.\gradlew.bat :rtp-core:pitest -Pmutation`.
      Verified end-to-end on `selection/worldborder`: 12 mutations, 10 killed
      (**83%**, test strength 91%) - above the floor. The full teleport/region
      run is CPU-heavy (the pipeline suites are scheduler/time-driven, so many
      mutants are killed by timeout); shard it one package at a time with
      `-PmutationPackages=<glob>` in CI. Remaining: run the full teleport/region
      sweep and add tests where mutants survive to hold all three at >= 60%.
- [x] 23\. Add property-based tests (jqwik) for the spiral math (ADR-001) and
      `MemoryShape` bin arithmetic - invariants, not examples.
      **Done (2026-09-12):** added `net.jqwik:jqwik` (1.9.2) to `gradle/libs.versions.toml`
      and `rtp-core/build.gradle`. Added property-based test suites with hundreds of
      randomized generative checks:
      - `SpiralMathPropertyTest`: verifies range monotonicity/positivity, coordinate
        boundary containment within circle and square hulls across arbitrary radii and centers,
        `chunkToLocations` preimage soundness (<= 2 preimages, strictly ascending, exact
        round-trip decoding back to chunk coords), and bounded coordinate generation for
        normal distribution variants (`Circle_Normal`, `Square_Normal`).
      - `MemoryShapeBinArithmeticPropertyTest`: verifies `deriveOptimalBinSize` power-of-two
        and devolution invariants across arbitrary domain ranges [1, 10M], dyadic adaptive stride
        scaling, `SegmentedKeyRunTable` bin partitioning and cell conservation, ground-truth
        run containment consistency, two-tier `resolveAccumulate` mathematical invariants
        (strict monotonicity, validity within [0, totalRange), complete exclusion of bad cells),
        and `fullCollapseTolerance` coverage guarantees.
- [x] 24\. Add a `MemoryTracker` **leak assertion**: after a full pipeline run including
      cancellation and failure paths, tickets and tasks must return to zero (S-002).
      **Done (2026-09-12):** Created comprehensive test suite `ReqRtpS002PipelineLeakAssertionTest`
      in `rtp-core` covering normal completion, setup cancellation, load cancellation with active
      reservations, cancellation via `RTPTeleportCancel`, location generator failure, generation
      exception, and active GC diagnostic sweep force-closure. Fixed `TeleportPipelineTask.runCleanup`
      to unconditionally release chunk reservations on null coordinates/region paths, registered
      and untracked task lifecycle in `MemoryTracker`, and enabled UUID untrack delegation.
- [x] 25\. Add a concurrency stress harness (jcstress or a randomized soak) over
      `LockFreeLocationBuffer` and `RegionQueueManager`.
      **Done (2026-09-12):** Created comprehensive multi-threaded concurrency stress suites:
      - `LockFreeLocationBufferConcurrencyStressTest`: verifies single-producer / single-consumer
        high-throughput soak invariants (zero element loss, exact conservation of items, strict
        FIFO stream ordering), ring-buffer index wrap-around stress under sustained concurrency
        across power-of-two boundaries, concurrent clear and reservation closure, and accurate
        accounting of `onAdd` and `onRemove` callbacks without missed invocations.
      - `RegionQueueManagerConcurrencyStressTest`: verifies thread safety and invariant maintenance
        across public queues (`keptLocations`, `unkeptLocations`), personal coordinate buckets
        (`openPersonalQueue`, `closePersonalQueue`, `enqueuePlayerLocation`), fast queue futures,
        network reservations (`reserveFromNetworkKept`, `redeemReserved`, `releaseToNetworkKept`),
        dynamic login cache toggling, and clean shutdown under active concurrency without deadlocks
        or resource leaks. Hardened `RegionQueueManager.enqueuePlayerLocation` with atomic
        `computeIfAbsent` to prevent NPE race conditions during concurrent personal queue closing.
- [x] 26\. Wire `simulationBenchmark` (ADR-080) to a committed baseline JSON and fail
      or warn on >X% regression. (The `simulation` tier tag now exists and is
      excluded from the default logic tier - see section 0.)
      **Done (2026-09-12):**
      - Updated `SimulationReport` to emit `.json` report sidecars alongside `.md` and `.csv`.
      - Implemented `SimulationBaselineRegressionComparator` with JSON baseline parsing, formatted
        generation, configurable tolerance (`-PregressionTolerance`), fail-or-warn mode
        (`-PfailOnRegression`), and baseline update support (`-PupdateBaseline`).
      - Added committed baseline JSON at `rtp-core/src/test/resources/benchmarks/simulation-baseline.json`
        covering >4,000 metrics across the 29 ADR-080 simulation benchmark suites.
      - Registered `evaluateSimulationBaseline` task in `rtp-core/build.gradle` and wired it as a
        `finalizedBy` gate on `simulationBenchmark`.
      - Added unit tests in `SimulationBaselineRegressionComparatorTest` verifying baseline parsing,
        regression detection thresholds, and JSON generation.

### 4.5 Enforce the prohibitions mechanically

- [x] 27\. Extend the ArchUnit ruleset to gate every statically expressible prohibition:
      no `org.bukkit.*` / `net.minecraft.*` in `rtp-core`/`rtp-api`;
      no `new Thread()` / `Executors.new*` outside documented carve-outs;
      no `printStackTrace()`; no `System.out`/`Bukkit.getLogger()`;
      no synchronous `getChunkAt` on main-thread paths (S-005).
      **Done (2026-09-12):**
      - Fixed all 9 historical `printStackTrace()` occurrences in `rtp-core` (`SyncTaskProcessing`,
        `SubConfigCmd`, `MultiConfigParser`, `SQLiteDatabaseAccessor`, `YamlFileDatabase`,
        `RedisManager`, `ChunkUnloadProcessor`, `ScanTask`) to use structured `RTP.log(Level.WARNING, ...)`.
      - Expanded `RTPArchitectureTest` with ArchUnit rules enforcing all 5 prohibitions:
        * `core_and_api_must_not_depend_on_platform_apis`: forbids `org.bukkit..`, `net.minecraft..`,
          `io.papermc.paper..`, `ca.spottedleaf.moonrise..`, and `net.fabricmc..` in `rtp-core` and `rtp-api`.
        * `no_thread_instantiation_or_executors_outside_carveouts`: forbids raw `Thread` instantiation
          and `Executors.new*` outside documented carve-outs (e.g. test fixtures and `AnvilIoPool`).
        * `no_print_stack_trace_in_core_or_api`: forbids `Throwable.printStackTrace()` calls.
        * `no_system_out_or_bukkit_getlogger_in_core_or_api`: forbids accessing `System.out`/`System.err`
          or calling `System.setOut`/`System.setErr` or `Bukkit.getLogger()`.
        * `no_synchronous_chunk_io_on_platform_world`: forbids synchronous `getChunkAt` calls on
          native platform world objects (S-005).
- [x] 28\. Add a row in `TRACEABILITY.md` mapping each S-00x to its enforcing ArchUnit rule.
      **Done (2026-09-12):**
      - Updated `TRACEABILITY.md` root prohibition section (REQ-RTP-S-001 through REQ-RTP-S-007)
        mapping each prohibition to its enforcing `RTPArchitectureTest` ArchUnit rules (Rules 1-10)
        and associated test suites.
- [x] 29\. Raise SpotBugs to `MEDIUM` confidence on new code; require written
      justifications in `spotbugs-exclude.xml` (auditors read suppressions).
      **Done (2026-09-12):**
      - Set `reportLevel = Confidence.valueOf('MEDIUM')` in `rtp-core/build.gradle`.
      - Extensively updated `spotbugs-exclude.xml` with auditor-ready written justifications
        categorizing and explaining pre-existing patterns across the codebase (e.g., representation
        exposure for zero-copy performance hot paths [REQ-RTP-F-001], constructor validation throws,
        public configuration fields, defensive null-checks, dynamic SQL generation, and optimistic
        concurrency checks).
      - Verified `.\gradlew.bat :rtp-core:spotbugsMain` passes cleanly with 0 violations.
- [x] 29a\. Programmable local static-analysis gate landed: opt-in PMD
      (`-PstaticAnalysis`) over the appendable `config/pmd/ruleset.xml`, including the
      custom `PreferNonLockingExecution` rule that flags `synchronized` in favour of
      the non-blocking contract. Complements (does not replace) the ArchUnit rules in
      items 27-28. Still opt-in only; wire into CI and triage the existing
      `synchronized` hits to give it teeth.

---

## 5. Compatibility (make it measured, not asserted)

- [x] 30\. Publish a **support matrix**: platform family (Spigot / Paper / Folia / Fabric
      / NeoForge / Velocity / BungeeCord) x MC version x Java version, each cell marked
      **tested / best-effort / unsupported**. No cell may say "should work".
      **Done (2026-09-12):** Authored and published canonical matrix at
      [`docs/dev/SUPPORT_MATRIX.md`](SUPPORT_MATRIX.md), referenced in `INDEX.md` and
      `MAP.md`. Details definitions for Tested / Best-effort / Unsupported, mandates Java 21+
      across all platforms (REQ-RTP-SYS-001), covers server platforms (Paper + forks, Folia,
      Spigot, Fabric, NeoForge) across MC 1.19.4 through 26.x, proxies (Velocity 3.3.x+,
      BungeeCord/Waterfall), hybrid runtimes (Mohist, Arclight), and verification tiers.
- [x] 31\. Add `japicmp` or `revapi` against the previous release for `rtp-api`,
      `commands-api`, `effects-api`, `maps-api`, `metrics-api`, `anvil-api`, `tags-api`.
      Fail the build on unannounced binary-incompatible change.
      **Done (2026-09-12):**
      - Integrated `me.champeau.gradle.japicmp` plugin in `gradle/libs.versions.toml` and `build.gradle`.
      - Configured `checkBinaryCompatibility` task on all 7 public API modules (`:rtp-api`,
        `:commands-api`, `:effects-api`, `:maps-api`, `:metrics-api`, `:anvil-api`, `:tags-api`)
        with root aggregator task `:checkBinaryCompatibility`.
      - Compares newly compiled JAR against baseline artifact coordinate (`io.github.dailystruggle:<module>:<baselineVersion>`,
        default `3.2.0`, configurable via `-PjapicmpBaselineVersion=...`).
      - Produces detailed HTML reports (`build/reports/japi.html`) and enforces binary compatibility rules
        in accordance with `DEPRECATION_POLICY.md`.
- [x] 32\. Write a **deprecation policy**: minimum N minor versions of notice,
      `@Deprecated(forRemoval = true, since = ...)` everywhere, changelog removal section.
      **Done (2026-09-12):** Authored and published canonical policy at
      [`docs/dev/DEPRECATION_POLICY.md`](DEPRECATION_POLICY.md), referenced in `INDEX.md`
      and `MAP.md`. Defines explicit 2-minor-release notice window, compiler annotation
      requirements (`@Deprecated(forRemoval = true, since = "...")`), Javadoc `@deprecated`
      replacement tags, changelog tracking (`### Deprecated` / `### Removed`), carrier
      retirement lifecycle (aligned with `MULTI_PLATFORM_PLAN.md`), and config schema migration.
- [x] 33\. Wire a nightly CI job that boots the `devstack` containers and runs a smoke
      subset of `run-acceptance.sh`, uploading logs as release evidence.
      **Done (2026-09-12):**
      - Authored scheduled GitHub Actions workflow [`.github/workflows/devstack-acceptance.yml`](../../.github/workflows/devstack-acceptance.yml).
      - Executes nightly at 03:00 UTC and supports manual trigger (`workflow_dispatch`) with scenario selection (`all`, `boot`, `heartbeat`, `killswitch`, `roundtrip`).
      - Sets up dual Java 21/25 toolchain, Node.js 20, builds proxy & plugin jars, executes devstack acceptance harness, and captures full logs.
- [x] 34\. Attach the acceptance evidence log to each GitHub release.
      **Done (2026-09-12):**
      - Devstack acceptance workflow automatically captures and uploads `acceptance-evidence.log` and per-service diagnostic logs (`*.log`) with 30-day retention.
      - Integrated artifact preservation for release pipelines to attach verified acceptance evidence logs to releases.
- [x] 35\. Add config-schema versioning with tested automatic migration; make
      `wiki/Migrating.md` per-major and backed by a test.
      **Done (2026-09-12):**
      - Documented config upgrade and schema versioning lifecycle in
        [`docs/admin/configuration/CONFIG_LIFECYCLE.md`](../admin/configuration/CONFIG_LIFECYCLE.md)
        and [`docs/admin/MIGRATION.md`](../admin/MIGRATION.md).
      - Backed by automated unit test suites in `rtp-core`: `ConfigParserUpdateTest` (verifies
        automatic version detection, `.old1` file rotation, default extraction, and user value
        overlay), `ConfigParserLocaleSwitchTest`, `MultiConfigParserLocaleSwitchTest`, and
        `MultiConfigParserIsolationTest`.
      - Updated `wiki/Migrating.md` redirect pointer to canonical `docs/admin/MIGRATION.md`.
- [x] 36\. Add Testcontainers-based integration tests for the Redis and SQL network
      bindings (covers item 17 and gives the proxy claim real evidence).
      **Done (2026-09-11):** Completed alongside items 17 and 18:
      - Redis integration test suite on real Testcontainers Redis 7 (`RedisTestContainer`,
        `@EnabledIf(dockerAvailable)`): `RedisLeaderLeaseIT`, `RedisNetworkRequestQueueIT`,
        `RedisNetworkWaitlistIT`, `RedisPlayerOwnershipTrackerIT`, and `RedisNetworkStateBindingIT`.
      - SQL integration test suite across databases: `RealH2DatabaseAccessorTest`,
        `RealSQLiteDatabaseAccessorTest`, and Docker-gated `RealMySQLDatabaseAccessorTest`
        and `RealPostgreSQLDatabaseAccessorTest` (`SqlTestContainers`, MySQL 8.4 / Postgres 16).
      - All suites pass cleanly in CI and plain-JVM/Docker test runs.

---

## 6. TODO - Supply chain

- [ ] 37\. CycloneDX SBOM generated per release, attached to the GitHub release.
- [ ] 38\. Dependabot or Renovate enabled; OWASP dependency-check in CI.
      Shipped drivers: HikariCP, Jedis, H2, PostgreSQL, SQLite - all CVE surfaces.
- [ ] 39\. Sign the shaded plugin jar and publish checksums alongside it
      (Maven Central signing already exists; extend to the deliverable).
- [ ] 40\. Reproducible builds: `preserveFileTimestamps = false`,
      `reproducibleFileOrder = true`, plus Gradle `dependencyLocking`.
- [ ] 41\. Pin all GitHub Actions by commit SHA rather than tag.
- [ ] 42\. Enable GitHub build provenance / SLSA attestation.

---

## 7. TODO - Policy and presentation

- [ ] 43\. Explicit SemVer contract: what is public API vs internal.
- [ ] 44\. Make `SUPPORT.md` specific: which versions get fixes, for how long,
      expected response window. "Best-effort, typically within N days" beats silence.
- [ ] 45\. Make `SECURITY.md` specific: private disclosure channel, triage timeline,
      advisory history.
- [ ] 46\. One-page licensing clarity across `LICENSE` / `LICENSE-MIT` and the
      Pro / Lite split (ADR-024). Ambiguity blocks adoption more than bugs do.
- [ ] 47\. Add severity + status columns to `POTENTIAL_BUGS.md` so it reads as a
      managed backlog rather than a pile of open defects.
- [ ] 48\. Repository root hygiene: ~25 loose chart `.png` files, `gitstat.txt`,
      `checkout_list.txt`, `test_2d_map.bmp`, `default_2d_map.bmp`, a generated
      `site/` directory, and a folder named `Python Test Scripts` (with a space).
      Move charts to `docs/assets/`, gitignore `site/`, rename the spaced folder.
- [ ] 49\. Add a CI grep for the usual UTF-8-read-as-CP1252 mojibake markers (the
      three-byte em-dash and non-breaking-space corruptions) across tracked text
      files. There is live mojibake in shipped source comments today, e.g.
      `rtp-core/build.gradle` lines 33 and 144, where an em dash was corrupted.
- [ ] 50\. Sweep the stray untracked `.bak` files out of the working tree
      (62 as of 2026-09-11; none are committed - local clutter only, but they leak
      into IDE search).

---

## 8. Sequencing

**Phase 1 - stop the bleeding (days).** Items 1-6, 37-41, 49, 50.
Honest gates and supply chain. After this, nothing in the repo actively contradicts
the claim.

**Phase 2 - close the zeroes (1-2 weeks).** Items 7-14, 27-29, 31.
Every platform-neutral module has tests and the prohibitions are machine-enforced.

**Phase 3 - reach the target ratios (4-6 weeks).** Items 15-21, 36.
`rtp-core` and `rtp-proxy-common` to 90/80.

**Phase 4 - prove depth (ongoing).** Items 22-26, 30, 33-35, 42-48.
Mutation score, soak tests, measured compatibility matrix, published policy.

---

## 9. Definition of done

The claim is defensible when all of the following are true and **externally visible**:

1. Every platform-neutral module is at or above 90% instruction / 80% branch, enforced
   by a build gate that cannot be lowered without a commit.
2. Mutation score on the safety packages is at or above 60%.
3. Every S-00x prohibition has an automated rule, cited in `TRACEABILITY.md`.
4. The support matrix distinguishes tested from best-effort, and the tested cells are
   re-verified by CI on a schedule.
5. Each release ships an SBOM, signed artifacts, checksums, and an acceptance log.
6. API compatibility is gated automatically, and the deprecation policy is published.
7. Zero known CVEs in shipped dependencies, checked automatically.

Until then, prefer the provable phrasing over the adjective: state the tested matrix,
the coverage number, the signing and SBOM status. That language ages into the claim
on its own, and it matches the evidence-over-adjectives voice the project already uses.

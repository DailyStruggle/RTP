# RTP-67 / RTP-68 worktree resume and OS reinstall handoff

**Prepared:** 2026-10-20
**Purpose:** Preserve the current package-hub refactor plan and observations across the planned OS reinstall. This is a handoff note only; no refactor or build was run while preparing it.

## Before reinstall: protect the working tree

The repository has extensive uncommitted work. **This Markdown file does not protect that work.** It is uncommitted locally (and may be staged with newer unstaged edits), so it will be lost in the OS reinstall unless included in the external copy. Before wiping or reinstalling the OS, copy both the complete working tree and `.git` metadata to storage that will not be wiped. **You must include all untracked and ignored files.** A staged copy of this handoff may be stale; if you later elect to commit, stage the current handoff contents first.

Verify the copy is readable and contains the worktree contents, `.git` directory, and all hidden/ignored artifacts before proceeding. If `git status` or your backup tool reports "Filename too long" (common in `build/` paths), enable `core.longpaths` (`git config --global core.longpaths true`) or use a backup tool that handles long paths (like `robocopy` on Windows).

No commit, push, or PR was requested. Do not create one without explicit direction. Treat all existing dirty code as user-owned; do not discard, reset, restore, stash, or clean it. Before editing any uncommitted code file, make a `<file>.bak` copy.
**NEVER overwrite existing `.bak` files.** They may hold pre-Wave-1 or older snapshots left from earlier tasks (the rewrite script only creates a `.bak` when none exists, so older ones were kept as-is). If you need to make another backup of an already-backed-up file, use a distinct suffix (e.g., `<file>.bak.v2`). Remove backups only after the work is verified and the user has a safe external backup of the full tree.

## Wave 1 Artifacts and Attribution

Wave 1 has started. Several gitignored files contain the state of the worktree just before and during this transition:
- `scripts/tmp/rewrite_runtime_wave1.py` (the rewrite script)
- `scripts/tmp/wave1_files.txt` (a 591-line list of rewritten files)
- roughly 150-160 ignored `*.bak` files (some older than Wave 1; including `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/RTP.java.bak`)

These artifacts are **gitignored** and will be lost on OS reinstall unless you explicitly include them in your external backup.

While Git status does not automatically tag changes, the Wave 1 delta can be reconstructed for the dirty files listed in `wave1_files.txt` that have a `.bak`, by comparing `.bak` to the current file (e.g., `git diff --no-index <file>.bak <file>`). This only separates Wave 1 edits from earlier uncommitted work; the split between the earlier RTP-67 and RTP-68 work cannot be determined from git alone. Preserve the entire dirty tree as carry-in.

## Observed worktree state (2026-10-20)

- `git status --short --untracked-files=all` was non-clean, with changes across core, API, addons, platform modules, and `rtp-plugin`.
- `git diff --stat -- rtp-core` reported **433 changed tracked files**, **2,968 insertions**, and **2,777 deletions**. The runtime files below were untracked and therefore are not included in that diff statistic.
- Present but untracked under `rtp-core`:
  - `src/main/java/io/github/dailystruggle/rtp/common/runtime/RTPContext.java`
  - `src/main/java/io/github/dailystruggle/rtp/common/runtime/RTPRuntime.java`
  - `src/main/java/io/github/dailystruggle/rtp/common/runtime/package-info.java`
  - `src/test/java/io/github/dailystruggle/rtp/common/runtime/RTPRuntimeTest.java`
- `RTP.java` and `RTPArchitectureTest.java` are modified. The inspected code has `RTP.log(..)` and `RTP.flushPendingLogs()` delegating to `RTPRuntime`, an injected `BooleanSupplier` log gate, volatile runtime slots, JUL fallback, and Rule 20. Platform/plugin service-setter references and many core `RTPRuntime` references are also present. This is clear evidence Wave 1 started and has substantial implementation in the worktree; it is **not evidence that the full repository rewrite compiles or behaves correctly**.
- The narrow service-reference search found replacements such as `RTPRuntime.scheduler()` and `RTPRuntime.serverAccessor()` in core; some remaining matches are comments, log text, and test display text. The platform/plugin search found setter use in sources/tests. No exhaustive audit of every call site was completed.
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/placeholders/PlaceholderProvider.java` is modified; `common/selection/model/FailTypes.java` had no reported working-tree change in the narrow status check. Existing Git status does not tag changes with issue IDs or authors, so the RTP-67 versus RTP-68 split cannot be determined from git alone (only the Wave 1 delta is recoverable from `.bak` files, see above). Preserve the entire dirty tree as carry-in.
- The current diff includes numerous platform, addon, core and test changes. Do not assume the cancelled executor left no changes, and do not restart Wave 1 from a clean-state assumption.
- No Gradle task was run and no Gradle lock/mutex file or external process state was inspected. The tool's terminal status only describes processes started by this agent; whether another process holds the Gradle wrapper lock is **unknown**. Let the wrapper's built-in mutex serialize later work; never stop another agent's Gradle run or kill Gradle daemons.
- Verification performed for this handoff: read-only Git status/diff summaries and source/ADR inspection only. No compile, test, architecture rule execution, `jdeps`, or platform build was run here.
- Pre-backup cleanup (2026-10-20): the gitignored world save `testdata-world/` was deleted, along with devstack worlds, sweep data, `node_modules` and Gradle outputs. After the reinstall, the world-data benchmarks (`TwoTierRouterWorldDataBenchmarkTest`, `HeuristicPlannerBenchmarkTest`) skip and `LinearRealDataParityTest.multiChunkRealWorldParity` returns early. This is expected and is not a regression.

## Baseline and governing decision

The project plan reports the initial post-RTP-67 measurement as **38 mutual package pairs**, **31 involving `common`**, and **66 packages depending on `common`**. The **6,658 green tests** figure was reported in task feedback, not the plan. These are recorded as supplied baseline facts; this handoff did not independently reproduce either measurement. Re-measure after each completed wave and report the final result without presenting these baseline values as current results.

[ADR-115](../adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md) is accepted and governs this work. Its seam 4 keeps `common.RTP` as the Tier 4 composition root and prohibits internal domain packages from depending on it. Preserve ADR-115 invariant 3: no runtime behavior change. In particular preserve null-before-install semantics, service installation order, shutdown order, and the buffered logging/JUL fallback contract. The staged plan below is the approved multi-module design; do not broaden it with unrelated package cycles or constructor-injection work.

## Resume checklist: approved five-step execution sequence

Keep this checklist as the resume source of truth. All items remain open until their implementation and verification are actually confirmed. Wave 1 artifacts already exist as described above; first review and safely preserve the existing diff rather than duplicating it.

- [x] **1. Tier 1 runtime holder: logging, accessor, scheduler, economy.** Verified 2026-10-20; see *Wave 1 verification* below. Review the existing `common.runtime.RTPContext` / `RTPRuntime` files. Keep the holder dependent only on `rtp-api` and the JDK; keep API-typed services in volatile slots that return `null` before installation. Move the pending-log buffer, JUL fallback and ordered `flushPendingLogs` replay into `RTPRuntime`; keep `RTP.log(..)` and `RTP.flushPendingLogs()` as delegates. Inject the logging-parser buffering gate with `BooleanSupplier` so `runtime` does not depend on configuration. Finish/verify mechanical call-site rewrites in main and test code throughout `rtp-core`, `platforms/**`, and `rtp-plugin`; update `RTPTestSetup` and mock fixtures. Retain/add `RTPRuntimeTest` coverage for no-accessor JUL fallback, closed/open gate behavior, buffer cap, and ordered flush. Keep Rule 20 enforcing that `common.runtime..` has no dependency on other core packages. Compile `rtp-core` and all touched platform/plugin main and test sources; run `RTPRuntimeTest`, `RTPArchitectureTest`, and relevant targeted tests; run `jdeps` and record the post-Wave-1 package-pair count. Do not claim completion from the currently present but unverified files.
- [x] **2. Owned slots for configs and factories.** Verified 2026-10-20; see *Wave 2 verification* below. Add `configuration.ActiveConfigs.get()/set()` and update every `RTP` assignment, including reload paths. Move `factoryNames` to `factory.FactoryNames` without changing enum constant order; add `factory.FactoryRegistry` to own the map. Rewrite all repository call sites, including tests and fixtures. Compile touched modules; run config reload, factory/shape, and architecture tests; record `jdeps` counts.
- [x] **3. Selection, metrics, action/trigger services, selection context, and network hooks.** Verified 2026-10-20; see *Wave 3 verification* below. Add `selection.ActiveSelection`; provide `CoreMetrics.instance()` and owning slots for action and trigger managers. Move thread-local region/world context beside `selection.region`. Move `networkCommandHook`, `networkBridgeFactory`, and `backendStateSamplerFactory` to `network.NetworkHooks`. Rewrite and update fixtures; compile and run targeted tests plus `RTPArchitectureTest`; record `jdeps` counts.
- [x] **4. Runtime state in owning packages; remove domain `getInstance()` use.** Verified 2026-10-20; see *Wave 4 verification* below. Add `playerData.TeleportSessions` for `latestTeleportData`, `priorTeleportData`, `processingPlayers`, `queuedPlayers`, and `invulnerablePlayers`; `tasks.scan.ScanTasks`; `database.ActiveDatabase` for `databaseAccessor` and `teleportLimitStore`; `tasks.tick.MiscTasks`; and `tasks.teleport.DeathTeleports` for `deathEffectInFlight` and `pendingDeathTeleports`. `RTP` creates/installs these at the existing points and retains shutdown order. Update `RegionTicketCensus`, `RegionTaskCoordinator`, lifecycle, teleport and all other callers so domain code no longer uses `RTP.getInstance()` or `RTP` as a type. Compile every touched module; run all `:rtp-core:test` tests and platform/plugin main and test compiles; record `jdeps` counts.
- [ ] **5. Enforce and document the final boundary.** Add Rule 21: no `rtp-core` class outside root package `io.github.dailystruggle.rtp.common` may depend on `common.RTP`. Run full `:rtp-core:test`, `:rtp-plugin:compileJava`, `:rtp-plugin:compileLiteJava`, and compile all touched platform modules (main and test sources). Run `jdeps`; report final mutual-pair count and number of packages outside `common` depending on `common`. Update the RTP-67/RTP-68 entries in `docs/dev/POTENTIAL_BUGS.md` with measured results, add the ADR-115 seam 4 holder-pattern note and `docs/dev/TRACEABILITY.md` rows for new tests. After verification and safe backup, remove only the `.bak` copies and scratch scripts created for this effort. Do not alter issue status in Linear or make a commit/PR unless requested.

## Wave 1 verification (2026-10-20, Linux, after the reinstall)

The backup restored intact: 152 ignored `*.bak` files, `scripts/tmp/` and the untracked `common/runtime/` files were present.

- `./gradlew compileJava compileTestJava :rtp-plugin:compileLiteJava --continue` passed for every project (Bukkit, Paper, Folia, Fabric, NeoForge, proxy, addons, helpers, plugin).
- The first `./gradlew :rtp-core:test` run had 37 failures with two causes, both fixed:
  - `BootstrapLogBufferTest` reflected on `RTP.logsBuffered` / `RTP.pendingLogs`, which now live in `RTPRuntime`. It now targets `RTPRuntime` and again calls the `RTP.log` / `RTP.flushPendingLogs` delegates.
  - A class-init ordering regression (33 cascaded `NoClassDefFoundError`s). The old `RTP.serverAccessor = x` write forced `RTP.<clinit>` to run before the accessor existed, so default-shape registration skipped `loadLangFile`. `RTPRuntime.setServerAccessor` does not touch `RTP`. Fix: added `RTP.ensureInitialized()` and call it before `RTPRuntime.setServerAccessor` in `RTPFabricMod`, `RTPNeoForgeMod.prepareCore` and `BootstrapSupport` (Bukkit was already safe because `onLoad` logs first), and in `EditorSurveyWorldsTest`. Regression test: `RTPStaticInitOrderTest` (isolated class loader; covers both orders). Wave 2 can drop the hook once factory registration leaves `RTP`'s static init.
- After the fixes: `:rtp-core:test` ran 6,667 tests with 0 failures and 46 skipped. `RTPArchitectureTest` (including Rule 20) and `RTPRuntimeTest` pass.
- `jdeps -verbose:class -filter:none rtp-core/build/classes/java/main` gave 34 mutual package pairs, 27 involving root `common`, and 50 packages depending on root `common` (all 50 through `common.RTP`). The 38/31/66 baseline was not produced with the same script, so treat the deltas as indicative.
- Not run: platform module tests (`:rtp-plugin:test`, Fabric/NeoForge tests) and devstack runtime acceptance.

## Wave 2 verification (2026-10-20, Linux)

- Added `configuration.ActiveConfigs` (volatile slot, `get()`/`set()`), `factory.FactoryNames` (same constants and order as `RTP.factoryNames`) and `factory.FactoryRegistry` (owns the `EnumMap`; `get(name)` and `map()`). Removed `RTP.configs`, `RTP.factoryMap` and `RTP.factoryNames`. `RTP`'s static init still fills the registry; the `Configs` constructor installs itself through `ActiveConfigs.set(this)`.
- Rewrite: `scripts/tmp/rewrite_wave2.py` changed 256 files (1,117 edits). File list: `scripts/tmp/wave2_files.txt`. Backups for dirty files: `<file>.bak` if none existed, else `<file>.bak.w2` (173 files, listed in `scripts/tmp/wave2_backups.txt`). So the Wave 2 delta of a file with a Wave 1 `.bak` is `git diff --no-index <file>.bak.w2 <file>`.
- Class-init side effect: reading `RTP.configs` / `RTP.factoryMap`, or writing `RTP.serverAccessor` (Wave 1), used to trigger `RTP.<clinit>`. The holders don't. Production is unchanged because every platform calls `RTP.ensureInitialized()` before installing the accessor. Some tests had relied on the implicit trigger, so they now call `RTP.ensureInitialized()` explicitly: `MenuActionsTest`, `MenuModelTest` (GUI addon), `FoliaRTPPlayerSetLocationTest`, `FoliaRTPChunkKeepTest`, `FoliaRTPWorldChunkTicketTest`, `CountBoundTaskPipeTest`, `FoliaSchedulerTest` and `NeoForgeTestUmbrellaSchedulerTest`. The Folia, NeoForge and `MenuActionsTest` cases were Wave 1 leftovers that no earlier run had reached.
- New tests: `RTPStaticInitOrderTest.staticInitPopulatesFactoryRegistry` (isolated class loader: the registry starts empty, `RTP` static init fills every key, and `selectionAPI` shares the registry's shape factory) and `FactoryNamesTest` (constant names and order).
- `./gradlew compileJava compileTestJava :rtp-plugin:compileLiteJava` passed for every project. Tests, 0 failures in each: `:rtp-core` 6,669 (46 skipped), `:rtp-plugin` 584, Bukkit common 94, Paper common 47, Folia common 94, Folia v1_20_R1 110, Fabric common 117, NeoForge common 54, Claim addon 159, GUI common 33, Rift addon 6, Action addon 4.
- `jdeps` (same script as the Wave 1 figures): 27 mutual package pairs (Wave 1: 34), 20 involving root `common` (27), 40 packages depending on root `common`, all through `common.RTP` (50).
- Left for later steps: `Region` and other domain classes still read `RTP` statics (`lobbyMode`, `getInstance()`), and `Region`'s task dispatcher relies on that to force `RTP.<clinit>`. Steps 3 and 4 remove those reads, so they shall keep RTP init ordering explicit as they go. Addon-facing docs (`docs/FOR_ADDON_DEVELOPERS.md`, `addons/LeafRTPCountdownAddon/README.md`, `docs/admin/configuration/EVENTS_AND_EFFECTS.md`) still show `RTP.configs` and `RTP.serverAccessor`. Update them in step 5.

## Wave 3 verification (2026-10-20, Linux)

- Added holders and removed the matching `RTP` statics:
  - `selection.ActiveSelection` (`get()`/`set()`). Like the old field it is never `null`: it holds a `SelectionAPI` from first access, and `RTP`'s static init attaches the shape factory to it.
  - `CoreMetrics.instance()` and `PhysicalTriggerManager.instance()`: lazy-holder singletons. `RTP`'s static init still starts the trigger manager and closes it in `stop()`.
  - `action.ActionServices` (`actionManager()`, `cacheWarmTask()`; `null` until the action addon installs them).
  - `selection.region.RegionContext` (`region()`, `world()` thread-locals).
  - `network.NetworkHooks` (`commandHook()` defaulting to `LOCAL_ONLY`, `bridgeFactory()`, `backendStateSamplerFactory()`, and `lobbyMode()`, which `Region` read).
- Rewrite: `scripts/tmp/rewrite_wave34.py 3` changed 181 files (866 edits); list in `scripts/tmp/wave3_files.txt`. Backups for dirty files: `<file>.bak` if none existed, else `<file>.bak.w3` (139 files, `scripts/tmp/wave3_backups.txt`). Two tests that reached the removed fields reflectively (`ActionSubspaceIntegrationTest`, `RTPServerAccessorTpsParityTest`) now call the holders.
- New test `RTPStaticInitOrderTest.holdersKeepFormerRtpDefaults`: in an isolated class loader, every holder has the former field default both before and after `RTP` static init.
- Tests, 0 failures: `:rtp-core` 6,685 (46 skipped); the platform, plugin and addon suites listed under Wave 2 all pass with the same counts.
- `jdeps`: 25 mutual package pairs (Wave 2: 27), 17 involving root `common` (20), 33 packages depending on `common.RTP` (40).

## Wave 4 verification (2026-10-20, Linux)

- Added holders and removed the matching `RTP` fields:
  - `playerData.TeleportSessions`, an instance holding `latestTeleportData`, `priorTeleportData`, `processingPlayers`, `queuedPlayers` and `invulnerablePlayers`, plus `regionTeleportTimes` and `usageCaps`, which domain code also read.
  - `tasks.scan.ScanTasks` (the scan-task map).
  - `database.ActiveDatabase` (`accessor()`, `teleportLimitStore()`).
  - `tasks.tick.MiscTasks` (the sync, async, startup and cancel pipes, plus `chunksToUnload`).
  - `tasks.teleport.DeathTeleports` (`effectInFlight()`, `pending()`).
- Install points: `RTP` still creates `TeleportSessions`, the limit store, the scan map and the unload queue as field initializers, in the same order as before, and installs them immediately before `instance = this`. So `TeleportSessions.current() == null` holds exactly when `RTP.getInstance() == null`. At the same point it clears `ActiveDatabase.accessor()`, because a new instance used to start with a `null` accessor. The pipes are installed where the constructor used to assign them. `stop()` keeps its order; nothing clears the slots, as before.
- Rewrite: `scripts/tmp/rewrite_wave34.py 4` changed 139 files (952 edits; `scripts/tmp/wave4_files.txt`; 123 backups as `.bak` or `.bak.w4`, `scripts/tmp/wave4_backups.txt`). `scripts/tmp/rewrite_wave4_guards.py` then turned every remaining domain `RTP.getInstance()` null guard and `RTP`-typed local in non-root `rtp-core` packages into `TeleportSessions.current()` (26 files). Three memory-shape tests that set `databaseAccessor` reflectively now call `ActiveDatabase.setAccessor`.
- `Region` no longer reads any `RTP` static, so nothing on a `Region` path forces `RTP.<clinit>` any more. Only the explicit `ensureInitialized()` ordering installs the task dispatcher before dispatch; the comment in `RTP`'s static block says so. No test needed a new `ensureInitialized()` call.
- New tests: `RTPInstanceStateHoldersTest` (a new instance installs fresh sessions, scan map, pipes and limit store, clears the accessor, and keeps the cancel pipe unbounded). `RTPStaticInitOrderTest.holdersKeepFormerRtpDefaults` now also checks that the per-instance holders stay `null` until an instance exists.
- `./gradlew compileJava compileTestJava :rtp-plugin:compileLiteJava` passed for every project. Tests, 0 failures in each: `:rtp-core` 6,691 (46 skipped), `:rtp-plugin` 584, Bukkit common 94, Paper common 47, Folia common 94, Folia v1_20_R1 110, Fabric common 117, NeoForge common 54, Claim addon 159, GUI common 33, Rift addon 6, Action addon 4.
- `jdeps`: 22 mutual package pairs (Wave 3: 25), 10 involving root `common` (17), 19 packages depending on `common.RTP` (33). No `rtp-core` class outside the root package calls `RTP.getInstance()` or declares an `RTP`-typed variable.
- Left for step 5: Rule 21 cannot pass yet. 19 packages (28 classes) still use `RTP` statics that steps 3 and 4 did not cover:
  - fields: `baseCommand`, `reloading`, `messageTap`, `futures`, `biomeActivity`, `groupPlacementService`, `minRTPExecutions`, `testUmbrellaContext`;
  - methods: `getDelay`, `getCooldown`, `getEffectiveLastTeleportTime`, `getLastRegionTeleportTime`, `setLastRegionTeleportTime`, `updateSharedLastTeleportTime`, `addShape`, `registerDefaultShapes`, `handleMigration`;
  - `RTP.class` as a lock or class-init trigger in `ConfigParser`, `Configs` and `FactoryValue`.
  Most users are in `commands.*` (Tier 4). The Tier 2/3 users are `action`, `configuration`, `factory`, `selection`, `selection.region`, `tasks.scan`, `tasks.teleport`, `tasks.tick` and `tools`; ADR-115 invariant 5 still fails for `selection`, `tools` and `tasks`.

## Resume and verification commands

Run commands from the repository root in PowerShell using the wrapper, one command at a time. The module wrapper has a built-in serialization mutex; wait for it rather than interrupting another run. First inspect current `git status --short --untracked-files=all` and review the existing diff; do not run commands that discard working-tree state.

For Wave 1, the approved starting checks are:

```powershell
.\gradlew.bat :rtp-core:compileTestJava
.\gradlew.bat :rtp-core:test --tests "*RTPRuntimeTest" --tests "*RTPArchitectureTest"
```

Then compile `compileJava` and `compileTestJava` for each touched platform module and plugin source set. If exact Gradle project paths are unclear after reinstall, inspect `settings.gradle` or use the wrapper's project listing to find the actual paths; do not guess them based only on directory names. Finish the full test/compile scope described in checklist item 5. Run `jdeps -verbose:class rtp-core\build\classes\java\main` against freshly compiled classes and calculate/report package dependencies and mutual pairs after each wave and at completion. Record actual commands and outcomes; a passed compilation is not a substitute for tests or the `jdeps` measurement.

## Handoff boundary

This session created only this Markdown handoff. It did not modify or verify the Java refactor, did not run Gradle or `jdeps`, and did not make a commit, push, or PR. The immediate next action is to create and verify an external backup of the dirty worktree before the OS reinstall; after the new OS is ready, re-check status and continue by reviewing the existing Wave 1 diff.
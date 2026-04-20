# Project Guidelines

This document provides operational guidelines for AI assistants (like Junie) operating within the RTP repository.

## Pre-Flight Checklist (Mandatory)
Before generating any code or terminal commands, you MUST explicitly state and verify:
1. **Target Platform**: Is this for Folia, Paper, Spigot, or Fabric?
2. **Thread Context**: If Folia, verify `Bukkit.isOwnedByCurrentRegion` before execution.
3. **Check I/O**: Ensure zero synchronous chunk loads or `.get()` calls on the main thread.
4. **Terminal**: Confirm you are using PowerShell syntax (`.\gradlew`, `;`, and single quotes).
5. **Reference Prohibitions**: Explicitly state which S-00x rule from the requirements applies to the task.
6. **Backups**: Confirm you have created backups of any files you intend to modify (e.g., `cp <file> <file>.bak`).

## Backup Policy
Before any file modification, you MUST create a backup copy of the original file.
- Use the suffix `.bak` for backup files (e.g., `LocationGenerator.java.bak`).
- Backup files should be deleted only after the changes are verified and submitted.
- Never modify a file without a corresponding backup in the same directory.

## Required Reading (Context Protocol)
Read only the documents relevant to your task. Do **not** read all of them unconditionally — that wastes tokens. If `AGENTS.md` was already provided in full at session start, do not re-read it.

| Task type | Must read before starting |
|-----------|---------------------------|
| Any safety-critical code (threading, chunk I/O, teleport) | `docs/dev/REQUIREMENTS.md §3`, relevant platform `REQUIREMENTS.md` |
| Modifying scheduling or concurrency | `docs/dev/DESIGN.md`, `docs/dev/REQUIREMENTS.md §3` |
| Placing new code in a module | `docs/dev/ARCHITECTURE.md`, `Architecture Boundaries` section below |
| Introducing or renaming a domain term | `docs/dev/GLOSSARY.md` |
| Writing or updating tests | `docs/dev/COVERAGE_PLAN.md`, `docs/dev/TRACEABILITY.md` |
| Making a structural change | `docs/adr/README.md` + relevant ADR |
| Starting a new feature or platform work | `docs/dev/MULTI_PLATFORM_PLAN.md`, `Current Development Focus` below |
| Modifying a specific platform module | That module's `REQUIREMENTS.md` (see list below) |

Module-level requirement files:
- `rtp-api/REQUIREMENTS.md`
- `rtp-core/REQUIREMENTS.md`
- `rtp-spigot/REQUIREMENTS.md`
- `rtp-paper/REQUIREMENTS.md`
- `rtp-folia/REQUIREMENTS.md`

Full document index (read on demand):
* `docs/dev/REQUIREMENTS.md` — absolute laws: threading, memory, command contexts
* `docs/dev/ARCHITECTURE.md` — module separation; ArchUnit enforcement
* `docs/dev/DESIGN.md` — threading model, `MemoryTracker` lifecycle, region caching
* `docs/dev/CONCEPTS.md` — O(log n) Archimedean spiral math
* `docs/dev/MULTI_PLATFORM_PLAN.md` — current Fabric roadmap phase status
* `docs/dev/GLOSSARY.md` — canonical domain terms; do not invent synonyms
* `docs/dev/TRACEABILITY.md` — REQ-* → class/test mapping
* `docs/dev/COVERAGE_PLAN.md` — JaCoCo baseline (49% → 80% target); critical-gap packages
* `docs/adr/README.md` — architecture decision records index

## Strict Folia Threading & Operational Logic
* **Zero-Blocking Policy**: Never suggest blocking tick threads to wait for chunk loads, as this is strictly forbidden on Folia.
* **Asynchronous Chaining**: Refactor any synchronous `.get()` or `.join()` calls into `CompletableFuture` chains using `.thenCompose()` or `.thenAccept()`.
* **Region Ownership Verification**: Before scheduling tasks to a region, check if the current thread already owns the target region using `Bukkit.isOwnedByCurrentRegion` to eliminate unnecessary 1-tick delays.
* **Database Processing**: `DatabaseProcessing` is enabled on all platforms (Spigot, Paper, Folia). On Folia, it runs via `RTP.scheduler.runTaskTimerAsynchronously` to ensure database deletions/saves are flushed during runtime.
* **Vault Isolation**: Any interaction with Vault or economy plugins MUST be isolated within a Global Region Scheduler or Async Scheduler to prevent `ThreadAccessExceptions` on Region Threads.
* **Entity Schedulers**: Target the Entity Scheduler for any player modifications, including teleports.
* **Task Pipelines**: Use Count-Bound task pipes strictly for Folia to prevent stalled Region Threads. Time-Bound task pipes are permitted for Spigot and Paper.

## Prohibition Requirements (Hard Safety Rules)
The following are absolute prohibitions derived from `docs/dev/REQUIREMENTS.md §3`. Violating any of these is a critical defect — never generate code that does so.

**Quick reference** (scan first; read full rules below only if you are touching that code path):

| ID | One-line rule | Do NOT |
|----|--------------|--------|
| S-001 | No unsafe-block teleport destinations | Add a second block check in adapters or commands |
| S-002 | No permanently force-loaded chunks | Call extra `close()` that would double-release a ticket |
| S-003 | No teleport into claim-protected land | Add inline claim-plugin calls in the pipeline or commands |
| S-004 | No silently discarded teleport failures | Use silent `return` or catch-and-swallow in new pipeline stages |
| S-005 | No chunk loading on the main thread | Call synchronous `world.getChunkAt()` on any main-thread path |
| S-006 | No NPE when addons call API before core loads | Add null-guard returns that silently no-op |
| S-007 | Configurable "busy" and "invalid command" messages | Hardcode strings for command failure states |

Each rule below also shows **where the requirement is already satisfied**. Before adding safety-related code, confirm the existing implementation does not already cover your case.

* **REQ-RTP-S-001** — The system shall not teleport a player to a location where the landing block or surrounding blocks are lava, fire, magma, void air, or any other block designated unsafe in the active region configuration.
  - *Already satisfied by:* `LocationGenerator.getLocation(Region, Set<String>)` — the inner `safetyCheck` loop calls `chunk.isSafe(xx, y, zz, unsafeBlocks)` against the `SafetyKeys.unsafeBlocks` list read from config, with a configurable `SafetyKeys.safetyRadius`. Do **not** add a second block-type check in the teleport command or adapter layer.

* **REQ-RTP-S-002** — The system shall not leave a chunk in a force-loaded state beyond the configured reservation window. Every chunk ticket acquired shall be released either by explicit close, by `MemoryTracker` watchdog, or by JVM weak-reference collection.
  - *Already satisfied by:* `TeleportPipelineTask.runCleanup()` calls `reservation.close()` on every exit path (normal, exception, and player-disconnect). `TeleportPipelineTask.runTeleport()` also calls `reservation.close()` in its `whenComplete` callback. The `MemoryTracker.runDiagnostics()` watchdog detects leaked `TeleportPipelineTask` instances and force-cancels them. Do **not** add extra `close()` calls elsewhere that would double-release a ticket.

* **REQ-RTP-S-003** — The system shall not teleport a player into a location that a registered claim or protection addon has marked as inaccessible to that player.
  - *Already satisfied by:* `GlobalRegionVerifiers.checkGlobalRegionVerifiers(RTPCoords)` — called at the end of the candidate-selection loop in `LocationGenerator.getLocation()` (line 662). Protection addons (GriefPrevention, WorldGuard, Towny) register a `Predicate<RTPCoords>` or async `Function<RTPCoords, CompletableFuture<Boolean>>` via `GlobalRegionVerifiers.addGlobalRegionVerifier()` / `addGlobalRegionVerifierAsync()`. Do **not** add inline claim-plugin calls inside `TeleportPipelineTask` or the command handlers.

* **REQ-RTP-S-004** — The system shall not silently discard a teleport request. Every failure (empty queue, invalid region, permission denied, safety rejection) shall produce a player-visible message and a log entry at WARN level or higher.
  - *Already satisfied by:* `TeleportPipelineTask.processGenerationResult()` sends `ConfigCache.unsafe` message on null coords; the `setLocation.whenComplete` callback in `runTeleport()` sends `ConfigCache.teleportMessage` on success or `ConfigCache.unsafe` on failure. All exception paths call `SupportLogger.logException(Level.WARNING, …)`. On the **pregen** path, `LocationGenerator.getLocation(Region, Set<String>)` attributes every `chunk == null` drop to the `FailTypes.nullChunk` bucket (sub-keys `reason=asyncLoadNull` and `reason=neighborNull`) — which is the exit taken by `BukkitRTPWorld.getChunkAt` when the ADR-016 Anvil pre-filter returns `REJECT`. The bucket appears in the pregen summary printed at `Level.INFO` when `logging.selection_failure=true`; a `Level.FINE` per-candidate breadcrumb is also emitted. `BaseRTPCmd.msgBadParameter` and `BaseRTPCmd.msgInvalidCommand` ensure invalid command inputs log a `Level.WARNING` and send configurable feedback. Do **not** add silent `return` or `catch`-and-swallow blocks in new pipeline stages, and do **not** drop the `FailTypes.nullChunk` attribution when refactoring the null-chunk branches — the regression guard is `ReqRtpS004NullChunkAttributionTest`.

* **REQ-RTP-F-013** — The system shall allow all user-facing messages to be configurable via the `messages.yml` configuration file.
  - *Already satisfied by:* `ConfigParser` loads `messages.yml` into a `FactoryValue<MessagesKeys>` map. `BaseRTPCmd.msgBadParameter` and `BaseRTPCmd.msgInvalidCommand` retrieve messages using `MessagesKeys` constants (e.g., `lang.getConfigValue(MessagesKeys.badArg)`). `RTPCmd` intercepts library failure messages and translates them using `MessagesKeys.invalidCommand` and `MessagesKeys.busy`.

* **REQ-RTP-S-005** — The system shall not perform chunk loading or validation on the main server thread. All such operations shall be dispatched through the platform-appropriate async scheduler.
  - *Already satisfied by:* `LocationGenerator.getLocation(Region, Set<String>)` calls `world.getChunkAtAsync(cx, cz)` (returns a `CompletableFuture`) and the entire method is invoked from `TeleportPipelineTask.runSetup()`, which runs under `AsyncTaskProcessing`. Platform adapters (`rtp-paper`, `rtp-folia`) override `getChunkAtAsync` with their native async APIs. **On pure Spigot this is only partially achieved:** the Bukkit API ships only the `Consumer`-based async chunk overloads; the `World#getChunkAtAsync(int,int) -> CompletableFuture<Chunk>` overload is a Paper addition. `BukkitRTPWorld` probes for it reflectively (`CHUNK_AT_ASYNC_FUTURE`) and, when absent (vanilla Spigot), `loadChunkFuture` falls back to `Bukkit.getScheduler().runTask(plugin, () -> world.getChunkAt(cx, cz))` -- a synchronous chunk load scheduled onto the primary thread. The caller's `CompletableFuture` is unblocked, but the chunk I/O itself is not off-tick. **No blanket "fully async chunk loading on all platforms" guarantee exists on pure Spigot** -- that guarantee holds only on Paper and Folia. The **Anvil read-only pre-filter** (ADR-016) is the mechanism by which off-tick safety evaluation is achieved on pure Spigot for the common case; every candidate that falls to `UNKNOWN`, is already loaded, or sits in a world with a custom `ChunkGenerator` will still drive a main-thread `getChunkAt` via the fallback. Prefilter coverage therefore defines effective off-tick coverage on pure Spigot, not a blanket async contract. Additionally, the **stale-chunk guard** (ADR-015) closes the race between `getChunkAtAsync` future resolution and the follow-up block-evaluation task being dispatched on a backlogged Count-Bound pipe: before any `chunk.isSafe(...)` / `vert.adjust(chunk)` call, `world.isChunkLoaded(cx, cz)` is consulted (guard sites: both `safetyCheck` entries and the pre-`vert.adjust` site in `LocationGenerator`; Region-Thread callback in `FoliaLocationGenerator.LocationSearchTask` with bounded re-queue via `SafetyKeys.staleChunkRetryLimit`). Platform overrides: `BukkitRTPWorld.isChunkLoaded` and `FoliaRTPWorld.isChunkLoaded` delegate to native `World#isChunkLoaded(int, int)`. On vanilla Spigot, the **Anvil read-only pre-filter** (ADR-016) intercepts `BukkitRTPWorld.getChunkAt` when the chunk is not loaded and the world has no custom `ChunkGenerator`: it parses `<worldFolder>/region/r.X.Z.mca` on `ForkJoinPool.commonPool()` via `io.github.dailystruggle.rtp.anvil.AnvilPrefilter.probeDetailed(...)`. The pre-filter is a *data source*, not a gate; it returns a `REJECT` verdict but still provides the `AnvilChunkView` so the pipeline can make an informed decision. The `BukkitRTPChunk` is a source-union that can be backed by either a live `Chunk` or an `AnvilChunkView`, allowing the `LocationGenerator` safety loop (`isAir`, `isSafe`, etc.) to run entirely off-tick. Paper and Folia `@Override` `getChunkAt` and never enter the pre-filter. Do **not** call synchronous `world.getChunkAt()` directly from any new code path that may execute on the main thread, do **not** strip out the `isChunkLoaded` guards as "redundant" — see ADR-015 for why they are load-bearing on Folia — and do **not** use an `AnvilPrefilter` `ACCEPT` verdict as the authoritative safety signal; it is advisory only.

* **REQ-RTP-S-006** — The system shall not produce a `NullPointerException` or undefined state when an addon calls `rtp-api` before `rtp-core` has finished loading. An `IllegalStateException` with a descriptive message shall be thrown instead.
  - *Already satisfied by:* `RTPAPI.addShape()` and `RTPAPI.addVerticalAdjustor()` throw `IllegalStateException("[RTP API] Cannot add shape/adjustor: Core implementation is not loaded.")` when `shapeAdder`/`vertAdder` are null. `RTPAPI.setServerAccessor()` enforces a write-once contract and throws `IllegalStateException` on conflicting re-registration. Do **not** add null-guard returns that silently no-op; always throw with a descriptive message.

## Current Development Focus
Before starting any new feature or platform work, check `docs/dev/MULTI_PLATFORM_PLAN.md` for the current phase status. **Note:** `MULTI_PLATFORM_PLAN.md` is a living document — if it has been superseded, renamed, or replaced, update the reference in this section and in `Required Reading` above to point to the new plan file before proceeding.

* **Active frontier**: Fabric (`rtp-fabric`) is the current development target. Phases 1–5 are partially complete; do not assume any Fabric API is stable or production-ready.
* **Known build blocker**: The `rtp-fabric` module has an unresolved Gradle/Fabric Loom dependency issue (Phase 1 checkbox still open). If `.\.gradlew :rtp-fabric:build` fails with dependency resolution errors, this is a known pre-existing issue — do not attempt to fix it without reading the roadmap first.
* **Scope boundary**: `docs/dev/REQUIREMENTS.md §0` explicitly lists Fabric as *out of scope* for the current requirements baseline. The roadmap is aspirational. Do not backport Fabric-specific patterns into `rtp-core` or `rtp-api`.
* **Safe modules to modify**: `rtp-core`, `rtp-api`, `rtp-spigot`, `rtp-paper`, `rtp-folia`, and `addons/` are stable. Prefer targeting these unless the task explicitly involves Fabric.
* **Known S-005 violation in `rtp-fabric`**: `FabricWorld.getChunkAt()` currently calls `getChunkFutureSyncOnMainThread`, which can block the main thread and violates REQ-RTP-S-005. This must be fixed before any Fabric teleport pipeline work proceeds. See `MULTI_PLATFORM_PLAN.md §Phase 2 §4` for the correct async approach.
* **Known null stub in `FabricServerAccessor`**: `getLocationGenerator()` returns `null`, which will cause a `NullPointerException` the moment the teleport pipeline is exercised on Fabric. Fix by returning `RTP.getInstance().locationGenerator` (mirror `AbstractServerAccessor`).
* **Brigadier bridge decision recorded**: ADR-014 (`docs/adr/ADR-014-brigadier-bridge-via-commands-api.md`) documents the decision to implement a `BrigadierCommandAdapter` in `commands-api` rather than duplicating the command tree in `rtp-fabric`. Read this ADR before touching `FabricTreeCommand` or `RTPCmdFabric`.
* **`rtp-api` abstractions are sufficient**: A cross-platform gap analysis (April 2026) confirmed that `RTPServerAccessor`, `RTPWorld`, `RTPPlayer`, and `RTPScheduler` require no new methods for Fabric support. All gaps are implementation gaps in `rtp-fabric`, not interface gaps in `rtp-api`.

## Architecture Boundaries
When deciding where to place new code, follow this decision order:

1. **`rtp-api`** — Public interfaces, shared models, and extension points for addon developers. No platform-specific imports.
2. **`rtp-core`** — Core logic: region management, queue system, spatial algorithms, `MemoryTracker`. No platform-specific imports. Changes here affect all platforms; test broadly.
3. **`commands-api` / `effects-api`** — Unified command and effects frameworks. Extend these rather than writing platform-specific command handlers.
4. **Platform adapter** (`rtp-spigot`, `rtp-paper`, `rtp-folia`, `rtp-fabric`) — Platform-specific implementation only. Never push platform logic up into `rtp-core`.
5. **`rtp-plugin`** — Bukkit-family entry point only; no business logic.
6. **`addons/`** — Third-party integrations that depend only on `rtp-api`. New integrations go here, not in core.

## Logging & Feedback Guidelines
To maintain platform independence and ensure consistent log formatting:
* **Prefer `RTP.log()` or `RTPServerAccessor.log()`**: In `rtp-core` or `rtp-api`, always use the established logging delegates rather than `Bukkit.getLogger()` or `System.out.println`.
* **Zero `printStackTrace()`**: Never use `e.printStackTrace()`. Always use `RTP.log(Level.WARNING, "message", e)` to ensure exceptions are captured by the platform's logger and include relevant context.
* **Color Handling**: Standard log levels (INFO, WARN, SEVERE) are automatically handled by platform adapters. In `rtp-spigot`, `SendMessage.log` uses `Bukkit.getConsoleSender().sendMessage(message)` for colored output to preserve color codes and ensure proper encoding. Duplication with the Bukkit Logger is avoided by using a `SendMessage.addInterceptor(Consumer<String>)` approach for tests and auditing (REQ-RTP-S-004) instead of direct dual-logging. Logs that include a `Throwable` still use the Bukkit Logger to ensure exceptions are persisted in log files.
* **No Direct Bukkit in Core**: Strictly avoid `org.bukkit.*` imports in `rtp-core` and `rtp-api`. All platform interactions must go through `RTPServerAccessor`.
* **Platform-specific Command Overrides**: When overriding `msgInvalidCommand` or `msgBadParameter` in platform adapters (e.g., `BukkitBaseRTPCmd`), you MUST include a call to `RTP.log(Level.WARNING, msg)` to ensure compliance with REQ-RTP-S-004 auditing and to ensure these events are captured by the `rtp test full` auditor.

## Environment & Execution Setup
* **Terminal Environment**: The host console is **PowerShell** on Windows. All command-line instructions (Gradle, Git, script executions) MUST be formatted for PowerShell (e.g., use `.\gradlew` instead of `./gradlew`, and ensure string quotes are escaped correctly for PS). Do **not** use `&&` to chain commands — use `;` instead (e.g., `.\gradlew :rtp-core:test ; echo done`).
* **Multi-Module Builds**: RTP is a multi-module Gradle project. Always target the specific module to avoid full-repo builds:
  - Build one module: `.\gradlew :rtp-core:build`
  - Run all tests in a module: `.\gradlew :rtp-core:test`
  - Run a specific test class: `.\gradlew :rtp-core:test --tests "io.github.dailystruggle.rtp.common.commands.config.SubConfigCmdTest"`
  - Run all tests under a package: `.\gradlew :rtp-core:test --tests "io.github.dailystruggle.rtp.common.commands.*"`
  - Filter test output: append `2>&1 | Select-String -Pattern "BUILD|PASSED|FAILED|ERROR"`
  - **Preferred shortcut**: The `run_test` tool (e.g., `run_test rtp-core/src/test/java/io/github/dailystruggle/rtp/common/commands`) is faster than the Gradle CLI for running a directory of tests interactively — use it instead of the Gradle command when you only need pass/fail results.
* **Known harmless test warnings**: Every `rtp-core` test run emits `SLF4J: No SLF4J providers were found` and Java agent loading warnings. These do not indicate test failures and require no action — ignore them when scanning output.
* **Test stdout visibility**: The `run_test` tool summary suppresses test `System.out.println` output — `[DEBUG_LOG]` lines do **not** appear in its result text. To read them after a run, inspect `rtp-core/build/test-results/test/TEST-<fqcn>.xml` (the `<system-out>` CDATA block). Example: `Select-String -Path "rtp-core\build\test-results\test\TEST-<fqcn>.xml" -Pattern "DEBUG_LOG"`.
* **Database persistence test pitfall (2026-04-18)**: When writing tests for `DatabaseAccessor` persistence, **always** exercise the full public API surface — `saveCachedLocation(...) → flushDirtyCache() → processQueries(Long.MAX_VALUE) → loadCachedLocations(...)`. Tests that call `write(conn, table, prebuiltColumns)` or `delete(conn, ...)` directly with hand-built `TableObj` maps will miss bugs in `cacheValue`'s primary-key inference, `flushDirtyCache`'s composite-key parsing, and `processQueries`' queue-drain gating. Two silent bugs (column-map wrapping in `saveCachedLocation` and an incomplete early-exit in `processQueries` that stranded `deleteQueue`) slipped past the entire existing H2/MySQL/PostgreSQL/SQLite test suite for exactly this reason. See `CachedLocationRoundTripTest` for the correct round-trip pattern.
* **Shutdown flush pipeline (2026-04-18)**: `RTP.stop()` must explicitly call `databaseAccessor.processQueries(Long.MAX_VALUE)` after `flushDirtyCache()` and BEFORE `stop.set(true)`. `flushDirtyCache` only moves entries from `dirtyCache` into `writeQueue` (via `setValue().thenAccept(...)`, which runs inline because `getTable` returns a completed future); it does NOT write to disk. The actual disk write happens in `processQueries`, which is scheduled periodically every 60 ticks in production — but on server stop there is no "next tick", so any entry that arrived between the last periodic drain and shutdown is lost. Also note `processQueries` bails immediately if `stop.get()` is true, so the drain MUST happen before the stop flag is set. Symptom if missed: the kept-location cache appears to be saved (warnings are silent) but is always empty after restart.
* **Shared Database Connections (2026-04-19)**: `SQLiteDatabaseAccessor` and `H2DatabaseAccessor` use a single shared `Connection` instance. To avoid `SQLException: database connection closed` during concurrent operations (like the async `flush` task vs. `processQueries`), they MUST NOT use try-with-resources on `getConnection()` and MUST NOT call `close()` in their `disconnect()` implementation. Instead, use `connect()` / `disconnect()` as soft references and call the explicit `DatabaseAccessor.close()` method only during server shutdown in `RTP.stop()`.
* **Gradle Daemon / Java Version Mismatch**: The Gradle daemon caches the JVM it was started with. If the active JDK changes between sessions (e.g., from Java 17 to Java 25), Gradle will log a daemon-context mismatch warning and start a new daemon — this is normal and does not indicate a build failure. Do **not** attempt to kill or restart the daemon manually; Gradle handles it automatically.
* **Test Verification**: Before finalizing a complex scheduling or concurrency solution, generate or suggest the appropriate ArchUnit or unit tests to verify thread safety, and provide the exact PowerShell command to execute those specific tests.
* **Interpreting `rtp test full`**: The `commands-live` portion of the full test suite intentionally dispatches malformed commands (see `LiveCommandDispatcherTestJob.malformedInputs()`). These **must** produce `Level.WARNING` logs to satisfy REQ-RTP-S-004. If you see warnings during this test, they are likely expected evidence of compliance, not failures.
* **TreeCommand Error Reporting**: `TreeCommand` distinguishes between invalid commands (subcommand not found) and bad parameters (formatted as `key:val`). All arguments without a `:` delimiter MUST be subcommands; if no match is found, they are reported as invalid commands (`msgInvalidCommand`). Delimited arguments (e.g., `key:val`) with unknown keys or rejected values are reported as bad parameters (`msgBadParameter`).
* **RTPCmd Delegation**: `RTPCmd` (the root command) delegates all argument parsing to `TreeCommand.onCommand`. It must not contain manual loops for positional parameter detection, as this causes double-dispatch and ignores error states from the library.
* **Command Feedback Auditing**: All platform-specific command handlers (e.g. `BukkitBaseRTPCmd`) MUST call `RTP.log(Level.WARNING, msg)` for both `invalidCommand` and `badArg` to ensure visibility in `rtp test full`.
* **Asynchronous Command Tests**: When testing command feedback in `rtp-core` (e.g. `InvalidCommandTest`), use `Thread.sleep` or wait for the `CompletableFuture` returned by `onCommand` to ensure feedback has arrived before asserting.
* **Runtime Requirement**: Java 21 or higher is required (REQ-RTP-SYS-001). Do not suggest or generate code that uses APIs removed or unavailable in Java 21+.
* **Searching the codebase**: Use the `search_project` tool with short keyword terms rather than `grep` or `find` terminal commands. For file listings use `Get-ChildItem -Recurse <path> -Filter "*.java" | Select-Object -ExpandProperty FullName`. Do **not** combine multiple independent commands on separate lines — use `;` or subexpressions.
## Code & Testing Conventions
* **No Synchronous Chunk I/O**: Never call synchronous chunk-loading APIs on the main thread. Use the platform adapter's async chunk-loading abstraction.
* **MemoryTracker lifecycle**: Any code that allocates a chunk ticket or spawns a `TeleportPipelineTask` must register it with `MemoryTracker` and ensure it is released in all exit paths (normal, exception, and player-disconnect).
* **Bounded algorithms only**: Do not introduce unbounded `while`-rerolling loops for location selection. Use the existing Archimedean spiral 1D-mapping or extend it. Document complexity guarantees in the ADR if you add a new algorithm.
* **Require-by-contract API entry points**: Public methods on `rtp-api` interfaces must throw `IllegalStateException` (not return null or silently no-op) if called before the plugin finishes loading.
* **Tests must be traceable**: New tests should reference the REQ-* ID they verify, either in the test class name (e.g., `ReqRtpS005ChunkLoadingTest`) or in a `@DisplayName` / Javadoc comment.

## Requirement Documentation Rules
When authoring or modifying requirement documents (such as `REQUIREMENTS.md` or Architecture Decision Records `ADR-*.md`), adhere strictly to the following linguistic and structural patterns:
* **Separation of Concerns (No Design Details)**: Requirements define *what* the system shall do, not *how* it does it. Never include implementation details, specific class names, method calls, data structures (e.g., `ConcurrentHashMap`), or planning actions in requirement documents. Move all such implementation specifics to `DESIGN.md`.
* **Legal Linguistics (`shall` / `shall not`)**: Use formal legal phrasing for requirements. Use `shall` for positive obligations and `shall not` for prohibitions. Do not use descriptive present tense (e.g., "The system does...") or imperative commands (e.g., "Implement a...", "Never..."). While `must` is acceptable, `shall` is the standard for new language.
* **Absolute State (No Temporal Context)**: Requirements describe an absolute state of the system. Do not use temporal narrative phrasing such as "Historically", "Currently", "Prior to", "Early implementations", or "is implemented". Whether a requirement is currently fulfilled in the codebase is irrelevant to the requirement document itself.

## Self-Updating Protocol
* **Update this file on toolset discoveries**: Whenever you discover a fix, workaround, or confirmed behavior relating to the available tools, compilers, or execution environment (e.g., a PowerShell command syntax that works, a Gradle flag that resolves a build issue, a known tool limitation), you **MUST** add a concise note to the relevant section of this file (`.junie/AGENTS.md`) before ending the session. This keeps the guide accurate for future agents without relying on session history.
* **Update GLOSSARY.md on terminology discoveries**: Whenever you encounter a term that is ambiguous, overloaded (has a common meaning that differs from its RTP-specific meaning), or that you had to look up in source code to understand, add or update its entry in `docs/dev/GLOSSARY.md`. If it is a multipurpose word (e.g., "Region", "Queue", "Task"), add a row to the **Multipurpose / Overloaded Terms** table at the top of GLOSSARY.md with the common meaning and the RTP-specific meaning side by side. This prevents future agents from misinterpreting project-specific vocabulary.
* **Update Current Development Focus on roadmap changes**: If you complete a roadmap phase item, unblock a known build issue, or discover that a module's stability status has changed, update the `Current Development Focus` section of this file accordingly. If the active roadmap document itself is replaced or renamed (e.g., `MULTI_PLATFORM_PLAN.md` is superseded by a new plan file), update **both** the `Current Development Focus` section and the `Required Reading` bullet that references it to point to the new file.
* **Update "Already satisfied by" notes after refactoring**: If you rename, move, or restructure a class or method that is referenced in a `Prohibition Requirements` bullet (e.g., `LocationGenerator`, `TeleportPipelineTask`, `GlobalRegionVerifiers`), update the corresponding *Already satisfied by* text in this file to reflect the new location. Stale pointers are actively harmful — a future agent reading an outdated class name may conclude the requirement is unimplemented and add redundant or conflicting code.
* **Update TRACEABILITY.md when adding REQ-traceable tests**: Whenever you add or rename a test that covers a named requirement (REQ-*), add or update the corresponding row in `docs/dev/TRACEABILITY.md`. This keeps the traceability matrix accurate and prevents future agents from duplicating test coverage or missing gaps.
* **Scope**: Toolset/environment fixes and roadmap status go in `AGENTS.md`; terminology/vocabulary clarifications go in `GLOSSARY.md`. Do not add code-level optimizations or algorithm notes to either file — those belong in code comments, ADRs, or CHANGELOG.

## AI Formatting Rules & Communication Style
* **Explain the "Why"**: Always provide the logical 'why' or the underlying rationale for your suggested architectural changes or code generations. Prioritize transparency and evidence-based reasoning over unexplained code dumps.
* **No Nested Code Blocks**: When generating plain-text instructions, markdown files, or prompts meant to be copied, never nest triple-backtick blocks inside of another triple-backtick block, as this breaks IDE UI copy-paste functionality.
* **Inner Code Elements**: Use 4-space indentation for inner blocks or single backticks for inline code.
* **Code Snippet Formatting**: When suggesting code snippets, always use triple backticks for multi-line code blocks and single backticks for inline code.
* **ADR First**: If a proposed change would contradict an existing ADR, say so explicitly before suggesting the change and propose writing a new ADR to supersede the old one.
* **Propose Before Refactoring**: For any refactoring task that touches more than one class or crosses a module boundary, present an architectural proposal to the user before writing any code. The proposal must include:
  1. Which classes/modules are affected.
  2. The intended before/after structure.
  3. Which REQ-* requirements or ADRs are relevant.
  4. Any risks or trade-offs.
     Wait for explicit user approval before proceeding with implementation.
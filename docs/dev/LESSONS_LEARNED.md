# RTP Lessons Learned

Dated engineering notes, reproduction pitfalls, and non-obvious behaviors discovered during development. These are **not normative requirements** (see `REQUIREMENTS.md` for those) and **not architectural decisions** (see `docs/adr/` for those). They exist to save the next contributor from repeating a debugging session.

Each entry is dated so it can be pruned or superseded over time. When an entry becomes obsolete (e.g., the underlying bug has been fixed and is regression-guarded by a test), delete it and reference the guarding test in `TRACEABILITY.md` instead.

---

## Database & Persistence

### `DatabaseAccessor` persistence tests must exercise the full public API (2026-04-18)

When writing tests for `DatabaseAccessor` persistence, **always** exercise the full public surface:

    saveCachedLocation(...) → flushDirtyCache() → processQueries(Long.MAX_VALUE) → loadCachedLocations(...)

Tests that call `write(conn, table, prebuiltColumns)` or `delete(conn, ...)` directly with hand-built `TableObj` maps will miss bugs in:

- `cacheValue`'s primary-key inference,
- `flushDirtyCache`'s composite-key parsing, and
- `processQueries`' queue-drain gating.

Two silent bugs (column-map wrapping in `saveCachedLocation` and an incomplete early-exit in `processQueries` that stranded `deleteQueue`) slipped past the entire H2 / MySQL / PostgreSQL / SQLite test suite for exactly this reason. See `CachedLocationRoundTripTest` for the correct round-trip pattern.

### Shutdown flush pipeline (2026-04-18)

`RTP.stop()` must explicitly call `databaseAccessor.processQueries(Long.MAX_VALUE)` **after** `flushDirtyCache()` and **before** `stop.set(true)`.

- `flushDirtyCache` only moves entries from `dirtyCache` into `writeQueue` (via `setValue().thenAccept(...)`, which runs inline because `getTable` returns a completed future); it does **not** write to disk.
- The actual disk write happens in `processQueries`, scheduled every 60 ticks in production — but on server stop there is no "next tick", so any entry that arrived between the last periodic drain and shutdown is lost.
- `processQueries` bails immediately if `stop.get()` is true, so the drain must happen **before** the stop flag is set.

Symptom if missed: the kept-location cache appears to save (no warnings) but is always empty after restart.

### Shared database connections (2026-04-19)

`SQLiteDatabaseAccessor` and `H2DatabaseAccessor` use a single shared `Connection`. To avoid `SQLException: database connection closed` during concurrent operations (e.g., async `flush` vs. `processQueries`):

- **Do not** use try-with-resources on `getConnection()`.
- **Do not** call `close()` in their `disconnect()` implementation.
- Use `connect()` / `disconnect()` as soft references and call the explicit `DatabaseAccessor.close()` **only** during server shutdown in `RTP.stop()`.

---

## Command Pipeline

### `TreeCommand` error reporting

`TreeCommand` distinguishes between invalid commands (subcommand not found) and bad parameters (formatted as `key:val`):

- Arguments **without** a `:` delimiter must be subcommands; if no match is found, report via `msgInvalidCommand`.
- Delimited arguments (`key:val`) with unknown keys or rejected values report via `msgBadParameter`.

### `RTPCmd` delegation

`RTPCmd` (the root command) delegates all argument parsing to `TreeCommand.onCommand`. It must **not** contain manual loops for positional parameter detection — that causes double-dispatch and ignores error states from the library.

### Command feedback auditing (REQ-RTP-S-004)

All platform-specific command handlers (e.g., `BukkitBaseRTPCmd`) must call `RTP.log(Level.WARNING, msg)` for **both** `invalidCommand` and `badArg` to ensure visibility in `rtp test full`.

### Testing async command feedback

When testing command feedback in `rtp-core` (e.g., `InvalidCommandTest`), use `Thread.sleep` or await the `CompletableFuture` returned by `onCommand` to ensure feedback has arrived before asserting.

### `TreeCommand` subcommand dispatch hops the common pool when `whenCompleteAsync` is used (2026-05-17)

`TreeCommand.onCommand` enqueues the parent on `CommandsAPI.commandPipeline` (REQ-API-ARCH-006, tick-driven drain) whenever the first parsed token is a sub-command, and chains the sub-command's `onCommand` off a continuation future `cont`. Until 2026-05-17 that continuation was attached with `cont.whenCompleteAsync(...)` (no explicit executor), which routes onto `ForkJoinPool.commonPool`.

Combined with the pipeline enqueue, this means every multi-token `/rtp …` invocation paid:

1. One `CommandsAPI.commandPipeline` pulse latency (up to ~50 ms, by REQ-API-ARCH-006), then
2. A thread bounce onto `ForkJoinPool.commonPool` before the sub-command's logic ran.

A player typing `/rtp` (zero args) doesn't hit this path — the parse loop is skipped and `compute()` runs inline on the chat thread. A player clicking a book-menu row, however, sends `/rtp menu token:<…>`, which parses as `menu` sub-command on the `/rtp` root — so every menu click ate the pulse + hop. The visible symptom is a brief but consistent delay between clicking the row and the teleport beginning ("scheduler hop on Teleport me now"), plus a latent Folia thread-context hazard: `MenuRedeemSubcommand.dispatchRun` (and ultimately the bare-root `compute()`) executed on a `commonPool` worker rather than the click thread.

Fix: change `cont.whenCompleteAsync(...)` to `cont.whenComplete(...)` (no executor) at `commands-api/.../TreeCommand.java:259`. The continuation then runs on whatever thread completes `cont` — i.e. the platform-driven `CommandsAPI.execute()` drain thread, which the bridge owns and which is already main/region-correct. The pipeline-pulse latency is still present (architecturally intended), but the second thread hop is gone and the Folia hazard is closed.

Note: removing the pipeline enqueue entirely is **not** the right fix — `REQ-API-ARCH-006` requires the tick-driven drain, and other root-command pre-processing in `RTPCmd.onCommand` relies on `nextCommand != null` short-circuiting which only fires via the executor path. If the residual one-pulse delay becomes user-visible, prefer pushing the menu redeem path off the `TreeCommand` parser entirely (dedicated listener that calls `MenuRedeemSubcommand` directly with the parsed token) over weakening the pipeline contract.

---

## Test Infrastructure

### Harmless `rtp-core` test warnings

Every `rtp-core` test run emits `SLF4J: No SLF4J providers were found` and Java-agent loading warnings. Ignore them — they do not indicate test failures.

### `run_test` suppresses stdout

The `run_test` tool summary suppresses test `System.out.println`; `[DEBUG_LOG]` lines do **not** appear in its result text. To read them, inspect `rtp-core/build/test-results/test/TEST-<fqcn>.xml` (the `<system-out>` CDATA block):

    Select-String -Path "rtp-core\build\test-results\test\TEST-<fqcn>.xml" -Pattern "DEBUG_LOG"

### Interpreting `rtp test full`

The `commands-live` portion of the full test suite intentionally dispatches malformed commands (see `LiveCommandDispatcherTestJob.malformedInputs()`). These **must** produce `Level.WARNING` logs to satisfy REQ-RTP-S-004; warnings there are evidence of compliance, not failures.

---

## Performance & Throughput

### Vanilla `addRegionTicket` / `addTicketWithRadius` third arg is *distance*, not *level* (2026-05-06)

On Fabric (and any direct-vanilla-API caller), the third argument of:

- `DistanceManager#addRegionTicket(TicketType, ChunkPos, int distance, T value)` (≤ 1.21.4)
- `ServerChunkCache#addTicketWithRadius(TicketType, ChunkPos, int radius)` (≥ 1.21.5)

is a **distance/radius in chunks**, not the underlying effective ticket level. Vanilla converts via `effectiveLevel = ChunkMap.MAX_CHUNK_DISTANCE - radius` (i.e. `33 - radius`). For a chunk to reach `FULL` (block reads valid, `hasChunk` true), `effectiveLevel` must be ≤ 33; for `ENTITY_TICKING` (parity with `TicketType.FORCED` and Bukkit's `addPluginChunkTicket`), use **`radius = 3`** → effective level `30`.

Passing `31` into this slot under the mistaken belief it is the ticket level either:

- Requests effective level `2` (clamped/rejected on the `addRegionTicket` path); ticket never lands at the expected level and the chunk is not pinned at `FULL`. Symptom: `RTPWorld#chunkTickets` ref-count shows the ticket as held, but `world.getChunkSource().hasChunk(cx, cz)` returns `false` shortly after — kept-cache entries silently unpin.
- Requests a `(2·radius + 1)² = 3969`-chunk square force-load on the `addTicketWithRadius` path; clamped/rejected by the chunk system, same end state.

When the kept-cache invariant breaks, the consumer `/rtp` path falls back to `FabricRTPWorld.getChunkAt`'s synchronous `cache.getChunk(cx, cz, ChunkStatus.FULL, true)` on the server tick (~14 ms/command vs Bukkit's ~1 ms). See `rtp-fabric-ADR-006`.

When auditing a new per-version Fabric adapter (or any non-Bukkit platform that talks to the chunk system directly), verify after `applyTicket` that `world.getChunkSource().hasChunk(cx, cz)` is `true` and remains `true` until `releaseTicket` fires; sample over a multi-second window because `TicketType.UNKNOWN` carries a built-in timeout and any auto-expiry will surface within a few ticks.

### Don't borrow `TicketType.UNKNOWN` for kept-cache pinning on 1.21.5+ (2026-05-06)

Follow-up to the previous entry. `javap` on the Mojmap-remapped 1.21.5 server jar (`minecraft-merged-…-v2.jar`, path under `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/`) confirms `TicketType` is now a record `(long timeout, boolean persist, TicketType.TicketUse use)` with `TicketUse ∈ { LOADING, SIMULATION, LOADING_AND_SIMULATION }`, and the public static `UNKNOWN` constant is registered with `timeout = 1L` (a **1-tick** auto-expiry, not 1 second) and `use = LOADING`. Both are wrong for kept-cache pinning:

- `timeout = 1L` ⇒ the chunk evicts on the next tick after `applyTicket`, even if `removeTicketWithRadius` is paired with it.
- `use = LOADING` ⇒ no entity ticking, so the chunk does not match the Bukkit `addPluginChunkTicket` `ENTITY_TICKING` end state even if the timeout were ignored.

Vanilla `FORCED` is the right shape (`timeout = 0`, `use = LOADING_AND_SIMULATION`), but its `persist = true` writes into `level.dat#ForcedChunks` (S-002 hazard). The fix used by `V1_21_R5FabricVersionAdapter` is a single static instance constructed via the public record constructor: `new TicketType(TicketType.NO_TIMEOUT, /*persist=*/ false, TicketUse.LOADING_AND_SIMULATION)`. Identity-equality is what `addTicketWithRadius` / `removeTicketWithRadius` compare on, and reusing one static instance for every call from the adapter keeps add/remove paired. No registry call is needed (so no chunk-subsystem class-init ordering hazard). See `rtp-fabric-ADR-006`.

To verify a `TicketType` constant's actual `timeout` / `use` on any future MC patch, extract the class and run `javap -p -c` on it — the `<clinit>` block lists the literal `register("name", timeout, persist, use)` call sequence (e.g. `ldc "unknown"; lconst_1; iconst_0; getstatic …TicketUse.LOADING; invokestatic …register`). The `Loom`-cached Mojmap jar lives at `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/<mc>-loom.mappings…/minecraft-merged-<mc>-loom.mappings…-v2.jar` and is the right artifact to extract from (the intermediary jars under `~/.gradle/caches/fabric-loom/<mc>/minecraft-*.jar` do not contain the Mojmap-named class).

### Never `Semaphore.acquire()` blockingly from a common-pool worker — self-reschedule instead (2026-05-08)

Companion lesson to `rtp-fabric-ADR-008`. The original `FabricRTPWorld.liveLoadPipe` (a 2-permit `Semaphore` gating entry into `loadLiveChunk`) was acquired with a blocking `acquire()` call from inside a `CompletableFuture.runAsync(...)` lambda — i.e., from a `ForkJoinPool.commonPool` worker. When the two permit-holders parked on the tick thread (the deeper bug fixed by ADR-008), every other commonPool worker that reached the gate parked on `Semaphore$FairSync`. The 2026-05-08 crash report shows all 14 commonPool workers stuck there simultaneously, freezing every other `CompletableFuture` chain on the JVM (not just RTP's).

The deeper principle, separate from ADR-008's "don't block the tick thread": **a bounded-pipe primitive that lives on the common pool must never `Semaphore.acquire()` (or any other indefinite park) on contention.** When no permit is available, the correct shape is to *self-reschedule* and free the worker:

```java
if (!gate.tryAcquire()) {
    return CompletableFuture.supplyAsync(
        () -> /* retry */,
        CompletableFuture.delayedExecutor(BACKOFF_MS, TimeUnit.MILLISECONDS)
    ).thenCompose(f -> f);
}
```

This keeps the worker available for unrelated work while back-pressure is in effect, can't deadlock if permit-holders block on a different scheduler, and degrades gracefully under load instead of cliff-edge stalling. The blocking shape is only safe on a thread you own (a dedicated executor) where you've reasoned about the *full* dependency graph of every task that could hold a permit — a property that's almost never true on the shared common pool.

`ScanTask.inFlightGate` is the project's canonical bounded-pipe pattern; it's safe specifically because its permit-holders complete on the same scheduler that drives the gate, not on a foreign one (the tick thread, an IO worker, etc.). When the holders' completion path *does* live on a foreign scheduler, prefer the `tryAcquire` + `delayedExecutor` shape above. ADR-008 sidestepped the question by removing `liveLoadPipe` entirely (vanilla's chunk system already provides back-pressure), but if a future bounded pipe is needed in `FabricRTPWorld` or any other adapter, this is the shape to reach for.

### Don't `.orTimeout` chunk-load futures at orchestration sites — let the world adapter own the per-chunk deadline (2026-05-08)

Companion to ADR-008 and the `Semaphore.acquire()` lesson above. `rtp-core`'s `QueueTask` and `PregenTask` historically wrapped every `world.getOrLoadChunk(cx, cz)` and `world.getChunkAt(cx, cz)` neighbour-grid `allOf` in `.orTimeout(5, SECONDS)`. The reasoning at the time was "bound the wait so a stuck chunk can't pin an attempt forever". The reality on Fabric (1.20.1 vanilla chunk system, ADR-008 non-blocking dispatch in place) is that cold-start generation routinely takes 5–10 s under early-server scan/pre-fill load — long enough for the wrapper to fire and reject *every* in-progress generation that would have completed seconds later, while the `Region.execute()` pulse already provides natural per-attempt budgeting.

Two anti-patterns this exposed:

- **The wrapper does not cancel the underlying load**, only the future the orchestrator is waiting on. Vanilla generation keeps running and warms `rtpChunkCache` for the next attempt at the same coordinate — but the current attempt is rejected and the `[RTP] getOrLoadChunk failed … TimeoutException: null` log line is emitted, framing useful pre-fetch work as a failure.
- **Orchestration sites have no knowledge of the underlying chunk system's SLA.** A 5 s ceiling is right for Folia (Paper chunk-system-v2, parallel) and roughly right for Paper, but wrong for Fabric vanilla and unknowable for any future platform. Hardcoding it cross-platform meant every platform either lived with the wrong cap or had to be papered over with adapter-side `completeOnTimeout` shims that "undercut" the orchestration cap (the early shape of `FabricRTPWorld.getOrLoadChunk`).

The architectural rule, going forward: **per-chunk timeouts live inside the `RTPWorld` adapter at the leaf where it actually calls the server.** The adapter is the only layer that knows when "the server is incapable of loading this particular chunk" — `FabricRTPWorld` uses `completeOnTimeout(null, FABRIC_GENERATION_DEADLINE_MS)` at the live-load leaf for exactly this reason. Orchestration code (`QueueTask`, `PregenTask`, `ScanTask` outside its own scan-budget wrapper) should chain `.thenAccept` / `.whenComplete` and let the future complete naturally; rejection on `null` / exception still flows through the existing `FailTypes.nullChunk` attribution path, so `S-004` is preserved.

The 2 s `reservation.readyFuture().orTimeout(2, SECONDS)` at `QueueTask:306` / `PregenTask:433` is *not* a chunk-load deadline — it's the ADR-015 ticket-apply race, a self-contained synchronization on a future that the adapter completes deterministically as soon as the ticket lands. Leave it alone.

If you find yourself reaching for `.orTimeout` on a chunk-load future at an orchestration site, push the timeout down into the relevant `RTPWorld` override instead.

### Don't reschedule via `continueInline` from a `CompletableFuture` callback — submit fresh (2026-05-08)

When a self-rescheduling task (e.g. `PregenTask.rescheduleNextAttempt`) is invoked from a `CompletableFuture.whenComplete` / `thenAccept` callback, calling the task's `run()` (or any equivalent inline continuation) on the callback thread attaches the next attempt's *new* dependents (`whenComplete`, `allOf`, etc.) as children of the current callback's source future. The source future is itself retained as a dependent of an upstream future, so the chain compounds: each iteration grows the `BiApply` / `CoCompletion` / `BiRelay` graph by one node, and nothing drains until the *original* root future at the top of the chain completes. With `maxAttempts × in-flight pregens × neighbour-grid allOf` depth this is multiplicative.

Heap-histogram signature observed 2026-05-08 on Fabric 1.20.1 (no pregen, C2ME) — `~33 M BiApply + 44 M CompletableFuture + 21 M CoCompletion + 8 M BiRelay`, **~6 GB retained on a 16 GB heap**, with RTP's own data structures (kept-cache 6, active tickets 6, L3 backlog 0) holding well under 1 GB. The graph itself was the leak.

The architectural rule: **a self-rescheduling task that re-enters via a `CompletableFuture` callback must always submit fresh on the async scheduler**, never call its own `run()` (or any `continueInline`-style trampoline) directly from the callback. The trampoline / `inRunAttempt` flag pattern is correct *only* for the synchronous in-loop case; the async-callback path must hop:

```java
if (inRunAttempt) {
    needsReschedule = true;                                   // synchronous trampoline
} else {
    RTP.serverAccessor.getScheduler().runTaskAsynchronously(this); // CF callback path
}
```

This mirrors the original Folia fix shape (`getChunkAtAsync` → `.thenAccept` lets the prior `Region.execute()` pulse end before the next attempt starts). Failure mode crept in during the timeout-removal pass when `.orTimeout` was replaced by `continueInline(this::rescheduleNextAttempt)` from inside `whenComplete` callbacks.

`continueInline(...)` is still safe for *fall-through within a single attempt* (bounded depth, e.g. probe-fast-path falling back to full path) — the multiplicative growth requires re-entry into the *outer* loop. If you reach for `continueInline(this::rescheduleNextAttempt)` (or any equivalent) from inside a CF callback, that's the bug.

### Never chain RTP continuations inline on a vanilla chunk-holder future (26.x DistanceManager CME) (2026-06-20)

`V26_1_R1FabricRTPWorld` / `V26_2_R1FabricRTPWorld` crashed every tick (and again at shutdown) on a deterministic, single-thread NPE inside vanilla code:

```
NullPointerException: Cannot invoke "...ReferenceArrayList.get(int)" because "this.wrapped" is null
    at it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet$SetIterator.next(...)
    at net.minecraft.server.level.DistanceManager.runAllUpdates(DistanceManager.java:85)
    at net.minecraft.server.level.ServerChunkCache.runDistanceManagerUpdates(...)
```

`wrapped == null` in a fastutil `ReferenceOpenHashSet$SetIterator` is the signature of the set being *structurally modified while being iterated*. `runAllUpdates` iterates `DistanceManager.chunksToUpdateFutures` twice (calling `ChunkHolder.updateHighestAllowedStatus` then `updateFutures`) and then `clear()`s it. The `DistanceManager`/`ServerChunkCache`/`TicketStorage`/`TicketType` classes are byte-identical between 26.1.2 and 26.2 (verified via `javap` on the loom-cached deobf jars), so this was never a vanilla-version diff — it was RTP re-entering the chunk system mid-iteration on the **server thread**.

Mechanism: the `V26_x_R1FabricRTPWorld.getChunkAt` chain attached RTP continuations directly (`thenApply` / `thenCompose`) onto the `CompletableFuture` returned by vanilla `ServerChunkCache#getChunkFuture(..., FULL, create=true)`. The 26.x chunk system completes a `ChunkHolder`'s full-chunk future **inline on the server thread** from inside `runAllUpdates` (`ChunkHolder.updateFutures` -> `CompletableFuture.complete`). So RTP's whole pipeline ran inline mid-iteration; when the rtp-core neighbour-grid step then called `world.getChunkAt(neighbour)` -> `MinecraftServer.submit(...)`, `submit` runs the supplier **inline when already on the server thread** (`BlockableEventLoop` same-thread fast path), so `getChunkFuture(create=true)` added a generation ticket and mutated `chunksToUpdateFutures` while it was being iterated -> CME.

Fix: hop RTP's first continuation off the completing thread. `requestChunkFuture` now uses `thenApplyAsync(unwrapChunk, RTP_CONTINUATION_EXECUTOR)` (the executor routes to `RTP.scheduler.runTaskAsynchronously`), so the unwrap and every downstream RTP continuation run on the async pool, and the downstream `submit` queues to a safe between-tick point instead of running inline. This is the same off-thread self-dispatch rule rtp-fabric-ADR-008 established for the unobf carrier's `loadLiveChunk`; the per-version 26.x `RTPWorld`s had reintroduced the inline pattern.

Rule for any non-Bukkit adapter that talks to the chunk system directly: never let RTP's own pipeline run as an *inline* dependent of a vanilla chunk future. Always force the first continuation onto an RTP-owned executor (`*Async` with `RTP.scheduler`), because vanilla may complete that future synchronously from inside `DistanceManager.runAllUpdates` where any further ticket/`getChunkFuture` call (including an inline same-thread `MinecraftServer.submit`) corrupts the live iteration.

### Paper 26.1 scan throughput parity with Spigot/Folia (2026-04-21)

Paper 26.1 with the non-blocking `LocationGenerator` state machine (ADR-015 post-refactor) achieves roughly **300 cps effective scan throughput**, on parity with the Spigot/Folia Anvil-based scan path. This confirms that ADR-016 §1.1 (the adapter-internal `[RTP] Anvil gate skipped reason=chunk-already-loaded` gate firing on essentially every candidate on Paper chunk-system-v2) is **not** a performance regression relative to the pure-Anvil path — Paper's live-chunk `getBiome` on an already-loaded chunk is cheap enough to close the gap.

Important caveat: the ~300 cps figure is a **batched / parallel effective throughput** (candidates-per-wall-second aggregated across the `scan` command's parallel `allOf` chunk-load batches). It is **not** a per-chunk read latency and must not be divided out as `1/N` to reason about per-stage cost — dividing underestimates true per-chunk latency by roughly the scan batch's parallelism factor.

For a genuine per-chunk latency number, use the serial `--samples N` harness in the `rtp test/async-chunk-load` sub-command (reports p50/p95/p99 over N serial `getChunkAt` probes along a non-spawn spiral). That harness deliberately walks one candidate at a time and is the correct signal to compare between Paper chunk-system versions and/or before/after ADR-015 / ADR-016 changes.

---

## Localization

### Locale-switch migration in `ConfigParser` (2026-04-26)

When `language.yml` is edited from `en` → another locale and the server reloads, `ConfigParser.detectAndPreserveLocaleMismatch` re-extracts `lang/<locale>/<file>.yml` from the JAR over the on-disk file (after backing it up to `<name>.old<n>`) and re-applies user-customized scalar values keyed by enum so they survive under the new locale's key names. Three pitfalls the regression suite (`ConfigParserLocaleSwitchTest`) guards against:

- **Stale `fileDatabase` cache.** After re-extraction, evict `cachedLookup` / `cachedLookupLastModified` for the file or the next read returns the previously-loaded English `YamlFile` and every lookup via the new locale's key names returns `null` (silent blank lines, e.g. the original `rtp info` Spanish report).
- **Identity mappings carry no locale signal.** A `<file>.lang.yml` line where left == right (`infoTickets: infoTickets`) must not count as evidence the on-disk file is already in the active locale. The detector explicitly excludes identity entries from `activeLocaleHits`; otherwise files like `messages.yml` that happen to retain English-named identity keys short-circuit migration falsely.
- **No-rename locale ⇒ no migration.** Files like `integrations.yml` that have no localized JAR resource get a seeded identity-only `language_mapping` from `loadLangFile`. Treating them as "foreign" causes an infinite re-backup loop on every reload. The detector short-circuits when **every** mapping entry is identity (i.e. the active locale renames zero keys for this file).

When adding a new translatable file or locale, see `TRANSLATION_GUIDE.md`. The `LocaleResourceParityTest` enforces the on-disk shape contract that this migration depends on.

---

## Pre-Generation & Shutdown

### Chunky-generated chunks may never reach disk without a forced `World.save()` (2026-05-01)

Discovered while building `helpers/PeriodicWorldSaver`. On a Spigot 1.20.1 server running Chunky unattended (no players online), the `world/region/` directory **did not grow at all** across multiple multi-hour Chunky runs — every restart Chunky began from scratch as if the prior run had produced nothing. Bukkit's `ticks-per.autosave` (even lowered from 6000 → 600) did not help, and `/stop` froze for minutes on the final flush.

Root cause hypothesis (consistent with observation): Chunky's generated chunks accumulate in RAM but never become eligible for Bukkit's autosave path — autosave walks the dirty-chunk set, but Chunky's chunks either aren't flagged dirty in the way autosave checks or are continuously held by Chunky's own tickets so the "save on unload" path never fires. Result: chunks live and die in RAM; `/stop`'s final flush is the first time the server actually tries to persist them, and it can't finish in time.

Fix that worked: a periodic `world.save()` call (currently every 60 s while no players are online, via `helpers/PeriodicWorldSaver`). After installing it, `region/` size grows steadily during the Chunky run and `/stop` returns promptly. The accompanying `unloadChunk` sweep is **not** the lever — it returns false on essentially every Chunky-ticketed chunk; the `world.save()` call is what does the work.

Implications:

- Do not assume `ticks-per.autosave` covers pre-generator output. It does not, at least with Chunky.
- For any unattended pre-generation workload (Chunky, WorldBorder fill, custom generators), a forced periodic `World.save()` from a separate plugin is currently the only reliable way to bound RAM and avoid shutdown freezes on Spigot.
- The `unloadChunk` portion of the helper is kept because it is cheap and harmless, and it picks up the small tail of chunks Chunky has already released. The `world.save()` portion is load-bearing.

See `helpers/PeriodicWorldSaver/README.md` for the runtime model. Folia is excluded because the main-thread sweep would hit `ThreadAccessException`; a `RegionScheduler` variant remains future work.

---

## Stress Testing

### Calling EssentialsX `/tpr` causes teleport timeouts on Spigot under StressTestRTP (2026-05-02)

Discovered while running the `helpers/StressTestRTP` measurement sequence against a Spigot test server. When the EssentialsX `/tpr` target is included in the roster, the harness records a high and reproducible rate of teleport timeouts (attempts that never produce a `PlayerTeleportEvent` within the per-attempt deadline) **for that target's slice**. Removing the EssentialsX `/tpr` target from the roster — with EssentialsX itself still installed and all other plugins, world state, and StressTestRTP configuration unchanged — eliminates the timeouts and the remaining targets complete cleanly. It is the act of dispatching `/tpr`, not EssentialsX's mere presence in `plugins/`, that drives the timeout storm.

The failure mode is not a crash or a logged exception — it surfaces only as `MetricsRecorder.onTimeout` entries in the CSV and the `[StressTestRTP]` warm-up "zero successful attempts" warning when timeouts dominate the `/tpr` slice. Hypothesis (not yet root-caused): EssentialsX's `/tpr` handler (cooldown/warmup interception, request-accept handshake, or the way it ultimately fires the teleport) does not produce a `PlayerTeleportEvent` of the kind the probe is waiting on within the per-attempt deadline — possibly because `/tpr` is a teleport-request command rather than a direct teleport. Paper/Folia have not been observed to reproduce this under the same harness.

Implications for anyone running stress tests:

- Treat the EssentialsX `/tpr` target as a **known timeout source** for StressTestRTP on Spigot. Either drop it from the roster or expect that target's timeout numbers to reflect EssentialsX `/tpr` semantics, not RTP behavior.
- Do not use a Spigot run whose timeout count is dominated by the `/tpr` slice as evidence of an RTP regression without first re-running with `/tpr` excluded.
- If `/tpr` must remain in the roster (e.g., to measure interaction), document it explicitly in the run's CSV header / notes so the numbers aren't compared against rosters where it was excluded.

Recorded as a potential third-party interaction worth a deeper investigation when stress-testing priorities allow; an `EXTERNAL_HOOKS.md` row is **not** warranted yet because RTP adds no reflection or soft-depend for EssentialsX — the interference is purely runtime event ordering when `/tpr` is dispatched on Spigot.

---

## Build & Environment

### Gradle daemon / Java version mismatch

The Gradle daemon caches the JVM it was started with. If the active JDK changes between sessions (e.g., Java 17 → Java 25), Gradle logs a daemon-context mismatch and starts a new daemon. This is normal; do **not** kill or restart the daemon manually.

## 2026-05-02 - StressTestRTP spark profiles empty when --only-ticks-over is set

Symptom: a stress run finishes, .sparkprofile files appear in plugins/spark/profiles/, but opening them on the spark viewer shows little/no Server Thread sample data - even though some ticks visibly exceeded the threshold in /spark tickmonitor.

Cause: `StressTestRTP`'s `SparkHook` was passing `--only-ticks-over 50` by default. Spark's tick filter discards every sample taken during a tick that did not cross the threshold, evaluated per-rotation-slice. On idle phases, warm-up windows, or short rotation slices that happen to miss a spike, the saved profile contains zero qualifying samples and renders as empty.

Fix: changed default `spark.only-ticks-over-ms` from 50 to 0 in `helpers/StressTestRTP/src/main/resources/config.yml` and the matching fallback in `SparkHook.startSliceInternal`. With 0, spark captures every sample so baseline RTP work is always visible; spike-only filtering is now opt-in.

If you intentionally want spike isolation, set `spark.only-ticks-over-ms` to a positive value AND ensure `spark.rotate-seconds` is long enough that most slices contain at least one over-threshold tick, otherwise some slices will still come back empty.

## 2026-05-02 - StressTestRTP "JakesRTP" rows in §5b–§5e were our own RTP plugin (Bukkit command-map collision)

When two Bukkit plugins register the same command label (here `/rtp`: ours and JakesRTP's), Bukkit's `SimpleCommandMap` keeps a single `PluginCommand` instance per label, and the namespaced lookup (`jakesrtp:rtp`) can resolve to whichever plugin won the registration race rather than to the namespace-owner. On the Spigot 1.20.1 test rig this resolved `jakesrtp:rtp` to **our** plugin — confirmed in-game by `/jakesrtp:rtp <TAB>` returning our subcommands. Every "JakesRTP" row in `helpers/StressTestRTP/PRE_WRITEUP.md` §5b–§5e is therefore a second `rtp` measurement under a different label. The "JakesRTP parity with RTP" headline reduces to "RTP measured against itself, twice — reproducibility OK", which is fine as a reproducibility datapoint and useless as a cross-plugin comparison.

Workaround for future StressTestRTP runs: drive JakesRTP via its own admin command `jakesrtp:forcertp {player} -c default-settings` (registered only by JakesRTP, no collision possible). This requires `dispatch-as-player: false` for that target because `jakesrtp.others` is `default: op`. Landed in `helpers/StressTestRTP/src/main/resources/config.yml` on the same date.

General lesson for any future cross-plugin comparison harness: never trust the namespaced form (`<plugin>:<command>`) to bypass a label collision — verify with `/version` and `<TAB>`-completion before publishing numbers. Same trap applies to BetterRTP (also registers `/rtp`) and any other RTP-style plugin that doesn't use a unique top-level command label.

---

## 2026-06-17 - StressTestRTP chunks/att was inflated by the post-teleport arrival ring; `ChunkLoadCounter` now reports a view-distance-corrected `chunks_selection`

The per-attempt chunk metric (`chunks_loaded_attributed` / `chunks_per_attempt`) was overcounting by one to two orders of magnitude on every plugin: a Folia 26.1.x RTP run reported 28.4 chunks/att when the real destination-selection cost is ~1. Root cause: `ChunkLoadCounter` charges every `ChunkLoadEvent` that fires while an attempt is in flight to that attempt, which sweeps in the `(2*viewDistance+1)^2` render-distance square the server loads when the player materialises at the destination. That arrival ring is server-caused, plugin-independent, and scales with view distance, so it is the bulk of the reported count and is useless as a *pipeline* cost signal (it was the "post-arrival view-distance follow-up" confound flagged but unaddressed in the 2026-05-02 §5h entry below).

Fix (harness-only, `helpers/StressTestRTP`): `ChunkLoadCounter` now records each attributed load's packed chunk coordinates per attempt and, at `endAttempt`, classifies them against the destination chunk (known from the completed teleport): a load within `viewDistance + 1` chunks (Chebyshev) of the destination, other than the destination chunk itself, is arrival cost and excluded; the remainder is selection cost. The corrected value is exposed as the per-attempt `chunks_selection` column and the per-phase `chunks_selection` / `chunks_selection_per_attempt` columns; the raw `chunks_loaded_attributed` columns are retained for audit. Timeouts / failures (destination unknown) fall back to the raw count, and the ring radius reads the live `Bukkit.getViewDistance()` unless overridden via `ChunkLoadCounter#setViewDistance`. Reducing the server view distance shrinks the ring and is the empirical cross-check that the inflation was arrival, not pipeline.

Caveats: this removes the arrival-ring confound but **not** the intra-plugin `ScanTask` background-fill confound from the §5h `‡` footnote (scan loads are far from the destination, so they survive the distance gate and still inflate the raw attributed count); for a pure pipeline-cost number, disable RTP's queue/scan as in the §5e queues-off configuration. Cross-plugin chunk-cost comparisons should now quote `chunks_selection_per_attempt`. New columns are appended at the end of both CSVs so existing parsers (`spark_summary.py`, downstream scripts) stay compatible.

---

## 2026-05-02 - Under saturating offered teleport load on Spigot 1.20.1, server TPS is a tick-saturation floor; delivered TP/s is the discriminator (rewrite #3)

**Rewritten 2026-05-02 (third pass) after recognising the saturating-load framing.** With `per-player-gap-ticks: 0` and per-plugin pre-queues disabled, the harness re-dispatches each player as soon as the previous attempt completes, so *offered* load is constant and saturating: the only knob the plugin controls is how long each attempt takes. Under that condition the server's main thread is pinned at ~100 % CPU on every phase and `last1m` is dragged to a tick-saturation floor common to every plugin — it cannot drop below it without dropping ticks, and it cannot rise above it without idle headroom that doesn't exist. The discriminating signal is therefore **delivered TP/s** (`successes / wall_s`), not server TPS. Earlier rewrites of this entry (calling the floor a "plugin-independent steady-state TPS") were technically correct about the floor but mis-framed the comparison axis; this rewrite supersedes them.

The §5e run kept the same two clients and the same four plugins as §5b–§5d, but changed three things at once:

1. Per-plugin pre-queues (RTP `keptLocations`, BetterRTP `Queue.Enabled`, JakesRTP queue) **disabled** so that chunk loading happens on dispatch rather than during a hidden warm-up.
2. `sequence.per-target-seconds: 300` (5-min phases) so each phase reaches steady state instead of ending in the warm-up minute.
3. `ChunkLoadCounter` rewritten as per-attempt (Paper plugin-tickets → main-thread temporal fallback on Spigot), so `chunks_loaded_during_attempt` and the new `chunks_loaded_attributed`/`chunks_loaded_background` phase columns reflect causal attribution rather than the global counter.

What §5e shows at a glance:

| Phase | Att | Succ% | **TP/s** | main CPU/att (ms) | end-of-phase `last1m` | p99 latency (ms) |
|---|---:|---:|---:|---:|---:|---:|
| betterrtp slot 1 | 316 | 99.1 | 1.05 | 797 | 3.40 | 4894 |
| huskhomes slot 2 | 386 | 99.5 | 1.28 | 713 | 4.87 | 4020 |
| jakesrtp slot 3  | 480 | 100.0| 1.59 | 587 | 3.90 | 1457 |
| **rtp slot 4**   | 498 | 100.0| **1.66** | **565** | 3.94 | 1454 |

TP/s ranks **inversely** with main-thread CPU/att, as expected for a single-threaded pipeline saturating the tick: `TP/s ≈ 1000 / main_CPU_per_attempt_ms` matches the measured ratios within ~10 %.

What this settles:

- **The "monotonic carry-over across phases" claim is withdrawn outright.** `rtp` ran in **slot 4** here at full success and ended its phase in the same TPS band as `betterrtp` in slot 1. The 11→4 decay shape repeated identically inside every phase, regardless of order — it is `last1m` rolling-window arithmetic catching up to steady state, not chunk residency.
- **Cross-phase chunk residency is not accumulating.** Spark's loaded-chunk count is flat at ~1600–1800 across all four 5-min phases. The §5b–§5d 60 s windows ended before this could be observed; once a phase is long enough to reach steady state, the loaded-chunk count just stabilises.
- **Delivered TP/s varies by 1.6× across plugins** under saturating offered load: `rtp 1.66 > jakesrtp 1.59 > huskhomes 1.28 > betterrtp 1.05`. This is the publishable throughput axis for Spigot 1.20.1.
- **Server TPS is a tick-saturation floor (~3.4–4.9 `last1m`)** common to every plugin in this configuration. The main thread is pinned at 100 % CPU and `last1m` is dragged to whatever rate the slowest stage allows; the floor reflects Spigot's synchronous chunk-gen ceiling, not a per-plugin signature. **Do not publish server TPS as a per-plugin comparison number from this configuration**; publish TP/s (and main-CPU/att) instead, and use server TPS only to document that every plugin saturates the tick at this offered rate.
- **Latency tails *do* discriminate plugins** at this configuration. RTP and JakesRTP both deliver tight p99 (~1455 ms) at 100 % success; BetterRTP and HuskHomes show 3–5× wider tails (p99 4894 / 4020 ms) and a small number of timeouts. With queues off, p99-success latency is the cleanest single number for cross-plugin comparison on Spigot.
- **`rtp` main-thread CPU/attempt is reproducible across four independent runs**: §5b 572 ms, §5c slot 1 518 ms, §5d slot 2 542 ms, §5e slot 4 565 ms. ±5 % spread across queue-on/off and 60 s/300 s phases — strong publishable headline.
- **chunks/att is now meaningful**: 79 / 70 / 76 / 77 across the four plugins under attributed measurement, with the per-phase background bucket ≤ 1 % of attributed. Pre-§5e numbers (the global counter, with queues on) were upper bounds inflated by inter-player crosstalk and pre-warm — do not compare §5b/§5c/§5d chunks/att head-to-head against §5e or against new runs.
- **Cold-start cost re-emerges with queues off.** Queue-on runs (§5b–§5d) reported RTP cold-starts of 14–52 ms; §5e shows 1037 ms. The queue-on number was queue-fetch latency, not teleport pipeline latency. The §5e cold-start is the actual pipeline cost of the first dispatch.

What survives unchanged:

- Server thread = 100 % of CPU weight on **every** Spigot 1.20.1 spark profile in every run captured this day. Platform baseline; **not** an RTP-side S-005 violation. To diagnose actual S-005 candidates from spark on Paper/Folia, look for a non-Server thread carrying meaningful weight while the Server thread *also* shows long ticks (the AsyRTP signature).
- Within-phase TPS dips track `chunk_load_cost_ms` row-for-row in the per-attempt CSV across all four runs — within-phase TPS is sync-chunk-gen-bound on Spigot 1.20.1.

Methodological consequences for future runs:

- **Disable per-plugin pre-queues for any benchmark that uses chunks/att or cold-start as a comparison axis.** Otherwise the harness measures queue plumbing, not pipeline cost.
- **Use 5-min phases minimum.** 60 s phases never reach steady state on Spigot 1.20.1 under this workload, so any TPS / latency-tail claim from a 60 s run is a transient. §5b–§5d numbers on those axes should be treated as superseded by §5e.
- **Compare plugins on delivered TP/s, main-thread CPU/attempt, and p99-success latency** — not on end-of-phase server `last1m`. Under saturating offered load, server TPS collapses to a platform-side tick-saturation floor common to every plugin and stops discriminating. TP/s, CPU/att, and p99 all rank the plugins consistently in the §5e data and are the cross-checked axes for the public write-up.
- **Spigot main-thread temporal attribution is exact only at concurrency = 1.** §5e ran 2 clients with `per-player-gap-ticks: 3` (concurrency 1–2) and produced ≤ 1 % background residual — small but non-zero. Reduce to concurrency = 1 if exactness matters; accept the residual otherwise.

---

## 2026-05-02 - With per-plugin queues enabled, RTP serves /rtp from L1 cache and decouples dispatch latency from synchronous chunk-gen (5g)

Counterpart to the §5e (queues-off) finding above. Run `20260502-154929` re-enabled per-plugin queues where supported (RTP `cacheCap: 10` + `activeChunkCap: 10`, BetterRTP `Settings.Queue.Enabled: true`, JakesRTP `location-cache-filler.enabled: true`; HuskHomes has no equivalent — N/A) and ran a 2-min/120 s-gap rtp→betterrtp→huskhomes head-to-head with both real clients OPed and `per-player-gap-ticks: 30` (non-saturating offered load).

What it shows under the **attributed-chunk counter** (the §5e refactor — first run where this is measurable for queue-on plugins):

| Phase | Att | Succ% | TP/s | main CPU/att | **chunks/att (attributed)** | p50 | p99 | end-of-phase `last1m` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rtp       | 150 | 100 | 1.25 | 625 ms | **1.07** | 1 ms    | **8 ms**    | 20.0 |
| betterrtp | 127 | 100 | 1.05 | 732 ms | **35.9** | 581 ms  | **4229 ms** | 17.2 |
| huskhomes | 128 | 100 | 1.05 | 785 ms | **64.0** | 1032 ms | **5124 ms** | 11.8 |
| jakesrtp  | 126 | 100 | 1.04 | —      | **17.0** | 0 ms    | **2252 ms** | 7.5† |

† jakesrtp ran in slot 4 behind huskhomes and inherits its chunk-residency carry-over; the 7.5 TPS figure is **not** a clean per-plugin number. Latency and chunks/att (dispatch-time measurements) remain valid.

Four things worth recording as durable lessons:

- **RTP's L1/L2 queues take the dispatch path off the synchronous chunk-gen critical path.** 1.07 chunks-attributed-per-attempt is essentially "the destination chunk plus rounding"; the candidate-search loads happened in the background-bucket scan (20 495 background loads on the rtp phase), not on dispatch. The latency consequence is **p99 = 8 ms** — the scheduler-noise floor — versus BetterRTP's **p99 = 4229 ms** and HuskHomes's **p99 = 5124 ms** in the same configuration. The p99 ratios (BetterRTP 530×, HuskHomes 640×) are the single largest discriminators we have published from any of §5b–§5g.
- **BetterRTP's queue does not absorb sustained 1 TP/s with default queue size.** Even with `Queue.Enabled: true`, every betterrtp dispatch still resolves through its synchronous pipeline (35.9 chunks attributed per attempt; `chunk_load_cost_ms` 28× higher than RTP's). Documented because it is a strong cross-plugin signal that *queue presence is not equivalent to queue effectiveness*; the comparison axis is queue-served chunks/att, not "does the plugin have a queue config knob".
- **HuskHomes pays the full synchronous-pipeline cost on every dispatch and is the only non-slot-4 phase here that did not reach steady state in 2 min.** No queue concept (no equivalent config knob exists), 64 chunks/att, end-of-phase `last1m` still decaying (16.6 → 11.8). Three independent measurement axes — chunks/att, main-CPU/att, end-of-phase `last1m` — agree on the ranking **rtp << betterrtp < huskhomes** in this configuration.
- **JakesRTP's `location-cache-filler` (`cache-locations: 10`) demonstrably pre-resolves locations under sustained dispatch, but exhausts under load.** chunks/att 17.0 (a third of BetterRTP's, a quarter of HuskHomes's), p50 = 0 ms (median attempt is a free cache read — the second-best p50 of any plugin in this run, behind only RTP's 1 ms), and p99 = 2252 ms (the cache-miss tail when `cache-locations: 10` drains and the next attempt pays the full pipeline). It is the only non-RTP plugin in this run that demonstrates queue-served dispatch on at least the median attempt. **Its slot-4 end-of-phase TPS (7.5) is not publishable** because slot 4 inherits HuskHomes's residual chunk pressure (the same slot-4 carry-over confound that §5c/§5d failed to settle in earlier runs); the dispatch-time axes (latency, chunks/att) are unaffected by carry-over and are the publishable JakesRTP numbers.

Methodological consequences:

- **The chunks/att discriminator only works under the queue-on configuration with the attributed counter.** Queue-off runs (§5e) show all plugins paying ~70–80 chunks/att because the queue plumbing is bypassed; the cross-plugin signal collapses. Queue-on with the global (pre-2026-05-02) counter showed inflated chunks/att for everyone because of inter-player crosstalk. Both prior framings should be treated as superseded by §5g for this axis.
- **At non-saturating offered load (`per-player-gap-ticks: 30`), the discriminator is latency tail, chunks/att, and end-of-phase server TPS — not delivered TP/s.** All three plugins delivered ~1.05–1.25 TP/s, near the 2-player × 30-tick artificial cap of 1.33 TP/s. To compare TP/s honestly, drop the gap to 0 (cf. §5e). The two configurations answer different questions: §5e "what is each plugin's saturation throughput?", §5g "at the load real servers run, what does dispatch latency feel like?". Critically, **server TPS *is* a meaningful per-plugin axis at non-saturating load** (rtp 20.0 / betterrtp 17.2 / huskhomes 11.8 end-of-phase `last1m`) — the §5e tick-saturation-floor caveat applies only when offered load saturates the tick. At real-server pacing, server TPS recovers as a discriminator.

---

## 2026-05-02 - Paper 1.20.1 with the same RTP config delivers 8× the throughput at 1/40th the main-thread cost (5h)

Run `20260502-161230` was the first Paper 1.20.1 capture in this measurement series, RTP-solo, 2-min phase, queues enabled (`cacheCap: 10`, `activeChunkCap: 10`), 2 OPed clients, `dispatch-as-player: true`, world data reused from the Spigot 1.20.1 series (skips pre-gen — same chunks already generated). Same harness binary, same `per-player-gap-ticks: 30`, same plugin config as §5g's rtp slot-1 phase. The only changed variable was the platform.

| Axis | Spigot 1.20.1 §5g RTP | **Paper 1.20.1 §5h RTP** | Δ |
|---|---:|---:|---:|
| Delivered TP/s | 1.25 | **10.84** | 8.7× |
| p50 latency | 4 ms | 105 ms | +26× |
| p99 latency | 8 ms | 303 ms | +38× |
| Main CPU/att | n/a | **13.3 ms** | — |
| Total CPU/att | n/a | 144.4 ms | — |
| chunks/att (attributed) | 1.07 | 18.7 ‡ | retracted |
| Server `last1m` (end) | 20.00 | 20.00 | flat |
| MSPT median (spark) | null† | 5.2 ms | populated |

† Spigot exposes no MSPT API; spark's MSPT fields are blank on Spigot regardless of harness state.

‡ **The 18.7 chunks/att figure is retracted** (user-confirmed post-run). The actual per-attempt pipeline cost on Paper §5h is ~1 chunk-load — the same L1-served regime as Spigot §5g — not 18.7. The inflation comes from RTP's background `ScanTask` pre-filling the L1/L2 caches (`cacheCap: 10`, `activeChunkCap: 10`): scan loads execute on the main thread, are emitted by the RTP plugin, and land in the same wall-clock window as in-flight attempts, so both attribution paths (Paper plugin-tickets matching on plugin label, Spigot temporal fallback matching on main-thread + in-flight) bill them to the attempt. Neither path can distinguish *intra-plugin* concurrent work.

Four durable lessons:

- **The §5g RTP latency numbers (p99 = 8 ms) measured queue-served L1 hits, not pipeline cost.** At Spigot's 1.25 TP/s offered rate the L1 cache fed every dispatch from a cache hit and the latency was scheduler noise. At Paper's 10.84 TP/s the L1 cache *also* serves every dispatch (per-attempt pipeline cost ≈ 1 chunk-load, same regime as §5g — see ‡), but the p50/p99 latency rises to 105/303 ms because Paper's chunk-async path actually waits on async loads it scheduled, where Spigot's queue-fetch is essentially instant. **Different platform, same operating regime; the latency delta is async-wait, not queue-miss.** Do not compare §5g's p99 = 8 ms head-to-head with §5h's p99 = 303 ms as a "Paper is slower per attempt" claim: the §5g number is queue-fetch latency on a sync platform, the §5h number is queue-fetch latency on an async platform that waits for chunk readiness post-fetch. The publishable §5g→§5h comparison is the **throughput delta (8.7×) and the main-thread CPU/att gap (565 → 13.3 ms)**, both of which are platform-fast-path effects independent of L1 hit rate.
- **Per-attempt chunk-load attribution cannot separate *intra-plugin* concurrent work** (pipeline-search vs. background scan-task vs. post-arrival view-distance follow-up) under either the Paper plugin-tickets path or the Spigot temporal fallback. Both attribute by *who* (plugin label) and *when* (main-thread, in-flight window); neither attributes by *which subsystem of that plugin*. For RTP specifically, this means the `chunks_per_attempt` headline is meaningful only when the scan task is disabled (`cacheCap: 0`, `activeChunkCap: 0` — the §5e queues-off configuration). With queues on, the attributed count is closer to "ScanTask + pipeline loads emitted by the plugin during the phase ÷ attempts" than to "per-attempt pipeline loads". A future `ChunkLoadCounter` extension that subtracts a per-tick scan-task baseline is the cleanest fix; until then, treat queue-on chunks/att as an upper bound and use the §5e queues-off configuration for any per-attempt pipeline-cost claim.
- **Paper's chunk worker threads do most of the per-teleport work.** Total CPU/att = 144.4 ms vs main CPU/att = 13.3 ms: only ~9 % of the JVM cost lands on the tick thread. This is what S-005 / ADR-015 / ADR-016 imply *should* happen and what the §5e Spigot saturating run (565 ms main CPU/att for the same plugin) showed cannot happen on Spigot. Spark windows confirm: process CPU window aggregate 6.7 % on Paper vs ~100 % on Spigot under saturating load. **The same RTP code is on the chunk-async fast path on Paper and not on Spigot** — most of that gap is platform, not plugin.
- **Paper's MSPT API populates `mspt_median` / `mspt_max` in spark profiles.** The harness's 2026-05-02 Spigot tick-wall fallback lands MSPT in the per-attempt CSV instead; on Paper the Paper API path resolves and spark's protobuf carries real MSPT. The first published §5h captures show median 5.2 ms and one ~111 ms spike per ~1200 ticks per window — a latent S-005 candidate worth flagging, but invisible at the `last1m` axis because Paper absorbs it inside the same minute.

Methodological consequences:

- **Same-config Spigot↔Paper comparisons should use TP/s and main-CPU/att, not p50/p99 latency**, unless both runs were captured in the same operating regime (both queue-served or both pipeline-served). Different operating regimes produce different p99 axes that cannot be compared head-to-head.
- **The `Chunk#getPluginChunkTickets()` reflective attribution path is live on Paper 1.20.1** (24 377 "attributed" / 26 288 total chunk loads ⇒ 92.7 % bound to an RTP ticket). Caveat: this confirms the Paper API path resolves and that 92.7 % of phase chunk loads were emitted *by the RTP plugin*, **not** that 92.7 % of them were pipeline-driven per-attempt loads. Per the ‡ footnote, the bulk of the attributed count is the RTP `ScanTask` background cache-fill, not the dispatch pipeline.
- **A `per-player-gap-ticks: 0` Paper run remains pending** to establish RTP's saturation throughput on Paper. From §5h's 105 ms p50 the predicted ceiling is in the 15–20 TP/s band, but predicted only.

---

## 2026-05-02 - Paper 1.20.1 four-plugin head-to-head: ranking reverses vs Spigot, p99 tails compress 14×–42× (5h extension)

The `20260502-161230` run actually contains **all four plugins** (`rtp → betterrtp → huskhomes → jakesrtp`), not just RTP-solo as the prior 5h entry above first documented. With queues on, OPed clients, `per-player-gap-ticks: 30`, 2-min phases, 120 s gap, and the `jakesrtp:forcertp` workaround, all four plugins delivered 100 % success and Paper's `last1m` stayed pinned at 20.00 across **every phase** — Paper has the headroom to absorb the entire offered load on this rig.

| Plugin | TP/s | Cold (ms) | p50 | p99 | Max | Main CPU/att (ms) | chunks/att |
|---|---:|---:|---:|---:|---:|---:|---:|
| rtp        | 10.84 | 2   | 105 | 154 | 201  | 13.3 | 18.7 ‡ |
| betterrtp  | 6.57  | 169 | 160 | 852 | 1226 | 23.3 | 23.0   |
| huskhomes  | 5.94  | 260 | 208 | 372 | 1214 | 24.7 | 27.6   |
| jakesrtp   | **19.93** | 10 | **12** | **54** | 287 | **9.7** | 3.2 |

‡ Same scan-task confound as the 5h RTP-solo entry above; the other three plugins do not run a comparable scan task and their chunks/att numbers are direct.

Comparison vs Spigot §5g, same plugins/configs/world:

| Metric | rtp | betterrtp | huskhomes | jakesrtp |
|---|---:|---:|---:|---:|
| TP/s Spigot → Paper | 1.25 → 10.84 (8.7×) | 1.07 → 6.57 (6.1×) | 1.06 → 5.94 (5.6×) | 1.05 → 19.93 (19×) |
| p99 Spigot → Paper (ms) | 8 → 154 (+19×) | 4229 → 852 (5.0× lower) | 5124 → 372 (**13.8× lower**) | 2252 → 54 (**42× lower**) |

Durable lessons:

- **Plugin ranking reverses across platforms.** Spigot §5g p99 ranking: `rtp(8) ≪ jakesrtp(2252) < betterrtp(4229) < huskhomes(5124)`. Paper §5h p99 ranking: **`jakesrtp(54) < rtp(154) < huskhomes(372) < betterrtp(852)`**. JakesRTP moves from slot-4 worst tail to fastest plugin in the run; BetterRTP becomes the worst tail. JakesRTP's `location-cache-filler` (cache-locations: 10) hits dominate on Paper because the faster chunk-loading lets the cache stay full at higher offered rate; BetterRTP's single-pre-warmed-location queue cannot do the same. **"Best on Spigot" does not transfer to Paper, even within the same plugin set, same configuration, same world data.** Publish per-platform rankings, not a global one.
- **Per-plugin spread compresses on Paper.** chunks/att spans 8.6× (3.2 → 27.6) on Paper vs 60× (1.07 → 64.0) on Spigot; p99 spans 23× (54 → 1226 ms) on Paper vs 640× (8 → 5124 ms) on Spigot. Paper's async chunk pipeline does most of the work, narrowing the gap between plugin-side optimization and brute-force-pipeline plugins. Plugins that rely on careful queue management to avoid sync chunk-gen (RTP's L1, JakesRTP's cache) lose much of their relative advantage on Paper because the platform handles the chunk-gen cost regardless.
- **HuskHomes p99 collapses 14× (5124 → 372 ms) at constant config.** The Spigot §5g HuskHomes number was the platform's sync chunk-gen showing through, not the plugin's design choice; Paper's async pipeline lets HuskHomes's per-player serialisation proceed at chunk-worker speed. Same plugin, same code, same per-player concurrency, same chunk count to load — only the platform changed. **Use this as the canonical "queue-less plugin on Paper-vs-Spigot" datapoint** when discussing why platform choice matters more than plugin choice for sustained-load servers.
- **JakesRTP's saturation ceiling is *above* the gap-capped offered rate on Paper.** It delivered 19.93 TP/s at `per-player-gap-ticks: 30`, which exceeds even RTP's predicted Paper saturation ceiling. The cache-hit fast path appears to bypass chunk-load entirely (3.2 chunks/att, the lowest of any plugin) — a `per-player-gap-ticks: 0` Paper run is needed before publishing a JakesRTP saturation number, but the gap-capped figure already establishes that the cache hit path is faster than the harness can offer.
- **BetterRTP "queue presence ≠ queue effectiveness" reconfirmed on Paper.** Same finding as Spigot §5g, now cross-platform: `Queue.Enabled: true` on Paper still produces p99 = 852 ms vs RTP's L1-served 154 ms. BetterRTP's single-location-per-player queue cannot decouple latency from chunk-gen the way RTP's L1 pool can, on either platform.

Methodological consequences:

- **Cross-platform rankings must be published per-platform.** Same-plugin Spigot↔Paper comparisons are valid (e.g. "HuskHomes is 14× faster on Paper"), but cross-plugin rankings transfer poorly because each plugin's relative position depends on which subsystem dominates per-attempt cost (sync chunk-gen vs queue plumbing vs cache hit) and that mix is platform-dependent.
- **The Spigot↔Paper TP/s ratio for queue-on plugins is 5.6×–8.7× for the three pipeline-bound plugins, but 19× for JakesRTP.** The latter is a cache-hit-path effect, not a chunk-pipeline effect; treat JakesRTP's Paper number as a different operating regime from the other three. The 5.6×–8.7× band is the publishable "Paper async-chunk benefit" headline for plugins that actually load chunks per attempt.

---

## 2026-05-02 - RTP cache depth + per-tick refill (`cacheCap: 100` + `period: 1`) collapses p99 38× at constant TP/s and CPU/att (5i)

Run `20260502-171548` (Paper 1.20.1, four plugins, queues on, OPed clients, 2-min phases, 120 s gap, `per-player-gap-ticks: 0`) re-ran the §5h matrix with **two RTP knobs raised**: `regions/default.yml cacheCap: 10 → 100` (10× deeper L1 pool) **and** `performance.yml period: 10 → 1` (background scan/refill task fires every tick instead of every 10). All other plugin configs unchanged. The intent: cache-size-comparable to JakesRTP's `cache-locations: 10` plus its own backlog, refilled fast enough to never drain mid-phase.

| Plugin     | TP/s §5h (`cacheCap 10`) | TP/s §5i | p99 §5h (ms) | p99 §5i (ms) | Main CPU/att §5h | Main CPU/att §5i |
|------------|-------------------------:|---------:|-------------:|-------------:|-----------------:|-----------------:|
| rtp        |                  19.16   | 19.83    |       154    |        **4** |             17.0 |             16.9 |
| betterrtp  |                   6.95   |  7.09    |       852    |       771    |             58.5 |             53.6 |
| huskhomes  |                   6.20   |  6.18    |       372    |       335    |             50.8 |             52.2 |
| jakesrtp   |                  19.93   | 20.00    |        54    |        70    |             24.4 |             26.0 |

Durable lessons:

- **`cacheCap` and `period` are independent levers — both matter.** Raising `cacheCap` alone (the 5h regime, which already had `cacheCap: 10`) gave RTP 19.16 TP/s with p99 = 154 ms because the cache *did* drain mid-burst at `period: 10`-tick refill. Raising `period: 10 → 1` together with `cacheCap: 10 → 100` collapsed RTP's p99 to 4 ms — the scheduler-noise floor. Depth keeps the queue from draining mid-phase; per-tick `period` keeps it from draining mid-second. **Either knob alone is insufficient under saturating offered load**; both are needed for queue-served dispatch on a real-server-saturating workload.
- **The performance ceiling at saturating 2-client offered load is now `Bukkit.dispatchCommand` plumbing, not the RTP pipeline.** With p99 = 4 ms, every percentile is below the 50 ms tick boundary; a measurable fraction of dispatches complete inside the same tick they were issued in. Further p99 reduction on this rig would require harness changes (avoiding the command-map round-trip), not plugin changes.
- **Knob change isolates correctly: only RTP's row moves.** BetterRTP / HuskHomes / JakesRTP TP/s, p99, and main-CPU/att are within run-to-run variance vs §5h despite the RTP cache changes — the harness measures plugin-side configuration changes cleanly when they touch only one plugin's config tree.
- **Background chunks/att (22.74) is `period: 1` × `cacheCap: 100` working correctly, not a bug.** At default `period: 10` the scan task would emit ~1/10th the background loads. **Do not publish the background bucket as a per-attempt cost** — it is a configuration-driven baseline that scales linearly with `1/period` and with `cacheCap` headroom. The pipeline-cost number is the *attributed* bucket (4.77/att for RTP), still confounded by intra-plugin scan/pipeline overlap as 5h documented.

Methodological consequences:

- **The publishable RTP recommendation for sustained-load servers is `cacheCap: 100, period: 1` on Paper 1.20.1**, capable of serving p99 = 4 ms at the harness's 2-client offered ceiling. Smaller caches or longer refill periods will trade tail latency for background CPU; the §5h `cacheCap: 10, period: 10` regime is the reasonable default for low-traffic servers.
- **A reverse-order rerun at `cacheCap: 100, period: 1` would settle remaining slot-1/slot-4 confounds** (RTP's 0 ms cold and JakesRTP's 21 ms cold). The current §5i is n=1 in the original phase order.
- **Higher concurrency is now required to find RTP's saturation ceiling.** Both rtp and jakesrtp are pinned at the 2-client × 20-tick = 20 TP/s offered ceiling; the actual plugin ceilings are unknown. A bot-harness ADR (cf. §6b) is the only way past this rig's ceiling.

---

## 2026-05-02 - §5i headlines reproduce at n=2 on a same-config consecutive run (5j)

Run `20260502-174114` (Paper 1.20.1, four plugins, queues on, OPed clients, 2-min phases, 120 s gap, `per-player-gap-ticks: 0`, RTP `cacheCap: 100` + `period: 1`) is a deliberate same-order, same-config rerun of §5i to test n=2 reproducibility before publishing.

| Plugin     | TP/s §5i | TP/s §5j | p99 §5i (ms) | p99 §5j (ms) | Main CPU/att §5i | Main CPU/att §5j |
|------------|---------:|---------:|-------------:|-------------:|-----------------:|-----------------:|
| rtp        |  19.83   |  19.92   |        4     |        **3** |       16.9       |       14.7       |
| betterrtp  |   7.09   |   7.25   |      771     |       722    |       53.6       |       43.0       |
| huskhomes  |   6.18   |   6.18   |      335     |       313    |       52.2       |       47.2       |
| jakesrtp   |  20.00   |  19.97   |       70     |        89    |       26.0       |       19.0       |

Durable lessons:

- **The §5i p99 collapse is real, not a single-run artefact.** RTP's p99 reproduces at 3–4 ms across both runs; n=2 is enough to publish the `cacheCap: 100` + `period: 1` recommendation without an "n=1" footnote. The remaining 1 ms variance is below spark sampling resolution.
- **Pipeline-served plugins (BetterRTP, HuskHomes) are the most reproducible axis.** TP/s within ±2.3 % across runs; p99 within ±49 ms. They have no cache to swing on and no scan-task timing to be sensitive to, so their per-attempt pipeline cost is the cleanest cross-plugin signal in the four-plugin matrix.
- **JakesRTP's tail has a wider band than RTP's** under the same offered load: p99 70 ↔ 89 ms across n=2, vs RTP's 4 ↔ 3 ms. The cache-exhaustion regime is genuine — `cache-locations: 10` is shallow enough that tail latency depends on *when* in the refill cycle a burst arrives. RTP's `cacheCap: 100` is deep enough to be insensitive to that phase. Worth recording as a structural difference between the two cache implementations, not a per-run artefact.
- **Main-CPU/att moved 10–27 % lower across the board on the second run** (jakesrtp -27 %, betterrtp -20 %, rtp -13 %, huskhomes -10 %). The ranking is preserved (rtp < jakesrtp < huskhomes < betterrtp on both runs) but the absolute number swings rig-side, not plugin-side. Two consistent explanations: extended JIT warm-up over the 11-min interval before §5j and Paper's chunk caches reusing more pre-loaded state. **Do not quote main-CPU/att to two significant figures from a single 2-min phase; quote a range or rerun for the published number.**

Methodological consequences:

- **For publication: TP/s and p99 reproduce well enough to quote n=2 averages from §5i + §5j directly.** Main-CPU/att should be quoted as a range across both runs rather than a single number.
- **The reverse-order rerun flagged in §5i.4 is *still* outstanding** — both §5i and §5j ran the same `rtp → betterrtp → huskhomes → jakesrtp` order, so any slot-1-vs-slot-4 carry-over is common-mode in this n=2 verification and would not be detected. A `jakesrtp → huskhomes → betterrtp → rtp` rerun is the cleanest way to settle whether RTP's slot-1 0–1 ms cold-start is order-dependent.

---

## 2026-05-02 - SorekillRTP also remaps `/rtp` to whichever other plugin owns the bare alias; AsyRTP measured cleanly, EzRTP target form rejected (5k)

Run `20260502-181051` (Paper 1.20.1, three additional RTP-style plugins targeted: `asyrtp:rtp`, `ezrtp:rtp`, `sorekillrtp:rtp`; same harness config as §5i/§5j: queues on where applicable, OPed clients, `dispatch-as-player: true`, `per-player-gap-ticks: 0`, 2-min phases, 120 s gap). Two of three targets did **not** measure their intended plugin.

| Plugin        | Att | Succ | TP/s  | p50 (ms) | p99 (ms) | Main CPU/att (ms) | chunks/att (attr) | Status |
|---------------|----:|-----:|------:|---------:|---------:|------------------:|------------------:|:------:|
| asyrtp        | 245 | 245  |  2.04 |     401  |   2071   |             43.18 |              6.83 | ✅ measured |
| ezrtp         |  24 |   0  |  0.00 |       —  |      —   |            153.62 |              0.00 | ⚠ target rejected |
| sorekillrtp   | 795 | 794  | 10.00 |       1  |      2   |             14.25 |              5.77 | ⊘ collision — measured our RTP plugin |

Durable lessons:

- **SorekillRTP also exhibits the Bukkit command-map collision pattern documented for JakesRTP in §5g.** SorekillRTP's plugin internally re-dispatches the `/rtp` (or `sorekillrtp:rtp`) it received as another plugin's `/rtp`. On a server where our RTP plugin and SorekillRTP both register `/rtp`, the harness's `sorekillrtp:rtp` dispatches were ultimately served by **our RTP plugin's queue-served path** — the 10.0 TP/s, p99=2 ms, 5.77 chunks-attributed-per-attempt numbers in the table are RTP self-reproducibility data (consistent with §5i / §5j RTP rows at the same rig configuration), **not** SorekillRTP performance. **Treat any third-party RTP plugin's bare-`/rtp` invocation as suspect on a server where our RTP plugin is also installed**; verify with `/version` + tab-completion before publishing any cross-plugin row. The §5g verification rule (always confirm the namespaced command resolves only to the intended plugin's `PluginCommand`) generalises beyond JakesRTP — assume collision until proven otherwise.
- **EzRTP's `ezrtp:rtp` form silently rejects every dispatch after the first under saturating offered load.** First attempt loaded 119 chunks then timed out; every subsequent attempt loaded 0 chunks and timed out — `PlayerTeleportEvent` never fired within the 5 s deadline. Most likely cause is a per-player cooldown / one-shot internal lock on EzRTP's side that survives the harness's deliberate cooldown=0 / delay=0 config edits (these cover other plugins; EzRTP's behavior here suggests its own gating is *not* exposed in the visible cooldown knobs). **EzRTP measurement remains pending** the workaround — switch the target to the admin form `ezrtp:forcertp {player} world` and disable `dispatch-as-player` for that target only, or test with a cooldown-bypass permission.
- **AsyRTP delivers 2.04 TP/s with full success but a ~2 s p99 tail under saturating offered load.** Spark MSPT_max for the asyrtp window is **1343–1768 ms** (single-tick stalls > 1 second), median 3.3–3.7 ms — the long ticks are concentrated in a handful of attempts rather than smeared. AsyRTP's main-CPU/att (43.18 ms) is in the same band as BetterRTP §5j (43.01 ms) and HuskHomes §5j (47.19 ms), but its delivered TP/s (2.04) is roughly 1/3 of those plugins'. The bottleneck is not per-attempt main-thread CPU; it is something gating attempt-to-attempt pacing (worker-thread contention, internal serialisation, retry budget, or async-completion plumbing). **Publishable as the third pipeline-served plugin in the four-plugin Paper §5h matrix**, with the caveat that its tail is dominated by occasional multi-second main-thread stalls rather than steady per-attempt cost.
- **Methodological prescription**: when adding a new third-party RTP plugin to the comparison set, the first run should be a **collision-audit run** — single phase, single target, post-run `/version`-and-output-source verification — before any cross-plugin comparison. Two of three plugins added in this session (sorekillrtp, ezrtp) failed to produce their own measurements on the first attempt; collision and target-form audits are now part of the standard onboarding cost for each new plugin, not a one-time exercise after JakesRTP.

---

## 2026-05-02 - Post-workaround Paper 1.20.1 four-plugin extension: EzRTP / AdvancedRTP / EssentialsX measured for the first time, AsyRTP §5k reproduced (5L)

Run `20260502-185238` (Paper 1.20.1, four targets `asyrtp:rtp world world {player}`, `ezrtp:forcertp {player} world`, `advancedrtp:rtp {player}`, `essentialsx:tpr {player}`; queues on where applicable, OPed clients, `dispatch-as-player: true`, `per-player-gap-ticks: 0`, 2-min phases, 120 s gap). After the §5k cooldown / countdown / target-form config edits, three of the four targets that were previously unmeasured produced their first publishable rows; AsyRTP reproduced §5k as an n=2 control.

| Plugin        | Att | Succ | Fail (TIMEOUT) | TP/s | p50 (ms) | p99 (ms) | Main CPU/att (ms) | chunks/att (attr) |
|---------------|----:|-----:|---------------:|-----:|---------:|---------:|------------------:|------------------:|
| asyrtp        | 204 | 204  |  0             | 1.67 |     917  |   4534   |             38.83 |             12.28 |
| ezrtp         | 212 | 212  |  0             | 1.76 |     904  |   2903   |            139.59 |             99.18 |
| advancedrtp   | 270 | 260  | 10 (3.7 %)     | 2.16 |     448  |   2100   |             92.07 |             47.69 |
| essentialsx   | 158 | 120  | 38 (24.1 %)    | 0.96 |     177  |   4504   |             88.90 |              2.78 |

Durable lessons:

- **The `chunks_per_attempt_attributed` axis discriminates own-plugin from §5g/§5k command-map collisions.** Under a §5g/§5k collision the harness measures our RTP queue-served path (chunks/att ≈ 0.3, main-CPU/att ≈ 17 ms — see §5j RTP row). EzRTP's 99.2 chunks/att, AdvancedRTP's 47.7, and AsyRTP's 12.3 are all ≥ 12× the collision signature; EssentialsX's 2.78 is low but its 88.9 ms main-CPU/att rules out collision (which would give low CPU/att too). **All four §5L rows are the targeted plugins, not our RTP plugin under a label.** This is now the standard post-run check for any new third-party RTP target before publishing its row.
- **EzRTP is the heaviest pipeline of the seven plugins measured to date.** 99.2 chunks/att (vs HuskHomes 52, AdvancedRTP 48, AsyRTP 12, RTP/JakesRTP queue-served 0–1) and 139.6 ms main-CPU/att. 100 % success and bounded p99 (2.9 s) confirm the pipeline is correct, just expensive. Worldgen-extent caveat applies: in a fresh world both numbers would be higher. **Use as the canonical "heavy-pipeline RTP plugin" reference point in publication**; do not extrapolate without the worldgen footnote.
- **AsyRTP §5k reproduces with a wide n=2 variance band.** §5k: 2.04 TP/s, p99 2071 ms, MSPT_max 1343–1768 ms. §5L: 1.67 TP/s, p99 4534 ms, MSPT_max ~855 ms. ~22 % TP/s and ~2× p99 swing across runs, while the 1+ s MSPT-spike profile (a handful of attempts dominating the tick-time tail) is preserved. **Publish AsyRTP figures as ranges, not point numbers**; the underlying pacing-bound profile is reproducible but the magnitude is run-sensitive.
- **EssentialsX `/tpr` reproduces the timeout storm on Paper.** The prior LESSONS_LEARNED Stress-Testing entry called this Spigot-only; §5L shows 38/158 = 24.1 % timeouts on Paper too at 0-cooldown. The diagnostic signature (low chunks/att = 2.78, high main-CPU/att = 88.9 ms) is consistent with `/tpr` doing significant non-chunk work — request handshake, message I/O, retry plumbing — before / instead of a full pipeline search. **The 24.1 % timeout figure is harness-shaped, not plugin-shaped**: `/tpr` is a teleport-*request*-shaped command, and the harness's 5 s per-attempt deadline is shorter than the request-accept handshake's natural latency for some fraction of attempts. The Stress-Testing entry should be updated to drop the "Spigot-only" framing.
- **AdvancedRTP delivered 2.16 TP/s with the lowest p99 (2.1 s) of the four.** chunks/att = 47.7 puts its per-attempt cost in the same class as HuskHomes; combined with 100 % success on the 260 non-timeout attempts, AdvancedRTP is a credible mid-tier RTP at this rig. The 10 timeouts (3.7 %) are real and not yet root-caused — could be transient bad-candidate runs that exhaust an internal retry budget, or a queue-of-one collision when both clients dispatch in the same tick. Reproduction needed before quoting the 3.7 % failure rate as a plugin property.

Methodological consequences:

- **Server `last1m` TPS held ≥ 19.95 across all four phases** despite per-attempt CPU costs ranging 38.8–139.6 ms. With 2 OPed clients and `gap-ticks: 0`, each plugin's own serial pipeline gates throughput before the tick budget does — server TPS is a non-discriminator at this offered load. Use TP/s, p99, and MSPT_max instead.
- **Cross-plugin chunks/att now spans ~150×** across queued (RTP §5j ≈0.3 attributed) and pipeline-served (EzRTP §5L 99.2) extremes in published runs. The attributed-chunk counter is producing a meaningful per-plugin discriminator at saturating offered load, modulo the RTP scan-task footnote (‡) on RTP itself.
- **All §5L rows are n=1 except AsyRTP (n=2 with §5k).** Per the §5j precedent (10–27 % main-CPU/att variance run-to-run), two-significant-figure precision is unjustified for the EzRTP / AdvancedRTP / EssentialsX rows. Publish ranges or rerun before quoting headline numbers.

---

## 2026-05-02 - Folia 1.21.11 three-plugin head-to-head: RTP delivers 4.00 ms main-CPU/att (lowest on any platform), beats BetterRTP 7.6× and HuskHomes 5.7× at p99 (5M)

Run `20260502-200653` (Folia 1.21.11 / `git-Folia-14-529aabc`, three sequential 10-min phases `rtp` → `betterrtp` → `huskhomes` with 120 s gaps, queues on, OPed clients, `dispatch-as-player: true`, `per-player-gap-ticks: 0`). Spark `thread_cpu_top` confirms `Folia Region Scheduler Thread (x3)` at 100 % combined weight — region-parallel pipeline execution, not a single main thread.

> **Note (entry rewritten in place 2026-05-02):** an earlier version of this entry described the run as "RTP and BetterRTP dispatched concurrently" and reported BetterRTP at 1.13 TP/s / p99 = 1102 ms. That was a partial mid-run snapshot; the run is sequential phases, and the final numbers below supersede the earlier draft.

| Plugin    | n    | Succ % | /rtp/s | p50 (ms) | p99 (ms) | max (ms) | Main CPU/att (ms) | chunks/att (attr) |
|-----------|-----:|-------:|-------:|---------:|---------:|---------:|------------------:|------------------:|
| rtp       | 5927 | 99.97  | **9.87** |   101  | **157**  |   236  | **4.00** | 18.0 ‡ |
| betterrtp | 2291 | 100    | **3.82** |   399  | **1 200**| 2 300  | **8.06** | 34.8 |
| huskhomes | 2008 | 100    | **3.32** |   350  | **901**  | 1 602  | **14.62** | 28.4 |

Server TPS pinned at 19.75–20.00 across all spark windows of all three phases, MSPT median 0.09–6.5 ms, MSPT_max ≤ 117.9 ms.

Durable lessons:

- **RTP main-CPU/att = 4.00 ms is the lowest of any platform measured.** Spigot §5g/§5j RTP main-CPU/att = 60+ ms, Paper §5j RTP = 14.7 ms, Folia §5M = 4.00 ms. The trend tracks the platform's main-thread-serialisation cost: Spigot serialises everything, Paper offloads chunk-gen, Folia parallelises across regions. **The Folia number is the publishable best-case figure for RTP main-thread cost** at queue-served L1 hit; lower than this is not achievable on a multi-region rig without harness changes.
- **BetterRTP and HuskHomes are both functional on Folia under load** (100 % success on 2 291 / 1 942 dispatches over 10 min each, no thread-access exceptions, no sync-load violations observed at this scale). chunks/att signatures (34.8 / 28.4) are consistent with each plugin's own pipeline running, *not* collapsed via a §5g/§5k command-map collision. **Both are publishable as fair-fight market comparisons** for the public writeup.
- **Cross-platform p99 ratio table (RTP vs slowest competitor in the run)**:

  | Platform        | RTP p99 | BetterRTP p99 | HuskHomes p99 | Ratio (worst / RTP) |
  |-----------------|--------:|--------------:|--------------:|--------------------:|
  | Spigot 1.20.1   |    8 ms |       4 229 ms |       5 124 ms |              **640×** |
  | Paper 1.20.1    |  3–4 ms |     722–852 ms |     313–372 ms |              **280×** |
  | Folia 1.21.11   |  157 ms |       1 200 ms |         901 ms |              **8×**   |

  The ratio compresses dramatically as platform parallelism grows. RTP's queue-served path beats both pipeline-served competitors on every platform, but **Folia is by far the most forgiving platform for non-queueing competitors** — they gain the most from Folia, RTP gains the least. **Publishable claim (revised)**: "RTP delivers 5–640× faster p99 than the next-fastest tested competitor across Spigot 1.20.1, Paper 1.20.1, and Folia 1.21.11."

- **HuskHomes is the most striking Folia win.** Spigot §5g p99 = 5 124 ms → Folia p99 = 901 ms (5.7× improvement). The no-queue Spigot/Paper failure mode disappears almost entirely on Folia because the region thread can absorb the per-attempt chunk-gen cost without dragging the rest of the server. Curiously its Paper §5h p99 (313–372 ms) is *better* than Folia — likely because Paper's chunk-gen pipeline is mature and per-attempt while Folia's region threads share chunk-gen with player ticking. Worth a footnote in any Folia-vs-Paper claim.
- **The Folia value-prop has two layers, only one of which is RTP-specific.** Layer 1 (platform-wide): TPS pinned at 20 even when one region thread is serving 1+ s teleports — non-teleporting players in other regions saw zero impact. Layer 2 (RTP-specific): the player teleporting *also* sees sub-200 ms p99, while a BetterRTP user on the same Folia rig waits ~1.2 s and a HuskHomes user waits ~0.9 s. The marketing line — "on Spigot, slow plugins drag everyone down; on Folia, only the teleporting player waits — and with RTP, even they don't" — is supported by this run on three plugins.

Methodological consequences:

- **Folia 1.21.11 ≠ Paper 1.20.1**: cross-platform comparisons against §5g (Spigot 1.20.1) and §5h–§5L (Paper 1.20.1) carry a one-version delta. Some of the Spigot→Folia gap is genuine MC 1.20.1 → 1.21.11 platform improvement, not pure Folia parallelism. **Do not attribute the full delta to "Folia"**; in the public writeup, footnote that the Folia rig was on the newer MC version.
- **Pre-warmup contamination**: 73 rtp / 43 betterrtp / 34 huskhomes rows fell outside their respective phase windows. They are not in `phases.csv`, but they *are* in the per-attempt CSV. Cold-start figures must be filtered to in-window rows; the first in-window RTP dispatch for this run was 146 ms vs typical p50 = 101 ms — consistent with cold-cache rebuild after warm-up.
- **Mid-run reads of `phases.csv` are dangerous.** An earlier draft of this entry (and §5M) published BetterRTP at 1.13 TP/s based on a partial mid-run snapshot — the harness flushes per-attempt rows continuously but `phases.csv` only finalises at phase end. **Always wait for the run-final `phases.csv` row count to match the configured phase count before publishing any per-plugin number.**
- **n=1 on Folia for all three plugins.** Quote ranges (or rerun) before any two-significant-figure publication; the §5j ±15–25 % main-CPU/att band applies until proven otherwise on Folia.

---

## 2026-05-06 - Mojang renames Mojmap symbols across 1.21.x point releases; Loom does not auto-remap project-dependency JARs per consumer

The 1.21.5 → 1.21.11 jump renamed at least four publicly-used Mojmap symbols on the `rtp-fabric` SPI footprint:

| 1.21.5 Mojmap | 1.21.11 Mojmap | Intermediary |
|---------------|----------------|--------------|
| `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` | `class_2960` |
| `TicketType(long, boolean, TicketUse)` (record) | `TicketType(long, int)` (record; flags bitfield) | `class_3230` |
| `TicketType.TicketUse` (inner enum) | **removed** | `class_3230$class_10558` → `$class_12084` (now an `@interface` marker) |
| `TicketType.NO_TIMEOUT` | `TicketType.NO_EXPIRATION` | `field_55598` |

Mojang historically rarely renamed Mojmap symbols, but the 1.21.11 → 26.1 transition (deobfuscation, "Mojmap becomes the source") prompted a rename pass to align Mojmap with conventional naming. Expect more of this through 26.1.

**Why this hit `rtp-fabric` specifically.** `rtp-fabric-common` was compiled once against a fixed Mojmap snapshot (1.21.5) and exposed `FabricVersionAdapter` with method signatures referencing `ResourceLocation`, `ServerLevel`, `ChunkAccess`, `BlockPos`, etc. That bytecode bakes in the *literal* Mojmap names. When the 1.21.11 adapter (compiled against a Mojmap snapshot where the same intermediary `class_2960` is now named `Identifier`) tried to override common's `ResourceLocation`-typed method, javac failed with `cannot access ResourceLocation: class file for net.minecraft.resources.ResourceLocation not found` — Loom does **not** auto-remap project-dependency JARs per the consumer's mappings. Remapping is a publication-time step driven by `remapJar`, not a per-consumer view.

Resolution (rtp-fabric-ADR-007, accepted 2026-05-06): remove all `net.minecraft.*` types from the SPI signature surface. The adapter interface now uses project-owned wrapper records (`RTPLevelHandle`, `RTPBlockHandle`, `RTPBlockStateHandle`, `RTPChunkHandle`, `RTPRegistryKey`) carrying an `Object` payload + `as(Class<T>)` for in-adapter casting. Coordinates pass as primitives. The 1.21.11 (`R11`) adapter then compiles cleanly against the rename and was re-included in the default build.

Durable consequences:

- **Treat the Mojmap symbol surface as unstable across MC point releases**, not just across major versions. Any cross-version Fabric SPI in this project must avoid leaking `net.minecraft.*` types in method signatures. Wrapper records with `as(Class<T>)` are the project pattern; primitives where reasonable.
- **Bukkit/Paper/Folia v-submodules are not exposed to this risk** because Bukkit's API package (`org.bukkit.*`) is the cross-version contract — Mojang is free to rename internals because Bukkit pins the ABI. The Fabric platform has no such layer; every adapter sees raw Mojmap.
- **Loom build implication.** `fabric-loom`'s remapping does not reach into project dependencies. A multi-module Loom build where module A is compiled against MC vX and module B (depending on A) compiles against MC vY will fail on any Mojmap symbol renamed between vX and vY. There is no `remapDependencies` knob; the only mechanical fix is to keep MC types out of cross-module API.
- **Diagnostic signature.** A user-visible `NoClassDefFoundError: net/minecraft/class_<NNNN>$class_<NNNN>` at Fabric mod startup, on a runtime newer than the adapter's compile target, is the symptom — confirms the runtime Mojang jar no longer contains the inner class the older adapter's `<clinit>` references. Always check whether the inner class was renamed (intermediary number changed) or removed entirely (became an `@interface` marker, became a top-level class, or was inlined into the outer class).

Cross-references: `platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-007-mojmap-name-decoupling.md` (the ADR), `platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-006-ticket-radius-and-non-expiring-type.md` (the radius/flag analysis for 1.21.5+ that fed into the R11 flag computation).

**Operator-confirmed (2026-05-06):** `/rtp` functional on a live 1.21.11 Fabric server via the wrapper SPI + R11 adapter; observed average latency ~4 ms (high relative to Paper but within acceptable range for Fabric). Closes the 1.21.11 multiversion track started at the top of this entry.

---

## 2026-05-08 - Don't "simplify" `Object`-typed accessor wrappers on the Fabric early-startup path

When working on Fabric MC 26.1 (deobfuscated runtime, `rtp-fabric-ADR-007`), several `FabricServerAccessor` / `FabricEventBridge` methods were deliberately reshaped to **route any cross-class call that touches a `net.minecraft.*` type through an `Object`-typed wrapper, with the actual cast/dispatch performed reflectively (`Class.forName` + `Method.invoke`)**. Examples in `rtp-fabric-common`:

- `FabricServerAccessor.registerWorldObject(Object)` / `unregisterWorldObject(Object)` — keeps `class_3218` (`ServerLevel`) out of `FabricEventBridge.onServerStarted`'s bytecode constant pool.
- `FabricEventBridge.registerWorldEventsReflective()` / `registerPlayConnectionEventsReflective()` — keeps `ServerWorldEvents`, `ServerPlayConnectionEvents`, and their callback-parameter types (`class_3244` etc.) out of the bridge's `<init>` / `register` bytecode.
- `FabricCommandRegistrar.registerRtpCommand(Object root, Object bridgeCtx)` — keeps `CommandRegistrationCallback` and `CommandBuildContext` (`class_7157`) out of `RTPFabricMod.onInitialize`.
- `FabricBrigadierSourceBridge.resolveSenderUuid(Object)` / `checkPermission(Object,String)` — keeps `CommandSourceStack` (`class_2168`) and `ServerPlayer` (`class_3222`) out of `RTPFabricMod`.

These wrappers look like dead code or pointless indirection on every other platform; they are **load-bearing** on 26.1+. JVM verification of a method *also* verifies every type in its constant pool, including types referenced only by `instanceof` / `checkcast`. On a runtime where `class_NNNN` does not resolve, *any* of those methods will fail to link the moment they are first executed — even if the method body would never have actually used the type.

**Mistake to avoid (committed once, 2026-05-08):** during a follow-up pass to silence the `class_7923` warning loop, the `registerWorldObject` body was "simplified" from a reflective dispatch back to `if (level instanceof ServerLevel sl) registerWorld(sl);`. That re-introduced `class_3218` into the wrapper's constant pool and brought back the exact `NoClassDefFoundError` warnings the wrapper was created to absorb (3× at server start). The user immediately caught it ("did you inline make a change for a reason then revert it to 'simplify'?"); I had to restore the reflective form and add this entry.

**Rule going forward:** when you see one of these `Object`-typed wrappers in `rtp-fabric-common`, treat the verbose-looking reflective body as the contract and **do not collapse it to a typed cast**, even if it compiles and passes the unit tests on every other MC version. The contract is "this method's bytecode references zero `net.minecraft.*` symbols"; the test suite cannot prove that on a 26.1 runtime because we don't have one in CI. The bytecode-scan regression tests for `RTPFabricMod` and `FabricAnsiText` exist for exactly this reason — when adding a new such wrapper, add a matching scan test if practical.

If genuine simplification is desired, the path is to add a bytecode-scan test for the wrapper (asserting no `net/minecraft/` references in its `.class`) *first*, then verify any refactor still passes — not the other way around.

---


## 2026-05-11 - RTP.miscAsyncTasks is drained on Fabric via core's self-scheduled AsyncTaskProcessing, not via the platform mod

A Fabric ⇄ Bukkit startup-parity audit briefly suspected that RTP.miscAsyncTasks was never drained on Fabric because a recursive search over 
tp-fabric/** and 
tp-plugin/.../fabric/ found zero references to the symbol. That was a false positive. The drain is in 
tp-core's RTP constructor: line 243 schedules a new AsyncTaskProcessing(25ms) on RTP.scheduler.runTaskTimerAsynchronously(...) every tick, and AsyncTaskProcessing.run calls RTP.getInstance().miscAsyncTasks.execute(...). On Fabric, FabricScheduler.runTaskTimerAsynchronously(task, delay, period) queues () -> ASYNC_EXECUTOR.execute(task) into the per-tick scheduled map, which FabricEventBridge drives from ServerTickEvents.END_SERVER_TICK. Net effect: once setServer(MinecraftServer) fires, every server tick dispatches an AsyncTaskProcessing instance onto the Fabric scheduler's ASYNC_EXECUTOR, which drains miscAsyncTasks exactly as it does on Bukkit/Paper/Folia. The takeaway: when auditing parity for a 
tp-core field, search 
tp-core first (specifically the RTP constructor and 	asks.tick package) before concluding the platform adapter is missing wiring.

## 2026-05-26 - Spark-tagged regions in the live pipeline (no Spark soft-dep needed)

Spark's async sampler attributes samples by stack-frame method name plus thread name; it has no public source-tag API. To make Spark reports for the five "demanding code paths" (architecture diagrams 01-05) self-describing without depending on Spark itself, `RTPRunnable.runWithTracking()` now dispatches `run()` through one of a fixed allow-list of pre-named bridge methods when the subclass overrides `sparkFrameName()` to return a tag. The bridge method's Java name (e.g. `rtp_pipeline_attempt`) is what shows up in the Spark stack frame, which is why the dispatch table is hard-coded rather than synthesized - Spark can read method names, not dynamic strings.

Active tags (do not rename without updating this entry; saved Spark report URLs reference them):

- `rtp_pipeline_attempt` - `TeleportPipelineTask` (diagrams 01 + 08)
- `rtp_cache_generator` - `RegionCacheTask` (diagram 02)
- `rtp_scan_crawler` - `ScanTask` (diagram 05)
- `rtp_async_task_drain` - `AsyncTaskProcessing` (diagrams 01/02 async worker drain)
- `rtp_scan_drain` - `ScanTaskProcessing` (diagram 05)
- `rtp_force_queue` - `ForceQueue` (diagram 02 force-queue trigger)
- `rtp_active_gc_sweep` - `MemoryTracker.runDiagnostics` (diagram 04; static method, wraps body directly rather than going through `RTPRunnable` since `MemoryTracker` is not a task)

The convention is `rtp_<stage>` snake_case. Adding a new tag requires three coordinated edits: a new bridge method on `RTPRunnable`, a new `case` in `RTPRunnable.runTagged`, and a row in this list. Diagram 03 (chunk-ticket lifecycle) intentionally has no dedicated tag - its work happens inside `TeleportPipelineTask` and `RegionCacheTask`, both already tagged. Cost: one extra (cheap) bridge stack frame per tagged task run. The takeaway: when you need profiler observability for a hot path, you do not need to add Spark as a dependency - a named method on the call stack is the entire contract.
# RTP Code Tour — Behavioral Flow for Repair Work

> **Audience.** Minecraft plugin developers at roughly CS-student level (and AI agents) whose job is to locate *where* RTP might need repair given a reported behavior — a laggy teleport, a stuck queue, a leaked chunk, a rejected safe spot, a cancelled `/rtp` with no feedback.
>
> **This doc is not a reference.** It is a guided walk through the diagrams in [`docs/architecture/`](../architecture/) and the module graph in [`ARCHITECTURE.md`](ARCHITECTURE.md), narrating each arrow with *what can go wrong there* and *which rule or ADR governs the answer*. Detailed explanations live in the linked docs; read them only when the tour points you at them.
>
> **Before you change code**, run the pre-flight checklist in [`.junie/AGENTS.md`](../../.junie/AGENTS.md) (S-00x rules, Folia threading, `.bak` policy).

---

## How to use this tour

Pick the symptom closest to the reported bug, jump to that section, and follow the diagram arrows. Each step answers three questions:

1. **Where am I in the code?** (module → class → method)
2. **What invariant must hold here?** (S-00x rule, REQ-*, ADR)
3. **How does this step typically break?** (the realistic failure mode)

If a section sends you to a sibling doc, treat that doc as *optional deep-reading* — return here when done.

Symptom → start here:

| Symptom | Section |
|---|---|
| "`/rtp` takes seconds / blocks the server" | [§2 Teleport pipeline](#2-teleport-pipeline-end-to-end) |
| "Queue never refills / always empty" | [§3 Budgeted cache generator](#3-budgeted-cache-generator-queue-refill) |
| "Chunks stay loaded forever / RAM grows" | [§4 Chunk ticket lifecycle](#4-chunk-ticket-lifecycle) and [§5 Active GC sweep](#5-active-gc-sweep) |
| "`/rtp scan` stalls, crashes, or never finishes" | [§6 Scan task crawler](#6-scan-task-crawler) |
| "I don't know which module to edit" | [§1 Module map](#1-module-map-where-does-my-change-go) |
| "Teleport silently does nothing / no error message" | [§7 Failure attribution and user feedback](#7-failure-attribution-and-user-feedback) |
| "Folia throws `ThreadAccessException` / wrong region" | [§8 Folia threading gotchas](#8-folia-threading-gotchas) |
| "Plugin doesn't start / missed WorldLoadEvent / integrations didn't hook" | [§10 Plugin setup lifecycle](#10-plugin-setup-lifecycle) |
| "Why did `/rtp` pick *that* world/region? / override loop error" | [§11 How behaviors are decided](#11-how-behaviors-are-decided) |
| "Why was this spot rejected? / too many `vert` / `biome` / `safety` misses" | [§12 Location selection per attempt](#12-location-selection-per-attempt) |
| "Changed a config value and it didn't take effect / `/rtp reload` leaks / messages stuck in English" | [§13 Configuration load and reload](#13-configuration-load-and-reload) |
| "Server stop hangs / cached locations lost after restart / leaked chunks on `/stop`" | [§14 Shutdown and flush lifecycle](#14-shutdown-and-flush-lifecycle) |

---

## 1. Module map — where does my change go?

The dependency graph (source: [`ARCHITECTURE.md`](ARCHITECTURE.md#module-dependency-graph)) tells you the *only* legal direction code may flow:

```
rtp-api ──► rtp-core ──► rtp-plugin ◄── rtp-spigot / rtp-paper / rtp-folia
   │                                    (and rtp-core ──► rtp-fabric)
   └──► addons/
```

**Repair rule of thumb.** Ask *"is the bug behavior different on Folia vs Paper vs Spigot?"*

- **Same on every platform** → the defect lives in `rtp-core` or `rtp-api`. Never put `org.bukkit.*` imports there — `RTPServerAccessor` is the only bridge (see [`.junie/AGENTS.md` Logging & Feedback](../../.junie/AGENTS.md)).
- **One platform only** → the defect lives in that platform adapter. Do **not** "fix" it in core by branching on platform; push the difference behind an abstraction.
- **Only in addons** → the addon calls `rtp-api` incorrectly, or `rtp-api` needs a new capability (propose via ADR first — see [`docs/adr/README.md`](../adr/README.md)).

> Deep read (optional): [`ARCHITECTURE.md`](ARCHITECTURE.md) for the enforced boundaries, [`DESIGN.md`](DESIGN.md) for why they exist.

---

## 2. Teleport pipeline (end-to-end)

Canonical diagram: [`docs/architecture/01-teleport-execution-pipeline.md`](../architecture/01-teleport-execution-pipeline.md). Open it in a side window; each node below matches a state in that state-diagram.

The pipeline is four phases — **Setup → Load → Teleport → Cleanup** — embodied by `TeleportPipelineTask` in `rtp-core/.../common/tasks/teleport/`. Static action lists (`setupPreActions`, `setupPostActions`, `loadPreActions`, …) are the only supported extension points; addons register into them.

Walk the diagram with repair eyes:

1. **`CmdTrigger` → `QueryCache`.** The player runs `/rtp`. The command lives in `commands-api` and the Bukkit dispatch in `rtp-plugin/.../bukkit/commands/`. *Break:* if the user sees nothing at all, the command probably failed parameter validation — see REQ-RTP-S-007 ("busy" / "invalid command" messages must be configurable via `messages.yml`) and §7 below.

2. **`QueryCache` choice node.** `RegionQueueManager` checks whether a pre-validated location is already waiting. *Break:* if teleport is instant when the server is idle but slow under load, the region's queue is starving — jump to [§3](#3-budgeted-cache-generator-queue-refill).

3. **`cache_check → ReqTicket` (hot path) / `perm_check` (cold path).** The `unqueued` permission lets a player skip the wait and trigger an ad-hoc search. *Break:* a player with `unqueued` causing server stutter is running an unbounded loop — ensure the shape is an Archimedean 1D mapping, not a reroll loop ([ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md)).

4. **`GenRandom` — SETUP stage.** The shape (`Circle`, `Square`, …) emits `(x,z)` from its 1D index. This runs on an async worker. *Break:* non-uniform distribution usually means a custom `Shape` in an addon violates the 1D contract; `MemoryShape` caching of bad indices can also mask it.

5. **`ReqTicket` — LOAD stage.** Here the platform adapter is called to asynchronously acquire a chunk ticket. This is the single highest-risk step in the whole plugin:
   - **S-005** — no synchronous chunk I/O on the main thread.
   - **S-002** — no permanently force-loaded chunks (use plugin chunk tickets, not `Chunk.setForceLoaded(true)`).
   - On pure Spigot, `BukkitRTPWorld.loadChunkFuture` may bounce to tick; see [`DESIGN.md §rtp-spigot Implementation Notes`](DESIGN.md) and [ADR-016](../adr/ADR-016-anvil-subsystem.md) for the Anvil pre-filter that avoids a load.

6. **`EvalBlocks` — TELEPORT-prep safety.** Runs on the region-owning thread (Folia) or main thread (Paper/Spigot). *Break:* if a "safe" spot turns out to be in lava or a claim, inspect `Region.isSafe` plus the verticalAdjustor and the `badLocation` predicates — see [ADR-017](../adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md). S-001 forbids *unsafe-block destinations*; S-003 forbids *claim-protected land*. Never add a "second" block check in an adapter — keep all checks in `rtp-core`.

7. **`EvalBlocks → GenRandom` retry / `Teardown` max-retries.** Bounded by configuration (`maxAttempts`). *Break:* silent exhaustion → see §7, and the regression guard `ReqRtpS004NullChunkAttributionTest` mentioned in [`.junie/AGENTS.md`](../../.junie/AGENTS.md).

8. **`MovePlayer` — Entity Scheduler.** On Folia, player mutations go through the Entity Scheduler, not the Region Scheduler. *Break:* teleport "succeeds" but the player ends up at spawn → wrong scheduler or a pre/post-teleport event handler threw and the pipeline swallowed it (S-004 violation).

9. **`Teardown` — Cleanup phase.** `TeleportPipelineTask.runCleanup()` releases the chunk reservation, untracks from `MemoryTracker`, and decrements `inFlightCalculations`. This **must** run on every exit path — normal, exception, disconnect, cancel. Jump to [§4](#4-chunk-ticket-lifecycle).

> Deep read (optional): [`DESIGN.md §Pipeline Phases`](DESIGN.md), [`REQUIREMENTS.md §3`](REQUIREMENTS.md) for all S-00x.

---

## 3. Budgeted cache generator (queue refill)

Canonical diagram: [`docs/architecture/02-budgeted-cache-generator.md`](../architecture/02-budgeted-cache-generator.md).

The queue is refilled by a separate *pulse* — `selectionAPI.compute()` — that wakes on a timer, walks every region in round-robin order, and spends at most one **budget** per tick/pulse. The budget is either **time-bound** (Spigot/Paper) or **count-bound** (Folia). Which one you get is not negotiable: Folia per-region ticks make wall-clock slicing non-deterministic.

Walk the diagram:

- **`PulseTrigger → InitBudget → CheckBudget`.** *Break:* if a region never refills, the round-robin `period` gate (`CheckPeriod`) is probably too large, or an earlier region is eating the whole budget. Log the per-region spend in `MemoryTracker.runDiagnostics()`.
- **`ExecuteRegion → SpawnWorker → PushQueue → WakePlayer`.** Players parked in the public queue (node `QueueWait` in diagram 01) are unblocked *here*, not in `/rtp` itself. *Break:* "player waits forever even though queue just got a location" usually means the wake-up signal was missed — check the ordering of `PushQueue` and the notification to `QueueWait`.
- **`YieldTask`.** When the budget is exhausted the pulse yields. *Break:* a runaway `while`-loop in a custom shape will never yield — S-005-adjacent and the reason bounded algorithms are mandated (see [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md)).

> Deep read (optional): [`DESIGN.md §Pulse-Driven Maintenance`](DESIGN.md).

---

## 4. Chunk ticket lifecycle

Canonical diagram: [`docs/architecture/03-chunk-ticket-lifecycle.md`](../architecture/03-chunk-ticket-lifecycle.md).

Every chunk the plugin touches **must** be framed by `addPluginChunkTicket` / `removePluginChunkTicket` plus a `MemoryTracker.track` / `untrack` pair. The diagram has three exit paths and **all three converge** on `DropTicket → UntrackRes → RAM Freed`:

1. **Happy path:** `EvalBlocks → CloseRes` via `try-finally`.
2. **Stalled pipeline:** `SweepTask → ForceClose` from the background GC (see [§5](#5-active-gc-sweep)).
3. **Player disconnect:** the quit listener calls `reservation.close()` for every in-flight task tied to the player.

*Break patterns:*

- Ticket acquired, exception thrown before the `finally` — chunk leaks. S-002 violation risk.
- Ticket closed *twice* (once in the happy path, once in a cleanup action) — adapter may log a warning or crash on some versions. The fix is **idempotent `close()`**, never a null-guarded skip.
- `Chunk.setForceLoaded(true)` used instead of plugin tickets — permanent leak, not reclaimed on disable. Hard prohibition.

> Deep read (optional): [`DESIGN.md §Chunk Allocation Management`](DESIGN.md), [`.junie/AGENTS.md` Code & Testing Conventions](../../.junie/AGENTS.md).

---

## 5. Active GC sweep

Canonical diagram: [`docs/architecture/04-active-gc-sweep.md`](../architecture/04-active-gc-sweep.md).

RTP does not trust the happy path alone. A periodic async timer does two sweeps:

1. **Internal sweep** — iterate `MemoryTracker`'s tracked reservations; if `age > timeout`, force-close them, decrement `inFlightCalculations`, untrack.
2. **Native sweep** — query the server for *all* chunk tickets owned by the plugin and drop any that the internal tracker doesn't know about. This catches orphans from code paths that forgot to `track()`.

*Break patterns:*

- Timeout too aggressive → healthy but slow pipelines are killed mid-teleport (manifests as S-004 silent discards *unless* the force-close routes through the proper failure attribution).
- Timeout too lax → real leaks accumulate for minutes before GC reclaims them.
- Native sweep disabled or broken → orphans accumulate silently; only the server RAM graph reveals it.

When debugging a "slow leak" complaint, enable `MemoryTracker.runDiagnostics()` output first, not Java heap dumps.

---

## 6. Scan task crawler

Canonical diagram: [`docs/architecture/05-scan-task-crawler.md`](../architecture/05-scan-task-crawler.md).

`/rtp scan` is a separate long-lived async worker. It shares the safety checks with the teleport pipeline but has its own throttle (`inFlightGate`) and its own wrap-up (checkpointing to disk via `.scan`).

Walk it when:

- **Scan stalls at N% forever.** `DrainGate` is waiting on chunk futures that never complete. Inspect the adapter's `getOrLoadChunk` — on pure Spigot, uncovered by the Anvil pre-filter, it bounces to the main thread and can starve under heavy TPS drop ([ADR-016](../adr/ADR-016-anvil-subsystem.md)).
- **Scan disagrees with live teleport.** The scan uses `AnvilBiome` (off-tick pre-filter) while the teleport pipeline may use live generation — see `isSelfContained()` branch in the diagram. For the deeper rationale of the pre-filter and what it can/cannot decide without a load, read [ADR-016](../adr/ADR-016-anvil-subsystem.md).
- **Scan looks like it leaks.** It shouldn't — it goes through the same ticket lifecycle from [§4](#4-chunk-ticket-lifecycle). If it does, look at the `DrainGate` path: releases happen in the callback, so an exception in `VertAdjust` / `PhysBiome` *before* `ReleaseGate` leaks a permit and a ticket.

---

## 7. Failure attribution and user feedback

Two absolute rules converge here:

- **S-004** — no silently discarded teleport failures. Every pipeline exit that is not a successful teleport must *attribute* the failure (there is a `FailTypes` enum and a `FailTypes.nullChunk` path guarded by `ReqRtpS004NullChunkAttributionTest`).
- **S-007** + **REQ-RTP-F-013** — all user-facing messages (including "busy" and "invalid command") are configurable via `messages.yml`. Never hardcode strings in a command or adapter.

Where to look when a user reports "nothing happens":

1. Command layer (`commands-api`, Bukkit `BukkitBaseRTPCmd` and friends). Platform overrides of `msgInvalidCommand` / `msgBadParameter` **must** call `RTP.log(Level.WARNING, msg)` — this is required for auditing and for `rtp test full` to observe the failure (see [`.junie/AGENTS.md` Logging & Feedback](../../.junie/AGENTS.md)).
2. Pipeline cleanup (`runCleanup()`) — did the failure reach an action list that reports to the player? Silent `return` inside a phase is the classic S-004 violation.
3. Config loader — a missing or malformed `messages.yml` key reads as empty string. Check the fallback.

> Deep read (optional): [`TRACEABILITY.md`](TRACEABILITY.md) row `REQ-RTP-F-013`, [`REQUIREMENTS.md §3`](REQUIREMENTS.md) for S-004/S-007.

---

## 8. Folia threading gotchas

Folia splits the world into regions, each owned by a dedicated thread. Calling the wrong API on the wrong thread throws `ThreadAccessException`. The short checklist:

- **Before scheduling:** `Bukkit.isOwnedByCurrentRegion(entity|location)`. If yes, run inline; if no, `RegionScheduler.run(plugin, location, task)`.
- **Player mutations** (teleport, inventory) — **Entity Scheduler**, not Region.
- **Vault / economy** (`withdraw`, `deposit`, `getBalance`) — **Global Region Scheduler** or **Async Scheduler**. Region threads throw.
- **Task pipelines** — **Count-Bound** only on Folia (`CountBoundTaskPipe`). Time-Bound is permitted on Spigot/Paper only.
- **Database** — enabled on all platforms; on Folia it runs via `RTP.scheduler.runTaskTimerAsynchronously`.

When a bug reproduces only on Folia, the answer is almost always one of the above.

> Deep read (optional): [`DESIGN.md §rtp-folia Implementation Notes`](DESIGN.md), [`.junie/AGENTS.md` Folia Threading](../../.junie/AGENTS.md).

---

## 9. Your first repair — checklist

1. Reproduce locally (or write a failing test — see [`COVERAGE_PLAN.md`](COVERAGE_PLAN.md)).
2. Identify the section above that matches the symptom.
3. From the diagram, name the *arrow* where behavior diverges from the spec.
4. Find the enclosing S-00x rule / REQ-* via [`TRACEABILITY.md`](TRACEABILITY.md).
5. Edit the correct module per [§1](#1-module-map-where-does-my-change-go). Before editing any *uncommitted code* file, make a `.bak` copy ([`.junie/AGENTS.md` Backup Policy](../../.junie/AGENTS.md)).
6. Add or extend a REQ-traceable test (class name or `@DisplayName` referencing `REQ-*` / `S-00x`) and update [`TRACEABILITY.md`](TRACEABILITY.md) if new.
7. Run the targeted test via `.\gradlew :<module>:test --tests "<pattern>"` (PowerShell, `;` not `&&`).
8. If the change crosses a module boundary or touches more than one class, **stop and propose first** per [`.junie/AGENTS.md` Propose Before Refactoring](../../.junie/AGENTS.md).

---

## 10. Plugin setup lifecycle

Canonical diagram: [`docs/architecture/06-plugin-setup-lifecycle.md`](../architecture/06-plugin-setup-lifecycle.md). Entry class: `RTPBukkitPlugin` (`rtp-plugin/.../bukkit/RTPBukkitPlugin.java`).

Startup is the one path that is not repeated at runtime, so bugs here look like "plugin silently half-enabled" rather than a pipeline failure. Read the diagram, then use the following repair lenses:

- **`onLoad` fail-fast.** Only SQLite JDBC is probed; missing JDBC throws `IllegalStateException` *before* Bukkit calls `onEnable`. If you see no RTP log lines at all, check the server log for that exception first.
- **Reflective accessor wiring** (`BukkitServerProvider.resolveServerModel` → `Class.forName(serverModel.accessorClassName)`). A `ClassNotFoundException`/`NoSuchMethodException` here bails out via `onDisable()` *from inside `onEnable`*. Symptom: "plugin shows as enabled in `/pl` but every command says unknown". Fix: confirm the platform detection (`isPaper()` / `isFolia()`) resolved the expected model, and that the adapter JAR for that platform is on the classpath.
- **Synchronous event registration.** `setupBukkitEvents()` is deliberately called in-line, *not* via `runTaskLater(..., 1)`. The git history on `OnWorldLoadUnload` records why: Multiverse-style generators fire `WorldLoadEvent` on tick 1, and a deferred listener missed them, leaving dormant regions for late-loaded worlds unbound. If you ever feel tempted to "clean this up" by deferring, don't — read the Javadoc on `OnWorldLoadUnload.rebindFallbackRegionsForAllLoadedWorlds` first.
- **Startup tasks drained three times.** `RTP.startupTasks.execute(MAX)` is invoked eagerly, then on tick 1 (via `runTaskLater`), then again after integrations/effects register — because integrations can push new startup tasks. If a feature "works after `/rtp reload` but not on a fresh boot", it's probably a startup task registered too late to be drained.
- **Deferred integrations (`setupIntegrations`, `setupEffects`).** These run on tick 1 so that other plugins have completed their own `onEnable`. If a claim-plugin integration is missing, the usual cause is that the claim plugin enabled *after* RTP's tick-1 hook; check load-order in `plugin.yml` (`softdepend`).
- **`ChunkUnloadProcessor` is non-Folia only.** On Folia, per-region tick scheduling handles chunk lifetime. If a new chunk-unload bug reproduces only on Spigot/Paper, this timer is where to look.
- **Shutdown path.** `onDisable` cancels all RTP-owned Bukkit tasks, kills each subsystem processor (`AsyncTeleportProcessing`, `SyncTeleportProcessing`, `ScanTaskProcessing`, `DatabaseProcessing`), then calls `RTP.stop()`. Every allocator that ran during `onEnable` *must* have a matching release here — otherwise a `/rtp reload` or a server stop leaks state. See [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) for prior shutdown-flush pitfalls.

> Deep read (optional): [`DESIGN.md`](DESIGN.md) for the platform-adapter split, [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) for database / shutdown-flush / command-pipeline pitfalls.

---

## 11. How behaviors are decided

Canonical diagram: [`docs/architecture/07-rtp-command-region-selection.md`](../architecture/07-rtp-command-region-selection.md). Scope: the `/rtp` + `/wild` command path through `SelectionAPI.getRegion(player)` — other behavior paths (onEvent auto-teleport, `tempRegion`, `/rtp scan`) have their own entry points and are not covered by this diagram. Entry class: `SelectionAPI.getRegion(RTPPlayer)` in `rtp-core/.../common/selection/SelectionAPI.java`.

The key mental model: **RTP's behavior is data, not code.** From the moment the player issues `/rtp`, every subsequent choice (which world, which region, which shape, which vertical adjustor, cache size, price, whether to queue or search ad-hoc) is read from configuration and permission nodes. `rtp-core` never branches on world or region *name*.

Walk the diagram as a repair tool:

1. **Command layer.** Parameter validation failures route to `msgBadParameter`; a server already saturated with in-flight calculations routes to `msgBusy`. Both strings are configurable (S-007, REQ-RTP-F-013). If a user says "nothing happens", this is the first lens — see also [§7](#7-failure-attribution-and-user-feedback).
2. **World resolution loop.** `WorldKeys.requirePermission` + `WorldKeys.override` form a chain: if the player lacks `rtp.worlds.<name>`, the world falls back to the configured override. A `Set<String> worldsAttempted` guard throws `IllegalStateException("infinite override loop detected at world - ...")` on cycle — this is *not* an S-004 violation; it is a configuration bug surfaced loudly on purpose.
3. **Region resolution loop.** Identical structure with `RegionKeys` and `rtp.regions.<name>`. Same cycle guard, same exception. The region key chosen at the end of the world loop is the *starting* region for this loop.
4. **Queue vs ad-hoc search.** `rtp.unqueued` is the pivot. Without it, the player waits on the public queue (diagram 02); with it, an ad-hoc async search is spawned immediately. Avoid granting `rtp.unqueued` broadly — on large servers every holder can trigger a search, and only the 1D spiral guarantee ([ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md)) keeps that bounded.
5. **`RegionSettings` is the leaf.** Shape, vertical adjustor, cache cap, active chunk cap, price, spatial resolution, world-border override — all sourced from `region.yml`. If two regions "behave differently" for no apparent reason, diff their `RegionSettings`, not their code paths.

*Break patterns:*

- **"Player gets teleported to the wrong world."** Almost always a `WorldKeys.override` chain that quietly redirects. Dump the `worldsAttempted` set by enabling verbose logging, or trace `SelectionAPI.getRegion(player)` by hand.
- **"`IllegalStateException: infinite override loop`."** A config author wrote a cycle (e.g., `world_nether.override: world` and `world.override: world_nether` both with `requirePermission: true`). Fix the config; do not catch the exception.
- **"Shape feels clustered."** Not a decision-tree bug — see [§2 step 4](#2-teleport-pipeline-end-to-end) and [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md).
- **"Effects don't fire."** Effects are a *separate* decision tree driven by `rtp.effect.<stage>.*` permissions; set `effectParsing: true` in `performance.yml`. See [`docs/admin/EVENTS_AND_EFFECTS.md`](../admin/EVENTS_AND_EFFECTS.md).
- **"I want one command to use a custom shape."** Use `SelectionAPI.tempRegion(params, baseRegionName)` — it clones a base region and overrides specific `RegionKeys`. Never subclass `Region` in an addon.

> Deep read (optional): [`REQUIREMENTS.md §3`](REQUIREMENTS.md) (S-007 configurable messages), [`docs/admin/EVENTS_AND_EFFECTS.md`](../admin/EVENTS_AND_EFFECTS.md), [`GLOSSARY.md`](GLOSSARY.md) for the canonical meaning of *region* vs *world* vs *shape*.

---

## 12. Location selection (per attempt)

Canonical diagram: [`docs/architecture/08-location-selection-per-attempt.md`](../architecture/08-location-selection-per-attempt.md). Entry class: `PregenTask.runAttempt` in `rtp-core/.../common/selection/region/PregenTask.java`, called via `LocationGenerator.getLocationFuture`.

This is the **decision core** of the plugin. Every caller you met earlier — the `/rtp` command ([§11](#11-how-behaviors-are-decided)), the cache generator ([§3](#3-budgeted-cache-generator-queue-refill)), `/rtp scan` ([§6](#6-scan-task-crawler)) — ends up asking `ILocationGenerator` for a coordinate, and that coordinate comes out of this loop. If it emits a bad `(x, y, z)` or emits one too slowly, *everything downstream looks broken*. That is why it gets its own diagram separate from the outer attempt-loop plumbing.

Mental model: one attempt = **pick → probe → resolve → evaluate → accept or recycle**. The loop is orchestrated by `PregenTask` as a non-blocking state machine ([ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md) Option B) so the async worker is never parked on `.get()`.

Walk the diagram as a repair tool:

1. **Cap check.** `i > maxAttempts` or `biomeChecks >= maxBiomeChecks` → `completeExhausted`. *Break:* empty `GenerationResult` with no logged reason almost always means `maxBiomeChecks` was hit silently. Dump `state.failMap` (verbose) to see which bucket drained the budget.
2. **Shape pick.** `MemoryShape.rand()` is the bounded Archimedean spiral ([ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md)). If `biomeRecall: true`, a prefix-sum weighted pick is used over biomes already seen; `biomeRecallForced: false` silently falls back to uniform when memory is empty. *Break:* "clustered spawns" → recall is on and has converged on a few tiles; toggle it off or lower `cacheCap` so the memory churns.
3. **WorldBorder probe.** If the candidate is outside, `worldBorderFails++` and `maxAttempts++` — cheap misses. Cap is 1000. *Break:* a region configured beyond the vanilla border will hit the cap and emit an empty result; check `worldBorderOverride` in `region.yml`.
4. **Probe-first chunk resolution.** `world.getOrLoadChunk(cx, cz)` walks cached → Anvil → live ([ADR-016](../adr/ADR-016-anvil-subsystem.md) §13.1). A null return is attributed to `FailTypes.nullChunk` with a sub-reason (`ticketFailed`, `chunkLoadTimeout`, `asyncLoadNull`). **Do not refactor this attribution** — the regression guard is `ReqRtpS004NullChunkAttributionTest` (S-004).
5. **Self-contained vs live branch.** If `chunk.isSelfContained()` (Anvil), stay on the async thread. Otherwise hop to the region-owning thread via `dispatchLiveEvaluation`, which allocates the `ChunkReservation` ([§4](#4-chunk-ticket-lifecycle)) and arms the [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md) stale guard. **This branch is where most S-005 violations hide when porting** — if a new platform runs the live path on the wrong thread, you will see `ThreadAccessException` on Folia or silent corruption on Paper. The Fabric blocker mentioned in [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) is exactly this.
6. **`vert.adjust(chunk)`.** Returns the `(x, y, z)` for a given chunk under the region's vertical adjustor (linear, nether-ceiling, etc.). Null = no valid y. *Break:* "teleports into lava at y=-64" or "always picks y=0" → wrong adjustor for the dimension; verify `vert:` in `region.yml`.
7. **Biome filter.** `biomeNames` + `biomeWhitelist`. Misses increment `biomeChecks` (soft cap `maxBiomeChecks`) but also `maxAttempts++`, so biome filtering doesn't prematurely exhaust hard attempts. *Break:* "infinite misses" when targeting a rare biome → turn on `biomeRecall` so the shape remembers hits; without it every attempt is uniform across the whole region.
8. **Neighbour grid load.** For `safetyRadius r`, loads the `(2r+1)²` neighbour chunks via `getChunkAt` with a 5-second `orTimeout`. Timeout or any null neighbour → `FailTypes.nullChunk / neighborNull`. *Break:* scans or caches that stall with `neighborNull` in verbose output point at a slow chunk backend — check the Anvil pre-filter wiring (ADR-016 §11).
9. **Safety y-scan.** Walks `±safetyRadius` around `(x, y, z)`, rejecting if any block is in `unsafeBlocks`. This is the S-001 enforcement point. Never add a second check in an adapter or command; all block safety lives here (and in the `badLocation` predicates, [ADR-017](../adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md)).
10. **`GlobalRegionVerifiers`.** Async chain that runs claim-plugin checks (S-003) and any custom verifiers addons have registered. Failure → `FailTypes.safetyExternal`. *Break:* "claim overlap still teleports me in" → a verifier is missing or throwing and being swallowed; the pipeline attributes the exception, but addons must register a verifier to enforce their claim system. Inline claim calls in the pipeline or commands are an S-003 violation.
11. **`completeSuccess`.** Records the biome hit back into `MemoryShape`, preloads a `ChunkSet` of radius `max(safetyRadius, performance.viewDistanceSelect)`, and transfers ownership via `GenerationResult` so diagram 01's `Start LOAD` stage can hand it off without a second round-trip. *Break:* "player arrives and chunks pop in for a second" → `viewDistanceSelect` is too low, or the `ChunkSet` was dropped (check `MemoryTracker` diagnostics, [§4](#4-chunk-ticket-lifecycle)).

*Recurring repair lens:* almost every rejection calls `MemoryShape.addBadLocation(finalL)` so the spiral won't re-propose that 1D index. This is what makes `vert` / `safety` / `safetyExternal` failures self-limiting on a correctly-shaped region. If you see the same coordinate rejected twice within one cache fill, either the shape is not a `MemoryShape` (so it has no memory) or `addBadLocation` wasn't called on that path — audit the new branch.

> Deep read (optional): [`DESIGN.md`](DESIGN.md) for the pipeline-vs-generator split, [ADR-015](../adr/ADR-015-stale-chunk-guard-countbound-pipes.md) for the non-blocking state machine, [ADR-016](../adr/ADR-016-anvil-subsystem.md) for the probe-first chain, [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md) for why the pick itself is bounded.

---

## 13. Configuration load and reload

Canonical diagram: [`docs/architecture/09-configuration-load-and-reload.md`](../architecture/09-configuration-load-and-reload.md). Entry class: `Configs` in `rtp-core/.../common/configuration/Configs.java`; called from `RTPBukkitPlugin.onEnable` (first load) and from the `/rtp reload` command (subsequent reloads) via `Configs.reload -> reloadAction -> reloadConfigs + reloadRegions`.

This section is the one to open *first* for any bug where "the config says X but RTP acts as if Y". Most such reports resolve here before you ever touch the pipeline.

Mental model: **RTP never mutates a `ConfigParser` in place.** A reload builds a fresh set of parsers off to the side, then swaps the `configParserMap` / `multiConfigParserMap` fields atomically. Any task already holding a reference to an old parser keeps reading the old values until it finishes. This is by design — it makes mid-teleport reloads safe — but it is also why key changes sometimes appear to "not apply" for one cycle.

Walk the diagram as a repair tool:

1. **`FlushDB` → `CancelTasks`.** `fileDatabase.processQueries(MAX)` drains the write queue before reconnecting; then in-flight teleports are cancelled via `RTPTeleportCancel` and `processingPlayers` is cleared. *Break:* if a reload crashes mid-teleport, someone bypassed `Configs.reload` and skipped the cancel step — always reload through the command or `Configs.reload`.
2. **New parsers (blue subgraph).** Seven single-file parsers (`logging.yml`, `config.yml`, `messages.yml`, `economy.yml`, `performance.yml`, plus the `safety/` directory) and two `MultiConfigParser`s (`regions/`, `worlds/`). Adding a new config category means adding an enum under `configuration/enums/` and a new `ConfigParser<...>` line here — there is no registry, the list is deliberately explicit.
3. **Locale bootstrap** ([ADR-020](../adr/ADR-020-language-bootstrap-and-locale-aware-configparser.md) / REQ-RTP-F-013). Before any `ConfigParser` is constructed, `LanguageBootstrap` reads `plugins/RTP/language.yml` (created with `language: en` on first run) and returns a sanitized locale string. Each fresh parser is then built locale-aware: when the locale is non-`en`, the parser loads `lang/<locale>/<name>.yml` (and the optional `lang/<locale>/<name>.lang.yml` key-name remap) directly from the jar with English fallback. *Break:* "messages still in English despite `language: de` in `language.yml`" → the corresponding `lang/de/messages.yml` jar resource is missing, or `language.yml` itself was deleted (it will be recreated as `en` on next reload). Note: editing `language` in `config.yml` has no effect — that key was removed.
4. **Atomic swap (green).** `this.configParserMap = newConfigParserMap;` is where new readers start seeing new values. In-flight tasks that captured the old map earlier in their lifetime continue on the old values — this is *not* a bug, it is the reason reloads are non-disruptive.
5. **`ShutRegions`.** Every `Region` in `permRegionLookup` and `tempRegions` has `shutDown()` called before the maps are cleared. *Break:* "reload duplicated my region" → a third party inserted into `permRegionLookup` after the clear, or `shutDown` threw on one entry and bailed out of the loop.
6. **`BuildRegions` → Dormant decision.** For each `regions/*.yml`, `RegionConfigLoader.load` produces a `RegionSettings`, then `detectFallbackConfiguredWorld` checks whether the configured world is already loaded. Yes → live region. No → **dormant region** (world is `null`, rebinds on `WorldLoadEvent` via `OnWorldLoadUnload.rebindWorld`). This is the Multiverse-compatibility path — see also [§10 step 3](#10-plugin-setup-lifecycle). *Break:* "region never activates when my world loads" → confirm the world name matches, and that `OnWorldLoadUnload` is registered (diagram 07).
7. **`ShapePick` — deferred async.** Each region's shape is selected on `miscAsyncTasks` with a 60-tick delay, and skipped entirely when the region is dormant or has no shape. *Break:* "shape is null on first attempt" after a reload → attempt fired before the 60-tick shape pick; this is expected, the next pulse will succeed.
8. **`onReload` callbacks.** Integrations, effects, and anything else registered via `Configs.onReload(Runnable)` fires here. *Break:* "integration re-hooks on first load but not on reload" → the integration didn't register an `onReload` callback; it only hooked during `setupIntegrations` in `onEnable` (diagram 07).

*Common misreads:*

- **"Per-world setting ignored."** Readers must go through `Configs.getWorldParserValue(worldName, key)`, which walks world-override chain → falls back to the global `ConfigKeys` parser. Reading `ConfigKeys` directly skips every per-world override.
- **"Per-region setting ignored."** Same pattern with `getParser(RegionKeys.class)` vs. the specific region's `ConfigParser<RegionKeys>` obtained through the region's own `RegionSettings`. `RegionSettings` is the *materialized* snapshot; mutate the YAML + reload, not the snapshot.
- **"`/rtp reload` ordering."** First-enable and reload take *exactly* the same path (`reloadAction`). If a bug reproduces on reload but not on fresh boot, the difference is in what was created *since* the boot — usually a region, a player cache, or an integration that didn't implement `onReload`.
- **"Override loop error on reload."** That exception comes from the `SelectionAPI.getRegion` cycle guard (diagram 08), not from this path. The config load is content-agnostic; cycles are only detected when something actually traverses the override chain.

> Deep read (optional): [`DESIGN.md`](DESIGN.md) for the per-world / per-region override resolution, [ADR-020](../adr/ADR-020-language-bootstrap-and-locale-aware-configparser.md) for the locale bootstrap, [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) for prior reload / database-flush pitfalls.

---

## 14. Shutdown and flush lifecycle

Canonical diagram: [`docs/architecture/10-shutdown-and-flush-lifecycle.md`](../architecture/10-shutdown-and-flush-lifecycle.md). Entry classes: `RTPBukkitPlugin.onDisable` (`rtp-plugin/.../bukkit/RTPBukkitPlugin.java`) and `RTP.stop()` (`rtp-core/.../common/RTP.java`).

Shutdown is the symmetric partner of [§10 Plugin setup lifecycle](#10-plugin-setup-lifecycle). Unlike a reload (§13), which reuses allocations, a shutdown must *release* every allocation made during `onEnable`. Two classes of bug dominate this path: **data loss** (cached locations not flushed before the DB stop flag is set) and **resource leaks** (chunk tickets not released — an S-002 violation).

Mental model: **four phases** — (1) *stop accepting new work*, (2) *drain in-flight work*, (3) *persist state to disk*, (4) *release platform resources*. The ordering between phases 3 and 4 is load-bearing.

Walk the diagram as a repair tool:

1. **Cancel command timers.** `commandTimer.cancel` and `commandProcessing.cancel` stop new `/rtp` dispatches. *Break:* "a new `/rtp` ran mid-disable and NPE'd" → a command listener was registered outside `setupBukkitEvents` and isn't cancelled here.
2. **Kill the four task processors.** `AsyncTeleportProcessing`, `SyncTeleportProcessing`, `ScanTaskProcessing`, `DatabaseProcessing` each have a static `kill()`. Each is wrapped in `catch (NoClassDefFoundError ignored)` because Bukkit can call `onDisable` twice on init failure (see the bail-outs at lines 108/119 of `RTPBukkitPlugin`). **Do not remove those guards.**
3. **`RTP.stop` enters.** Completes every outstanding `CompletableFuture` with `null`, cancels in-flight `TeleportData` via `RTPTeleportCancel`. *Break:* "server hang on stop" → something is `.get()`-ing a future that was never added to `RTP.futures`; audit new async code for the registration step.
4. **Database flush sequence — load-bearing ordering.** `SQL flush → rebuildCachedLocationsFromMemory → flushDirtyCache → processQueries(MAX)`, **all before** `stop.set(true)`. Regression test: `MemoryShapeShutdownTest`. *Break:* "cached locations gone after restart" is almost always a reorder here. `processQueries` bails immediately if `stop` is already set, so setting the flag early silently drops the drain. See [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) §"Shutdown ordering".
5. **Stop the task pipes and cancel tracked tasks.** `miscAsyncTasks.stop` + `miscSyncTasks.stop` + scheduler `cancelTask` for every entry in `trackedTasks`. *Break:* "a scheduled task kept running after disable" → it wasn't registered via `RTP.scheduler` (which adds to `trackedTasks`); fix the registration, not this loop.
6. **Region shutdown.** `permRegionLookup.values().forEach(shutDown)` then clear; same for `tempRegions`. A region's `shutDown` closes its own chunk reservations (see [§4](#4-chunk-ticket-lifecycle)) and unregisters from `MemoryTracker`. *Break:* "Folia warning about region threads" during shutdown → a region's `shutDown` touched entity state without a scheduler hop; route through `RTPScheduler`.
7. **Set the DB stop flag and close.** Only now does `databaseAccessor.stop.set(true)` + `close()` run. The flag gates new enqueues; `close` releases the JDBC connection. *Break:* "DB file locked on next startup" → either `close` threw (check logs) or an asynchronous write was still in flight (means step 4 didn't fully drain).
8. **Re-cancel any late `TeleportData`.** A second pass over `latestTeleportData` catches any entries that were added during region shutdown. Both cancel passes are required.
9. **`ScanTask.kill`, `networkManager.shutdown`, `serverAccessor.stop`.** Static registry clears and platform hooks. The network bus is now typed as the `RTPNetworkManager` interface (was `RedisManager`); concrete `RedisManager` is constructed reflectively in `RTP.createRedisNetworkManager` so `rtp-core` carries no symbolic ref to the Jedis driver class (ADR-024). On Folia, `serverAccessor.stop` must run on the global region scheduler — the accessor handles that internally; don't relocate the call.
10. **Post-`RTP.stop` — Bukkit-side cleanup.** Cancel all RTP-owned async `BukkitTask`s that were still pending (belt-and-suspenders for tasks not in `trackedTasks`), write the `referenceData` sentinel row (zero-UUID + timestamp) so the next boot can tell a clean shutdown from a crash, and finally `releaseAllChunkTickets`. **`releaseAllChunkTickets` is the last durable action and is the S-002 enforcement point** — if region shutdown throws and unwinds past it, tickets leak across the `/reload`.

*Break patterns:*

- **"`/stop` hangs for a minute, then kills the JVM."** A future that `CompleteFutures` can't reach, or a `processQueries(MAX)` that is waiting on a JDBC connection that died. Look at thread dumps taken during the hang.
- **"Chunks stay force-loaded after `/reload`."** S-002 regression. `releaseAllChunkTickets` either didn't run (exception unwound past it) or a new allocator bypassed the central ticket registry. Every chunk-ticket allocation path must register with `MemoryTracker` so this single call can release them.
- **"`referenceData` row missing / startup thinks every boot is a crash."** Someone moved the sentinel write ahead of `RTP.stop()`. It must be after, because it uses the still-open `DatabaseAccessor` and calls `processQueries(MAX)` one more time to force the write.
- **"`NoClassDefFoundError` in shutdown logs."** Expected and ignored — a platform class (e.g., Folia's scheduler) wasn't on the classpath because the adapter JAR was never installed, and `onDisable` ran as part of the `onEnable` bail-out. Real defects in shutdown will surface as other exceptions, not `NoClassDefFoundError`.

> Deep read (optional): [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) §"Shutdown ordering", [`TRACEABILITY.md`](TRACEABILITY.md) row `REQ-CORE-NF-001` (deterministic shutdown persistence), [`REQUIREMENTS.md §3`](REQUIREMENTS.md) S-002 (no permanently force-loaded chunks).

---

Welcome to RTP. The diagrams are the map; the S-00x rules are the law; the ADRs are the precedent.

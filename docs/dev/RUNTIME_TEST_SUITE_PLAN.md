# Runtime Test Suite Plan (`rtp test …`)

> Status: Living document. Subcommands ship incrementally; this plan is
> amended each time one lands. No up-front promise is made about which
> subcommands are available — consult the **Shipped** / **Roadmap**
> sections below, which are the single source of truth.
>
> Intent of continuity: `rtp test full` (alias: `rtp test all`) is the
> umbrella entry point that always runs every subcommand currently shipped.
> Whenever a new subcommand is implemented, it is wired into `full` as part
> of the same change, and its row is moved from **Roadmap** to **Shipped**
> in this document.

## 1. Motivation

Not every server operator has access to the permission tools, load-generation
plugins, or profiling harnesses needed to validate RTP under real-world load.
The goal of `rtp test` is to give operators a **built-in, permission-gated,
server-side** way to exercise the critical code paths that are hardest to
reason about without running traffic through them:

* the teleport pipeline (`TeleportPipelineTask`) end-to-end,
* the async region queue (`ADR-006`),
* the `MemoryTracker` ticket lifecycle (REQ-RTP-S-002),
* the platform scheduler adapters (Folia region routing, Paper async chunks),
* and the global region verifiers (claim/protection addons — REQ-RTP-S-003).

These tests run against the **live server process**, so they must obey every
safety rule in `docs/dev/REQUIREMENTS.md §3`. Crucially:

* **S-004** — every failed iteration must produce a player-visible message or
  a `Level.WARNING` log entry. Stress tests may not swallow failures.
* **S-005** — all chunk access goes through the pipeline's existing async
  path; the test commands never call `world.getChunkAt()` directly.
* **S-002** — stress tests must not acquire their own chunk tickets; they
  reuse `TeleportPipelineTask`, which already releases tickets on every
  exit path.

## 2. Command Surface

All subcommands live under a single permission namespace so operators can
expose the suite to trusted staff without granting `rtp.reload` or friends.

| Command | Permission | Purpose |
|---|---|---|
| `rtp test` | `rtp.test` | Parent node; prints help if invoked bare. |
| `rtp test stress player:<name> [iterations:N] [intervalTicks:T] [region:R]` | `rtp.test` | Repeatedly teleport the listed player(s). |
| `rtp test queue region:<R> [iterations:N]` | `rtp.test` | Drain and refill the async location queue N times, recording per-candidate timings. |
| `rtp test safety region:<R> [samples:N]` | `rtp.test` | Sample N locations through `LocationGenerator` and assert each one passes `SafetyKeys.unsafeBlocks`. |
| `rtp test verifiers region:<R> [samples:N]` | `rtp.test` | Sample N candidates and run them through every registered `GlobalRegionVerifier` (sync and async) with timing breakdowns. |
| `rtp test memory` | `rtp.test` | Force a `MemoryTracker.runDiagnostics()` pass and report tracked-object counts per bucket. |
| `rtp test platform` | `rtp.test` | Print the active platform adapter, scheduler class, async-chunk implementation, and Folia region-ownership check result for the caller. |
| `rtp test full` / `rtp test all` | `rtp.test.full` | Runs every currently-shipped subcommand in sequence with safe defaults. Contents grow as roadmap entries ship. |
| `rtp test cancel [all]` | `rtp.test` / `rtp.test.admin` for `all` | Stops any in-flight `rtp test *` job. |
| `rtp test scheduler` | `rtp.test` | Probes each `RTPScheduler` tier and reports dispatch latency. |
| `rtp test reload-safety [iterations:N]` | `rtp.test.admin` | Runs `stress` concurrently with `rtp reload` to flush out ADR-009 races. |
| `rtp test commands` | `rtp.test` | Read-only audit of the whole command tree: declared name, permission, and lookup-key consistency. |
| `rtp test api-compat` | `rtp.test` | Read-only reflective probe of every Bukkit/Paper/Folia method RTP actually calls; surfaces `NoSuchMethodError` risk before a teleport is ever attempted. |
| `rtp test chunk-ticket` | `rtp.test` | Positive-path probe of the `MemoryTracker` release lifecycle that backs every chunk ticket (REQ-RTP-S-002). |
| `rtp test disconnect-midflight` | `rtp.test` | Synthetic-UUID probe of the disconnect-mid-teleport cleanup contract; verifies `processingPlayers`, `invulnerablePlayers`, `latestTeleportData`, and `nextTask.cancelled` are all cleared (REQ-RTP-S-002 leak canary). |

### Parameters

| Name | Type | Default | Notes |
|---|---|---|---|
| `player` | existing player name | sender (if player) | Multi-valued: `player:a,b,c` stress-tests several players in parallel. |
| `iterations` | integer ≥ 1 | `10` | Hard-capped at `1000` to avoid runaway jobs. |
| `intervalTicks` | integer ≥ 1 | `40` (2 s) | Lower bound enforced to prevent overlapping teleports. |
| `region` | region name | player's current region | Uses the same `RegionParameter` as `rtp scan`. |
| `samples` | integer ≥ 1 | `50` | Used by `queue`, `safety`, `verifiers`. |

### Output Contract

Every subcommand emits a structured report to the caller **and** to the
server log at `Level.INFO`. Failures log at `Level.WARNING` to satisfy
S-004. The report is a fixed, machine-parseable table:

    [RTP test/<sub>] ok=<n> fail=<n> avg=<ms> p95=<ms> max=<ms> notes=<…>

## 3. Shipped Subcommands

The implementations currently live in the repository. Each entry in this
section MUST link to its command class and, where present, its test class.

### 3.1 `rtp test stress` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestStressCmd`
(in `rtp-plugin` because it depends on `OnlinePlayerParameter`, which is
Bukkit-specific). Parent: `TestCmd` in the same package.

Validates the scheduling contract end-to-end by routing synthetic traffic
through the real command pipeline.

### Semantics

1. Resolve each `player:<name>` to an `RTPPlayer` via
   `RTP.serverAccessor.getPlayer(name)`. Unresolvable names emit a
   `MessagesKeys.badArg` message and are skipped (S-004).
2. Clamp `iterations` to `[1, 1000]` and `intervalTicks` to `[10, 6000]`.
3. For each iteration, re-invoke the parent `RTPCmd.compute(senderId, args,
   null)` with a synthetic `parameterValues` map containing the resolved
   player (and optional region). This intentionally reuses every guard the
   real command path enforces: cooldown, `processingPlayers`, economy,
   claim verifiers, queue drain, and safety check.
4. Schedule iterations with
   `RTP.scheduler.runTaskTimerAsynchronously(Runnable, 0L, intervalTicks)`
   and cancel the handle when `iterations` is reached or the caller leaves.
5. Aggregate per-iteration outcomes from
   `RTP.getInstance().latestTeleportData` and emit the final report.

### Threading Compliance

* The timer runs on the **async** scheduler — no main-thread chunk I/O.
* Each iteration dispatches through the normal pipeline, which is already
  audited for S-005 (async chunk fetch) and S-001 (safety check).
* On Folia, the pipeline internally routes entity teleports to the player's
  Entity Scheduler, so the stress command itself does not need to call
  `Bukkit.isOwnedByCurrentRegion`.

### Non-Goals

* No direct `TeleportPipelineTask` construction — delegation keeps the
  command honest and ensures stress results reflect real user traffic.
* No bypass of cooldown/economy. Operators who want a "cold" test disable
  those in `config.yml` before running; the command does not silently
  subvert production rules.

### 3.2 `rtp test full` / `rtp test all` — umbrella (always shipped)

The `full` subcommand (aliased as `all`) runs every currently-shipped
subcommand in sequence with conservative defaults (`iterations: 3`,
`samples: 20` once `samples`-taking subcommands land) and emits a single
consolidated report.

* **Invariant**: `full` never references a subcommand that is not yet in
  **Shipped**. When a new subcommand lands, the same change adds it to
  the `full` sequence.
* **Today it runs**: `commands` (read-only audit, runs first) then `api-compat` then `chunk-ticket` then `scheduler` then `stress` (iterations clamped to `3`,
  default interval, caller as the sole target). `cancel` is deliberately
  excluded (invoking it inside the sweep would abort the sweep itself),
  and `reload-safety` is excluded because it requires `rtp.test.admin`
  and intentionally courts failures that would swamp a default operator
  run. Operators who want those must invoke them directly.
* **Permission**: `rtp.test.full` — distinct from `rtp.test` so operators
  can grant individual subcommands without granting the aggregate sweep.

### 3.3 `rtp test cancel` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestCancelCmd`.
Backed by `ActiveTestJobs`, a process-wide registry of in-flight
`rtp test *` jobs. Without arguments, cancels jobs owned by the caller;
with the literal positional token `all`, cancels every active job
(requires `rtp.test.admin`). Motivated by the fact that a bad
`iterations:1000 intervalTicks:10` invocation previously had no in-band
stop switch short of a server restart.

Cancel semantics are cooperative: each registered job exposes a
canceller hook that flips a local `AtomicBoolean` *and* calls
`RTP.scheduler.cancelTask(handle)`, so an in-flight tick bails on the
next iteration instead of being forcibly interrupted mid-teleport. This
preserves S-002 — the canceller never orphans a chunk ticket, because
the pipeline still completes the teleport it started.

### 3.4 `rtp test scheduler` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestSchedulerCmd`.
Actively probes the async, primary/sync, and region tiers of
`RTPScheduler` by dispatching an empty `Runnable` to each and recording
the round-trip latency in microseconds. The entire probe runs on the
async scheduler (the caller's location is snapshotted on the calling
thread first) so the command handler never blocks waiting for
`CompletableFuture.get(...)` — S-005 is preserved on Paper/Spigot where
the command thread may be the main thread.

The region probe is skipped from the console (no location), with an INFO
log line rather than an error. Any tier that exceeds the 2-second
probe timeout is reported at WARNING per S-004 — a TIMEOUT on the region
tier is the headline signal of a Folia region thread stalled by
mis-routed work.

### 3.5 `rtp test reload-safety` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestReloadSafetyCmd`.
Interleaves `RTP.configs.reload()` calls on an async timer (period 20
ticks) with in-flight stress teleports, to flush out reload-vs-teleport
races in `ConfigCache` and the region/queue rebuild path (ADR-009
territory). Hard-capped to 20 iterations and gated behind
`rtp.test.admin` — this subcommand intentionally courts failures that
other subcommands avoid.

A `configs.reload()` returning `false` or throwing is recorded as a
failure and logged at WARNING (S-004). The command registers with
`ActiveTestJobs`, so `rtp test cancel` can abort a misfiring run.

### 3.6 `rtp test commands` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestCommandsCmd`.
Read-only audit of the full RTP command tree. Walks `parent()` up to the
root (typically `RTPCmdBukkit`), then recurses via
`TreeCommand.getCommandLookup()` and reports, for every node reached:

* **missing-name** — `name()` is null or blank.
* **missing-perm** — `permission()` is null or blank (operator surprise;
  effectively an ungated command).
* **key-name-mismatch** — the lookup key does not equal the child's own
  `name()` (case-insensitive). `TreeCommand.addSubCommand` upper-cases the
  key at insertion, so a node installed with a lower-cased key is only
  reachable by alias. One intentional alias (`ALL` → `TestFullCmd`) is
  whitelisted in the walker.
* **null-children** — a lookup entry whose value is null.
* **traversal-errors** — any throwable raised while reading a node's
  `getCommandLookup()` or metadata accessors, captured per-node so one
  bad subtree cannot halt the audit.

The audit deliberately **does not dispatch** the commands it finds. Real
dispatch-level checks ("does this command actually send a message? does it
honor its permission in-band?") require a live `RTPPlayer` and a live
platform scheduler; those remain runtime-only and will grow into the
`commands-live` / `feedback` / `args` roadmap entries below. Keeping
`commands` read-only makes it safe to include in the `full` sweep even
on a production server.

The `test` subtree is skipped at depth > 0 so the audit does not recurse
into its own siblings (which would re-invoke `stress`, `reload-safety`,
etc. if dispatch were ever enabled here).

Clean audit logs at INFO; any finding logs at WARNING per S-004 and is
also sent to the caller. Unit-tested via
`TestCommandsCmdAuditTest` (7 tests) covering clean trees, each finding
category, the `test`-subtree skip, `findRoot` climbing, and cycle
termination.

## 4. Roadmap for Remaining Subcommands

### `rtp test commands-live` (dispatch audit)
Live-server extension of `commands` (§3.6) that actually invokes every
audited leaf through the real dispatcher with an empty argument map,
asserting each produces at least one `sendMessage` call and does not
allow a throwable to escape. Also flips the caller's simulated
permission off and asserts the invocation is refused with a visible
message, not silence. Requires a synthetic sender harness that captures
message output and thread context; unit tests cannot cover this.

### `rtp test feedback`
Renders every `MessagesKeys` / `ConfigCache.*` string through the active
message pipeline (placeholders + color codes + PlaceholderAPI if
present) and asserts no `%placeholder%` survives un-substituted, no
`&`-style code survives un-translated on platforms that use `§`, and
all keys resolve to non-empty strings. Catches config renames that
left the yml stale.

### `rtp test args`
Feeds each `CommandParameter` subclass (`OnlinePlayerParameter`,
`RegionParameter`, `WorldParameter`, `BiomeParameter`, …) a matrix of
inputs (happy, missing, malformed, offline target, whitespace, long,
unicode) and asserts each returns a consistent failure with a
player-visible message (S-004), not an NPE or silence.

### `rtp test tab-complete`
Invokes `onTabComplete` on every leaf command with every prefix length
from 0 to the longest valid suggestion. Asserts no synchronous chunk
I/O fires (S-005), latency stays under ~50 ms, and suggestions do not
leak entities (e.g. vanished players) the sender cannot see. Threading
profile is genuinely different from execute path (main thread on
Paper/Spigot, region thread on Folia).

### `rtp test async-reply`
For every subcommand that schedules async work and then replies,
asserts the `RTPPlayer.sendMessage` call lands on a thread where the
platform accepts it (on Folia: `Bukkit.isOwnedByCurrentRegion(player)`
must be true). Wraps the synthetic player to capture
`Thread.currentThread()` at each send.

### `rtp test cooldown`
Drives a synthetic player through `rtp` → immediate re-`rtp` → wait →
re-`rtp` and asserts the cooldown message fires (S-004), no duplicate
enqueue occurs, `processingPlayers` is released on every exit path
(including disconnect mid-teleport), and economy refund fires exactly
once on failure and never on cancel.

### `rtp test i18n`
Enumerates every `ConfigCache.*` / `MessagesKeys.*` read at runtime and
flags any that resolves to a default/placeholder value. Catches
renames that left a key stale after a reload.

<!--
Roadmap stub for `rtp test disconnect-midflight` removed on promotion to
Shipped; see §3.9 below.
-->

### `rtp test economy`
If Vault is loaded: debit a synthetic player, force an unsafe region,
assert the teleport fails and the refund fires exactly once. If Vault
is absent: assert the economy path gracefully no-ops rather than
raising `NoClassDefFoundError`.

### `rtp test placeholderapi`
If PAPI is loaded: resolve every RTP-registered placeholder against a
synthetic player and assert non-null, non-exception. If PAPI is
absent: assert the RTP placeholder expansion gracefully no-ops.

### `rtp test folia-ownership`
On Folia only, from the caller's current location, asserts
`Bukkit.isOwnedByCurrentRegion` holds where our code assumes it (entity
scheduler callbacks, region-bound teleport finalization). Pure no-op
on Spigot/Paper. Regression canary for REQ-RTP-S-005 / ADR-004.

### 3.7 `rtp test api-compat` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestApiCompatCmd`.

Replaces the earlier `version-compat` proposal. Matching NMS package
suffixes (e.g. `v1_20_R1` vs `v26_1_R1`) is a brittle signal: RTP
deliberately targets the **Bukkit/Paper/Folia API surface** rather than
NMS internals, so a Minecraft release that ships a new NMS version number
while keeping the API stable should be considered compatible. Conversely,
an API method that quietly disappears in a point release will not be
detected by a package-name match but **will** manifest as a
`NoSuchMethodError` at first teleport — which is exactly the failure mode
this subcommand is designed to pre-empt.

#### Semantics

1. Holds a curated, versioned list of `ApiProbe` entries — one per
   (declaring class, method name, parameter signature) tuple that RTP
   actually calls at runtime (e.g. `org.bukkit.World#getChunkAtAsync(int,int)`,
   `org.bukkit.Bukkit#isOwnedByCurrentRegion(org.bukkit.Location)` on Folia,
   `org.bukkit.entity.Player#teleportAsync(org.bukkit.Location)` on Paper).
2. For each probe, reflectively resolves the declaring class (skipping
   probes whose class is not on the classpath — e.g. Folia-only probes on
   Spigot) and then `Class#getMethod(...)` / `getDeclaredMethod(...)` the
   listed signature.
3. Reports `ok` / `missing-class` / `missing-method` counts and logs every
   miss at `Level.WARNING` per S-004. Does **not** invoke any resolved
   method — purely a resolution probe.
4. Read-only and side-effect-free; safe to include in `rtp test full`.

#### Threading

Runs entirely on the calling thread. `Class.forName` and
`Class#getMethod` do no I/O and do no chunk loading, so S-005 is not
implicated.

#### Why not NMS-package matching

The previous `version-compat` proposal matched `getClass().getPackage()`
against an expected `v1_20_R1`-style suffix. That approach rejects
API-compatible Minecraft releases with new NMS numbering (e.g. a
hypothetical 27.1 that keeps the Bukkit API stable but renumbers NMS)
and accepts API-incompatible releases that happen to land in a package
whose name matches. An API-surface probe is strictly more informative:
it reports exactly the methods whose absence would break RTP, regardless
of package nomenclature.

### 3.8 `rtp test chunk-ticket` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestChunkTicketCmd`.

Positive-path probe of the `MemoryTracker` release lifecycle, which is
the registry backing every chunk ticket RTP acquires (REQ-RTP-S-002).
The existing watchdog tests (in `rtp-core`) exercise the *negative*
path — `runDiagnostics()` force-closing a leaked `TeleportPipelineTask`.
The happy path — an entry released via explicit `untrack` call is
actually dropped, and `runDiagnostics()` does NOT over-remove
non-leaking entries — was previously unverified at runtime. A silent
regression there would let tickets accumulate for a full reservation
window before the watchdog reacted, which is exactly the class of leak
REQ-RTP-S-002 forbids.

#### Semantics

Registers sentinel `Object`s under a dedicated label
(`rtp-test-chunk-ticket-sentinel`) so assertions stay isolated from
any concurrent live teleport activity on the server. Exercises every
release path the tracker currently exposes:

1. `untrack(UUID)` — mirrors `TeleportPipelineTask.runCleanup()`.
2. `untrack(Object)` — mirrors `RTPRunnable.untrackHook` /
   `TrackedRTPTask` release.
3. `runDiagnostics()` against a live, non-leaking entry — must be a
   no-op (regression guard against over-aggressive diagnostics).

Asserts that the label-scoped residual count returns to zero after the
sequence. Any non-zero residual logs at WARNING and is surfaced to the
caller (S-004).

#### Threading & Safety

No chunk I/O, no scheduler dispatch, no real ticket acquisition — every
step is an in-memory registry call. Safe on the caller's thread,
including the main thread (S-005 not implicated). Because sentinels are
plain `Object`s rather than real `TeleportPipelineTask` instances, a
bug in the probe itself cannot leak a chunk ticket (S-002 self-safety).

Unit-tested via `TestChunkTicketCmdTest` (3 tests): clean-tracker pass,
label isolation from unrelated registry traffic, and repeat-invocation
idempotence.

#### Why not exercise the full teleport pipeline?

An earlier draft proposed allocating N real reservations via
`TeleportPipelineTask`. That approach entangles the probe with the
live world, cooldowns, the queue, claim verifiers, and the economy
layer, so a teleport-layer regression would mask a tracker-layer
regression. The sentinel approach isolates the lifecycle contract
tests want to verify. The sibling `rtp test disconnect-midflight`
(§3.9) covers the disconnect-cleanup release path; the full
teleport-pipeline release path remains intentionally uncovered (see
§3.9's "Why not drive PlayerQuitEvent" note).

### 3.9 `rtp test disconnect-midflight` — shipped

Class: `io.github.dailystruggle.rtp.bukkit.commands.test.TestDisconnectMidflightCmd`.

Synthetic-UUID probe of the disconnect-mid-teleport cleanup contract.
The production cleanup path lives in `OnPlayerQuit#onPlayerQuit`, which
mutates three separate singleton collections
(`processingPlayers`, `invulnerablePlayers`, `latestTeleportData`) and
delegates to `RTPTeleportCancel`. `rtp-core` unit tests exercise
`RTPTeleportCancel` in isolation; they do not verify that the
plugin-level quit listener correctly orchestrates all three collections
in the order the pipeline assumes. A silent regression in that ordering
— clearing `processingPlayers` before cancelling `nextTask`, or
skipping `invulnerablePlayers` entirely — would leave a player wedged
with no way to issue `/rtp` again until a server restart, exactly the
"silently discarded" failure REQ-RTP-S-004 forbids.

#### Semantics

1. Allocates a synthetic UUID guaranteed not to collide with live state
   (checked against all four collections before use).
2. Stages that UUID into `processingPlayers`, `invulnerablePlayers`, and
   `latestTeleportData` with a plain `RTPRunnable` as `nextTask` — this
   mirrors the state the pipeline holds for a player between
   `RTPCmd.onCommand` and `TeleportPipelineTask.runTeleport`'s
   `whenComplete` callback.
3. Reproduces the exact cleanup sequence `OnPlayerQuit` performs:
   remove from `invulnerablePlayers` → remove from `processingPlayers`
   → `nextTask.setCancelled(true)` → invoke `RTPTeleportCancel`.
4. Asserts all three collections are cleared for the synthetic UUID and
   that `nextTask.isCancelled()` returns `true`. Any deviation logs at
   `Level.WARNING` (S-004) and is surfaced to the caller.

#### Why not drive `PlayerQuitEvent`

An earlier draft proposed firing a real `PlayerQuitEvent` through the
Bukkit event bus. That would require a live server, depend on listener
priority ordering we do not control, and mask tracker-layer regressions
with event-dispatch regressions. Reproducing the cleanup sequence
directly against a synthetic UUID keeps the probe's failure mode
unambiguous: if it fails, the cleanup contract itself is broken, not
the event plumbing.

#### Lock-step contract

The cleanup sequence this probe reproduces must stay faithful to
`OnPlayerQuit` and `RTPTeleportCancel.refund`. If either is refactored
(e.g. a new collection is added to the cleanup), both the probe's
inline sequence and its `fakeCancelMirror` helper must be updated in
the same change. A stale mirror would silently pass while production
leaked state — exactly the regression class this test exists to
prevent.

Unit-tested via `TestDisconnectMidflightCmdTest` (4 tests): clean-state
pass, probe self-containment (no residual state), collision-avoidance
against pre-seeded UUIDs, and idempotence across repeated invocations.

### `rtp test queue`
Calls `region.getContent()` (or equivalent) until the queue is drained,
records the wall-clock per candidate, then waits for `ADR-006`'s
pre-generator to refill. Repeats `iterations` times. Surfaces regressions
in the Archimedean spiral (`CONCEPTS.md`) or queue starvation.

### `rtp test safety`
Generates `samples` candidates directly through `LocationGenerator` and
asserts `chunk.isSafe(...)` against `SafetyKeys.unsafeBlocks`. Useful when
operators change `unsafeBlocks` at runtime; flags any candidate that would
have been delivered despite being unsafe (REQ-RTP-S-001 regression test).

### `rtp test verifiers`
For each registered entry in `GlobalRegionVerifiers`, runs `samples`
candidates through the verifier and reports pass/fail/timeout counts with
per-verifier latency. Directly exercises REQ-RTP-S-003 and helps diagnose
slow claim-plugin integrations.

### `rtp test memory`
Invokes `MemoryTracker.runDiagnostics()` on demand, reports counts of
live `TeleportData`, `TeleportPipelineTask`, and chunk-ticket entries, and
flags any object exceeding its reservation window (REQ-RTP-S-002).

### `rtp test platform`
Prints `RTP.scheduler.getClass().getName()`, the resolved
`RTPServerAccessor` implementation, and — on Folia — the result of
`Bukkit.isOwnedByCurrentRegion(...)` for the caller's location, so
operators can confirm the right adapter is loaded.

### `rtp test full` / `rtp test all`
Already shipped as an umbrella (see §3.2). Its behaviour grows as each
roadmap entry below is promoted to **Shipped**. The target order once all
entries land is `platform` → `memory` → `safety` → `verifiers` → `queue`
→ `scheduler` → `stress` → `reload-safety`, with `cancel` available at
any time as an out-of-band interrupt.

(`cancel`, `scheduler`, and `reload-safety` have moved to **Shipped** —
see §3.3, §3.4, §3.5 respectively.)

## 5. Requirements Traceability

| Subcommand | Exercises |
|---|---|
| `stress` | REQ-RTP-S-001, S-002, S-004, S-005; REQ-RTP-SYS-001 |
| `queue` | ADR-006, ADR-009 |
| `safety` | REQ-RTP-S-001 |
| `verifiers` | REQ-RTP-S-003 |
| `memory` | REQ-RTP-S-002 |
| `platform` | REQ-RTP-SYS-001; Folia threading contract |
| `scheduler` | REQ-RTP-SYS-001; Folia region/entity scheduler contracts |
| `reload-safety` | ADR-009; REQ-RTP-S-004 |
| `cancel` | Operator safety; no specific REQ |
| `commands` | REQ-RTP-S-004 (operator-visible audit of the command tree contracts) |
| `api-compat` | REQ-RTP-SYS-001; operator-visible surfacing of `NoSuchMethodError` risk |
| `chunk-ticket` | REQ-RTP-S-002 (positive-path) |
| `disconnect-midflight` | REQ-RTP-S-002 (leak canary), REQ-RTP-S-004 |
| `full` / `all` | All currently-shipped subcommands |

Once each subcommand ships, add the corresponding row to
`docs/dev/TRACEABILITY.md` with a pointer to its test class.

## 6. Out of Scope

* **Fabric.** `rtp-fabric` has an open S-005 violation and a `null`
  `LocationGenerator` stub (see `AGENTS.md → Current Development Focus`).
  The stress command will refuse to run on Fabric until those two blockers
  are resolved, to avoid producing misleading results.
* **Benchmarking.** This suite surfaces correctness and stability, not
  micro-benchmark numbers. JMH / `PerformanceTracker` remain the right
  tools for latency histograms.

# ADR-015 — Stale-Chunk Guard for Count-Bound Pipes

**Status:** Accepted
**Date:** 2026-04-18

## Context

RTP strictly forbids the permanent force-loading of chunks on Folia (see ADR-004
"Count-Bound Task Pipe on Folia" and REQ-RTP-S-002). Once
`RTPWorld.getChunkAtAsync(cx, cz)` resolves, the subsequent block-evaluation
task (`chunk.isSafe(...)`, `vert.adjust(chunk)`, etc.) is enqueued onto a
Count-Bound task pipe backed by the Folia Region Thread scheduler. The chunk is
kept alive only by the native server's own lifetime tracking of the
just-loaded chunk and by the transient `ChunkSet` reference on the heap — it is
**not** pinned by an RTP-held ticket.

When that Count-Bound pipe is backlogged — e.g. during an `/rtp scan` burst,
the cache replenishment pulse, or a player-queue fan-out — the block-evaluation
task can sit in the queue for multiple seconds. During that window, Folia's
native chunk garbage collector is free to unload the chunk. When the task
finally runs and calls `chunk.isSafe(...)`, `world.getChunkAt(...)`, or any
other read against an unloaded chunk, Folia satisfies the read by triggering a
**synchronous** chunk load on the current Region Thread. A synchronous chunk
load on a tick thread is a direct REQ-RTP-S-005 violation and reliably stalls
the Folia Watchdog, crashing the server.

This race is invisible to single-threaded testing and to low-traffic servers,
but manifests quickly on busy Folia deployments. Addressing it with a chunk
ticket or `setForceLoaded(true)` is not an option on Folia — it would either
violate REQ-RTP-S-002 or require a Global Region Scheduler detour that
destroys the throughput benefit of Count-Bound pipes.

The same race exists, with different mechanics, on Paper chunk-system-v2 (MC
26.1+): `World#getChunkAtAsync(cx, cz)` returns a `Chunk` reference **without**
a plugin ticket, and the chunk is eligible for immediate native GC on the
scheduler thread that resolved the future. A guard that queries
`world.isChunkLoaded(cx, cz)` on the server's default TTL therefore reports
`false` on every candidate, rejecting loads that RTP itself just performed.
The guard must test RTP-owned liveness — a bounded `ChunkReservation` held
across the block-evaluation section — not server-default TTL.

A bounded `ChunkReservation` alone is still insufficient on Paper chunk-system-v2.
The raw `addPluginChunkTicket` call is main-thread-only on Bukkit/Paper, and
`BukkitRTPWorld.setForceLoadedImpl` therefore schedules the call via
`Bukkit.getScheduler().runTask(...)` when invoked off-thread. The location
generator runs on an async scheduler thread (`Craft Scheduler Thread - * - RTP`);
`new ChunkReservation(...)` returns immediately, before the scheduled
`addPluginChunkTicket` has actually executed on the primary thread. The
stale-chunk guard running on the same async thread therefore still sees
`isChunkLoaded=false` and rejects the candidate. The reservation must expose
a confirmation future that the caller awaits on its async thread before the
guard reads `isChunkLoaded`; the platform adapter completes that future only
from inside the scheduled `runTask` (or `GlobalRegionScheduler.run`) lambda
that applies the ticket.

The async confirmation must be composed through `CompletableFuture`, not a
bounded `.get()` on an async worker. A bounded block (`awaitReady(2s)` on a
worker thread) serialises the generator's attempt loop through the async pool
and, under cache replenishment pressure, starves unrelated
`RTP.serverAccessor.getScheduler().runTaskAsynchronously` work. The generator
releases the worker between each I/O-bearing stage
(`getChunkAtAsync` → `readyFuture` → `ticket.chunks().get(0)` → neighbour
`allOf` → `checkGlobalRegionVerifiers`) via a state machine driven by
`thenCompose` / `whenComplete`, and the synchronous rejection paths (biome,
worldborder, stale-guard trip) loop in a local trampoline without a new
scheduler dispatch so the exhaust path (e.g. 20 000 biome mismatches for an
impossible-biome request) completes in milliseconds without stack growth.

## Decision

We introduce a **Stale-Chunk Guard** contract on `RTPWorld` and call it
immediately before any block-evaluation work that runs in a
Count-Bound-scheduled callback:

1. `RTPWorld` gains a public method `boolean isChunkLoaded(int cx, int cz)`.
   The method is a pure, non-blocking status query — implementations **must
   not** trigger a chunk load or dispatch to a Region Thread. It returns the
   native `World#isChunkLoaded(int, int)` value on Bukkit-family adapters (this
   API is documented as a thread-safe state lookup), and `true` by default on
   adapters that have not yet implemented the contract (preserving legacy
   behavior).

2. Before every `chunk.isSafe(...)`, `vert.adjust(chunk)`, or other block-state
   read dispatched from a Count-Bound callback, the caller must verify
   `world.isChunkLoaded(cx, cz)`. If it returns `false`, the candidate is
   rejected through the existing "unsafe" path — no silent discards, an
   existing WARN log is emitted on retry-budget exhaustion (REQ-RTP-S-004).

3. On the Folia Region-Thread dispatch site specifically
   (`FoliaLocationGenerator`), a bounded retry is performed: if the guard
   trips, the generator re-queues an async `getChunkAtAsync(cx, cz)` call up
   to `SafetyKeys.staleChunkRetryLimit` times (default `2`), bouncing back
   through the async pool rather than the Region Thread. On exhaustion the
   candidate is rejected and the spiral advances. The retry limit is
   configurable in `safety.yml` because certain server forks (Purpur,
   Pufferfish) run hyper-aggressive chunk eviction.

4. On the `LocationGenerator` internal block-sampling paths (the two
   `safetyCheck` loops, and the pre-`vert.adjust` site in the
   `getLocation(Region, Set<String>)` overload), the guard is bracketed by a
   `ChunkReservation` allocated immediately after the async/probe path
   resolves a chunk and released in a `finally` on all exit paths
   (`continue`, `break`, exception). The guard therefore rejects only when
   the server reports the chunk unloaded **despite** a live RTP ticket — a
   genuine GC race — and never falsely classifies an async-load-returned
   chunk as stale on Paper chunk-system-v2. On rejection the outer
   spiral/poll loop picks a fresh candidate on the next iteration.

5. `RTPWorld.setForceLoadedImpl` returns a `CompletableFuture<Void>` that
   completes only after the raw `addPluginChunkTicket` /
   `removePluginChunkTicket` call has executed on the appropriate scheduler
   (primary thread on Bukkit/Paper; Global Region Scheduler on Folia). The
   public `RTPWorld.setForceLoaded` propagates this future, and
   ref-counted no-op invocations (count > 0) return the in-flight apply
   future so a second caller cannot bypass the ticket-application wait by
   incrementing past a still-pending first application. `ChunkReservation`
   captures this future on construction and exposes
   `readyFuture()` / `awaitReady(timeout, unit)`; the `LocationGenerator`
   state machine composes the guard via
   `reservation.readyFuture().orTimeout(2, SECONDS).whenComplete(...)`
   rather than a bounded `.get()` on the async worker. A timeout attributes
   to `FailTypes.timeout / reason=ticketApplyTimeout` and the candidate is
   rejected (REQ-RTP-S-004). The non-blocking composition preserves
   REQ-RTP-S-005 compliance by never occupying an async worker for the
   duration of the platform-scheduled apply, and eliminates the head-of-line
   blocking of unrelated scheduler work that a bounded wait incurs. The
   legacy `awaitReady` accessor is retained on `ChunkReservation` as a
   convenience for test fixtures and the deprecated sync shims on
   `LocationGenerator`.

6. The `LocationGenerator` pregen path (`getLocation(Region, Set<String>)`)
   and the queue path (`getLocation(Region, sender, player, biomeNames)`)
   are non-blocking state machines (`PregenTask` and `QueueTask` in
   `rtp-core.../selection/region`). Each I/O-bearing stage is composed via
   `CompletableFuture`; the worker is released between stages. Synchronous
   rejection paths (biome, worldborder, stale guard, vert.adjust returning
   `null`) re-enter `runAttempt` through a per-task trampoline — a
   `volatile boolean inRunAttempt` flag toggled around the top of the
   state-machine loop — so that a CF callback which resolves on the current
   thread (the common case when the platform adapter returns a completed
   future) does not grow the stack or schedule a redundant scheduler hop.
   When a CF resolves on a different thread after the loop has returned,
   the callback invokes `PregenTask.run()` on that thread, starting a fresh
   iteration. The deprecated static `LocationGenerator.getLocation(...)`
   methods are sync shims that `.get(60s)` on the async future for
   backward compatibility with the test suite and the
   `ENQUEUE_TRACE LocationGenerator taking UNQUEUED fast-path` internal
   caller; `FoliaLocationGenerator` collapses to an inheritance-only
   subclass of the unified core generator, which routes its scheduler
   calls through `RTP.serverAccessor.getScheduler()` (Folia's adapter
   implementation picks up `runTaskAsynchronously` without a separate
   state machine).

## Consequences

- **Positive:**
  - Eliminates the "Stale Chunk Trap" class of Watchdog crashes on busy Folia
    servers without introducing any force-load ticket.
  - The guard is O(1) on Paper/Folia (a pure `World#isChunkLoaded` call) and is
    a no-op on adapters that haven't opted in, so platforms without the race
    pay effectively zero overhead.
  - Makes the Count-Bound-pipe contract (ADR-004) complete: a callback
    scheduled on a Count-Bound pipe can now safely assume either "chunk is
    loaded" or "candidate rejected" — never "chunk is unloaded and we will
    force-load it".

- **Negative / Trade-offs:**
  - Adds a new method to the `RTPWorld` interface (with a safe default so
    addons and legacy adapters keep compiling).
  - Candidates rejected by the guard waste the async chunk load that fed them;
    under extreme GC churn the retry budget can push additional async loads.
    Tuning lives in `SafetyKeys.staleChunkRetryLimit`.
  - A future contributor reading only the `.thenAccept` block might see the
    `isChunkLoaded` check as redundant ("we just loaded this chunk three lines
    ago"). This ADR exists to prevent that optimization from being reverted.

## References

- REQ-RTP-S-002 (chunk reservation lifecycle) — `docs/dev/REQUIREMENTS.md section 3`.
- REQ-RTP-S-004 (no silent teleport failures) — `docs/dev/REQUIREMENTS.md section 3`.
- REQ-RTP-S-005 (no main-thread chunk loading) — `docs/dev/REQUIREMENTS.md section 3`.
- ADR-004 "Count-Bound Task Pipe on Folia".
- ADR-012 "Chunk Reservation Abstraction".
- Implementation:
  - `rtp-api`: `RTPWorld.isChunkLoaded(int, int)`; `RTPWorld.setForceLoaded`
    and the abstract `RTPWorld.setForceLoadedImpl` return
    `CompletableFuture<Void>`; `RTPWorld.ticketApplyFutures` tracks the
    in-flight apply future per chunk key so ref-counted no-op callers
    receive the same future that resolves the initial apply.
  - `rtp-api`: `ChunkReservation` captures the apply future on construction
    and exposes `readyFuture()` / `awaitReady(long, TimeUnit)`.
  - `rtp-core`: `LocationGenerator` / `PregenState` / `PregenTask` /
    `QueueTask` — unified non-blocking state machine. Stale-chunk guards at
    the two `safetyCheck` entry points and pre-`vert.adjust`, with
    `reservation.readyFuture().orTimeout(2, SECONDS).whenComplete(...)`
    bracketing the pre-`vert.adjust` guard on the pregen path; timeout
    attributes to `FailTypes.timeout / reason=ticketApplyTimeout`. The
    per-task trampoline (`volatile boolean inRunAttempt`) prevents
    stack growth on synchronous rejection chains.
  - `rtp-folia`: `FoliaLocationGenerator` — inheritance-only subclass of
    `LocationGenerator`; scheduler routing is provided by the Folia
    platform adapter's `RTPScheduler` implementation.
  - `rtp-bukkit` / `rtp-folia`: `BukkitRTPWorld.isChunkLoaded` and
    `FoliaRTPWorld.isChunkLoaded` delegate to native
    `World#isChunkLoaded(int, int)`; `BukkitRTPWorld.setForceLoadedImpl`
    completes the apply future from inside the `Bukkit.getScheduler().runTask`
    lambda (primary-thread path completes immediately);
    `FoliaRTPWorld.setForceLoadedImpl` completes inside the Global Region
    Scheduler lambda.
- Tests:
  - `rtp-core` `ReqRtpS005StaleChunkGuardTest` — simulates Folia native
    GC via `MockRTPWorld.isChunkLoadedPredicate` and asserts that
    `MockRTPChunk.isSafe` is never invoked when the guard trips.
  - `rtp-core` `ReqRtpS005PaperStaleGuardFalsePositiveTest` — verifies that
    a `ChunkReservation` with a synchronous apply pins the chunk across the
    guard check (pinning-semantics baseline).
  - `rtp-core` `ReqRtpS005PaperTicketApplicationRaceTest` — installs a
    deferred-apply mock world whose `setForceLoadedImpl` returns an
    incomplete future drained by a background "primary-thread simulator";
    asserts that `reservation.awaitReady(...)` bridges the race and that
    `MockRTPChunk.isSafe` runs only after the deferred apply has fired.
- Configuration: `safety.yml` `staleChunkRetryLimit` (default `2`).

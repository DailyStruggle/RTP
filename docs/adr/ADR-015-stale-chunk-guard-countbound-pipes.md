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
   `getLocation(Region, Set<String>)` overload), the guard simply rejects the
   candidate without a retry — the outer spiral/poll loop will naturally
   pick a fresh candidate (and load its chunk async) on the next iteration.

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

- REQ-RTP-S-002 (chunk reservation lifecycle) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-004 (no silent teleport failures) — `docs/dev/REQUIREMENTS.md §3`.
- REQ-RTP-S-005 (no main-thread chunk loading) — `docs/dev/REQUIREMENTS.md §3`.
- ADR-004 "Count-Bound Task Pipe on Folia".
- ADR-012 "Chunk Reservation Abstraction".
- Implementation:
  - `rtp-api`: `RTPWorld.isChunkLoaded(int, int)`.
  - `rtp-core`: `LocationGenerator.getLocation(...)` — stale-chunk guards at the
    two `safetyCheck` entry points and pre-`vert.adjust`.
  - `rtp-folia`: `FoliaLocationGenerator.LocationSearchTask` Region-Thread
    callback — bounded re-queue via `SafetyKeys.staleChunkRetryLimit`.
  - `rtp-spigot` / `rtp-folia`: `BukkitRTPWorld.isChunkLoaded` and
    `FoliaRTPWorld.isChunkLoaded` delegate to native
    `World#isChunkLoaded(int, int)`.
- Test: `rtp-core` `ReqRtpS005StaleChunkGuardTest` — simulates Folia native
  GC via `MockRTPWorld.isChunkLoadedPredicate` and asserts that
  `MockRTPChunk.isSafe` is never invoked when the guard trips.
- Configuration: `safety.yml` `staleChunkRetryLimit` (default `2`).

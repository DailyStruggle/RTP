 d# ADR-112 - Per-Region Work Division on Folia

**Status:** Accepted
**Date:** 2026-10-14

## Context

Under a many-player `/rtp` stress run on Folia, one region dropped to about 1.4 TPS (700 ms ticks) while the mean across regions stayed at 18.9-19.0. RTP's own work was concatenated onto that region instead of divided across region threads:

1. **Chunk-future continuations ran on the completing region thread.** Folia completes `World.getChunkAtAsync` on the region that owns the chunk. RTP chains candidate evaluation, retries, claim checks, neighbour-grid orchestration and teleport setup on those futures without an executor, so all of it ran unbudgeted on whichever region finished the load.
2. **Inline execution had no limit.** `FoliaSchedulerImpl.runTask(world, cx, cz)` ran a task inline whenever the caller already owned the region. Chains of continuations that kept landing in one region therefore ran back to back in one tick.
3. **Folia's region scheduler runs everything queued for a region in its next tick.** The ADR-087 permit pool bounded tasks in flight server-wide, but not per region: a hot region could absorb every permit and every task in one tick.
4. **The tick-health gate was server-wide.** A single slow region either hid in the mean or, read as the worst region, paused cache filling for the whole server.
5. **Spatial clustering amplifies all of the above.** In a small region, kept chunks and their neighbour grids sit close enough for Folia to merge them into one ticking region, so every player's work lands on the same thread.

Count-bound pipes (ADR-004) were also concatenating: each `execute` ran up to its count inline on the draining thread, and its time check before the first task meant a 1 ns progress floor usually ran nothing.

## Decision

Divide RTP's region-thread work per region and bound each region's share, implementing ADR-087 section 3.4 (coalesce per region, interleave across regions) and section 3.6 (regional circuit breaker).

1. **Chunk futures complete off the region thread.** `FoliaRTPWorld` hands every RTP chunk future's completion to Folia's async scheduler after the region thread has cached the chunk. Continuations that read live blocks re-enter through `RTPScheduler.runTask`. Live safety scans in `PregenTask` dispatch to the centre chunk's region explicitly, and `TeleportPipelineTask` continues generation results off a region thread.
2. **Per-region FIFO cells with one slice in flight.** Each one-shot regional task joins the FIFO of its cell (`2^3` chunks square, below Folia's default 16-chunk section so one cell is owned by one region). One slice per cell holds one permit from the ADR-087 pool and runs on the owning region thread.
3. **Per-region-thread budget.** A slice runs tasks until that thread's `syncAllottedTime` budget is spent, always at least one, then re-queues behind every other waiting cell. The budget is a per-thread token bucket holding at most one budget per 50 ms, so a slow region earns no catch-up allowance. Nested spans are charged once.
4. **Inline only within budget and FIFO.** A caller that owns the region runs inline only while its thread has budget and the cell has nothing queued; otherwise the task waits for the next slice. Interactive requests are charged but never deferred by the budget.
5. **Regional circuit breaker.** A slice dispatched more than 100 ms late, or whose region is unhealthy by `minTPS` / `maxMSPT` (per-region readings from `MetricsBinding.regionTps1m` / `regionMspt`), runs one task. `RTPScheduler.isRegionSaturated(world, cx, cz)` reports such regions and cells with 64 or more queued tasks; cold-to-hot promotion leaves candidates in those regions cold and takes the next candidate.
6. **Server-wide gate reads the mean on Folia.** `ServerHealthGate.shouldPause()` uses the mean TPS across regions when ticking is regional and skips its MSPT half there (Folia samples tick intervals, not work); hot spots are handled per region by `ServerHealthGate.regionUnhealthy`.
7. **Count-bound pipes are count- and time-bound.** A positive budget always runs one task; a budget of zero or less runs none; inline work stops once the budget is spent on the draining thread. The cache pipe cap rises from 2 to 8 because `asyncAllottedTime` now bounds it.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep the server-wide pause driven by the worst region | One slow region (often not caused by RTP) stops cache filling everywhere; it does not move work away from the slow region. |
| Mean-only gate | Hides the 1.4 TPS region entirely. |
| Per-cell budget instead of per-thread | Merged regions span many cells, so a large region would receive many budgets per tick. |
| Wall-clock refill without a cap | A 700 ms region tick would earn fourteen budgets, giving RTP more time on exactly the region that can least afford it. |
| Move all evaluation to the async pool, including block reads | Live block reads off the owning region thread violate Folia's threading contract (ADR-087 alternatives). |

## Consequences

- **Positive:**
  - RTP adds at most about one `syncAllottedTime` to any region tick, however many players' work targets that region.
  - Work for other regions keeps progressing while one region is slow; background candidates move to healthy regions.
  - Selection retries, claim checks and teleport setup run on async workers, not tick threads.
- **Negative / Trade-offs:**
  - Live checks and teleport stages that used to run inline may wait one region tick under load.
  - Background refill near a slow region slows down; candidates there stay cold until it recovers.
  - Cells assume Folia's grid exponent is at least 3; a smaller exponent is detected at run time and the stray task is dispatched to its own region without the limiter.

## References

- [ADR-004](ADR-004-countbound-taskpipe-on-folia.md): CountBoundTaskPipe Instead of TimeBoundTaskPipe on Folia Regional Threads (amended by this ADR)
- [ADR-054](ADR-054-rtprunnable-self-scheduling-thread-routing.md): RTPRunnable Self-Scheduling Thread Routing
- [ADR-087](ADR-087-adaptive-tick-budget-and-memory-backpressure-for-folia.md): Adaptive Tick-Budget, Memory Backpressure, and Dynamic Workload Regulation for Folia Execution (sections 3.4 and 3.6 implemented here)
- [`PERFORMANCE.md`](../admin/configuration/PERFORMANCE.md)

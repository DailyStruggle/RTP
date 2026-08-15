# ADR-004 — CountBoundTaskPipe Instead of TimeBoundTaskPipe on Folia Regional Threads

**Status:** Accepted
**Date:** 2026-04-15

## Context

RTP's queue replenishment pipeline uses a bounded task pipe to limit how much work is done per scheduling cycle, preventing the plugin from monopolising server CPU. Two bounding strategies exist in the codebase:

- **`TimeBoundTaskPipe`**: Runs tasks until a wall-clock time budget (in milliseconds) is exhausted. Suitable when execution happens on a single, predictable thread where measuring elapsed time is cheap and meaningful.
- **`CountBoundTaskPipe`**: Runs a fixed number of tasks per scheduling cycle, regardless of how long they take.

On Spigot and Paper, queue replenishment is scheduled on or near the main server thread, where timing is relatively predictable and a time budget is a reasonable proxy for server impact.

Folia introduces **region-based multithreading**: each region of the world runs on its own thread, and chunk/entity ticking is distributed across many threads simultaneously. This fundamentally changes the cost model for async task scheduling:

1. There is no singular main thread to protect — load is already distributed across regional threads.
2. Measuring the wall-clock cost of async tasks running across multiple regional threads is unreliable: thread scheduling jitter, context switches, and cross-region contention make elapsed time a poor predictor of actual server impact.
3. Predictive scheduling based on time budgets (as used by `TimeBoundTaskPipe`) becomes ineffective when the execution environment is this complex.

## Decision

Use `CountBoundTaskPipe` instead of `TimeBoundTaskPipe` for queue replenishment tasks scheduled on Folia regional threads.

Rather than attempting to predict and budget execution time — which is unreliable in Folia's multi-threaded regional model — the pipeline switches to counting tasks. A fixed number of location-generation tasks are dispatched per scheduling cycle. This is a simpler, more robust bound that does not depend on timing accuracy and degrades gracefully under Folia's concurrent execution model.

## Consequences

- **Positive:**
  - Task bounding is simple, predictable, and immune to timing measurement errors on Folia's multi-threaded scheduler.
  - Each regional thread receives a consistent, bounded workload per cycle regardless of system load or scheduling jitter.
  - The approach is conservative: if tasks run faster than expected, the bound is slightly under-utilised; if they run slower, the server is protected from a runaway replenishment burst.

- **Negative / Trade-offs:**
  - The count bound is a coarser control than a time budget: if individual tasks vary significantly in cost, a fixed count may over- or under-utilise the available time window.
  - The optimal task count per cycle shall be tuned empirically for typical Folia deployments; it is exposed as a configurable parameter.

## References

- Implementing classes: `CountBoundTaskPipe`, `TimeBoundTaskPipe` (`rtp-core`); Folia scheduler wiring (`rtp-folia`)
- Design reference: [`DESIGN.md` §2 — Concurrency and Platform-Specific Thread Safety](../dev/DESIGN.md)
- Folia regional threading model: https://github.com/PaperMC/Folia
- Requirements: `REQ-FOLIA-F-001`, `REQ-FOLIA-NF-001` (thread safety on regional threads)

# ADR-006 — Async Queue Pre-Generation Over On-Demand Async Selection

**Status:** Accepted
**Date:** 2026-04-15

## Context

A safe, validated world location shall be produced when a player issues the `/rtp` command. Two broad strategies exist for sourcing that location:

1. **On-demand async selection**: When a teleport is requested, spawn an async task that generates and validates a candidate location in real time, then fulfil the teleport once the task completes.
2. **Pre-generation queue**: Continuously maintain a standing queue of pre-validated locations in the background. Teleport requests are fulfilled instantly by consuming the next entry from the queue; a background pipeline replenishes it.

Early versions of RTP used on-demand async selection. This produced **sporadic, nondeterministic server load**: every teleport request triggered a burst of async work (chunk loading, safety checks, retries on bad sectors) whose cost and timing could not be predicted. Under concurrent requests the bursts overlapped, causing CPU spikes with no recovery periods between them.

In addition, on-demand selection pays the full validation cost at request time — including loading and ticking chunks that may never have been visited — making the worst-case latency unbounded when the region contains many bad sectors or unloaded terrain.

## Decision

Maintain a standing pre-generation queue (`RegionQueueManager`) replenished by a **periodic rotating background task**.

The replenishment task is scheduled on a fixed cadence rather than triggered reactively. This distributes validation work evenly across server ticks, giving the server predictable recovery periods between replenishment cycles — analogous to real-time scheduling, where a bounded time slice is granted periodically and work does not accumulate into uncontrolled bursts.

Pre-filling the queue via `/rtp fill` further guards against runtime chunk-loading costs: when a region has been pre-filled into the spatial memory database (`MemoryShape`), background replenishment can skip the most expensive step (loading and ticking unknown chunks) entirely, because safe sectors are already known.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| On-demand async selection (original design) | Produced sporadic, nondeterministic CPU load spikes with no recovery periods between concurrent requests; worst-case latency was unbounded in poorly-seeded regions. |
| On-demand selection with a thread pool | Bounds concurrency but does not smooth the load distribution over time; bursts still occur at peak request rates and recovery periods are unpredictable. |
| Lazy per-request caching (generate once, reuse) | Does not scale to multiple concurrent users or high-frequency servers; the first requester still bears the full cold-start validation cost. |

## Consequences

- **Positive:**
  - Teleport execution is O(1) (queue dequeue) from the player's perspective — no blocking on the main thread.
  - Server CPU load from location validation is spread evenly over time with deterministic per-cycle cost, analogous to real-time scheduling.
  - Pre-filling via `/rtp fill` eliminates runtime chunk-loading costs, making replenishment nearly free for well-seeded regions.
  - The queue depth and replenishment rate are configurable, allowing operators to tune memory usage against responsiveness.

- **Negative / Trade-offs:**
  - The standing queue consumes memory proportional to its configured depth and the number of active regions.
  - There is a brief cold-start period after server startup (or after `/rtp fill reset`) during which the queue may be empty and teleports shall wait.
  - Over-provisioning the queue depth wastes chunk ticket allocations; operators shall tune depth to their expected concurrent player count.

## References

- Implementing classes: `RegionQueueManager`, `TeleportPipelineTask` (`rtp-core`)
- Design reference: [`DESIGN.md` §1 — Asynchronous Queue-Based Pre-Generation](../DESIGN.md)
- Related: [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) (bounded spatial selection), [ADR-008](ADR-008-memory-tracker-active-gc.md) (chunk allocation management)
- Requirements: `REQ-RTP-F-001` (instant execution), `REQ-RTP-NF-001` (bounded computation overhead)

# ADR-110 - Purpose-Gated Live Loads

**Status:** Proposed
**Date:** 2026-10-08
**Extends:** [ADR-016](ADR-016-anvil-subsystem.md) (Anvil read-only data source), [ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md) (chunk tickets and stale guard)
**Related:** [ADR-109](ADR-109-measured-cost-probe-first-gate.md) (probe-first governor)

## Context

Every full candidate check resolves its chunk through `RTPWorld.getOrLoadChunk`: RTP cache, then the region-file view (ADR-016), then a native (server) load. Queue fill (`RegionCacheTask` -> `PregenTask`) used the same chain as an immediate teleport, so whenever the region file could not answer (ungenerated chunk, corrupt entry, non-Anvil format, `anvilPrefilterEnabled: false`) fill loaded, and generated if needed, the center chunk, the safety neighbour grid, and the `(2r+1)^2` success ring (`r = max(safetyRadius, viewDistanceSelect)`).

None of that was pinned. `PregenTask` returned results without a reservation; the shared-queue path ignored the ring and stored the location unpinned in `unkeptLocations`; the per-player path waited for the ring, then stored it unpinned too. Native loads without a ticket are typically unloaded before the location is used.

A native load costs the server far more than RTP's own timing shows: memory for the full chunk, light and entities; scheduling work it owns (generation workers, on Folia the owning region); and churn in its open region-file cache. ADR-109 measures only RTP-side wall time, so it cannot price this. A load whose result is discarded is pure cost.

## Decision

1. **Purpose travels with generation.** `LoadPurpose { IMMEDIATE, SPECULATIVE }` is set on `PregenState`. `RegionCacheTask` fills with `SPECULATIVE` (shared and per-player). Every other caller (empty-queue fallback, `/rtp biome:...`, immediate teleport) keeps `IMMEDIATE` and today's behaviour. Third-party `ILocationGenerator` implementations keep their own contract.
2. **Non-loading read SPI.** `RTPWorld.getChunkIfReadable(cx, cz)` resolves from a resident chunk or the region file and completes with `null` where only a native load could answer; `getOrReadChunk` composes it with the cache. Bukkit/Paper, Folia, NeoForge and Fabric common / common-unobf override it with their region-file path. The default serves resident chunks only (used by the v26 Fabric adapters, which have no region-file path).
3. **Speculative fill never loads natively unpinned.** Center chunk and safety neighbours resolve through the read SPI. On a miss the attempt either:
   - **pins**: claims a slot from `LiveLoadGate` (per region, `keptLocations.size() + pinsInFlight < activeChunkCap`), then proceeds with native loads and returns its result with a `ChunkReservation`, which `RegionCacheTask` hands to the kept (pinned) queue; if the kept queue refuses, the reservation is closed and the location is stored unpinned; or
   - **defers**: no slot free, so the candidate is skipped (`deferred/...` outcome) without touching the bad-chunk tables, and the next attempt runs.
   A slot is released on every attempt exit (reschedule, success, exhaustion).
4. **No unpinned ring.** A speculative result that never pinned completes without the success ring; the teleport-time preload (`viewDistanceTeleport`, ADR-072) loads it when a player is waiting. The per-player fill path likewise enqueues without building a ring.
5. **Resident chunks are free.** A chunk the server already holds is always used, whatever the purpose.
6. **ADR-109 prices file reads only.** On the speculative path, native-load time is excluded from the probe governor's samples.
7. **Observable.** `LiveLoadGateRow` per region in `RTPMetricsExtension.liveLoadGates`: read-resolved, deferred (center / neighbour), pinned loads, pinned kept / overflow, rings skipped, pins in flight, and resident-at-use counts split by pinned / unpinned (`QueueTask` checks `isChunkLoaded` when a queued location is consumed). A 60 s per-region `[RTP][load-gate]` INFO summary is temporary verification output and may be lowered to FINE once accepted.

## Consequences

- Fill no longer generates or loads chunks it may never use; memory, generation work and region-file cache churn from fill are bounded by `activeChunkCap` pinned results.
- On fresh / mostly ungenerated worlds or non-Anvil formats, fill output is limited to what the kept queue can pin; the rest is deferred and teleports fall back to the immediate path (native load at teleport time). This is deliberate: the cost is paid only when a player needs the result.
- `ChunkReservation` tickets only the set's center chunk; ring chunks of a pinned result are held by the server's ticket propagation around it, not individually.
- `unkeptLocations` entries from fill are verified from file data only; the existing teleport-time `isSafe` re-check (ADR-016 section 4) remains authoritative (S-001).
- Status moves to Accepted once a stress run shows lower MSPT / memory from fill without a teleport-latency regression, and the resident-at-use counts confirm pinned results survive until use.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Status quo (fill loads natively) | Loads and generates chunks that are typically unloaded before use; costs the server memory and scheduling RTP never measures. |
| Defer strictly (never load natively in fill) | Fill produces nothing on fresh worlds or non-Anvil formats. |
| Cap speculative loads by measured unloaded-before-use rate | Wasted loads happen before the measurement reacts; pinning bounds the memory by construction. |
| Let ADR-109 price native loads | Server-side memory and scheduling costs are invisible to RTP-side timings. |

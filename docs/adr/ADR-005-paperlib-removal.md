# ADR-005 — Removal of PaperLib in Favour of Native Paper APIs

**Status:** Accepted (amended 2026-10-08, see Amendments 1 and 2)
**Date:** 2026-04-15

## Amendment 2 (2026-10-08): Same-tick teleport for resident destinations (`teleportPath`)

### Context

Amendment 1 recorded `teleportAsync` as "0-1 tick, 6-14 ms". Paper's source shows why the upper end is the common case: `CraftEntity.teleportAsync` always goes through `loadChunksForMoveAsync`, then `scheduleOnMain`, which queues the move on `MinecraftServer.processQueue`. That queue is drained once per tick in `tickChildren`, after the Bukkit scheduler heartbeat. A call made after the drain therefore waits for the next tick even when the chunk is already loaded. This is the 6 ms p50 dispatch-to-arrival latency in the Paper benchmarks, against about 1 ms for the earlier inline `player.teleport`.

The inline teleport was dropped because of the `isInWall -> syncLoad` stall. Its root cause was the arrival chunk ticket being released in the teleport tick (`RTP-4`), and decision 4 of Amendment 1 (`holdArrivalReservation`) removes it. An inline teleport is safe again when the destination chunk is already loaded.

### Decision

1. `PaperRTPPlayer#setLocation` and the reflective Paper path in `BukkitRTPPlayer#setLocation` call `player.teleport(location)` inline and complete the future at once when `TeleportPathSelector.canTeleportDirect` holds: mode `AUTO`, caller on the primary thread, not Folia, and `World#isChunkLoaded` true for the destination chunk. Otherwise they use `teleportAsync` as in Amendment 1.
2. The player is placed at block centre, so the bounding box (+/-0.3) stays inside that one chunk. The inline path therefore cannot trigger a chunk load (S-005), and the arrival hold keeps the chunk loaded through the player's first tick.
3. Folia always uses `teleportAsync`: a cross-region teleport must not run inline. Spigot (no `teleportAsync`), Fabric and NeoForge are unchanged.
4. S-004: an inline `false` result (for example a cancelled `PlayerTeleportEvent`) completes the future `false`, and the pipeline sends the configurable `unsafe` message. An inline throw is logged and falls back to `teleportAsync`.
5. `advanced/performance.yml` `teleportPath: AUTO | ASYNC` (default `AUTO`). `ASYNC` restores Amendment 1 behaviour exactly.

### Consequences

- **Positive:** Kept-queue hits dispatched on the main thread arrive in the dispatch tick, removing up to one tick of latency per teleport. In a closed-loop load, throughput is in-flight attempts divided by latency, so it also raises the achievable teleport rate.
- **Negative / Trade-offs:** The teleport's work (event, entity move, tracking updates) now runs inside the caller's tick slice instead of the process-queue drain, so MSPT accounting moves between the two. Dispatches from async threads still take the `teleportAsync` path and its tick wait.
- **Verification:** `PaperRTPPlayerSetLocationTest` (inline when resident on main; async when unloaded, off-main, on Folia, or with `ASYNC`; inline `false` and throw handling) and `BukkitRTPPlayerSetLocationTest` (reflective path). Latency gain to be confirmed in a bench run.

## Amendment 1 (2026-10-08): Native Asynchronous Entity Teleportation (`teleportAsync`) and S-005 Arrival Tick Protection

### Context and Problem

The original decision in ADR-005 focused on native asynchronous chunk loading (`World#getChunkAtAsync`), removing PaperLib. However, PaperLib's second core capability — `PaperLib.teleportAsync` for entity teleportation — was omitted from `rtp-paper`. Entity teleportation remained in `BukkitRTPPlayer.setLocation(RTPLocation)` (in `rtp-bukkit-common`, compiled against the Spigot API). While an `if (IS_FOLIA)` check was later added to reflectively call `teleportAsync` on Folia to avoid cross-region sync teleport exceptions, Paper was routed to legacy Spigot synchronous `player.teleport(Location)` on the primary server thread. When `PaperRTPPlayer` was introduced in ADR-072 for view-distance clamping, it only overrode view-distance accessors and inherited synchronous `setLocation` by omission.

Empirical profiling on Paper 26.2 (spark analysis during burst saturation benchmark run `20261008-111241`, Linear issue `RTP-4`) revealed that synchronous `player.teleport(Location)` places the player into destination chunk coordinates before Paper's chunk system (Chunk System v2) has completed promoting the chunk to full `ENTITY_TICKING` status. Later in the arrival gametick, entity collision processing:
```text
net.minecraft.world.entity.LivingEntity.baseTick
  net.minecraft.world.entity.LivingEntity.isInWall
    net.minecraft.world.entity.Entity.isInWall
      net.minecraft.server.level.ServerChunkCache.getChunk
        net.minecraft.server.level.ServerChunkCache.getChunkFallback
          net.minecraft.server.level.ServerChunkCache.syncLoad
```
parked the primary server thread in synchronous chunk loading for an average of 15.4 ms per teleport. Across the 4,096-teleport phase, this accumulated to 63.3 seconds of main-thread stall (49%–57% of all server CPU), violating absolute safety prohibition S-005 (zero main-thread chunk loading). In contrast, competitor plugins (BetterRTP via PaperLib and EzRTP via native Paper API) both route teleports through `teleportAsync` and incurred only ~0.1 ms/min on this path.

### Analysis of `teleportAsync` Mechanics & Scheduling Latency

A concern was evaluated: does Paper's `Entity#teleportAsync(Location)` inherently delay the teleport or push it into a deferred background task?

1. **No Artificial Timer or Delay:** `teleportAsync` does not inject arbitrary sleeps, timers, or artificial delays. Its completion is tied strictly to the destination chunk's readiness at `ENTITY_TICKING` status.
2. **Pre-Verified Cache Resident Chunks:** In LeafRTP, candidate destinations are served from pre-verified background queues where the target chunk is already loaded and pinned via a plugin ticket (`ChunkReservation`). Because the underlying chunk future is already completed (`isDone() == true`), Paper's entity transition callback runs immediately on the main-thread task drain:
   - If dispatched early in the tick, execution completes **within the same gametick**.
   - If dispatched after the current tick's task drain, execution completes at the start of the **very next gametick** (a transition window of 0–1 ticks, or 6–14 ms wall-clock latency).
3. **True Cost Comparison:** While legacy synchronous `player.teleport` technically executed on the current tick's call stack, it stalled that tick by 15.4 ms waiting on synchronous chunk loading. Native `teleportAsync` offloads chunk readiness verification and executes without stalling the tick loop.

### Decisions Landed

1. **`PaperRTPPlayer#setLocation` Native Override:**
   `PaperRTPPlayer` (in `platforms/rtp-paper/rtp-paper-common`) overrides `setLocation(RTPLocation)` to directly invoke Paper's native `Player#teleportAsync(Location)` API, completing the returned `CompletableFuture<Boolean>` upon transition completion. Preserves player yaw and pitch rotation on arrival.
2. **`BukkitRTPPlayer#setLocation` Dynamic Discovery & Fallback:**
   `BukkitRTPPlayer` (in `platforms/rtp-bukkit/rtp-bukkit-common`) dynamically resolves and caches `teleportAsync(Location)` via reflection on any supporting Bukkit platform (Paper, Purpur, Folia), preserving rotation. It falls back to synchronous `player.teleport` on the scheduler only on pure Spigot / CraftBukkit runtimes where `teleportAsync` is absent.
3. **S-004 Auditing & Fail-Safe Fallback:**
   Exceptional completions of `teleportAsync` log a warning via `RTP.log` and complete the future `false` (never hanging or silently swallowed). If `teleportAsync` throws synchronously on invocation, it safely catches, logs, and falls back to a scheduled synchronous teleport.
4. **Arrival Ticket Retention (`holdArrivalReservation`):**
   Complements `teleportAsync` by retaining the destination `ChunkReservation` in `TeleportPipelineTask` for `ARRIVAL_HOLD_TICKS = 20` ticks post-arrival. This ensures the landing chunk remains pinned against aggressive chunk eviction policies (such as Paper's `delay-chunk-unloads-by: 0s`) until player-connection chunk tickets take over.

### Consequences

- **Positive:** Eliminates the `isInWall -> syncLoad` stall on Paper, restoring compliance with REQ-RTP-S-005 (zero main-thread chunk loading).
- **Positive:** Completes the removal of PaperLib started in ADR-005 by adopting Paper's native async entity teleportation API across the Paper platform adapter.
- **Positive:** `BukkitRTPPlayer` gracefully accelerates on modern Paper/Purpur runtimes when running the Spigot adapter jar without requiring platform-specific recompilation.
- **Negative / Trade-offs:** Entity transition completes asynchronously across a 0–1 tick boundary rather than inline synchronously; the teleport pipeline already expects asynchronous completion via `CompletableFuture<Boolean>`, so no pipeline changes were required.

---

## Context

[PaperLib](https://github.com/PaperMC/PaperLib) is a cross-platform compatibility shim (async chunk loading, etc.) intended for plugins that run on both Paper and plain Spigot. RTP maintains separate adapter modules (`rtp-bukkit`, `rtp-paper`) for each platform, so the Paper adapter has no Spigot code path to shim and a cross-platform compatibility library adds no value there.

## Decision

`rtp-paper` shall not depend on PaperLib. It calls Paper's native async chunk loading APIs directly.

Because `rtp-paper` is loaded only on Paper servers, a compatibility shim is unnecessary. Direct native calls remove a runtime dependency, eliminate an indirection layer, and keep the adapter on the current, non-deprecated Paper API surface.

## Consequences

- **Positive:**
  - Removes a runtime dependency; operators no longer need PaperLib on their server when running the Paper adapter.
  - The adapter uses Paper's current, actively maintained async chunk loading API directly, without a deprecated shim in the call path.
  - Reduces indirection and simplifies the call stack for async chunk operations.

- **Negative / Trade-offs:**
  - The `rtp-paper` adapter is now strictly Paper-only at compile time; it cannot be loaded on a plain Spigot server (this was already the intended deployment model).
  - Any future Paper API changes shall be handled directly in `rtp-paper` rather than being absorbed by a compatibility library.

## References

- PaperLib repository: https://github.com/PaperMC/PaperLib
- Implementing modules: `rtp-paper` (all version submodules), `rtp-bukkit` (`BukkitRTPPlayer`)
- Implementing classes: `PaperRTPPlayer`, `BukkitRTPPlayer`, `TeleportPathSelector`, `TeleportPipelineTask` (`holdArrivalReservation`)
- Tests: `PaperRTPPlayerSetLocationTest`, `BukkitRTPPlayerSetLocationTest`, `ReqRtpS005ArrivalChunkHoldTest`
- Changelog entry: [`CHANGELOG.md` — 2.0.18](../../CHANGELOG.md)
- Upgrade notes for operators: [`MIGRATION.md`](../admin/MIGRATION.md)
- Related ADRs: [ADR-012](ADR-012-chunk-reservation-abstraction.md) (`ChunkReservation` abstraction), [ADR-015](ADR-015-stale-chunk-guard-countbound-pipes.md) (stale-chunk guard), [ADR-072](ADR-072-teleport-view-distance-clamp-and-restore.md) (view-distance clamp and `PaperRTPPlayer`)
- Issue tracking: Linear `RTP-4`
- Requirements: `REQ-RTP-S-001` (platform compatibility), `REQ-RTP-S-004` (no silent teleport failures), `REQ-RTP-S-005` (zero main-thread chunk loading), `REQ-PAPER-F-001` (Paper async chunk load and entity teleport), `REQ-SPIGOT-F-001` (Spigot chunk load and entity teleport)

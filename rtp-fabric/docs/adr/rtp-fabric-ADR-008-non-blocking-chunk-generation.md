# rtp-fabric-ADR-008 — Non-blocking chunk generation via `getChunkFuture`

- **Status:** Accepted
- **Date:** 2026-05-08
- **Supersedes:** none (refines `FabricRTPWorld.loadLiveChunk` and adds an SPI method on `FabricVersionAdapter`)
- **Scope:** `rtp-fabric` only

## Context

`FabricRTPWorld.loadLiveChunk` previously did:

```java
server.submit(() -> {
    ChunkAccess chunk = cache.getChunk(cx, cz, ChunkStatus.FULL, /*load=*/ true);
    ...
});
```

Where `server.submit(...)` runs the supplier on the `MinecraftServer` tick thread, and `ServerChunkCache#getChunk(..., true)` (Mojmap) / `class_3215.method_12121` (intermediary) is the **synchronous-blocking** chunk-load entry point: it parks the *calling thread* on the server's own task queue and pumps tasks until generation finishes.

When the calling thread *is* the server tick thread (which it always is here), this turns into a self-deadlock against the chunk-generation dependency graph: the tick thread drives its own queue from inside one of its tasks, and any other queued tick task that participates in chunk generation cannot make progress until the current generation completes — but the current generation may itself be blocked waiting for those queued tasks. Crash report `crash-2026-05-08_01.22.29-server.txt` captured this exactly:

- Server thread (Id=114) parked inside `class_3215.method_12121` at `FabricRTPWorld.lambda$loadLiveChunk$2:297`, driven by `class_1255.method_18857` (`BlockableEventLoop.runTask`).
- All 14 `ForkJoinPool.commonPool-worker-*` threads parked on `Semaphore$FairSync` inside `FabricRTPWorld.lambda$loadLiveChunk$1:280` — i.e. waiting for one of two `liveLoadPipe` permits that were held by AsyncSupply tasks themselves stuck on the tick thread.

The 2-permit "generation pipe" introduced in the prior session did not prevent the freeze; it shaped it into a cleaner deadlock by guaranteeing nobody else could even attempt the work.

## Decision

Replace the blocking call with vanilla's **non-blocking** chunk-future entry point:

- Mojmap: `ServerChunkCache#getChunkFuture(int cx, int cz, ChunkStatus status, boolean create) -> CompletableFuture<Either<ChunkAccess, ChunkLoadingFailure>>`
- Intermediary on 1.20.1: `class_3215.method_17298` (occasionally surfaced as `getChunkFutureMainThread` in some Yarn shipments).

This method must be called on the server thread but returns immediately; vanilla then schedules generation across its own internal `Worker-Main` pool / `ProcessorMailbox` graph and completes the future on whichever thread finishes the last stage.

### Implementation

1. **New SPI method** on `FabricVersionAdapter`:

   ```java
   default CompletableFuture<RTPChunkHandle> requestFullChunkAsync(RTPLevelHandle level, int cx, int cz);
   ```

   Default implementation falls through to the legacy `getChunkFull` (blocking) for forward compatibility with out-of-tree adapters; every RTP-shipped adapter overrides it.

2. **Per-version implementations** (v1_20_R1, v1_21_R1, v1_21_R5, v1_21_R11) resolve `getChunkFuture` *structurally* — match any method on `ServerChunkCache` (or any superclass) with signature `(int, int, ChunkStatus, boolean) -> CompletableFuture` and cache the resolved `Method` once. This makes the implementation mapping-agnostic (works under both Mojmap and Fabric intermediary). The result `Either<ChunkAccess, ChunkLoadingFailure>` is unwrapped reflectively (`left().orElse(null)`) to avoid hard-binding to `com.mojang.datafixers.util.Either`.

   - The `v26_1_R1` adapter retains the default fallback for now (it's a stub module without `net.minecraft.*` imports configured); when the 26.x adapter is fleshed out it should override following the same pattern.

3. **`FabricRTPWorld.loadLiveChunk` rewrite:**

   ```java
   server.submit(() -> adapter.requestFullChunkAsync(levelHandle, cx, cz))
         .thenCompose(inner -> inner)
         .whenComplete((handle, error) -> { ... });
   ```

   The `server.submit` supplier only *initiates* the request and returns the inner future inline; the `thenCompose` flattens off-thread. The continuation runs on whichever thread vanilla's chunk system completes the inner future on — explicitly *not* the tick thread.

4. **Removed:** the per-world `Semaphore liveLoadPipe` and `FABRIC_GENERATION_CONCURRENCY = 2` constant. With non-blocking dispatch there is no multi-second wait to back-pressure, and vanilla's chunk system has its own internal back-pressure.

5. **Preserved:**
   - Per-coordinate de-duplication map `inFlightLiveLoads` — concurrent callers for the same `(cx,cz)` still share one future.
   - The 4-second `completeOnTimeout` outer wrapper on `getOrLoadChunk` — defense-in-depth deadline against any future regression.
   - REQ-RTP-S-004 attribution: vanilla's `ChunkLoadingFailure` (Right branch of `Either`) is mapped to a `null` chunk in the result, which callers route through `FailTypes.nullChunk` rather than silently discarding.

## Affected files

- `rtp-fabric/rtp-fabric-common/src/main/java/.../version/FabricVersionAdapter.java` — new SPI method with default fallback.
- `rtp-fabric/rtp-fabric-common/src/main/java/.../world/FabricRTPWorld.java` — refactored `loadLiveChunk`, removed `liveLoadPipe`/`FABRIC_GENERATION_CONCURRENCY`.
- `rtp-fabric/rtp-fabric-v1_20_R1/.../V1_20_R1FabricVersionAdapter.java` — override.
- `rtp-fabric/rtp-fabric-v1_21_R1/.../V1_21_R1FabricVersionAdapter.java` — override.
- `rtp-fabric/rtp-fabric-v1_21_R5/.../V1_21_R5FabricVersionAdapter.java` — override.
- `rtp-fabric/rtp-fabric-v1_21_R11/.../V1_21_R11FabricVersionAdapter.java` — override.

## Consequences

**Positive:**
- The self-deadlock root cause (tick thread blocking on its own task queue from inside an AsyncSupply) is eliminated.
- The tick loop stays responsive even under simultaneous `QueueTask` + `ScanTask` + `PregenTask` pressure during early-server warm-up.
- No change to public API or to `rtp-core` / `rtp-api`.

**Negative / risks:**
- Relies on `getChunkFuture`'s `(int, int, ChunkStatus, boolean) -> CompletableFuture` shape. This signature has been stable across Mojang refactors of `ServerChunkCache` for years (since 1.14 in some form). Resilience to renames is provided by the structural matcher — same pattern proven on `applyTicket` / `removeTicket` in ADR-006.
- If any future Minecraft version moves to a different signature, the structural matcher will throw `NoSuchMethodException` on first use, the `whenComplete` will surface the failure, and `QueueTask`'s outer 5 s timeout will degrade gracefully.

**Out of scope:**
- The `rtp-core` 5 s `.orTimeout()` constants in `QueueTask` / `PregenTask` / `ScanTask` are unchanged.
- `v26_1_R1` adapter remains on the default fallback; flagged for follow-up when that module is implemented.

## References

- `crash-reports/crash-2026-05-08_01.22.29-server.txt` (the deadlock dump)
- `rtp-fabric-ADR-006` (structural method-resolution pattern)
- `docs/dev/REQUIREMENTS.md` REQ-RTP-S-004 (no silent discards), REQ-RTP-S-005 (no chunk loading on the main thread)

package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.safety.SafetyCompilationCache;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * State machine for the queue path of
 * {@link LocationGenerator#getLocationFuture(Region, RTPCommandSender, RTPPlayer, Set)}.
 *
 * <p>Non-blocking port of the original {@code while (!custom) { poll(); evaluate; }}
 * loop. Each polled pair is evaluated via chained {@link CompletableFuture}s; on
 * rejection, the task polls again without returning to the worker pool unless a
 * stage completed on a different thread.
 */
final class QueueTask {

    private final Region region;
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final @Nullable Set<String> biomeNames;
    private final CompletableFuture<GenerationResult> result;
    private final UUID playerId;
    private final boolean custom;

    QueueTask(
            Region region,
            RTPCommandSender sender,
            RTPPlayer player,
            @Nullable Set<String> biomeNames,
            CompletableFuture<GenerationResult> result) {
        this.region = region;
        this.sender = sender;
        this.player = player;
        this.biomeNames = biomeNames;
        this.result = result;
        this.playerId = player.uuid();
        this.custom = biomeNames != null && !biomeNames.isEmpty();
    }

    void start() {
        try {
            region.getShape();
            RTP.log(Level.FINE, "[ENQUEUE_TRACE] LocationGenerator.getLocation ENTER playerId=" + playerId
                    + " region=" + region.name
                    + " custom=" + custom
                    + " biomeNames=" + biomeNames
                    + " hasUnqueuedPerm=" + (sender != null && sender.hasPermission("rtp.unqueued"))
                    + " thread=" + Thread.currentThread().getName());
            if (custom) {
                fallback();
                return;
            }
            pollNext();
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] QueueTask.start failed: " + t, t);
            result.complete(null);
        }
    }

    private void pollNext() {
        CompletableFuture<RTPLocation> poll = region.queueManager.poll(playerId);
        if (poll == null) {
            fallback();
            return;
        }
        // Hot path: RegionQueueManager.poll returns CompletableFuture.completedFuture(loc)
        // for kept / per-player / fast entries. Staying on the caller thread here
        // satisfies architecture/01-teleport-execution-pipeline.md "cache_check -->
        // ReqTicket : Cache Hot" (no scheduler hop). Only unresolved polls (rare)
        // trampoline through the async pool.
        if (poll.isDone()) {
            RTPLocation pair;
            try {
                pair = poll.getNow(null);
            } catch (Throwable ex) {
                RTP.log(Level.WARNING, ex.getMessage(), ex);
                pollNext();
                return;
            }
            handlePair(pair);
            return;
        }
        poll.whenComplete((pair, ex) -> {
            if (ex != null) {
                RTP.log(Level.WARNING, ex.getMessage(), ex);
                // Match prior semantics: exception on poll → treat as null pair, try next poll.
                reenterAsync(this::pollNext);
                return;
            }
            reenterAsync(() -> handlePair(pair));
        });
    }

    private void reenterAsync(Runnable r) {
        try {
            RTP.serverAccessor.getScheduler().runTaskAsynchronously(r);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] QueueTask reenter failed: " + t, t);
            result.complete(null);
        }
    }

    private void handlePair(@Nullable RTPLocation pair) {
        if (pair == null || pair.coords() == null) {
            // Previous loop broke out immediately on null pair → fallback.
            // But if the pair was non-null with null coords, it also fell back.
            // We pollNext again to drain any remaining queued futures if available,
            // then fallback when the queue is truly empty.
            if (pair == null) {
                fallback();
                return;
            }
            pollNext();
            return;
        }

        RTPCoords left = pair.coords();
        RTPWorld<?> world = region.getWorld();
        int cx = left.x() >> 4;
        int cz = left.z() >> 4;

        ChunkReservation preReservation = pair.reservation();
        if (preReservation != null) {
            // Pre-acquired reservation from the queue: chunk is already loaded + pinned.
            Long resolvedKey = null;
            try {
                resolvedKey = preReservation.getChunkSet().chunks().get(0).getNow(null);
            } catch (Throwable ignored) { /* fall through to resolve via getCachedChunk */ }
            RTPChunk<?> cachedChunk = (resolvedKey != null) ? world.getCachedChunk(resolvedKey) : null;
            if (cachedChunk != null) {
                afterChunkResolved(pair, left, world, cx, cz, cachedChunk, preReservation);
            } else {
                // Unusual: reservation held but chunk not in cache. Use probe + livefallback.
                // Per-attempt chunk-load: do NOT race with .orTimeout here. The world
                // adapter is the canonical owner of any per-chunk deadline (it knows when
                // the server is incapable of loading a particular chunk). Letting the
                // future complete naturally means slow loads still warm rtpChunkCache for
                // the next attempt at the same coordinate. Rejection on null/exception
                // routes through the existing FailTypes.nullChunk attribution path.
                world.getOrLoadChunk(cx, cz, "QueueTask.preReservedFallback")
                        .whenComplete((chunk, ex) -> afterChunkResolved(pair, left, world, cx, cz,
                                (ex == null) ? chunk : null, preReservation));
            }
            return;
        }

        // BIOME_LOOKUP_PERF_PLAN.md Stage 1 — probe-first gate (QUEUETASK_PROBE_FIRST_PLAN.md
        // Mirrors PregenTask.tryProbeFirst / ScanTask.tryProbeFirstScan: try to
        // reject the candidate from a lean column probe before paying for the full
        // getOrLoadChunk decode. On probe-accept / probe-UNKNOWN / probe-exception we
        // fall through to the original load path unchanged; the post-load evaluateSafety
        // and Region.execute unkept→kept recheck remain authoritative.
        if (tryProbeFirstQueue(pair, left, world, cx, cz)) {
            return;
        }
        runFullLoadAndResolve(pair, left, world, cx, cz);
    }

    /**
     * Stage-1 probe-first gate for the location caching loop
     * (both {@code RegionCacheTask}-driven fills of {@code unkeptLocations}
     * and player-triggered {@code LocationGenerator.getLocation} attempts).
     *
     * <p>Returns {@code true} iff the probe committed a verdict that the caller
     * must honour without running the full load path:
     * <ul>
     *   <li><b>Reject</b> — {@link #pollNext()} has been (or will be) invoked
     *       via {@link #reenterAsync(Runnable)}. No chunk load, no reservation,
     *       no DB write.</li>
     *   <li><b>Accept-via-async</b> — the probe completed asynchronously and
     *       {@link #runFullLoadAndResolve} was dispatched from the callback.</li>
     * </ul>
     * Returns {@code false} on synchronous probe-UNKNOWN (default no-op adapter,
     * probe future null, adapter threw) so the caller runs the load path inline —
     * preserving zero behaviour change on platforms without an Anvil-backed probe.</p>
     *
     * <p>S-005: all probe work stays on the async pool; the
     * {@link java.util.concurrent.CompletableFuture} chain matches the shape used by
     * {@code ScanTask.tryProbeFirstScan} / {@code PregenTask.tryProbeFirst}.</p>
     */
    private boolean tryProbeFirstQueue(
            RTPLocation pair, RTPCoords left, RTPWorld<?> world, int cx, int cz) {
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor<?> vert;
        try {
            vert = region.getVert();
        } catch (Throwable t) {
            return false;
        }
        if (vert == null) return false;
        int minY = vert.minY();
        int maxY = vert.maxY();
        if (minY >= maxY) return false;

        CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkColumnProbe> fut;
        try {
            // PR-18 window: widen by one block below minY so adjustFromProbe's
            // standing-surface y-1 read doesn't trivially reject.
            fut = world.probeChunkColumn(cx, cz, minY - 1, maxY);
        } catch (Throwable t) {
            RTP.log(Level.FINE,
                    "[RTP] QueueTask probeChunkColumn threw for world=" + world.name()
                            + " chunk=(" + cx + "," + cz + "): " + t);
            return false;
        }
        if (fut == null) return false;

        // Synchronous fast path: default no-op adapter returns completedFuture(null);
        // real adapters with a warm cache frequently complete inline as well.
        if (fut.isDone() && !fut.isCompletedExceptionally()) {
            io.github.dailystruggle.rtp.api.world.ChunkColumnProbe probe;
            try {
                probe = fut.getNow(null);
            } catch (Throwable ignored) {
                return false;
            }
            if (probe == null) return false;
            RTPCoords picked;
            try {
                picked = vert.adjustFromProbe(probe, world.name());
            } catch (Throwable t) {
                // Adjustor misbehaved — treat as UNKNOWN, fall through.
                return false;
            }
            if (picked == null) {
                // Stage-1 reject: no valid Y in the probe window. Skip load + DB write.
                reenterAsync(this::pollNext);
                return true;
            }
            return false; // probe-accept — caller runs full load.
        }

        // Async completion: we've committed to this candidate; continuation happens
        // from the whenComplete callback.
        fut.whenComplete((probe, ex) -> {
            if (ex != null) {
                RTP.log(Level.FINE,
                        "[RTP] QueueTask probeChunkColumn failed for world=" + world.name()
                                + " chunk=(" + cx + "," + cz + "): "
                                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                reenterAsync(() -> runFullLoadAndResolve(pair, left, world, cx, cz));
                return;
            }
            if (probe == null) {
                reenterAsync(() -> runFullLoadAndResolve(pair, left, world, cx, cz));
                return;
            }
            RTPCoords picked;
            try {
                picked = vert.adjustFromProbe(probe, world.name());
            } catch (Throwable t) {
                reenterAsync(() -> runFullLoadAndResolve(pair, left, world, cx, cz));
                return;
            }
            if (picked == null) {
                reenterAsync(this::pollNext);
                return;
            }
            reenterAsync(() -> runFullLoadAndResolve(pair, left, world, cx, cz));
        });
        return true;
    }

    private void runFullLoadAndResolve(
            RTPLocation pair, RTPCoords left, RTPWorld<?> world, int cx, int cz) {
        // ADR-016 §13.1 probe-first via getOrLoadChunk traffic-cop.
        // Anvil-backed candidates run inline with no reservation; live-backed
        // candidates allocate a reservation and dispatch to the region thread.
        // Per-attempt chunk-load: per-chunk deadline lives in the world adapter,
        // not here. See note in the pre-reserved branch above.
        world.getOrLoadChunk(cx, cz, "QueueTask.runFullLoadAndResolve")
                .whenComplete((chunk, ex) -> {
                    if (ex != null || chunk == null) {
                        if (ex != null) {
                            RTP.log(Level.WARNING,
                                    "[RTP] QueueTask getOrLoadChunk failed for world=" + world.name()
                                            + " chunk=(" + cx + "," + cz + "): "
                                            + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        }
                        result.complete(new GenerationResult(null, 1, null));
                        return;
                    }
                    if (chunk.isSelfContained()) {
                        // Anvil branch: no reservation, no region-thread hop.
                        afterChunkResolved(pair, left, world, cx, cz, chunk, /*reservation*/ null);
                        return;
                    }
                    // Live branch: allocate reservation, await ticket apply, dispatch to region thread.
                    long chunkKey = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                    ChunkSet ticket = new ChunkSet(
                            world, cx, cz,
                            Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
                            new CompletableFuture<>());
                    ChunkReservation reservation = new ChunkReservation(ticket, world);
                    reservation.readyFuture().orTimeout(2, TimeUnit.SECONDS).whenComplete((v, tex) -> {
                        if (tex != null) {
                            RTP.log(Level.WARNING,
                                    "[RTP] QueueTask ticket apply did not complete within 2s ("
                                            + world.name() + " " + cx + "," + cz + ")");
                            reservation.close();
                            result.complete(new GenerationResult(null, 1, null));
                            return;
                        }
                        io.github.dailystruggle.rtp.api.world.RTPLocation targetLoc =
                                new io.github.dailystruggle.rtp.api.world.RTPLocation(
                                        world, (cx << 4) + 8, 0, (cz << 4) + 8);
                        try {
                            RTP.serverAccessor.getScheduler().runTask(targetLoc, () -> {
                                try {
                                    if (!world.isChunkLoaded(cx, cz)) {
                                        RTP.log(Level.FINE,
                                                "[RTP] Stale chunk on QueueTask region-thread dispatch ("
                                                        + world.name() + " " + cx + "," + cz + "); rejecting.");
                                        reservation.close();
                                        finishRejected(null);
                                        return;
                                    }
                                    afterChunkResolved(pair, left, world, cx, cz, chunk, reservation);
                                } catch (Throwable t) {
                                    RTP.log(Level.WARNING,
                                            "[RTP] QueueTask region-thread evaluation threw: " + t, t);
                                    reservation.close();
                                    finishRejected(null);
                                }
                            });
                        } catch (Throwable t) {
                            RTP.log(Level.WARNING,
                                    "[RTP] QueueTask failed to dispatch region-thread evaluation: " + t, t);
                            reservation.close();
                            finishRejected(null);
                        }
                    });
                });
    }

    @SuppressWarnings("unchecked")
    private void afterChunkResolved(
            RTPLocation pair,
            RTPCoords left,
            RTPWorld<?> world,
            int cx,
            int cz,
            @Nullable RTPChunk<?> chunk,
            @Nullable ChunkReservation reservation) {

        // Defensive re-resolve (Option 2+3, 2026-05-08): a chunk can be unloaded
        // between getOrLoadChunk completing and this consumer reading block state.
        // For live-backed chunks (isSelfContained==false) re-fetch the cached
        // reference and re-check isChunkLoaded — rejection routes through the
        // existing FailTypes.nullChunk attribution path. Anvil-backed chunks
        // (isSelfContained==true) are off-disk snapshots that cannot go stale,
        // so they bypass the guard and proceed unchanged.
        if (chunk != null && !chunk.isSelfContained()) {
            long centerKey = ((long) cx & 0xffffffffL) | ((long) cz << 32);
            RTPChunk<?> live = world.getCachedChunk(centerKey);
            if (live == null || !world.isChunkLoaded(cx, cz)) {
                RTP.log(Level.FINE,
                        "[RTP] QueueTask center chunk went stale before evaluateSafety ("
                                + world.name() + " " + cx + "," + cz + "); rejecting.");
                finishRejected(reservation);
                return;
            }
            chunk = live;
        }

        boolean pass = chunk != null;

        // Refresh unsafe-block cache (mirrors original inline logic).
        long t = System.currentTimeMillis();
        long dt = t - LocationGenerator.lastUpdate.get();
        Set<String> unsafeBlocks = LocationGenerator.unsafeBlocksCache;
        int safetyRadiusVal = LocationGenerator.safetyRadiusCache.get();
        if (dt > 5000 || dt < 0) {
            ConfigParser<SafetyKeys> safety =
                    (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
            if (value instanceof Collection<?> collection) {
                unsafeBlocks.clear();
                unsafeBlocks.addAll(
                        collection.stream()
                                .filter(Objects::nonNull)
                                .map(Object::toString)
                                .collect(Collectors.toSet()));
                RTPServerAccessor accessor = RTPAPI.serverAccessor;
                Map<String, Set<String>> tagSnapshot =
                        (accessor != null) ? accessor.blockTagSnapshot() : Collections.emptyMap();
                SafetyCompilationCache.getOrCompile(
                        unsafeBlocks,
                        tagSnapshot,
                        rejection ->
                                RTP.log(
                                        Level.WARNING,
                                        "[safety.yml] rejected unsafe-blocks token '"
                                                + rejection.rawToken()
                                                + "': "
                                                + rejection.reason()));
            }
            LocationGenerator.lastUpdate.set(t);
            safetyRadiusVal = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();
            LocationGenerator.safetyRadiusCache.set(safetyRadiusVal);
            // Ground-sweep depth — reuses SafetyKeys.safetyRadius so it stays
            // distinct from SafetyKeys.platformDepth (which exclusively sizes the
            // platform-creation tool in BukkitRTPWorld.platform / FoliaRTPWorld.platform).
            // Floor of 1 so the live-chunk re-check in runSafetyScan always validates
            // the ground column even when safetyRadius:0 collapsed the cube scan to a
            // no-op (probe-vs-live drift such as ice→water or water flow-in cases).
            int platformDepthVal = Math.max(
                    1,
                    safety.getNumber(SafetyKeys.safetyRadius, 1).intValue());
            LocationGenerator.platformDepthCache.set(platformDepthVal);
        }

        if (!pass) {
            finishRejected(reservation);
            return;
        }

        int safe = safetyRadiusVal;
        int L = safe * 2 + 1;
        int centerChunkX = chunk.x();
        int centerChunkZ = chunk.z();
        final RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
        localChunks[safe * L + safe] = chunk;

        // ADR-015 Folia-follow-up: no redundant stale-guard here. Live-backed
        // branch already guarded on the region thread in handlePair; anvil-
        // backed branch has no live chunk to race. A post-dispatch race
        // surfaces organically when neighbour getCachedChunk returns null.

        // Pre-load all needed neighbour chunks async.
        Set<Long> neighbourKeys = new LinkedHashSet<>();
        List<int[]> neighbourIdx = new ArrayList<>();
        for (int x = left.x() - safe; x <= left.x() + safe; x++) {
            int chunkX = x >> 4;
            int dcX = chunkX - centerChunkX;
            for (int z = left.z() - safe; z <= left.z() + safe; z++) {
                int chunkZ = z >> 4;
                int dcZ = chunkZ - centerChunkZ;
                int index = (dcX + safe) * L + (dcZ + safe);
                if (index < 0 || index >= localChunks.length) continue;
                if (localChunks[index] != null) continue;
                long packed = ((long) (chunkX + 0x80000000L) << 32) | (chunkZ + 0x80000000L);
                if (neighbourKeys.add(packed)) {
                    neighbourIdx.add(new int[]{chunkX, chunkZ, index});
                }
            }
        }

        if (neighbourIdx.isEmpty()) {
            evaluateSafety(pair, left, world, localChunks, L, centerChunkX, centerChunkZ, safe, unsafeBlocks, reservation);
            return;
        }

        List<CompletableFuture<Long>> nFutures = new ArrayList<>();
        for (int[] entry : neighbourIdx) {
            world.recordChunkLoadOrigin("QueueTask.replenishNeighbours");
            nFutures.add(world.getChunkAt(entry[0], entry[1]));
        }
        // Neighbour-grid load: per-chunk deadlines live inside the world adapter.
        // We orchestrate via allOf and let it complete naturally.
        CompletableFuture.allOf(nFutures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        finishRejected(reservation);
                        return;
                    }
                    for (int k = 0; k < nFutures.size(); k++) {
                        Long key = null;
                        try {
                            key = nFutures.get(k).getNow(null);
                        } catch (Throwable ignored) {
                            // leave null
                        }
                        RTPChunk<?> nchunk = (key != null) ? world.getCachedChunk(key) : null;
                        if (nchunk == null) {
                            finishRejected(reservation);
                            return;
                        }
                        localChunks[neighbourIdx.get(k)[2]] = nchunk;
                    }
                    evaluateSafety(pair, left, world, localChunks, L, centerChunkX, centerChunkZ, safe, unsafeBlocks, reservation);
                });
    }

    private void evaluateSafety(
            RTPLocation pair,
            RTPCoords left,
            RTPWorld<?> world,
            RTPChunk<?>[] localChunks,
            int L,
            int centerChunkX,
            int centerChunkZ,
            int safe,
            Set<String> unsafeBlocks,
            ChunkReservation reservation) {

        // Safety scan must run on the centre chunk's owning region thread on
        // Folia — live-backed RTPChunk.isSafe reads Level.getBlockState, which
        // is thread-local. On Spigot/Paper, RTP.scheduler.runTask(world,cx,cz,..)
        // hops to the MAIN server thread, which would dump a region-hop onto
        // the tick loop per candidate (unacceptable). Only Folia needs the
        // region-thread hop — dispatch inline on other platforms where the
        // current async-worker context is fine for the read.
        Runnable scan = () -> runSafetyScan(pair, left, world, localChunks, L,
            centerChunkX, centerChunkZ, safe, unsafeBlocks, reservation);
        if (isFoliaPlatform()) {
            RTP.scheduler.runTask(world, centerChunkX, centerChunkZ, scan);
        } else {
            scan.run();
        }
    }

    private static boolean isFoliaPlatform() {
        try {
            return "Folia".equals(RTP.serverAccessor.getPlatform());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void runSafetyScan(
            RTPLocation pair,
            RTPCoords left,
            RTPWorld<?> world,
            RTPChunk<?>[] localChunks,
            int L,
            int centerChunkX,
            int centerChunkZ,
            int safe,
            Set<String> unsafeBlocks,
            ChunkReservation reservation) {

        boolean pass = true;
        try {
            safetyCheck:
            for (int x = left.x() - safe; x <= left.x() + safe; x++) {
                int chunkX = x >> 4;
                int xx = x & 15;
                int dcX = chunkX - centerChunkX;
                for (int z = left.z() - safe; z <= left.z() + safe; z++) {
                    int chunkZ = z >> 4;
                    int zz = z & 15;
                    int dcZ = chunkZ - centerChunkZ;
                    int index = (dcX + safe) * L + (dcZ + safe);
                    RTPChunk<?> chunk1 = (index >= 0 && index < localChunks.length) ? localChunks[index] : null;
                    if (chunk1 == null) {
                        pass = false;
                        break safetyCheck;
                    }
                    for (int y = left.y() - safe; y <= left.y() + safe; y++) {
                        if (y > world.getMaxHeight() || y < world.getMinHeight()) continue;
                        if (!chunk1.isSafe(xx, y, zz, unsafeBlocks)) {
                            pass = false;
                            break safetyCheck;
                        }
                    }
                }
            }

            // Always-on commit-time re-validation of the candidate's own column on
            // the live center chunk, independent of safetyRadius. This mirrors the
            // gate that LinearAdjustor / JumpAdjustor used at probe time:
            //   - head cell (y+1) must not be in unsafeBlocks,
            //   - ground sweep (y-1 .. y-platformDepth) must not be in unsafeBlocks.
            // Rationale: with safetyRadius:0 the cube loop above only inspected the
            // feet air cell (trivially safe), so any probe-vs-live drift on the
            // ground (e.g. ice that melted to water, source water that flowed in,
            // a waterlogged-state block whose Set<String> isSafe path missed it)
            // went unchallenged. Re-running the predicate on the live center chunk
            // closes that window without changing the meaning of safetyRadius for
            // operators who configured a wider neighbourhood scan.
            if (pass) {
                RTPChunk<?> centerLive = (L > 0)
                        ? localChunks[safe * L + safe]
                        : null;
                if (centerLive == null) {
                    pass = false;
                } else {
                    int xx = left.x() & 15;
                    int zz = left.z() & 15;
                    int feetY = left.y();
                    int headY = feetY + 1;
                    int minH = world.getMinHeight();
                    int maxH = world.getMaxHeight();
                    if (headY <= maxH && headY >= minH
                            && !centerLive.isSafe(xx, headY, zz, unsafeBlocks)) {
                        pass = false;
                    }
                    if (pass) {
                        int depth = Math.max(
                                1, LocationGenerator.platformDepthCache.get());
                        for (int d = 1; d <= depth; d++) {
                            int gy = feetY - d;
                            if (gy < minH || gy > maxH) continue;
                            if (!centerLive.isSafe(xx, gy, zz, unsafeBlocks)) {
                                pass = false;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            pass = false;
            RTP.log(Level.WARNING, e.getMessage(), e);
        }

        if (!pass) {
            finishRejected(reservation);
            return;
        }

        // Non-blocking global-region-verifiers stage.
        final RTPLocation fpair = pair;
        GlobalRegionVerifiers.checkGlobalRegionVerifiers(left)
                .whenComplete((ok, ex) -> {
                    if (ex != null || !Boolean.TRUE.equals(ok)) {
                        finishRejected(reservation);
                        return;
                    }
                    // SUCCESS: for the live-backed branch, transfer ownership
                    // of the reservation's chunkSet via GenerationResult. For
                    // the anvil-backed branch (reservation==null), synthesise
                    // a fresh view-distance ChunkSet from anvil probes.
                    ChunkSet transferred;
                    if (reservation != null) {
                        // Do NOT transferOwnership() here: the reservation is still passed
                        // into GenerationResult and is closed by TeleportPipelineTask.runCleanup
                        // on every exit path. Calling transferOwnership() flips the
                        // reservation's `transferred` flag, which makes its later close()
                        // a no-op — leaking the kept entry's pinned chunk ticket on every
                        // successful queue-path teleport (kept count growth observed via
                        // /rtp info). The downstream load/verify code only needs the
                        // ChunkSet *view*, not ownership.
                        transferred = reservation.getChunkSet();
                    } else {
                        int ccx = left.x() >> 4;
                        int ccz = left.z() >> 4;
                        int radius = 1; // minimal view-distance analog for anvil branch
                        List<CompletableFuture<Long>> vd = new ArrayList<>();
                        for (int dx = -radius; dx <= radius; dx++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                world.recordChunkLoadOrigin("QueueTask.anvilViewDistance");
                                vd.add(world.getChunkAt(ccx + dx, ccz + dz));
                            }
                        }
                        transferred = new ChunkSet(world, ccx, ccz, vd, new CompletableFuture<>());
                    }
                    result.complete(new GenerationResult(left, fpair.attempts(), transferred, reservation));
                });
    }

    private void finishRejected(@Nullable ChunkReservation reservation) {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (Throwable ignored) {
                // best-effort close
            }
        }
        // Trampoline through the async pool rather than a direct call. When many
        // queued polls are already completed (e.g. a storm of rejected candidates
        // from an async pre-check) a direct pollNext() recurses pollNext ->
        // handlePair -> afterChunkResolved -> evaluateSafety -> finishRejected ->
        // pollNext on the same stack, which has been observed to exceed 50 frames
        // deep and blow past the tolerable call-stack budget. Re-enter via the
        // async pool to break the stack between rejections.
        reenterAsync(this::pollNext);
    }

    /**
     * Determines whether {@code region} is pumped by {@link io.github.dailystruggle.rtp.common.tasks.tick.AsyncTaskProcessing},
     * i.e. whether {@code Region.execute()} is periodically invoked for it.
     *
     * <p>Only the permanently-configured regions in {@code permRegionLookup} are
     * ticked; synthetic temp regions (shape/vert overrides and ADR-065
     * world-override regions such as {@code default_world_nether}) are never
     * pumped, so their teleport waitlist ({@code queueManager.playerQueue}) is
     * never drained. Enqueuing a player on such a region traps them in the
     * teleporting state forever. The check is by object identity because
     * {@link Region#equals(Object)} is value-based (shape/vert/world) and a
     * temp region cloned from {@code default} would otherwise compare equal to
     * the pumped {@code default} region.
     */
    private boolean isRegionPumped() {
        for (Region r : RTP.selectionAPI.permRegionLookup.values()) {
            if (r == region) return true;
        }
        return false;
    }

    private void fallback() {
        // A region that is not pumped by AsyncTaskProcessing (every synthetic
        // temp region: shape/vert overrides and ADR-065 world-override regions)
        // can never drain its teleport waitlist, so enqueuing would trap the
        // player. Serve it through the pregen path so the teleport completes on
        // first use instead.
        boolean unqueuedFast = custom
                || !isRegionPumped()
                || (sender != null && sender.hasPermission("rtp.unqueued"));
        if (unqueuedFast) {
            RTP.log(Level.FINE,
                    "[ENQUEUE_TRACE] LocationGenerator taking UNQUEUED fast-path playerId=" + playerId);
            LocationGenerator.getLocationFuture(region, biomeNames).whenComplete((res, ex) -> {
                RTP.log(Level.FINE,
                        "[ENQUEUE_TRACE] LocationGenerator UNQUEUED fast-path result playerId="
                                + playerId + " resNull=" + (res == null));
                if (ex != null) {
                    RTP.log(Level.WARNING, ex.getMessage(), ex);
                    result.complete(null);
                    return;
                }
                if (res == null) {
                    result.complete(null);
                    return;
                }
                long attempts = res.attempts();
                TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
                if (data != null && !data.completed) {
                    data.attempts = attempts;
                }
                result.complete(new GenerationResult(res.coords(), attempts, res.verifiedChunks()));
            });
            return;
        }

        // Normal enqueue branch: no I/O, runs inline on the async worker.
        try {
            RTP.log(Level.FINE,
                    "[ENQUEUE_TRACE] LocationGenerator taking ENQUEUE branch playerId=" + playerId
                            + " region=" + region.name);
            RTP.getInstance().processingPlayers.add(playerId);
            TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
            if (data == null) {
                data = new TeleportData();
                MemoryTracker.track(data, "TeleportData-" + playerId.toString(), 120000L);
                data.sender = (sender != null) ? sender : player;
                data.completed = false;
                data.time = System.currentTimeMillis();
                data.delay = (sender != null) ? sender.delay() : player.delay();
                data.targetRegion = region;
                data.originalCoords = new RTPCoords(
                        player.getLocation().world().name(),
                        player.getLocation().x(),
                        player.getLocation().y(),
                        player.getLocation().z());
                RTP.getInstance().latestTeleportData.put(playerId, data);
            }
            for (int j = 0; j < Region.onPlayerQueuePush.size(); j++) {
                Region.onPlayerQueuePush.get(j).accept(region, playerId);
            }
            region.queueManager.playerQueue.add(playerId);
            RTP.getInstance().queuedPlayers.add(playerId);
            data.queueLocation = region.queueManager.playerQueue.size();
            RTP.log(Level.FINE,
                    "[ENQUEUE_TRACE] LocationGenerator ENQUEUED playerId=" + playerId
                            + " queueSize=" + data.queueLocation
                            + " -> calling sendMessage(queueUpdate)");
            RTP.serverAccessor.sendMessage(playerId, MessagesKeys.queueUpdate);
            RTP.log(Level.FINE,
                    "[ENQUEUE_TRACE] LocationGenerator sendMessage(queueUpdate) RETURNED playerId=" + playerId);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] QueueTask enqueue branch failed: " + t, t);
        }
        result.complete(null);
    }
}

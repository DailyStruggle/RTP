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
            // Pre-acquired reservation from the queue: skip probe-first + ticket allocation.
            ChunkSet ticket = preReservation.getChunkSet();
            evaluateLoadedChunk(pair, left, world, cx, cz, ticket, preReservation, /*temporary*/ false, /*resolvedKey*/ null);
            return;
        }

        // ADR-016 §11 probe-first ordering.
        world.getChunkAt(cx, cz)
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((probeKey, probeEx) -> {
                    Long pk = (probeEx == null) ? probeKey : null;
                    RTPChunk<?> probed = (pk != null) ? world.getCachedChunk(pk) : null;
                    if (probed != null) {
                        // Anvil / view hit: synthesise an empty ChunkSet and a temporary reservation.
                        ChunkSet ticket = new ChunkSet(
                                world, cx, cz,
                                Collections.singletonList(CompletableFuture.completedFuture(pk)),
                                new CompletableFuture<>());
                        ChunkReservation reservation = new ChunkReservation(ticket, world);
                        evaluateLoadedChunk(pair, left, world, cx, cz, ticket, reservation, /*temporary*/ true, pk);
                        return;
                    }
                    // No probe hit: fall through to live-load.
                    world.getChunkAtAsync(cx, cz).whenComplete((ticket, liveEx) -> {
                        if (liveEx != null || ticket == null) {
                            RTP.log(Level.WARNING, (liveEx != null ? liveEx.getMessage() : "null live ticket"), liveEx);
                            result.complete(new GenerationResult(null, 1, null));
                            return;
                        }
                        ChunkReservation reservation = new ChunkReservation(ticket, world);
                        evaluateLoadedChunk(pair, left, world, cx, cz, ticket, reservation, /*temporary*/ true, null);
                    });
                });
    }

    @SuppressWarnings("unchecked")
    private void evaluateLoadedChunk(
            RTPLocation pair,
            RTPCoords left,
            RTPWorld<?> world,
            int cx,
            int cz,
            ChunkSet ticket,
            ChunkReservation reservation,
            boolean temporaryReservation,
            @Nullable Long resolvedKey) {
        // Resolve the center chunk from the ticket (or reuse a probe-hit key).
        CompletableFuture<Long> keyFuture;
        if (resolvedKey != null) {
            keyFuture = CompletableFuture.completedFuture(resolvedKey);
        } else {
            keyFuture = ticket.chunks().get(0);
        }
        keyFuture.orTimeout(5, TimeUnit.SECONDS).whenComplete((key, ex) -> {
            RTPChunk<?> chunk = (ex == null && key != null) ? world.getCachedChunk(key) : null;
            if (chunk == null) {
                // Secondary probe: matches the prior fallback on null cached chunk.
                world.getChunkAt(cx, cz)
                        .orTimeout(5, TimeUnit.SECONDS)
                        .whenComplete((fallbackKey, fex) -> {
                            RTPChunk<?> fb = (fex == null && fallbackKey != null)
                                    ? world.getCachedChunk(fallbackKey)
                                    : null;
                            afterChunkResolved(pair, left, world, cx, cz, fb, reservation);
                        });
                return;
            }
            afterChunkResolved(pair, left, world, cx, cz, chunk, reservation);
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
            ChunkReservation reservation) {

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

        boolean centerStillLoaded = world.isChunkLoaded(cx, cz);
        if (!centerStillLoaded) {
            RTP.log(Level.FINE,
                    "[RTP] Stale center chunk on safetyCheck entry ("
                            + world.name() + " " + cx + "," + cz + "); rejecting candidate.");
            finishRejected(reservation);
            return;
        }

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
            nFutures.add(world.getChunkAt(entry[0], entry[1]));
        }
        CompletableFuture.allOf(nFutures.toArray(new CompletableFuture[0]))
                .orTimeout(5, TimeUnit.SECONDS)
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
                    // SUCCESS: transfer ownership of the reservation's chunkSet via GenerationResult.
                    ChunkSet transferred = reservation.transferOwnership();
                    result.complete(new GenerationResult(left, fpair.attempts(), transferred, reservation));
                });
    }

    private void finishRejected(ChunkReservation reservation) {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (Throwable ignored) {
                // best-effort close
            }
        }
        pollNext();
    }

    /**
     * Fallback branch: either dispatch the pregen path (custom biomes OR the sender
     * has {@code rtp.unqueued}), or enqueue the player and return null.
     */
    private void fallback() {
        boolean unqueuedFast = custom || (sender != null && sender.hasPermission("rtp.unqueued"));
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

package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * State machine for the pregen path of {@link LocationGenerator}. One task instance
 * drives the attempt loop; between I/O-bearing stages it reschedules itself on the
 * async scheduler so the worker thread is released during
 * {@link RTPWorld#getChunkAtAsync}, {@link ChunkReservation#readyFuture},
 * neighbour chunk loads, and
 * {@link GlobalRegionVerifiers#checkGlobalRegionVerifiers}.
 *
 * <p>Preserves every {@link LocationGenerator.FailTypes} attribution from the previous
 * blocking implementation, including {@code ticketFailed}, {@code chunkLoadTimeout},
 * {@code asyncLoadNull}, {@code staleChunkBeforeVert}, {@code ticketApplyTimeout},
 * {@code neighborNull}, and the worldborder {@code OUTSIDE_BORDER} counter.
 */
final class PregenTask implements Runnable {

    private final PregenState state;
    private final CompletableFuture<GenerationResult> result;
    private long i;

    // Trampoline state. `inRunAttempt` is true while the while-loop in run() is
    // executing a single attempt. When a synchronous rejection path asks to
    // advance to the next attempt, it sets `needsReschedule=true` rather than
    // recursing, and the while-loop picks that up on the next iteration. If an
    // async callback lands after run()'s while-loop has already returned, the
    // callback invokes run() itself on its own thread, starting a fresh loop.
    private volatile boolean inRunAttempt = false;
    private volatile boolean needsReschedule = false;

    PregenTask(PregenState state, CompletableFuture<GenerationResult> result, long initialAttempt) {
        this.state = state;
        this.result = result;
        this.i = initialAttempt;
    }

    /**
     * Advance to the next attempt. If we are currently inside {@link #runAttempt()}
     * on this thread (either synchronously or via an inline CF callback), set a
     * flag so the while-loop in {@link #run()} continues; otherwise invoke
     * {@link #run()} directly on the current thread (which is an async worker
     * because CF callbacks running on a tick thread are not permitted by the
     * scheduler contract).
     */
    private void rescheduleNextAttempt() {
        i++;
        if (result.isDone()) return;
        if (inRunAttempt) {
            needsReschedule = true;
        } else {
            run();
        }
    }

    /** Continue the current attempt inline (no scheduler hop). */
    private void continueInline(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] PregenTask inline step failed: " + t, t);
            result.complete(null);
        }
    }

    @Override
    public void run() {
        while (!result.isDone()) {
            needsReschedule = false;
            inRunAttempt = true;
            try {
                runAttempt();
            } catch (Throwable t) {
                RTP.log(Level.WARNING, "[RTP] PregenTask attempt threw: " + t, t);
                result.complete(null);
                inRunAttempt = false;
                return;
            } finally {
                inRunAttempt = false;
            }
            if (!needsReschedule) {
                // Attempt is in flight on a future; the callback will re-invoke run().
                return;
            }
        }
    }

    private void runAttempt() {
        if (i > state.maxAttempts || state.biomeChecks >= state.maxBiomeChecks) {
            completeExhausted();
            return;
        }

        // --- shape select + biomeRecall ---
        long l = -1;
        int[] select;
        if (state.shape instanceof MemoryShape<?> memoryShape) {
            memoryShape.flushAndRebuild(state.resolution);
            if (state.biomeRecall && !state.defaultBiomes) {
                List<Map.Entry<Long, Long>> biomes = new ArrayList<>();
                for (String biomeName : state.biomeNames) {
                    long[] keys = memoryShape.getBiomeKeys(biomeName);
                    long[] sums = memoryShape.getBiomePrefixSums(biomeName);
                    if (keys != null && sums != null) {
                        for (int k = 0; k < keys.length; k++) {
                            long prevSum = (k > 0) ? sums[k - 1] : 0L;
                            biomes.add(new AbstractMap.SimpleEntry<>(keys[k], sums[k] - prevSum));
                        }
                    }
                }
                if (!biomes.isEmpty()) {
                    int nextInt = LocationGenerator.rng().nextInt(biomes.size());
                    Map.Entry<Long, Long> entry = biomes.get(nextInt);
                    l = entry.getKey() + (long) (LocationGenerator.rng().nextDouble() * entry.getValue());
                } else if (state.biomeRecallForced) {
                    RTP.log(Level.WARNING,
                            "[RTP] invalid state, biome recall enabled but biomes are not in memory - "
                                    + Arrays.toString(state.biomeNames.toArray()));
                    result.complete(new GenerationResult(null, i, null));
                    return;
                } else {
                    l = memoryShape.rand();
                }
            } else {
                l = memoryShape.rand();
            }
            select = memoryShape.locationToXZ(l);
        } else {
            select = state.shape.select();
        }

        int blockX = (select[0] << 4) + 8;
        int blockZ = (select[1] << 4) + 8;
        if (state.verbose) {
            state.selections.add(new AbstractMap.SimpleEntry<>((long) select[0], (long) select[1]));
        }

        // --- worldborder ---
        WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(state.world.name());
        io.github.dailystruggle.rtp.api.world.RTPLocation borderProbe =
                new io.github.dailystruggle.rtp.api.world.RTPLocation(
                        state.world,
                        blockX,
                        (state.vert.maxY() + state.vert.minY()) / 2,
                        blockZ);
        if (!border.isInside().apply(borderProbe)) {
            state.maxAttempts++;
            state.worldBorderFails++;
            if (state.worldBorderFails > 1000L) {
                RTP.log(Level.WARNING,
                        "[RTP] 1000 worldborder checks failed. region/selection is likely outside the worldborder");
                result.complete(new GenerationResult(null, i, null));
                return;
            }
            if (state.verbose) {
                state.failMap.get(LocationGenerator.FailTypes.worldBorder)
                        .put("OUTSIDE_BORDER", state.worldBorderFails);
            }
            rescheduleNextAttempt();
            return;
        }

        int cx = select[0];
        int cz = select[1];
        final long finalL = l;

        // --- probe-first (ADR-016 §11) ---
        CompletableFuture<Long> probeFuture = state.world.getChunkAt(cx, cz);
        probeFuture.orTimeout(5, TimeUnit.SECONDS).whenComplete((probeKey, probeEx) -> {
            Long pk = probeEx == null ? probeKey : null;
            RTPChunk<?> probedChunk = (pk != null) ? state.world.getCachedChunk(pk) : null;
            continueInline(() -> onProbeResolved(cx, cz, finalL, pk, probedChunk));
        });
    }

    private void onProbeResolved(int cx, int cz, long finalL, Long probeKey, RTPChunk<?> probedChunk) {
        if (probedChunk != null) {
            // Anvil / view hit: synthesise a ticket for the downstream reservation and continue.
            onChunkLoaded(cx, cz, finalL, probeKey, /*ticket*/ null, probedChunk);
            return;
        }
        // Fall through to the live-load path.
        CompletableFuture<ChunkSet> ticketFuture = state.world.getChunkAtAsync(cx, cz);
        ticketFuture.whenComplete((ticket, ex) -> {
            if (ex != null || ticket == null) {
                RTP.log(Level.WARNING,
                        "[RTP] Async chunk ticket failed for world=" + state.world.name()
                                + " chunk=(" + cx + "," + cz + "): "
                                + (ex != null ? (ex.getClass().getSimpleName() + ": " + ex.getMessage()) : "null ticket"));
                if (state.verbose) {
                    state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                            .compute("reason=ticketFailed", (s, a) -> (a == null) ? 1L : ++a);
                }
                continueInline(this::rescheduleNextAttempt);
                return;
            }
            CompletableFuture<Long> keyFuture = ticket.chunks().get(0);
            keyFuture.orTimeout(5, TimeUnit.SECONDS).whenComplete((key, ex2) -> {
                if (ex2 != null) {
                    RTP.log(Level.WARNING, "Chunk load timed out or failed at " + cx + ", " + cz);
                    if (state.verbose) {
                        state.failMap.get(LocationGenerator.FailTypes.timeout)
                                .compute("reason=chunkLoadTimeout", (s, a) -> (a == null) ? 1L : ++a);
                    }
                    continueInline(this::rescheduleNextAttempt);
                    return;
                }
                RTPChunk<?> chunk = (key != null) ? state.world.getCachedChunk(key) : null;
                if (chunk == null) {
                    if (state.verbose) {
                        state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                .compute("reason=asyncLoadNull", (s, a) -> (a == null) ? 1L : ++a);
                    }
                    continueInline(this::rescheduleNextAttempt);
                    return;
                }
                continueInline(() -> onChunkLoaded(cx, cz, finalL, key, ticket, chunk));
            });
        });
    }

    @SuppressWarnings("resource")
    private void onChunkLoaded(int cx, int cz, long finalL, Long chunkKey, ChunkSet ticketIn, RTPChunk<?> chunk) {
        // Synthesise a ticket for the probe-hit branch so ChunkReservation has a ChunkSet.
        ChunkSet ticket = ticketIn;
        if (ticket == null) {
            ticket = new ChunkSet(
                    state.world, cx, cz,
                    Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
                    new CompletableFuture<>());
        }
        final ChunkReservation reservation = new ChunkReservation(ticket, state.world);

        // --- await ticket apply (ADR-015 Paper chunk-system-v2) ---
        reservation.readyFuture().orTimeout(2, TimeUnit.SECONDS).whenComplete((v, ex) -> {
            if (ex != null) {
                RTP.log(Level.WARNING,
                        "[RTP] Chunk ticket application did not complete within 2s ("
                                + state.world.name() + " " + cx + "," + cz
                                + "); rejecting candidate.");
                if (state.verbose) {
                    state.failMap.get(LocationGenerator.FailTypes.timeout)
                            .compute("reason=ticketApplyTimeout", (s, a) -> (a == null) ? 1L : ++a);
                }
                reservation.close();
                continueInline(this::rescheduleNextAttempt);
                return;
            }
            continueInline(() -> onReservationReady(cx, cz, finalL, chunk, reservation));
        });
    }

    private void onReservationReady(int cx, int cz, long finalL, RTPChunk<?> chunk, ChunkReservation reservation) {
        // --- stale-chunk guard (ADR-015) ---
        if (!state.world.isChunkLoaded(cx, cz)) {
            RTP.log(Level.FINE,
                    "[RTP] Stale chunk before vert.adjust ("
                            + state.world.name() + " " + cx + "," + cz + "); rejecting candidate.");
            if (state.verbose) {
                state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                        .compute("reason=staleChunkBeforeVert", (s, a) -> (a == null) ? 1L : ++a);
            }
            reservation.close();
            rescheduleNextAttempt();
            return;
        }

        // --- vert.adjust ---
        RTPCoords res = state.vert.adjust(chunk);
        if (res == null) {
            if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                ((MemoryShape<?>) state.shape).addBadLocation(finalL);
            }
            if (state.verbose) {
                state.failMap.get(LocationGenerator.FailTypes.vert)
                        .compute("biome=", (s, a) -> (a == null) ? 1L : ++a);
            }
            reservation.close();
            rescheduleNextAttempt();
            return;
        }

        final int finalX = res.x();
        final int finalY = res.y();
        final int finalZ = res.z();

        // --- biome filter (ADR-016 §13.1 — read from the resolved chunk) ---
        String currBiome = chunk.getBiome(finalX, finalY, finalZ).toUpperCase();
        if (state.biomeNames.contains(currBiome) != state.biomeWhitelist) {
            state.biomeChecks++;
            if (state.biomeChecks < state.maxBiomeChecks) {
                state.maxAttempts++;
            }
            if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                ((MemoryShape<?>) state.shape).addBadLocation(finalL);
            }
            if (state.verbose) {
                String cb = currBiome;
                state.failMap.get(LocationGenerator.FailTypes.biome)
                        .compute("biome=" + cb, (s, a) -> (a == null) ? 1L : ++a);
            }
            reservation.close();
            if (state.biomeChecks >= state.maxBiomeChecks) {
                completeExhausted();
                return;
            }
            rescheduleNextAttempt();
            return;
        }

        final String resBiome = currBiome;

        // --- safetyCheck: load the (2r+1)² neighbour grid, then y-scan ---
        int safe = state.safetyRadius;
        int L = safe * 2 + 1;
        int centerChunkX = chunk.x();
        int centerChunkZ = chunk.z();
        final RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
        localChunks[safe * L + safe] = chunk;

        List<CompletableFuture<Long>> neighbourFutures = new ArrayList<>();
        List<int[]> neighbourIdx = new ArrayList<>();
        for (int dx = -safe; dx <= safe; dx++) {
            for (int dz = -safe; dz <= safe; dz++) {
                if (dx == 0 && dz == 0) continue;
                int ncx = centerChunkX + dx;
                int ncz = centerChunkZ + dz;
                int idx = (dx + safe) * L + (dz + safe);
                neighbourFutures.add(state.world.getChunkAt(ncx, ncz));
                neighbourIdx.add(new int[]{idx});
            }
        }

        if (neighbourFutures.isEmpty()) {
            // safetyRadius == 0: no neighbours to load, evaluate immediately.
            evaluateSafety(cx, cz, finalL, finalX, finalY, finalZ, resBiome, localChunks, L, centerChunkX, centerChunkZ, reservation);
            return;
        }

        CompletableFuture.allOf(neighbourFutures.toArray(new CompletableFuture[0]))
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        RTP.log(Level.WARNING,
                                "[RTP] Safety-check neighbour loads failed ("
                                        + state.world.name() + " center=(" + cx + "," + cz + ")): " + ex);
                        if (state.verbose) {
                            state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                    .compute("reason=neighborNull", (s, a) -> (a == null) ? 1L : ++a);
                        }
                        reservation.close();
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    // Populate the localChunks grid from the resolved neighbour keys.
                    boolean ok = true;
                    for (int idxI = 0; idxI < neighbourFutures.size(); idxI++) {
                        Long nkey;
                        try {
                            nkey = neighbourFutures.get(idxI).getNow(null);
                        } catch (Throwable t) {
                            nkey = null;
                        }
                        RTPChunk<?> nchunk = (nkey != null) ? state.world.getCachedChunk(nkey) : null;
                        if (nchunk == null) {
                            ok = false;
                            break;
                        }
                        localChunks[neighbourIdx.get(idxI)[0]] = nchunk;
                    }
                    if (!ok) {
                        if (state.verbose) {
                            state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                    .compute("reason=neighborNull", (s, a) -> (a == null) ? 1L : ++a);
                        }
                        reservation.close();
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    continueInline(() -> evaluateSafety(
                            cx, cz, finalL, finalX, finalY, finalZ, resBiome,
                            localChunks, L, centerChunkX, centerChunkZ, reservation));
                });
    }

    private void evaluateSafety(int cx, int cz, long finalL, int finalX, int finalY, int finalZ,
                                String resBiome,
                                RTPChunk<?>[] localChunks, int L,
                                int centerChunkX, int centerChunkZ,
                                ChunkReservation reservation) {
        int safe = state.safetyRadius;
        // Stale guard immediately before the y-scan.
        boolean centerStillLoaded = state.world.isChunkLoaded(cx, cz);
        boolean pass = centerStillLoaded;
        if (!centerStillLoaded) {
            RTP.log(Level.FINE,
                    "[RTP] Stale center chunk on safetyCheck entry ("
                            + state.world.name() + " " + cx + "," + cz + "); rejecting candidate.");
        }

        safetyCheck:
        for (int x = finalX - safe; pass && x <= finalX + safe; x++) {
            int chunkX = x >> 4;
            int xx = x & 15;
            int dcX = chunkX - centerChunkX;
            for (int z = finalZ - safe; z <= finalZ + safe; z++) {
                int chunkZ = z >> 4;
                int zz = z & 15;
                int dcZ = chunkZ - centerChunkZ;

                int idx = (dcX + safe) * L + (dcZ + safe);
                RTPChunk<?> c1 = (idx >= 0 && idx < localChunks.length) ? localChunks[idx] : null;
                if (c1 == null) {
                    if (state.verbose) {
                        state.failMap.get(LocationGenerator.FailTypes.nullChunk)
                                .compute("reason=neighborNull", (s, a) -> (a == null) ? 1L : ++a);
                    }
                    pass = false;
                    break safetyCheck;
                }
                for (int y = finalY - safe; y <= finalY + safe; y++) {
                    if (y > state.world.getMaxHeight() || y < state.world.getMinHeight()) continue;
                    if (!c1.isSafe(xx, y, zz, state.unsafeBlocks)) {
                        pass = false;
                        break safetyCheck;
                    }
                }
            }
        }

        if (!pass) {
            if (state.verbose) {
                final int fx = finalX, fy = finalY, fz = finalZ;
                state.failMap.get(LocationGenerator.FailTypes.misc)
                        .compute("location=(" + fx + "," + fy + "," + fz,
                                (s, a) -> (a == null) ? 1L : ++a);
            }
            if (state.shape instanceof MemoryShape) {
                ((MemoryShape<?>) state.shape).addBadLocation(finalL);
            }
            reservation.close();
            rescheduleNextAttempt();
            return;
        }

        // --- GlobalRegionVerifiers (non-blocking) ---
        RTPCoords resCoords = new RTPCoords(state.world.name(), finalX, finalY, finalZ);
        GlobalRegionVerifiers.checkGlobalRegionVerifiers(resCoords)
                .whenComplete((verPass, verEx) -> {
                    if (verEx != null || !Boolean.TRUE.equals(verPass)) {
                        if (state.verbose) {
                            final int fx = finalX, fy = finalY, fz = finalZ;
                            state.failMap.get(LocationGenerator.FailTypes.misc)
                                    .compute("location=(" + fx + "," + fy + "," + fz,
                                            (s, a) -> (a == null) ? 1L : ++a);
                        }
                        if (state.shape instanceof MemoryShape) {
                            ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                        }
                        reservation.close();
                        continueInline(this::rescheduleNextAttempt);
                        return;
                    }
                    // SUCCESS
                    continueInline(() -> completeSuccess(finalL, finalX, finalY, finalZ, resBiome, resCoords, reservation));
                });
    }

    private void completeSuccess(long finalL, int finalX, int finalY, int finalZ,
                                 String resBiome, RTPCoords resCoords,
                                 ChunkReservation reservation) {
        if (state.shape instanceof MemoryShape && finalL > 0) {
            ((MemoryShape<?>) state.shape).addBiomeLocation(finalL, state.resolution, resBiome);
        }
        long viewDistanceRadius = state.performance.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        int radius = Math.max(state.safetyRadius, (int) viewDistanceRadius);
        int ccx = resCoords.x() >> 4;
        int ccz = resCoords.z() >> 4;
        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                chunks.add(state.world.getChunkAt(ccx + x, ccz + z));
            }
        }
        ChunkSet verifiedChunks = new ChunkSet(state.world, ccx, ccz, chunks, new CompletableFuture<>());
        // Close the per-iteration reservation; ownership of the verifiedChunks set
        // transfers via the returned GenerationResult (matches the prior contract).
        reservation.close();
        result.complete(new GenerationResult(resCoords, i, verifiedChunks));
    }

    private void completeExhausted() {
        long reported = Math.min(i, state.maxAttempts);
        // Verbose failure summary — preserves the historical log shape.
        if (state.verbose
                && (i >= state.maxAttempts || i > state.maxAttemptsBase * Region.maxBiomeChecksPerGen)) {
            RTP.log(Level.INFO,
                    "#00ff80[RTP] ["
                            + state.region.name
                            + "] failed to generate a location within "
                            + state.maxAttempts
                            + " tries. Adjust your configuration.");
            for (Map.Entry<LocationGenerator.FailTypes, Map<String, Long>> mapEntry : state.failMap.entrySet()) {
                Map<String, Long> map = mapEntry.getValue();
                String[] output = new String[map.size()];
                int pos = 0;
                long count = 0;
                for (Map.Entry<String, Long> entry : map.entrySet()) {
                    output[pos] = "#00ff80[RTP] [" + state.region.name + "]  cause="
                            + mapEntry.getKey() + " " + entry.getKey() + " fails=" + entry.getValue();
                    count += entry.getValue();
                    pos++;
                }
                RTP.log(Level.INFO,
                        "#00ff80[RTP] [" + state.region.name + "]  cause=" + mapEntry.getKey() + " fails=" + count);
                for (String out : output) {
                    RTP.log(Level.INFO, out);
                }
            }

            StringBuilder selectionsStr = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Long, Long> entry : state.selections) {
                if (!first) selectionsStr.append(",");
                first = false;
                selectionsStr.append("(").append(entry.getKey()).append(",").append(entry.getValue()).append(")");
            }
            selectionsStr.append("}");
            RTP.log(Level.INFO, "#0f0080[RTP] [" + state.region.name + "] selections: " + selectionsStr);
        }
        result.complete(new GenerationResult(null, reported, null));
    }
}

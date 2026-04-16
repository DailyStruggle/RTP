package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;

import io.github.dailystruggle.rtp.folia.thread.AsyncThread;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class FoliaLocationGenerator implements ILocationGenerator {

    @Override
    @AsyncThread
    public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        UUID playerId = (context.player() != null) ? context.player().uuid() : null;
        return getLocationAsync(r, context.biomeNames(), playerId);
    }

    @Override
    @AsyncThread
    public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        // generateLocation explicitly bypasses the cache (used by RegionCacheTask to fill queues)
        return executeSearch(r, context.biomeNames());
    }

    @Override
    @AsyncThread
    public CompletableFuture<GenerationResult> getLocation(Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        UUID playerId = (player != null) ? player.uuid() : null;
        return getLocationAsync(r, biomeNames, playerId);
    }

    @Override
    @AsyncThread
    public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        return executeSearch(r, biomeNames); // No player provided, bypass cache
    }

    // Natively routes the request through the cache before falling back to generation
    @AsyncThread
    private CompletableFuture<GenerationResult> getLocationAsync(Region region, Set<String> biomeNames, UUID playerId) {
        boolean custom = biomeNames != null && !biomeNames.isEmpty();

        if (!custom) {
            CompletableFuture<io.github.dailystruggle.rtp.common.selection.region.RTPLocation> poll = region.queueManager.poll(playerId);
            if (poll != null) {
                // If a cached location is available, instantly map it to a GenerationResult.
                return poll.thenCompose(cachedLoc -> {
                    if (cachedLoc != null && cachedLoc.coords() != null) {
                        // pass the reservation along so the pipeline can close it.
                        return CompletableFuture.completedFuture(
                                new GenerationResult(cachedLoc.coords(), cachedLoc.attempts(), null, cachedLoc.reservation())
                        );
                    }
                    // If the poll completed with null (cache miss/error), fallback to generation
                    return executeSearch(region, biomeNames);
                });
            }
        }

        // If custom biomes were requested or the queue manager rejected the poll
        return executeSearch(region, biomeNames);
    }

    // Rename your old getLocationAsync wrapper to executeSearch
    @AsyncThread
    private CompletableFuture<GenerationResult> executeSearch(Region region, Set<String> biomeNames) {
        long resolution = Math.max(1L, region.getSettings().spatialResolution());

        ConfigParser<PerformanceKeys> performance =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        ConfigParser<LoggingKeys> logging =
                (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);

        boolean defaultBiomes = false;
        if (biomeNames == null || biomeNames.isEmpty()) {
            defaultBiomes = true;
            Object o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
            boolean whitelist = (o instanceof Boolean b) ? b : Boolean.parseBoolean(o.toString());

            o = safety.getConfigValue(SafetyKeys.biomes, null);
            List<String> biomeList = (o instanceof List<?> list)
                    ? list.stream().map(Object::toString).toList()
                    : null;
            Set<String> biomeSet = (biomeList == null)
                    ? new HashSet<>()
                    : biomeList.stream().map(String::toUpperCase).collect(Collectors.toSet());

            if (whitelist) {
                biomeNames = biomeSet;
            } else {
                Set<String> biomes = RTP.serverAccessor.getBiomes(region.getWorld());
                Set<String> set = new HashSet<>();
                for (String s : biomes) {
                    if (!biomeSet.contains(s.toUpperCase())) {
                        set.add(s);
                    }
                }
                biomeNames = set;
            }
        }

        boolean verbose = false;
        if (logging != null) {
            Object o = logging.getConfigValue(LoggingKeys.selection_failure, false);
            verbose = (o instanceof Boolean b) ? b : Boolean.parseBoolean(o.toString());
        }

        Shape<?> shape = region.getShape();
        VerticalAdjustor<?> vert = region.getVert();

        Object o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
        Set<String> unsafeBlocks = (o instanceof Collection<?> collection)
                ? collection.stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
                : new HashSet<>();

        int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();
        long maxAttemptsBase = performance.getNumber(PerformanceKeys.maxAttempts, 20).longValue();
        maxAttemptsBase = Math.max(maxAttemptsBase, 1);
        long maxAttempts = maxAttemptsBase;
        long maxBiomeChecks = Region.maxBiomeChecksPerGen * maxAttempts;
        if (!defaultBiomes) maxBiomeChecks *= 10;

        boolean biomeRecall = Boolean.parseBoolean(
                performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());
        boolean biomeRecallForced = Boolean.parseBoolean(
                performance.getConfigValue(PerformanceKeys.biomeRecallForced, false).toString());

        State state = new State();
        state.region = region;
        state.biomeNames = biomeNames;
        state.defaultBiomes = defaultBiomes;
        state.verbose = verbose;
        state.shape = shape;
        state.vert = vert;
        state.unsafeBlocks = unsafeBlocks;
        state.safetyRadius = safetyRadius;
        state.maxAttempts = maxAttempts;
        state.maxBiomeChecks = maxBiomeChecks;
        state.biomeRecall = biomeRecall;
        state.biomeRecallForced = biomeRecallForced;
        state.resolution = resolution;
        state.maxAttemptsBase = maxAttemptsBase;
        state.performance = performance;

        CompletableFuture<GenerationResult> resultFuture = new CompletableFuture<>();
        LocationSearchTask worker = new LocationSearchTask(state, resultFuture, 1, 0);
        RTP.serverAccessor.getScheduler().runTaskAsynchronously(worker);
        return resultFuture;
    }

    private static class State {
        Region region;
        Set<String> biomeNames;
        boolean defaultBiomes;
        boolean verbose;
        Shape<?> shape;
        VerticalAdjustor<?> vert;
        Set<String> unsafeBlocks;
        int safetyRadius;
        long maxAttempts;
        long maxBiomeChecks;
        boolean biomeRecall;
        boolean biomeRecallForced;
        long resolution;
        long maxAttemptsBase;
        ConfigParser<PerformanceKeys> performance;
    }

    // Natively flattens the search process into an iterative task to prevent StackOverflows
    private static class LocationSearchTask implements Runnable {
        private final State state;
        private final CompletableFuture<GenerationResult> resultFuture;
        private long i;
        private long biomeChecks;

        @AsyncThread
        public LocationSearchTask(State state, CompletableFuture<GenerationResult> resultFuture, long i, long biomeChecks) {
            this.state = state;
            this.resultFuture = resultFuture;
            this.i = i;
            this.biomeChecks = biomeChecks;
        }

        // Pass a flag telling the task if it is currently safe to loop inline
        @AsyncThread
        private void reschedule(boolean isCurrentlyAsync) {
            if (isCurrentlyAsync) {
                // We know we are on the async pool. Execute instantly to save overhead.
                this.run();
            } else {
                // We are on a tick thread (or an unpredictable future completion thread).
                // Bounce back to the async pool to protect the Watchdog.
                RTP.serverAccessor.getScheduler().runTaskAsynchronously(this);
            }
        }

        @Override
        @AsyncThread
        public void run() {
            try {
                // Use a while loop to instantly handle math failures without deep recursion
                while (i <= state.maxAttempts && biomeChecks < state.maxBiomeChecks) {
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
                                int nextInt = ThreadLocalRandom.current().nextInt(biomes.size());
                                Map.Entry<Long, Long> entry = biomes.get(nextInt);
                                l = entry.getKey() + ThreadLocalRandom.current().nextLong(entry.getValue());
                            } else if (state.biomeRecallForced) {
                                resultFuture.complete(new GenerationResult(null, i, null));
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
                    RTPWorld<?> world = state.region.getWorld();
                    // TODO: THREAD-VIOLATION - Requires async bridge: getBiome() is @RegionThread but called here on @AsyncThread
                    String currBiome = world.getBiome(blockX, (state.vert.minY() + state.vert.maxY()) / 2, blockZ).toUpperCase();

                    // Instantly retry on the current thread! No recursion, no queueing.
                    if (!state.biomeNames.contains(currBiome)) {
                        if (state.shape instanceof MemoryShape && state.defaultBiomes && state.biomeRecall) {
                            ((MemoryShape<?>) state.shape).addBadLocation(l);
                        }
                        i++;
                        biomeChecks++;
                        continue;
                    }

                    WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());
                    if (!border.isInside().apply(new io.github.dailystruggle.rtp.api.world.RTPLocation(
                            world, blockX, (state.vert.maxY() + state.vert.minY()) / 2, blockZ))) {
                        i++;
                        reschedule(true);
                        continue;
                    }

                    final long finalL = l;
                    final int cx = select[0];
                    final int cz = select[1];

                    // Request the chunk. We break the while loop by returning,
                    // which cleanly suspends this worker thread until the future fires.
                    world.getChunkAtAsync(cx, cz).thenAccept(chunkSet -> {
                        try {
                            if (chunkSet == null) {
                                this.i++;
                                reschedule(false);
                                return;
                            }

                            // THE TRAFFIC COP: Route the internal block checks to the Region Thread
                            io.github.dailystruggle.rtp.api.world.RTPLocation targetLoc =
                                    new io.github.dailystruggle.rtp.api.world.RTPLocation(world, blockX, 0, blockZ);

                            RTP.serverAccessor.getScheduler().runTask(targetLoc, () -> {
                                try {
                                    long chunkKey = ((long) cx & 0xffffffffL | ((long) cz << 32));
                                    RTPChunk<?> chunk = world.getCachedChunk(chunkKey);
                                    if (chunk == null) {
                                        this.i++;
                                        reschedule(false);
                                        return;
                                    }

                                    MutableRTPCoords res = new MutableRTPCoords(world.name(), 0, 0, 0);
                                    if (!state.vert.adjust(chunk, res)) {
                                        if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                                            ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                                        }
                                        this.i++;
                                        reschedule(false); // Safely queues async, because we are guaranteed to be on a TickThread here
                                        return;
                                    }

                                    int finalX = res.x;
                                    int finalY = res.y;
                                    int finalZ = res.z;
                                    String resBiome = world.getBiome(finalX, finalY, finalZ).toUpperCase();

                                    if (!state.biomeNames.contains(resBiome)) {
                                        if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                                            ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                                        }
                                        this.i++;
                                        this.biomeChecks++;
                                        reschedule(false);
                                        return;
                                    }

                                    int safe = state.safetyRadius;
                                    if (safe <= 0) {
                                        GlobalRegionVerifiers.checkGlobalRegionVerifiers(res.toImmutable()).thenAccept(pass -> {
                                            try {
                                                if (pass) {
                                                    if (state.shape instanceof MemoryShape && finalL > 0) {
                                                        ((MemoryShape<?>) state.shape).addBiomeLocation(finalL, state.resolution, resBiome);
                                                    }

                                                    // SUCCESS! Pass it up the pipeline.
                                                    resultFuture.complete(new GenerationResult(res.toImmutable(), i, null));
                                                } else {
                                                    if (state.shape instanceof MemoryShape) {
                                                        ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                                                    }
                                                    this.i++;
                                                    reschedule(false);
                                                }
                                            } catch (Exception e) {
                                                io.github.dailystruggle.rtp.common.tools.SupportLogger.logException(Level.SEVERE, "Error in GlobalRegionVerifiers callback", e);
                                                this.i++;
                                                reschedule(false);
                                            }
                                        });
                                        return;
                                    }

                                    List<CompletableFuture<Long>> chunkFutures = new ArrayList<>();
                                    int minCx = (finalX - safe) >> 4;
                                    int maxCx = (finalX + safe) >> 4;
                                    int minCz = (finalZ - safe) >> 4;
                                    int maxCz = (finalZ + safe) >> 4;

                                    for (int x = minCx; x <= maxCx; x++) {
                                        for (int z = minCz; z <= maxCz; z++) {
                                            chunkFutures.add(world.getChunkAt(x, z));
                                        }
                                    }

                                    CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                                        try {
                                            boolean allSafe = true;
                                            for (int x = finalX - safe; x <= finalX + safe; x++) {
                                                int ccx = x >> 4;
                                                int xx = x & 15;
                                                for (int z = finalZ - safe; z <= finalZ + safe; z++) {
                                                    int ccz = z >> 4;
                                                    int zz = z & 15;
                                                    long key = ((long) ccx & 0xffffffffL) | (((long) ccz & 0xffffffffL) << 32);
                                                    RTPChunk<?> chunk1 = world.getCachedChunk(key);
                                                    if (chunk1 == null) {
                                                        allSafe = false;
                                                        break;
                                                    }
                                                    for (int y = finalY - safe; y <= finalY + safe; y++) {
                                                        if (y > world.getMaxHeight() || y < world.getMinHeight()) continue;
                                                        if (!chunk1.isSafe(xx, y, zz, state.unsafeBlocks)) {
                                                            allSafe = false;
                                                            break;
                                                        }
                                                    }
                                                    if (!allSafe) break;
                                                }
                                                if (!allSafe) break;
                                            }

                                            if (allSafe) {
                                                GlobalRegionVerifiers.checkGlobalRegionVerifiers(res.toImmutable()).thenAccept(pass -> {
                                                    try {
                                                        if (pass) {
                                                            if (state.shape instanceof MemoryShape && finalL > 0) {
                                                                ((MemoryShape<?>) state.shape).addBiomeLocation(finalL, state.resolution, resBiome);
                                                            }
                                                            long viewDistanceRadius = state.performance.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
                                                            int radius = Math.max(state.safetyRadius, (int) viewDistanceRadius);
                                                            List<CompletableFuture<Long>> chunks = new ArrayList<>();
                                                            for (int x = -radius; x <= radius; x++) {
                                                                for (int z = -radius; z <= radius; z++) {
                                                                    chunks.add(world.getChunkAt((finalX >> 4) + x, (finalZ >> 4) + z));
                                                                }
                                                            }
                                                            ChunkSet verifiedChunks = new ChunkSet(world, finalX >> 4, finalZ >> 4, chunks, new CompletableFuture<>());
                                                            resultFuture.complete(new GenerationResult(res.toImmutable(), i, verifiedChunks));
                                                        } else {
                                                            if (state.shape instanceof MemoryShape) {
                                                                ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                                                            }
                                                            this.i++;
                                                            reschedule(false);
                                                        }
                                                    } catch (Exception e) {
                                                        io.github.dailystruggle.rtp.common.tools.SupportLogger.logException(Level.SEVERE, "Error in nested GlobalRegionVerifiers callback", e);
                                                        this.i++;
                                                        reschedule(false);
                                                    }
                                                });
                                            } else {
                                                if (state.shape instanceof MemoryShape) {
                                                    ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                                                }
                                                this.i++;
                                                reschedule(false);
                                            }
                                        } catch (Exception e) {
                                            io.github.dailystruggle.rtp.common.tools.SupportLogger.logException(Level.SEVERE, "Error in chunkFutures callback", e);
                                            this.i++;
                                            reschedule(false);
                                        }
                                    });

                                } catch (Exception e) {
                                    SendMessage.log(Level.SEVERE, "Failed to generate location!", e);
                                    this.i++;
                                    reschedule(false);
                                }
                            });
                        } catch (Exception e) {
                            SendMessage.log(Level.SEVERE, "Failed to generate location in getChunkAtAsync callback!", e);
                            this.i++;
                            reschedule(false);
                        }
                    });

                    // SUSPEND the while loop cleanly! It will resume asynchronously when the chunk loads.
                    return;
                }

                // If the while loop naturally ends because maxAttempts were reached
                resultFuture.complete(new GenerationResult(null, i, null));

            } catch (Exception e) {
                SendMessage.log(Level.SEVERE, "Failed to generate location!", e);
                resultFuture.complete(null);
            }
        }
    }

}

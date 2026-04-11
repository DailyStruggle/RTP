package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class FoliaLocationGenerator implements ILocationGenerator {

    @Override
    public GenerationResult getLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region)) return null;
        return LocationGenerator.getLocation((Region) region, context);
    }

    @Override
    public GenerationResult generateLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region)) return null;
        return LocationGenerator.generateLocation((Region) region, context);
    }

    @Override
    public GenerationResult getLocation(Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
        if (!(region instanceof Region)) return null;
        return LocationGenerator.getLocation((Region) region, sender, player, biomeNames);
    }

    @Override
    public GenerationResult getLocation(Object region, Set<String> biomeNames) {
        if (!(region instanceof Region)) return null;
        return LocationGenerator.getLocation((Region) region, biomeNames);
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(GenerationContext context) {
        RTPWorld<?> rtpWorld = context.player().getLocation().world();
        Region region = RTP.selectionAPI.getRegion(rtpWorld);

        long resolution = Math.max(1L, region.getSettings().spatialResolution());

        ConfigParser<PerformanceKeys> performance =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        ConfigParser<LoggingKeys> logging =
                (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);

        Set<String> biomeNames = context.biomeNames();
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

        return attempt(state, 1, 0);
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

    private CompletableFuture<GenerationResult> attempt(State state, long i, long biomeChecks) {
        if (i > state.maxAttempts || biomeChecks >= state.maxBiomeChecks) {
            return CompletableFuture.completedFuture(new GenerationResult(null, i, null));
        }

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
                    return CompletableFuture.completedFuture(new GenerationResult(null, i, null));
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
        String currBiome = world.getBiome(blockX, (state.vert.minY() + state.vert.maxY()) / 2, blockZ).toUpperCase();

        if (!state.biomeNames.contains(currBiome)) {
            if (state.shape instanceof MemoryShape && state.defaultBiomes && state.biomeRecall) {
                ((MemoryShape<?>) state.shape).addBadLocation(l);
            }
            return attempt(state, i + 1, biomeChecks + 1);
        }

        WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());
        if (!border.isInside().apply(new io.github.dailystruggle.rtp.api.world.RTPLocation(
                world, blockX, (state.vert.maxY() + state.vert.minY()) / 2, blockZ))) {
            return attempt(state, i + 1, biomeChecks);
        }

        final long finalL = l;
        final int cx = select[0];
        final int cz = select[1];

        return RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, cx, cz).thenCompose(chunkKey -> {
            if (chunkKey == null) return attempt(state, i + 1, biomeChecks);
            RTPChunk<?> chunk = world.getCachedChunk(chunkKey);
            if (chunk == null) return attempt(state, i + 1, biomeChecks);

            MutableRTPCoords res = new MutableRTPCoords(world.name(), 0, 0, 0);
            if (!state.vert.adjust(chunk, res)) {
                if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                    ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                }
                return attempt(state, i + 1, biomeChecks);
            }

            int finalX = res.x;
            int finalY = res.y;
            int finalZ = res.z;
            String resBiome = world.getBiome(finalX, finalY, finalZ).toUpperCase();

            if (!state.biomeNames.contains(resBiome)) {
                if (state.defaultBiomes && state.shape instanceof MemoryShape && state.biomeRecall) {
                    ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                }
                return attempt(state, i + 1, biomeChecks + 1);
            }

            int safe = state.safetyRadius;
            if (safe <= 0) {
                return GlobalRegionVerifiers.checkGlobalRegionVerifiers(res.toImmutable()).thenCompose(pass -> {
                    if (pass) {
                        if (state.shape instanceof MemoryShape && finalL > 0) {
                            ((MemoryShape<?>) state.shape).addBiomeLocation(finalL, state.resolution, resBiome);
                        }
                        long viewDistanceRadius = state.performance.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
                        ChunkSet verifiedChunks = state.region.chunkManager.chunks(res.toImmutable(), Math.max(state.safetyRadius, (int) viewDistanceRadius));
                        return CompletableFuture.completedFuture(new GenerationResult(res.toImmutable(), i, verifiedChunks));
                    } else {
                        if (state.shape instanceof MemoryShape) {
                            ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                        }
                        return attempt(state, i + 1, biomeChecks);
                    }
                });
            }

            List<CompletableFuture<Long>> chunkFutures = new ArrayList<>();
            int minCx = (finalX - safe) >> 4;
            int maxCx = (finalX + safe) >> 4;
            int minCz = (finalZ - safe) >> 4;
            int maxCz = (finalZ + safe) >> 4;

            for (int x = minCx; x <= maxCx; x++) {
                for (int z = minCz; z <= maxCz; z++) {
                    chunkFutures.add(RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, x, z));
                }
            }

            return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).thenCompose(v -> {
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
                    return GlobalRegionVerifiers.checkGlobalRegionVerifiers(res.toImmutable()).thenCompose(pass -> {
                        if (pass) {
                            if (state.shape instanceof MemoryShape && finalL > 0) {
                                ((MemoryShape<?>) state.shape).addBiomeLocation(finalL, state.resolution, resBiome);
                            }
                            long viewDistanceRadius = state.performance.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
                            ChunkSet verifiedChunks = state.region.chunkManager.chunks(res.toImmutable(), Math.max(state.safetyRadius, (int) viewDistanceRadius));
                            return CompletableFuture.completedFuture(new GenerationResult(res.toImmutable(), i, verifiedChunks));
                        } else {
                            if (state.shape instanceof MemoryShape) {
                                ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                            }
                            return attempt(state, i + 1, biomeChecks);
                        }
                    });
                } else {
                    if (state.shape instanceof MemoryShape) {
                        ((MemoryShape<?>) state.shape).addBadLocation(finalL);
                    }
                    return attempt(state, i + 1, biomeChecks);
                }
            });
        });
    }
}

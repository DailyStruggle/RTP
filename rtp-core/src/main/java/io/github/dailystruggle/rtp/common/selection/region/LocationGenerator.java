package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
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
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class LocationGenerator implements ILocationGenerator {

    /**
     * RNG used for biome-recall entry selection. Defaults to {@link ThreadLocalRandom#current()}
     * at call time. Inject a seeded {@link Random} via {@link #setRng(Random)} in tests to make
     * location selection fully deterministic.
     */
    static Random rng = null;

    /** Returns the active RNG, falling back to {@link ThreadLocalRandom#current()}. */
    static Random rng() {
        return rng != null ? rng : ThreadLocalRandom.current();
    }

    /**
     * Injects a deterministic RNG. Pass {@code null} to restore {@link ThreadLocalRandom}
     * behaviour. Intended for unit tests only.
     */
    public static void setRng(Random rng) {
        LocationGenerator.rng = rng;
    }

    private static final Set<String> unsafeBlocks = new ConcurrentSkipListSet<>();
    private static final AtomicLong lastUpdate = new AtomicLong(0);
    private static final AtomicInteger safetyRadius = new AtomicInteger(0);

    // localized generic task for
    public enum FailTypes {
        biome,
        worldBorder,
        timeout,
        vert,
        safety,
        safetyExternal,
        misc
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region)) return CompletableFuture.completedFuture(null);
        return CompletableFuture.completedFuture(getLocation((Region) region, context));
    }

    @Override
    public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region)) return CompletableFuture.completedFuture(null);
        return CompletableFuture.completedFuture(generateLocation((Region) region, context));
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(
            Object region, RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        if (!(region instanceof Region)) return CompletableFuture.completedFuture(null);
        return CompletableFuture.completedFuture(getLocation((Region) region, sender, player, biomeNames));
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
        if (!(region instanceof Region)) return CompletableFuture.completedFuture(null);
        return CompletableFuture.completedFuture(getLocation((Region) region, biomeNames));
    }

    public static GenerationResult getLocation(Region region, GenerationContext context) {
        return getLocation(region, context.sender(), context.player(), context.biomeNames());
    }

    public static GenerationResult generateLocation(Region region, GenerationContext context) {
        return getLocation(region, context.biomeNames());
    }

    /**
     * getLocation - get a location from cache or generate one
     *
     * @param region region to generate from
     * @param sender command sender
     * @param player player to teleport
     * @param biomeNames optional set of biomes to filter by
     * @return location and number of attempts
     */
    public static GenerationResult getLocation(
            Region region, RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        RTPLocation pair = null;
        ChunkSet chunkSet = null;

        region.getShape(); // validate shape before using cache

        UUID playerId = player.uuid();

        boolean custom = biomeNames != null && !biomeNames.isEmpty();

        while (!custom) {
            CompletableFuture<RTPLocation> poll = region.queueManager.poll(playerId);
            if (poll == null) {
                break;
            }
            try {
                pair = poll.get();
            } catch (InterruptedException | ExecutionException e) {
                RTP.log(Level.WARNING, e.getMessage(), e);
                pair = null;
            }

            if (pair != null) {
                RTPCoords left = pair.coords();
                if (left != null) {
                    boolean pass = true;
                    RTPWorld<?> world = region.getWorld();
                    int cx = left.x() >> 4;
                    int cz = left.z() >> 4;
                    ChunkSet ticket;
                    ChunkReservation reservation = pair.reservation();
                    boolean temporaryReservation = false;
                    if (reservation != null) {
                        ticket = reservation.getChunkSet();
                    } else {
                        try {
                            ticket = world.getChunkAtAsync(cx, cz).get();
                            reservation = new ChunkReservation(ticket, world);
                            temporaryReservation = true;
                        } catch (InterruptedException | ExecutionException e) {
                            return new GenerationResult(null, 1, null);
                        }
                    }

                    try {
                        CompletableFuture<Long> chunkAt = ticket.chunks().getFirst();
                        Long chunkKey = chunkAt.get();
                        RTPChunk<?> chunk = (chunkKey != null) ? world.getCachedChunk(chunkKey) : null;

                        if (chunk == null) {
                            chunkKey = world.getChunkAt(cx, cz).get(5, java.util.concurrent.TimeUnit.SECONDS);
                            if (chunkKey != null) {
                                chunk = world.getCachedChunk(chunkKey);
                            }
                        }

                        if (chunk == null) {
                            pass = false;
                        } else {
                            // verify
                            try {
                                long t = System.currentTimeMillis();
                                long dt = t - lastUpdate.get();
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
                                    }
                                    lastUpdate.set(t);
                                    safetyRadius.set(safety.getNumber(SafetyKeys.safetyRadius, 0).intValue());
                                }

                                int safe = safetyRadius.get();
                                int L = safe * 2 + 1;
                                RTPChunk[] localChunks = new RTPChunk[L * L];
                                int centerChunkX = chunk.x();
                                int centerChunkZ = chunk.z();
                                localChunks[safe * L + safe] = chunk;
                                // REQ-RTP-S-005 Stale-Chunk Guard (ADR-015): the center chunk was loaded
                                // async above, but the getLocation(...) call may have sat on the
                                // AsyncTaskProcessing pipe long enough for Folia's native chunk GC to
                                // unload it before we reach the safetyCheck. A subsequent chunk1.isSafe
                                // call on an unloaded chunk can force a synchronous load. Detect and
                                // reject the candidate (the poll loop will pick the next one).
                                boolean centerStillLoaded = world.isChunkLoaded(cx, cz);
                                if (!centerStillLoaded) {
                                    RTP.log(Level.FINE,
                                            "[RTP] Stale center chunk on safetyCheck entry ("
                                                    + world.name() + " " + cx + "," + cz
                                                    + "); rejecting candidate.");
                                    pass = false;
                                }
                                safetyCheck:
                                for (int x = left.x() - safe; x <= left.x() + safe; x++) {
                                    if (!centerStillLoaded) break safetyCheck;
                                    int chunkX = x >> 4;
                                    int xx = x & 15;
                                    int dcX = chunkX - centerChunkX;

                                    for (int z = left.z() - safe; z <= left.z() + safe; z++) {
                                        int chunkZ = z >> 4;
                                        int zz = z & 15;
                                        int dcZ = chunkZ - centerChunkZ;

                                        int index = (dcX + safe) * L + (dcZ + safe);
                                        RTPChunk<?> chunk1 = localChunks[index];
                                        if (chunk1 == null) {
                                            try {
                                                Long key = world.getChunkAt(chunkX, chunkZ).get();
                                                chunk1 = world.getCachedChunk(key);
                                                localChunks[index] = chunk1;
                                            } catch (InterruptedException | ExecutionException e) {
                                                pass = false;
                                                break safetyCheck;
                                            }
                                        }

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
                        }

                        if (pass && GlobalRegionVerifiers.checkGlobalRegionVerifiers(left).join()) {
                            return new GenerationResult(left, pair.attempts(), reservation.transferOwnership(), reservation);
                        }
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        RTP.log(Level.WARNING, e.getMessage(), e);
                    } finally {
                        if (reservation != null) {
                            reservation.close();
                        }
                    }
                }
            }
        }

        if (custom || sender.hasPermission("rtp.unqueued")) {
            GenerationResult res = getLocation(region, biomeNames);
            if (res != null) {
                chunkSet = res.verifiedChunks();
                long attempts = res.attempts();
                RTPCoords coords = res.coords();
                TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
                if (data != null && !data.completed) {
                    data.attempts = attempts;
                }
                return new GenerationResult(coords, attempts, chunkSet);
            }
        } else {
            RTP.getInstance().processingPlayers.add(playerId);
            TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
            if (data == null) {
                data = new TeleportData();
                io.github.dailystruggle.rtp.common.tools.MemoryTracker.track(data, "TeleportData-" + playerId.toString(), 120000L);
                data.sender = (sender != null) ? sender : player;
                data.completed = false;
                data.time = System.currentTimeMillis();
                data.delay = sender.delay();
                data.targetRegion = region;
                data.originalCoords =
                        new RTPCoords(
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
            RTP.serverAccessor.sendMessage(playerId, MessagesKeys.queueUpdate);
        }
        return null;
    }

    /**
     * getLocation - generate a location with biome requirements
     *
     * @param region region to generate from
     * @param biomeNames set of biomes to filter by
     * @return location and number of attempts
     */
    @Nullable
    public static GenerationResult getLocation(Region region, @Nullable Set<String> biomeNames) {
        long resolution = Math.max(1L, region.getSettings().spatialResolution());

        boolean defaultBiomes = false;
        ConfigParser<PerformanceKeys> performance =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        ConfigParser<LoggingKeys> logging =
                (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
        Object o;
        if (biomeNames == null || biomeNames.isEmpty()) {
            defaultBiomes = true;
            o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
            boolean whitelist = (o instanceof Boolean b) ? b : Boolean.parseBoolean(o.toString());

            o = safety.getConfigValue(SafetyKeys.biomes, null);
            List<String> biomeList =
                    (o instanceof List<?> list)
                            ? list.stream().map(Object::toString).toList()
                            : null;
            Set<String> biomeSet =
                    (biomeList == null)
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
            o = logging.getConfigValue(LoggingKeys.selection_failure, false);
            if (o instanceof Boolean b) {
                verbose = b;
            } else {
                verbose = Boolean.parseBoolean(o.toString());
            }
        }

        Shape<?> shape = region.getShape();
        if (shape == null) {
            new IllegalStateException("[RTP] invalid state, null shape").printStackTrace();
            return null;
        }

        VerticalAdjustor<?> vert = region.getVert();
        if (vert == null) {
            new IllegalStateException("[RTP] invalid state, null vert").printStackTrace();
            return null;
        }

        o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
        Set<String> unsafeBlocks =
                (o instanceof Collection<?> collection)
                        ? collection
                        .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
                        : new HashSet<>();

        int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();

        long maxAttemptsBase = performance.getNumber(PerformanceKeys.maxAttempts, 20).longValue();
        maxAttemptsBase = Math.max(maxAttemptsBase, 1);
        long maxAttempts = maxAttemptsBase;
        long maxBiomeChecks = Region.maxBiomeChecksPerGen * maxAttempts;
        if (!defaultBiomes) maxBiomeChecks *= 10;
        long biomeChecks = 0L;

        RTPWorld<?> world = region.getWorld();

        Map<FailTypes, Map<String, Long>> failMap = new EnumMap<>(FailTypes.class);
        for (FailTypes f : FailTypes.values()) failMap.put(f, new HashMap<>());
        long worldBorderFails = 0;
        List<Map.Entry<Long, Long>> selections = new ArrayList<>();

        int finalX = 0, finalY = 0, finalZ = 0;
        boolean locationFound = false;
        long i = 1;

        boolean biomeRecall =
                Boolean.parseBoolean(
                        performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());
        boolean biomeRecallForced =
                Boolean.parseBoolean(
                        performance.getConfigValue(PerformanceKeys.biomeRecallForced, false).toString());

        MutableRTPCoords cursor = new MutableRTPCoords(world.name(), 0, 0, 0);
        for (; i <= maxAttempts; i++) {
            long l = -1;
            int[] select;
            if (shape instanceof MemoryShape<?> memoryShape) {
                memoryShape.flushAndRebuild(resolution);
                if (biomeRecall && !defaultBiomes) {
                    List<Map.Entry<Long, Long>> biomes = new ArrayList<>();
                    for (String biomeName : biomeNames) {
                        long[] keys = memoryShape.getBiomeKeys(biomeName);
                        long[] sums = memoryShape.getBiomePrefixSums(biomeName);
                        if (keys != null && sums != null) {
                            for (int k = 0; k < keys.length; k++) {
                                long prevSum = (k > 0) ? sums[k - 1] : 0L;
                                biomes.add(new AbstractMap.SimpleEntry<>(keys[k], sums[k] - prevSum));
                            }
                        }
                    }
                    Map.Entry<Long, Long> entry;
                    if (biomes.size() > 0) {
                        int nextInt = rng().nextInt(biomes.size());
                            entry = biomes.get(nextInt);
                            l = entry.getKey() + (long) (rng().nextDouble() * entry.getValue());
                    } else if (biomeRecallForced) {
                        new IllegalStateException(
                                "[RTP] invalid state, biome recall enabled but biomes are not in memory - "
                                        + Arrays.toString(biomeNames.toArray()))
                                .printStackTrace();
                        return new GenerationResult(null, i, null);
                    } else l = memoryShape.rand();
                } else {
                    l = memoryShape.rand();
                }

                select = memoryShape.locationToXZ(l);

            } else {
                select = shape.select();
            }

            int blockX = (select[0] << 4) + 8;
            int blockZ = (select[1] << 4) + 8;
            cursor.setXZ(blockX, blockZ);
            if (verbose) {
                //                if( shape instanceof MemoryShape ) selections.add( new
                // AbstractMap.SimpleEntry<>( (long ) selections.size(), l) );
                //                else selections.add( new AbstractMap.SimpleEntry<>( (long ) select[0], (
                // long ) select[1]) );
                selections.add(new AbstractMap.SimpleEntry<>((long) select[0], (long) select[1]));
            }

            String currBiome =
                    world.getBiome(blockX, (vert.minY() + vert.maxY()) / 2, blockZ).toUpperCase();

            for (;
                 biomeChecks < maxBiomeChecks && !biomeNames.contains(currBiome);
                 biomeChecks++, maxAttempts++, i++) {
                if (shape instanceof MemoryShape<?> memoryShape) {
                    if (defaultBiomes && biomeRecall) {
                        memoryShape.addBadLocation(l);
                    }
                    if (biomeRecall && !defaultBiomes) {
                        List<Map.Entry<Long, Long>> biomes = new ArrayList<>();
                        for (String biomeName : biomeNames) {
                            long[] keys = memoryShape.getBiomeKeys(biomeName);
                            long[] sums = memoryShape.getBiomePrefixSums(biomeName);
                            if (keys != null && sums != null) {
                                for (int k = 0; k < keys.length; k++) {
                                    long prevSum = (k > 0) ? sums[k - 1] : 0L;
                                    biomes.add(new AbstractMap.SimpleEntry<>(keys[k], sums[k] - prevSum));
                                }
                            }
                        }
                        Map.Entry<Long, Long> entry;
                        if (biomes.size() > 0) {
                            int nextInt = rng().nextInt(biomes.size());
                            entry = biomes.get(nextInt);
                            l = entry.getKey() + (long) (rng().nextDouble() * entry.getValue());
                        } else if (biomeRecallForced) {
                            new IllegalStateException(
                                    "[RTP] invalid state, biome recall enabled but biomes are not in memory - "
                                            + Arrays.toString(biomeNames.toArray()))
                                    .printStackTrace();
                            return new GenerationResult(null, i, null);
                        } else l = memoryShape.rand();
                    } else {
                        l = memoryShape.rand();
                    }

                    select = memoryShape.locationToXZ(l);
                } else {
                    select = shape.select();
                }

                blockX = (select[0] << 4) + 8;
                blockZ = (select[1] << 4) + 8;
                cursor.setXZ(blockX, blockZ);

                if (verbose) {
                    //                if( shape instanceof MemoryShape ) selections.add( new
                    // AbstractMap.SimpleEntry<>( (long ) selections.size(), l) );
                    //                else selections.add( new AbstractMap.SimpleEntry<>( (long ) select[0], (
                    // long ) select[1]) );
                    selections.add(new AbstractMap.SimpleEntry<>((long) select[0], (long) select[1]));
                }

                if (verbose) {
                    String key = "biome=" + currBiome;
                    failMap
                            .get(FailTypes.biome)
                            .compute(
                                    key,
                                    (s, aLong) -> {
                                        if (aLong == null) return 1L;
                                        return ++aLong;
                                    });
                }
                currBiome =
                        world.getBiome(blockX, (vert.minY() + vert.maxY()) / 2, blockZ);
            }
            if (biomeChecks >= maxBiomeChecks) break;

            WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());
            if (!border
                    .isInside()
                    .apply(
                            new io.github.dailystruggle.rtp.api.world.RTPLocation(
                                    world, blockX, (vert.maxY() + vert.minY()) / 2, blockZ))) {
                if (verbose) {
                    new IllegalStateException(
                            "worldborder check failed. region/selection is likely outside the worldborder")
                            .printStackTrace();
                }
                maxAttempts++;
                worldBorderFails++;
                if (worldBorderFails > 1000) {
                    new IllegalStateException(
                            "1000 worldborder checks failed. region/selection is likely outside the worldborder")
                            .printStackTrace();
                    return new GenerationResult(null, i, null);
                }
                if (verbose) {
                    failMap.get(FailTypes.worldBorder).put("OUTSIDE_BORDER", worldBorderFails);
                }
                continue;
            }

            int cx = select[0];
            int cz = select[1];
            ChunkSet ticket;
            try {
                ticket = world.getChunkAtAsync(cx, cz).get();
            } catch (InterruptedException | ExecutionException e) {
                continue;
            }
            RTPChunk<?> chunk;
            try {
                CompletableFuture<Long> cfChunk = ticket.chunks().get(0);
                // Bounded fetch
                Long key = cfChunk.get(5, java.util.concurrent.TimeUnit.SECONDS);
                chunk = (key != null) ? world.getCachedChunk(key) : null;

                if (chunk == null) {
                    try {
                        key = (Long) world.getChunkAt(cx, cz)
                                .get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        key = null;
                    }
                    if (key != null) {
                        chunk = world.getCachedChunk(key);
                    }
                }
            } catch (java.util.concurrent.TimeoutException | InterruptedException | ExecutionException e) {
                RTP.log(Level.WARNING, "Chunk load timed out or failed at " + cx + ", " + cz);
                continue;
            }

            if (chunk == null) {
                continue;
            }

            // REQ-RTP-S-005 Stale-Chunk Guard (ADR-015): vert.adjust(chunk) below invokes
            // chunk.isSafe(...) repeatedly for vertical scanning. If the chunk was evicted
            // by native GC between getChunkAtAsync() above and this line, those isSafe
            // reads can force a synchronous chunk load on a tick thread. Skip the candidate.
            if (!world.isChunkLoaded(cx, cz)) {
                RTP.log(Level.FINE,
                        "[RTP] Stale chunk before vert.adjust ("
                                + world.name() + " " + cx + "," + cz
                                + "); rejecting candidate.");
                continue;
            }

            try {
                RTPCoords res = vert.adjust(chunk);
                if (res == null) {
                    if (defaultBiomes && shape instanceof MemoryShape && biomeRecall) {
                        ((MemoryShape<?>) shape).addBadLocation(l);
                    }
                    if (verbose) {
                        failMap
                                .get(FailTypes.vert)
                                .compute("biome=" + currBiome, (s, aLong) -> (aLong == null) ? (1L) : (++aLong));
                    }
                    continue;
                }

                finalX = res.x();
                finalY = res.y();
                finalZ = res.z();
                currBiome = world.getBiome(finalX, finalY, finalZ).toUpperCase();

                if (!biomeNames.contains(currBiome)) {
                    biomeChecks++;
                    maxAttempts++;
                    if (defaultBiomes && shape instanceof MemoryShape && biomeRecall) {
                        ((MemoryShape<?>) shape).addBadLocation(l);
                    }

                    if (verbose) {
                        failMap
                                .get(FailTypes.biome)
                                .compute("biome=" + currBiome, (s, aLong) -> (aLong == null) ? 1L : ++aLong);
                    }
                    continue;
                }

                boolean pass = true;

                // todo: waterlogged check
                RTPChunk<?>[] localChunks = new RTPChunk[(safetyRadius * 2 + 1) * (safetyRadius * 2 + 1)];
                int centerChunkX = chunk.x();
                int centerChunkZ = chunk.z();
                int L = safetyRadius * 2 + 1;
                localChunks[safetyRadius * L + safetyRadius] = chunk;
                // REQ-RTP-S-005 Stale-Chunk Guard (ADR-015): native chunk GC may have unloaded
                // the center chunk between the async load above and this safetyCheck. On a
                // stale detection, reject the candidate rather than letting chunk1.isSafe
                // force a synchronous load on a tick thread.
                boolean centerStillLoaded = world.isChunkLoaded(cx, cz);
                if (!centerStillLoaded) {
                    RTP.log(Level.FINE,
                            "[RTP] Stale center chunk on safetyCheck entry ("
                                    + world.name() + " " + cx + "," + cz
                                    + "); rejecting candidate.");
                    pass = false;
                }
                safetyCheck:
                for (int x = finalX - safetyRadius; x <= finalX + safetyRadius; x++) {
                    if (!centerStillLoaded) break safetyCheck;
                    int chunkX = x >> 4;
                    int xx = x & 15;
                    int dcX = chunkX - centerChunkX;

                    for (int z = finalZ - safetyRadius; z <= finalZ + safetyRadius; z++) {
                        int chunkZ = z >> 4;
                        int zz = z & 15;
                        int dcZ = chunkZ - centerChunkZ;

                        int index = (dcX + safetyRadius) * L + (dcZ + safetyRadius);
                        RTPChunk<?>chunk1 = localChunks[index];
                        if (chunk1 == null) {
                            try {
                                Long key = (Long) world.getChunkAt(chunkX, chunkZ).get();
                                chunk1 = region.getWorld().getCachedChunk(key);
                                localChunks[index] = chunk1;
                            } catch (InterruptedException | ExecutionException e) {
                                RTP.log(Level.WARNING, e.getMessage(), e);
                                pass = false;
                                break safetyCheck;
                            }
                        }

                        if (chunk1 == null) {
                            pass = false;
                            break safetyCheck;
                        }

                        for (int y = finalY - safetyRadius; y <= finalY + safetyRadius; y++) {
                            if (y > region.getWorld().getMaxHeight() || y < region.getWorld().getMinHeight()) continue;
                            if (!chunk1.isSafe(xx, y, zz, unsafeBlocks)) {
                                pass = false;
                                break safetyCheck;
                            }
                        }
                    }
                }

                pass &= GlobalRegionVerifiers.checkGlobalRegionVerifiers(cursor.toImmutable()).join();

                if (!pass) {
                    if (verbose)
                        failMap
                                .get(FailTypes.misc)
                                .compute(
                                        "location=" + "(" + finalX + "," + finalY + "," + finalZ,
                                        (s, aLong) -> (aLong == null) ? 1L : ++aLong);
                    if (shape instanceof MemoryShape) {
                        ((MemoryShape<?>) shape).addBadLocation(l);
                    }
                    continue;
                }

                if (shape instanceof MemoryShape && l > 0) {
                    ((MemoryShape<?>) shape).addBiomeLocation(l, resolution, currBiome);
                }
                locationFound = true;

                break;
            } finally {
                // ticket cleanup if needed
            }
        }

        if (verbose && i >= maxAttempts || i > maxAttemptsBase * Region.maxBiomeChecksPerGen) {
            RTP.log(
                    Level.INFO,
                    "#00ff80[RTP] ["
                            + region.name
                            + "] failed to generate a location within "
                            + maxAttempts
                            + " tries. Adjust your configuration.");
            for (Map.Entry<FailTypes, Map<String, Long>> mapEntry : failMap.entrySet()) {
                Map<String, Long> map = mapEntry.getValue();
                String[] output = new String[map.size()];
                int pos = 0;
                long count = 0;
                for (Map.Entry<String, Long> entry : map.entrySet()) {
                    output[pos] =
                            "#00ff80[RTP] ["
                                    + region.name
                                    + "] "
                                    + " cause="
                                    + mapEntry.getKey()
                                    + " "
                                    + entry.getKey()
                                    + " fails="
                                    + entry.getValue();
                    count += entry.getValue();
                    pos++;
                }
                RTP.log(
                        Level.INFO,
                        "#00ff80[RTP] [" + region.name + "] " + " cause=" + mapEntry.getKey() + " fails=" + count);
                for (String out : output) {
                    RTP.log(Level.INFO, out);
                }
            }

            StringBuilder selectionsStr = new StringBuilder();
            boolean first = true;
            selectionsStr = selectionsStr.append("{");
            for (Map.Entry<Long, Long> entry : selections) {
                if (!first) {
                    selectionsStr = selectionsStr.append(",");
                }
                selectionsStr =
                        selectionsStr
                                .append("(")
                                .append(entry.getKey())
                                .append(",")
                                .append(entry.getValue())
                                .append(")");
            }
            selectionsStr = selectionsStr.append("}");
            RTP.log(Level.INFO, "#0f0080[RTP] [" + region.name + "] selections: " + selectionsStr);
        }

        i = Math.min(i, maxAttempts);

        if (!locationFound) return new GenerationResult(null, i, null);
        cursor.setXZ(finalX, finalZ);
        cursor.setY(finalY);
        RTPCoords resCoords = cursor.toImmutable();

        long viewDistanceRadius = performance.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();
        int radius = Math.max(safetyRadius, (int) viewDistanceRadius);
        int cx = resCoords.x() >> 4;
        int cz = resCoords.z() >> 4;
        List<CompletableFuture<Long>> chunks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                chunks.add(world.getChunkAt(cx + x, cz + z));
            }
        }
        ChunkSet verifiedChunks = new ChunkSet(world, cx, cz, chunks, new CompletableFuture<>());

        return new GenerationResult(resCoords, i, verifiedChunks);
    }
}

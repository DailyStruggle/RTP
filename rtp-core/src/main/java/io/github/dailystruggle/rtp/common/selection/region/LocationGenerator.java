package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.safety.SafetyCompilationCache;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
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

    /**
     * ADR-016 §13.3 (2026-04-20 revision): the pre-chunk-load biome pre-check is
     * disabled by default because it forces a live `world.getBiome(...)` call
     * BEFORE any chunk load for the candidate (no Anvil view has been published
     * yet for that key), producing a continuous stream of
     * `reason=no-view-cached` fallthroughs on pregenerated vanilla worlds without
     * any correctness benefit — the post-load read later in the candidate loop
     * is authoritative and runs through the §13.1 three-tier precedence chain.
     *
     * <p>The pre-check code path is preserved intact under this constant so it
     * can be flipped back on without code archaeology if a future workload
     * (e.g. bounded biome-targeted search on giant regions) needs the
     * short-circuit optimisation.
     */
    private static final boolean PRE_CHUNK_BIOME_PRECHECK_ENABLED = false;

    // localized generic task for
    public enum FailTypes {
        biome,
        worldBorder,
        timeout,
        vert,
        safety,
        safetyExternal,
        nullChunk,
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
        RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] LocationGenerator.getLocation ENTER playerId=" + playerId
                + " region=" + (region != null ? region.name : "null")
                + " custom=" + custom
                + " biomeNames=" + biomeNames
                + " hasUnqueuedPerm=" + (sender != null && sender.hasPermission("rtp.unqueued"))
                + " thread=" + Thread.currentThread().getName());

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
                    RTPChunk<?> chunk = null;
                    Long chunkKey = null;

                    // ADR-016 §11 probe-first ordering (2026-04-20 follow-up):
                    // When the poll has no pre-acquired reservation, run the
                    // adapter's probe-first `getChunkAt` path BEFORE allocating
                    // a live ticket. On an anvil hit the subsequent
                    // `getBiome(finalX,finalY,finalZ)` routes through
                    // `anvilProbeSupport.takeCached` (tier 2 of ADR-016 §13.1)
                    // instead of falling through to the live getter. Only on a
                    // no-view outcome do we create a temporary reservation via
                    // `getChunkAtAsync`. See the parallel edit in
                    // `getLocation(Region, biomeNames)` below.
                    if (reservation != null) {
                        ticket = reservation.getChunkSet();
                    } else {
                        try {
                            chunkKey = world.getChunkAt(cx, cz)
                                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
                            if (chunkKey != null) {
                                chunk = world.getCachedChunk(chunkKey);
                            }
                        } catch (java.util.concurrent.TimeoutException | InterruptedException | ExecutionException ignored) {
                            // Fall through to the live-load path.
                        }

                        if (chunk == null) {
                            try {
                                ticket = world.getChunkAtAsync(cx, cz).get();
                                reservation = new ChunkReservation(ticket, world);
                                temporaryReservation = true;
                            } catch (InterruptedException | ExecutionException e) {
                                return new GenerationResult(null, 1, null);
                            }
                        } else {
                            // Anvil hit: no live ticket acquired. Synthesise an
                            // empty `ChunkSet` and a temporary reservation so
                            // the downstream `finally { reservation.close(); }`
                            // invariant holds. Closing a reservation over an
                            // empty chunk list is a no-op in MemoryTracker.
                            ticket = new ChunkSet(
                                    world, cx, cz,
                                    java.util.Collections.singletonList(
                                            CompletableFuture.completedFuture(chunkKey)),
                                    new CompletableFuture<>());
                            reservation = new ChunkReservation(ticket, world);
                            temporaryReservation = true;
                        }
                    }

                    try {
                        if (chunk == null) {
                            CompletableFuture<Long> chunkAt = ticket.chunks().getFirst();
                            chunkKey = chunkAt.get();
                            chunk = (chunkKey != null) ? world.getCachedChunk(chunkKey) : null;

                            if (chunk == null) {
                                chunkKey = world.getChunkAt(cx, cz).get(5, java.util.concurrent.TimeUnit.SECONDS);
                                if (chunkKey != null) {
                                    chunk = world.getCachedChunk(chunkKey);
                                }
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
                                        // ADR-017 / REQ-RTP-S-004: surface malformed safety
                                        // tokens to the log at WARNING. The cache's
                                        // one-time-sink semantics ensure we warn exactly
                                        // once per distinct raw-token set — repeated refresh
                                        // cycles against the same config will not spam.
                                        // We also preload the compiled set so that Slice 3a
                                        // tag expansion is memoized off the hot path.
                                        RTPServerAccessor accessor = RTPAPI.serverAccessor;
                                        Map<String, Set<String>> tagSnapshot =
                                                (accessor != null)
                                                        ? accessor.blockTagSnapshot()
                                                        : Collections.emptyMap();
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
            RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] LocationGenerator taking UNQUEUED fast-path playerId=" + playerId);
            GenerationResult res = getLocation(region, biomeNames);
            RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] LocationGenerator UNQUEUED fast-path result playerId=" + playerId + " resNull=" + (res == null));
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
            RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] LocationGenerator taking ENQUEUE branch playerId=" + playerId + " region=" + (region != null ? region.name : "null"));
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
            RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] LocationGenerator ENQUEUED playerId=" + playerId
                    + " queueSize=" + data.queueLocation
                    + " -> calling sendMessage(queueUpdate)");
            RTP.serverAccessor.sendMessage(playerId, MessagesKeys.queueUpdate);
            RTP.log(java.util.logging.Level.FINE, "[ENQUEUE_TRACE] LocationGenerator sendMessage(queueUpdate) RETURNED playerId=" + playerId);
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

        // BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md §4 step 5 (revised 2026-04-20):
        // The biome filter is evaluated against the user/config-supplied set directly,
        // with `biomeWhitelist` deciding the match polarity. Earlier revisions inverted
        // the blacklist against a world-level enumeration (`RTPServerAccessor#getBiomes`
        // or `AnvilRegionScanner.scanBiomes`) to materialise a "good-biomes" set; that
        // approach broke on cold start when the enumeration was empty or lossy (closed
        // vanilla `Biome` enum collapses Iris/Terra/datapack biomes). The direct
        // whitelist/blacklist evaluation is enumeration-free and therefore drift-proof.
        boolean biomeWhitelist;
        if (biomeNames == null || biomeNames.isEmpty()) {
            defaultBiomes = true;
            o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
            biomeWhitelist = (o instanceof Boolean b) ? b : Boolean.parseBoolean(o.toString());

            o = safety.getConfigValue(SafetyKeys.biomes, null);
            List<String> biomeList =
                    (o instanceof List<?> list)
                            ? list.stream().map(Object::toString).toList()
                            : null;
            biomeNames =
                    (biomeList == null)
                            ? new HashSet<>()
                            : biomeList.stream().map(String::toUpperCase).collect(Collectors.toSet());
        } else {
            // Caller-supplied biome set (e.g. `/rtp biome:plains`) is a whitelist by
            // definition: the caller asked for these biomes explicitly.
            biomeWhitelist = true;
            biomeNames = biomeNames.stream().map(String::toUpperCase).collect(Collectors.toSet());
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
            RTP.log(Level.WARNING, "[RTP] invalid state, null shape", new IllegalStateException());
            return null;
        }

        VerticalAdjustor<?> vert = region.getVert();
        if (vert == null) {
            RTP.log(Level.WARNING, "[RTP] invalid state, null vert", new IllegalStateException());
            return null;
        }

        o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
        Set<String> unsafeBlocks =
                (o instanceof Collection<?> collection)
                        ? collection
                        .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
                        : new HashSet<>();
        // ADR-017 / REQ-RTP-S-004: pregen path — surface malformed safety tokens at
        // WARNING via the SafetyCompilationCache's one-time-sink semantics.
        // Preloading the compiled set here also memoises the tag expansion for
        // subsequent candidate checks (Slice 3a).
        {
            RTPServerAccessor pregenAccessor = RTPAPI.serverAccessor;
            Map<String, Set<String>> pregenTagSnapshot =
                    (pregenAccessor != null)
                            ? pregenAccessor.blockTagSnapshot()
                            : Collections.emptyMap();
            SafetyCompilationCache.getOrCompile(
                    unsafeBlocks,
                    pregenTagSnapshot,
                    rejection ->
                            RTP.log(
                                    Level.WARNING,
                                    "[safety.yml] rejected unsafe-blocks token '"
                                            + rejection.rawToken()
                                            + "': "
                                            + rejection.reason()));
        }

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

            // ADR-016 §13.3 (2026-04-20 revision) — pre-chunk-load biome check retired.
            //
            // Rationale: the prior pre-check called `world.getBiome(...)` BEFORE any
            // chunk was loaded for this candidate, which forced `BukkitRTPWorld#getBiome`
            // / `FoliaRTPWorld#getBiome` to fall through to the live seed-synthesised
            // `world.getBiome(x,y,z)` (the Anvil cache has no entry before the probe
            // runs for this key). On pregenerated vanilla worlds where the selected
            // chunk falls outside the pregen radius, this produced a continuous stream
            // of `reason=no-view-cached` fallthroughs and zero `anvil-hits` in
            // `rtp test biome-source` without any correctness benefit.
            //
            // The authoritative biome check happens after the async chunk load at the
            // post-load read further down this loop (`world.getBiome(finalX, finalY,
            // finalZ)`), which is routed through the ADR-016 §13.1 three-tier
            // precedence chain (loaded chunk → AnvilChunkView → live getter) and is
            // therefore upgrade-drift-proof on every in-scope Bukkit-family adapter.
            //
            // The former pre-check block (seed-biome pre-filter + whitelist-driven
            // re-selection loop that could short-circuit a candidate before any chunk
            // load) is preserved below under `if (PRE_CHUNK_BIOME_PRECHECK_ENABLED)` so
            // it can be revived without code archaeology if a future workload needs it.
            String currBiome = "";

            //noinspection ConstantValue
            if (PRE_CHUNK_BIOME_PRECHECK_ENABLED) {
                final boolean vanillaPreCheck = world.isVanilla();
                boolean canPreCheck = vanillaPreCheck && !world.isChunkGenerated(select[0], select[1]);
                currBiome = canPreCheck
                        ? world.getBiome(blockX, (vert.minY() + vert.maxY()) / 2, blockZ).toUpperCase()
                        : "";

                for (;
                     canPreCheck
                             && biomeChecks < maxBiomeChecks
                             && (biomeNames.contains(currBiome) != biomeWhitelist);
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
                    canPreCheck = vanillaPreCheck && !world.isChunkGenerated(select[0], select[1]);
                    currBiome = canPreCheck
                            ? world.getBiome(blockX, (vert.minY() + vert.maxY()) / 2, blockZ)
                            : "";
                }
                if (biomeChecks >= maxBiomeChecks) break;
            }

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

            // ADR-016 §11 probe-first ordering (2026-04-20 follow-up):
            // Call `world.getChunkAt(cx, cz)` BEFORE any live async load so the
            // adapter's internal `shouldPrefilter → probeAndPublish` chain runs
            // and publishes an `AnvilChunkView` into `anvilProbeSupport` for
            // this chunk key. Only on a no-view outcome (UNKNOWN / gate-skip
            // with an evicted live chunk) do we fall through to
            // `getChunkAtAsync`, which acquires a live ticket + MemoryTracker
            // registration. Without this ordering, eager `getChunkAtAsync`
            // loaded every candidate live, `shouldPrefilter` correctly saw
            // `isChunkLoaded=true` and skipped the probe, and the subsequent
            // `world.getBiome(finalX,finalY,finalZ)` below at the post-load
            // read always fell through to the live seed-synthesised getter
            // with `reason=no-view-cached`, producing zero `anvil-hits` in
            // `rtp test biome-source` regardless of what was on disk.
            RTPChunk<?> chunk = null;
            Long probeKey = null;
            try {
                probeKey = world.getChunkAt(cx, cz)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (probeKey != null) {
                    chunk = world.getCachedChunk(probeKey);
                }
            } catch (java.util.concurrent.TimeoutException | InterruptedException | ExecutionException ignored) {
                // Fall through to the live-load path below.
            }

            ChunkSet ticket = null;
            if (chunk == null) {
                try {
                    ticket = world.getChunkAtAsync(cx, cz).get();
                } catch (InterruptedException | ExecutionException e) {
                    continue;
                }
                try {
                    CompletableFuture<Long> cfChunk = ticket.chunks().get(0);
                    // Bounded fetch
                    Long key = cfChunk.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    chunk = (key != null) ? world.getCachedChunk(key) : null;
                } catch (java.util.concurrent.TimeoutException | InterruptedException | ExecutionException e) {
                    RTP.log(Level.WARNING, "Chunk load timed out or failed at " + cx + ", " + cz);
                    continue;
                }
            }

            if (chunk == null) {
                if (verbose) {
                    failMap.get(FailTypes.nullChunk).compute("reason=asyncLoadNull", (s, aLong) -> (aLong == null) ? 1L : ++aLong);
                }
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
                // ADR-016 §13.1 follow-up (2026-04-20): ask the resolved chunk
                // for its own biome, not the world. On an anvil-backed chunk
                // this goes straight to the decoded `AnvilChunkView` we already
                // hold (bypassing `anvilProbeSupport.takeCached`, which can be
                // evicted between probe-publish and this read). On a live-
                // backed chunk this goes to the loaded block's biome (no
                // seed-synth fallthrough). Either way, `no-view-cached` can no
                // longer be logged at this point.
                currBiome = chunk.getBiome(finalX, finalY, finalZ).toUpperCase();

                if (biomeNames.contains(currBiome) != biomeWhitelist) {
                    biomeChecks++;
                    // Bounded-rerolling invariant (REQ-RTP-F-006): grow `maxAttempts` on a
                    // biome mismatch so a run of unlucky candidates does not prematurely
                    // exhaust the outer loop, but only while biome-check budget remains.
                    // Once `biomeChecks` saturates `maxBiomeChecks`, stop extending
                    // `maxAttempts` so an impossible-biome filter terminates instead of
                    // looping forever. The legacy pre-check enforced this bound inline;
                    // it must also be enforced here now that the pre-check is retired
                    // (ADR-016 §13.3, 2026-04-20).
                    if (biomeChecks < maxBiomeChecks) {
                        maxAttempts++;
                    }
                    if (defaultBiomes && shape instanceof MemoryShape && biomeRecall) {
                        ((MemoryShape<?>) shape).addBadLocation(l);
                    }

                    if (verbose) {
                        failMap
                                .get(FailTypes.biome)
                                .compute("biome=" + currBiome, (s, aLong) -> (aLong == null) ? 1L : ++aLong);
                    }
                    if (biomeChecks >= maxBiomeChecks) {
                        break;
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
                            if (verbose) {
                                failMap.get(FailTypes.nullChunk).compute("reason=neighborNull", (s, aLong) -> (aLong == null) ? 1L : ++aLong);
                            }
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

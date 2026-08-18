package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.safety.SafetyCompilationCache;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Immutable configuration + mutable counters for a single invocation of
 * {@link LocationGenerator#getLocationFuture(Region, Set)}. Kept as a
 * package-private helper so it doesn't leak into the public API.
 */
final class PregenState {

    final Region region;
    final RTPWorld<?> world;
    final Set<String> biomeNames;
    final boolean defaultBiomes;
    final boolean biomeWhitelist;
    final boolean verbose;
    final Shape<?> shape;
    final VerticalAdjustor<?> vert;
    final Set<String> unsafeBlocks;
    final int safetyRadius;
    /**
     * ADR-015 Folia-follow-up: bounded retry budget for the stale-chunk guard on
     * the live-backed branch. When a candidate's live chunk has been GC'd between
     * {@code getOrLoadChunk} resolving and the region-thread dispatch actually
     * running, re-request {@code getOrLoadChunk} up to this many times before
     * advancing the spiral index. Default {@code 2}.
     */
    final int staleChunkRetryLimit;
    final boolean biomeRecall;
    final boolean biomeRecallForced;
    /**
     * ADR-062 biome-probability weighting. When {@code true} and biome steering is active,
     * selects target biomes proportionally or with equal probability across spatial memory.
     */
    final boolean biomeWeighted;
    /**
     * ADR-062 per-biome relative weights. Maps upper-cased biome name to non-negative weight (default 1.0).
     */
    final Map<String, Double> biomeWeights;
    /**
     * ADR-062: full biome registry (upper-cased and namespaced names) from {@link RTPServerAccessor#getBiomes(RTPWorld)}.
     * Distinguishes reachable gray-space biomes from impossible biomes.
     */
    final Set<String> worldBiomeRegistry;
    /**
     * ADR-062: per-world biome sampling capability for coordinate-level biome probes.
     */
    final io.github.dailystruggle.rtp.api.world.BiomeSampleCapability biomeSampleCapability;
    final long resolution;
    final long maxAttemptsBase;
    final ConfigParser<PerformanceKeys> performance;

    // Mutable across the attempt loop
    long maxAttempts;
    long worldBorderFails = 0L;
    /**
     * Ceiling on {@link #maxAttempts} growth from free retries (bounded at {@code maxAttemptsBase * 100}).
     */
    final long maxAttemptsCeiling;

    final Map<LocationGenerator.FailTypes, Map<String, Long>> failMap;
    final List<Map.Entry<Long, Long>> selections = new ArrayList<>();
    /**
     * Per-attempt outcome breadcrumb trail recorded across {@code PregenTask} attempt lifecycle.
     */
    final List<String> attemptOutcomes = new ArrayList<>();

    private PregenState(
            Region region,
            RTPWorld<?> world,
            Set<String> biomeNames,
            boolean defaultBiomes,
            boolean biomeWhitelist,
            boolean verbose,
            Shape<?> shape,
            VerticalAdjustor<?> vert,
            Set<String> unsafeBlocks,
            int safetyRadius,
            int staleChunkRetryLimit,
            long maxAttemptsBase,
            long maxAttempts,
            boolean biomeRecall,
            boolean biomeRecallForced,
            boolean biomeWeighted,
            Map<String, Double> biomeWeights,
            Set<String> worldBiomeRegistry,
            io.github.dailystruggle.rtp.api.world.BiomeSampleCapability biomeSampleCapability,
            long resolution,
            ConfigParser<PerformanceKeys> performance) {
        this.region = region;
        this.world = world;
        this.biomeNames = biomeNames;
        this.defaultBiomes = defaultBiomes;
        this.biomeWhitelist = biomeWhitelist;
        this.verbose = verbose;
        this.shape = shape;
        this.vert = vert;
        this.unsafeBlocks = unsafeBlocks;
        this.safetyRadius = safetyRadius;
        this.staleChunkRetryLimit = staleChunkRetryLimit;
        this.maxAttemptsBase = maxAttemptsBase;
        this.maxAttempts = maxAttempts;
        this.maxAttemptsCeiling = Math.max(maxAttempts, maxAttemptsBase * 100L);
        this.biomeRecall = biomeRecall;
        this.biomeRecallForced = biomeRecallForced;
        this.biomeWeighted = biomeWeighted;
        this.biomeWeights = biomeWeights;
        this.worldBiomeRegistry = worldBiomeRegistry;
        this.biomeSampleCapability = biomeSampleCapability;
        this.resolution = resolution;
        this.performance = performance;

        this.failMap = new EnumMap<>(LocationGenerator.FailTypes.class);
        for (LocationGenerator.FailTypes f : LocationGenerator.FailTypes.values()) {
            this.failMap.put(f, new HashMap<>());
        }
    }

    /**
     * Parses configuration and builds the per-invocation state. Returns {@code null} if
     * the region's shape or vertical adjustor are {@code null} (matches the previous
     * blocking implementation's early-return contract).
     */
    @SuppressWarnings("unchecked")
    static @Nullable PregenState build(Region region, @Nullable Set<String> biomeNamesIn) {
        long resolution = Math.max(1L, region.getSettings().spatialResolution());

        ConfigParser<PerformanceKeys> performance =
                (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        ConfigParser<LoggingKeys> logging =
                (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
        ConfigParser<BiomesKeys> biomes =
                (ConfigParser<BiomesKeys>) RTP.configs.getParser(BiomesKeys.class);

        boolean defaultBiomes = false;
        boolean biomeWhitelist;
        Set<String> biomeNames;
        if (biomeNamesIn == null || biomeNamesIn.isEmpty()) {
            defaultBiomes = true;
            Object o = RTP.configs.getConfigValue(BiomesKeys.biomeWhitelist, false);
            biomeWhitelist = (o instanceof Boolean b) ? b : Boolean.parseBoolean(o.toString());

            o = RTP.configs.getConfigValue(BiomesKeys.biomes, null);
            List<String> biomeList =
                    (o instanceof List<?> list)
                            ? list.stream().map(Object::toString).toList()
                            : null;
            biomeNames =
                    (biomeList == null)
                            ? new HashSet<>()
                            : biomeList.stream().map(String::toUpperCase).collect(Collectors.toSet());
        } else {
            biomeWhitelist = true;
            biomeNames = biomeNamesIn.stream().map(String::toUpperCase).collect(Collectors.toSet());
        }

        boolean verbose = false;
        if (logging != null) {
            Object o = logging.getConfigValue(LoggingKeys.selection_failure, false);
            verbose = (o instanceof Boolean b) ? b : Boolean.parseBoolean(o.toString());
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

        Object o = RTP.configs.getConfigValue(BlocksKeys.unsafeBlocks, new ArrayList<>());
        Set<String> unsafeBlocks =
                (o instanceof Collection<?> collection)
                        ? collection
                        .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
                        : new HashSet<>();

        // ADR-017 / REQ-RTP-S-004 pregen-path surfacing of malformed safety tokens.
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

        int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();
        int staleChunkRetryLimit = Math.max(0,
                safety.getNumber(SafetyKeys.staleChunkRetryLimit, 2).intValue());

        long maxAttemptsBase = Math.max(1L, performance.getNumber(PerformanceKeys.maxAttempts, 32).longValue());
        long maxAttempts = maxAttemptsBase;

        boolean biomeRecall = Boolean.parseBoolean(
                performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());
        boolean biomeRecallForced = Boolean.parseBoolean(
                performance.getConfigValue(PerformanceKeys.biomeRecallForced, false).toString());
        boolean biomeWeighted = Boolean.parseBoolean(
                RTP.configs.getConfigValue(BiomesKeys.biomeWeighted, false).toString());
        Map<String, Double> biomeWeights = parseBiomeWeights(biomes);

        // ADR-062: resolve the world's biome registry and sampling capability
        // once per invocation (like the weights above). Both feed the
        // gray-space steering decision in PregenTask; the registry lets a
        // registered-but-unrecorded requested biome stay reachable instead of
        // being implicitly weight 0.
        RTPWorld<?> world = region.getWorld();
        Set<String> worldBiomeRegistry = new HashSet<>();
        io.github.dailystruggle.rtp.api.world.BiomeSampleCapability biomeSampleCapability =
                io.github.dailystruggle.rtp.api.world.BiomeSampleCapability.GENERATE_REQUIRED;
        if (accessor != null && world != null) {
            try {
                Set<String> registry = accessor.getBiomes(world);
                if (registry != null) {
                    for (String b : registry) {
                        if (b != null) worldBiomeRegistry.add(b.toUpperCase(Locale.ROOT));
                    }
                }
            } catch (RuntimeException re) {
                RTP.log(Level.WARNING, "[RTP] biome registry lookup failed: " + re);
            }
            try {
                io.github.dailystruggle.rtp.api.world.BiomeSampleCapability cap =
                        accessor.biomeSampleCapability(world);
                if (cap != null) biomeSampleCapability = cap;
            } catch (RuntimeException re) {
                RTP.log(Level.WARNING, "[RTP] biome-sample capability lookup failed: " + re);
            }
        }

        return new PregenState(
                region,
                region.getWorld(),
                biomeNames,
                defaultBiomes,
                biomeWhitelist,
                verbose,
                shape,
                vert,
                unsafeBlocks,
                safetyRadius,
                staleChunkRetryLimit,
                maxAttemptsBase,
                maxAttempts,
                biomeRecall,
                biomeRecallForced,
                biomeWeighted,
                biomeWeights,
                worldBiomeRegistry,
                biomeSampleCapability,
                resolution,
                performance);
    }

    /**
     * Parse the {@code biomes.yml#biomeWeights} map into an upper-cased,
     * non-negative weight table (ADR-062). Non-numeric or negative values are
     * dropped (negative weights are meaningless for a probability draw); a
     * malformed or absent map yields an empty table, which the draw treats as
     * "all biomes equal".
     */
    private static Map<String, Double> parseBiomeWeights(ConfigParser<BiomesKeys> biomes) {
        Map<String, Double> result = new HashMap<>();
        Map<String, Object> raw;
        try {
            raw = biomes.getMap(BiomesKeys.biomeWeights);
        } catch (RuntimeException re) {
            return result;
        }
        if (raw == null || raw.isEmpty()) return result;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            double w;
            try {
                w = (e.getValue() instanceof Number n)
                        ? n.doubleValue()
                        : Double.parseDouble(e.getValue().toString().trim());
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (Double.isNaN(w) || w < 0.0d) continue;
            result.put(e.getKey().toUpperCase(Locale.ROOT), w);
        }
        return result;
    }
}

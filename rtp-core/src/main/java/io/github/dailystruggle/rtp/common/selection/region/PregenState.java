package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.safety.SafetyCompilationCache;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
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
    final long resolution;
    final long maxAttemptsBase;
    final ConfigParser<PerformanceKeys> performance;

    // Mutable across the attempt loop
    long maxAttempts;
    long worldBorderFails = 0L;
    /**
     * Absolute ceiling on how large {@link #maxAttempts} may grow via
     * "free-retry" bumps (biome reject, worldborder miss, prefilter reject,
     * stale-chunk retry, etc.). Without this ceiling, a configuration that can
     * never satisfy the biome filter (e.g. MockRTPWorld + an impossible biome)
     * would bump {@code maxAttempts} on every attempt and never exhaust.
     * <p>Set to {@code maxAttemptsBase * 100} — the historical multiplier that
     * was previously expressed as {@code Region.maxBiomeChecksPerGen}; that
     * knob was retired in PR-3a of the biome-lookup performance plan but the
     * bound it enforced is still structurally required.</p>
     */
    final long maxAttemptsCeiling;

    final Map<LocationGenerator.FailTypes, Map<String, Long>> failMap;
    final List<Map.Entry<Long, Long>> selections = new ArrayList<>();
    /**
     * Per-attempt outcome breadcrumb trail. One entry per call to
     * {@code runAttempt()} that produced any outcome — rejection, success, or
     * exhausted-guard trip. Added opportunistically from every {@code reschedule}
     * and {@code completeSuccess} site in {@code PregenTask} so a failing log
     * always reveals which code path fired, even if the {@link #failMap}
     * bucketing is ambiguous (or, historically, corrupted by concurrent
     * non-synchronised {@link HashMap#compute} calls).
     * <p>Not thread-safe — relies on the {@code PregenTask} serialisation
     * contract (one attempt resolves before the next enters {@code runAttempt}).
     * If that contract is violated, {@link java.util.ConcurrentModificationException}
     * is preferable to silent bucket loss.</p>
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

        boolean defaultBiomes = false;
        boolean biomeWhitelist;
        Set<String> biomeNames;
        if (biomeNamesIn == null || biomeNamesIn.isEmpty()) {
            defaultBiomes = true;
            Object o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
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

        Object o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
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

        long maxAttemptsBase = Math.max(1L, performance.getNumber(PerformanceKeys.maxAttempts, 20).longValue());
        long maxAttempts = maxAttemptsBase;

        boolean biomeRecall = Boolean.parseBoolean(
                performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());
        boolean biomeRecallForced = Boolean.parseBoolean(
                performance.getConfigValue(PerformanceKeys.biomeRecallForced, false).toString());

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
                resolution,
                performance);
    }
}

package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curve;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;

import java.util.Locale;

/**
 * Catalogue of normalized load-balancing inputs extracted from a
 * {@link BackendHeartbeat}. Every extractor returns a value in {@code [0,1]}
 * where {@code 0.0} means "ideal candidate" and {@code 1.0} means "worst
 * candidate" (lowest score wins in
 * {@link WeightedAverageBackendSelector#score}).
 *
 * <p>Normalization references match rtp-proxy-ADR-004 (50 ms tick budget, soft
 * cap, heap max, 100-player headline, 64-location warm pool). Inputs whose
 * raw scale is "higher is better" (free heap, kept count, TPS) are inverted
 * here so the scoring formula stays "lower is better" across the board.</p>
 *
 * <p>Heartbeat does not yet publish TPS directly; we derive it from
 * {@code mspt} as {@code tps = min(20, 1000/mspt)}.</p>
 */
public enum MetricInput {

    /** Mean server tick time, normalized as {@code mspt / 50.0}. Higher is worse. */
    MSPT("mspt") {
        @Override public double normalize(BackendHeartbeat b) {
            return Curve.clamp01(b.mspt() / 50.0);
        }
    },

    /** Teleport waitlist depth, normalized as {@code queueDepth / max(1, softCap)}. Higher is worse. */
    QUEUE_DEPTH("queueDepth") {
        @Override public double normalize(BackendHeartbeat b) {
            int cap = Math.max(1, b.softCap());
            return Curve.clamp01((double) b.queueDepth() / cap);
        }
    },

    /** Used-heap ratio {@code heapUsed / heapMax}. Higher is worse. */
    HEAP_USED("heapUsed") {
        @Override public double normalize(BackendHeartbeat b) {
            if (b.heapMaxBytes() <= 0L) return 0.0;
            return Curve.clamp01((double) b.heapUsedBytes() / b.heapMaxBytes());
        }
    },

    /**
     * Free-heap ratio expressed in worst-is-1 polarity: {@code heapUsed / heapMax}
     * itself, i.e. "remaining memory" framing: when heap is near full the
     * cost of a teleport (chunk loads, NBT IO) is highest. Identical extractor
     * to {@link #HEAP_USED} but distinct alias so operators can write
     * {@code input: heapFree} when reasoning about "remaining capacity".
     * Heartbeat does not expose free-bytes directly, so this is computed as
     * the used ratio; semantically it answers "how close to OOM are we".
     */
    HEAP_FREE("heapFree") {
        @Override public double normalize(BackendHeartbeat b) {
            return HEAP_USED.normalize(b);
        }
    },

    /** Online player count, normalized by 100. Higher is worse (lagging indicator; ADR-004 section playerCount). */
    PLAYER_COUNT("playerCount") {
        @Override public double normalize(BackendHeartbeat b) {
            return Curve.clamp01(b.playerCount() / 100.0);
        }
    },

    /**
     * Inverted kept-pool depth: {@code 1 - min(1, keptCount / 64)}. Empty kept
     * pool = 1.0 (worst); a warm pool of 64+ locations = 0.0 (best). 64 is the
     * default kept-cache size; operators with non-default sizes are encouraged
     * to pair this input with a {@code logarithmic} curve.
     */
    KEPT_COUNT("keptCount") {
        @Override public double normalize(BackendHeartbeat b) {
            double warm = Math.min(1.0, Math.max(0, b.keptCount()) / 64.0);
            return Curve.clamp01(1.0 - warm);
        }
    },

    /**
     * Inverted ticks-per-second derived from {@code mspt}:
     * {@code tps = min(20, 1000/mspt)}, then {@code 1 - tps/20}. A healthy
     * 20 TPS server yields 0.0; a 10 TPS server yields 0.5.
     */
    TPS("tps") {
        @Override public double normalize(BackendHeartbeat b) {
            double mspt = b.mspt();
            double tps = (mspt <= 0.0) ? 20.0 : Math.min(20.0, 1000.0 / mspt);
            return Curve.clamp01(1.0 - tps / 20.0);
        }
    },

    /**
     * Inverted per-region kept-pool depth: {@code 1 - min(1, regionKeptCounts[r] / 64)}.
     * Region-scoped variant of {@link #KEPT_COUNT}; consumed by
     * {@code RegionAwareSelector} when scoring {@code (serverId, regionKey)}
     * pairs. With a {@code null} or unknown {@code regionKey} the extractor
     * falls back to the backend-scoped {@link #KEPT_COUNT}.
     */
    KEPT_REGION("keptRegion") {
        @Override public double normalize(BackendHeartbeat b) {
            return KEPT_COUNT.normalize(b);
        }
        @Override public double normalize(BackendHeartbeat b, String regionKey) {
            if (regionKey == null || regionKey.isEmpty()) return KEPT_COUNT.normalize(b);
            Integer mapped = (b.regionKeptCounts() == null)
                    ? null : b.regionKeptCounts().get(regionKey);
            int count = (mapped == null) ? 0 : Math.max(0, mapped);
            double warm = Math.min(1.0, count / 64.0);
            return Curve.clamp01(1.0 - warm);
        }
    };

    private final String configId;

    MetricInput(String configId) {
        this.configId = configId;
    }

    /** Lowercase identifier used in YAML (e.g. {@code mspt}, {@code queueDepth}). */
    public String configId() { return configId; }

    /** Extracts the normalized value in {@code [0,1]} from a heartbeat. */
    public abstract double normalize(BackendHeartbeat b);

    /**
     * Region-aware extractor variant. Backend-scoped inputs ignore
     * {@code regionKey} and delegate to {@link #normalize(BackendHeartbeat)};
     * region-scoped inputs (currently {@link #KEPT_REGION}) consume it. A
     * {@code null} {@code regionKey} is tolerated and falls back to the
     * backend-scoped extractor.
     */
    public double normalize(BackendHeartbeat b, String regionKey) {
        return normalize(b);
    }

    /** Case-insensitive parse from the YAML {@code input} key. */
    public static MetricInput fromConfigId(String s) {
        if (s == null) throw new IllegalArgumentException("input must not be null");
        String norm = s.trim().toLowerCase(Locale.ROOT);
        for (MetricInput v : values()) {
            if (v.configId.toLowerCase(Locale.ROOT).equals(norm)) return v;
        }
        // Also accept enum name (MSPT, QUEUE_DEPTH, ...) for forgiving parsing.
        for (MetricInput v : values()) {
            if (v.name().toLowerCase(Locale.ROOT).equals(norm)) return v;
        }
        throw new IllegalArgumentException(
                "Unknown load-balancer input '" + s + "'. Expected one of: " +
                "mspt, queueDepth, heapUsed, heapFree, playerCount, keptCount, tps, keptRegion.");
    }
}

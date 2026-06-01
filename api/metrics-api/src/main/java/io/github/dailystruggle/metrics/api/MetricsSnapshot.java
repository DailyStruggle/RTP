package io.github.dailystruggle.metrics.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable point-in-time snapshot of runtime health signals.
 *
 * <p>The single carrier consumed by health surfaces (test commands, info commands,
 * bStats integrations, telemetry publishers). Callers shall treat fields as scalar
 * primitives and compute deltas themselves; the snapshot retains no history.
 *
 * <p>Plugin-specific counters belong on a {@link MetricsExtension} attached via
 * {@link #withExtension(MetricsExtension)}; this class itself carries only
 * host-runtime fields.
 *
 * <p>All fields are public-final so the snapshot stays trivially serialisable to JSON.
 * No platform types appear here.
 */
public class MetricsSnapshot {

    /** Sentinel for &ldquo;not sampled by the active binding yet&rdquo;. Always {@link Double#NaN}. */
    public static final double UNSAMPLED = Double.NaN;

    public final double tps1m;
    public final double tps5m;
    public final double tps15m;
    public final double mspt;
    /** {@code mspt / 50.0} when {@link #mspt} is sampled, else {@link Double#NaN}. */
    public final double tickBudgetUtilisation;

    public final int playerCount;
    public final int softCap;

    public final long heapUsedBytes;
    public final long heapMaxBytes;

    /** Wall-clock millisecond timestamp when this snapshot was assembled. */
    public final long takenAtEpochMs;

    /**
     * Per-region detail on Folia (and any future multi-region runtime). Empty on
     * single-region runtimes. Never null; defensively wrapped unmodifiable.
     */
    public final List<FoliaRegionSample> foliaRegions;

    /** Plugin-extension payloads keyed by concrete extension class. Defensively unmodifiable. */
    private final Map<Class<? extends MetricsExtension<?>>, MetricsExtension<?>> extensions;

    /** Public constructor: host-runtime fields plus optional per-region detail. */
    public MetricsSnapshot(
            double tps1m,
            double tps5m,
            double tps15m,
            double mspt,
            int playerCount,
            int softCap,
            long heapUsedBytes,
            long heapMaxBytes,
            long takenAtEpochMs,
            List<? extends FoliaRegionSample> foliaRegions) {
        this(tps1m, tps5m, tps15m, mspt, playerCount, softCap,
                heapUsedBytes, heapMaxBytes, takenAtEpochMs, foliaRegions,
                Collections.emptyMap());
    }

    /** Full internal constructor including the extension map. */
    protected MetricsSnapshot(
            double tps1m,
            double tps5m,
            double tps15m,
            double mspt,
            int playerCount,
            int softCap,
            long heapUsedBytes,
            long heapMaxBytes,
            long takenAtEpochMs,
            List<? extends FoliaRegionSample> foliaRegions,
            Map<Class<? extends MetricsExtension<?>>, MetricsExtension<?>> extensions) {
        this.tps1m = tps1m;
        this.tps5m = tps5m;
        this.tps15m = tps15m;
        this.mspt = mspt;
        this.tickBudgetUtilisation = Double.isNaN(mspt) ? Double.NaN : mspt / 50.0;
        this.playerCount = playerCount;
        this.softCap = softCap;
        this.heapUsedBytes = heapUsedBytes;
        this.heapMaxBytes = heapMaxBytes;
        this.takenAtEpochMs = takenAtEpochMs;
        this.foliaRegions = (foliaRegions == null || foliaRegions.isEmpty())
                ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(foliaRegions));
        this.extensions = (extensions == null || extensions.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(extensions));
    }

    /**
     * Returns a copy of this snapshot with {@code extension} registered (or replacing
     * any prior payload of the same concrete type). The receiver is not mutated.
     */
    @SuppressWarnings("unchecked")
    public MetricsSnapshot withExtension(MetricsExtension<?> extension) {
        if (extension == null) {
            throw new NullPointerException("extension");
        }
        HashMap<Class<? extends MetricsExtension<?>>, MetricsExtension<?>> next = new HashMap<>(this.extensions);
        next.put((Class<? extends MetricsExtension<?>>) extension.getClass(), extension);
        return new MetricsSnapshot(tps1m, tps5m, tps15m, mspt, playerCount, softCap,
                heapUsedBytes, heapMaxBytes, takenAtEpochMs, foliaRegions, next);
    }

    @Override
    public String toString() {
        return "MetricsSnapshot{"
                + "tps1m=" + tps1m
                + ", tps5m=" + tps5m
                + ", tps15m=" + tps15m
                + ", mspt=" + mspt
                + ", tickBudgetUtilisation=" + tickBudgetUtilisation
                + ", playerCount=" + playerCount
                + ", softCap=" + softCap
                + ", heapUsedMb=" + heapUsedMb()
                + ", heapMaxMb=" + heapMaxMb()
                + ", takenAtEpochMs=" + takenAtEpochMs
                + ", foliaRegions=" + foliaRegions
                + ", extensions=" + extensions
                + '}';
    }

    /** Convenience: heap used in MB (rounded down). */
    public long heapUsedMb() {
        return heapUsedBytes / (1024L * 1024L);
    }

    /** Convenience: heap max in MB (rounded down), or {@code -1} if unbounded ({@link #heapMaxBytes} {@code < 0}). */
    public long heapMaxMb() {
        return (heapMaxBytes < 0) ? -1L : heapMaxBytes / (1024L * 1024L);
    }

    /**
     * Type-safe accessor for an extension payload previously attached
     * via {@link #withExtension(MetricsExtension)}.
     */
    public <T extends MetricsExtension<T>> T extension(Class<T> type) {
        if (type == null) {
            return null;
        }
        MetricsExtension<?> raw = extensions.get(type);
        return type.isInstance(raw) ? type.cast(raw) : null;
    }
}

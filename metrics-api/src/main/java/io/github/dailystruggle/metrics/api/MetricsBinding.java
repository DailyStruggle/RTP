package io.github.dailystruggle.metrics.api;

import java.util.Collections;
import java.util.List;

/**
 * Platform-supplied source for metric fields that the host plugin cannot derive on its
 * own (TPS / MSPT / player count / soft cap / chunk-load backlog / database latency /
 * per-region Folia samples).
 *
 * <p>Each platform adapter implements this interface and registers it via
 * {@link Metrics#registerBinding(MetricsBinding)} (or, for RTP, via
 * {@code CoreMetrics#setBinding} which mirrors into the same registry).
 *
 * <p>Implementations <strong>shall not</strong> block the calling thread. If a value is
 * not yet sampled, return the documented sentinel ({@link MetricsSnapshot#UNSAMPLED} for
 * doubles, {@code 0} for counts, {@code -1} for {@code databaseLatencyMs}).
 *
 * <p>The {@link #NOOP} binding is the default; it is what {@link Metrics#currentBinding()}
 * returns when no platform has registered.
 */
public interface MetricsBinding {

    default double tps1m() { return MetricsSnapshot.UNSAMPLED; }

    default double tps5m() { return MetricsSnapshot.UNSAMPLED; }

    default double tps15m() { return MetricsSnapshot.UNSAMPLED; }

    default double mspt() { return MetricsSnapshot.UNSAMPLED; }

    default int playerCount() { return 0; }

    default int softCap() { return 0; }

    default int chunkLoadBacklog() { return 0; }

    default int databaseLatencyMs() { return -1; }

    /**
     * Per-region detail on Folia. Empty on single-region runtimes (Paper / Bukkit /
     * Fabric); Folia overrides this with one entry per region.
     */
    default List<FoliaRegionSample> foliaRegions() { return Collections.emptyList(); }

    /** Default no-op binding; returns documented sentinels for every field. */
    MetricsBinding NOOP = new MetricsBinding() {};
}

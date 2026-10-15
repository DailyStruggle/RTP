package io.github.dailystruggle.metrics.api;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

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

    /**
     * Lowest 1m TPS across every tick loop the host runs. Equals {@link #tps1m()} on
     * single-loop runtimes; Folia returns the worst region (or global heartbeat) so a
     * load-shedding gate sees one overloaded region instead of the mean.
     */
    default double worstTps1m() { return tps1m(); }

    /** Highest MSPT across every tick loop the host runs; see {@link #worstTps1m()}. */
    default double worstMspt() { return mspt(); }

    /**
     * 1m TPS of the tick loop that owns chunk {@code (chunkX, chunkZ)} in world {@code worldId}.
     * Single-loop runtimes return the server value; {@link MetricsSnapshot#UNSAMPLED} when that
     * loop has no samples yet. Non-blocking, any thread.
     */
    default double regionTps1m(UUID worldId, int chunkX, int chunkZ) { return tps1m(); }

    /** MSPT of the tick loop that owns the chunk; see {@link #regionTps1m(UUID, int, int)}. */
    default double regionMspt(UUID worldId, int chunkX, int chunkZ) { return mspt(); }

    /**
     * Whether ticks are split across independent loops (Folia regions). A server-wide gate then
     * reads the mean and leaves hot spots to per-region gates via {@link #regionTps1m}.
     */
    default boolean regionalTicking() { return false; }

    /**
     * Whether this binding is {@code other} or decorates it. Lets a platform binding that
     * is wrapped (e.g. by a spark decorator) recognise that it is still the installed one.
     */
    default boolean wraps(MetricsBinding other) { return this == other; }

    /** Default no-op binding; returns documented sentinels for every field. */
    MetricsBinding NOOP = new MetricsBinding() {};
}

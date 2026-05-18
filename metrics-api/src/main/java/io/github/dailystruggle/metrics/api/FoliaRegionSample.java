package io.github.dailystruggle.metrics.api;

/**
 * Immutable per-region health sample for Folia (and any future multi-region runtime).
 *
 * <p>Carried as an element of {@link MetricsSnapshot#foliaRegions} alongside the
 * aggregated scalar fields. The scalar fields remain authoritative for cross-platform
 * consumers (bStats, /rtp info default view, network telemetry). This carrier exposes
 * the underlying per-region detail for verbose surfaces and operator diagnostics.
 *
 * <p>The {@link #regionId} is an opaque, stable-per-server-lifetime identifier
 * (e.g. {@code region-0}, {@code region-1}). Per
 * {@code METRICS_PLAN.md > regionQueueStatus redaction in network mode} this id does not
 * leak human-readable region names across the wire.
 *
 * <p>Non-Folia bindings populate {@link MetricsSnapshot#foliaRegions} with an empty list
 * (the default on {@link MetricsBinding#foliaRegions()}); single-region runtimes already
 * publish their TPS / MSPT via the scalar fields.
 *
 * <p>No platform types appear here.
 */
public class FoliaRegionSample {

    /** Stable opaque identifier (e.g. {@code "region-0"}). Never null. */
    public final String regionId;

    /** Per-region 1m TPS EMA, or {@link MetricsSnapshot#UNSAMPLED}. */
    public final double tps1m;

    /** Per-region milliseconds-per-tick, or {@link MetricsSnapshot#UNSAMPLED}. */
    public final double mspt;

    /** Player count owned by this region, or {@code 0} if unknown. */
    public final int playerCount;

    /** Queue depth attributable to this region, or {@code 0} if unknown. */
    public final int queueDepth;

    public FoliaRegionSample(String regionId, double tps1m, double mspt, int playerCount, int queueDepth) {
        this.regionId = (regionId == null) ? "region-?" : regionId;
        this.tps1m = tps1m;
        this.mspt = mspt;
        this.playerCount = playerCount;
        this.queueDepth = queueDepth;
    }

    /** {@code mspt / 50.0} when {@link #mspt} is sampled, else {@link Double#NaN}. */
    public double tickBudgetUtilisation() {
        return Double.isNaN(mspt) ? Double.NaN : mspt / 50.0;
    }

    @Override
    public String toString() {
        return "FoliaRegionSample{"
                + "regionId='" + regionId + '\''
                + ", tps1m=" + tps1m
                + ", mspt=" + mspt
                + ", playerCount=" + playerCount
                + ", queueDepth=" + queueDepth
                + '}';
    }
}

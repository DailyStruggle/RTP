package io.github.dailystruggle.metrics.api;

/**
 * Snapshot of one Folia region's health metrics.
 *
 * <p>Constructed by Folia-specific adapters (such as {@code rtp-folia})
 * and surfaced across the platform-neutral {@link MetricsBinding} SPI.
 * On non-Folia platforms, {@link MetricsBinding#foliaRegions()} is empty
 * and this record is never instantiated.
 *
 * <p>All fields are immutable values captured at sample time. {@link #regionId}
 * is an opaque, stable string identifying the region thread / chunk group
 * (e.g. {@code "world:overworld@chunk:12,-4"}).
 */
public class FoliaRegionSample {

    /** Opaque, stable identifier for the sampled region. Never {@code null}. */
    public final String regionId;

    /** Per-region 1m TPS EMA, or {@link MetricsSnapshot#UNSAMPLED}. */
    public final double tps1m;

    /** Per-region milliseconds-per-tick, or {@link MetricsSnapshot#UNSAMPLED}. */
    public final double mspt;

    /** Player count owned by this region, or {@code 0} if unknown. */
    public final int playerCount;

    /** Queue depth attributable to this region, or {@code 0} if unknown. */
    public final int queueDepth;

    /** Valid tick delta samples recorded for this region, or {@code 0} if unknown. */
    public final long sampleCount;

    /** Age of this region sampler in milliseconds, or {@code 0} if unknown. */
    public final long ageMillis;

    public FoliaRegionSample(String regionId, double tps1m, double mspt, int playerCount, int queueDepth) {
        this(regionId, tps1m, mspt, playerCount, queueDepth, 0L, 0L);
    }

    public FoliaRegionSample(String regionId, double tps1m, double mspt, int playerCount, int queueDepth,
                             long sampleCount, long ageMillis) {
        this.regionId = (regionId == null) ? "region-?" : regionId;
        this.tps1m = tps1m;
        this.mspt = mspt;
        this.playerCount = playerCount;
        this.queueDepth = queueDepth;
        this.sampleCount = sampleCount;
        this.ageMillis = ageMillis;
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
                + ", sampleCount=" + sampleCount
                + ", ageMillis=" + ageMillis
                + '}';
    }
}

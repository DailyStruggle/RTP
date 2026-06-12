package io.github.dailystruggle.rtp.bukkitplatform.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import java.util.List;

/**
 * {@link MetricsBinding} that sources TPS / MSPT from the
 * <a href="https://spark.lucko.me/">spark</a> profiler when it is installed,
 * merging those richer values over a native {@code delegate} binding for every
 * other field. spark exposes per-window TPS and a mean-MSPT statistic that are
 * consistent across Paper / Spigot / Folia and more accurate than the coarse
 * {@code Server#getTPS()} / {@code Server#getAverageTickTime()} the native
 * bindings read.
 *
 * <p><b>Soft-dependency.</b> spark is never required. The binding probes the
 * spark API once at construction; if the API classes are absent
 * ({@link ClassNotFoundException} / {@link NoClassDefFoundError} - including
 * trimmed assemblies such as the rtp-lite jar) it enters a permanent
 * <em>disabled</em> state and every field simply delegates. The
 * {@code softdepend: [spark]} entry in {@code plugin.yml} only constrains load
 * order so spark is enabled before RTP; it does not make spark mandatory.
 *
 * <p><b>Per-field merge (approved design).</b> Only {@link #tps1m()} /
 * {@link #tps5m()} / {@link #tps15m()} / {@link #mspt()} are taken from spark.
 * {@link #playerCount()}, {@link #softCap()}, {@link #chunkLoadBacklog()},
 * {@link #databaseLatencyMs()}, and {@link #foliaRegions()} always delegate -
 * spark does not own those. When spark is present but a statistic is not yet
 * sampled (or {@code SparkProvider.get()} transiently throws before spark
 * finishes init), the affected getter returns the delegate's value instead, so
 * the merge self-heals once spark warms up.
 *
 * <p><b>Threading.</b> Every getter is non-blocking and never throws on the
 * calling thread (binding contract). All reflective access is wrapped so a
 * spark-side failure degrades to the delegate / sentinel rather than
 * propagating.
 *
 * <p><b>Folia.</b> This binding is intentionally <em>not</em> applied on Folia
 * by {@code MetricsBindingDispatcher}; Folia keeps its native
 * {@code FoliaMetricsBinding} so per-region samples ({@link #foliaRegions()})
 * are preserved.
 *
 * <p>Testable without spark on the classpath via the package-private
 * {@link #SparkMetricsBinding(MetricsBinding, SparkStats)} constructor.
 */
public final class SparkMetricsBinding implements MetricsBinding {

    /** Source of spark-derived statistic values. */
    interface SparkStats {
        /** {@code true} once the spark API has been linked successfully. */
        boolean available();

        /**
         * TPS for window index {@code 0=1m, 1=5m, 2=15m}, or
         * {@link MetricsSnapshot#UNSAMPLED} ({@code NaN}) if unavailable / not
         * yet sampled.
         */
        double tps(int windowIdx);

        /** Mean MSPT (last minute), or {@link MetricsSnapshot#UNSAMPLED}. */
        double mspt();
    }

    private final MetricsBinding delegate;
    private final SparkStats spark;

    /** Production constructor: builds a reflective spark accessor. */
    public SparkMetricsBinding(MetricsBinding delegate) {
        this(delegate, new ReflectiveSparkStats());
    }

    /** Test seam. */
    SparkMetricsBinding(MetricsBinding delegate, SparkStats spark) {
        this.delegate = (delegate == null) ? MetricsBinding.NOOP : delegate;
        this.spark = spark;
    }

    /** Visible for diagnostics / tests. */
    boolean sparkEnabled() {
        return spark.available();
    }

    private double sparkTpsOr(int windowIdx, double fallback) {
        if (!spark.available()) return fallback;
        double v = spark.tps(windowIdx);
        return Double.isNaN(v) ? fallback : v;
    }

    @Override
    public double tps1m() {
        return sparkTpsOr(0, delegate.tps1m());
    }

    @Override
    public double tps5m() {
        return sparkTpsOr(1, delegate.tps5m());
    }

    @Override
    public double tps15m() {
        return sparkTpsOr(2, delegate.tps15m());
    }

    @Override
    public double mspt() {
        double fallback = delegate.mspt();
        if (!spark.available()) return fallback;
        double v = spark.mspt();
        return Double.isNaN(v) ? fallback : v;
    }

    @Override
    public int playerCount() {
        return delegate.playerCount();
    }

    @Override
    public int softCap() {
        return delegate.softCap();
    }

    @Override
    public int chunkLoadBacklog() {
        return delegate.chunkLoadBacklog();
    }

    @Override
    public int databaseLatencyMs() {
        return delegate.databaseLatencyMs();
    }

    @Override
    public List<FoliaRegionSample> foliaRegions() {
        return delegate.foliaRegions();
    }
}

package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.DualSparkline;
import io.github.dailystruggle.mapsapi.render.DualSparklineRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.metrics.MetricsSnapshotRing;

import java.util.logging.Level;

/**
 * Resolver for {@link ChartSpec.Kind#METRIC_SPARKLINE}.
 *
 * <p>Produces a {@link DualSparkline} carrying two index-aligned series
 * sampled at 1 Hz by the {@code MetricsSnapshotRing} sampler installed in
 * {@code RTP.start}:
 *
 * <ul>
 *   <li><strong>Series A: MSPT (ms).</strong> On Folia, aggregated across
 *       regions by the platform binding per the configurable
 *       {@code foliaAggregationMspt} mode ({@code max} default, or
 *       {@code mean}); the ring records that host scalar directly. Y-scale
 *       autoscales to {@code [0, max(observed, 50 ms)]} so an idle server
 *       doesn't zoom into noise but a spike clearly stands above the 50 ms
 *       budget line.</li>
 *   <li><strong>Series B: heap used (MB).</strong> Per-JVM scalar; no
 *       Folia aggregation. Y-scale autoscales to
 *       {@code [0, max(observed, heapMax)]}.</li>
 * </ul>
 *
 * <p>Both series share the same x-axis (sample index, oldest left, newest
 * right) and the same 128-slot history depth. With the default 1 Hz
 * sampler this is ~2 minutes of rolling history.
 *
 * <p>Global by design: {@code ChartSpec#regionName} is ignored. The
 * sampler reads from {@code RTP.metrics.snapshot()} which is already
 * platform-aware (Folia regions are aggregated upstream by the
 * {@code MetricsSnapshotRing#recordFromSnapshot} call).
 *
 * <p>S-005 clean (no chunk I/O), no blocking futures, no platform
 * imports.
 */
public final class MetricSparklineResolver implements ChartSpecResolver {

    /**
     * MSPT floor for the y-scale ceiling. A 50 ms tick is the full vanilla
     * budget; clamping the ceiling here means an idle server (every sample
     * &lt; 10 ms) still renders as a low line near the bottom of the
     * chart, not as a maxed-out sawtooth.
     */
    private static final double MSPT_FLOOR_CEILING = 50.0;

    /**
     * Heap floor for the y-scale ceiling, in bytes. 256 MB. Same reasoning
     * as {@link #MSPT_FLOOR_CEILING}: a tiny dev server with 50 MB heap
     * still renders against a stable y-axis instead of getting visually
     * pinned to the top.
     */
    private static final double HEAP_FLOOR_CEILING_BYTES = 256.0 * 1024 * 1024;

    @Override
    public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
        RTP.log(Level.FINE,
                "[viz/sparkline] resolver entry: spec="
                        + (spec == null ? "null" : spec.kind().toString()));
        if (spec == null) {
            throw new UnresolvableChartSpecException("spec shall not be null");
        }
        if (spec.kind() != ChartSpec.Kind.METRIC_SPARKLINE) {
            throw new UnresolvableChartSpecException(
                    "MetricSparklineResolver only handles METRIC_SPARKLINE, got " + spec.kind());
        }

        MetricsSnapshotRing ring = RTP.metrics.snapshotRing();
        if (ring == null) {
            // CoreMetrics always exposes a ring; defensive guard.
            throw new UnresolvableChartSpecException(
                    "metrics snapshotRing is null");
        }

        double[] msptSamples = ring.msptSnapshot();
        long[] heapBytes = ring.heapSnapshot();

        if (msptSamples.length == 0 || heapBytes.length == 0) {
            // Warm-up: the sampler runs at 1 Hz so we typically have data
            // within ~1 s of plugin enable. Surface a deterministic
            // placeholder series of length 1 so the chart still renders
            // and the user gets a visible "no data yet" frame instead of
            // an UnresolvableChartSpecException.
            msptSamples = new double[]{Double.NaN};
            heapBytes = new long[]{0L};
        }

        // Convert heap bytes to MB for the chart axis. Match length.
        double[] heapMb = new double[heapBytes.length];
        for (int i = 0; i < heapBytes.length; i++) {
            heapMb[i] = heapBytes[i] < 0 ? Double.NaN
                    : ((double) heapBytes[i]) / (1024.0 * 1024.0);
        }

        // The two series share the same writer (recordFromSnapshot
        // updates both atomically per sample) so their lengths are
        // identical by construction. Defensive equalise: pad the shorter
        // one with NaN so the renderer doesn't reject the model.
        int n = Math.max(msptSamples.length, heapMb.length);
        if (msptSamples.length != n) {
            double[] padded = new double[n];
            java.util.Arrays.fill(padded, Double.NaN);
            System.arraycopy(msptSamples, 0, padded,
                    n - msptSamples.length, msptSamples.length);
            msptSamples = padded;
        }
        if (heapMb.length != n) {
            double[] padded = new double[n];
            java.util.Arrays.fill(padded, Double.NaN);
            System.arraycopy(heapMb, 0, padded,
                    n - heapMb.length, heapMb.length);
            heapMb = padded;
        }

        // Autoscale MSPT to [0, max(observed, 50 ms)]. Ignore NaN.
        double msptMax = MSPT_FLOOR_CEILING;
        for (double v : msptSamples) {
            if (!Double.isNaN(v) && v > msptMax) msptMax = v;
        }

        // Autoscale heap to [0, max(observed, 256 MB or heapMaxBytes / MB)].
        double heapMax = HEAP_FLOOR_CEILING_BYTES / (1024.0 * 1024.0);
        long heapMaxBytes = RTP.metrics.snapshot().heapMaxBytes;
        if (heapMaxBytes > 0) {
            heapMax = Math.max(heapMax,
                    ((double) heapMaxBytes) / (1024.0 * 1024.0));
        }
        for (double v : heapMb) {
            if (!Double.isNaN(v) && v > heapMax) heapMax = v;
        }

        RTP.log(Level.FINE,
                "[viz/sparkline] resolver: samples=" + n
                        + " mspt-range=[0," + msptMax + "]"
                        + " heap-range=[0," + heapMax + "] MB");

        DualSparkline model = new DualSparkline(
                "MSPT (ms)", msptSamples, 0.0, msptMax,
                "Heap (MB)", heapMb, 0.0, heapMax);
        return Resolution.of(DualSparklineRenderer.INSTANCE, model);
    }
}

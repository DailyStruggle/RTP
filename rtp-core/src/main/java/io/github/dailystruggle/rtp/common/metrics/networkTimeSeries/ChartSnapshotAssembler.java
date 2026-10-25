package io.github.dailystruggle.rtp.common.metrics.networkTimeSeries;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.api.metrics.MetricTimeSeriesPublisher;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Off-thread diagnostic chart series assembler consuming {@link MetricTimeSeriesPublisher}
 * range-scan results without copying on the main/region thread (ADR-039, ADR-040,
 * REQ-RTP-METTS-006, REQ-RTP-S-005).
 */
public final class ChartSnapshotAssembler {

  /**
   * Immutable chart series snapshot assembled from persisted {@link MetricBucketRow} buckets.
   *
   * @param backendId          queried backend selector ({@code null} or {@code "all"} for cluster-wide)
   * @param metricKey          metric key
   * @param fromEpochInclusive query range start (epoch seconds)
   * @param toEpochInclusive   query range end (epoch seconds)
   * @param buckets            zero-copy unmodifiable bucket list ordered by {@code windowStartEpoch}
   * @param timestamps         aligned bucket start timestamps
   * @param means              mean series
   * @param mins               low-water band series
   * @param maxes              high-water band series
   * @param p95s               optional 95th percentile series ({@code Double.NaN} where absent)
   */
  public record ChartSeriesSnapshot(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive,
      List<MetricBucketRow> buckets,
      long[] timestamps,
      double[] means,
      double[] mins,
      double[] maxes,
      double[] p95s) {}

  private final MetricTimeSeriesPublisher publisher;
  private final BooleanSupplier mainThreadDetector;

  public ChartSnapshotAssembler(MetricTimeSeriesPublisher publisher) {
    this(publisher, NetworkMetricTimeSeriesPublisher::defaultIsMainOrRegionThread);
  }

  public ChartSnapshotAssembler(
      MetricTimeSeriesPublisher publisher, BooleanSupplier mainThreadDetector) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.mainThreadDetector = Objects.requireNonNull(mainThreadDetector, "mainThreadDetector");
  }

  /**
   * Range-scans {@code publisher} off the main/region thread and assembles a
   * {@link ChartSeriesSnapshot} preserving the range-scan bucket list reference without copying
   * on the main/region thread (REQ-RTP-METTS-006, REQ-RTP-S-005).
   */
  public ChartSeriesSnapshot assemble(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive) {
    if (mainThreadDetector.getAsBoolean()) {
      throw new IllegalStateException(
          "REQ-RTP-S-005: ChartSnapshotAssembler.assemble() must not run on the main or region thread");
    }
    List<MetricBucketRow> rows =
        publisher.rangeScan(backendId, metricKey, fromEpochInclusive, toEpochInclusive);
    List<MetricBucketRow> bucketRef = (rows != null) ? rows : Collections.emptyList();
    int n = bucketRef.size();
    long[] timestamps = new long[n];
    double[] means = new double[n];
    double[] mins = new double[n];
    double[] maxes = new double[n];
    double[] p95s = new double[n];

    for (int i = 0; i < n; i++) {
      MetricBucketRow r = bucketRef.get(i);
      timestamps[i] = r.windowStartEpoch();
      means[i] = r.mean();
      mins[i] = r.min();
      maxes[i] = r.max();
      p95s[i] = r.p95() != null ? r.p95() : Double.NaN;
    }

    return new ChartSeriesSnapshot(
        backendId,
        metricKey,
        fromEpochInclusive,
        toEpochInclusive,
        bucketRef,
        timestamps,
        means,
        mins,
        maxes,
        p95s);
  }
}

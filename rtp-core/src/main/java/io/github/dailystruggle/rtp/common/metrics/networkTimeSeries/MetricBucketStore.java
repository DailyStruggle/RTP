package io.github.dailystruggle.rtp.common.metrics.networkTimeSeries;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Storage port for cross-backend metric time-series buckets (ADR-040, REQ-RTP-METTS-001,
 * REQ-RTP-METTS-005, REQ-RTP-METTS-006, REQ-RTP-METTS-008).
 */
public interface MetricBucketStore {

  /**
   * Idempotently ensures the backing schema or key layout exists (REQ-RTP-METTS-008).
   */
  void bootstrapSchema();

  /**
   * Writes a batch of {@link MetricBucketRow}s idempotently; duplicate
   * {@code (backend_id, metric_key, window_start_epoch)} keys are ignored (REQ-RTP-METTS-001).
   *
   * @param rows non-null batch of rows
   * @return number of newly inserted rows
   */
  int insertBatchIdempotent(List<MetricBucketRow> rows);

  /**
   * Deletes expired rows with {@code window_start_epoch < cutoffEpochExclusive} for the given
   * {@code backendId} only (REQ-RTP-METTS-005).
   *
   * @param backendId            originating backend identifier
   * @param cutoffEpochExclusive epoch seconds cutoff (exclusive)
   * @return number of deleted rows
   */
  int deleteExpired(String backendId, long cutoffEpochExclusive);

  /**
   * Range-scans rows for {@code metricKey} within {@code [fromEpochInclusive, toEpochInclusive]},
   * ordered by {@code window_start_epoch} ascending (REQ-RTP-METTS-006).
   *
   * @param backendId          specific backend id, or {@code null}/{@code "all"}/{@code "*"} for all backends
   * @param metricKey          metric key
   * @param fromEpochInclusive lower bound (inclusive, epoch seconds)
   * @param toEpochInclusive   upper bound (inclusive, epoch seconds)
   * @return immutable list of rows ordered by {@code window_start_epoch} ascending
   */
  List<MetricBucketRow> rangeScan(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive);
}

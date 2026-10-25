package io.github.dailystruggle.rtp.common.metrics.networkTimeSeries;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.database.options.RedisMetricBucketAccessor;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * Redis sorted-set {@link MetricBucketStore} delegating to {@link RedisMetricBucketAccessor}
 * (ADR-040, REQ-RTP-METTS-001, REQ-RTP-METTS-005, REQ-RTP-METTS-006, REQ-RTP-METTS-008).
 */
public final class RedisMetricBucketStore implements MetricBucketStore {

  private final RedisMetricBucketAccessor accessor;
  private final LongSupplier retentionSecondsSupplier;

  public RedisMetricBucketStore(
      RedisMetricBucketAccessor accessor, LongSupplier retentionSecondsSupplier) {
    this.accessor = Objects.requireNonNull(accessor, "accessor");
    this.retentionSecondsSupplier =
        Objects.requireNonNull(retentionSecondsSupplier, "retentionSecondsSupplier");
  }

  public RedisMetricBucketStore(RedisMetricBucketAccessor accessor, long retentionSeconds) {
    this(accessor, () -> retentionSeconds);
  }

  @Override
  public void bootstrapSchema() {
    // Redis sorted sets are created on ZADD with EXPIRE; schema bootstrap is a safe no-op.
  }

  @Override
  public int insertBatchIdempotent(List<MetricBucketRow> rows) {
    return accessor.insertMetricBuckets(rows, retentionSecondsSupplier.getAsLong());
  }

  @Override
  public int deleteExpired(String backendId, long cutoffEpochExclusive) {
    return accessor.purgeExpiredMetricBuckets(backendId, cutoffEpochExclusive);
  }

  @Override
  public List<MetricBucketRow> rangeScan(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive) {
    return accessor.queryMetricBuckets(
        backendId, metricKey, fromEpochInclusive, toEpochInclusive);
  }
}

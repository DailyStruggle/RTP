package io.github.dailystruggle.rtp.common.metrics.networkTimeSeries;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe in-memory {@link MetricBucketStore} enforcing composite primary key
 * {@code (backendId, metricKey, windowStartEpoch)} idempotency, local-backend retention sweep,
 * and sorted range scans.
 */
public final class InMemoryMetricBucketStore implements MetricBucketStore {

  public record PrimaryKey(String backendId, String metricKey, long windowStartEpoch) {}

  private final ConcurrentHashMap<PrimaryKey, MetricBucketRow> rows = new ConcurrentHashMap<>();
  private final AtomicInteger bootstrapCalls = new AtomicInteger(0);

  @Override
  public void bootstrapSchema() {
    bootstrapCalls.incrementAndGet();
  }

  @Override
  public int insertBatchIdempotent(List<MetricBucketRow> batch) {
    if (batch == null || batch.isEmpty()) return 0;
    for (MetricBucketRow row : batch) {
      if (row == null) {
        throw new IllegalArgumentException("MetricBucketRow batch element must not be null");
      }
    }
    int inserted = 0;
    for (MetricBucketRow row : batch) {
      PrimaryKey pk = new PrimaryKey(row.backendId(), row.metricKey(), row.windowStartEpoch());
      if (rows.putIfAbsent(pk, row) == null) {
        inserted++;
      }
    }
    return inserted;
  }

  @Override
  public int deleteExpired(String backendId, long cutoffEpochExclusive) {
    if (backendId == null || backendId.isBlank()) return 0;
    int removed = 0;
    Iterator<Map.Entry<PrimaryKey, MetricBucketRow>> it = rows.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<PrimaryKey, MetricBucketRow> entry = it.next();
      PrimaryKey pk = entry.getKey();
      if (backendId.equals(pk.backendId()) && pk.windowStartEpoch() < cutoffEpochExclusive) {
        it.remove();
        removed++;
      }
    }
    return removed;
  }

  @Override
  public List<MetricBucketRow> rangeScan(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive) {
    if (metricKey == null || metricKey.isBlank() || toEpochInclusive < fromEpochInclusive) {
      return Collections.emptyList();
    }
    boolean allBackends =
        backendId == null
            || backendId.isBlank()
            || "all".equalsIgnoreCase(backendId)
            || "*".equals(backendId);
    List<MetricBucketRow> out = new ArrayList<>();
    for (MetricBucketRow row : rows.values()) {
      if (!metricKey.equals(row.metricKey())) continue;
      if (!allBackends && !backendId.equals(row.backendId())) continue;
      long epoch = row.windowStartEpoch();
      if (epoch >= fromEpochInclusive && epoch <= toEpochInclusive) {
        out.add(row);
      }
    }
    out.sort(
        Comparator.comparingLong(MetricBucketRow::windowStartEpoch)
            .thenComparing(MetricBucketRow::backendId));
    return Collections.unmodifiableList(out);
  }

  /** @return total rows currently stored */
  public int size() {
    return rows.size();
  }

  /** @return count of {@link #bootstrapSchema()} invocations */
  public int bootstrapCallCount() {
    return bootstrapCalls.get();
  }
}

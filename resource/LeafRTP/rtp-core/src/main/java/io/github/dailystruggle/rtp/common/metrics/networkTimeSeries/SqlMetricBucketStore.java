package io.github.dailystruggle.rtp.common.metrics.networkTimeSeries;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * SQL-backed {@link MetricBucketStore} delegating to {@link AbstractSQLDatabaseAccessor}
 * (ADR-040, REQ-RTP-METTS-001, REQ-RTP-METTS-005, REQ-RTP-METTS-006, REQ-RTP-METTS-008).
 */
public final class SqlMetricBucketStore implements MetricBucketStore {

  private final Supplier<AbstractSQLDatabaseAccessor> accessorSupplier;

  /**
   * Constructs a store bound to a specific {@link AbstractSQLDatabaseAccessor}.
   */
  public SqlMetricBucketStore(AbstractSQLDatabaseAccessor accessor) {
    Objects.requireNonNull(accessor, "accessor");
    this.accessorSupplier = () -> accessor;
  }

  /**
   * Constructs a store that dynamically resolves {@link ActiveDatabase#accessor()}.
   */
  public SqlMetricBucketStore() {
    this.accessorSupplier =
        () -> {
          DatabaseAccessor<?> acc = ActiveDatabase.accessor();
          return (acc instanceof AbstractSQLDatabaseAccessor sql) ? sql : null;
        };
  }

  @Override
  public void bootstrapSchema() {
    AbstractSQLDatabaseAccessor sql = accessorSupplier.get();
    if (sql != null) {
      sql.ensureMetricBucketSchema();
    }
  }

  @Override
  public int insertBatchIdempotent(List<MetricBucketRow> rows) {
    AbstractSQLDatabaseAccessor sql = accessorSupplier.get();
    if (sql == null) {
      throw new IllegalStateException("No active SQL database accessor available");
    }
    return sql.insertMetricBuckets(rows);
  }

  @Override
  public int deleteExpired(String backendId, long cutoffEpochExclusive) {
    AbstractSQLDatabaseAccessor sql = accessorSupplier.get();
    if (sql == null) return 0;
    return sql.purgeExpiredMetricBuckets(backendId, cutoffEpochExclusive);
  }

  @Override
  public List<MetricBucketRow> rangeScan(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive) {
    AbstractSQLDatabaseAccessor sql = accessorSupplier.get();
    if (sql == null) return Collections.emptyList();
    return sql.queryMetricBuckets(backendId, metricKey, fromEpochInclusive, toEpochInclusive);
  }
}

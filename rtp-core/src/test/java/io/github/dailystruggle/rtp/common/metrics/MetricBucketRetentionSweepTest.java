package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkMetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkTimeSeriesConfig;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.SqlMetricBucketStore;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code REQ-RTP-METTS-005}: Retention sweep deletes rows older than
 * {@code metrics.networkTimeSeries.retentionDays} for the local {@code backend_id} only,
 * leaving peer backend rows and fresh local rows intact.
 */
@DisplayName("REQ-RTP-METTS-005: MetricBucketRetentionSweepTest")
class MetricBucketRetentionSweepTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("REQ-RTP-METTS-005: Periodic flush sweep deletes expired local backend rows while preserving peer backend rows")
  void retentionSweepDeletesOnlyLocalExpiredRows() {
    File sqliteFile = tempDir.resolve("retention_sweep.db").toFile();
    SQLiteDatabaseAccessor sqlite =
        new SQLiteDatabaseAccessor("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
    try {
      SqlMetricBucketStore store = new SqlMetricBucketStore(sqlite);
      store.bootstrapSchema();

      long nowEpoch = 2_000_000_000L;
      long retentionSeconds = 7L * 86_400L; // 604800s
      long cutoffEpoch = nowEpoch - retentionSeconds;

      MetricBucketRow localExpired =
          new MetricBucketRow("surv-local", "mspt", cutoffEpoch - 60L, 60, 60, 12.0, 8.0, 18.0, 16.0, 1L);
      MetricBucketRow localAtCutoff =
          new MetricBucketRow("surv-local", "mspt", cutoffEpoch, 60, 60, 13.0, 9.0, 19.0, 17.0, 1L);
      MetricBucketRow localFresh =
          new MetricBucketRow("surv-local", "mspt", nowEpoch - 60L, 60, 60, 14.0, 10.0, 20.0, 18.0, 1L);
      MetricBucketRow peerExpired =
          new MetricBucketRow("surv-peer", "mspt", cutoffEpoch - 3600L, 60, 60, 22.0, 15.0, 35.0, 31.0, 1L);

      store.insertBatchIdempotent(List.of(localExpired, localAtCutoff, localFresh, peerExpired));

      NetworkMetricTimeSeriesPublisher localPublisher =
          new NetworkMetricTimeSeriesPublisher(
              () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
              () -> true,
              () -> "surv-local",
              () -> nowEpoch,
              () -> false,
              store,
              null);

      // Run periodic flush pass on surv-local at nowEpoch
      localPublisher.flushPass(nowEpoch);
      assertEquals(1L, localPublisher.totalExpiredDeletedRows());

      List<MetricBucketRow> localRows =
          localPublisher.rangeScan("surv-local", "mspt", 0L, nowEpoch);
      assertEquals(2, localRows.size(), "Only local rows older than cutoffEpoch must be deleted");
      assertEquals(cutoffEpoch, localRows.get(0).windowStartEpoch());
      assertEquals(nowEpoch - 60L, localRows.get(1).windowStartEpoch());

      List<MetricBucketRow> peerRows =
          localPublisher.rangeScan("surv-peer", "mspt", 0L, nowEpoch);
      assertEquals(
          1,
          peerRows.size(),
          "Local retention sweep must never delete rows owned by peer backend_id");
      assertEquals(cutoffEpoch - 3600L, peerRows.get(0).windowStartEpoch());
    } finally {
      sqlite.close();
    }
  }
}

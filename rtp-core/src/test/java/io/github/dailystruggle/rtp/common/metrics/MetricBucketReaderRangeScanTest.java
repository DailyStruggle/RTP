package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.ChartSnapshotAssembler;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.ChartSnapshotAssembler.ChartSeriesSnapshot;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkMetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkTimeSeriesConfig;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.SqlMetricBucketStore;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code REQ-RTP-METTS-006}: Reader range-scan returns rows in
 * {@code window_start_epoch} order and is consumed by {@link ChartSnapshotAssembler}
 * without copy on the main/region thread ({@code REQ-RTP-S-005} regression).
 */
@DisplayName("REQ-RTP-METTS-006 / REQ-RTP-S-005: MetricBucketReaderRangeScanTest")
class MetricBucketReaderRangeScanTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("REQ-RTP-METTS-006: Range-scan returns buckets ordered by window_start_epoch and ChartSnapshotAssembler consumes them off-thread without copying")
  void rangeScanOrdersByWindowStartAndRejectsMainThread() {
    File sqliteFile = tempDir.resolve("range_scan.db").toFile();
    SQLiteDatabaseAccessor sqlite =
        new SQLiteDatabaseAccessor("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
    try {
      SqlMetricBucketStore store = new SqlMetricBucketStore(sqlite);
      AtomicBoolean onMainThread = new AtomicBoolean(false);
      AtomicReference<List<MetricBucketRow>> lastReturnedList = new AtomicReference<>();

      NetworkMetricTimeSeriesPublisher publisher =
          new NetworkMetricTimeSeriesPublisher(
              () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
              () -> true,
              () -> "surv-1",
              () -> 1_900_000_300L,
              onMainThread::get,
              new io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.MetricBucketStore() {
                @Override
                public void bootstrapSchema() {
                  store.bootstrapSchema();
                }

                @Override
                public int insertBatchIdempotent(List<MetricBucketRow> rows) {
                  return store.insertBatchIdempotent(rows);
                }

                @Override
                public int deleteExpired(String backendId, long cutoffEpochExclusive) {
                  return store.deleteExpired(backendId, cutoffEpochExclusive);
                }

                @Override
                public List<MetricBucketRow> rangeScan(
                    String backendId,
                    String metricKey,
                    long fromEpochInclusive,
                    long toEpochInclusive) {
                  List<MetricBucketRow> res =
                      store.rangeScan(backendId, metricKey, fromEpochInclusive, toEpochInclusive);
                  lastReturnedList.set(res);
                  return res;
                }
              },
              null);

      // Enqueue out-of-order epochs across two backends
      publisher.enqueue(
          new MetricBucketRow("surv-1", "mspt", 1_900_000_180L, 60, 60, 18.0, 12.0, 28.0, 25.0, 1L));
      publisher.enqueue(
          new MetricBucketRow("surv-1", "mspt", 1_900_000_060L, 60, 60, 12.0, 8.0, 19.0, 17.0, 1L));
      publisher.enqueue(
          new MetricBucketRow("surv-1", "mspt", 1_900_000_120L, 60, 60, 15.0, 10.0, 24.0, 22.0, 1L));
      publisher.enqueue(
          new MetricBucketRow("surv-2", "mspt", 1_900_000_090L, 60, 60, 20.0, 15.0, 30.0, 28.0, 1L));
      publisher.flushPass(1_900_000_300L);

      ChartSnapshotAssembler assembler =
          new ChartSnapshotAssembler(publisher, onMainThread::get);
      ChartSeriesSnapshot series =
          assembler.assemble("surv-1", "mspt", 1_900_000_000L, 1_900_000_200L);

      assertEquals(3, series.buckets().size());
      assertSame(
          lastReturnedList.get(),
          series.buckets(),
          "ChartSnapshotAssembler must consume range-scan list without copying");
      assertArrayEquals(
          new long[] {1_900_000_060L, 1_900_000_120L, 1_900_000_180L}, series.timestamps());
      assertArrayEquals(new double[] {12.0, 15.0, 18.0}, series.means(), 1e-6);
      assertArrayEquals(new double[] {8.0, 10.0, 12.0}, series.mins(), 1e-6);
      assertArrayEquals(new double[] {19.0, 24.0, 28.0}, series.maxes(), 1e-6);
      assertArrayEquals(new double[] {17.0, 22.0, 25.0}, series.p95s(), 1e-6);

      // Cluster-wide (--backend all) range scan returns all backends ordered by window_start_epoch
      List<MetricBucketRow> allRows =
          publisher.rangeScan("all", "mspt", 1_900_000_000L, 1_900_000_200L);
      assertEquals(4, allRows.size());
      assertEquals(1_900_000_060L, allRows.get(0).windowStartEpoch());
      assertEquals(1_900_000_090L, allRows.get(1).windowStartEpoch());
      assertEquals(1_900_000_120L, allRows.get(2).windowStartEpoch());
      assertEquals(1_900_000_180L, allRows.get(3).windowStartEpoch());

      // S-005 regression: invoking rangeScan or assemble on the main/region thread must fail fast
      onMainThread.set(true);
      assertThrows(
          IllegalStateException.class,
          () -> publisher.rangeScan("surv-1", "mspt", 1_900_000_000L, 1_900_000_200L));
      assertThrows(
          IllegalStateException.class,
          () -> assembler.assemble("surv-1", "mspt", 1_900_000_000L, 1_900_000_200L));
    } finally {
      sqlite.close();
    }
  }
}

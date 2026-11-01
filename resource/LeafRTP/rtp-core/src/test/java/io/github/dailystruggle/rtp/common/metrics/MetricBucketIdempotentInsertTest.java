package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.database.options.H2DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.RedisMetricBucketAccessor;
import io.github.dailystruggle.rtp.common.database.options.SQLiteDatabaseAccessor;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.InMemoryMetricBucketStore;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkMetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkTimeSeriesConfig;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.RedisMetricBucketStore;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.SqlMetricBucketStore;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code REQ-RTP-METTS-001}: Publisher writes exactly one row per
 * {@code (backend_id, metric_key, window_start_epoch)} and re-publication is a no-op.
 */
@DisplayName("REQ-RTP-METTS-001: MetricBucketIdempotentInsertTest")
class MetricBucketIdempotentInsertTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("REQ-RTP-METTS-001: SQLite re-publication of identical primary key is a no-op")
  void sqliteRepublicationIsNoOp() {
    File sqliteFile = tempDir.resolve("metrics_sqlite.db").toFile();
    SQLiteDatabaseAccessor sqlite =
        new SQLiteDatabaseAccessor("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
    try {
      SqlMetricBucketStore store = new SqlMetricBucketStore(sqlite);
      NetworkMetricTimeSeriesPublisher publisher =
          new NetworkMetricTimeSeriesPublisher(
              () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
              () -> true,
              () -> "lobby-1",
              () -> 1_700_000_120L,
              () -> false,
              store,
              null);

      MetricBucketRow first =
          new MetricBucketRow("lobby-1", "mspt", 1_700_000_000L, 60, 60, 12.5, 8.0, 22.0, 19.5, 1L);
      // Duplicate composite key (lobby-1, mspt, 1_700_000_000L) with different stats/generation
      MetricBucketRow duplicate =
          new MetricBucketRow("lobby-1", "mspt", 1_700_000_000L, 60, 60, 40.0, 30.0, 50.0, 48.0, 2L);
      MetricBucketRow nextWindow =
          new MetricBucketRow("lobby-1", "mspt", 1_700_000_060L, 60, 60, 14.0, 9.0, 25.0, 21.0, 1L);

      publisher.enqueue(first);
      publisher.enqueue(duplicate);
      publisher.enqueue(nextWindow);
      publisher.flushPass(1_700_000_120L);

      // Re-publish again in a second pass
      publisher.enqueue(first);
      publisher.flushPass(1_700_000_120L);

      List<MetricBucketRow> rows =
          publisher.rangeScan("lobby-1", "mspt", 1_700_000_000L, 1_700_000_120L);
      assertEquals(2, rows.size(), "Duplicate (backend_id, metric_key, window_start_epoch) must not insert a second row");
      assertEquals(12.5, rows.get(0).mean(), 1e-6, "First published row must be preserved on conflict");
      assertEquals(1L, rows.get(0).generation());
      assertEquals(1_700_000_060L, rows.get(1).windowStartEpoch());
    } finally {
      sqlite.close();
    }
  }

  @Test
  @DisplayName("REQ-RTP-METTS-001: H2 re-publication of identical primary key is a no-op")
  void h2RepublicationIsNoOp() {
    File h2File = tempDir.resolve("metrics_h2").toFile();
    H2DatabaseAccessor h2 =
        new H2DatabaseAccessor(
            "jdbc:h2:" + h2File.getAbsolutePath() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    try {
      SqlMetricBucketStore store = new SqlMetricBucketStore(h2);
      MetricBucketRow row1 =
          new MetricBucketRow("surv-eu-1", "mspt", 1_700_001_000L, 60, 60, 15.0, 10.0, 25.0, 23.0, 5L);
      MetricBucketRow row1Dup =
          new MetricBucketRow("surv-eu-1", "mspt", 1_700_001_000L, 60, 60, 35.0, 30.0, 45.0, 42.0, 6L);

      store.bootstrapSchema();
      store.insertBatchIdempotent(List.of(row1));
      store.insertBatchIdempotent(List.of(row1Dup));

      List<MetricBucketRow> rows =
          store.rangeScan("surv-eu-1", "mspt", 1_700_000_000L, 1_700_002_000L);
      assertEquals(1, rows.size());
      assertEquals(15.0, rows.get(0).mean(), 1e-6);
      assertEquals(5L, rows.get(0).generation());
    } finally {
      h2.close();
    }
  }

  @Test
  @DisplayName("REQ-RTP-METTS-001: Redis sorted-set re-publication of identical primary key is a no-op")
  void redisRepublicationIsNoOp() {
    Map<String, NavigableMap<Long, List<String>>> zsets = new HashMap<>();
    RedisMetricBucketAccessor redisAccessor =
        new RedisMetricBucketAccessor(
            args -> {
              String cmd = args[0];
              String key = args[1];
              if ("ZRANGEBYSCORE".equals(cmd)) {
                long min = Long.parseLong(args[2]);
                long max = Long.parseLong(args[3]);
                NavigableMap<Long, List<String>> zset = zsets.get(key);
                if (zset == null) return List.of();
                List<String> res = new ArrayList<>();
                for (List<String> members : zset.subMap(min, true, max, true).values()) {
                  res.addAll(members);
                }
                return res;
              } else if ("ZADD".equals(cmd)) {
                long score = Long.parseLong(args[2]);
                String member = args[3];
                NavigableMap<Long, List<String>> zset =
                    zsets.computeIfAbsent(key, k -> new TreeMap<>());
                List<String> bucket = zset.computeIfAbsent(score, s -> new ArrayList<>());
                if (!bucket.contains(member)) {
                  bucket.add(member);
                  return 1L;
                }
                return 0L;
              } else if ("EXPIRE".equals(cmd)) {
                return 1L;
              }
              return null;
            });

    RedisMetricBucketStore store = new RedisMetricBucketStore(redisAccessor, 7L * 86_400L);
    MetricBucketRow original =
        new MetricBucketRow("surv-us-1", "queue_depth", 1_700_000_600L, 60, 60, 4.0, 1.0, 9.0, 8.0, 10L);
    MetricBucketRow replay =
        new MetricBucketRow("surv-us-1", "queue_depth", 1_700_000_600L, 60, 60, 99.0, 90.0, 110.0, 105.0, 11L);

    assertEquals(1, store.insertBatchIdempotent(List.of(original)));
    assertEquals(0, store.insertBatchIdempotent(List.of(replay)));

    List<MetricBucketRow> rows =
        store.rangeScan("surv-us-1", "queue_depth", 1_700_000_000L, 1_700_001_000L);
    assertEquals(1, rows.size());
    assertEquals(4.0, rows.get(0).mean(), 1e-6);
    assertEquals(10L, rows.get(0).generation());

    // In-memory store parity check
    InMemoryMetricBucketStore memStore = new InMemoryMetricBucketStore();
    assertEquals(1, memStore.insertBatchIdempotent(List.of(original)));
    assertEquals(0, memStore.insertBatchIdempotent(List.of(replay)));
    assertEquals(1, memStore.size());
  }
}

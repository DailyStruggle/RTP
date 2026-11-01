package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.InMemoryMetricBucketStore;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkMetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkTimeSeriesConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code REQ-RTP-METTS-004}: Per-pass batch cap ({@code perPassWriteCap}, default 64)
 * is honored; overflow defers without silent drop and emits a structured warning ({@code S-004}).
 */
@DisplayName("REQ-RTP-METTS-004 / REQ-RTP-S-004: MetricBucketBatchCapAndOverflowTest")
class MetricBucketBatchCapAndOverflowTest {

  @Test
  @DisplayName("REQ-RTP-METTS-004: Flushing 150 queued buckets under default perPassWriteCap=64 writes 64, 64, 22 without silent drop and logs S-004 warnings")
  void batchCapDefersOverflowAndLogsStructuredWarning() {
    InMemoryMetricBucketStore store = new InMemoryMetricBucketStore();
    List<String> warnings = new ArrayList<>();
    NetworkMetricTimeSeriesPublisher publisher =
        new NetworkMetricTimeSeriesPublisher(
            () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
            () -> true,
            () -> "surv-1",
            () -> 1_800_010_000L,
            () -> false,
            store,
            warnings::add);

    long baseEpoch = 1_800_000_000L;
    for (int i = 0; i < 150; i++) {
      publisher.enqueue(
          new MetricBucketRow(
              "surv-1",
              "metric_" + (i % 5),
              baseEpoch + (i * 60L),
              60,
              60,
              15.0,
              10.0,
              20.0,
              18.0,
              1L));
    }
    assertEquals(150, publisher.pendingCount());

    // Pass 1: writes 64, defers 86, emits structured S-004 warning
    int pass1 = publisher.flushPass(baseEpoch + 10_000L);
    assertEquals(64, pass1);
    assertEquals(86, publisher.pendingCount());
    assertEquals(64, store.size());
    assertEquals(1L, publisher.overflowWarningCount());
    assertEquals(1L, publisher.consecutiveOverflowPasses());
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("REQ-RTP-METTS-004"));
    assertTrue(warnings.get(0).contains("S-004"));
    assertTrue(warnings.get(0).contains("deferredOverflow=86"));

    // Pass 2: writes 64, defers 22, emits second structured S-004 warning (sustained overflow)
    int pass2 = publisher.flushPass(baseEpoch + 10_060L);
    assertEquals(64, pass2);
    assertEquals(22, publisher.pendingCount());
    assertEquals(128, store.size());
    assertEquals(2L, publisher.overflowWarningCount());
    assertEquals(2L, publisher.consecutiveOverflowPasses());
    assertEquals(2, warnings.size());
    assertTrue(warnings.get(1).contains("consecutiveOverflowPasses=2"));

    // Pass 3: writes remaining 22, queue drains to 0, consecutive overflow counter resets
    int pass3 = publisher.flushPass(baseEpoch + 10_120L);
    assertEquals(22, pass3);
    assertEquals(0, publisher.pendingCount());
    assertEquals(150, store.size(), "All 150 rows must be persisted across passes with zero silent drops");
    assertEquals(0L, publisher.consecutiveOverflowPasses());
    assertEquals(2, warnings.size(), "No new overflow warning when queue fully drains");
  }
}

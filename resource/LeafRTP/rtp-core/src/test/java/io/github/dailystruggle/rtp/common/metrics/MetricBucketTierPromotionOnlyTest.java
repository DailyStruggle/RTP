package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.InMemoryMetricBucketStore;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkMetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkTimeSeriesConfig;
import io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code REQ-RTP-METTS-002}: Publisher writes only from the configured published tier
 * on promotion; per-sample and per-tick writes never occur.
 */
@DisplayName("REQ-RTP-METTS-002: MetricBucketTierPromotionOnlyTest")
class MetricBucketTierPromotionOnlyTest {

  @Test
  @DisplayName("REQ-RTP-METTS-002: 1 Hz samples within a 60s window do not publish until the 24h@1m tier boundary promotes")
  void publishesOnlyOnConfiguredMinuteTierPromotion() {
    InMemoryMetricBucketStore store = new InMemoryMetricBucketStore();
    NetworkMetricTimeSeriesPublisher publisher =
        new NetworkMetricTimeSeriesPublisher(
            () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
            () -> true,
            () -> "backend-alpha",
            () -> 1_800_000_060L,
            () -> false,
            store,
            null);

    MetricsSnapshotRing ring =
        new MetricsSnapshotRing("backend-alpha", "24h@1m", 7L, publisher::enqueue);

    long baseEpoch = 1_800_000_000L; // aligned to 60s boundary
    // Record 60 one-second samples across [baseEpoch, baseEpoch + 59].
    // Five 1h@10s buckets complete inside this minute, but the 24h@1m bucket has not crossed baseEpoch + 60 yet.
    for (int sec = 0; sec < 60; sec++) {
      ring.recordSample("mspt", 10.0 + sec, baseEpoch + sec);
      assertEquals(
          0,
          publisher.pendingCount(),
          "Per-sample / per-tick or sub-tier (1h@10s) promotions must never enqueue when publishedTier=24h@1m (sec="
              + sec
              + ")");
    }
    publisher.flushPass(baseEpoch + 59);
    assertEquals(0, store.size(), "Zero rows may be written before the 24h@1m bucket boundary is crossed");

    // Record first sample of the next minute [baseEpoch + 60, baseEpoch + 120):
    // this promotes the completed [baseEpoch, baseEpoch + 60) bucket.
    ring.recordSample("mspt", 25.0, baseEpoch + 60);
    assertEquals(1, publisher.pendingCount(), "Exactly one bucket must be queued upon 24h@1m tier promotion");

    int written = publisher.flushPass(baseEpoch + 60);
    assertEquals(1, written);
    assertEquals(1, store.size());

    List<MetricBucketRow> rows =
        publisher.rangeScan("backend-alpha", "mspt", baseEpoch, baseEpoch + 120);
    assertEquals(1, rows.size());
    MetricBucketRow row = rows.get(0);
    assertEquals("backend-alpha", row.backendId());
    assertEquals("mspt", row.metricKey());
    assertEquals(baseEpoch, row.windowStartEpoch());
    assertEquals(60, row.windowSeconds());
    assertEquals(60, row.sampleCount());
    assertEquals(10.0, row.min(), 1e-6);
    assertEquals(69.0, row.max(), 1e-6);
    assertEquals(39.5, row.mean(), 1e-6);
    assertNotNull(row.p95());
    assertTrue(row.p95() >= 66.0 && row.p95() <= 69.0);
    assertEquals(7L, row.generation());
  }

  @Test
  @DisplayName("REQ-RTP-METTS-002: Configuring tier=1h@10s promotes on 10s boundaries while forbidding 1s per-sample writes")
  void honorsConfigured10sPublishedTierWithoutPerSampleWrites() {
    List<MetricBucketRow> promoted = new ArrayList<>();
    MetricsSnapshotRing ring =
        new MetricsSnapshotRing("backend-beta", "1h@10s", 3L, promoted::add);

    long base = 1_800_000_000L;
    for (int s = 0; s < 10; s++) {
      ring.recordSample("tps_1m", 20.0 - (s * 0.1), base + s);
      assertEquals(0, promoted.size(), "Must not emit per-sample writes inside 10s window");
    }

    // Crossing the 10s boundary promotes the [base, base+10) bucket
    ring.recordSample("tps_1m", 19.8, base + 10);
    assertEquals(1, promoted.size());
    assertEquals(base, promoted.get(0).windowStartEpoch());
    assertEquals(10, promoted.get(0).windowSeconds());
    assertEquals(10, promoted.get(0).sampleCount());
  }

  @Test
  @DisplayName("REQ-RTP-METTS-002: CoreMetrics.instance().snapshotRing() enqueues across 24h@1m boundary with live generation and flushTimeSeriesBuckets() flushes")
  void coreMetricsSingletonEnqueuesWithLiveGenerationAndFlushes() {
    CoreMetrics coreMetrics = CoreMetrics.instance();
    NetworkMetricTimeSeriesPublisher previousPublisher = coreMetrics.timeSeriesPublisher();
    NetworkModeBootstrap previousLive = NetworkModeBootstrap.LIVE;

    InMemoryMetricBucketStore store = new InMemoryMetricBucketStore();
    long baseEpoch = 1_850_000_040L; // aligned to 60s boundary (1_850_000_040 % 60 == 0)
    String metricKey = "core_metrics_flush_probe";
    NetworkMetricTimeSeriesPublisher publisher =
        new NetworkMetricTimeSeriesPublisher(
            () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
            () -> true,
            () -> "backend-live",
            () -> baseEpoch + 60L,
            () -> false,
            store,
            null);

    NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
    bootstrap.setGeneration(42L);
    try {
      NetworkModeBootstrap.LIVE = bootstrap;
      coreMetrics.setTimeSeriesPublisher(publisher);

      coreMetrics.snapshotRing().recordSample(metricKey, 12.0, baseEpoch);
      coreMetrics.snapshotRing().recordSample(metricKey, 18.0, baseEpoch + 30L);
      assertEquals(0, publisher.pendingCount());

      // Advance across the 24h@1m bucket boundary [baseEpoch, baseEpoch + 60)
      coreMetrics.snapshotRing().recordSample(metricKey, 15.0, baseEpoch + 60L);
      assertTrue(publisher.pendingCount() >= 1, "Crossing 24h@1m boundary must enqueue a MetricBucketRow");

      int flushed = coreMetrics.flushTimeSeriesBuckets();
      assertTrue(flushed >= 1, "flushTimeSeriesBuckets() must flush the enqueued row");
      assertEquals(0, publisher.pendingCount());

      List<MetricBucketRow> rows =
          publisher.rangeScan(null, metricKey, baseEpoch, baseEpoch + 120L);
      assertEquals(1, rows.size());
      MetricBucketRow row = rows.get(0);
      assertEquals(metricKey, row.metricKey());
      assertEquals(baseEpoch, row.windowStartEpoch());
      assertEquals(60, row.windowSeconds());
      assertEquals(2, row.sampleCount());
      assertEquals(15.0, row.mean(), 1e-6);
      assertEquals(12.0, row.min(), 1e-6);
      assertEquals(18.0, row.max(), 1e-6);
      assertEquals(42L, row.generation(), "Must stamp the live generation from NetworkModeBootstrap.LIVE");
    } finally {
      NetworkModeBootstrap.LIVE = previousLive;
      coreMetrics.setTimeSeriesPublisher(previousPublisher);
    }
  }
}

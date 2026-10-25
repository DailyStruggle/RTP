package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.api.metrics.MetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.InMemoryMetricBucketStore;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkMetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.metrics.networkTimeSeries.NetworkTimeSeriesConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code REQ-RTP-METTS-003}: Row payload always carries {@code (count, mean, min, max)}
 * and optional {@code p95}; mean-only and max-only payloads are rejected at construction and at
 * the publisher boundary. Also verifies {@code REQ-RTP-S-006} pre-init guard on
 * {@link MetricTimeSeriesPublisher}.
 */
@DisplayName("REQ-RTP-METTS-003 / REQ-RTP-S-006: MetricBucketHybridPayloadTest")
class MetricBucketHybridPayloadTest {

  @Test
  @DisplayName("REQ-RTP-METTS-003: Valid hybrid payload with and without optional p95 constructs cleanly")
  void validHybridPayloadsConstructCleanly() {
    MetricBucketRow withP95 =
        new MetricBucketRow("surv-1", "mspt", 1_700_000_000L, 60, 60, 14.2, 8.5, 31.0, 27.4, 2L);
    assertEquals(60, withP95.sampleCount());
    assertEquals(14.2, withP95.mean(), 1e-6);
    assertEquals(8.5, withP95.min(), 1e-6);
    assertEquals(31.0, withP95.max(), 1e-6);
    assertEquals(27.4, withP95.p95(), 1e-6);

    MetricBucketRow withoutP95 =
        new MetricBucketRow("surv-1", "mspt", 1_700_000_060L, 60, 60, 12.0, 9.0, 18.0, 2L);
    assertNull(withoutP95.p95());
  }

  @Test
  @DisplayName("REQ-RTP-METTS-003: Mean-only payloads (missing min/max) are rejected at construction and publisher boundary")
  void meanOnlyPayloadsAreRejected() {
    // Constructor with NaN min/max (mean-only)
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricBucketRow(
                "surv-1", "mspt", 1_700_000_000L, 60, 60, 15.0, Double.NaN, Double.NaN, null, 1L));

    // Builder with only mean set (min/max omitted)
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricBucketRow.builder()
                .backendId("surv-1")
                .metricKey("mspt")
                .windowStartEpoch(1_700_000_000L)
                .windowSeconds(60)
                .sampleCount(60)
                .mean(15.0)
                .generation(1L)
                .build());

    // Map payload with only mean (missing min/max) rejected at publisher boundary
    NetworkMetricTimeSeriesPublisher publisher =
        new NetworkMetricTimeSeriesPublisher(
            () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
            () -> true,
            () -> "surv-1",
            () -> 1_700_000_120L,
            () -> false,
            new InMemoryMetricBucketStore(),
            null);
    Map<String, Object> meanOnlyMap =
        Map.of(
            "backend_id", "surv-1",
            "metric_key", "mspt",
            "window_start_epoch", 1_700_000_000L,
            "window_seconds", 60,
            "sample_count", 60,
            "mean", 15.0,
            "generation", 1L);
    assertThrows(IllegalArgumentException.class, () -> publisher.enqueueFromMap(meanOnlyMap));
    assertThrows(IllegalArgumentException.class, () -> publisher.enqueue(null));
  }

  @Test
  @DisplayName("REQ-RTP-METTS-003: Max-only payloads (missing mean or min) and inconsistent bounds are rejected")
  void maxOnlyAndInconsistentBoundsAreRejected() {
    // Constructor with NaN mean (max-only)
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricBucketRow(
                "surv-1", "mspt", 1_700_000_000L, 60, 60, Double.NaN, 5.0, 40.0, null, 1L));

    // Builder with only max set
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetricBucketRow.builder()
                .backendId("surv-1")
                .metricKey("mspt")
                .windowStartEpoch(1_700_000_000L)
                .windowSeconds(60)
                .sampleCount(60)
                .max(40.0)
                .generation(1L)
                .build());

    // Map payload with only max rejected at publisher boundary
    Map<String, Object> maxOnlyMap =
        Map.of(
            "backend_id", "surv-1",
            "metric_key", "mspt",
            "window_start_epoch", 1_700_000_000L,
            "window_seconds", 60,
            "sample_count", 60,
            "max", 40.0,
            "generation", 1L);
    assertThrows(IllegalArgumentException.class, () -> MetricBucketRow.fromMap(maxOnlyMap));

    // min > max
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("surv-1", "mspt", 1_700_000_000L, 60, 60, 15.0, 20.0, 10.0, 1L));

    // mean outside [min, max]
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("surv-1", "mspt", 1_700_000_000L, 60, 60, 25.0, 10.0, 20.0, 1L));

    // p95 outside [min, max]
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetricBucketRow(
                "surv-1", "mspt", 1_700_000_000L, 60, 60, 15.0, 10.0, 20.0, 25.0, 1L));
  }

  @Test
  @DisplayName("REQ-RTP-S-006: MetricTimeSeriesPublisher throws IllegalStateException when called before core loads")
  void preInitGuardThrowsIllegalStateException() {
    MetricTimeSeriesPublisher prev =
        MetricTimeSeriesPublisher.isRegistered() ? MetricTimeSeriesPublisher.require() : null;
    try {
      MetricTimeSeriesPublisher.clear();
      IllegalStateException ex =
          assertThrows(IllegalStateException.class, MetricTimeSeriesPublisher::require);
      assertTrue(ex.getMessage().contains("Core implementation is not loaded"));

      MetricBucketRow sample =
          new MetricBucketRow("surv-1", "mspt", 1_700_000_000L, 60, 60, 10.0, 5.0, 15.0, 1L);
      assertThrows(
          IllegalStateException.class, () -> MetricTimeSeriesPublisher.publishBucket(sample));
      assertThrows(
          IllegalStateException.class,
          () -> MetricTimeSeriesPublisher.queryRange("surv-1", "mspt", 0L, 100L));
      assertThrows(
          IllegalStateException.class, () -> MetricTimeSeriesPublisher.UNINITIALIZED.enqueue(sample));
      assertThrows(
          IllegalStateException.class, MetricTimeSeriesPublisher.UNINITIALIZED::flush);
    } finally {
      MetricTimeSeriesPublisher.register(prev);
    }
  }
}

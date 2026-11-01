package io.github.dailystruggle.rtp.api.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link MetricBucketRow} and {@link MetricTimeSeriesPublisher} contracts in {@code rtp-api}
 * (ADR-040, REQ-RTP-METTS-001, REQ-RTP-METTS-003, REQ-RTP-S-006).
 */
@DisplayName("REQ-RTP-METTS-003: MetricBucketRow and MetricTimeSeriesPublisher API contract")
class MetricBucketRowTest {

  @AfterEach
  void tearDown() {
    MetricTimeSeriesPublisher.clear();
  }

  @Test
  @DisplayName("REQ-RTP-METTS-003: Valid rows construct, expose getters, pack/unpack Redis members, and convert to/from maps")
  void validRowRoundTripsAndGetters() {
    MetricBucketRow withP95 =
        new MetricBucketRow("surv-1", "mspt", 1_700_000_000L, 60, 60, 12.5, 8.0, 20.0, 18.5, 3L);
    assertEquals("surv-1", withP95.getBackendId());
    assertEquals("mspt", withP95.getMetricKey());
    assertEquals(1_700_000_000L, withP95.getWindowStartEpoch());
    assertEquals(60, withP95.getWindowSeconds());
    assertEquals(60, withP95.getSampleCount());
    assertEquals(12.5, withP95.getMean());
    assertEquals(8.0, withP95.getMin());
    assertEquals(20.0, withP95.getMax());
    assertEquals(18.5, withP95.getP95());
    assertEquals(3L, withP95.getGeneration());

    String packedWithP95 = withP95.packRedisMember();
    assertEquals(withP95, MetricBucketRow.unpackRedisMember("surv-1", "mspt", packedWithP95));

    Map<String, Object> mapWithP95 = withP95.toMap();
    assertEquals(withP95, MetricBucketRow.fromMap(mapWithP95));

    MetricBucketRow withoutP95 =
        new MetricBucketRow("surv-1", "tps", 1_700_000_060L, 60, 60, 20.0, 19.5, 20.0, 4L);
    assertNull(withoutP95.getP95());
    String packedNoP95 = withoutP95.packRedisMember();
    assertEquals(withoutP95, MetricBucketRow.unpackRedisMember("surv-1", "tps", packedNoP95));
    assertEquals(withoutP95, MetricBucketRow.fromMap(withoutP95.toMap()));
  }

  @Test
  @DisplayName("REQ-RTP-METTS-003: Constructor and builder reject invalid fields and single-statistic payloads")
  void constructorAndBuilderRejectInvalidPayloads() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow(null, "mspt", 0L, 60, 1, 1.0, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("  ", "mspt", 0L, 60, 1, 1.0, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", null, 0L, 60, 1, 1.0, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", " ", 0L, 60, 1, 1.0, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", -1L, 60, 1, 1.0, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 0, 1, 1.0, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 0, 1.0, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, Double.NaN, 1.0, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 1.0, Double.NaN, 1.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 1.0, 1.0, Double.NaN, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 5.0, 10.0, 2.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 0.5, 1.0, 2.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 2.5, 1.0, 2.0, null, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 1.5, 1.0, 2.0, Double.NaN, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 1.5, 1.0, 2.0, 0.5, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 1.5, 1.0, 2.0, 2.5, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetricBucketRow("b1", "mspt", 0L, 60, 1, 1.5, 1.0, 2.0, 1.8, -1L));

    MetricBucketRow built =
        MetricBucketRow.builder()
            .backendId("b1")
            .metricKey("mspt")
            .windowStartEpoch(120L)
            .windowSeconds(60)
            .sampleCount(10)
            .mean(15.0)
            .min(10.0)
            .max(20.0)
            .p95(19.0)
            .generation(2L)
            .build();
    assertEquals(15.0, built.mean());
  }

  @Test
  @DisplayName("REQ-RTP-METTS-003: unpackRedisMember and fromMap handle alternate keys and reject malformed inputs")
  void unpackAndFromMapEdgeCases() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MetricBucketRow.unpackRedisMember("b1", "mspt", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetricBucketRow.unpackRedisMember("b1", "mspt", "   "));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetricBucketRow.unpackRedisMember("b1", "mspt", "1|2|3"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetricBucketRow.unpackRedisMember("b1", "mspt", "bad|60|1|1.0|1.0|1.0||0"));

    Map<String, Object> camelMap = new LinkedHashMap<>();
    camelMap.put("backendId", "b-camel");
    camelMap.put("metricKey", "mspt");
    camelMap.put("windowStartEpoch", "1700000000");
    camelMap.put("windowSeconds", "60");
    camelMap.put("sampleCount", "30");
    camelMap.put("mean", "12.0");
    camelMap.put("min", "10.0");
    camelMap.put("max", "15.0");
    camelMap.put("p95", "14.5");
    camelMap.put("generation", "5");

    MetricBucketRow parsed = MetricBucketRow.fromMap(camelMap);
    assertEquals("b-camel", parsed.backendId());
    assertEquals(14.5, parsed.p95());

    Map<String, Object> badNumbers = new LinkedHashMap<>();
    badNumbers.put("backend_id", "b1");
    badNumbers.put("metric_key", "mspt");
    badNumbers.put("window_start_epoch", "not-a-long");
    badNumbers.put("window_seconds", "not-an-int");
    badNumbers.put("sample_count", "not-an-int");
    badNumbers.put("mean", "not-a-double");
    badNumbers.put("min", null);
    badNumbers.put("max", "10.0");
    badNumbers.put("generation", "not-a-long");
    assertThrows(IllegalArgumentException.class, () -> MetricBucketRow.fromMap(badNumbers));

    Map<String, Object> nullKeys = new LinkedHashMap<>();
    assertThrows(IllegalArgumentException.class, () -> MetricBucketRow.fromMap(nullKeys));
  }

  @Test
  @DisplayName("REQ-RTP-S-006: MetricTimeSeriesPublisher enforces pre-init guard and delegates when registered")
  void publisherPreInitGuardAndRegistration() {
    MetricTimeSeriesPublisher.clear();
    assertFalse(MetricTimeSeriesPublisher.isRegistered());

    MetricBucketRow sample =
        new MetricBucketRow("b1", "mspt", 60L, 60, 10, 10.0, 5.0, 15.0, 14.0, 1L);

    assertThrows(IllegalStateException.class, MetricTimeSeriesPublisher::require);
    assertThrows(IllegalStateException.class, MetricTimeSeriesPublisher::instance);
    assertThrows(
        IllegalStateException.class, () -> MetricTimeSeriesPublisher.publishBucket(sample));
    assertThrows(
        IllegalStateException.class,
        () -> MetricTimeSeriesPublisher.queryRange("b1", "mspt", 0L, 120L));

    assertThrows(
        IllegalStateException.class, () -> MetricTimeSeriesPublisher.UNINITIALIZED.enqueue(sample));
    assertThrows(IllegalStateException.class, MetricTimeSeriesPublisher.UNINITIALIZED::flush);
    assertThrows(
        IllegalStateException.class,
        () -> MetricTimeSeriesPublisher.UNINITIALIZED.rangeScan("b1", "mspt", 0L, 120L));
    assertThrows(IllegalStateException.class, MetricTimeSeriesPublisher.UNINITIALIZED::isActive);
    assertThrows(
        IllegalStateException.class, MetricTimeSeriesPublisher.UNINITIALIZED::pendingCount);

    List<MetricBucketRow> enqueued = new ArrayList<>();
    MetricTimeSeriesPublisher stub =
        new MetricTimeSeriesPublisher() {
          @Override
          public void enqueue(MetricBucketRow row) {
            enqueued.add(row);
          }

          @Override
          public int flush() {
            return enqueued.size();
          }

          @Override
          public List<MetricBucketRow> rangeScan(
              @Nullable String backendId,
              String metricKey,
              long fromEpochInclusive,
              long toEpochInclusive) {
            return List.copyOf(enqueued);
          }

          @Override
          public boolean isActive() {
            return true;
          }

          @Override
          public int pendingCount() {
            return enqueued.size();
          }
        };

    MetricTimeSeriesPublisher.register(stub);
    assertTrue(MetricTimeSeriesPublisher.isRegistered());
    assertSame(stub, MetricTimeSeriesPublisher.require());
    assertSame(stub, MetricTimeSeriesPublisher.instance());

    assertThrows(IllegalArgumentException.class, () -> stub.enqueueAll(null));
    stub.enqueueAll(List.of(sample));
    MetricTimeSeriesPublisher.publishBucket(sample);
    assertEquals(2, stub.pendingCount());
    assertEquals(2, stub.flush());
    assertTrue(stub.isActive());
    assertEquals(2, MetricTimeSeriesPublisher.queryRange("b1", "mspt", 0L, 120L).size());
    assertEquals(2, stub.rangeScan("mspt", 0L, 120L).size());
  }
}

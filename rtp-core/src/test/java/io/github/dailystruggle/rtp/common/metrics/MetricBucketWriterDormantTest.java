package io.github.dailystruggle.rtp.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Verifies {@code REQ-RTP-METTS-007}: Writer is dormant when
 * {@code metrics.networkTimeSeries.publish=false} or when ADR-036 network mode is disabled
 * (one structured warning at startup in the mismatch case, zero writes otherwise).
 */
@DisplayName("REQ-RTP-METTS-007 / REQ-RTP-S-004: MetricBucketWriterDormantTest")
class MetricBucketWriterDormantTest {

  @Test
  @DisplayName("REQ-RTP-METTS-007: When publish=false, writer stays completely silent and performs zero writes")
  void dormantWhenPublishIsFalse() {
    InMemoryMetricBucketStore store = new InMemoryMetricBucketStore();
    List<String> warnings = new ArrayList<>();

    NetworkMetricTimeSeriesPublisher publisher =
        new NetworkMetricTimeSeriesPublisher(
            () -> NetworkTimeSeriesConfig.DEFAULT, // publish=false
            () -> true, // even with network mode enabled
            () -> "surv-1",
            () -> 1_800_000_060L,
            () -> false,
            store,
            warnings::add);

    assertFalse(publisher.isActive());
    publisher.enqueue(
        new MetricBucketRow("surv-1", "mspt", 1_800_000_000L, 60, 60, 12.0, 8.0, 20.0, 18.0, 1L));
    assertEquals(0, publisher.pendingCount());
    assertEquals(0, publisher.flushPass(1_800_000_060L));
    assertEquals(0, store.size());
    assertEquals(0, store.bootstrapCallCount());
    assertEquals(0, warnings.size(), "publish=false must emit zero startup warnings");
  }

  @Test
  @DisplayName("REQ-RTP-METTS-007: When publish=true and ADR-036 network mode is disabled, emits exactly one startup warning and performs zero writes")
  void mismatchWhenPublishTrueAndNetworkModeDisabledWarnsOnceAndStaysDormant() {
    InMemoryMetricBucketStore store = new InMemoryMetricBucketStore();
    List<String> warnings = new ArrayList<>();

    NetworkMetricTimeSeriesPublisher publisher =
        new NetworkMetricTimeSeriesPublisher(
            () -> NetworkTimeSeriesConfig.of(true, "24h@1m", 7, 64),
            () -> false, // ADR-036 network mode disabled
            () -> "surv-1",
            () -> 1_800_000_060L,
            () -> false,
            store,
            warnings::add);

    // Exactly one structured warning emitted at startup
    assertFalse(publisher.isActive());
    assertEquals(1, warnings.size());
    assertEquals(1L, publisher.mismatchWarningCount());
    assertTrue(warnings.get(0).contains("REQ-RTP-METTS-007"));
    assertTrue(warnings.get(0).contains("S-004"));

    // Subsequent enqueue and flush calls must not spam warnings or write rows
    for (int i = 0; i < 5; i++) {
      publisher.enqueue(
          new MetricBucketRow(
              "surv-1", "mspt", 1_800_000_000L + (i * 60L), 60, 60, 12.0, 8.0, 20.0, 18.0, 1L));
      assertEquals(0, publisher.flushPass(1_800_000_060L + (i * 60L)));
    }
    assertEquals(0, publisher.pendingCount());
    assertEquals(0, store.size(), "Zero rows may be written while network mode is disabled");
    assertEquals(0, store.bootstrapCallCount(), "Schema bootstrap must not run when dormant");
    assertEquals(1, warnings.size(), "Mismatch warning must fire only once at startup");
  }

  @Test
  @DisplayName("REQ-RTP-METTS-007: NetworkTimeSeriesConfig parses metrics.networkTimeSeries.* from network.yml")
  void networkYmlSectionParsesNetworkTimeSeriesConfig() {
    io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig.parse(
            """
            network:
              enabled: true
              serverId: "surv-1"
            metrics:
              networkTimeSeries:
                publish: true
                tier: "1h@10s"
                retentionDays: 14
                perPassWriteCap: 32
            version: 1.1
            """);
    NetworkTimeSeriesConfig cfg = NetworkTimeSeriesConfig.fromYaml(yaml);
    assertTrue(cfg.publish());
    assertEquals("1h@10s", cfg.tier());
    assertEquals(14, cfg.retentionDays());
    assertEquals(32, cfg.perPassWriteCap());
  }
}

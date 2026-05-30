package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-RTP-OBS-005 (ADR-053 §2a) slow-teleport audit and REQ-RTP-OBS-006 (§2b) queue-growth
 * audit surfaced through {@link CoreMetrics}. Without a bootstrapped {@code RTP.configs} the
 * threshold readers fall back to their documented defaults (slow = 5000 ms enabled,
 * queue-growth = 0 disabled), which is exactly what these tests exercise.
 */
@DisplayName("REQ-RTP-OBS-005/006 slow-teleport + queue-growth audit counters")
class ReqRtpObs005SlowTeleportAuditTest {

    @Test
    void immediateTeleport_aboveDefaultThreshold_incrementsSlowCount() {
        CoreMetrics m = new CoreMetrics();
        assertEquals(0L, m.slowPipelineCount());
        // Default slowPipelineThresholdMs is 5000 when no config is bootstrapped.
        m.auditImmediateTeleport(6000L, "player=test region=default");
        assertEquals(1L, m.slowPipelineCount());
        m.auditImmediateTeleport(9000L, "player=test region=default");
        assertEquals(2L, m.slowPipelineCount());
    }

    @Test
    void immediateTeleport_belowThreshold_doesNotIncrement() {
        CoreMetrics m = new CoreMetrics();
        m.auditImmediateTeleport(100L, "player=test region=default");
        m.auditImmediateTeleport(4999L, "player=test region=default");
        assertEquals(0L, m.slowPipelineCount());
    }

    @Test
    void snapshot_surfacesSlowCountAndThreshold() {
        CoreMetrics m = new CoreMetrics();
        m.auditImmediateTeleport(7000L, "player=test region=default");
        MetricsSnapshot s = m.snapshot();
        RTPMetricsExtension ext = s.extension(RTPMetricsExtension.class);
        assertNotNull(ext);
        assertEquals(1L, ext.slowPipelineCount);
        assertEquals(5000L, ext.slowPipelineThresholdMs);
    }

    @Test
    void queueGrowthAudit_disabledByDefault_neverIncrements() {
        CoreMetrics m = new CoreMetrics();
        // Default queueGrowthWarnThreshold is 0 (disabled) without config; snapshot evaluates
        // the edge audit on the (empty) queue depth and must not increment or throw.
        assertDoesNotThrow(m::snapshot);
        MetricsSnapshot s = m.snapshot();
        RTPMetricsExtension ext = s.extension(RTPMetricsExtension.class);
        assertNotNull(ext);
        assertEquals(0L, ext.queueGrowthWarnCount);
        assertEquals(0, ext.queueGrowthWarnThreshold);
        assertEquals(0L, m.queueGrowthWarnCount());
    }

    @Test
    void audit_neverThrows() {
        CoreMetrics m = new CoreMetrics();
        assertDoesNotThrow(() -> m.auditImmediateTeleport(123456L, "player=null region=null"));
    }
}

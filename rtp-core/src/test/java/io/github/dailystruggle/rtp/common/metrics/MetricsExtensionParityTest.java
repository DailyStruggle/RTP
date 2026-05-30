package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.metrics.api.Metrics;
import io.github.dailystruggle.metrics.api.MetricsExtension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase B parity test for the {@link MetricsExtension} slot mechanism on
 * {@link MetricsSnapshot}. Verifies:
 * <ol>
 *   <li>{@link Metrics#NOOP} attaches a zero {@link RTPMetricsExtension}.</li>
 *   <li>{@link MetricsSnapshot#withExtension(MetricsExtension)} returns a new
 *       snapshot carrying the payload, leaves the receiver untouched, and
 *       preserves all 15 legacy host-runtime fields.</li>
 *   <li>{@link MetricsSnapshot#extension(Class)} returns {@code null} for
 *       absent payloads and rejects null type without NPE.</li>
 * </ol>
 *
 * <p>See {@code metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md}.
 */
class MetricsExtensionParityTest {

    @Test
    void noopAttachesZeroRtpExtension() {
        MetricsSnapshot snap = new CoreMetrics().snapshot();
        RTPMetricsExtension ext = snap.extension(RTPMetricsExtension.class);
        assertNotNull(ext, "NOOP must attach RTPMetricsExtension for forward-compat readers");
        assertEquals(0, ext.queueDepth);
        assertEquals(0, ext.pendingTeleports);
        assertEquals(0, ext.memoryTrackerEntries);
        assertEquals(0, ext.chunkLoadBacklog);
        assertEquals(Double.NaN, ext.avgPipelineMs);
        assertEquals(-1, ext.databaseLatencyMs);
    }

    @Test
    void withExtensionIsImmutableAndPreservesHostFields() {
        MetricsSnapshot base = new CoreMetrics().snapshot();
        RTPMetricsExtension custom = new RTPMetricsExtension(7, 3, 11, 2, 4.5, 12);
        MetricsSnapshot derived = base.withExtension(custom);

        // Receiver untouched
        RTPMetricsExtension baseExt = base.extension(RTPMetricsExtension.class);
        assertNotNull(baseExt);
        assertEquals(0, baseExt.queueDepth);

        // Derived carries the new payload
        RTPMetricsExtension dExt = derived.extension(RTPMetricsExtension.class);
        assertNotNull(dExt);
        assertEquals(7, dExt.queueDepth);
        assertEquals(12, dExt.databaseLatencyMs);

        // Host-runtime fields propagated verbatim
        assertEquals(base.tps1m, derived.tps1m);
        assertEquals(base.heapMaxBytes, derived.heapMaxBytes);
        assertEquals(base.playerCount, derived.playerCount);
        assertEquals(base.takenAtEpochMs, derived.takenAtEpochMs);
    }

    @Test
    void extensionAccessorTolerantOfNullAndAbsent() {
        MetricsSnapshot snap = new CoreMetrics().snapshot();
        assertNull(snap.extension(null));
        assertNull(snap.extension(NeverAttached.class));
        assertThrows(NullPointerException.class, () -> snap.withExtension(null));
    }

    private static final class NeverAttached implements MetricsExtension<NeverAttached> {
    }
}

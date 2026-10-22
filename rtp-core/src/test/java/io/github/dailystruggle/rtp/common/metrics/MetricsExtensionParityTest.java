package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.metrics.api.MetricsExtension;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity test for the {@link MetricsExtension} slot mechanism on {@link MetricsSnapshot}.
 */
class MetricsExtensionParityTest {

    /**
     * {@link MemoryTracker} is a process-wide static whose entry count flows into
     * {@code RTPMetricsExtension.memoryTrackerEntries}. Earlier tests in the shared
     * JVM can leave tracked entries behind, so reset to a known-empty state before
     * each parity assertion that expects an absolute zero count.
     */
    @BeforeEach
    void clearMemoryTracker() {
        MemoryTracker.reset();
    }

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
    void builderCarriesEveryFieldAndCopiesTables() {
        java.util.Map<String, io.github.dailystruggle.metrics.api.RegionQueueRow> queues = new java.util.HashMap<>();
        queues.put("r", null);
        RTPMetricsExtension ext = RTPMetricsExtension.builder()
                .queueDepth(1).pendingTeleports(2).memoryTrackerEntries(3).chunkLoadBacklog(4)
                .avgPipelineMs(5.5).databaseLatencyMs(6)
                .slowPipeline(7L, 8L).queueGrowthWarn(9L, 10)
                .regionQueueStatus(queues).probeGovernors(null)
                .build();
        queues.clear();

        assertEquals("RTPMetricsExtension{queueDepth=1, pendingTeleports=2, memoryTrackerEntries=3,"
                + " chunkLoadBacklog=4, avgPipelineMs=5.5, databaseLatencyMs=6, slowPipelineCount=7,"
                + " slowPipelineThresholdMs=8, queueGrowthWarnCount=9, queueGrowthWarnThreshold=10,"
                + " regionQueueStatus={r=null}, probeGovernors={}, liveLoadGates={}, immediateGovernors={},"
                + " actionCbit=Snapshot[missingObjectiveReads={}, gateOutcomes=[], unresolvedPlaceholders=0,"
                + " unknownActions=0, orphanedSessions=0, gateOutcomeSamples=200, lastUnresolvedToken=,"
                + " lastUnknownAction=, lastOrphanedAction=]}",
                ext.toString());
        assertThrows(UnsupportedOperationException.class, () -> ext.regionQueueStatus.put("x", null));
        assertTrue(ext.probeGovernors.isEmpty());
    }

    @Test
    void coreSnapshotIncludesActionCbitCounters() {
        ActionCbitCounters.reset();
        try {
            ActionCbitCounters.recordMissingObjective("metrics_objective");

            RTPMetricsExtension extension = new CoreMetrics().snapshot().extension(RTPMetricsExtension.class);

            assertEquals(1L, extension.actionCbit.missingObjectiveReads().get("metrics_objective"));
        } finally {
            ActionCbitCounters.reset();
        }
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

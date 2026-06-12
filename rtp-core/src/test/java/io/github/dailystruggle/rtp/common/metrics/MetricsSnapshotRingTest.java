package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MetricsSnapshotRing}, the rolling MSPT + heap
 * time-series that feeds the {@code METRIC_SPARKLINE} chart kind.
 *
 * <p>Focus: the Folia MSPT aggregation contract. The ring records the host
 * scalar {@code snapshot.mspt} (which the platform binding has already
 * aggregated per the configurable {@code foliaAggregationMspt} mode), so the
 * sparkline stays consistent with every other MSPT surface. The per-region
 * {@code max} derivation is only a fallback for an unsampled host scalar.
 */
class MetricsSnapshotRingTest {

    @Test
    void recordsHostScalarMspt_onSingleRegionRuntime() {
        MetricsSnapshotRing ring = new MetricsSnapshotRing();
        ring.recordFromSnapshot(snapshot(25.0, Collections.emptyList()));

        double[] mspt = ring.msptSnapshot();
        assertEquals(1, mspt.length);
        assertEquals(25.0, mspt[0], 0.0001);
    }

    @Test
    void honoursConfiguredAggregate_whenScalarSampled_ignoringPerRegionMax() {
        // Binding configured for 'mean' -> snapshot.mspt = 30, even though the
        // worst region is 90. The ring must record the configured scalar (30),
        // NOT re-derive the per-region max (90). This is the regression the
        // fix addresses: the sparkline previously disagreed with /rtp info.
        MetricsSnapshotRing ring = new MetricsSnapshotRing();
        List<FoliaRegionSample> regions = List.of(
                new FoliaRegionSample("region-0", 20.0, 10.0, 0, 0),
                new FoliaRegionSample("region-1", 20.0, 90.0, 0, 0));
        ring.recordFromSnapshot(snapshot(30.0, regions));

        double[] mspt = ring.msptSnapshot();
        assertEquals(1, mspt.length);
        assertEquals(30.0, mspt[0], 0.0001);
    }

    @Test
    void fallsBackToPerRegionMax_whenHostScalarUnsampled() {
        MetricsSnapshotRing ring = new MetricsSnapshotRing();
        List<FoliaRegionSample> regions = List.of(
                new FoliaRegionSample("region-0", 20.0, 12.0, 0, 0),
                new FoliaRegionSample("region-1", 20.0, 47.0, 0, 0),
                new FoliaRegionSample("region-2", 20.0, MetricsSnapshot.UNSAMPLED, 0, 0));
        ring.recordFromSnapshot(snapshot(MetricsSnapshot.UNSAMPLED, regions));

        double[] mspt = ring.msptSnapshot();
        assertEquals(1, mspt.length);
        assertEquals(47.0, mspt[0], 0.0001);
    }

    @Test
    void preservesUnsampledSentinel_whenNoData() {
        MetricsSnapshotRing ring = new MetricsSnapshotRing();
        ring.recordFromSnapshot(snapshot(MetricsSnapshot.UNSAMPLED, Collections.emptyList()));

        double[] mspt = ring.msptSnapshot();
        assertEquals(1, mspt.length);
        assertTrue(Double.isNaN(mspt[0]));
    }

    @Test
    void heapAndMsptSeriesAreIndexAlignedAndChronological() {
        MetricsSnapshotRing ring = new MetricsSnapshotRing();
        ring.recordFromSnapshot(snapshot(10.0, 1L * 1024 * 1024, Collections.emptyList()));
        ring.recordFromSnapshot(snapshot(20.0, 2L * 1024 * 1024, Collections.emptyList()));

        double[] mspt = ring.msptSnapshot();
        long[] heap = ring.heapSnapshot();
        assertEquals(2, mspt.length);
        assertEquals(2, heap.length);
        // Oldest first, newest last.
        assertEquals(10.0, mspt[0], 0.0001);
        assertEquals(20.0, mspt[1], 0.0001);
        assertEquals(1L * 1024 * 1024, heap[0]);
        assertEquals(2L * 1024 * 1024, heap[1]);
    }

    @Test
    void ringWrapsAtCapacity_keepingMostRecentSamples() {
        MetricsSnapshotRing ring = new MetricsSnapshotRing();
        int total = MetricsSnapshotRing.CAPACITY + 5;
        for (int i = 0; i < total; i++) {
            ring.recordFromSnapshot(snapshot(i, Collections.emptyList()));
        }
        assertEquals(MetricsSnapshotRing.CAPACITY, ring.sampleCount());
        assertEquals(total, ring.totalRecorded());

        double[] mspt = ring.msptSnapshot();
        assertEquals(MetricsSnapshotRing.CAPACITY, mspt.length);
        // Newest sample is the last write.
        assertEquals(total - 1, mspt[mspt.length - 1], 0.0001);
        // Oldest retained sample is total - CAPACITY.
        assertEquals(total - MetricsSnapshotRing.CAPACITY, mspt[0], 0.0001);
    }

    private static MetricsSnapshot snapshot(double mspt, List<FoliaRegionSample> regions) {
        return snapshot(mspt, 1024L, regions);
    }

    private static MetricsSnapshot snapshot(double mspt, long heapUsedBytes,
                                            List<FoliaRegionSample> regions) {
        return new MetricsSnapshot(
                20.0, 20.0, 20.0, mspt,
                1, 20,
                heapUsedBytes, 2048L,
                System.currentTimeMillis(),
                regions);
    }
}

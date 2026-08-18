package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FoliaRegionSample} and {@link MetricsSnapshot#foliaRegions}.
 *
 * <p>Verifies immutability, empty defaults, and tick budget calculation (REQ-RTP-OBS-001).
 */
class FoliaRegionSampleTest {

    @Test
    void ctor_emptyList_leavesFoliaRegionsEmpty() {
        MetricsSnapshot s = new MetricsSnapshot(
                20.0, 20.0, 20.0, 25.0,
                1, 20,
                1024L, 2048L,
                System.currentTimeMillis(),
                Collections.emptyList());
        assertNotNull(s.foliaRegions, "foliaRegions must never be null");
        assertTrue(s.foliaRegions.isEmpty(), "empty-list ctor must leave foliaRegions empty");
    }

    @Test
    void fullCtor_acceptsAndExposesList() {
        FoliaRegionSample a = new FoliaRegionSample("region-0", 19.8, 26.0, 3, 1);
        FoliaRegionSample b = new FoliaRegionSample("region-1", 20.0, 22.5, 0, 0);
        MetricsSnapshot s = new MetricsSnapshot(
                19.9, 19.9, 19.9, 24.0,
                3, 20,
                1024L, 2048L,
                System.currentTimeMillis(),
                Arrays.asList(a, b));
        assertEquals(2, s.foliaRegions.size());
        assertSame(a, s.foliaRegions.get(0));
        assertSame(b, s.foliaRegions.get(1));
    }

    @Test
    void fullCtor_foliaRegionsList_isImmutable() {
        MetricsSnapshot s = new MetricsSnapshot(
                20.0, 20.0, 20.0, 25.0,
                1, 20,
                1024L, 2048L,
                System.currentTimeMillis(),
                Collections.singletonList(new FoliaRegionSample("r", 20.0, 25.0, 0, 0)));
        assertThrows(UnsupportedOperationException.class,
                () -> s.foliaRegions.add(new FoliaRegionSample("x", 0.0, 0.0, 0, 0)),
                "snapshot must hand back an unmodifiable view");
    }

    @Test
    void fullCtor_nullList_normalisesToEmpty() {
        MetricsSnapshot s = new MetricsSnapshot(
                20.0, 20.0, 20.0, 25.0,
                1, 20,
                1024L, 2048L,
                System.currentTimeMillis(),
                null);
        assertNotNull(s.foliaRegions);
        assertTrue(s.foliaRegions.isEmpty());
    }

    @Test
    void binding_foliaRegionsDefault_isEmpty() {
        // Non-Folia binding subclasses inherit this default; covers
        // PaperMetricsBinding / BukkitTpsSampler / future FabricMetricsBinding.
        MetricsBinding binding = new MetricsBinding() {};
        List<FoliaRegionSample> regions = binding.foliaRegions();
        assertNotNull(regions);
        assertTrue(regions.isEmpty());
        assertTrue(MetricsBinding.NOOP.foliaRegions().isEmpty(),
                "NOOP binding also returns empty");
    }

    @Test
    void coreMetrics_snapshot_propagatesBindingRegions() {
        CoreMetrics m = new CoreMetrics();
        FoliaRegionSample sample = new FoliaRegionSample("region-0", 19.5, 27.0, 2, 1);
        m.setBinding(new MetricsBinding() {
            @Override public List<FoliaRegionSample> foliaRegions() {
                return Collections.singletonList(sample);
            }
        });
        MetricsSnapshot s = m.snapshot();
        assertEquals(1, s.foliaRegions.size());
        assertSame(sample, s.foliaRegions.get(0));
    }

    @Test
    void regionSample_tickBudgetUtilisation_isMsptOver50() {
        FoliaRegionSample s = new FoliaRegionSample("r", 20.0, 25.0, 0, 0);
        assertEquals(0.5, s.tickBudgetUtilisation(), 0.0001);
    }

    @Test
    void regionSample_tickBudgetUtilisation_isNaNWhenUnsampled() {
        FoliaRegionSample s = new FoliaRegionSample("r", 20.0, MetricsSnapshot.UNSAMPLED, 0, 0);
        assertTrue(Double.isNaN(s.tickBudgetUtilisation()));
    }

    @Test
    void regionSample_nullId_normalisesToSentinel() {
        FoliaRegionSample s = new FoliaRegionSample(null, 20.0, 25.0, 0, 0);
        assertEquals("region-?", s.regionId);
    }
}

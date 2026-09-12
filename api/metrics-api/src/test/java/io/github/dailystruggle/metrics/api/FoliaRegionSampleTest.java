package io.github.dailystruggle.metrics.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FoliaRegionSample (METRICS_PLAN.md)")
class FoliaRegionSampleTest {

    @Test
    @DisplayName("Fields stored verbatim")
    void fields() {
        FoliaRegionSample s = new FoliaRegionSample("region-3", 19.9, 12.5, 7, 2);
        assertEquals("region-3", s.regionId);
        assertEquals(19.9, s.tps1m);
        assertEquals(12.5, s.mspt);
        assertEquals(7, s.playerCount);
        assertEquals(2, s.queueDepth);
    }

    @Test
    @DisplayName("Null regionId falls back to the 'region-?' sentinel")
    void nullRegionIdFallback() {
        assertEquals("region-?", new FoliaRegionSample(null, 1, 1, 0, 0).regionId);
    }

    @Test
    @DisplayName("tickBudgetUtilisation is mspt/50 when sampled, NaN otherwise")
    void tickBudgetUtilisation() {
        assertEquals(0.25, new FoliaRegionSample("r", 20, 12.5, 0, 0).tickBudgetUtilisation());
        assertTrue(Double.isNaN(
                new FoliaRegionSample("r", 20, MetricsSnapshot.UNSAMPLED, 0, 0).tickBudgetUtilisation()));
    }

    @Test
    @DisplayName("toString carries the region id and fields")
    void toStringContent() {
        String str = new FoliaRegionSample("region-1", 19.5, 25.0, 4, 1).toString();
        assertTrue(str.contains("regionId='region-1'"));
        assertTrue(str.contains("FoliaRegionSample{"));
    }
}

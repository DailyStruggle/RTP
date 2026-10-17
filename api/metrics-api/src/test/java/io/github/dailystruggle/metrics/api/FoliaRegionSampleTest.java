package io.github.dailystruggle.metrics.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FoliaRegionSample")
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
        assertEquals(0L, s.sampleCount);
        assertEquals(0L, s.ageMillis);

        FoliaRegionSample detailed = new FoliaRegionSample("region-4", 19.8, 14.0, 5, 1, 150L, 7500L);
        assertEquals(150L, detailed.sampleCount);
        assertEquals(7500L, detailed.ageMillis);
    }

    @Test
    @DisplayName("Null regionId falls back to the 'region-?' sentinel")
    void nullRegionIdFallback() {
        assertEquals("region-?", new FoliaRegionSample(null, 1, 1, 0, 0).regionId);
    }

    @Test
    @DisplayName("tickBudgetUtilisation derives mspt / 50.0")
    void utilisation() {
        assertEquals(0.5, new FoliaRegionSample("r", 20.0, 25.0, 0, 0).tickBudgetUtilisation(), 1e-6);
        assertTrue(Double.isNaN(new FoliaRegionSample("r", 20.0, Double.NaN, 0, 0).tickBudgetUtilisation()));
    }

    @Test
    @DisplayName("toString includes fields")
    void toStringIncludes() {
        String s = new FoliaRegionSample("r-1", 19.5, 12.0, 4, 1, 10L, 500L).toString();
        assertTrue(s.contains("r-1"));
        assertTrue(s.contains("19.5"));
        assertTrue(s.contains("12.0"));
        assertTrue(s.contains("sampleCount=10"));
        assertTrue(s.contains("ageMillis=500"));
    }
}

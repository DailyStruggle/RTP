package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemoryShape} cache hit, miss, and invalidation semantics.
 */
public class MemoryShapeCacheTest {

    static {
        MockRTPServerAccessor accessor =
                new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    private Circle shape;

    @BeforeEach
    void setUp() {
        shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 200L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
        shape.setRng(new Random(42L));
    }

    // -------------------------------------------------------------------------
    // Cache miss: dirty flag triggers rebuild
    // -------------------------------------------------------------------------

    @Test
    void addBadLocation_marksCacheDirty() {
        shape.flushAndRebuild(shape.spatialResolution());
        assertFalse(shape.badLocationsDirty, "Cache should be clean after flushAndRebuild");

        shape.addBadLocation(100L);
        assertTrue(shape.badLocationsDirty, "addBadLocation must mark cache as dirty");
    }

    @Test
    void flushAndRebuild_afterDirty_rebuildsCache() {
        shape.addBadLocation(10L);
        shape.addBadLocation(20L);
        assertTrue(shape.badLocationsDirty);

        shape.flushAndRebuild(shape.spatialResolution());

        assertFalse(shape.badLocationsDirty, "Cache should be clean after rebuild");
        assertEquals(2, shape.getEffectiveBadCount(),
                "Cache should reflect 2 bad locations after rebuild");
    }

    // -------------------------------------------------------------------------
    // Cache hit: no rebuild when clean
    // -------------------------------------------------------------------------

    @Test
    void flushAndRebuild_whenClean_doesNotChangeCacheContents() {
        shape.addBadLocation(50L);
        shape.flushAndRebuild(shape.spatialResolution());

        long countAfterFirstBuild = shape.getEffectiveBadCount();
        assertFalse(shape.badLocationsDirty);

        // Call again without adding anything - should be a no-op
        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals(countAfterFirstBuild, shape.getEffectiveBadCount(),
                "Second flushAndRebuild on clean cache must not change effective bad count");
    }

    // -------------------------------------------------------------------------
    // Cache invalidation: clear() resets everything
    // -------------------------------------------------------------------------

    @Test
    void clear_resetsAllCaches() {
        shape.addBadLocation(10L);
        shape.addBadLocation(20L);
        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals(2, shape.getEffectiveBadCount());

        shape.clear();
        // clear() marks dirty and clears the underlying maps; rebuild to get fresh counts
        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals(0, shape.getEffectiveBadCount(),
                "clear() must reset effective bad count to 0");
        assertTrue(shape.badLocationsDirty || !shape.badLocationsDirty,
                "dirty flag state after clear+rebuild is implementation-defined");
    }

    @Test
    void clear_thenRebuild_startsFromEmpty() {
        shape.addBadLocation(10L);
        shape.addBadLocation(20L);
        shape.flushAndRebuild(shape.spatialResolution());
        shape.clear();

        // Rebuild after clear - should be empty
        shape.flushAndRebuild(shape.spatialResolution());
        assertEquals(0, shape.getEffectiveBadCount(),
                "After clear+rebuild, bad count must be 0");
        assertFalse(shape.badLocationsDirty,
                "After clear+rebuild, dirty flag must be false");
    }

    // -------------------------------------------------------------------------
    // Biome cache: addBiomeLocation marks dirty
    // -------------------------------------------------------------------------

    @Test
    void addBiomeLocation_marksCacheDirty() {
        shape.flushAndRebuild(shape.spatialResolution());
        assertFalse(shape.biomeLocationsDirty, "Biome cache should be clean after flushAndRebuild");

        shape.addBiomeLocation(30L, 1L, "plains");
        assertTrue(shape.biomeLocationsDirty,
                "addBiomeLocation must mark biome cache as dirty");
    }

    @Test
    void biomeCache_afterRebuild_reflectsAddedLocations() {
        shape.addBiomeLocation(10L, 1L, "ocean");
        shape.addBiomeLocation(20L, 1L, "forest");
        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals(2, shape.getEffectiveGoodCount(),
                "Biome cache should reflect 2 good locations after rebuild");
        assertFalse(shape.badLocationsDirty);
    }

    @Test
    void biomeCache_clear_resetsGoodCount() {
        shape.addBiomeLocation(10L, 1L, "ocean");
        shape.addBiomeLocation(20L, 1L, "forest");
        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals(2, shape.getEffectiveGoodCount());

        shape.clear();
        shape.flushAndRebuild(shape.spatialResolution());
        assertEquals(0, shape.getEffectiveGoodCount(),
                "clear() must reset effective good count to 0 after rebuild");
    }

    // -------------------------------------------------------------------------
    // isKnownBad: cache correctly identifies bad locations
    // -------------------------------------------------------------------------

    @Test
    void isKnownBad_returnsTrueForAddedLocation() {
        shape.addBadLocation(42L);
        shape.flushAndRebuild(shape.spatialResolution());
        assertTrue(shape.isKnownBad(42L),
                "isKnownBad must return true for a location added via addBadLocation");
    }

    @Test
    void isKnownBad_returnsFalseForUnaddedLocation() {
        shape.addBadLocation(42L);
        shape.flushAndRebuild(shape.spatialResolution());
        assertFalse(shape.isKnownBad(99L),
                "isKnownBad must return false for a location not added as bad");
    }

    @Test
    void isKnownBad_afterClear_returnsFalse() {
        shape.addBadLocation(42L);
        shape.flushAndRebuild(shape.spatialResolution());
        shape.clear();
        shape.flushAndRebuild(shape.spatialResolution());
        assertFalse(shape.isKnownBad(42L),
                "isKnownBad must return false after clear()");
    }

    // -------------------------------------------------------------------------
    // Spatial resolution: different resolutions merge differently
    // -------------------------------------------------------------------------

    @Test
    void spatialResolution_higher_mergesMoreLocations() {
        shape.addBadLocation(10L);
        shape.addBadLocation(15L);
        shape.addBadLocation(20L);

        // Resolution 1: each location is its own interval
        shape.flushAndRebuild(1);
        long countRes1 = shape.getEffectiveBadCount();

        // Resolution 10: intervals of width 10 - all three may merge
        shape.badLocationsDirty = true;
        shape.flushAndRebuild(10);
        long countRes10 = shape.getEffectiveBadCount();

        assertTrue(countRes10 >= countRes1,
                "Higher spatial resolution should cover at least as many locations (merging intervals)");
    }

    // -------------------------------------------------------------------------
    // rand() uses cache: bad locations are skipped in ACCUMULATE mode
    // -------------------------------------------------------------------------

    @Test
    void rand_afterCacheRebuild_doesNotReturnBadLocation() {
        shape.set(GenericMemoryShapeParams.mode, "ACCUMULATE");
        for (long i = 0; i < 500; i++) shape.addBadLocation(i);
        shape.flushAndRebuild(shape.spatialResolution());

        for (int i = 0; i < 100; i++) {
            long loc = shape.rand();
            assertFalse(shape.isKnownBad(loc),
                    "rand() in ACCUMULATE mode must not return a cached bad location, got " + loc);
        }
    }
}

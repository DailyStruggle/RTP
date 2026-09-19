package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryShapeTest {
    static {
        MockRTPServerAccessor accessor = new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    private static class TestShape extends MemoryShape<GenericMemoryShapeParams> {
        public TestShape() {
            super(GenericMemoryShapeParams.class, "TEST", createDefaultData());
        }

        private static EnumMap<GenericMemoryShapeParams, Object> createDefaultData() {
            EnumMap<GenericMemoryShapeParams, Object> data = new EnumMap<>(GenericMemoryShapeParams.class);
            data.put(GenericMemoryShapeParams.mode, "ACCUMULATE");
            data.put(GenericMemoryShapeParams.radius, 100L);
            data.put(GenericMemoryShapeParams.centerRadius, 0L);
            data.put(GenericMemoryShapeParams.centerX, 0L);
            data.put(GenericMemoryShapeParams.centerZ, 0L);
            data.put(GenericMemoryShapeParams.weight, 1.0);
            data.put(GenericMemoryShapeParams.uniquePlacements, false);
            data.put(GenericMemoryShapeParams.expand, false);
            return data;
        }

        @Override
        public long getRange() { return 100; }

        @Override
        public long xzToLocation(long x, long z) { return 0; }

        @Override
        public long xzToLocation(MutableRTPCoords coords) { return 0; }

        @Override
        public int[] locationToXZ(long location) { return new int[]{0, 0}; }

        @Override
        public void locationToXZ(long location, MutableRTPCoords output) {}

        @Override
        public Map<String, CommandParameter> getParameters() { return null; }

        @Override
        public Collection<String> keys() { return java.util.Collections.emptyList(); }

        @Override
        public int[] select() { return new int[]{0, 0}; }

        @Override
        public long rand() { return 0; }

        @Override
        public boolean contains(int x, int z) { return true; }

        // Expose protected method for testing
        @Override
        public void flushAndRebuild(long spatialResolution) {
            super.flushAndRebuild(spatialResolution);
        }

        public long[] getBadKeysCache() {
            return badKeysCache;
        }

        public long[] getBadPrefixSumsCache() {
            return badPrefixSumsCache;
        }

        public long[] getBiomeMappedKeysCache() {
            return biomeMappedKeysCache;
        }

        public long[] getBiomeMappedPrefixSumsCache() {
            return biomeMappedPrefixSumsCache;
        }

        public long getBadSum() {
            long[] sums = badPrefixSumsCache;
            return (sums.length > 0) ? sums[sums.length - 1] : 0L;
        }
    }

    @Test
    public void testSingleInsertion() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        assertTrue(shape.badLocationsDirty);

        shape.flushAndRebuild(shape.spatialResolution);
        assertEquals(1, shape.getEffectiveBadCount());

        assertEquals(1, shape.getBadSum());
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(1, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testNonOverlappingInsertions() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        shape.addBadLocation(30);

        shape.flushAndRebuild(shape.spatialResolution);

        assertEquals(2, shape.getBadSum());
        assertEquals(2, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(30, shape.getBadKeysCache()[1]);
        assertEquals(1, shape.getBadPrefixSumsCache()[0]);
        assertEquals(2, shape.getBadPrefixSumsCache()[1]);
    }

    @Test
    public void testContiguousMerge() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        shape.addBadLocation(14);
        shape.addBadLocation(12);

        shape.flushAndRebuild(shape.spatialResolution);

        assertEquals(5, shape.getBadSum());
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(5, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testOverlapReconciliation() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        shape.addBadLocation(12);

        shape.flushAndRebuild(shape.spatialResolution);

        // [10, 15) and [12, 17) -> [10, 17) length 7
        assertEquals(3, shape.getBadSum());
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(3, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testCompleteSubsumption() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        shape.addBadLocation(15);

        shape.flushAndRebuild(shape.spatialResolution);

        assertEquals(2, shape.getBadSum());
        assertEquals(2, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(1, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testClear() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        shape.flushAndRebuild(shape.spatialResolution);

        shape.clear();

        assertEquals(0, shape.getBadSum());
        assertEquals(0, shape.getBadKeysCache().length);
        assertEquals(0, shape.getBadPrefixSumsCache().length);
        assertTrue(shape.badLocationsDirty);
    }

    @Test
    public void testBiomeMapping() {
        TestShape shape = new TestShape();
        shape.addBiomeLocation(10L, 1L, "ocean");
        shape.addBiomeLocation(15L, 1L, "ocean"); // Contiguous [10, 11) and [15, 16) if resolution=1

        // Wait, addBiomeLocation(location, biome) uses width=1 implicitly?
        // Let's check MemoryShape.addBiomeLocation

        shape.addBiomeLocation(20L, 1L, "forest");
        shape.addBiomeLocation(21L, 1L, "forest");

        shape.flushAndRebuild(shape.spatialResolution);

        // Biome mapped should be union of all.
        // Assuming default resolution 1.
        // ocean: [10, 11), [15, 16)
        // forest: [20, 21), [21, 22) -> [20, 22)
        // union: [10, 11), [15, 16), [20, 22)

        long[] mappedKeys = shape.getBiomeMappedKeysCache();
        long[] mappedSums = shape.getBiomeMappedPrefixSumsCache();

        assertEquals(3, mappedKeys.length);
        assertEquals(10, mappedKeys[0]);
        assertEquals(15, mappedKeys[1]);
        assertEquals(20, mappedKeys[2]);

        assertEquals(1, mappedSums[0]);
        assertEquals(2, mappedSums[1]);
        assertEquals(4, mappedSums[2]);

        assertEquals(4, shape.getEffectiveGoodCount());
    }

    @Test
    public void testOverlappingBiomes() {
        TestShape shape = new TestShape();
        shape.addBiomeLocation(10L, 1L, "ocean"); // ocean: [10, 11)
        shape.addBiomeLocation(10L, 1L, "forest"); // forest: [10, 11)

        shape.flushAndRebuild(shape.spatialResolution);

        long[] mappedKeys = shape.getBiomeMappedKeysCache();
        assertEquals(1, mappedKeys.length);
        assertEquals(10, mappedKeys[0]);
        assertEquals(1, shape.getBiomeMappedPrefixSumsCache()[0]);
        assertEquals(1, shape.getEffectiveGoodCount());
    }

    @Test
    public void testGapBridging() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        shape.addBadLocation(16);
        shape.addBadLocation(13);

        shape.flushAndRebuild(1);
        assertEquals(3, shape.getBadKeysCache().length);
        assertEquals(3, shape.getBadSum());
        assertEquals(3, shape.getEffectiveBadCount());

        shape.badLocationsDirty = true; // force rebuild
        shape.flushAndRebuild(3);
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(7, shape.getBadSum());
        assertEquals(7, shape.getEffectiveBadCount());
    }

    @Test
    public void testLearnedStateSummary() {
        TestShape shape = new TestShape();
        // Three non-overlapping single-cell bad locations, all tagged safety.
        shape.addBadLocation(10L, io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.safety);
        shape.addBadLocation(20L, io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.safety);
        shape.addBadLocation(30L, io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.safety);
        shape.flushAndRebuild(1);

        MemoryShape.LearnedStateSummary summary = shape.learnedStateSummary();
        assertEquals(100L, summary.range());
        assertEquals(3L, summary.badCount());
        assertEquals(0L, summary.goodCount());
        assertEquals(3.0, summary.coveragePercent(), 1e-9);
        assertEquals(3.0, summary.badPercent(), 1e-9);
        assertEquals("safety", summary.topCause());
        assertEquals(100.0, summary.topCausePercent(), 1e-9);
    }

    @Test
    public void testLearnedStateSummary_emptyShape() {
        TestShape shape = new TestShape();
        shape.flushAndRebuild(1);

        MemoryShape.LearnedStateSummary summary = shape.learnedStateSummary();
        assertEquals(0L, summary.badCount());
        assertEquals("none", summary.topCause());
        assertTrue(Double.isNaN(summary.topCausePercent()));
    }
}

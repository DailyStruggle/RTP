package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryShapeTest {

    private static class TestShape extends MemoryShape<GenericMemoryShapeParams> {
        public TestShape() {
            super(GenericMemoryShapeParams.class, "TEST", new EnumMap<>(GenericMemoryShapeParams.class));
        }

        @Override
        public double getRange() { return 100; }

        @Override
        public double xzToLocation(long x, long z) { return 0; }

        @Override
        public double xzToLocation(MutableRTPCoords coords) { return 0; }

        @Override
        public int[] locationToXZ(long location) { return new int[]{0, 0}; }

        @Override
        public void locationToXZ(long location, MutableRTPCoords output) {}

        @Override
        public Map<String, CommandParameter> getParameters() { return null; }

        @Override
        public Collection<String> keys() { return null; }

        @Override
        public int[] select() { return new int[]{0, 0}; }

        @Override
        public long rand() { return 0; }

        // Expose protected method for testing
        @Override
        public void flushAndRebuild() {
            super.flushAndRebuild();
        }

        public long[] getBadKeysCache() {
            return badKeysCache;
        }

        public long[] getBadPrefixSumsCache() {
            return badPrefixSumsCache;
        }

        public long getBadSum() {
            long[] sums = badPrefixSumsCache;
            return (sums.length > 0) ? sums[sums.length - 1] : 0L;
        }
    }

    @Test
    public void testSingleInsertion() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10, 5);
        assertTrue(shape.badLocationsDirty);

        shape.flushAndRebuild();

        assertEquals(5, shape.getBadSum());
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(5, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testNonOverlappingInsertions() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10, 5);
        shape.addBadLocation(30, 5);

        shape.flushAndRebuild();

        assertEquals(10, shape.getBadSum());
        assertEquals(2, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(30, shape.getBadKeysCache()[1]);
        assertEquals(5, shape.getBadPrefixSumsCache()[0]);
        assertEquals(10, shape.getBadPrefixSumsCache()[1]);
    }

    @Test
    public void testContiguousMerge() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10, 5);
        shape.addBadLocation(15, 5);

        shape.flushAndRebuild();

        assertEquals(10, shape.getBadSum());
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(10, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testOverlapReconciliation() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10, 5);
        shape.addBadLocation(12, 5);

        shape.flushAndRebuild();

        // [10, 15) and [12, 17) -> [10, 17) length 7
        assertEquals(7, shape.getBadSum());
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(7, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testCompleteSubsumption() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10, 20);
        shape.addBadLocation(15, 2);

        shape.flushAndRebuild();

        // [10, 30) and [15, 17) -> [10, 30) length 20
        assertEquals(20, shape.getBadSum());
        assertEquals(1, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(20, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testClear() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10, 5);
        shape.flushAndRebuild();

        shape.clear();

        assertEquals(0, shape.getBadSum());
        assertEquals(0, shape.getBadKeysCache().length);
        assertEquals(0, shape.getBadPrefixSumsCache().length);
        assertTrue(shape.badLocationsDirty);
    }
}

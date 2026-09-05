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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

        // The union is stored blocked; materialize the flat columns the assertions read.
        public long[] getBiomeMappedKeysCache() {
            BiomeUnionTable union = biomeUnion;
            long[] out = new long[union.runCount()];
            for (int i = 0; i < out.length; i++) out[i] = union.keyAt(i);
            return out;
        }

        public long[] getBiomeMappedPrefixSumsCache() {
            BiomeUnionTable union = biomeUnion;
            long[] out = new long[union.runCount()];
            for (int i = 0; i < out.length; i++) out[i] = union.sumAt(i);
            return out;
        }

        public BiomeUnionTable union() {
            return biomeUnion;
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

        shape.flushAndRebuild(shape.spatialResolution());
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

        shape.flushAndRebuild(shape.spatialResolution());

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

        shape.flushAndRebuild(shape.spatialResolution());

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

        shape.flushAndRebuild(shape.spatialResolution());

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

        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals(2, shape.getBadSum());
        assertEquals(2, shape.getBadKeysCache().length);
        assertEquals(10, shape.getBadKeysCache()[0]);
        assertEquals(1, shape.getBadPrefixSumsCache()[0]);
    }

    @Test
    public void testClear() {
        TestShape shape = new TestShape();
        shape.addBadLocation(10);
        shape.flushAndRebuild(shape.spatialResolution());

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

        shape.flushAndRebuild(shape.spatialResolution());

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

        shape.flushAndRebuild(shape.spatialResolution());

        long[] mappedKeys = shape.getBiomeMappedKeysCache();
        assertEquals(1, mappedKeys.length);
        assertEquals(10, mappedKeys[0]);
        assertEquals(1, shape.getBiomeMappedPrefixSumsCache()[0]);
        assertEquals(1, shape.getEffectiveGoodCount());
    }

    @Test
    public void testUnionMergesOnBiomeIdentity() {
        TestShape shape = new TestShape();
        // Adjacent, but different biomes: proximity alone must not coalesce them, otherwise a
        // union run cannot carry a single biome id.
        shape.addBiomeLocation(10L, 1L, "ocean");  // [10, 11)
        shape.addBiomeLocation(11L, 1L, "forest"); // [11, 12)
        // Same biome across a resolution-sized gap still coalesces.
        shape.addBiomeLocation(30L, 1L, "desert"); // [30, 31)
        shape.addBiomeLocation(31L, 1L, "desert"); // [31, 32)

        shape.flushAndRebuild(shape.spatialResolution());

        long[] mappedKeys = shape.getBiomeMappedKeysCache();
        short[] ids = shape.getBiomeMappedIdsCache();
        String[] names = shape.getBiomeMappedNamesCache();

        assertEquals(3, mappedKeys.length);
        assertEquals(mappedKeys.length, ids.length);
        assertEquals(10, mappedKeys[0]);
        assertEquals(11, mappedKeys[1]);
        assertEquals(30, mappedKeys[2]);
        assertEquals("OCEAN", names[ids[0]]);
        assertEquals("FOREST", names[ids[1]]);
        assertEquals("DESERT", names[ids[2]]);

        // Widths still sum to the recorded cell count: the identity split does not double-count.
        assertEquals(4, shape.getEffectiveGoodCount());
        assertEquals("OCEAN", shape.biomeAt(10L));
        assertEquals("FOREST", shape.biomeAt(11L));
        assertEquals("DESERT", shape.biomeAt(31L));
    }

    @Test
    public void testUnionClipsCrossBiomeOverlap() {
        TestShape shape = new TestShape();
        shape.addBiomeLocation(10L, 4L, "ocean");  // [10, 14)
        shape.addBiomeLocation(12L, 4L, "forest"); // [12, 16), overlapping [12, 14)

        shape.flushAndRebuild(shape.spatialResolution());

        long[] mappedKeys = shape.getBiomeMappedKeysCache();
        long[] mappedSums = shape.getBiomeMappedPrefixSumsCache();
        short[] ids = shape.getBiomeMappedIdsCache();
        String[] names = shape.getBiomeMappedNamesCache();

        // The placed run keeps the contested cells; the incoming run is clipped past them, so the
        // union remains a partition of [10, 16) and the overlap is counted once.
        assertEquals(2, mappedKeys.length);
        assertEquals(10, mappedKeys[0]);
        assertEquals(14, mappedKeys[1]);
        assertEquals(4, mappedSums[0]);
        assertEquals(6, mappedSums[1]);
        assertEquals("OCEAN", names[ids[0]]);
        assertEquals("FOREST", names[ids[1]]);
        assertEquals(6, shape.getEffectiveGoodCount());

        // Extents are attributed, not un-clipped: the contested cells belong to whichever biome
        // biomeAt reports, so per-biome widths sum to the good count instead of over-counting.
        assertEquals(4, shape.biomeWidth("ocean"));
        assertEquals(2, shape.biomeWidth("forest"));
        assertEquals(shape.getEffectiveGoodCount(),
                shape.biomeWidth("ocean") + shape.biomeWidth("forest"));
        assertEquals("OCEAN", shape.biomeAt(13L));
        assertEquals("FOREST", shape.biomeAt(14L));

        // Range queries agree with the same attribution.
        assertEquals(4, shape.biomeDensity("ocean", 0L, 100L));
        assertEquals(2, shape.biomeDensity("forest", 0L, 100L));
        assertEquals(0, shape.biomeDensity("forest", 10L, 14L));
        assertEquals(2, shape.biomeDensity("ocean", 12L, 100L));
        assertEquals(0, shape.biomeWidth("desert"));
    }

    @Test
    public void testUnionBlockedTableSpansBlocksAndHugeGaps() {
        TestShape shape = new TestShape();
        // More runs than one block holds, plus a gap wider than Integer.MAX_VALUE so the builder
        // has to close a block early. Neither may change what the table reports.
        int runs = 2500;
        long[] expectedKeys = new long[runs + 1];
        for (int i = 0; i < runs; i++) {
            long key = 10L + i * 4L;
            expectedKeys[i] = key;
            shape.addBiomeLocation(key, 1L, (i % 2 == 0) ? "ocean" : "forest");
        }
        long farKey = 10L + runs * 4L + 5_000_000_000L;
        expectedKeys[runs] = farKey;
        shape.addBiomeLocation(farKey, 1L, "desert");

        shape.flushAndRebuild(shape.spatialResolution());

        long[] mappedKeys = shape.getBiomeMappedKeysCache();
        long[] mappedSums = shape.getBiomeMappedPrefixSumsCache();
        assertEquals(runs + 1, mappedKeys.length);
        for (int i = 0; i <= runs; i++) {
            assertEquals(expectedKeys[i], mappedKeys[i]);
            assertEquals(i + 1L, mappedSums[i]);
        }
        assertEquals(runs + 1, shape.getEffectiveGoodCount());

        // Point lookup must agree across every block, including the one past the huge gap.
        assertEquals("OCEAN", shape.biomeAt(10L));
        assertEquals("FOREST", shape.biomeAt(14L));
        assertEquals("OCEAN", shape.biomeAt(10L + 2048L * 4L));
        assertEquals("DESERT", shape.biomeAt(farKey));
        assertNull(shape.biomeAt(11L));
        assertNull(shape.biomeAt(farKey - 1L));
        assertNull(shape.biomeAt(farKey + 1L));
    }

    @Test
    public void testUnionRebuildSharesUnchangedBlocks() {
        TestShape shape = new TestShape();
        int runs = 2500;
        for (int i = 0; i < runs; i++) {
            shape.addBiomeLocation(10L + i * 4L, 1L, (i % 2 == 0) ? "ocean" : "forest");
        }
        shape.flushAndRebuild(shape.spatialResolution());

        MemoryShape.BiomeUnionTable before = shape.union();
        int blocks = before.blockCount();
        assertTrue(blocks > 2);
        int[][] refs = new int[blocks][];
        for (int b = 0; b < blocks; b++) refs[b] = before.keyBlock(b);

        // Append at the high end, as radius growth does. Only the tail block may be reallocated.
        long appended = 10L + runs * 4L;
        shape.addBiomeLocation(appended, 1L, "desert");
        shape.flushAndRebuild(shape.spatialResolution());

        MemoryShape.BiomeUnionTable after = shape.union();
        assertEquals(runs + 1, after.runCount());
        assertEquals(blocks, after.blockCount());
        assertEquals(1, after.freshBlockCount());
        for (int b = 0; b < blocks - 1; b++) {
            assertSame(refs[b], after.keyBlock(b), "block " + b + " should be shared, not copied");
        }
        assertNotSame(refs[blocks - 1], after.keyBlock(blocks - 1));

        // Sharing must not change what the table reports.
        long[] keys = shape.getBiomeMappedKeysCache();
        long[] sums = shape.getBiomeMappedPrefixSumsCache();
        for (int i = 0; i < runs; i++) {
            assertEquals(10L + i * 4L, keys[i]);
            assertEquals(i + 1L, sums[i]);
        }
        assertEquals(appended, keys[runs]);
        assertEquals(runs + 1L, sums[runs]);
        assertEquals(runs + 1, shape.getEffectiveGoodCount());
        assertEquals("DESERT", shape.biomeAt(appended));
        assertEquals("OCEAN", shape.biomeAt(10L));
    }

    @Test
    public void testBiomeAtBinaryLookup() {
        TestShape shape = new TestShape();
        shape.addBiomeLocation(10L, 4L, "ocean");   // ocean:  [10, 14)
        shape.addBiomeLocation(40L, 2L, "forest");  // forest: [40, 42)
        shape.addBiomeLocation(90L, 1L, "desert");  // desert: [90, 91)

        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals("OCEAN", shape.biomeAt(10L));
        assertEquals("OCEAN", shape.biomeAt(13L));
        assertEquals("FOREST", shape.biomeAt(41L));
        assertEquals("DESERT", shape.biomeAt(90L));

        // Gaps and out-of-range must not resolve to a neighbouring run.
        assertNull(shape.biomeAt(14L));
        assertNull(shape.biomeAt(39L));
        assertNull(shape.biomeAt(91L));
        assertNull(shape.biomeAt(0L));
    }

    @Test
    public void testBiomeWidthAndDensity() {
        TestShape shape = new TestShape();
        shape.addBiomeLocation(10L, 4L, "ocean");  // [10, 14)
        shape.addBiomeLocation(40L, 2L, "ocean");  // [40, 42)

        shape.flushAndRebuild(shape.spatialResolution());

        assertEquals(6, shape.biomeWidth("ocean"));
        assertEquals(0, shape.biomeWidth("forest"));

        // Exclusive upper bound, with partial overlap of a straddled run.
        assertEquals(0, shape.biomeWidthBefore("ocean", 10L));
        assertEquals(2, shape.biomeWidthBefore("ocean", 12L));
        assertEquals(4, shape.biomeWidthBefore("ocean", 14L));
        assertEquals(4, shape.biomeWidthBefore("ocean", 40L));
        assertEquals(6, shape.biomeWidthBefore("ocean", 100L));

        // Range queries: full, partial, gap-only, inverted.
        assertEquals(6, shape.biomeDensity("ocean", 0L, 100L));
        assertEquals(2, shape.biomeDensity("ocean", 12L, 40L));
        assertEquals(0, shape.biomeDensity("ocean", 20L, 30L));
        assertEquals(0, shape.biomeDensity("ocean", 50L, 20L));
        assertEquals(0, shape.biomeDensity("forest", 0L, 100L));
    }

    @Test
    public void testBiomeTableVersionTracksRebuilds() {
        TestShape shape = new TestShape();
        long initial = shape.biomeTableVersion();

        shape.addBiomeLocation(10L, 4L, "ocean");
        shape.flushAndRebuild(shape.spatialResolution());
        long afterFirst = shape.biomeTableVersion();
        assertTrue(afterFirst != initial, "a rebuild that swaps the tables must bump the version");

        // No new observations: repeated rebuilds must leave the version alone, otherwise a
        // caller caching the gathered tables re-gathers on every attempt.
        shape.flushAndRebuild(shape.spatialResolution());
        shape.flushAndRebuild(shape.spatialResolution());
        assertEquals(afterFirst, shape.biomeTableVersion(),
                "rebuild with nothing pending must not bump the biome table version");

        shape.addBiomeLocation(90L, 1L, "desert");
        shape.flushAndRebuild(shape.spatialResolution());
        assertTrue(shape.biomeTableVersion() != afterFirst,
                "a new biome observation must invalidate a cached gather");

        // Dropping the tables entirely must also invalidate: an unchanged version would let a
        // cached gather keep drawing from arrays the shape no longer holds.
        long beforeClear = shape.biomeTableVersion();
        shape.clear();
        assertTrue(shape.biomeTableVersion() != beforeClear, "clear() must bump the version");
    }

    /**
     * Builds a shape with {@code biomes} biomes, each holding {@code runsPerBiome} isolated runs.
     * Runs are spaced far enough apart that the rebuild cannot coalesce them, so the resulting
     * key array length is exactly {@code runsPerBiome} per biome.
     */
    private static TestShape buildBiomeShape(int biomes, int runsPerBiome) {
        TestShape shape = new TestShape();
        for (int b = 0; b < biomes; b++) {
            String biome = "biome_" + b;
            for (int r = 0; r < runsPerBiome; r++) {
                shape.addBiomeLocation(RUN_STRIDE * (long) r + b, 2L, biome);
            }
        }
        shape.flushAndRebuild(1);
        return shape;
    }

    private static final long RUN_STRIDE = 64L;

    /** Min-of-trials nanoseconds per {@code biomeAt} call, after JIT warm-up. */
    private static double nanosPerLookup(TestShape shape, int runsPerBiome, int lookups) {
        long span = RUN_STRIDE * runsPerBiome;
        java.util.Random rng = new java.util.Random(20260904L);
        long[] probes = new long[lookups];
        for (int i = 0; i < lookups; i++) probes[i] = Math.floorMod(rng.nextLong(), span);

        double best = Double.MAX_VALUE;
        for (int trial = 0; trial < 5; trial++) {
            long start = System.nanoTime();
            int sink = 0;
            for (long probe : probes) {
                if (shape.biomeAt(probe) != null) sink++;
            }
            long elapsed = System.nanoTime() - start;
            if (sink == Integer.MIN_VALUE) throw new AssertionError("unreachable");
            best = Math.min(best, elapsed / (double) lookups);
        }
        return best;
    }

    /**
     * REQ-RTP-P-001: biome point lookup scales logarithmically with run count.
     *
     * <p>The former implementation floor-scanned every biome's run array forward, so cost grew
     * linearly with runs per biome. Growing the table 64x must not grow per-lookup time anywhere
     * near 64x; a binary search predicts ~2x (log2 64x = 6 extra probes over ~5).
     */
    @Test
    public void testBiomeLookupScalesSublinearlyWithRunCount() {
        final int biomes = 4;
        final int smallRuns = 32;
        final int largeRuns = smallRuns * 64; // 2048

        TestShape small = buildBiomeShape(biomes, smallRuns);
        TestShape large = buildBiomeShape(biomes, largeRuns);
        assertEquals(smallRuns, small.getBiomeKeys("biome_0").length);
        assertEquals(largeRuns, large.getBiomeKeys("biome_0").length);

        // Warm up both shapes so neither measurement pays interpretation/JIT cost.
        nanosPerLookup(small, smallRuns, 20_000);
        nanosPerLookup(large, largeRuns, 20_000);

        double smallNs = nanosPerLookup(small, smallRuns, 200_000);
        double largeNs = nanosPerLookup(large, largeRuns, 200_000);
        double ratio = largeNs / smallNs;

        System.out.println(
                "[DEBUG_LOG] biomeAt runs/biome " + smallRuns + " -> " + smallNs + " ns; "
                        + largeRuns + " -> " + largeNs + " ns; ratio " + ratio
                        + " (table grew " + (largeRuns / smallRuns) + "x)");

        // Generous bound: log growth predicts ~2x, linear growth would be ~64x. Anything under
        // 8x rules out the linear scan while tolerating cache-miss effects and CI noise.
        assertTrue(
                ratio < 8.0,
                "biomeAt appears to scale linearly with run count: ratio=" + ratio
                        + " (" + smallNs + " ns -> " + largeNs + " ns)");
    }

    /**
     * REQ-RTP-P-001: range density queries are two binary searches, not a run walk.
     * Same 64x table growth must not produce proportional time growth.
     */
    @Test
    public void testBiomeDensityScalesSublinearlyWithRunCount() {
        final int smallRuns = 32;
        final int largeRuns = smallRuns * 64;

        TestShape small = buildBiomeShape(1, smallRuns);
        TestShape large = buildBiomeShape(1, largeRuns);

        double smallNs = timeDensity(small, smallRuns, 20_000);
        double largeNs = timeDensity(large, largeRuns, 20_000);
        smallNs = timeDensity(small, smallRuns, 200_000);
        largeNs = timeDensity(large, largeRuns, 200_000);
        double ratio = largeNs / smallNs;

        System.out.println(
                "[DEBUG_LOG] biomeDensity runs " + smallRuns + " -> " + smallNs + " ns; "
                        + largeRuns + " -> " + largeNs + " ns; ratio " + ratio);

        assertTrue(
                ratio < 8.0,
                "biomeDensity appears to scale linearly with run count: ratio=" + ratio);
    }

    private static double timeDensity(TestShape shape, int runsPerBiome, int queries) {
        long span = RUN_STRIDE * runsPerBiome;
        java.util.Random rng = new java.util.Random(9091L);
        double best = Double.MAX_VALUE;
        for (int trial = 0; trial < 5; trial++) {
            long acc = 0L;
            long start = System.nanoTime();
            for (int i = 0; i < queries; i++) {
                long from = Math.floorMod(rng.nextLong(), span);
                acc += shape.biomeDensity("biome_0", from, from + 4096L);
            }
            long elapsed = System.nanoTime() - start;
            if (acc == Long.MIN_VALUE) throw new AssertionError("unreachable");
            best = Math.min(best, elapsed / (double) queries);
        }
        return best;
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

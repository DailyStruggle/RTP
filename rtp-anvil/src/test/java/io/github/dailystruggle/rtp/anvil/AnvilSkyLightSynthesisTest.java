package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthesized sky-light precompute coverage. The {@code requireSkyLight} adjustor
 * predicate ({@code chunk.getSkyLight(...) > 7}) used to reject the overwhelming
 * majority of legitimate overworld surface candidates because freshly-generated
 * chunks ship with {@code isLightOn=false} and a stale / all-zero on-disk
 * {@code SkyLight} nibble payload. The fix prepopulates a sparse "first opaque
 * from top" world-Y per chunk-local {@code (x, z)} column at chunk-resolution
 * time and answers {@code getSkyLight} as a binary 15/0 sky-access proxy.
 *
 * <p>These tests pin the precompute output for a synthetic chunk built with a
 * known opaque surface, asserting:
 * <ul>
 *   <li>queried columns receive the world-Y of their topmost non-air block;</li>
 *   <li>off-list columns stay at {@link Integer#MIN_VALUE} (interpreted as
 *       fully sky-exposed by the consumer);</li>
 *   <li>{@code airBlocks} membership is honoured — leaf canopies at the top
 *       of a column are treated as transparent and the synthesis sees through
 *       them to the first truly opaque block below.</li>
 * </ul>
 */
class AnvilSkyLightSynthesisTest {

    /**
     * Helper: build an {@link AnvilChunkView} containing a single section at
     * {@code sectionY=0} where every column has {@code minecraft:stone} at the
     * supplied surface Y, air everywhere above, and air everywhere below. The
     * surface block is placed at section-local {@code ly == surfaceY & 15}; the
     * section covers world Y {@code 0..15}.
     */
    private static AnvilChunkView singleStoneSurfaceView(int surfaceWorldY) {
        List<String> palette = List.of("minecraft:air", "minecraft:stone");
        int[] indices = new int[4096];
        // Place stone at every (x,z) at surfaceWorldY (which equals ly because sectionY=0).
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                indices[PackedPaletteDecoder.entryIndex(x, surfaceWorldY, z)] = 1;
            }
        }
        long[] data = AnvilTestFixtures.packIndices(4, indices);
        PaletteSection ps = new PaletteSection(0, palette, data);
        return new AnvilChunkView(0, List.of(ps), new long[37],
                Collections.emptyList(), false /* isLightOn */);
    }

    @Test
    @DisplayName("Sparse precompute populates only the requested columns; others remain MIN_VALUE")
    void sparseTableOnlyHasRequestedColumns() {
        AnvilChunkView view = singleStoneSurfaceView(5);
        int[][] cols = {{7, 7}, {2, 2}};
        int[] tops = view.computeSynthesizedSkyTop(cols, Collections.emptySet());

        assertEquals(256, tops.length, "table is always 256-indexed");
        assertEquals(5, tops[(7 << 4) | 7], "(7,7) is on the list and resolves to surface Y");
        assertEquals(5, tops[(2 << 4) | 2], "(2,2) is on the list and resolves to surface Y");
        // Spot-check off-list columns: must remain the sentinel.
        assertEquals(Integer.MIN_VALUE, tops[(0 << 4) | 0],
                "(0,0) was never requested → fully sky-exposed sentinel");
        assertEquals(Integer.MIN_VALUE, tops[(12 << 4) | 12],
                "(12,12) was never requested → sentinel");
    }

    @Test
    @DisplayName("Empty / null column list yields an all-sentinel table without scanning")
    void emptyColumnListYieldsAllSentinel() {
        AnvilChunkView view = singleStoneSurfaceView(5);
        int[] tops = view.computeSynthesizedSkyTop(new int[0][], Collections.emptySet());
        for (int v : tops) {
            assertEquals(Integer.MIN_VALUE, v,
                    "no columns requested → every slot stays at the fully-sky-exposed sentinel");
        }
        int[] tops2 = view.computeSynthesizedSkyTop(null, Collections.emptySet());
        for (int v : tops2) {
            assertEquals(Integer.MIN_VALUE, v, "null columns are tolerated");
        }
    }

    @Test
    @DisplayName("Reconciled airBlocks members are treated as transparent and synthesis sees through them")
    void airBlocksHonoured() {
        // Build a section where (7,7) carries leaves at Y=10 and stone at Y=5 — every
        // other cell is air. Without airBlocks, synthesis would stop at the leaves
        // (Y=10); with airBlocks containing LEAVES, it should see through and report
        // Y=5 as the first opaque block.
        List<String> palette = List.of("minecraft:air", "minecraft:oak_leaves", "minecraft:stone");
        int[] indices = new int[4096];
        indices[PackedPaletteDecoder.entryIndex(7, 10, 7)] = 1; // leaves
        indices[PackedPaletteDecoder.entryIndex(7,  5, 7)] = 2; // stone
        long[] data = AnvilTestFixtures.packIndices(4, indices);
        PaletteSection ps = new PaletteSection(0, palette, data);
        AnvilChunkView view = new AnvilChunkView(
                0, List.of(ps), new long[37], Collections.emptyList(), false);

        int[][] cols = {{7, 7}};
        int[] withoutAir = view.computeSynthesizedSkyTop(cols, Collections.emptySet());
        assertEquals(10, withoutAir[(7 << 4) | 7],
                "without airBlocks the leaves at Y=10 count as opaque");

        int[] withAir = view.computeSynthesizedSkyTop(cols, Set.of("OAK_LEAVES"));
        assertEquals(5, withAir[(7 << 4) | 7],
                "with airBlocks containing OAK_LEAVES, synthesis sees through to stone at Y=5");
    }

    @Test
    @DisplayName("Empty section list yields an all-sentinel table (vacuum column)")
    void emptySectionListYieldsAllSentinel() {
        AnvilChunkView empty = new AnvilChunkView(
                0, Collections.emptyList(), new long[0], Collections.emptyList(), false);
        int[] tops = empty.computeSynthesizedSkyTop(new int[][]{{7, 7}}, Collections.emptySet());
        assertEquals(Integer.MIN_VALUE, tops[(7 << 4) | 7],
                "no sections → no opaque block → fully sky-exposed");
    }

    @Test
    @DisplayName("Lookup semantics: y > firstOpaque returns 15 (sky-exposed), y <= firstOpaque returns 0")
    void lookupSemanticsBinary() {
        AnvilChunkView view = singleStoneSurfaceView(5);
        int[][] cols = {{7, 7}};
        int[] tops = view.computeSynthesizedSkyTop(cols, Collections.emptySet());
        int idx = (7 << 4) | 7;
        int firstOpaque = tops[idx];

        // Mirrors the lookup the platform adapter does in getSkyLight.
        assertTrue(firstOpaque == 5, "surface should be detected at Y=5");
        // Above the surface → fully lit.
        assertEquals(15, (firstOpaque == Integer.MIN_VALUE || 6 > firstOpaque) ? 15 : 0);
        assertEquals(15, (firstOpaque == Integer.MIN_VALUE || 100 > firstOpaque) ? 15 : 0);
        // At and below the surface → blocked.
        assertEquals(0, (firstOpaque == Integer.MIN_VALUE || 5 > firstOpaque) ? 15 : 0);
        assertEquals(0, (firstOpaque == Integer.MIN_VALUE || 0 > firstOpaque) ? 15 : 0);
    }
}

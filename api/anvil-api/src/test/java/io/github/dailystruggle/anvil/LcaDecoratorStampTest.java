package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link LcaDecoratorStamp} {@code 01:H_3} Hilbert round-trip, wood-over-foliage conflict
 * precedence, contiguous Hilbert run coalescing, and multi-chunk canopy slicing.
 *
 * @governedBy anvil-api-ADR-003, leafgen-ADR-002, treegen-ADR-003, ADR-115
 * @trace REQ-CFG-ATLAS-001, REQ-RTP-S-004, REQ-RTP-S-005
 */
class LcaDecoratorStampTest {

    @AfterEach
    void tearDown() {
        LcaDecoratorStamp.clearRegistry();
    }

    @Test
    @DisplayName("01:H_3 xyzToH3 and h3ToYzx round-trip across all 4,096 section coordinates")
    void h3CoordinateRoundTripIsExact() {
        for (int h3 = 0; h3 < 4096; h3++) {
            int yzx = LcaDecoratorStamp.h3ToYzx(h3);
            int lx = yzx & 15;
            int lz = (yzx >>> 4) & 15;
            int ly = (yzx >>> 8) & 15;
            assertEquals(h3, LcaDecoratorStamp.xyzToH3(lx, ly, lz));
        }
    }

    @Test
    @DisplayName("Multi-chunk canopy slices coalesce contiguous 01:H_3 runs and preserve WOOD over FOLIAGE")
    void compilesMultiChunkCanopySlicesAndCoalescesHilbertRuns() {
        List<LcaDecoratorStamp.RelativeVoxel> voxels = new ArrayList<>();
        // Anchor near East/South chunk border (14, 14) so canopy crosses into (+1, 0), (0, +1), (+1, +1)
        for (int dy = 0; dy < 5; dy++) {
            voxels.add(new LcaDecoratorStamp.RelativeVoxel(
                    0, dy, 0, "minecraft:oak_log[axis=y]", LcaDecoratorStamp.BlockCategory.WOOD));
        }
        // Overlapping leaf at trunk top must not clobber WOOD
        voxels.add(new LcaDecoratorStamp.RelativeVoxel(
                0, 4, 0, "minecraft:oak_leaves[distance=1]", LcaDecoratorStamp.BlockCategory.FOLIAGE));
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                voxels.add(new LcaDecoratorStamp.RelativeVoxel(
                        dx, 4, dz, "minecraft:oak_leaves[distance=2]", LcaDecoratorStamp.BlockCategory.FOLIAGE));
            }
        }

        LcaDecoratorStamp stamp = LcaDecoratorStamp.fromRelativeBlocks(
                "Spiral_Oak", 14, 14, voxels, Set.of(1, 5));
        assertEquals("spiral_oak", stamp.speciesId());
        assertEquals(4, stamp.chunkSlices().size(), "Border canopy at (14,14) must partition across 4 chunks");
        assertTrue(
                stamp.totalHilbertRunCount() < stamp.totalVoxelCount(),
                "Spatial 2D/3D canopy blocks must coalesce into fewer 01:H_3 runs than raw voxels");

        LcaDecoratorStamp.ChunkCanopySlice originSlice = stamp.chunkSlices().stream()
                .filter(s -> s.deltaChunkX() == 0 && s.deltaChunkZ() == 0)
                .findFirst()
                .orElseThrow();
        assertEquals(
                LcaDecoratorStamp.BlockCategory.WOOD,
                originSlice.runs().get(0).category(),
                "WOOD runs must precede FOLIAGE runs in each chunk slice");

        LcaDecoratorStamp.registerStamp(stamp);
        assertNotNull(LcaDecoratorStamp.selectDeterministicStamp(1, 99));
    }
}

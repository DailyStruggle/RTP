package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LcaStructureStampTest {

    @Test
    void loadsGzipTemplateOmitsStructureVoidCarvesAirAndTransformsEightSymmetries() throws IOException {
        List<String> palette = List.of(
                "minecraft:structure_void",
                "minecraft:stone_bricks",
                "minecraft:air",
                "minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]",
                "minecraft:oak_log[axis=x]",
                "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]",
                "minecraft:chest[facing=east,type=single,waterlogged=false]",
                "minecraft:jigsaw[orientation=north_up]");

        int[][] blocks = {
            {0, 0, 0, 0}, // structure_void -> omitted completely
            {1, 0, 0, 1}, // stone_bricks
            {1, 1, 1, 2}, // interior air -> explicit air HilbertRun
            {2, 1, 0, 3}, // stairs facing=north, inner_left
            {3, 1, 0, 4}, // log axis=x
            {0, 1, 2, 5}, // fence east=true
            {2, 1, 2, 6}, // chest with block-entity LootTable
            {1, 1, 0, 7} // jigsaw north_up -> replaced by final_state cobblestone
        };

        Map<String, Object> chestNbt = new LinkedHashMap<>();
        chestNbt.put("id", "minecraft:chest");
        chestNbt.put("LootTable", "minecraft:chests/simple_dungeon");

        Map<String, Object> jigsawNbt = new LinkedHashMap<>();
        jigsawNbt.put("id", "minecraft:jigsaw");
        jigsawNbt.put("name", "minecraft:entrance");
        jigsawNbt.put("target", "minecraft:street");
        jigsawNbt.put("pool", "minecraft:village/plains/streets");
        jigsawNbt.put("final_state", "minecraft:cobblestone");
        jigsawNbt.put("joint", "aligned");

        Map<Integer, Map<String, Object>> beByRow = Map.of(6, chestNbt, 7, jigsawNbt);
        byte[] gzBytes = StructureTemplateNbtLoader.writeStructureNbtBytes(4, 3, 5, palette, blocks, beByRow, true);

        List<LcaStructureStamp.StructureProcessorRule> rules = List.of(
                new LcaStructureStamp.StructureProcessorRule(1, 2, 0.30f, 0xABCDL, true));
        StructureTemplateNbtLoader.TemplateVariantSet set = StructureTemplateNbtLoader.loadFromBytes(
                "minecraft:test_hall",
                gzBytes,
                LcaStructureStamp.TerrainProjection.RIGID,
                rules);

        assertEquals(8, set.variants().size());

        LcaStructureStamp v0 = set.variant(0, false);
        assertEquals(4, v0.sizeX());
        assertEquals(3, v0.sizeY());
        assertEquals(5, v0.sizeZ());
        // 8 raw blocks minus 1 structure_void = 7 placed voxels (including explicit air and jigsaw cobblestone)
        assertEquals(7, v0.totalVoxelCount());
        assertTrue(v0.statePalette().contains("minecraft:air"));
        assertTrue(v0.statePalette().contains("minecraft:cobblestone"));
        assertFalse(v0.statePalette().contains("minecraft:jigsaw[orientation=north_up]"));
        assertEquals(1, v0.blockEntities().size());
        assertEquals("minecraft:chests/simple_dungeon", v0.blockEntities().get(0).lootTableOrConfigId());

        List<LcaStructureStamp.JigsawConnector> c0 = set.connectors(0, false);
        assertEquals(1, c0.size());
        assertEquals("north", c0.get(0).frontFacing());

        // CW_90 rotation swaps sizeX/sizeZ (4x5 -> 5x4), rotates north->east, axis=x->z, fence east->south
        LcaStructureStamp vCw90 = set.variant(1, false);
        assertEquals(5, vCw90.sizeX());
        assertEquals(4, vCw90.sizeZ());
        assertEquals(7, vCw90.totalVoxelCount());
        assertTrue(vCw90.statePalette().contains(
                "minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left,waterlogged=false]"));
        assertTrue(vCw90.statePalette().contains("minecraft:oak_log[axis=z]"));
        assertTrue(vCw90.statePalette().contains(
                "minecraft:oak_fence[east=false,north=false,south=true,waterlogged=false,west=false]"));
        assertEquals("east", set.connectors(1, false).get(0).frontFacing());
        // Chest at (2,1,2) in 4x5 rotates CW_90 to (5-1-2, 1, 2) = (2,1,2) with facing east->south
        assertEquals(2, vCw90.blockEntities().get(0).relX());
        assertEquals(2, vCw90.blockEntities().get(0).relZ());

        // Front-back mirror flips east<->west and inner_left->inner_right
        LcaStructureStamp vMir0 = set.variant(0, true);
        assertTrue(vMir0.statePalette().contains(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=inner_right,waterlogged=false]"));
        assertTrue(vMir0.statePalette().contains(
                "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=true]"));
    }

    @Test
    void failsClosedOnCorruptOrEmptyStructureNbt() {
        assertThrows(
                IOException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "minecraft:broken",
                        new byte[] {0x1F, (byte) 0x8B, 0x08, 0x00, 0x01},
                        LcaStructureStamp.TerrainProjection.RIGID,
                        List.of()));
        assertThrows(
                IOException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "minecraft:empty",
                        new byte[0],
                        LcaStructureStamp.TerrainProjection.RIGID,
                        List.of()));
    }

    @Test
    void processorRulesWaterloggingAndDecoratorBridgeBehaveDeterministically() {
        List<LcaStructureStamp.StructureProcessorRule> rules = List.of(
                new LcaStructureStamp.StructureProcessorRule(1, 4, 0.35f, 9911L, true));
        int mossyHits = 0;
        for (int x = 0; x < 200; x++) {
            int outA = LcaStructureStamp.evaluateProcessorPaletteIndex(1, rules, x, 64, -17, 8675309L);
            int outB = LcaStructureStamp.evaluateProcessorPaletteIndex(1, rules, x, 64, -17, 8675309L);
            assertEquals(outA, outB);
            if (outA == 4) {
                mossyHits++;
            }
        }
        assertTrue(mossyHits > 30 && mossyHits < 110, "Expected ~35% substitution rate, got " + mossyHits);

        assertTrue(LcaStructureStamp.isWaterloggableState("minecraft:oak_stairs[facing=north,half=bottom]"));
        assertFalse(LcaStructureStamp.isWaterloggableState("minecraft:stone_bricks"));
        assertFalse(LcaStructureStamp.isWaterloggableState("minecraft:oak_slab[type=double]"));
        assertEquals(
                "minecraft:oak_stairs[facing=north,half=bottom,waterlogged=true]",
                LcaStructureStamp.withWaterlogged("minecraft:oak_stairs[facing=north,half=bottom]", true));

        LcaDecoratorStamp treeStamp = LcaDecoratorStamp.fromRelativeBlocks(
                "test_oak",
                14,
                14,
                List.of(
                        new LcaDecoratorStamp.RelativeVoxel(0, 0, 0, "minecraft:oak_log[axis=y]", LcaDecoratorStamp.BlockCategory.WOOD),
                        new LcaDecoratorStamp.RelativeVoxel(3, 4, 3, "minecraft:oak_leaves[distance=1,persistent=false]", LcaDecoratorStamp.BlockCategory.FOLIAGE)),
                Set.of(0, 1));
        LcaStructureStamp bridged = LcaStructureStamp.fromDecoratorStamp(treeStamp);
        assertEquals(2, bridged.totalVoxelCount());
        assertNotNull(bridged.sliceAt(0, 0));
        assertNotNull(bridged.sliceAt(1, 1));
    }

    @Test
    void builtinTemplatesProvideMultiChunkAndJigsawCoverage() {
        for (String id : List.of(
                "desert_pyramid",
                "shipwreck",
                "ruined_portal",
                "buried_treasure",
                "mineshaft",
                "village_plains",
                "trial_chambers",
                "ancient_city",
                "trail_ruins")) {
            StructureTemplateNbtLoader.TemplateVariantSet set =
                    StructureTemplateNbtLoader.synthesizeBuiltinVanillaTemplate(id);
            LcaStructureStamp stamp = set.variant(0, false);
            assertTrue(stamp.totalVoxelCount() > 0, "Empty builtin template for " + id);
        }
        assertEquals(2, StructureTemplateNbtLoader.synthesizeBuiltinVanillaTemplate("desert_pyramid")
                .variant(0, false).chunkRadiusX());
        assertFalse(StructureTemplateNbtLoader.synthesizeBuiltinVanillaTemplate("village_plains")
                .connectors(0, false).isEmpty());
    }
}

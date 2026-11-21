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

    @Test
    void validatesMalformedInputsSupportsRawAndMultiPaletteNbtAndTransformsAllBlockStates() throws IOException {
        assertThrows(
                IllegalArgumentException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "   ",
                        new byte[] {1, 2, 3, 4},
                        LcaStructureStamp.TerrainProjection.RIGID,
                        List.of()));
        assertThrows(
                IOException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "minecraft:null_bytes",
                        null,
                        LcaStructureStamp.TerrainProjection.RIGID,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructureTemplateNbtLoader.writeStructureNbtBytes(
                        0, 1, 1, List.of("minecraft:stone"), new int[][] {{0, 0, 0, 0}}, Map.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructureTemplateNbtLoader.writeStructureNbtBytes(
                        1, 0, 1, List.of("minecraft:stone"), new int[][] {{0, 0, 0, 0}}, Map.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructureTemplateNbtLoader.writeStructureNbtBytes(
                        1, 1, 0, List.of("minecraft:stone"), new int[][] {{0, 0, 0, 0}}, Map.of(), false));

        // Raw (uncompressed) NBT roundtrip with null rules list
        byte[] rawBytes = StructureTemplateNbtLoader.writeStructureNbtBytes(
                2, 2, 2, List.of("minecraft:stone"), new int[][] {{0, 0, 0, 0}}, null, false);
        StructureTemplateNbtLoader.TemplateVariantSet rawSet = StructureTemplateNbtLoader.loadFromBytes(
                "minecraft:raw_box", rawBytes, LcaStructureStamp.TerrainProjection.RIGID, null);
        assertEquals(1, rawSet.variant(0, false).totalVoxelCount());

        // Multi-palette ('palettes') fallback compound
        LinkedHashMap<String, Object> multiRoot = new LinkedHashMap<>();
        multiRoot.put("size", new Nbt.NbtList(Nbt.TAG_INT, List.of(1, 1, 1)));
        LinkedHashMap<String, Object> palItem = new LinkedHashMap<>();
        palItem.put("Name", "minecraft:cobblestone");
        multiRoot.put(
                "palettes",
                new Nbt.NbtList(
                        Nbt.TAG_LIST,
                        List.of(new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(palItem)))));
        LinkedHashMap<String, Object> blkItem = new LinkedHashMap<>();
        blkItem.put("pos", new Nbt.NbtList(Nbt.TAG_INT, List.of(0, 0, 0)));
        blkItem.put("state", 0);
        multiRoot.put("blocks", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(blkItem)));
        StructureTemplateNbtLoader.TemplateVariantSet multiSet = StructureTemplateNbtLoader.loadFromBytes(
                "minecraft:multi_pal",
                Nbt.writeNamedRoot("", multiRoot),
                LcaStructureStamp.TerrainProjection.RIGID,
                List.of());
        assertEquals(1, multiSet.variant(0, false).totalVoxelCount());

        // Invalid dimensions, empty palette, missing Name, and out-of-bounds state index
        LinkedHashMap<String, Object> badDim = new LinkedHashMap<>();
        badDim.put("size", new Nbt.NbtList(Nbt.TAG_INT, List.of(600, 1, 1)));
        assertThrows(
                IOException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "minecraft:bad_dim",
                        Nbt.writeNamedRoot("", badDim),
                        LcaStructureStamp.TerrainProjection.RIGID,
                        List.of()));

        LinkedHashMap<String, Object> emptyPal = new LinkedHashMap<>();
        emptyPal.put("size", new Nbt.NbtList(Nbt.TAG_INT, List.of(1, 1, 1)));
        emptyPal.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));
        assertThrows(
                IOException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "minecraft:empty_pal",
                        Nbt.writeNamedRoot("", emptyPal),
                        LcaStructureStamp.TerrainProjection.RIGID,
                        List.of()));

        LinkedHashMap<String, Object> blankName = new LinkedHashMap<>();
        blankName.put("size", new Nbt.NbtList(Nbt.TAG_INT, List.of(1, 1, 1)));
        LinkedHashMap<String, Object> blankPalEntry = new LinkedHashMap<>();
        blankPalEntry.put("Name", "  ");
        blankName.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(blankPalEntry)));
        assertThrows(
                IOException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "minecraft:blank_name",
                        Nbt.writeNamedRoot("", blankName),
                        LcaStructureStamp.TerrainProjection.RIGID,
                        List.of()));

        LinkedHashMap<String, Object> oobState = new LinkedHashMap<>();
        oobState.put("size", new Nbt.NbtList(Nbt.TAG_INT, List.of(1, 1, 1)));
        oobState.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(palItem)));
        LinkedHashMap<String, Object> badBlk = new LinkedHashMap<>();
        badBlk.put("pos", new Nbt.NbtList(Nbt.TAG_INT, List.of(0, 0, 0)));
        badBlk.put("state", 5);
        oobState.put("blocks", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(badBlk)));
        assertThrows(
                IOException.class,
                () -> StructureTemplateNbtLoader.loadFromBytes(
                        "minecraft:oob_state",
                        Nbt.writeNamedRoot("", oobState),
                        LcaStructureStamp.TerrainProjection.RIGID,
                        List.of()));

        // transformState coverage across mirror + quarter-turn rotations + edge formats
        assertEquals("minecraft:stone", StructureTemplateNbtLoader.transformState("minecraft:stone", true, 1));
        assertEquals("minecraft:stone[broken", StructureTemplateNbtLoader.transformState("minecraft:stone[broken", true, 1));
        assertEquals("minecraft:stone", StructureTemplateNbtLoader.transformState("minecraft:stone[]", false, 0));
        assertEquals(
                "minecraft:oak_sign[rotation=14]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_sign[rotation=2,badtok]", true, 0));
        assertEquals(
                "minecraft:oak_sign[rotation=bad]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_sign[rotation=bad]", true, 1));
        assertEquals(
                "minecraft:oak_sign[rotation=1234]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_sign[rotation=1234]", false, 1));
        assertEquals(
                "minecraft:oak_sign[rotation=6]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_sign[rotation=2]", false, 1));
        assertEquals(
                "minecraft:oak_fence[west=true]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_fence[east=true]", true, 0));
        assertEquals(
                "minecraft:oak_fence[east=true]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_fence[west=true]", true, 0));
        assertEquals(
                "minecraft:jigsaw[orientation=west_up]",
                StructureTemplateNbtLoader.transformState("minecraft:jigsaw[orientation=east_up]", true, 0));
        assertEquals(
                "minecraft:jigsaw[orientation=single]",
                StructureTemplateNbtLoader.transformState("minecraft:jigsaw[orientation=single]", true, 1));
        assertEquals(
                "minecraft:oak_log[axis=x]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_log[axis=z]", false, 1));
        assertEquals(
                "minecraft:oak_log[axis=y]",
                StructureTemplateNbtLoader.transformState("minecraft:oak_log[axis=y]", false, 1));
        for (String railShape : List.of(
                "north_south",
                "east_west",
                "ascending_east",
                "ascending_west",
                "ascending_north",
                "ascending_south",
                "south_east",
                "south_west",
                "north_west",
                "north_east",
                "outer_left",
                "outer_right",
                "straight")) {
            assertNotNull(StructureTemplateNbtLoader.transformState(
                    "minecraft:rail[shape=" + railShape + "]", true, 1));
            assertNotNull(StructureTemplateNbtLoader.transformState(
                    "minecraft:rail[shape=" + railShape + "]", false, 3));
        }
    }
}

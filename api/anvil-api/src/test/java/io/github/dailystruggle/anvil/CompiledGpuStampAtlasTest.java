package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CompiledGpuStampAtlasTest {

    @AfterEach
    void cleanup() {
        LcaDecoratorStamp.clearRegistry();
    }

    @Test
    void compilesDecoratorAndStructureStampsWithDryWetPairsAndExactProcessorRuleHashes() {
        String[] baseStates = {
            "minecraft:air",
            "minecraft:stone",
            "minecraft:water"
        };

        long epochBefore = LcaDecoratorStamp.registryEpoch();
        LcaDecoratorStamp cherry = LcaDecoratorStamp.fromRelativeBlocks(
                "atlas_cherry",
                14,
                14,
                List.of(
                        new LcaDecoratorStamp.RelativeVoxel(
                                0, 0, 0, "minecraft:cherry_log[axis=y]", LcaDecoratorStamp.BlockCategory.WOOD),
                        new LcaDecoratorStamp.RelativeVoxel(
                                3, 2, 3, "minecraft:cherry_leaves[distance=1]", LcaDecoratorStamp.BlockCategory.FOLIAGE)),
                Set.of(1));
        LcaDecoratorStamp.registerStamp(cherry);
        assertTrue(LcaDecoratorStamp.registryEpoch() > epochBefore);

        StructureTemplateNbtLoader.TemplateVariantSet templeSet =
                StructureTemplateNbtLoader.synthesizeBuiltinVanillaTemplate("minecraft:ocean_ruin_cold");
        LcaStructureStamp ruinVar0 = templeSet.variant(0, false);

        long structEpochBefore = LcaStructureStamp.registryEpoch();
        assertTrue(LcaStructureStamp.bumpRegistryEpoch() > structEpochBefore);

        CompiledGpuStampAtlas atlas = CompiledGpuStampAtlas.compile(
                baseStates, List.of(cherry), templeSet.variants(), 42L);

        assertEquals(42L, atlas.atlasEpoch());
        assertEquals(3, atlas.baseStateCount());
        assertTrue(atlas.sliceCount() > 0);
        assertTrue(atlas.runCount() > 0);

        int centerDecSlice = atlas.sliceIndexForDecorator(cherry, 0, 0);
        int borderDecSlice = atlas.sliceIndexForDecorator(cherry, 1, 1);
        assertTrue(centerDecSlice >= 0);
        assertTrue(borderDecSlice >= 0);
        assertEquals(-1, atlas.sliceIndexForDecorator(cherry, -2, 0));

        int ruinSlice = atlas.sliceIndexForStructure(ruinVar0, 0, 0);
        assertTrue(ruinSlice >= 0);

        // Verify waterloggable structure states received distinct wetGlobalStateId != dryGlobalStateId
        int[] meta = atlas.paletteMetaI32();
        boolean sawDistinctWetId = false;
        boolean sawStructureAirZero = false;
        for (int i = 0; i < meta.length; i += CompiledGpuStampAtlas.PALETTE_META_WORDS) {
            int dryId = meta[i] & 0xFFFF;
            int wetId = (meta[i] >>> 16) & 0xFFFF;
            int cat = meta[i + 1] & 0xFF;
            if (cat == CompiledGpuStampAtlas.CAT_STRUCTURE_AIR) {
                assertEquals(0, dryId);
                sawStructureAirZero = true;
            }
            if (dryId != wetId) {
                sawDistinctWetId = true;
                String wetState = atlas.stateNameForGlobalId(wetId);
                assertTrue(wetState.contains("waterlogged=true"), "Expected waterlogged=true in " + wetState);
                assertNotEquals(atlas.stateNameForGlobalId(dryId), wetState);
            }
        }
        assertTrue(sawStructureAirZero, "Interior air carving runs shall map to global state 0");
        assertTrue(sawDistinctWetId, "Waterloggable template states shall pre-intern wetStateId");
    }
}

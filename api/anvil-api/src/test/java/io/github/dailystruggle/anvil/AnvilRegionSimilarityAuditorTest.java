package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RegionSimilarityAuditor} verifying cross-format {@code .mca}/{@code .lca}
 * self-parity, topographical heightmap statistics, strict vs semantic voxel classification,
 * climate biome bucket mapping, cave air/fluid bands, ore counts, structure NBT retention, and
 * proto-chunk filtering (`ADR-016`, `ADR-077`, `ADR-115`, `anvil-api-ADR-002`, `anvil-api-ADR-003`,
 * `REQ-RTP-S-005`).
 */
@DisplayName("anvil-api-ADR-002 / ADR-003: RegionSimilarityAuditor Multi-Format Parity")
class AnvilRegionSimilarityAuditorTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        RegionFormatRegistry.reset();
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Identical chunk encoded as .mca and transcoded .lca yields 100% parity")
    void mcaToLcaSelfParityYieldsExactMatch(@TempDir Path tmpDir) throws IOException {
        LinkedHashMap<String, Object> root = buildSyntheticOverworldChunk(
                0,
                false,
                true,
                "minecraft:oak_log",
                "minecraft:cave_air",
                "minecraft:birch_forest",
                true,
                "minecraft:full");
        byte[] mcaBytes = AnvilTestFixtures.writeSingleChunkRegion(root);
        byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);

        Path refWorld = tmpDir.resolve("vanilla_world");
        Path candWorld = tmpDir.resolve("leafgen_world");
        Files.createDirectories(refWorld.resolve("region"));
        Files.createDirectories(refWorld.resolve("entities"));
        Files.createDirectories(candWorld.resolve("region"));

        Files.write(refWorld.resolve("region/r.0.0.mca"), mcaBytes);
        // Empty entities region must be ignored by block-region discovery
        Files.write(refWorld.resolve("entities/r.0.0.mca"), new byte[8192]);
        Files.write(candWorld.resolve("region/r.0.0.lca"), lcaBytes);

        RegionSimilarityAuditor.RegionSimilarityScorecard sc =
                RegionSimilarityAuditor.compareDirectories(
                        refWorld, candWorld, "vanilla-mca", "leafgen-lca", 0);

        assertEquals(1, sc.comparedFullChunks());
        assertEquals(0, sc.refOnlyChunks());
        assertEquals(0, sc.candOnlyChunks());
        assertEquals(100.0, sc.strictBlockIdMatchPct(), 1e-6);
        assertEquals(100.0, sc.semanticCategoryMatchPct(), 1e-6);
        assertEquals(100.0, sc.exactBiomeQuartetMatchPct(), 1e-6);
        assertEquals(100.0, sc.climateClassQuartetMatchPct(), 1e-6);

        for (RegionSimilarityAuditor.HeightmapStats hm : sc.heightmaps().values()) {
            assertEquals(256L, hm.sampleColumns());
            assertEquals(1.0, hm.pearsonR(), 1e-6);
            assertEquals(0.0, hm.maeBlocks(), 1e-6);
            assertEquals(0.0, hm.rmseBlocks(), 1e-6);
            assertEquals(0, hm.maxAbsDeltaBlocks());
        }

        assertEquals(sc.refOreCounts(), sc.candOreCounts());
        assertEquals(4L, sc.refOreCounts().get("diamond"));
        assertEquals(6L, sc.refOreCounts().get("coal"));
        assertEquals(sc.refFloraAndCanopyVoxels(), sc.candFloraAndCanopyVoxels());
        assertTrue(sc.refFloraAndCanopyVoxels() > 0L);

        RegionSimilarityAuditor.CaveFluidStats cf = sc.caveFluidStats();
        assertEquals(16L, cf.refUpperCaveAirVoxels());
        assertEquals(16L, cf.candUpperCaveAirVoxels());
        assertEquals(8L, cf.refDeepLavaVoxels());
        assertEquals(8L, cf.candDeepLavaVoxels());

        assertEquals(1, sc.refChunksWithStructureStarts());
        assertEquals(1, sc.candChunksWithStructureStarts());
        assertEquals(1, sc.refChunksWithStructureReferences());
        assertEquals(1, sc.candChunksWithStructureReferences());
        assertEquals(1, sc.refStructureStartCounts().get("minecraft:village_plains"));

        RegionSimilarityAuditor.MigrationSeamStats ms = sc.migrationSeam();
        assertEquals(0L, ms.comparedSharedChunkEdges());
        assertEquals(60L, ms.borderColumnsChecked());
        assertEquals(100.0, ms.borderSurfaceExactPct(), 1e-6);
        assertEquals(100.0, ms.borderSurfaceWithin1BlockPct(), 1e-6);
        assertEquals(0.0, ms.seamSurfaceMaeBlocks(), 1e-6);
        assertEquals(0, ms.seamMaxInducedCliffBlocks());
        assertEquals(0L, ms.seamInducedCliffEdges());
        assertEquals(100.0, ms.borderExactBiomePct(), 1e-6);
        assertEquals(100.0, ms.borderStrictBlockPct(), 1e-6);
        assertEquals(100.0, ms.interiorStrictBlockPct(), 1e-6);
        assertEquals(100.0, ms.borderSubterraneanSolidAirAgreementPct(), 1e-6);
        assertEquals(0L, ms.borderSubterraneanFluidLeakVoxels());
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Controlled heightmap, cave-flood, ore, and structure perturbations produce exact deltas")
    void controlledPerturbationsProduceExactDeltas(@TempDir Path tmpDir) throws IOException {
        LinkedHashMap<String, Object> refRoot = buildSyntheticOverworldChunk(
                0,
                false,
                true,
                "minecraft:oak_log",
                "minecraft:cave_air",
                "minecraft:birch_forest",
                true,
                "minecraft:full");
        LinkedHashMap<String, Object> candRoot = buildSyntheticOverworldChunk(
                4,
                true,
                false,
                "minecraft:oak_log",
                "minecraft:cave_air",
                "minecraft:birch_forest",
                false,
                "minecraft:full");

        Path refDir = tmpDir.resolve("ref");
        Path candDir = tmpDir.resolve("cand");
        Files.createDirectories(refDir);
        Files.createDirectories(candDir);
        Files.write(refDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(refRoot));
        Files.write(candDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(candRoot));

        RegionSimilarityAuditor.RegionSimilarityScorecard sc =
                RegionSimilarityAuditor.compareDirectories(refDir, candDir, "ref", "cand", 0);

        assertEquals(1, sc.comparedFullChunks());
        RegionSimilarityAuditor.HeightmapStats surf = sc.heightmaps().get("WORLD_SURFACE_WG");
        assertEquals(1.0, surf.pearsonR(), 1e-6);
        assertEquals(4.0, surf.maeBlocks(), 1e-6);
        assertEquals(4.0, surf.rmseBlocks(), 1e-6);
        assertEquals(4, surf.maxAbsDeltaBlocks());

        RegionSimilarityAuditor.CaveFluidStats cf = sc.caveFluidStats();
        assertEquals(16L, cf.refUpperCaveAirVoxels());
        assertEquals(0L, cf.candUpperCaveAirVoxels());
        assertEquals(0L, cf.refUpperSubsurfaceWater());
        assertEquals(16L, cf.candUpperSubsurfaceWater());

        assertEquals(4L, sc.refOreCounts().get("diamond"));
        assertEquals(0L, sc.candOreCounts().get("diamond"));
        assertEquals(4L, sc.refOreYBandCounts().get("diamond").get("[-64,-33]"));
        assertEquals(0L, sc.candOreYBandCounts().get("diamond").get("[-64,-33]"));

        assertEquals(1, sc.refChunksWithStructureStarts());
        assertEquals(0, sc.candChunksWithStructureStarts());
        assertEquals(1, sc.refChunksWithStructureReferences());
        assertEquals(0, sc.candChunksWithStructureReferences());
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Separates strict ID shifts from 6-class semantic and 8-climate biome parity")
    void separatesStrictIdFromSemanticAndClimateParity(@TempDir Path tmpDir) throws IOException {
        LinkedHashMap<String, Object> refRoot = buildSyntheticOverworldChunk(
                0,
                false,
                true,
                "minecraft:oak_log",
                "minecraft:cave_air",
                "minecraft:birch_forest",
                false,
                "minecraft:full");
        LinkedHashMap<String, Object> candRoot = buildSyntheticOverworldChunk(
                0,
                false,
                true,
                "minecraft:spruce_log",
                "minecraft:air",
                "minecraft:forest",
                false,
                "minecraft:full");

        Path refFile = tmpDir.resolve("ref/r.1.-2.mca");
        Path candFile = tmpDir.resolve("cand/r.1.-2.lca");
        Files.createDirectories(refFile.getParent());
        Files.createDirectories(candFile.getParent());
        byte[] refMca = AnvilTestFixtures.writeSingleChunkRegion(refRoot);
        byte[] candMca = AnvilTestFixtures.writeSingleChunkRegion(candRoot);
        Files.write(refFile, refMca);
        Files.write(candFile, LcaRegionWriter.transcodeFromMca(candMca, 1, -2, false));

        RegionSimilarityAuditor.RegionSimilarityScorecard sc =
                RegionSimilarityAuditor.compareDirectories(
                        refFile.getParent(), candFile.getParent(), "vanilla", "leafgen", 0);

        assertTrue(sc.strictBlockIdMatchPct() < 100.0);
        assertEquals(100.0, sc.semanticCategoryMatchPct(), 1e-6);
        assertEquals(0.0, sc.exactBiomeQuartetMatchPct(), 1e-6);
        assertEquals(100.0, sc.climateClassQuartetMatchPct(), 1e-6);
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Excludes non-full proto-chunks and emits JSON and Markdown CLI artifacts")
    void protoChunkExclusionAndCliOutputs(@TempDir Path tmpDir) throws IOException {
        LinkedHashMap<String, Object> fullRoot = buildSyntheticOverworldChunk(
                0,
                false,
                true,
                "minecraft:oak_log",
                "minecraft:air",
                "minecraft:plains",
                false,
                "minecraft:full");
        LinkedHashMap<String, Object> protoRoot = buildSyntheticOverworldChunk(
                0,
                false,
                true,
                "minecraft:oak_log",
                "minecraft:air",
                "minecraft:plains",
                false,
                "minecraft:structure_starts");

        Path refDir = tmpDir.resolve("ref_proto");
        Path candDir = tmpDir.resolve("cand_proto");
        Files.createDirectories(refDir);
        Files.createDirectories(candDir);
        Files.write(refDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(fullRoot));
        Files.write(candDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(protoRoot));

        RegionSimilarityAuditor.RegionSimilarityScorecard protoSc =
                RegionSimilarityAuditor.compareDirectories(refDir, candDir, "ref", "cand", 0);
        assertEquals(0, protoSc.comparedFullChunks());
        assertEquals(1, protoSc.refOnlyChunks());
        assertEquals(0, protoSc.candOnlyChunks());

        Path jsonOut = tmpDir.resolve("reports/scorecard.json");
        Path mdOut = tmpDir.resolve("reports/scorecard.md");
        RegionSimilarityAuditor.main(new String[] {
            refDir.toString(),
            refDir.toString(),
            "--ref-label",
            "vanilla",
            "--cand-label",
            "self",
            "--format",
            "markdown",
            "--json-out",
            jsonOut.toString(),
            "--md-out",
            mdOut.toString()
        });

        String jsonText = Files.readString(jsonOut, StandardCharsets.UTF_8);
        String mdText = Files.readString(mdOut, StandardCharsets.UTF_8);
        assertTrue(jsonText.contains("\"compared_full_chunks\": 1"));
        assertTrue(jsonText.contains("\"strict_block_id_match_pct\": 100.000000"));
        assertTrue(mdText.contains("### Region Similarity Scorecard: `vanilla` vs `self`"));
    }

    @Test
    @DisplayName("REQ-RTP-S-005: SemanticMaterial and ClimateBiomeClass classifiers cover all Overworld buckets")
    void classifierTaxonomiesCoverAllMaterialBiomeAndOreClasses() {
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.AIR,
                RegionSimilarityAuditor.classifySemanticMaterial(null));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.AIR,
                RegionSimilarityAuditor.classifySemanticMaterial(""));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.AIR,
                RegionSimilarityAuditor.classifySemanticMaterial("   "));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.AIR,
                RegionSimilarityAuditor.classifySemanticMaterial("air"));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.FLUID,
                RegionSimilarityAuditor.classifySemanticMaterial("minecraft:water[level=0]"));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.FLUID,
                RegionSimilarityAuditor.classifySemanticMaterial("lava"));

        for (String ore : List.of(
                "minecraft:coal_ore",
                "minecraft:deepslate_iron_ore",
                "minecraft:raw_copper_block",
                "minecraft:gold_ore",
                "minecraft:redstone_ore",
                "minecraft:lapis_ore",
                "minecraft:diamond_ore",
                "minecraft:emerald_ore",
                "minecraft:nether_quartz_ore",
                "minecraft:ancient_debris")) {
            assertEquals(
                    RegionSimilarityAuditor.SemanticMaterial.MINERAL_ORE,
                    RegionSimilarityAuditor.classifySemanticMaterial(ore),
                    ore);
        }

        for (String decor : List.of(
                "minecraft:short_grass",
                "minecraft:cactus",
                "minecraft:oak_leaves",
                "minecraft:wart_block",
                "minecraft:nether_wart_block",
                "minecraft:warped_wart_block",
                "minecraft:oak_wood",
                "minecraft:crimson_stem",
                "minecraft:warped_hyphae",
                "minecraft:oak_sapling",
                "minecraft:mangrove_propagule",
                "minecraft:red_tulip",
                "minecraft:oak_planks",
                "minecraft:cobblestone",
                "minecraft:chest")) {
            assertEquals(
                    RegionSimilarityAuditor.SemanticMaterial.WOOD_FOLIAGE_DECOR,
                    RegionSimilarityAuditor.classifySemanticMaterial(decor),
                    decor);
        }

        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.SURFACE_STRATA,
                RegionSimilarityAuditor.classifySemanticMaterial("minecraft:orange_terracotta"));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.SURFACE_STRATA,
                RegionSimilarityAuditor.classifySemanticMaterial("minecraft:grass_block"));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.LITHIC_BULK,
                RegionSimilarityAuditor.classifySemanticMaterial("minecraft:raw_cod"));
        assertEquals(
                RegionSimilarityAuditor.SemanticMaterial.LITHIC_BULK,
                RegionSimilarityAuditor.classifySemanticMaterial("minecraft:stone"));

        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.PLAINS,
                RegionSimilarityAuditor.classifyClimateBiome(null));
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.PLAINS,
                RegionSimilarityAuditor.classifyClimateBiome("   "));
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.PLAINS,
                RegionSimilarityAuditor.classifyClimateBiome("plains"));
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.CAVE,
                RegionSimilarityAuditor.classifyClimateBiome("minecraft:lush_caves"));
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.CAVE,
                RegionSimilarityAuditor.classifyClimateBiome("minecraft:deep_dark"));
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.SNOWY,
                RegionSimilarityAuditor.classifyClimateBiome("minecraft:snowy_beach"));
        for (String snowy : List.of(
                "minecraft:snowy_plains",
                "minecraft:ice_spikes",
                "minecraft:frozen_peaks",
                "minecraft:jagged_peaks",
                "minecraft:grove")) {
            assertEquals(
                    RegionSimilarityAuditor.ClimateBiomeClass.SNOWY,
                    RegionSimilarityAuditor.classifyClimateBiome(snowy),
                    snowy);
        }
        for (String aquatic : List.of(
                "minecraft:deep_ocean",
                "minecraft:river",
                "minecraft:beach",
                "minecraft:stony_shore",
                "minecraft:mushroom_fields")) {
            assertEquals(
                    RegionSimilarityAuditor.ClimateBiomeClass.OCEAN_RIVER,
                    RegionSimilarityAuditor.classifyClimateBiome(aquatic),
                    aquatic);
        }
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.BADLANDS,
                RegionSimilarityAuditor.classifyClimateBiome("minecraft:eroded_badlands"));
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.DESERT,
                RegionSimilarityAuditor.classifyClimateBiome("minecraft:desert"));
        assertEquals(
                RegionSimilarityAuditor.ClimateBiomeClass.TAIGA,
                RegionSimilarityAuditor.classifyClimateBiome("minecraft:old_growth_pine_taiga"));
        for (String forest : List.of(
                "minecraft:forest",
                "minecraft:jungle",
                "minecraft:swamp",
                "minecraft:cherry_grove",
                "minecraft:pale_garden")) {
            assertEquals(
                    RegionSimilarityAuditor.ClimateBiomeClass.FOREST,
                    RegionSimilarityAuditor.classifyClimateBiome(forest),
                    forest);
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Handles packed Heightmaps, multi-biome quartets, maxChunks, and corrupt entries")
    void handlesPackedHeightmapsMultiBiomeQuartetsMaxChunksAndCorruptRegion(@TempDir Path tmpDir)
            throws IOException {
        long[] packedBiomes = new long[2];
        for (int i = 0; i < 64; i++) {
            packedBiomes[i >>> 5] |= ((long) (i & 3)) << ((i & 31) << 1);
        }

        // Multi-ore section spanning Y = 160..175 (sectionY = 10) with leaves & short_grass
        List<String> highPal = List.of(
                "minecraft:air",
                "minecraft:iron_ore",
                "minecraft:copper_ore",
                "minecraft:gold_ore",
                "minecraft:redstone_ore",
                "minecraft:lapis_ore",
                "minecraft:emerald_ore",
                "minecraft:short_grass");
        int[] highIdx = new int[4096];
        for (int i = 1; i <= 7; i++) {
            highIdx[i] = i;
        }
        LinkedHashMap<String, Object> secHigh = AnvilTestFixtures.sectionWithBiomes(
                (byte) 10,
                highPal,
                AnvilTestFixtures.packIndices(4, highIdx),
                List.of("minecraft:plains", "minecraft:desert", "minecraft:taiga", "minecraft:ocean"),
                packedBiomes);

        // Deep air cave at Y = -58 under stone ceiling
        int[] deepAirIdx = new int[4096];
        deepAirIdx[(6 << 8)] = 1;
        LinkedHashMap<String, Object> secDeepAir = AnvilTestFixtures.sectionWithBiomes(
                (byte) -4,
                List.of("minecraft:deepslate", "minecraft:cave_air"),
                AnvilTestFixtures.packIndices(4, deepAirIdx),
                List.of("minecraft:dripstone_caves"),
                null);

        // Pack non-zero 9-bit heightmap values spanning all 8 elevation histogram bands
        int[] targetHeights = {-50, -10, 20, 55, 75, 110, 150, 220};
        long[] packedHm = new long[37];
        for (int col = 0; col < 256; col++) {
            int rawVal = (targetHeights[col & 7] - (-64)) + 1;
            int wIdx = col / 7;
            int slot = col - wIdx * 7;
            packedHm[wIdx] |= ((long) rawVal & 0x1FFL) << (slot * 9);
        }

        LinkedHashMap<String, Object> rootA =
                AnvilTestFixtures.chunkRoot(3953, packedHm, List.of(secDeepAir, secHigh));
        rootA.remove("yPos"); // exercise view-derived chunkFloorY fallback
        rootA.put("Status", "minecraft:full");
        LinkedHashMap<String, Object> hmMap = new LinkedHashMap<>();
        hmMap.put("WORLD_SURFACE", packedHm);
        hmMap.put("OCEAN_FLOOR", packedHm);
        hmMap.put("MOTION_BLOCKING_NO_LEAVES", packedHm);
        rootA.put("Heightmaps", hmMap);

        // Legacy Level.Structures.Starts / references layout
        LinkedHashMap<String, Object> legacyStart = new LinkedHashMap<>();
        legacyStart.put("id", "minecraft:mineshaft");
        LinkedHashMap<String, Object> legacyStarts = new LinkedHashMap<>();
        legacyStarts.put("mineshaft", legacyStart);
        legacyStarts.put("empty_entry", new LinkedHashMap<>());
        LinkedHashMap<String, Object> legacyRefs = new LinkedHashMap<>();
        legacyRefs.put("mineshaft", new long[] {0L});
        LinkedHashMap<String, Object> legacyStructures = new LinkedHashMap<>();
        legacyStructures.put("Starts", legacyStarts);
        legacyStructures.put("references", legacyRefs);
        LinkedHashMap<String, Object> levelCompound = new LinkedHashMap<>();
        levelCompound.put("Structures", legacyStructures);
        rootA.put("Level", levelCompound);

        byte[] singleChunkMca = AnvilTestFixtures.writeSingleChunkRegion(rootA);
        Path singleRefFile = tmpDir.resolve("direct/r.0.0.mca");
        Path secondRefFile = tmpDir.resolve("direct/r.1.0.mca");
        Files.createDirectories(singleRefFile.getParent());
        Files.write(singleRefFile, singleChunkMca);
        Files.write(secondRefFile, singleChunkMca);

        // Direct regular-file comparison with null labels and maxChunks=1
        RegionSimilarityAuditor.RegionSimilarityScorecard capped =
                RegionSimilarityAuditor.compareDirectories(
                        singleRefFile.getParent(), singleRefFile.getParent(), null, null, 1);
        assertEquals(1, capped.comparedFullChunks());
        assertEquals("reference", capped.referenceLabel());
        assertEquals("candidate", capped.candidateLabel());
        assertEquals(1L, capped.refOreCounts().get("iron"));
        assertEquals(1L, capped.refOreCounts().get("emerald"));
        assertEquals(1L, capped.caveFluidStats().refDeepCaveAirVoxels());
        assertEquals(1, capped.refChunksWithStructureStarts());
        assertEquals(1, capped.refChunksWithStructureReferences());

        // Single regular-file path resolution
        RegionSimilarityAuditor.RegionSimilarityScorecard fileSc =
                RegionSimilarityAuditor.compareDirectories(singleRefFile, singleRefFile, "a", "b", 0);
        assertEquals(1, fileSc.comparedFullChunks());

        // Missing reference directory exercises empty scorecard & candOnlyChunks counter
        RegionSimilarityAuditor.RegionSimilarityScorecard missingRef =
                RegionSimilarityAuditor.compareDirectories(
                        tmpDir.resolve("does_not_exist"), singleRefFile.getParent(), "ref", "cand", 0);
        assertEquals(0, missingRef.comparedFullChunks());
        assertEquals(2, missingRef.candOnlyChunks());
        assertEquals(0L, missingRef.heightmaps().get("WORLD_SURFACE_WG").sampleColumns());

        // CLI JSON output + --max-chunks flag
        RegionSimilarityAuditor.main(new String[] {
            singleRefFile.toString(),
            singleRefFile.toString(),
            "--max-chunks",
            "1",
            "--format",
            "json",
            "--unknown-flag",
            "ignored"
        });

        // Corrupt chunk payload inside region archive must surface failure per S-004
        byte[] corruptMca = singleChunkMca.clone();
        int sectorByteOffset =
                (((corruptMca[0] & 0xFF) << 16) | ((corruptMca[1] & 0xFF) << 8) | (corruptMca[2] & 0xFF))
                        * 4096;
        corruptMca[sectorByteOffset + 4] = 99; // unknown compression scheme -> UnsupportedAnvilFormatException
        Path corruptDir = tmpDir.resolve("corrupt");
        Files.createDirectories(corruptDir);
        Files.write(corruptDir.resolve("r.0.0.mca"), corruptMca);
        assertThrows(
                UnsupportedAnvilFormatException.class,
                () -> RegionSimilarityAuditor.compareDirectories(
                        singleRefFile.getParent(), corruptDir, "ref", "corrupt", 0));

        // Corrupt zlib stream header surfaces IOException (ZipException) per S-004
        byte[] corruptZlibMca = singleChunkMca.clone();
        corruptZlibMca[sectorByteOffset + 5] = (byte) 0xFF;
        corruptZlibMca[sectorByteOffset + 6] = (byte) 0xFF;
        Path corruptZlibDir = tmpDir.resolve("corrupt_zlib");
        Files.createDirectories(corruptZlibDir);
        Files.write(corruptZlibDir.resolve("r.0.0.mca"), corruptZlibMca);
        assertThrows(
                IOException.class,
                () -> RegionSimilarityAuditor.compareDirectories(
                        singleRefFile.getParent(), corruptZlibDir, "ref", "corrupt_zlib", 0));
    }

    private static LinkedHashMap<String, Object> buildSyntheticOverworldChunk(
            int surfaceShiftY,
            boolean floodUpperCaveWithWater,
            boolean includeDiamondOre,
            String logBlockId,
            String caveAirBlockId,
            String biomeId,
            boolean includeStructures,
            String status) throws IOException {
        // Section Y = -4 (worldY -64..-49): deepslate bulk + 8 deep lava voxels + 4 diamond ores
        List<String> deepPal = List.of(
                "minecraft:deepslate",
                "minecraft:lava",
                "minecraft:deepslate_diamond_ore",
                caveAirBlockId);
        int[] deepIdx = new int[4096];
        Arrays.fill(deepIdx, 0);
        // Y = -58 -> ly = 6
        for (int lx = 0; lx < 8; lx++) {
            deepIdx[(6 << 8) | lx] = 1; // lava
        }
        if (includeDiamondOre) {
            for (int lx = 8; lx < 12; lx++) {
                deepIdx[(6 << 8) | lx] = 2; // diamond ore
            }
        }
        LinkedHashMap<String, Object> secDeep = AnvilTestFixtures.sectionWithBiomes(
                (byte) -4,
                deepPal,
                AnvilTestFixtures.packIndices(4, deepIdx),
                List.of(biomeId),
                null);

        // Section Y = 1 (worldY 16..31): stone bulk + 16 upper cave voxels at Y = 20 (ly = 4) + 6 coal ores
        List<String> midPal = List.of(
                "minecraft:stone",
                floodUpperCaveWithWater ? "minecraft:water" : caveAirBlockId,
                "minecraft:coal_ore");
        int[] midIdx = new int[4096];
        Arrays.fill(midIdx, 0);
        for (int lx = 0; lx < 16; lx++) {
            midIdx[(4 << 8) | lx] = 1; // cave air or flooded water
        }
        for (int lx = 0; lx < 6; lx++) {
            midIdx[(8 << 8) | (1 << 4) | lx] = 2; // coal ore at Y = 24
        }
        LinkedHashMap<String, Object> secMid = AnvilTestFixtures.sectionWithBiomes(
                (byte) 1,
                midPal,
                AnvilTestFixtures.packIndices(4, midIdx),
                List.of(biomeId),
                null);

        // Section Y = 4 (worldY 64..79): sloped grass surface (ly = (lx % 4) + surfaceShiftY) + tree log at col (0,0)
        List<String> surfPal = List.of(
                "minecraft:air",
                "minecraft:dirt",
                "minecraft:grass_block",
                logBlockId,
                "minecraft:oak_leaves");
        int[] surfIdx = new int[4096];
        Arrays.fill(surfIdx, 0);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int topLy = (lx & 3) + surfaceShiftY; // ly in 0..7 -> worldY 64..71
                for (int ly = 0; ly < topLy; ly++) {
                    surfIdx[(ly << 8) | (lz << 4) | lx] = 1; // dirt
                }
                surfIdx[(topLy << 8) | (lz << 4) | lx] = 2; // grass_block
            }
        }
        int trunkBaseLy = surfaceShiftY + 1;
        surfIdx[(trunkBaseLy << 8)] = 3; // trunk log
        surfIdx[((trunkBaseLy + 1) << 8)] = 3;
        surfIdx[((trunkBaseLy + 2) << 8)] = 4; // canopy leaf

        LinkedHashMap<String, Object> secSurf = AnvilTestFixtures.sectionWithBiomes(
                (byte) 4,
                surfPal,
                AnvilTestFixtures.packIndices(4, surfIdx),
                List.of(biomeId),
                null);

        // Fill solid stone sections between Y = -3..3 so cave voxels at Y = -58 and Y = 20 sit below solid ceiling
        List<LinkedHashMap<String, Object>> sections = new ArrayList<>();
        sections.add(secDeep);
        for (int sy = -3; sy <= 3; sy++) {
            if (sy == 1) {
                sections.add(secMid);
            } else {
                sections.add(AnvilTestFixtures.sectionWithBiomes(
                        (byte) sy,
                        List.of(sy < 0 ? "minecraft:deepslate" : "minecraft:stone"),
                        null,
                        List.of(biomeId),
                        null));
            }
        }
        sections.add(secSurf);

        LinkedHashMap<String, Object> root =
                AnvilTestFixtures.chunkRoot(3953, new long[37], sections);
        root.put("yPos", -4);
        root.put("Status", status);

        if (includeStructures) {
            LinkedHashMap<String, Object> villageStart = new LinkedHashMap<>();
            villageStart.put("id", "minecraft:village_plains");
            villageStart.put("ChunkX", 0);
            villageStart.put("ChunkZ", 0);

            LinkedHashMap<String, Object> invalidStart = new LinkedHashMap<>();
            invalidStart.put("id", "INVALID");

            LinkedHashMap<String, Object> starts = new LinkedHashMap<>();
            starts.put("minecraft:village_plains", villageStart);
            starts.put("minecraft:pillager_outpost", invalidStart);

            LinkedHashMap<String, Object> refs = new LinkedHashMap<>();
            refs.put("minecraft:village_plains", new long[] {0L});

            LinkedHashMap<String, Object> structures = new LinkedHashMap<>();
            structures.put("starts", starts);
            structures.put("References", refs);
            root.put("structures", structures);
        }
        return root;
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Axis 5 migration-seam audit isolates interior drift from cross-chunk cliffs, biome seams, and subterranean fluid leaks")
    void migrationSeamAuditDistinguishesInteriorDriftFromBorderSeams(@TempDir Path tmpDir)
            throws IOException {
        // 2x2 grid of adjacent chunks: (0,0), (1,0), (0,1), (1,1) -> 4 shared intra-region edges
        Map<Integer, LinkedHashMap<String, Object>> refChunks = new LinkedHashMap<>();
        Map<Integer, LinkedHashMap<String, Object>> interiorDriftChunks = new LinkedHashMap<>();
        Map<Integer, LinkedHashMap<String, Object>> cliffSeamChunks = new LinkedHashMap<>();

        for (int cz = 0; cz < 2; cz++) {
            for (int cx = 0; cx < 2; cx++) {
                int key = (cz << 5) | cx;
                LinkedHashMap<String, Object> refRoot = buildSyntheticOverworldChunk(
                        0, false, true, "minecraft:oak_log", "minecraft:cave_air",
                        "minecraft:plains", false, "minecraft:full");
                refChunks.put(key, refRoot);

                // Candidate A: interior-only block change (stone -> granite at interior x=5, z=5, y=20);
                // all chunk borders remain 100% identical to Vanilla.
                LinkedHashMap<String, Object> interiorOnly = buildSyntheticOverworldChunk(
                        0, false, true, "minecraft:oak_log", "minecraft:cave_air",
                        "minecraft:plains", false, "minecraft:full");
                mutateSingleSectionBlock(interiorOnly, 0, (4 << 8) | (5 << 4) | 5, "minecraft:granite");
                interiorDriftChunks.put(key, interiorOnly);

                // Candidate B: on chunk (0,0) shift surface +4 blocks (creating a 4-block excess cliff
                // against Vanilla neighbors (1,0) and (0,1)), change biome to desert, and flood border cave.
                boolean perturbed = (cx == 0 && cz == 0);
                LinkedHashMap<String, Object> cliffRoot = buildSyntheticOverworldChunk(
                        perturbed ? 4 : 0,
                        perturbed,
                        true,
                        "minecraft:oak_log",
                        "minecraft:cave_air",
                        perturbed ? "minecraft:desert" : "minecraft:plains",
                        false,
                        "minecraft:full");
                cliffSeamChunks.put(key, cliffRoot);
            }
        }

        Path refDir = tmpDir.resolve("ref/region");
        Path interiorDir = tmpDir.resolve("interior_only/region");
        Path cliffDir = tmpDir.resolve("cliff_seam/region");
        Files.createDirectories(refDir);
        Files.createDirectories(interiorDir);
        Files.createDirectories(cliffDir);

        Files.write(refDir.resolve("r.0.0.mca"), writeMultiChunkRegion(refChunks));
        Files.write(interiorDir.resolve("r.0.0.mca"), writeMultiChunkRegion(interiorDriftChunks));
        Files.write(cliffDir.resolve("r.0.0.mca"), writeMultiChunkRegion(cliffSeamChunks));

        RegionSimilarityAuditor.RegionSimilarityScorecard interiorSc =
                RegionSimilarityAuditor.compareDirectories(refDir, interiorDir, "vanilla", "interior-drift", 0);
        RegionSimilarityAuditor.MigrationSeamStats interiorSeam = interiorSc.migrationSeam();
        assertEquals(4L, interiorSeam.comparedSharedChunkEdges());
        assertEquals(240L, interiorSeam.borderColumnsChecked());
        assertEquals(100.0, interiorSeam.borderSurfaceExactPct(), 1e-6);
        assertEquals(100.0, interiorSeam.borderSurfaceWithin1BlockPct(), 1e-6);
        assertEquals(0.0, interiorSeam.seamSurfaceMaeBlocks(), 1e-6);
        assertEquals(0, interiorSeam.seamMaxInducedCliffBlocks());
        assertEquals(0L, interiorSeam.seamInducedCliffEdges());
        assertEquals(100.0, interiorSeam.borderExactBiomePct(), 1e-6);
        assertEquals(100.0, interiorSeam.borderStrictBlockPct(), 1e-6);
        assertTrue(interiorSeam.interiorStrictBlockPct() < 100.0,
                "Interior mutation must lower interiorStrictBlockPct while keeping borderStrictBlockPct at 100%");
        assertEquals(100.0, interiorSeam.borderSubterraneanSolidAirAgreementPct(), 1e-6);
        assertEquals(0L, interiorSeam.borderSubterraneanFluidLeakVoxels());

        RegionSimilarityAuditor.RegionSimilarityScorecard cliffSc =
                RegionSimilarityAuditor.compareDirectories(refDir, cliffDir, "vanilla", "cliff-seam", 0);
        RegionSimilarityAuditor.MigrationSeamStats cliffSeam = cliffSc.migrationSeam();
        assertEquals(4L, cliffSeam.comparedSharedChunkEdges());
        assertEquals(75.0, cliffSeam.borderSurfaceExactPct(), 1e-6);
        assertEquals(75.0, cliffSeam.borderSurfaceWithin1BlockPct(), 1e-6);
        assertEquals(4, cliffSeam.seamMaxInducedCliffBlocks());
        // 16 E-W edges ((0,0)->(1,0)) + 15 N-S edges ((0,0)->(0,1), lx=1..15);
        // at lx=0, (0,1) has a 3-block oak tree canopy at (0,0) so dRef=3 and dC2V=1.
        assertEquals(31L, cliffSeam.seamInducedCliffEdges());
        assertEquals(75.0, cliffSeam.borderExactBiomePct(), 1e-6);
        assertTrue(cliffSeam.borderSubterraneanFluidLeakVoxels() >= 16L,
                "Flooding upper cave along z=0 border of chunk (0,0) must report >= 16 fluid-to-air seam leaks");

        String json = RegionSimilarityAuditor.toJson(cliffSc);
        assertTrue(json.contains("\"migration_seam\""));
        assertTrue(json.contains("\"seam_max_induced_cliff_blocks\": 4"));
        assertTrue(json.contains("\"border_subterranean_fluid_leak_voxels\":"));

        String md = RegionSimilarityAuditor.toMarkdown(cliffSc);
        assertTrue(md.contains("Axis 5: Migration Seam Continuity"));
        assertTrue(md.contains("fluid-to-air seam leaks"));
    }

    @Test
    @DisplayName("REQ-RTP-S-005: MC 26.4 16x16x16 per-block biome differences off 4x4x4 quartet corners are detected")
    void auditorDetectsNonCorner26_4PerBlockBiomeDiffs(@TempDir Path tmpDir) throws IOException {
        int[] refBiomes = new int[4096];
        int[] candBiomes = new int[4096];
        // Identical at all 64 4x4x4 quartet corners (including (0,0,0) = desert so both use 16x16x16 storage)
        refBiomes[BiomePaletteSection.biomeVoxelIndex(0, 0, 0)] = 1;
        candBiomes[BiomePaletteSection.biomeVoxelIndex(0, 0, 0)] = 1;
        // Differ only at non-corner voxels: interior (1,0,1) and chunk border (0,0,1)
        candBiomes[BiomePaletteSection.biomeVoxelIndex(1, 0, 1)] = 1;
        candBiomes[BiomePaletteSection.biomeVoxelIndex(0, 0, 1)] = 1;

        long[] refPacked = packBiome4096(refBiomes, 2);
        long[] candPacked = packBiome4096(candBiomes, 2);
        List<String> biomePal = List.of("minecraft:plains", "minecraft:desert");

        LinkedHashMap<String, Object> refRoot = buildMc264SingleSectionChunk(biomePal, refPacked);
        LinkedHashMap<String, Object> candRoot = buildMc264SingleSectionChunk(biomePal, candPacked);

        Path refDir = tmpDir.resolve("ref264/region");
        Path candDir = tmpDir.resolve("cand264/region");
        Files.createDirectories(refDir);
        Files.createDirectories(candDir);
        Files.write(refDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(refRoot));
        Files.write(candDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(candRoot));

        RegionSimilarityAuditor.RegionSimilarityScorecard selfSc =
                RegionSimilarityAuditor.compareDirectories(refDir, refDir, "ref", "self", 0);
        assertEquals(100.0, selfSc.exactBiomeQuartetMatchPct(), 1e-6);
        assertEquals(100.0, selfSc.climateClassQuartetMatchPct(), 1e-6);
        assertEquals(100.0, selfSc.migrationSeam().borderExactBiomePct(), 1e-6);

        RegionSimilarityAuditor.RegionSimilarityScorecard diffSc =
                RegionSimilarityAuditor.compareDirectories(refDir, candDir, "ref", "cand", 0);
        assertEquals(1, diffSc.comparedFullChunks());
        assertTrue(diffSc.exactBiomeQuartetMatchPct() < 100.0,
                "Non-corner 16x16x16 biome difference must lower exactBiomeQuartetMatchPct");
        assertTrue(diffSc.climateClassQuartetMatchPct() < 100.0,
                "Non-corner 16x16x16 biome climate difference must lower climateClassQuartetMatchPct");
        assertTrue(diffSc.migrationSeam().borderExactBiomePct() < 100.0,
                "Non-corner border 16x16x16 biome difference at (0,0,1) must lower borderExactBiomePct");
    }

    private static LinkedHashMap<String, Object> buildMc264SingleSectionChunk(
            List<String> biomePalette, long[] packedBiomeData) throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
                (byte) 0,
                List.of("minecraft:stone"),
                null,
                biomePalette,
                packedBiomeData);
        LinkedHashMap<String, Object> root =
                AnvilTestFixtures.chunkRoot(DataVersionSupport.MC_26_4_DATA_VERSION, new long[37], List.of(sec));
        root.put("yPos", 0);
        root.remove("Status");
        root.put("status", "minecraft:full");
        return root;
    }

    private static long[] packBiome4096(int[] cells4096, int paletteSize) {
        int bits = BiomePaletteSection.biomeBitsPerEntry(paletteSize);
        int perLong = 64 / bits;
        int numLongs = BiomePaletteSection.requiredLongs(paletteSize, BiomePaletteSection.CELL_COUNT_16X16X16);
        long mask = (1L << bits) - 1L;
        long[] words = new long[numLongs];
        for (int i = 0; i < BiomePaletteSection.CELL_COUNT_16X16X16; i++) {
            words[i / perLong] |= (cells4096[i] & mask) << ((i % perLong) * bits);
        }
        return words;
    }

    @SuppressWarnings("unchecked")
    private static void mutateSingleSectionBlock(
            LinkedHashMap<String, Object> root, int targetSectionY, int voxelIndex, String newBlockId) {
        Object rawSections = root.get("sections");
        List<?> sections = (rawSections instanceof Nbt.NbtList nbtList)
                ? nbtList.items
                : (List<?>) rawSections;
        for (Object item : sections) {
            LinkedHashMap<String, Object> sec = (LinkedHashMap<String, Object>) item;
            if (((Number) sec.get("Y")).intValue() == targetSectionY) {
                int[] indices = new int[4096];
                Arrays.fill(indices, 0);
                indices[voxelIndex] = 1;
                List<String> pal = List.of("minecraft:stone", newBlockId);
                LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
                List<Object> palEntries = new ArrayList<>();
                for (String name : pal) {
                    LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
                    entry.put("Name", name);
                    palEntries.add(entry);
                }
                blockStates.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palEntries));
                blockStates.put("data", AnvilTestFixtures.packIndices(4, indices));
                sec.put("block_states", blockStates);
                return;
            }
        }
        throw new IllegalStateException("Section Y=" + targetSectionY + " not found");
    }

    private static byte[] writeMultiChunkRegion(Map<Integer, LinkedHashMap<String, Object>> chunksByKey)
            throws IOException {
        byte[] header = new byte[8192];
        ByteArrayOutputStream sectors = new ByteArrayOutputStream();
        int nextSector = 2;
        for (Map.Entry<Integer, LinkedHashMap<String, Object>> e : chunksByKey.entrySet()) {
            int key = e.getKey();
            int localX = key & 31;
            int localZ = (key >>> 5) & 31;
            byte[] nbt = Nbt.writeNamedRoot("", e.getValue());
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed)) {
                dos.write(nbt);
            }
            byte[] payload = compressed.toByteArray();
            int totalBytes = 4 + 1 + payload.length;
            int sectorCount = (totalBytes + 4095) / 4096;
            byte[] chunkBuf = new byte[sectorCount * 4096];
            int lenField = payload.length + 1;
            chunkBuf[0] = (byte) ((lenField >>> 24) & 0xFF);
            chunkBuf[1] = (byte) ((lenField >>> 16) & 0xFF);
            chunkBuf[2] = (byte) ((lenField >>> 8) & 0xFF);
            chunkBuf[3] = (byte) (lenField & 0xFF);
            chunkBuf[4] = 2; // zlib
            System.arraycopy(payload, 0, chunkBuf, 5, payload.length);
            sectors.write(chunkBuf);

            int hdrOff = 4 * ((localX & 31) + ((localZ & 31) * 32));
            header[hdrOff] = (byte) ((nextSector >>> 16) & 0xFF);
            header[hdrOff + 1] = (byte) ((nextSector >>> 8) & 0xFF);
            header[hdrOff + 2] = (byte) (nextSector & 0xFF);
            header[hdrOff + 3] = (byte) (sectorCount & 0xFF);
            nextSector += sectorCount;
        }
        byte[] body = sectors.toByteArray();
        byte[] out = new byte[8192 + body.length];
        System.arraycopy(header, 0, out, 0, 8192);
        System.arraycopy(body, 0, out, 8192, body.length);
        return out;
    }
}

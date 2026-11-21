package io.github.dailystruggle.anvil;

import io.github.dailystruggle.anvil.WeightedParityEvaluator.DeviationMode;
import io.github.dailystruggle.anvil.WeightedParityEvaluator.FeatureDomain;
import io.github.dailystruggle.anvil.WeightedParityEvaluator.ParityGateVerdict;
import io.github.dailystruggle.anvil.WeightedParityEvaluator.TemplateConformityLibrary;
import io.github.dailystruggle.anvil.WeightedParityEvaluator.WeightedMatrixCell;
import io.github.dailystruggle.anvil.WeightedParityEvaluator.WeightedParityReport;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 1 verification suite for {@link WeightedParityEvaluator} and its integration with
 * {@link RegionSimilarityAuditor} (`ADR-016`, `ADR-077`, `ADR-115`, `anvil-api-ADR-002`,
 * `anvil-api-ADR-003`, `leafgen-ADR-003`, `treegen-ADR-003`, `REQ-CFG-ATLAS-001`,
 * `REQ-RTP-S-004`, `REQ-RTP-S-005`).
 */
@DisplayName("anvil-api-ADR-003 / leafgen-ADR-003: Weighted Semantic & Relative-Layout Parity Matrix")
class WeightedParityEvaluatorTest {

    @BeforeEach
    @AfterEach
    void resetRegistries() {
        RegionFormatRegistry.reset();
        LcaDecoratorStamp.clearRegistry();
    }

    @Test
    @DisplayName("Missing village chest (<0.01% raw voxel diff) triggers CRITICAL_FUNCTIONAL hard tripwire and BREAKING_DEVIATION")
    void missingVillageChestTriggersHardBreakingDeviationDespiteHighRawVoxelMatch(@TempDir Path tmpDir)
            throws IOException {
        Path refDir = tmpDir.resolve("ref_village");
        Path candMissingChestDir = tmpDir.resolve("cand_missing_chest");
        Path candMissingLootEntityDir = tmpDir.resolve("cand_missing_loot_nbt");
        Files.createDirectories(refDir);
        Files.createDirectories(candMissingChestDir);
        Files.createDirectories(candMissingLootEntityDir);

        LinkedHashMap<String, Object> refChunk = buildVillageAndForestChunk(
                0, 0, true, true, 0, 0, 0, false, false);
        LinkedHashMap<String, Object> noChestChunk = buildVillageAndForestChunk(
                0, 0, false, false, 0, 0, 0, false, false);
        LinkedHashMap<String, Object> noChestEntityChunk = buildVillageAndForestChunk(
                0, 0, true, false, 0, 0, 0, false, false);

        Files.write(refDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(0L, refChunk)));
        Files.write(candMissingChestDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(0L, noChestChunk)));
        Files.write(
                candMissingLootEntityDir.resolve("r.0.0.mca"),
                writeMultiChunkRegion(Map.of(0L, noChestEntityChunk)));

        RegionSimilarityAuditor.RegionSimilarityScorecard missingBlockSc =
                RegionSimilarityAuditor.compareDirectories(
                        refDir, candMissingChestDir, "vanilla", "missing-chest", 0);

        // Raw voxel match is > 99.99% (only 2 blocks out of 98,304 differ: chest + bell),
        // yet weighted parity immediately catches the gameplay-breaking loss!
        assertTrue(missingBlockSc.strictBlockIdMatchPct() > 99.99);
        WeightedParityReport report = missingBlockSc.weightedParity();
        assertNotNull(report);
        assertEquals(ParityGateVerdict.BREAKING_DEVIATION, report.verdict());
        assertTrue(report.criticalFunctionalLossCount() >= 1L);
        assertFalse(report.breakingTripwires().isEmpty());
        assertTrue(
                report.breakingTripwires().stream().anyMatch(msg -> msg.contains("chest")),
                () -> "Expected chest tripwire in " + report.breakingTripwires());

        WeightedMatrixCell critMissingCell =
                report.cell(FeatureDomain.CRITICAL_FUNCTIONAL, DeviationMode.MISSING_OR_BROKEN);
        assertNotNull(critMissingCell);
        assertTrue(critMissingCell.voxelOrInstanceCount() >= 1L);
        assertTrue(critMissingCell.weightedPenaltyPoints() > 0.0);

        // Even if the minecraft:chest block voxel is placed, dropping its block_entities container NBT
        // (so villagers/players find an uninitialized or missing loot chest) must also trip BREAKING_DEVIATION!
        RegionSimilarityAuditor.RegionSimilarityScorecard missingEntitySc =
                RegionSimilarityAuditor.compareDirectories(
                        refDir, candMissingLootEntityDir, "vanilla", "missing-chest-nbt", 0);
        assertEquals(100.0, missingEntitySc.strictBlockIdMatchPct(), 1e-6);
        assertEquals(ParityGateVerdict.BREAKING_DEVIATION, missingEntitySc.weightedParity().verdict());
        assertTrue(
                missingEntitySc.weightedParity().breakingTripwires().stream()
                        .anyMatch(msg -> msg.toLowerCase().contains("block_entit") || msg.contains("chest")),
                () -> "Expected block_entity tripwire in "
                        + missingEntitySc.weightedParity().breakingTripwires());
    }

    @Test
    @DisplayName("Village dirt_path sheared by 1 block or cliff-stepped (|dY| > 1) triggers STRUCTURAL_PATH_CONNECTIVITY BREAKING_DEVIATION")
    void villagePathOffByOneOrDisconnectedStepTriggersLayoutDiscontinuity(@TempDir Path tmpDir)
            throws IOException {
        Path refDir = tmpDir.resolve("ref_paths");
        Path candShearedDir = tmpDir.resolve("cand_sheared_path");
        Files.createDirectories(refDir);
        Files.createDirectories(candShearedDir);

        // Two adjacent chunks (0,0) and (1,0) with a continuous east-west village dirt_path street
        // meeting a house doorway. In candidate chunk (1,0), the path is shifted north by 1 block (dz = +1)
        // and stepped up by +3 Y at the seam — breaking street continuity & doorway alignment (< 0.05% voxel delta).
        LinkedHashMap<String, Object> refC0 = buildVillageAndForestChunk(
                0, 0, true, true, 0, 0, 0, false, false);
        LinkedHashMap<String, Object> refC1 = buildVillagePathContinuationChunk(1, 0, 8, 65);

        LinkedHashMap<String, Object> candC0 = buildVillageAndForestChunk(
                0, 0, true, true, 0, 0, 0, false, false);
        LinkedHashMap<String, Object> candC1 = buildVillagePathContinuationChunk(1, 0, 9, 68);

        Files.write(refDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(
                packLocalChunk(0, 0), refC0,
                packLocalChunk(1, 0), refC1)));
        Files.write(candShearedDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(
                packLocalChunk(0, 0), candC0,
                packLocalChunk(1, 0), candC1)));

        RegionSimilarityAuditor.RegionSimilarityScorecard sc =
                RegionSimilarityAuditor.compareDirectories(
                        refDir, candShearedDir, "vanilla", "path-off-by-1", 0);

        assertTrue(sc.strictBlockIdMatchPct() > 99.90, "Raw voxel diff is tiny (<0.1%)");
        WeightedParityReport wp = sc.weightedParity();
        assertEquals(ParityGateVerdict.BREAKING_DEVIATION, wp.verdict());
        assertTrue(wp.pathDiscontinuityCount() > 0L, "1-block path shear + 3-block cliff step must be detected");
        assertTrue(
                wp.breakingTripwires().stream().anyMatch(t -> t.toLowerCase().contains("path")),
                () -> "Expected path connectivity tripwire in " + wp.breakingTripwires());
    }

    @Test
    @DisplayName("Tree shifted horizontally across chunk border matching .nbt template (and rotated 90 deg) + re-blobbed ores pass as ACCEPTABLE_EQUIVALENT with higher weighted score than broken chest")
    void shiftedTreeMatchingNbtTemplateAndReblobbedOresScoreAcceptableEquivalent(@TempDir Path tmpDir)
            throws IOException {
        // 1. Build a synthetic Paperclip outer server JAR containing a nested META-INF/versions/26.2/paper-26.2.jar
        // with a gzipped Vanilla .nbt tree/feature template under data/minecraft/structures/oak_canopy.nbt
        Path paperclipJar = buildPaperclipWithOakTemplateNbt(tmpDir.resolve("paperclip-templates.jar"));
        TemplateConformityLibrary library = TemplateConformityLibrary.fromSources(List.of(paperclipJar));
        assertTrue(library.loadedTemplateCount() >= 1, "Should load .nbt template from nested Paperclip JAR");

        Path refDir = tmpDir.resolve("ref_forest");
        Path candAcceptableDir = tmpDir.resolve("cand_shifted_tree_and_ores");
        Path candMalformedTreeDir = tmpDir.resolve("cand_malformed_floating_tree");
        Path candMissingChestDir = tmpDir.resolve("cand_missing_chest_compare");
        Files.createDirectories(refDir);
        Files.createDirectories(candAcceptableDir);
        Files.createDirectories(candMalformedTreeDir);
        Files.createDirectories(candMissingChestDir);

        // Reference: Village house with chest + path in (0,0), oak tree matching oak_canopy.nbt at (12, 65, 6),
        // and iron/diamond ore blobs in stone/deepslate.
        LinkedHashMap<String, Object> refC0 = buildVillageAndForestChunk(
                0, 0, true, true, 0, 0, 0, false, false);
        LinkedHashMap<String, Object> refC1 = buildVillagePathContinuationChunk(1, 0, 8, 65);

        // Acceptable candidate:
        // - Village house, chest (+ block_entity), and path are intact.
        // - Oak tree is shifted +3 blocks East (crossing chunk border x=15 -> chunk (1,0) lx=0!) AND rotated 90 deg CW,
        //   still 100% matching the 8-symmetry variant of oak_canopy.nbt!
        // - Ore blobs are shifted to different coordinates within the same Y-band with conserved totals (twice as many
        //   raw voxel mismatches as deleting the chest!).
        Map<Long, LinkedHashMap<String, Object>> acceptableChunks =
                buildCrossChunkShiftedTreeAndReblobbedOrePair(true);
        // Malformed candidate: floating truncated leaves/logs that do NOT match any .nbt or stamp template
        Map<Long, LinkedHashMap<String, Object>> malformedTreeChunks =
                buildCrossChunkShiftedTreeAndReblobbedOrePair(false);
        // Broken chest candidate (for direct score comparison)
        LinkedHashMap<String, Object> brokenChestC0 = buildVillageAndForestChunk(
                0, 0, false, false, 0, 0, 0, false, false);

        Files.write(refDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(
                packLocalChunk(0, 0), refC0,
                packLocalChunk(1, 0), refC1)));
        Files.write(candAcceptableDir.resolve("r.0.0.mca"), writeMultiChunkRegion(acceptableChunks));
        Files.write(candMalformedTreeDir.resolve("r.0.0.mca"), writeMultiChunkRegion(malformedTreeChunks));
        Files.write(candMissingChestDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(
                packLocalChunk(0, 0), brokenChestC0,
                packLocalChunk(1, 0), refC1)));

        RegionSimilarityAuditor.RegionSimilarityScorecard acceptableSc =
                RegionSimilarityAuditor.compareDirectories(
                        refDir, candAcceptableDir, "vanilla", "shifted-tree-ores", 0, List.of(paperclipJar));
        RegionSimilarityAuditor.RegionSimilarityScorecard malformedSc =
                RegionSimilarityAuditor.compareDirectories(
                        refDir, candMalformedTreeDir, "vanilla", "malformed-tree", 0, List.of(paperclipJar));
        RegionSimilarityAuditor.RegionSimilarityScorecard brokenChestSc =
                RegionSimilarityAuditor.compareDirectories(
                        refDir, candMissingChestDir, "vanilla", "missing-chest", 0, List.of(paperclipJar));

        // Raw strict block match has MORE mismatches in acceptableSc (shifted tree + re-blobbed ores)
        // than in brokenChestSc (2 missing functional blocks):
        assertTrue(
                acceptableSc.strictBlockIdMatchPct() < brokenChestSc.strictBlockIdMatchPct(),
                "Shifted tree + re-blobbed ores changes far more raw voxels than 1 missing chest");

        // Yet WeightedParityEvaluator classifies acceptableSc as ACCEPTABLE_EQUIVALENT with 0 breaking tripwires,
        // 100% template-matched candidate trees, and a HIGHER weightedParityScore than brokenChestSc and malformedSc!
        WeightedParityReport accWp = acceptableSc.weightedParity();
        assertEquals(ParityGateVerdict.ACCEPTABLE_EQUIVALENT, accWp.verdict(),
                () -> "Unexpected tripwires: " + accWp.breakingTripwires());
        assertTrue(accWp.breakingTripwires().isEmpty());
        assertTrue(accWp.templateMatchedFeatureCount() >= 1, "Shifted cross-chunk rotated tree must match .nbt variant");
        assertEquals(0, accWp.malformedFeatureCount());
        assertTrue(
                accWp.weightedParityScore() > 97.0,
                () -> "Template-valid shifted tree + conserved ores should score > 97, got "
                        + accWp.weightedParityScore());
        assertTrue(
                accWp.weightedParityScore() > brokenChestSc.weightedParity().weightedParityScore(),
                "Acceptable shifted tree/ores must outscore missing village chest");
        assertTrue(
                accWp.weightedParityScore() > malformedSc.weightedParity().weightedParityScore(),
                "Template-conformant shifted tree must outscore malformed floating canopy");
        assertTrue(malformedSc.weightedParity().malformedFeatureCount() >= 1);

        // Verify JSON and Markdown serialization include the Axis 4 Weighted Parity Matrix
        String json = RegionSimilarityAuditor.toJson(acceptableSc);
        String md = RegionSimilarityAuditor.toMarkdown(acceptableSc);
        assertTrue(json.contains("\"weighted_parity\": {"));
        assertTrue(json.contains("\"verdict\": \"ACCEPTABLE_EQUIVALENT\""));
        assertTrue(md.contains("#### Axis 4: Weighted Semantic & Relative-Layout Parity Matrix"));
    }

    @Test
    @DisplayName("Identical regions yield EXACT_MATCH with 100.0 weightedParityScore, and real paper-26.2.jar .nbt assets load cleanly when present")
    void exactRegionsYieldHundredScoreAndRealPaperJarStructuresLoad(@TempDir Path tmpDir) throws IOException {
        Path refDir = tmpDir.resolve("exact_ref");
        Files.createDirectories(refDir);
        LinkedHashMap<String, Object> c0 = buildVillageAndForestChunk(
                0, 0, true, true, 0, 0, 0, false, false);
        Files.write(refDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(0L, c0)));

        RegionSimilarityAuditor.RegionSimilarityScorecard exactSc =
                RegionSimilarityAuditor.compareDirectories(refDir, refDir, "vanilla", "exact", 0);
        assertEquals(ParityGateVerdict.EXACT_MATCH, exactSc.weightedParity().verdict());
        assertEquals(100.0, exactSc.weightedParity().weightedParityScore(), 1e-6);
        assertTrue(exactSc.weightedParity().breakingTripwires().isEmpty());

        Path cachedPaperJar = Path.of("/home/leaf26/minecraft/26.2-lca-ab/server-cache/paper-26.2.jar");
        if (Files.isRegularFile(cachedPaperJar)) {
            TemplateConformityLibrary realLib = TemplateConformityLibrary.fromSources(List.of(cachedPaperJar));
            assertTrue(
                    realLib.loadedTemplateCount() > 50,
                    () -> "Expected Vanilla structure .nbt templates from paper-26.2.jar, got "
                            + realLib.loadedTemplateCount());
        }
    }

    @Test
    @DisplayName("Multi-palette Vanilla .nbt templates (palettes without singular palette) and LcaDecoratorStamp registry stamps match shifted trees")
    void multiPaletteNbtAndLcaDecoratorStampRegistryMatchShiftedTrees(@TempDir Path tmpDir) throws IOException {
        // 1. Verify StructureTemplateNbtLoader decodes multi-palette (shipwreck-style `palettes` list) .nbt cleanly
        LinkedHashMap<String, Object> multiPalRoot = new LinkedHashMap<>();
        multiPalRoot.put("DataVersion", 3953);
        multiPalRoot.put("size", new Nbt.NbtList(Nbt.TAG_INT, List.of(2, 2, 2)));
        LinkedHashMap<String, Object> p0 = new LinkedHashMap<>();
        p0.put("Name", "minecraft:oak_planks");
        LinkedHashMap<String, Object> p1 = new LinkedHashMap<>();
        p1.put("Name", "minecraft:chest");
        Nbt.NbtList paletteVariant0 = new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(p0, p1));
        multiPalRoot.put("palettes", new Nbt.NbtList(Nbt.TAG_LIST, List.of(paletteVariant0)));

        LinkedHashMap<String, Object> b0 = new LinkedHashMap<>();
        b0.put("pos", new Nbt.NbtList(Nbt.TAG_INT, List.of(0, 0, 0)));
        b0.put("state", 0);
        LinkedHashMap<String, Object> b1 = new LinkedHashMap<>();
        b1.put("pos", new Nbt.NbtList(Nbt.TAG_INT, List.of(1, 0, 0)));
        b1.put("state", 1);
        multiPalRoot.put("blocks", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(b0, b1)));

        Path nbtDir = tmpDir.resolve("nbt_templates");
        Files.createDirectories(nbtDir);
        Path multiPalFile = nbtDir.resolve("shipwreck_multi_palette.nbt");
        Files.write(multiPalFile, Nbt.writeNamedRoot("", multiPalRoot));

        StructureTemplateNbtLoader.TemplateVariantSet set = StructureTemplateNbtLoader.loadFromBytes(
                "minecraft:shipwreck/multi_palette",
                Files.readAllBytes(multiPalFile),
                LcaStructureStamp.TerrainProjection.RIGID,
                List.of());
        assertEquals(8, set.variants().size());

        // 2. Register an LcaDecoratorStamp for our asymmetric oak and verify candidate shifted tree matches
        // via the in-memory stamp registry even when reference has NO trees at all
        int[][] oakRows = buildAsymmetricOakRelativeVoxels(false);
        List<LcaDecoratorStamp.RelativeVoxel> relVoxels = new ArrayList<>();
        for (int[] r : oakRows) {
            relVoxels.add(new LcaDecoratorStamp.RelativeVoxel(
                    r[0],
                    r[1],
                    r[2],
                    r[3] == 0 ? "minecraft:oak_log" : "minecraft:oak_leaves",
                    r[3] == 0 ? LcaDecoratorStamp.BlockCategory.WOOD : LcaDecoratorStamp.BlockCategory.FOLIAGE));
        }
        LcaDecoratorStamp.registerStamp(
                LcaDecoratorStamp.fromRelativeBlocks("minecraft:oak", 0, 0, relVoxels, Set.of(0)));

        // Also place a single standalone .nbt file, a sibling-versions Paperclip launcher JAR,
        // and a corrupt .nbt file (exercising S-004 warning skip without aborting valid templates)
        Path badNbtFile = nbtDir.resolve("corrupt.nbt");
        Files.write(badNbtFile, new byte[] {1, 2});

        Path launcherDir = tmpDir.resolve("paperclip_sibling");
        Path siblingVerDir = launcherDir.resolve("versions/26.2");
        Files.createDirectories(siblingVerDir);
        Path innerFlatJar = siblingVerDir.resolve("paper-26.2.jar");
        try (ZipOutputStream innerZos = new ZipOutputStream(Files.newOutputStream(innerFlatJar))) {
            innerZos.putNextEntry(new ZipEntry("data/minecraft/structures/oak_flat.nbt"));
            innerZos.write(Files.readAllBytes(multiPalFile));
            innerZos.closeEntry();
            innerZos.putNextEntry(new ZipEntry("../unsafe_traversal.nbt"));
            innerZos.write(new byte[] {0});
            innerZos.closeEntry();
        }
        Path emptyLauncherJar = launcherDir.resolve("paperclip.jar");
        try (ZipOutputStream outerZos = new ZipOutputStream(Files.newOutputStream(emptyLauncherJar))) {
            outerZos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            outerZos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            outerZos.closeEntry();
        }

        StructureTemplateNbtLoader.prewarmBuiltinTemplates();
        TemplateConformityLibrary lib =
                TemplateConformityLibrary.fromSources(List.of(nbtDir, multiPalFile, emptyLauncherJar));
        assertTrue(lib.loadedTemplateCount() >= 3);

        // 3. Exercise shifted critical block within 24-block radius, buried path, obstructed doorway,
        // timber beam on masonry, and RegionSimilarityAuditor CLI --template-nbt-source + backward-compat 27-arg ctor
        Path refEdgeDir = tmpDir.resolve("ref_edge");
        Path candEdgeDir = tmpDir.resolve("cand_edge");
        Files.createDirectories(refEdgeDir);
        Files.createDirectories(candEdgeDir);

        LinkedHashMap<String, Object> refEdgeChunk =
                buildVillageAndForestChunk(0, 0, true, true, 0, 0, 0, false, false);
        LinkedHashMap<String, Object> candEdgeChunk = buildObstructedDoorAndShiftedBellChunk();
        Files.write(refEdgeDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(0L, refEdgeChunk)));
        Files.write(candEdgeDir.resolve("r.0.0.mca"), writeMultiChunkRegion(Map.of(0L, candEdgeChunk)));

        Path cliJson = tmpDir.resolve("cli_out.json");
        Path cliMd = tmpDir.resolve("cli_out.md");
        RegionSimilarityAuditor.main(new String[] {
            refEdgeDir.toString(),
            candEdgeDir.toString(),
            "--ref-label", "edge-ref",
            "--cand-label", "edge-cand",
            "--max-chunks", "4",
            "--format", "markdown",
            "--template-nbt-source", nbtDir.toString(),
            "--json-out", cliJson.toString(),
            "--md-out", cliMd.toString()
        });
        assertTrue(Files.isRegularFile(cliJson));
        assertTrue(Files.isRegularFile(cliMd));
        String cliJsonText = Files.readString(cliJson, StandardCharsets.UTF_8);
        assertTrue(cliJsonText.contains("\"obstructed_doorway_count\": 1"));
        assertTrue(cliJsonText.contains("\"verdict\": \"BREAKING_DEVIATION\""));

        // Verify backward-compatible 27-arg RegionSimilarityScorecard constructor
        RegionSimilarityAuditor.RegionSimilarityScorecard legacySc =
                new RegionSimilarityAuditor.RegionSimilarityScorecard(
                        "a",
                        "b",
                        1,
                        0,
                        0,
                        Map.of(),
                        100.0,
                        100.0,
                        Map.of(),
                        Map.of(),
                        100.0,
                        100.0,
                        Map.of(),
                        Map.of(),
                        new RegionSimilarityAuditor.CaveFluidStats(0, 0, 0, 0, 0, 0, 0, 0),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        0L,
                        0L,
                        0,
                        0,
                        0,
                        0,
                        Map.of(),
                        Map.of());
        assertEquals(ParityGateVerdict.EXACT_MATCH, legacySc.weightedParity().verdict());
        assertEquals(FeatureDomain.CRITICAL_FUNCTIONAL.basePenaltyWeight(), 250.0, 1e-6);
        assertEquals(
                0L,
                legacySc.weightedParity()
                        .cell(FeatureDomain.CRITICAL_FUNCTIONAL, DeviationMode.LAYOUT_DISCONTINUITY)
                        .voxelOrInstanceCount());
    }

    private static LinkedHashMap<String, Object> buildObstructedDoorAndShiftedBellChunk() throws IOException {
        LinkedHashMap<String, Object> base =
                buildVillageAndForestChunk(0, 0, true, true, 0, 0, 0, false, false);
        List<Object> sections = ((Nbt.NbtList) base.get("sections")).items;
        // Replace section Y=4 with obstructed doorway (cobblestone walling both sides of oak_door),
        // buried dirt_path, timber beam resting on cobblestone, and bell shifted +2 in X
        List<String> surfPal = List.of(
                "minecraft:air", // 0
                "minecraft:dirt", // 1
                "minecraft:grass_block", // 2
                "minecraft:dirt_path", // 3
                "minecraft:cobblestone", // 4
                "minecraft:oak_planks", // 5
                "minecraft:oak_door", // 6
                "minecraft:chest", // 7
                "minecraft:bell", // 8
                "minecraft:oak_log", // 9
                "minecraft:oak_leaves", // 10
                "minecraft:red_bed"); // 11
        int[] surfIdx = new int[4096];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                surfIdx[(0 << 8) | (lz << 4) | lx] = 1;
                surfIdx[(1 << 8) | (lz << 4) | lx] = 2;
            }
        }
        // Obstructed door at (6, ly=2..3, 8) surrounded on all 4 horizontal sides by cobblestone
        surfIdx[(2 << 8) | (8 << 4) | 6] = 6;
        surfIdx[(3 << 8) | (8 << 4) | 6] = 6;
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            surfIdx[(2 << 8) | ((8 + d[1]) << 4) | (6 + d[0])] = 4;
            surfIdx[(3 << 8) | ((8 + d[1]) << 4) | (6 + d[0])] = 4;
        }
        // Buried dirt_path at (10, ly=1, 8) with cobblestone on top
        surfIdx[(1 << 8) | (8 << 4) | 10] = 3;
        surfIdx[(2 << 8) | (8 << 4) | 10] = 4;
        // Structural timber beam resting on cobblestone at (2, ly=2..3, 2) -> skipped by tree extractor
        surfIdx[(1 << 8) | (2 << 4) | 2] = 4;
        surfIdx[(2 << 8) | (2 << 4) | 2] = 9;
        surfIdx[(3 << 8) | (2 << 4) | 2] = 9;
        // Keep chest at (3, ly=2, 7), shift bell to (9, ly=2, 7), and add a bed
        surfIdx[(2 << 8) | (7 << 4) | 3] = 7;
        surfIdx[(2 << 8) | (7 << 4) | 9] = 8;
        surfIdx[(2 << 8) | (9 << 4) | 3] = 11;

        sections.set(
                sections.size() - 1,
                AnvilTestFixtures.sectionWithBiomes(
                        (byte) 4, surfPal, AnvilTestFixtures.packIndices(4, surfIdx), List.of("minecraft:plains"), null));
        return base;
    }

    // =========================================================================
    // Synthetic Multi-Chunk Village + Tree + Ore Region Builders
    // =========================================================================

    private static long packLocalChunk(int localX, int localZ) {
        return (((long) localZ) << 32) | (localX & 0xFFFFFFFFL);
    }

    /**
     * Asymmetric L-branching oak template (3x5x3) written as a real Vanilla gzipped {@code .nbt}
     * inside a nested Paperclip {@code META-INF/versions/26.2/paper-26.2.jar} archive.
     */
    private static Path buildPaperclipWithOakTemplateNbt(Path outerJarPath) throws IOException {
        List<String> palette = List.of(
                "minecraft:oak_log[axis=y]",
                "minecraft:oak_leaves[distance=1,persistent=false]");
        int[][] rows = buildAsymmetricOakRelativeVoxels(false);
        byte[] nbtBytes = StructureTemplateNbtLoader.writeStructureNbtBytes(
                3, 5, 3, palette, rows, Map.of(), true);

        ByteArrayOutputStream innerBuf = new ByteArrayOutputStream();
        try (ZipOutputStream innerZip = new ZipOutputStream(innerBuf)) {
            innerZip.putNextEntry(new ZipEntry("data/minecraft/structures/trees/oak_canopy.nbt"));
            innerZip.write(nbtBytes);
            innerZip.closeEntry();
        }

        try (ZipOutputStream outerZip = new ZipOutputStream(Files.newOutputStream(outerJarPath))) {
            outerZip.putNextEntry(new ZipEntry("META-INF/versions.list"));
            outerZip.write("sha256\t26.2\t26.2/paper-26.2.jar\n".getBytes(StandardCharsets.UTF_8));
            outerZip.closeEntry();

            outerZip.putNextEntry(new ZipEntry("META-INF/versions/26.2/paper-26.2.jar"));
            outerZip.write(innerBuf.toByteArray());
            outerZip.closeEntry();
        }
        return outerJarPath;
    }

    /**
     * Returns relative {@code {x, y, z, stateIdx}} rows for a 3x5x3 asymmetric oak tree
     * (trunk at {@code (1, 0..3, 1)} + canopy cross + asymmetric east/south leaf tip at {@code (2, 2, 2)}).
     * When {@code rotate90Cw == true}, applies a 90-degree clockwise rotation inside the 3x5x3 box:
     * {@code (x, z) -> (2 - z, x)}.
     */
    private static int[][] buildAsymmetricOakRelativeVoxels(boolean rotate90Cw) {
        int[][] base = {
            {1, 0, 1, 0},
            {1, 1, 1, 0},
            {1, 2, 1, 0},
            {1, 3, 1, 0},
            {0, 3, 1, 1},
            {2, 3, 1, 1},
            {1, 3, 0, 1},
            {1, 3, 2, 1},
            {1, 4, 1, 1},
            {2, 2, 2, 1} // asymmetric branch leaf proving 8-symmetry rotation matching
        };
        if (!rotate90Cw) {
            return base;
        }
        int[][] rot = new int[base.length][4];
        for (int i = 0; i < base.length; i++) {
            int x = base[i][0];
            int y = base[i][1];
            int z = base[i][2];
            rot[i] = new int[] {2 - z, y, x, base[i][3]};
        }
        return rot;
    }

    private static LinkedHashMap<String, Object> buildVillageAndForestChunk(
            int chunkX,
            int chunkZ,
            boolean includeFunctionalBlocks,
            boolean includeChestBlockEntity,
            int treeShiftX,
            int treeShiftZ,
            int oreShiftX,
            boolean rotateTree90Cw,
            boolean malformTree) throws IOException {
        // Section Y = -4 (worldY -64..-49): deepslate + 4 diamond ores
        List<String> deepPal = List.of("minecraft:deepslate", "minecraft:deepslate_diamond_ore");
        int[] deepIdx = new int[4096];
        for (int i = 0; i < 4; i++) {
            int lx = (4 + oreShiftX + (i & 1)) & 15;
            int lz = 4 + (i >>> 1);
            deepIdx[(6 << 8) | (lz << 4) | lx] = 1;
        }
        LinkedHashMap<String, Object> secDeep = AnvilTestFixtures.sectionWithBiomes(
                (byte) -4, deepPal, AnvilTestFixtures.packIndices(4, deepIdx), List.of("minecraft:plains"), null);

        // Section Y = 1 (worldY 16..31): stone + 8 iron ores in a compact 2x2x2 blob
        List<String> midPal = List.of("minecraft:stone", "minecraft:iron_ore");
        int[] midIdx = new int[4096];
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    int lx = (3 + oreShiftX + dx) & 15;
                    int lz = (3 + dz) & 15;
                    midIdx[((8 + dy) << 8) | (lz << 4) | lx] = 1;
                }
            }
        }
        LinkedHashMap<String, Object> secMid = AnvilTestFixtures.sectionWithBiomes(
                (byte) 1, midPal, AnvilTestFixtures.packIndices(4, midIdx), List.of("minecraft:plains"), null);

        // Section Y = 4 (worldY 64..79): flat grass_block at ly=1 (worldY=65), dirt at ly=0 (worldY=64),
        // village house at lx=2..6, lz=6..10 with doorway at (6, 66..67, 8), chest at (3, 66, 7), bell at (7, 66, 7),
        // dirt_path street from lx=7..15 at lz=8, ly=1 (worldY=65), and oak tree around (11 + treeShiftX, 66, 2 + treeShiftZ)
        List<String> surfPal = List.of(
                "minecraft:air",          // 0
                "minecraft:dirt",         // 1
                "minecraft:grass_block",  // 2
                "minecraft:dirt_path",    // 3
                "minecraft:cobblestone",  // 4
                "minecraft:oak_planks",   // 5
                "minecraft:oak_door",     // 6
                "minecraft:chest",        // 7
                "minecraft:bell",         // 8
                "minecraft:oak_log",      // 9
                "minecraft:oak_leaves");  // 10
        int[] surfIdx = new int[4096];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                surfIdx[(0 << 8) | (lz << 4) | lx] = 1; // dirt at Y=64
                surfIdx[(1 << 8) | (lz << 4) | lx] = 2; // grass_block at Y=65
            }
        }
        // Village house shell (lx=2..6, lz=6..10): floor cobblestone at ly=1, walls oak_planks at ly=2..4
        for (int lz = 6; lz <= 10; lz++) {
            for (int lx = 2; lx <= 6; lx++) {
                surfIdx[(1 << 8) | (lz << 4) | lx] = 4; // cobblestone floor
                boolean wall = (lx == 2 || lx == 6 || lz == 6 || lz == 10);
                if (wall) {
                    for (int ly = 2; ly <= 4; ly++) {
                        surfIdx[(ly << 8) | (lz << 4) | lx] = 5; // oak_planks wall
                    }
                }
            }
        }
        // Doorway opening on east wall (lx=6, lz=8, ly=2..3) leading onto dirt_path street at lx=7..15, lz=8, ly=1
        surfIdx[(2 << 8) | (8 << 4) | 6] = 6; // oak_door lower
        surfIdx[(3 << 8) | (8 << 4) | 6] = 6; // oak_door upper
        for (int lx = 7; lx <= 15; lx++) {
            surfIdx[(1 << 8) | (8 << 4) | lx] = 3; // dirt_path street at Y=65
        }
        if (includeFunctionalBlocks) {
            surfIdx[(2 << 8) | (7 << 4) | 3] = 7; // village chest at (3, 66, 7)
            surfIdx[(2 << 8) | (7 << 4) | 7] = 8; // village bell at (7, 66, 7)
        }

        // Stamp oak tree inside this chunk (if anchor stays inside chunk)
        int[][] treeRows = buildAsymmetricOakRelativeVoxels(rotateTree90Cw);
        int baseTx = 11 + treeShiftX;
        int baseTz = 2 + treeShiftZ;
        if (!malformTree) {
            for (int[] row : treeRows) {
                int lx = baseTx + row[0];
                int ly = 2 + row[1];
                int lz = baseTz + row[2];
                if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
                    surfIdx[(ly << 8) | (lz << 4) | lx] = row[3] == 0 ? 9 : 10;
                }
            }
        } else {
            // Malformed floating canopy missing lower trunk & disconnected leaf scatter
            surfIdx[(5 << 8) | (3 << 4) | 12] = 9;
            surfIdx[(7 << 8) | (3 << 4) | 12] = 10;
            surfIdx[(7 << 8) | (5 << 4) | 14] = 10;
        }

        LinkedHashMap<String, Object> secSurf = AnvilTestFixtures.sectionWithBiomes(
                (byte) 4, surfPal, AnvilTestFixtures.packIndices(4, surfIdx), List.of("minecraft:plains"), null);

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
                        List.of("minecraft:plains"),
                        null));
            }
        }
        sections.add(secSurf);

        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(3953, new long[37], sections);
        root.put("xPos", chunkX);
        root.put("zPos", chunkZ);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");

        List<Object> blockEntities = new ArrayList<>();
        if (includeFunctionalBlocks && includeChestBlockEntity) {
            LinkedHashMap<String, Object> chestBe = new LinkedHashMap<>();
            chestBe.put("id", "minecraft:chest");
            chestBe.put("x", (chunkX << 4) + 3);
            chestBe.put("y", 66);
            chestBe.put("z", (chunkZ << 4) + 7);
            chestBe.put("LootTable", "minecraft:chests/village/village_plains_house");
            blockEntities.add(chestBe);
        }
        root.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, blockEntities));

        LinkedHashMap<String, Object> villageStart = new LinkedHashMap<>();
        villageStart.put("id", "minecraft:village_plains");
        villageStart.put("ChunkX", chunkX);
        villageStart.put("ChunkZ", chunkZ);
        LinkedHashMap<String, Object> starts = new LinkedHashMap<>();
        starts.put("minecraft:village_plains", villageStart);
        LinkedHashMap<String, Object> structures = new LinkedHashMap<>();
        structures.put("starts", starts);
        root.put("structures", structures);
        return root;
    }

    private static LinkedHashMap<String, Object> buildVillagePathContinuationChunk(
            int chunkX, int chunkZ, int pathLz, int pathWorldY) throws IOException {
        return buildVillagePathContinuationChunkWithExtraVoxels(chunkX, chunkZ, pathLz, pathWorldY, List.of());
    }

    private static LinkedHashMap<String, Object> buildVillagePathContinuationChunkWithExtraVoxels(
            int chunkX,
            int chunkZ,
            int pathLz,
            int pathWorldY,
            List<int[]> extraTreeSurfVoxels) throws IOException {
        List<String> surfPal = List.of(
                "minecraft:air",         // 0
                "minecraft:dirt",        // 1
                "minecraft:grass_block", // 2
                "minecraft:dirt_path",   // 3
                "minecraft:oak_log",     // 4
                "minecraft:oak_leaves"); // 5
        int[] surfIdx = new int[4096];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                surfIdx[(0 << 8) | (lz << 4) | lx] = 1;
                surfIdx[(1 << 8) | (lz << 4) | lx] = 2;
            }
        }
        int pathLy = Math.max(1, Math.min(14, pathWorldY - 64));
        for (int lx = 0; lx <= 8; lx++) {
            for (int fillLy = 1; fillLy < pathLy; fillLy++) {
                surfIdx[(fillLy << 8) | (pathLz << 4) | lx] = 1;
            }
            surfIdx[(pathLy << 8) | (pathLz << 4) | lx] = 3;
        }
        for (int[] vox : extraTreeSurfVoxels) {
            surfIdx[(vox[1] << 8) | (vox[2] << 4) | vox[0]] = vox[3] == 0 ? 4 : 5;
        }

        List<LinkedHashMap<String, Object>> sections = new ArrayList<>();
        for (int sy = -4; sy <= 3; sy++) {
            sections.add(AnvilTestFixtures.sectionWithBiomes(
                    (byte) sy,
                    List.of(sy < 0 ? "minecraft:deepslate" : "minecraft:stone"),
                    null,
                    List.of("minecraft:plains"),
                    null));
        }
        sections.add(AnvilTestFixtures.sectionWithBiomes(
                (byte) 4, surfPal, AnvilTestFixtures.packIndices(4, surfIdx), List.of("minecraft:plains"), null));

        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(3953, new long[37], sections);
        root.put("xPos", chunkX);
        root.put("zPos", chunkZ);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        return root;
    }

    private static Map<Long, LinkedHashMap<String, Object>> buildCrossChunkShiftedTreeAndReblobbedOrePair(
            boolean validRotatedCrossChunkTree) throws IOException {
        if (!validRotatedCrossChunkTree) {
            LinkedHashMap<String, Object> c0 = buildVillageAndForestChunk(
                    0, 0, true, true, 0, 0, 5, false, true);
            LinkedHashMap<String, Object> c1 = buildVillagePathContinuationChunk(1, 0, 8, 65);
            return Map.of(packLocalChunk(0, 0), c0, packLocalChunk(1, 0), c1);
        }

        // Shift oak tree anchor to x = 14 (so x = 14, 15 live in chunk 0 and x = 16 -> lx = 0 lives in chunk 1!)
        // and rotate 90 degrees CW, plus shift ores by +5 blocks in X.
        LinkedHashMap<String, Object> c0 = buildVillageAndForestChunk(
                0, 0, true, true, 3, 1, 5, true, false);
        int[][] rotRows = buildAsymmetricOakRelativeVoxels(true);
        List<int[]> spillIntoChunk1 = new ArrayList<>();
        for (int[] row : rotRows) {
            int wx = 14 + row[0];
            int ly = 2 + row[1];
            int wz = 3 + row[2];
            if ((wx >> 4) == 1) {
                spillIntoChunk1.add(new int[] {wx & 15, ly, wz & 15, row[3]});
            }
        }
        LinkedHashMap<String, Object> c1 =
                buildVillagePathContinuationChunkWithExtraVoxels(1, 0, 8, 65, spillIntoChunk1);
        return Map.of(packLocalChunk(0, 0), c0, packLocalChunk(1, 0), c1);
    }

    private static byte[] writeMultiChunkRegion(Map<Long, LinkedHashMap<String, Object>> chunksByLocalCoord)
            throws IOException {
        ByteArrayOutputStream sectorStream = new ByteArrayOutputStream();
        int[] locEntries = new int[1024];
        int currentSector = 2;

        for (Map.Entry<Long, LinkedHashMap<String, Object>> entry : chunksByLocalCoord.entrySet()) {
            int localX = (int) (entry.getKey() & 0xFFFFFFFFL);
            int localZ = (int) (entry.getKey() >>> 32);
            int idx = (localZ & 31) * 32 + (localX & 31);

            byte[] nbtBytes = Nbt.writeNamedRoot("", entry.getValue());
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(nbtBytes.length);
            try (DeflaterOutputStream z = new DeflaterOutputStream(compressed)) {
                z.write(nbtBytes);
            }
            byte[] zlib = compressed.toByteArray();
            int declaredLen = zlib.length + 1;
            int totalBytes = 4 + declaredLen;
            int sectors = (totalBytes + 4095) / 4096;

            ByteArrayOutputStream padded = new ByteArrayOutputStream(sectors * 4096);
            padded.write((declaredLen >>> 24) & 0xFF);
            padded.write((declaredLen >>> 16) & 0xFF);
            padded.write((declaredLen >>> 8) & 0xFF);
            padded.write(declaredLen & 0xFF);
            padded.write(2); // zlib
            padded.write(zlib);
            byte[] chunkPad = new byte[sectors * 4096 - totalBytes];
            padded.write(chunkPad);

            sectorStream.write(padded.toByteArray());
            locEntries[idx] = ((currentSector & 0xFFFFFF) << 8) | (sectors & 0xFF);
            currentSector += sectors;
        }

        byte[] payload = sectorStream.toByteArray();
        byte[] region = new byte[8192 + payload.length];
        for (int i = 0; i < 1024; i++) {
            int loc = locEntries[i];
            if (loc != 0) {
                int off = i * 4;
                region[off] = (byte) ((loc >>> 24) & 0xFF);
                region[off + 1] = (byte) ((loc >>> 16) & 0xFF);
                region[off + 2] = (byte) ((loc >>> 8) & 0xFF);
                region[off + 3] = (byte) (loc & 0xFF);
                region[4096 + off + 3] = 1;
            }
        }
        System.arraycopy(payload, 0, region, 8192, payload.length);
        return region;
    }

    @Test
    @DisplayName("ChunkParityInput value semantics and Accumulator empty-input guards behave consistently")
    void chunkParityInputValueSemanticsAndEmptyGuards() throws IOException {
        String[][] sectionsA = new String[][] {{"minecraft:stone"}, {"minecraft:air"}};
        String[][] sectionsSame = new String[][] {{"minecraft:stone"}, {"minecraft:air"}};
        String[][] sectionsDiff = new String[][] {{"minecraft:dirt"}, {"minecraft:air"}};
        Map<String, Object> rootA = Map.of("xPos", 1, "zPos", 2);
        Map<String, Object> rootDiff = Map.of("xPos", 9, "zPos", 2);

        WeightedParityEvaluator.ChunkParityInput base =
                new WeightedParityEvaluator.ChunkParityInput(1, 2, sectionsA, rootA);
        WeightedParityEvaluator.ChunkParityInput equalCopy =
                new WeightedParityEvaluator.ChunkParityInput(1, 2, sectionsSame, Map.of("xPos", 1, "zPos", 2));

        assertEquals(base, base);
        assertEquals(base, equalCopy);
        assertEquals(base.hashCode(), equalCopy.hashCode());
        assertTrue(base.toString().contains("minecraft:stone"));
        assertFalse(base.equals(null));
        assertFalse(base.equals("not-a-chunk"));
        assertFalse(base.equals(new WeightedParityEvaluator.ChunkParityInput(9, 2, sectionsA, rootA)));
        assertFalse(base.equals(new WeightedParityEvaluator.ChunkParityInput(1, 9, sectionsA, rootA)));
        assertFalse(base.equals(new WeightedParityEvaluator.ChunkParityInput(1, 2, sectionsDiff, rootA)));
        assertFalse(base.equals(new WeightedParityEvaluator.ChunkParityInput(1, 2, sectionsA, rootDiff)));

        WeightedParityEvaluator.Accumulator acc =
                new WeightedParityEvaluator.Accumulator(TemplateConformityLibrary.fromSources(null));
        acc.evaluateRegionPair(0, 0, List.of(), List.of(base));
        acc.evaluateRegionPair(0, 0, List.of(base), List.of());
        WeightedParityReport report = acc.finish(100.0, Map.of(), Map.of());
        assertEquals(0L, report.criticalFunctionalLossCount());
    }
}

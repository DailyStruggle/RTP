package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnvilRegionInspectorCli} covering {@code .mca} and {@code .lca} region
 * inspection, 3D voxel counting, biome filtering, directory priority over {@code entities/poi},
 * and JSON CLI emission (`ADR-016`, `anvil-api-ADR-002`, `anvil-api-ADR-003`, `REQ-RTP-S-005`).
 */
@DisplayName("anvil-api-ADR-002 / ADR-003: AnvilRegionInspectorCli Multi-Format Inspection")
class AnvilRegionInspectorCliTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        RegionFormatRegistry.reset();
    }

    private static byte[] buildTreeChunkMca() throws IOException {
        int[] indices = new int[4096];
        Arrays.fill(indices, 0);
        // Place 10 oak_log voxels and 25 oak_leaves voxels in section Y=4
        for (int i = 0; i < 10; i++) {
            indices[i] = 1;
        }
        for (int i = 10; i < 35; i++) {
            indices[i] = 2;
        }
        long[] packedBlocks = AnvilTestFixtures.packIndices(4, indices);
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
                (byte) 4,
                List.of("minecraft:air", "minecraft:oak_log", "minecraft:oak_leaves"),
                packedBlocks,
                List.of("minecraft:forest"),
                null);
        long[] motionBlocking = new long[37];
        LinkedHashMap<String, Object> root =
                AnvilTestFixtures.chunkRoot(3953, motionBlocking, List.of(sec));
        return AnvilTestFixtures.writeSingleChunkRegion(root);
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Inspects .mca and transcoded .lca regions with identical voxel counts and biomes")
    void inspectsMcaAndLcaWithIdenticalVoxelCounts(@TempDir Path tmpDir) throws IOException {
        byte[] mcaBytes = buildTreeChunkMca();
        byte[] lcaBytes = LcaRegionWriter.transcodeFromMca(mcaBytes, 0, 0, false);

        Path mcaDir = tmpDir.resolve("world_mca/region");
        Path entitiesDir = tmpDir.resolve("world_mca/entities");
        Path lcaDir = tmpDir.resolve("world_lca/region");
        Files.createDirectories(mcaDir);
        Files.createDirectories(entitiesDir);
        Files.createDirectories(lcaDir);

        Path mcaFile = mcaDir.resolve("r.0.0.mca");
        Files.write(mcaFile, mcaBytes);
        // Entities region should be ignored when block region directory is present
        Files.write(entitiesDir.resolve("r.0.0.mca"), new byte[8192]);

        Path lcaFile = lcaDir.resolve("r.0.0.lca");
        Files.write(lcaFile, lcaBytes);

        List<AnvilRegionInspectorCli.RegionSummary> mcaSummaries =
                AnvilRegionInspectorCli.inspectPath(
                        tmpDir.resolve("world_mca"), List.of("log", "leaves"), List.of(), 5);
        List<AnvilRegionInspectorCli.RegionSummary> lcaSummaries =
                AnvilRegionInspectorCli.inspectPath(
                        lcaFile, List.of("log", "leaves"), List.of("forest"), 5);

        assertEquals(1, mcaSummaries.size());
        assertEquals(1, lcaSummaries.size());

        AnvilRegionInspectorCli.RegionSummary mca = mcaSummaries.get(0);
        AnvilRegionInspectorCli.RegionSummary lca = lcaSummaries.get(0);

        assertEquals(".mca", mca.format());
        assertEquals(".lca", lca.format());
        assertEquals(1, mca.populatedChunks());
        assertEquals(1, lca.populatedChunks());
        assertTrue(mca.biomes().contains("minecraft:forest"));
        assertTrue(lca.biomes().contains("minecraft:forest"));
        assertEquals(10L, mca.matchedBlockTotals().get("minecraft:oak_log"));
        assertEquals(25L, mca.matchedBlockTotals().get("minecraft:oak_leaves"));
        assertEquals(mca.matchedBlockTotals(), lca.matchedBlockTotals());
    }

    @Test
    @DisplayName("REQ-RTP-S-005: Biome filter excludes non-matching sample chunks and CLI main prints valid JSON")
    void biomeFilterAndCliMainJsonOutput(@TempDir Path tmpDir) throws Exception {
        byte[] mcaBytes = buildTreeChunkMca();
        Path mcaFile = tmpDir.resolve("r.2.-1.mca");
        Files.write(mcaFile, mcaBytes);

        List<AnvilRegionInspectorCli.RegionSummary> filteredOut =
                AnvilRegionInspectorCli.inspectPath(
                        mcaFile, List.of("log"), List.of("desert"), 5);
        assertEquals(1, filteredOut.size());
        assertEquals(1, filteredOut.get(0).populatedChunks());
        assertTrue(filteredOut.get(0).sampleChunks().isEmpty());

        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            AnvilRegionInspectorCli.main(new String[] {
                mcaFile.toString(),
                "--block-filter",
                "log,leaves",
                "--biome-filter",
                "forest",
                "--max-samples",
                "4"
            });
        } finally {
            System.setOut(origOut);
        }
        String json = buf.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"region_count\": 1"));
        assertTrue(json.contains("\"minecraft:oak_log\": 10"));
        assertTrue(json.contains("\"minecraft:oak_leaves\": 25"));
        assertTrue(json.contains("\"chunkX\": 64"));
        assertTrue(json.contains("\"chunkZ\": -32"));
        assertFalse(json.isBlank());
    }
}

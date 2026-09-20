package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnvilPrefilter advanced branch coverage tests")
class AnvilPrefilterDetailedTest {

    @Test
    void testDefaultReconcilerEdgeCases() {
        assertNull(AnvilPrefilter.DEFAULT_RECONCILER.apply(null));
        assertEquals("LAVA", AnvilPrefilter.DEFAULT_RECONCILER.apply("minecraft:lava"));
        assertEquals("LAVA", AnvilPrefilter.DEFAULT_RECONCILER.apply("lava"));
        assertEquals("CUSTOM_ORE", AnvilPrefilter.DEFAULT_RECONCILER.apply("mod_id:custom_ore"));
        assertEquals("", AnvilPrefilter.DEFAULT_RECONCILER.apply(""));
    }

    @Test
    void testRegionFileForPaths(@TempDir Path tempDir) {
        Path overworld = AnvilPrefilter.regionFileFor(tempDir, "", 35, 70);
        assertEquals(tempDir.resolve("region").resolve("r.1.2.mca"), overworld);

        Path nether = AnvilPrefilter.regionFileFor(tempDir, "DIM-1", 0, 0);
        assertEquals(tempDir.resolve("DIM-1").resolve("region").resolve("r.0.0.mca"), nether);
    }

    @Test
    void testProbeDetailedNullWorldFolder() {
        CompletableFuture<AnvilPrefilter.ProbeResult> fut = AnvilPrefilter.probeDetailed(
                null, "", 0, 0, Set.of("LAVA"), null);
        assertTrue(fut.isDone());
        AnvilPrefilter.ProbeResult res = fut.join();
        assertEquals(Verdict.UNKNOWN, res.verdict());
        assertNull(res.view());
    }

    @Test
    void testProbeDetailedValidExecution(@TempDir Path tempDir) throws IOException {
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION,
                new long[37],
                List.of(AnvilTestFixtures.section((byte) 0, List.of("minecraft:stone"), null)));
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(root));

        CompletableFuture<AnvilPrefilter.ProbeResult> fut = AnvilPrefilter.probeDetailed(
                tempDir, null, 0, 0, Set.of("LAVA"), null);
        AnvilPrefilter.ProbeResult res = fut.join();
        assertEquals(Verdict.ACCEPT, res.verdict());
        assertNotNull(res.view());
    }

    @Test
    void testProbeSyncDetailedEmptyLocationEntry(@TempDir Path tempDir) throws IOException {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        // Write an empty 8192-byte header where all chunk slots are zero
        Files.write(regionDir.resolve("r.0.0.mca"), new byte[8192]);

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict());
        assertNull(res.view());
    }

    @Test
    void testProbeSyncDetailedCorruptRegionFile(@TempDir Path tempDir) throws IOException {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        // Header with slot 0 pointing past EOF
        byte[] bytes = new byte[8192];
        bytes[0] = 10; // offset 10 sectors (40960 bytes)
        bytes[3] = 1;  // 1 sector
        Files.write(regionDir.resolve("r.0.0.mca"), bytes);

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict());
        assertNull(res.view());
    }

    @Test
    void testProbeSyncDetailedEmptySections(@TempDir Path tempDir) throws IOException {
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION,
                new long[37],
                Collections.emptyList()); // no sections
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(root));

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict());
        assertNull(res.view());
    }

    @Test
    void testProbeSyncDetailedMissingHeightmap(@TempDir Path tempDir) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", DataVersionSupport.MC_1_20_DATA_VERSION);
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(
                AnvilTestFixtures.section((byte) 0, List.of("minecraft:stone"), null))));
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.mca"), AnvilTestFixtures.writeSingleChunkRegion(root));

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict());
        assertNull(res.view());
    }
}

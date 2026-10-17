package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-016 section 8.2 parity gate.
 *
 * <p>Verifies that {@link AnvilReader} parses real server-produced region files across
 * every {@code DataVersion} we claim to support, and that {@link AnvilTestFixtures}
 * produces region bytes the same reader can re-parse with structural equivalence.
 * Without this test, a regression in either the reader or the generator would silently
 * pass through PR #1 and manifest only once the pre-filter is wired into
 * {@code BukkitRTPWorld.getChunkAt} (PR #3).
 *
 * <p><b>Fixture provenance.</b> The {@code r.0.0.mca} files under
 * {@code src/test/resources/anvil/real/<mc-version>/} were produced by live vanilla
 * servers, then trimmed to the single chunk at region-local {@code (0,0)} via the
 * one-shot {@code tools/anvil/TrimRegionToSingleChunk.ps1} utility (since removed from
 * the repo). Per ADR-016 section 8.2 they are the source of truth - the generator is
 * considered correct only insofar as its output is structurally equivalent to theirs.
 *
 * <p>{@code 26_3_R1} (Paper 26.3, {@code region-file-compression=lz4}) keeps three chunks at
 * their original slots, copied sector for sector: {@code (0,0)} {@code minecraft:full},
 * {@code (2,0)} {@code minecraft:terrain} and {@code (4,0)} {@code minecraft:structure_starts}.
 *
 * <p>Add a fixture for every DataVersion whose chunk layout changes: synthetic chunks only
 * encode the format as the test author understood it.
 */
class AnvilFixtureParityTest {

    @ParameterizedTest(name = "real fixture {0} parses with DataVersion={1}")
    @CsvSource({
            "1_20_R1, 3465, 2",
            "1_21_R1, 4671, 2",
            "26_1_R1, 4788, 2",
            "26_3_R1, 5023, 4"
    })
    @DisplayName("Real region files decode via AnvilReader at the expected DataVersion (REQ-RTP-S-005)")
    void realFixtureDecodesAtExpectedDataVersion(String dirName, int expectedDataVersion, int compression)
            throws IOException {
        byte[] regionBytes = loadRealFixture(dirName);

        AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(regionBytes, 0, 0);
        assertNotNull(entry, () -> "Chunk (0,0) missing from real fixture " + dirName);

        assertAll("real fixture " + dirName,
                () -> assertEquals(compression, entry.compressionType, "compression mode (2 zlib, 4 lz4)"),
                () -> assertEquals(expectedDataVersion, AnvilReader.getDataVersion(entry.root),
                        "DataVersion must match the integer recorded in DataVersionSupport"),
                () -> assertTrue(DataVersionSupport.isSupported(expectedDataVersion),
                        "DataVersionSupport whitelist must include " + expectedDataVersion),
                () -> assertNotNull(AnvilReader.getSections(entry.root),
                        "sections list must be present in a generated vanilla chunk"),
                () -> assertNotNull(AnvilReader.getMotionBlockingNoLeaves(entry.root),
                        "Heightmaps.MOTION_BLOCKING_NO_LEAVES must be present"));
    }

    @ParameterizedTest(name = "real fixture {0} decodes terrain")
    @CsvSource({"1_20_R1", "1_21_R1", "26_1_R1", "26_3_R1"})
    @DisplayName("Real generated chunk decodes to non-air blocks in the column probe and the chunk view (S-004)")
    void realFixtureDecodesTerrain(String dirName) throws IOException {
        // Structure checks alone pass a decoder that drops every section; the scan then reads
        // generated land as all air and marks it bad without a load.
        byte[] regionBytes = loadRealFixture(dirName);

        ColumnProbe probe = AnvilReader.readColumnProbe(regionBytes, 0, 0, -64, 319);
        assertNotNull(probe, () -> "column probe of generated chunk (0,0) in " + dirName + " must decode");
        assertTrue(!probe.sections().isEmpty(), () -> dirName + ": probe decoded no block section");
        int solidProbeY = Integer.MIN_VALUE;
        for (int y = probe.minY(); y <= probe.maxY(); y++) {
            if (isSolid(probe.blockAt(y))) {
                solidProbeY = y;
                break;
            }
        }
        assertNotEquals(Integer.MIN_VALUE, solidProbeY,
                () -> dirName + ": centre column probe has no non-air block");

        AnvilChunkView view = AnvilReader.readChunkView(regionBytes, 0, 0);
        assertNotNull(view);
        assertTrue(!view.sections().isEmpty(), () -> dirName + ": chunk view decoded no block section");
        final int y = solidProbeY;
        assertEquals(probe.blockAt(y),
                view.blockIdAt(ColumnProbe.CENTER_LOCAL_X, y, ColumnProbe.CENTER_LOCAL_Z),
                () -> dirName + ": probe and view disagree at y=" + y);
    }

    @Test
    @DisplayName("Real 26.3 proto-chunks are UNKNOWN to the column probe and the prefilter; the full chunk is not (S-004)")
    void realProtoChunksAreUnknown(@TempDir Path worldFolder) throws IOException {
        byte[] regionBytes = loadRealFixture("26_3_R1");
        Path regionDir = Files.createDirectories(worldFolder.resolve("region"));
        Path regionFile = Files.write(regionDir.resolve("r.0.0.mca"), regionBytes);

        // Full chunk: decodes on every read path.
        assertTrue(!AnvilReader.isIncompleteChunk(AnvilReader.readChunkEntry(regionBytes, 0, 0).root));
        assertNotNull(AnvilSectorReader.readColumnProbe(regionFile, 0, 0, -64, 319));
        assertNotEquals(Verdict.UNKNOWN,
                AnvilPrefilter.probeSyncDetailed(worldFolder, "", 0, 0, Set.of()).verdict());

        for (int cx : new int[] {2, 4}) {
            AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(regionBytes, cx, 0);
            assertNotNull(entry, "fixture must carry proto-chunk (" + cx + ",0)");
            assertTrue(AnvilReader.isIncompleteChunk(entry.root), "chunk (" + cx + ",0) Status=" + entry.root.get("Status"));
            assertNull(AnvilReader.readColumnProbe(regionBytes, cx, 0, -64, 319),
                    "proto-chunk (" + cx + ",0) must probe as UNKNOWN (null)");
            assertNull(AnvilSectorReader.readColumnProbe(regionFile, cx, 0, -64, 319),
                    "sector read of proto-chunk (" + cx + ",0) must probe as UNKNOWN (null)");
            assertEquals(Verdict.UNKNOWN,
                    AnvilPrefilter.probeSyncDetailed(worldFolder, "", cx, 0, Set.of()).verdict(),
                    "prefilter must defer proto-chunk (" + cx + ",0) to a live load");
        }

        // The terrain-stage chunk already holds stone: UNKNOWN comes from Status, not from a decode gap.
        AnvilChunkView terrain = AnvilReader.readChunkView(regionBytes, 2, 0);
        assertNotNull(terrain);
        boolean sawSolid = false;
        for (int y = -64; y <= 319 && !sawSolid; y++) {
            sawSolid = isSolid(terrain.blockIdAt(ColumnProbe.CENTER_LOCAL_X, y, ColumnProbe.CENTER_LOCAL_Z));
        }
        assertTrue(sawSolid, "terrain-stage proto-chunk should decode to stone");
    }

    @ParameterizedTest(name = "real fixture {0} survives semantic round-trip")
    @CsvSource({"1_20_R1", "1_21_R1", "26_1_R1", "26_3_R1"})
    @DisplayName("Real root → NBT write → NBT re-read is structurally lossless")
    void realRootSurvivesSemanticRoundTrip(String dirName) throws IOException {
        byte[] regionBytes = loadRealFixture(dirName);
        AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(regionBytes, 0, 0);
        assertNotNull(entry);

        byte[] reEncoded = Nbt.writeNamedRoot("", entry.root);
        LinkedHashMap<String, Object> reRead = Nbt.readRootCompound(reEncoded);

        assertAll("semantic round-trip for " + dirName,
                () -> assertEquals(
                        AnvilReader.getDataVersion(entry.root),
                        AnvilReader.getDataVersion(reRead),
                        "DataVersion must round-trip exactly"),
                () -> assertArrayEquals(
                        AnvilReader.getMotionBlockingNoLeaves(entry.root),
                        AnvilReader.getMotionBlockingNoLeaves(reRead),
                        "MOTION_BLOCKING_NO_LEAVES long array must round-trip byte-for-byte"),
                () -> assertEquals(
                        sectionCount(AnvilReader.getSections(entry.root)),
                        sectionCount(AnvilReader.getSections(reRead)),
                        "section count must round-trip"),
                () -> assertEquals(
                        entry.root.keySet(),
                        reRead.keySet(),
                        "root keys must round-trip in the same order"));
    }

    @Test
    @DisplayName("Synthetic single-chunk region generated by AnvilTestFixtures parses back cleanly")
    void syntheticChunkRoundTripsThroughReader() throws IOException {
        long[] heightmap = new long[37]; // MOTION_BLOCKING_NO_LEAVES is 37 packed longs in 1.20+
        for (int i = 0; i < heightmap.length; i++) heightmap[i] = (long) i * 0x0123456789ABCDEFL;

        List<String> palette = Arrays.asList("minecraft:air", "minecraft:stone", "minecraft:lava");
        List<LinkedHashMap<String, Object>> sections = new ArrayList<>();
        sections.add(AnvilTestFixtures.section((byte) -4, palette));
        sections.add(AnvilTestFixtures.section((byte) 0, palette));

        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, heightmap, sections);
        byte[] regionBytes = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(regionBytes, 0, 0);
        assertNotNull(entry, "synthetic region must contain chunk (0,0)");

        assertAll("synthetic chunk parity",
                () -> assertEquals(2, entry.compressionType, "synthetic writer must use zlib (compression mode 2)"),
                () -> assertEquals(DataVersionSupport.MC_1_20_DATA_VERSION,
                        AnvilReader.getDataVersion(entry.root)),
                () -> assertArrayEquals(heightmap, AnvilReader.getMotionBlockingNoLeaves(entry.root)),
                () -> assertEquals(2, sectionCount(AnvilReader.getSections(entry.root))),
                () -> assertEquals(palette, firstSectionPaletteNames(entry.root)));
    }

    // ---------------------------------------------------------------------------- helpers

    private static byte[] loadRealFixture(String dirName) throws IOException {
        String resource = "/anvil/real/" + dirName + "/r.0.0.mca";
        try (InputStream in = AnvilFixtureParityTest.class.getResourceAsStream(resource)) {
            if (in == null) fail("Real fixture not found on classpath: " + resource);
            return in.readAllBytes();
        }
    }

    private static boolean isSolid(String blockId) {
        return blockId != null && !blockId.endsWith("air") && !blockId.endsWith("water");
    }

    private static int sectionCount(Nbt.NbtList sections) {
        return sections == null ? 0 : sections.items.size();
    }

    @SuppressWarnings("unchecked")
    private static List<String> firstSectionPaletteNames(LinkedHashMap<String, Object> root) {
        Nbt.NbtList sections = AnvilReader.getSections(root);
        if (sections == null || sections.items.isEmpty()) return List.of();
        LinkedHashMap<String, Object> first = (LinkedHashMap<String, Object>) sections.items.get(0);
        LinkedHashMap<String, Object> blockStates = (LinkedHashMap<String, Object>) first.get("block_states");
        if (blockStates == null) return List.of();
        Nbt.NbtList palette = (Nbt.NbtList) blockStates.get("palette");
        if (palette == null) return List.of();
        List<String> names = new ArrayList<>(palette.items.size());
        for (Object entry : palette.items) {
            LinkedHashMap<String, Object> m = (LinkedHashMap<String, Object>) entry;
            Object n = m.get("Name");
            if (n instanceof String) names.add((String) n);
        }
        return names;
    }
}

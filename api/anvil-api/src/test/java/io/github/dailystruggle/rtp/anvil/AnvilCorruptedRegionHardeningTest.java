package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hardening and fail-closed regression tests for malformed, corrupted, or oversized
 * region files (both Anvil .mca and Linear .linear ZSTD formats).
 *
 * Covers:
 * 1. Truncated .mca headers (< 8192 bytes).
 * 2. Sector pointers indexing beyond EOF or into overlapping sector ranges (offset < 2).
 * 3. Corrupted or invalid Zlib / ZSTD compression streams and oversized payload bombs.
 * 4. Empty or malformed nibble arrays and heightmaps.
 */
@DisplayName("Anvil & Linear Corrupted Region File Hardening Tests")
class AnvilCorruptedRegionHardeningTest {

    // =========================================================================
    // 1. Truncated .mca headers (less than 8192 bytes)
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Truncated .mca headers (< 8192 bytes) fail closed safely")
    void testTruncatedMcaHeaders(@TempDir Path tempDir) throws IOException {
        // Test byte array slices from 0 up to 8191 bytes
        int[] truncatedLengths = {0, 1, 10, 100, 4095, 4096, 8191};
        for (int len : truncatedLengths) {
            byte[] truncated = new byte[len];
            assertFalse(AnvilReader.INSTANCE.isChunkGenerated(truncated, 0, 0),
                    "isChunkGenerated must return false for length " + len);
            assertThrows(CorruptRegionEntryException.class,
                    () -> AnvilReader.INSTANCE.readChunk(truncated, 0, 0),
                    "readChunk must throw CorruptRegionEntryException for length " + len);
            assertThrows(CorruptRegionEntryException.class,
                    () -> AnvilReader.readChunkView(truncated, 0, 0),
                    "readChunkView must throw CorruptRegionEntryException for length " + len);
            assertThrows(CorruptRegionEntryException.class,
                    () -> AnvilReader.readColumnProbe(truncated, 0, 0, -64, 320),
                    "readColumnProbe must throw CorruptRegionEntryException for length " + len);
        }

        // Test AnvilPrefilter.probeSyncDetailed on a world directory with truncated .mca file
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Path mcaFile = regionDir.resolve("r.0.0.mca");
        Files.write(mcaFile, new byte[500]); // truncated file on disk

        AnvilPrefilter.ProbeResult result = AnvilPrefilter.probeSyncDetailed(tempDir, "", 0, 0, Set.of("LAVA"));
        assertNotNull(result);
        assertEquals(Verdict.UNKNOWN, result.verdict(), "Truncated .mca must fail closed to UNKNOWN (UNAVAILABLE)");
        assertNull(result.view(), "View must be null on UNKNOWN");
    }

    // =========================================================================
    // 2. Sector pointers indexing beyond EOF or overlapping sector ranges
    // =========================================================================

    @Test
    @DisplayName("Scenario 2: Sector offset < 2 overlaps the 8 KiB header table and fails closed")
    void testSectorPointerOverlapsHeader(@TempDir Path tempDir) throws IOException {
        // 8192 header + 4096 sector 2
        byte[] region = new byte[8192 + 4096];
        int cx = 0, cz = 0;
        int locOffset = (cx + cz * 32) * 4;

        // Set sectorOffset = 1 (byte 4096, inside header timestamps table!) and sectorCount = 1
        region[locOffset] = 0;
        region[locOffset + 1] = 0;
        region[locOffset + 2] = 1; // sectorOffset 1 < 2
        region[locOffset + 3] = 1; // sectorCount 1

        assertFalse(AnvilReader.INSTANCE.isChunkGenerated(region, cx, cz),
                "isChunkGenerated must return false when sectorOffset < 2");

        CorruptRegionEntryException ex = assertThrows(CorruptRegionEntryException.class,
                () -> AnvilReader.INSTANCE.readChunk(region, cx, cz));
        assertTrue(ex.getMessage().contains("overlaps 8 KiB region header"), ex.getMessage());

        // File-backed probeSyncDetailed check
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.mca"), region);

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", cx, cz, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict(), "Sector overlap must fail closed to UNKNOWN");
        assertNull(res.view());
    }

    @Test
    @DisplayName("Scenario 2: Sector offset with zero sectorCount or indexing beyond EOF fails closed")
    void testSectorPointerBeyondEofOrZeroCount() {
        byte[] region = new byte[8192 + 4096]; // exactly 3 sectors (0, 1, 2)
        int cx = 5, cz = 5;
        int locOffset = (cx + cz * 32) * 4;

        // Case A: sectorOffset = 2, but sectorCount = 0
        region[locOffset] = 0;
        region[locOffset + 1] = 0;
        region[locOffset + 2] = 2;
        region[locOffset + 3] = 0;

        assertFalse(AnvilReader.INSTANCE.isChunkGenerated(region, cx, cz));
        assertThrows(CorruptRegionEntryException.class, () -> AnvilReader.INSTANCE.readChunk(region, cx, cz));

        // Case B: sectorOffset = 2, sectorCount = 2 (needs 8192 + 8192 = 16384 bytes, file is 12288)
        region[locOffset + 3] = 2;
        assertFalse(AnvilReader.INSTANCE.isChunkGenerated(region, cx, cz));
        CorruptRegionEntryException exB = assertThrows(CorruptRegionEntryException.class,
                () -> AnvilReader.INSTANCE.readChunk(region, cx, cz));
        assertTrue(exB.getMessage().contains("spans past end of file"), exB.getMessage());

        // Case C: sectorOffset = 100 (far beyond EOF)
        region[locOffset] = 0;
        region[locOffset + 1] = 0;
        region[locOffset + 2] = 100;
        region[locOffset + 3] = 1;
        assertFalse(AnvilReader.INSTANCE.isChunkGenerated(region, cx, cz));
        assertThrows(CorruptRegionEntryException.class, () -> AnvilReader.INSTANCE.readChunk(region, cx, cz));
    }

    // =========================================================================
    // 3. Corrupted or invalid Zlib / ZSTD compression streams & oversized data
    // =========================================================================

    @Test
    @DisplayName("Scenario 3: Corrupted Zlib / Deflate stream fails closed")
    void testCorruptedZlibStream(@TempDir Path tempDir) throws IOException {
        byte[] region = new byte[8192 + 4096];
        int cx = 2, cz = 3;
        int locOffset = (cx + cz * 32) * 4;
        region[locOffset] = 0;
        region[locOffset + 1] = 0;
        region[locOffset + 2] = 2; // sectorOffset 2 (byte 8192)
        region[locOffset + 3] = 1; // 1 sector

        // Fill sector 2 with invalid zlib data
        ByteBuffer bb = ByteBuffer.wrap(region, 8192, 4096);
        bb.putInt(50); // declaredLength (includes compression byte)
        bb.put((byte) 2); // mode 2 = zlib
        // Write garbage bytes that cannot decompress
        byte[] garbage = new byte[45];
        java.util.Arrays.fill(garbage, (byte) 0xFF);
        bb.put(garbage);

        assertThrows(IOException.class, () -> AnvilReader.INSTANCE.readChunk(region, cx, cz));

        // Prefilter probe fails closed to UNKNOWN
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.mca"), region);

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", (0 << 5) | cx, (0 << 5) | cz, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict());
        assertNull(res.view());
    }

    @Test
    @DisplayName("Scenario 3: Corrupted or invalid Linear ZSTD stream fails closed")
    void testCorruptedLinearZstdStream(@TempDir Path tempDir) throws IOException {
        // Linear region file header:
        // Magic (8 bytes) + version (1 byte) + timestamp (8 bytes) + compressionLevel (1 byte) + dataPayloadLength (4 bytes) = 22 bytes
        // Followed by 1024 ints chunk lengths (4096 bytes) + 1024 ints timestamps (4096 bytes) = 8214 bytes header
        int totalHeader = 22 + 4096 + 4096;
        byte[] linearBytes = new byte[totalHeader + 100];
        ByteBuffer buf = ByteBuffer.wrap(linearBytes);
        buf.putLong(LinearRegionReader.LINEAR_MAGIC_V1);
        buf.put((byte) 1); // version 1
        buf.putLong(123456789L); // timestamp
        buf.put((byte) 6); // compression level
        buf.putInt(100); // data payload length

        // Set chunk (0, 0) uncompressed length = 500 bytes
        buf.position(22);
        buf.putInt(500);

        // Put invalid non-zstd bytes at the payload offset
        buf.position(totalHeader);
        byte[] garbage = new byte[100];
        java.util.Arrays.fill(garbage, (byte) 0xAA);
        buf.put(garbage);

        assertTrue(LinearRegionReader.INSTANCE.isChunkGenerated(linearBytes, 0, 0));
        assertThrows(CorruptRegionEntryException.class, () -> LinearRegionReader.INSTANCE.readChunk(linearBytes, 0, 0));

        // Test oversized declared Linear chunk length (> 32 MiB)
        byte[] linearOversized = linearBytes.clone();
        ByteBuffer bufOver = ByteBuffer.wrap(linearOversized);
        bufOver.position(22);
        bufOver.putInt(100 * 1024 * 1024); // 100 MiB declared length
        assertThrows(CorruptRegionEntryException.class, () -> LinearRegionReader.INSTANCE.readChunk(linearOversized, 0, 0));

        // Check probeSyncDetailed on corrupted .linear file
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.linear"), linearBytes);

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict(), "Corrupt Linear ZSTD must fail closed to UNKNOWN");
        assertNull(res.view());
    }

    @Test
    @DisplayName("Scenario 3: Decompression bomb / oversized stream is aborted safely")
    void testOversizedDecompressionStreamAborted() throws Exception {
        // Create a synthetic NBT payload that decompresses to > 32 MiB
        byte[] largeZeros = new byte[33 * 1024 * 1024]; // 33 MiB of zeroes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(largeZeros);
        }
        byte[] compressed = baos.toByteArray(); // compressed zeroes are tiny (< 40 KiB)

        int sectorCount = (int) Math.ceil((compressed.length + 5.0) / 4096.0);
        byte[] region = new byte[8192 + sectorCount * 4096];
        int locOffset = 0;
        region[locOffset] = 0;
        region[locOffset + 1] = 0;
        region[locOffset + 2] = 2; // sectorOffset 2
        region[locOffset + 3] = (byte) sectorCount;

        ByteBuffer bb = ByteBuffer.wrap(region, 8192, sectorCount * 4096);
        bb.putInt(compressed.length + 1);
        bb.put((byte) 2); // zlib
        bb.put(compressed);

        CorruptRegionEntryException ex = assertThrows(CorruptRegionEntryException.class,
                () -> AnvilReader.INSTANCE.readChunk(region, 0, 0));
        assertTrue(ex.getMessage().contains("exceeded"), ex.getMessage());
    }

    // =========================================================================
    // 4. Empty or malformed nibble arrays and heightmaps
    // =========================================================================

    @Test
    @DisplayName("Scenario 4: Empty and malformed NibbleArray returns 0 safely without exceptions")
    void testNibbleArraySafety() {
        NibbleArray nullArr = new NibbleArray(null);
        assertFalse(nullArr.isComplete());
        assertEquals(0, nullArr.get(0));
        assertEquals(0, nullArr.get(0, 0, 0));
        assertEquals(0, nullArr.get(15, 15, 15));

        NibbleArray emptyArr = new NibbleArray(new byte[0]);
        assertFalse(emptyArr.isComplete());
        assertEquals(0, emptyArr.get(0));
        assertEquals(0, emptyArr.get(5, 5, 5));

        // Truncated array (e.g. 10 bytes instead of 2048)
        byte[] shortBytes = new byte[10];
        shortBytes[0] = (byte) 0xA5; // low nibble = 5, high nibble = 10
        NibbleArray shortArr = new NibbleArray(shortBytes);
        assertFalse(shortArr.isComplete());
        assertEquals(5, shortArr.get(0));
        assertEquals(10, shortArr.get(1));
        assertEquals(0, shortArr.get(20)); // beyond 10 bytes
        assertEquals(0, shortArr.get(15, 15, 15)); // maps to 4095, safe 0 return
        assertEquals(0, shortArr.get(-1, 0, 0)); // negative coordinate returns 0

        // Complete 2048-byte nibble array
        byte[] fullBytes = new byte[2048];
        fullBytes[0] = (byte) 0x37;
        NibbleArray fullArr = new NibbleArray(fullBytes);
        assertTrue(fullArr.isComplete());
        assertEquals(7, fullArr.get(0));
        assertEquals(3, fullArr.get(1));
    }

    @Test
    @DisplayName("Scenario 4: Truncated or empty block_states data array fails closed to palette[0]")
    void testPaletteSectionTruncatedDataArray() {
        List<String> palette = List.of("minecraft:stone", "minecraft:lava");
        // data array length 1 is far shorter than needed for 4096 4-bit entries (needs 256 longs)
        long[] shortData = new long[1];
        PaletteSection sec = new PaletteSection(0, palette, shortData);

        // entryIndex(0, 0, 0) maps to long 0 -> succeeds
        assertEquals("minecraft:stone", sec.blockIdAt(0, 0, 0));

        // entryIndex(15, 15, 15) maps to long 255 which is >= shortData.length!
        // Must NOT throw IndexOutOfBoundsException; must fail closed to palette[0]
        assertEquals("minecraft:stone", sec.blockIdAt(15, 15, 15));
    }

    @Test
    @DisplayName("Scenario 4: Empty heightmap array fails closed safely in AnvilPrefilter and AnvilChunkView")
    void testEmptyAndMalformedHeightmap(@TempDir Path tempDir) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 3465); // 1.20.2

        LinkedHashMap<String, Object> heightmaps = new LinkedHashMap<>();
        heightmaps.put("MOTION_BLOCKING_NO_LEAVES", new long[0]); // empty array
        root.put("Heightmaps", heightmaps);

        Nbt.NbtList secList = new Nbt.NbtList(Nbt.TAG_COMPOUND, new java.util.ArrayList<>());
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) 0);
        sec.put("block_states", java.util.Map.of("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND,
                List.of(java.util.Map.of("Name", "minecraft:stone")))));
        secList.items.add(sec);
        root.put("sections", secList);

        // AnvilChunkView getSurfaceHeight returns floor on empty heightmap
        AnvilChunkView view = AnvilReader.toView(root);
        assertEquals(0, view.getSurfaceHeight(0, 0));
        assertEquals(0, view.getSurfaceHeight(8, 8));

        // In AnvilPrefilter, an empty heightmap array must result in UNKNOWN:missing-heightmap
        byte[] nbtBytes = Nbt.writeNamedRoot("", root);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(nbtBytes);
        }
        byte[] compressed = baos.toByteArray();

        byte[] region = new byte[8192 + 4096];
        region[0] = 0; region[1] = 0; region[2] = 2; region[3] = 1;
        ByteBuffer bb = ByteBuffer.wrap(region, 8192, 4096);
        bb.putInt(compressed.length + 1);
        bb.put((byte) 1); // gzip
        bb.put(compressed);

        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);
        Files.write(regionDir.resolve("r.0.0.mca"), region);

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(tempDir, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict(), "Empty heightmap must fail closed to UNKNOWN (UNAVAILABLE)");
        assertNull(res.view());
    }

    @Test
    @DisplayName("Scenario 4: Malformed NBT with oversized array declaration fails closed")
    void testMalformedNbtOversizedArray() {
        // Construct raw NBT tag with type TAG_INT_ARRAY and declared length Integer.MAX_VALUE
        byte[] badNbt = new byte[]{
                Nbt.TAG_COMPOUND, // outer tag
                0, 0, // empty name
                Nbt.TAG_INT_ARRAY, // child tag
                0, 3, 'b', 'a', 'd', // child name "bad"
                0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF // length 2147483647
        };

        IOException ex = assertThrows(IOException.class, () -> Nbt.readRootCompound(badNbt));
        assertTrue(ex.getMessage().contains("Malformed TAG_Int_Array length"), ex.getMessage());
    }
}

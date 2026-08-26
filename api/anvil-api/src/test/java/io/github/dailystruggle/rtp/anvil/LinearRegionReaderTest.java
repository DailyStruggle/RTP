package io.github.dailystruggle.rtp.anvil;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ADR-077 - Linear (.linear / ZSTD) Region Reader")
class LinearRegionReaderTest {

    @Test
    @DisplayName("isZstdAvailable returns true when native library links")
    void testZstdAvailable() {
        assertTrue(LinearRegionReader.isZstdAvailable());
    }

    @Test
    @DisplayName("isChunkGenerated returns false on short or corrupt headers")
    void testIsChunkGeneratedInvalid() {
        assertFalse(LinearRegionReader.INSTANCE.isChunkGenerated(null, 0, 0));
        assertFalse(LinearRegionReader.INSTANCE.isChunkGenerated(new byte[10], 0, 0));
        assertFalse(LinearRegionReader.INSTANCE.isChunkGenerated(new byte[5000], -1, 0));
        assertFalse(LinearRegionReader.INSTANCE.isChunkGenerated(new byte[5000], 0, 32));
    }

    @Test
    @DisplayName("readChunk throws on invalid magic header")
    void testReadChunkInvalidMagic() {
        byte[] invalidBytes = new byte[100];
        assertThrows(CorruptRegionEntryException.class, () ->
                LinearRegionReader.INSTANCE.readChunk(invalidBytes, 0, 0));
    }

    @Test
    @DisplayName("Synthetically generated Linear v1/v2 region file decodes cleanly")
    void testSyntheticLinearRegionFile() throws IOException {
        long[] heightmap = new long[37];
        Arrays.fill(heightmap, 0x0123456789ABCDEFL);

        List<String> palette = Arrays.asList("minecraft:air", "minecraft:stone", "minecraft:grass_block");
        List<LinkedHashMap<String, Object>> sections = new ArrayList<>();
        sections.add(AnvilTestFixtures.section((byte) -4, palette));
        sections.add(AnvilTestFixtures.section((byte) 0, palette));

        LinkedHashMap<String, Object> root0 = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, heightmap, sections);
        byte[] chunk0Nbt = Nbt.writeNamedRoot("", root0);

        LinkedHashMap<String, Object> root1 = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_21_DATA_VERSION, heightmap, sections);
        byte[] chunk1Nbt = Nbt.writeNamedRoot("", root1);

        byte[] linearRegion = buildSyntheticLinearRegion(chunk0Nbt, chunk1Nbt);

        assertTrue(LinearRegionReader.INSTANCE.isChunkGenerated(linearRegion, 0, 0));
        assertTrue(LinearRegionReader.INSTANCE.isChunkGenerated(linearRegion, 1, 0));
        assertFalse(LinearRegionReader.INSTANCE.isChunkGenerated(linearRegion, 2, 0));

        // Read chunk (0,0)
        AnvilReader.ChunkEntry entry0 = LinearRegionReader.INSTANCE.readChunk(linearRegion, 0, 0);
        assertNotNull(entry0);
        assertEquals(DataVersionSupport.MC_1_20_DATA_VERSION, AnvilReader.getDataVersion(entry0.root));

        // Read chunk (1,0)
        AnvilReader.ChunkEntry entry1 = LinearRegionReader.INSTANCE.readChunk(linearRegion, 1, 0);
        assertNotNull(entry1);
        assertEquals(DataVersionSupport.MC_1_21_DATA_VERSION, AnvilReader.getDataVersion(entry1.root));

        // Read chunk (2,0) - absent
        AnvilReader.ChunkEntry entry2 = LinearRegionReader.INSTANCE.readChunk(linearRegion, 2, 0);
        assertNull(entry2);
    }

    @Test
    @DisplayName("RegionFileResolver prefers .linear over .mca when present")
    void testRegionFileResolver(@TempDir Path tempDir) throws IOException {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        Path mcaFile = regionDir.resolve("r.0.0.mca");
        Path linearFile = regionDir.resolve("r.0.0.linear");

        // When neither exists -> default to .mca path and Anvil reader
        RegionFileResolver.ResolvedRegion resNone = RegionFileResolver.resolve(tempDir, "", 0, 0);
        assertEquals(mcaFile, resNone.path());
        assertEquals(AnvilReader.INSTANCE, resNone.reader());
        assertFalse(resNone.isLinear());

        // When only .mca exists -> resolve to .mca
        Files.write(mcaFile, new byte[100]);
        RegionFileResolver.ResolvedRegion resMca = RegionFileResolver.resolve(tempDir, "", 0, 0);
        assertEquals(mcaFile, resMca.path());
        assertEquals(AnvilReader.INSTANCE, resMca.reader());
        assertFalse(resMca.isLinear());

        // When both exist -> prefer .linear
        Files.write(linearFile, new byte[100]);
        RegionFileResolver.ResolvedRegion resLinear = RegionFileResolver.resolve(tempDir, "", 0, 0);
        assertEquals(linearFile, resLinear.path());
        assertEquals(LinearRegionReader.INSTANCE, resLinear.reader());
        assertTrue(resLinear.isLinear());
    }

    /**
     * Builds a minimal valid Linear v2 region byte array with chunk 0 and chunk 1 populated.
     */
    private static byte[] buildSyntheticLinearRegion(byte[] chunk0, byte[] chunk1) throws IOException {
        int chunks = LinearRegionReader.CHUNKS_PER_REGION; // 1024
        int[] chunkLengths = new int[chunks];
        chunkLengths[0] = chunk0.length;
        chunkLengths[1] = chunk1.length;

        ByteArrayOutputStream uncompressedStream = new ByteArrayOutputStream();
        uncompressedStream.write(chunk0);
        uncompressedStream.write(chunk1);
        byte[] uncompressedBytes = uncompressedStream.toByteArray();

        // Compress stream with ZSTD level 3
        byte[] compressedZstd = Zstd.compress(uncompressedBytes, 3);

        int headerSize = 22;
        int chunkLengthsSize = chunks * 4;
        int timestampsSize = chunks * 8; // v2 format: 8 bytes per timestamp
        int totalHeaderSize = headerSize + chunkLengthsSize + timestampsSize;

        ByteBuffer buffer = ByteBuffer.allocate(totalHeaderSize + compressedZstd.length);
        buffer.putLong(LinearRegionReader.LINEAR_MAGIC_V1);
        buffer.put((byte) 2); // Version 2
        buffer.putLong(System.currentTimeMillis() / 1000L); // Newest timestamp
        buffer.put((byte) 3); // Compression level
        buffer.putInt(compressedZstd.length); // Data payload length

        for (int len : chunkLengths) {
            buffer.putInt(len);
        }
        for (int i = 0; i < chunks; i++) {
            buffer.putLong(0L); // Timestamps
        }
        buffer.put(compressedZstd);

        return buffer.array();
    }
}

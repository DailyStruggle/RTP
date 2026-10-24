package io.github.dailystruggle.rtp.anvil;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.PbitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LinearRegionReader - Canonical Specification & CBIT Tripwires")
class LinearRegionReaderTest {

    /**
     * Builds a minimal valid NBT compound representing a chunk with xPos and zPos.
     */
    private static byte[] buildMinimalChunkNbt(int chunkX, int chunkZ) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(10); // TAG_Compound
            dos.writeUTF("");  // root name

            // int xPos
            dos.writeByte(3); // TAG_Int
            dos.writeUTF("xPos");
            dos.writeInt(chunkX);

            // int zPos
            dos.writeByte(3); // TAG_Int
            dos.writeUTF("zPos");
            dos.writeInt(chunkZ);

            dos.writeByte(0); // TAG_End
        }
        return baos.toByteArray();
    }

    /**
     * Constructs a valid in-memory .linear region file conforming to the specification.
     */
    private static byte[] buildSyntheticLinearFile(int rx, int rz, byte[] chunkNbt) throws Exception {
        int index = (rx & 31) + ((rz & 31) << 5);

        // Decompressed stream: 8192 bytes index table + raw chunk NBTs
        byte[] uncompressed = new byte[8192 + chunkNbt.length];
        ByteBuffer indexBuf = ByteBuffer.wrap(uncompressed, 0, 8192);
        indexBuf.position(index * 8);
        indexBuf.putInt(chunkNbt.length); // chunkSize
        indexBuf.putInt(1700000000);      // timestamp

        System.arraycopy(chunkNbt, 0, uncompressed, 8192, chunkNbt.length);

        // Compress stream via host zstd
        byte[] compressed = com.github.luben.zstd.Zstd.compress(uncompressed);

        // Build file buffer: 32 bytes superblock + compressed + 8 bytes footer
        byte[] fileBytes = new byte[32 + compressed.length + 8];
        ByteBuffer bb = ByteBuffer.wrap(fileBytes);

        // Superblock
        bb.putLong(LinearRegionReader.LINEAR_SIGNATURE);
        bb.put((byte) 2); // version 2
        bb.putLong(System.currentTimeMillis()); // newestTimestamp
        bb.put((byte) 1); // compressionLevel
        bb.putShort((short) 1); // chunkCount
        bb.putInt(compressed.length); // completeRegionLength
        bb.putLong(0L); // reserved

        // Compressed payload
        bb.put(compressed);

        // Footer
        bb.putLong(LinearRegionReader.LINEAR_SIGNATURE);

        return fileBytes;
    }

    @Test
    @DisplayName("Successfully decodes valid chunk from Linear region file")
    void decodesValidChunk() throws Exception {
        byte[] nbt = buildMinimalChunkNbt(10, 20);
        int rx = 10 & 31;
        int rz = 20 & 31;
        byte[] linearFile = buildSyntheticLinearFile(rx, rz, nbt);

        LinearRegionReader reader = LinearRegionReader.INSTANCE;

        // Verify chunk presence check (both 3-arg and 4-arg overloads)
        assertTrue(reader.isChunkGenerated(linearFile, rx, rz));
        assertTrue(reader.isChunkGenerated(linearFile, linearFile.length, rx, rz));
        assertFalse(reader.isChunkGenerated(linearFile, (rx + 1) & 31, rz));
        assertFalse(reader.isChunkGenerated(linearFile, -1, rz));
        assertFalse(reader.isChunkGenerated(linearFile, 32, rz));
        assertFalse(reader.isChunkGenerated(null, rx, rz));
        assertFalse(reader.isChunkGenerated(new byte[10], rx, rz));

        // Read and decode chunk (both 3-arg and 4-arg overloads)
        AnvilReader.ChunkEntry entry = reader.readChunk(linearFile, rx, rz);
        assertNotNull(entry);
        assertEquals(nbt.length, entry.declaredSectionLength);

        AnvilReader.ChunkEntry entry4Arg = reader.readChunk(linearFile, linearFile.length, rx, rz);
        assertNotNull(entry4Arg);

        LinkedHashMap<String, Object> root = entry.root;
        assertNotNull(root);
        assertEquals(10, root.get("xPos"));
        assertEquals(20, root.get("zPos"));

        // Unallocated chunk returns null
        assertNull(reader.readChunk(linearFile, (rx + 1) & 31, rz));

        // Out of bounds coordinates throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> reader.readChunk(linearFile, -1, rz));
        assertThrows(IllegalArgumentException.class, () -> reader.readChunk(linearFile, 32, rz));
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(null, rx, rz));
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(new byte[10], rx, rz));
    }

    @Test
    @DisplayName("Tests LRU cache eviction by reading more than CACHE_CAPACITY files")
    void cacheEviction() throws Exception {
        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        byte[] nbt = buildMinimalChunkNbt(1, 1);
        for (int i = 0; i < 6; i++) {
            byte[] file = buildSyntheticLinearFile(1, 1, nbt);
            assertNotNull(reader.readChunk(file, 1, 1));
            // Second read hits cache
            assertNotNull(reader.readChunk(file, 1, 1));
        }
    }

    @Test
    @DisplayName("Unsupported Linear version throws CorruptRegionEntryException")
    void unsupportedVersion() throws Exception {
        byte[] nbt = buildMinimalChunkNbt(0, 0);
        byte[] linearFile = buildSyntheticLinearFile(0, 0, nbt);

        linearFile[8] = (byte) 3; // version 3
        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(linearFile, 0, 0));
    }

    @Test
    @DisplayName("CBIT Tripwire T1: Superblock signature mismatch throws CorruptRegionEntryException")
    void tripwireBadHeaderMagic() throws Exception {
        byte[] nbt = buildMinimalChunkNbt(0, 0);
        byte[] linearFile = buildSyntheticLinearFile(0, 0, nbt);

        // Corrupt header signature
        ByteBuffer.wrap(linearFile).putLong(0, 0x123456789ABCDEF0L);

        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(linearFile, 0, 0));
        assertFalse(reader.isChunkGenerated(linearFile, 0, 0));
    }

    @Test
    @DisplayName("CBIT Tripwire T2: Footer signature mismatch throws CorruptRegionEntryException")
    void tripwireBadFooterMagic() throws Exception {
        byte[] nbt = buildMinimalChunkNbt(0, 0);
        byte[] linearFile = buildSyntheticLinearFile(0, 0, nbt);

        // Corrupt footer signature (last 8 bytes)
        ByteBuffer.wrap(linearFile).putLong(linearFile.length - 8, 0xCAFEBABECAFEBABEL);

        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(linearFile, 0, 0));
    }

    @Test
    @DisplayName("CBIT Tripwire T3: Implausible completeRegionLength throws CorruptRegionEntryException")
    void tripwireBadCompleteRegionLength() throws Exception {
        byte[] nbt = buildMinimalChunkNbt(0, 0);
        byte[] linearFile = buildSyntheticLinearFile(0, 0, nbt);

        // Set declared stream length larger than buffer
        ByteBuffer.wrap(linearFile).putInt(20, linearFile.length + 100);

        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(linearFile, 0, 0));
    }

    @Test
    @DisplayName("CBIT Tripwire T6: Non-NBT payload (byte 0 != 0x0A) throws CorruptRegionEntryException")
    void tripwireNonNbtPayload() throws Exception {
        byte[] badPayload = "Not An NBT Compound".getBytes(StandardCharsets.UTF_8);
        byte[] linearFile = buildSyntheticLinearFile(0, 0, badPayload);

        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(linearFile, 0, 0));
    }

    @Test
    @DisplayName("PBIT execution reports healthy state when Zstd is operational")
    void pbitReportsHealthy() {
        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        PbitResult result = reader.executePbit();
        assertNotNull(result);
        assertTrue(result.passed());
        assertEquals(BitHealth.HEALTHY, reader.getHealthStatus());
    }
}

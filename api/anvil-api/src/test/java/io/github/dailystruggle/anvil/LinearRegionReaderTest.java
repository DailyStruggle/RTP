package io.github.dailystruggle.anvil;

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

        linearFile[8] = (byte) 99; // unsupported version
        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        assertThrows(CorruptRegionEntryException.class, () -> reader.readChunk(linearFile, 0, 0));
    }

    @Test
    @DisplayName("Decodes Leaves/Leaf Linear v2 bucketed (version 3) and Luminol/Leaf BufferedLinear (.b_linear v2/v3)")
    void decodesLinearV2AndBufferedLinear() throws Exception {
        byte[] nbt = buildMinimalChunkNbt(5, 9);
        int rx = 5;
        int rz = 9;

        // 1. Construct Leaves / Winds-Studio Leaf Linear v2 bucketed (version 3, gridSize = 8)
        int gridSize = 8;
        int bucketSpan = 32 / gridSize; // 4
        int targetBx = rx / bucketSpan;
        int targetBz = rz / bucketSpan;
        int targetBucketIdx = targetBx * gridSize + targetBz;

        ByteArrayOutputStream bucketRawOut = new ByteArrayOutputStream();
        try (com.github.luben.zstd.ZstdOutputStream zstdOut = new com.github.luben.zstd.ZstdOutputStream(bucketRawOut, 1);
             DataOutputStream bucketDos = new DataOutputStream(zstdOut)) {
            for (int cx = 0; cx < bucketSpan; cx++) {
                for (int cz = 0; cz < bucketSpan; cz++) {
                    int chunkX = targetBx * bucketSpan + cx;
                    int chunkZ = targetBz * bucketSpan + cz;
                    if (chunkX == rx && chunkZ == rz) {
                        bucketDos.writeInt(nbt.length + 8);
                        bucketDos.writeLong(1700000000L);
                        bucketDos.write(nbt);
                    } else {
                        bucketDos.writeInt(0);
                        bucketDos.writeLong(0L);
                    }
                }
            }
        }
        byte[] compressedBucket = bucketRawOut.toByteArray();

        ByteArrayOutputStream v2FileOut = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(v2FileOut)) {
            dos.writeLong(LinearRegionReader.LINEAR_SIGNATURE);
            dos.writeByte(3); // version 3 (Linear v2 bucketed)
            dos.writeLong(System.currentTimeMillis());
            dos.writeByte(gridSize);
            dos.writeInt(0); // region_x
            dos.writeInt(0); // region_z
            dos.write(new byte[128]); // existence bitmap
            dos.writeByte(0); // end of NBT features
            for (int i = 0; i < gridSize * gridSize; i++) {
                dos.writeInt(i == targetBucketIdx ? compressedBucket.length : 0);
                dos.writeByte(1);
                dos.writeLong(0L);
            }
            dos.write(compressedBucket);
            dos.writeLong(LinearRegionReader.LINEAR_SIGNATURE);
        }
        byte[] v2Bytes = v2FileOut.toByteArray();

        LinearRegionReader reader = LinearRegionReader.INSTANCE;
        assertTrue(reader.isChunkGenerated(v2Bytes, rx, rz));
        assertFalse(reader.isChunkGenerated(v2Bytes, 0, 0));
        AnvilReader.ChunkEntry v2Entry = reader.readChunk(v2Bytes, rx, rz);
        assertNotNull(v2Entry);
        assertEquals(5, v2Entry.root.get("xPos"));
        assertEquals(9, v2Entry.root.get("zPos"));

        // 2. Construct Luminol / Winds-Studio Leaf BufferedLinear v3 (.b_linear)
        int chunkIndex = rx + (rz << 5);
        int bLinearBucketIdx = chunkIndex >> 6;
        int bLinearBeginChunk = bLinearBucketIdx << 6;
        ByteArrayOutputStream blBucketUncompressed = new ByteArrayOutputStream();
        try (DataOutputStream bDos = new DataOutputStream(blBucketUncompressed)) {
            for (int idx = bLinearBeginChunk; idx < bLinearBeginChunk + 64; idx++) {
                if (idx == chunkIndex) {
                    bDos.writeInt(16 + nbt.length); // sectionSize
                    bDos.writeInt(nbt.length);      // uncompressedLen
                    bDos.writeLong(1700000000L);    // timestamp
                    bDos.writeInt(0x12345678);      // xxHash32
                    bDos.write(nbt);
                } else {
                    bDos.writeInt(0);
                }
            }
        }
        byte[] blRaw = blBucketUncompressed.toByteArray();
        byte[] blCompressed = com.github.luben.zstd.Zstd.compress(blRaw);

        ByteArrayOutputStream blV3Out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(blV3Out)) {
            dos.writeLong(LinearRegionReader.BLINEAR_SIGNATURE);
            dos.writeByte(3); // MASTER_FILE_VERSION_BUCKET
            dos.writeByte(6); // compressionLevel
            dos.writeInt(0x0721); // xxHash32Seed
            for (int i = 0; i < 16; i++) {
                dos.writeLong(i == bLinearBucketIdx ? 142L : 0L);
            }
            dos.writeInt(blRaw.length);
            dos.writeInt(blCompressed.length);
            dos.write(blCompressed);
        }
        byte[] blV3Bytes = blV3Out.toByteArray();

        assertTrue(reader.isChunkGenerated(blV3Bytes, rx, rz));
        assertFalse(reader.isChunkGenerated(blV3Bytes, 0, 0));
        AnvilReader.ChunkEntry blEntry = reader.readChunk(blV3Bytes, rx, rz);
        assertNotNull(blEntry);
        assertEquals(5, blEntry.root.get("xPos"));
        assertEquals(9, blEntry.root.get("zPos"));

        // 3. Construct Winds-Studio Leaf / Luminol BufferedLinear v3 (.b_linear) with inner LZ4 CompressingOps wrapper
        ByteArrayOutputStream innerFrameOut = new ByteArrayOutputStream();
        try (DataOutputStream fDos = new DataOutputStream(innerFrameOut)) {
            fDos.writeInt(16 + nbt.length); // actualBodyLength = 16 + nbt.length
            fDos.writeLong(1700000000L);
            fDos.writeInt(0x12345678);
            fDos.write(nbt);
        }
        byte[] innerFrameBytes = innerFrameOut.toByteArray();
        byte[] lz4Compressed = net.jpountz.lz4.LZ4Factory.fastestInstance()
                .fastCompressor()
                .compress(innerFrameBytes);
        ByteArrayOutputStream blLz4BucketUncompressed = new ByteArrayOutputStream();
        try (DataOutputStream bDos = new DataOutputStream(blLz4BucketUncompressed)) {
            for (int idx = bLinearBeginChunk; idx < bLinearBeginChunk + 64; idx++) {
                if (idx == chunkIndex) {
                    bDos.writeInt(4 + lz4Compressed.length); // sectionSize = 4 + lz4CompLen
                    bDos.writeInt(innerFrameBytes.length);   // uncompressedFrameLen (16 + nbt.length)
                    bDos.write(lz4Compressed);
                } else {
                    bDos.writeInt(0);
                }
            }
        }
        byte[] blLz4Raw = blLz4BucketUncompressed.toByteArray();
        byte[] blLz4Zstd = com.github.luben.zstd.Zstd.compress(blLz4Raw);

        ByteArrayOutputStream blLz4V3Out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(blLz4V3Out)) {
            dos.writeLong(LinearRegionReader.BLINEAR_SIGNATURE);
            dos.writeByte(3); // MASTER_FILE_VERSION_BUCKET
            dos.writeByte(6); // compressionLevel
            dos.writeInt(0x0721); // xxHash32Seed
            for (int i = 0; i < 16; i++) {
                dos.writeLong(i == bLinearBucketIdx ? 142L : 0L);
            }
            dos.writeInt(blLz4Raw.length);
            dos.writeInt(blLz4Zstd.length);
            dos.write(blLz4Zstd);
        }
        byte[] blLz4V3Bytes = blLz4V3Out.toByteArray();

        assertTrue(reader.isChunkGenerated(blLz4V3Bytes, rx, rz));
        assertFalse(reader.isChunkGenerated(blLz4V3Bytes, 0, 0));
        AnvilReader.ChunkEntry blLz4Entry = reader.readChunk(blLz4V3Bytes, rx, rz);
        assertNotNull(blLz4Entry);
        assertEquals(5, blLz4Entry.root.get("xPos"));
        assertEquals(9, blLz4Entry.root.get("zPos"));

        // 4. Construct BufferedLinear v2 (.b_linear version=2) with both direct and LZ4-wrapped chunks
        ByteArrayOutputStream blV2Stream = new ByteArrayOutputStream();
        try (DataOutputStream sDos = new DataOutputStream(blV2Stream)) {
            for (int idx = 0; idx < 1024; idx++) {
                if (idx == chunkIndex) {
                    sDos.writeInt(4 + lz4Compressed.length);
                    sDos.writeInt(innerFrameBytes.length);
                    sDos.write(lz4Compressed);
                } else if (idx == 0) {
                    sDos.writeInt(16 + nbt.length);
                    sDos.writeInt(nbt.length);
                    sDos.writeLong(1700000000L);
                    sDos.writeInt(0x12345678);
                    sDos.write(nbt);
                } else {
                    sDos.writeInt(0);
                }
            }
        }
        byte[] blV2Zstd = com.github.luben.zstd.Zstd.compress(blV2Stream.toByteArray());
        ByteArrayOutputStream blV2Out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(blV2Out)) {
            dos.writeLong(LinearRegionReader.BLINEAR_SIGNATURE);
            dos.writeByte(2); // version 2
            dos.write(new byte[9]); // 18-byte header total
            dos.write(blV2Zstd);
        }
        byte[] blV2Bytes = blV2Out.toByteArray();
        assertTrue(reader.isChunkGenerated(blV2Bytes, rx, rz));
        assertTrue(reader.isChunkGenerated(blV2Bytes, 0, 0));
        assertNotNull(reader.readChunk(blV2Bytes, rx, rz));
        assertNotNull(reader.readChunk(blV2Bytes, 0, 0));

        // 5. Exercise LcaRegionWriter and LcaRegionFileStorage zstdLevel (0, 1, 6) overloads
        byte[] lcaZ0 = LcaRegionWriter.transcodeFromReader(reader, blV3Bytes, 0, 0, 0);
        byte[] lcaZ1 = LcaRegionWriter.transcodeFromReader(reader, blV3Bytes, 0, 0, 1);
        byte[] lcaZ6 = LcaRegionWriter.transcodeFromReader(reader, blV3Bytes, 0, 0, 6);
        assertTrue(LcaRegionReader.INSTANCE.isChunkGenerated(lcaZ0, rx, rz));
        assertTrue(LcaRegionReader.INSTANCE.isChunkGenerated(lcaZ1, rx, rz));
        assertTrue(LcaRegionReader.INSTANCE.isChunkGenerated(lcaZ6, rx, rz));
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

package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Linear Dynamic Registration & Anvil Mode 5 Future-Proofing")
class LinearDynamicRegistrationTest {

    @BeforeEach
    @AfterEach
    void reset() {
        RegionFormatRegistry.reset();
    }

    private static byte[] buildMinimalChunkNbt(int chunkX, int chunkZ) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(10); // TAG_Compound
            dos.writeUTF("");  // root name

            dos.writeByte(3); // TAG_Int
            dos.writeUTF("xPos");
            dos.writeInt(chunkX);

            dos.writeByte(3); // TAG_Int
            dos.writeUTF("zPos");
            dos.writeInt(chunkZ);

            dos.writeByte(0); // TAG_End
        }
        return baos.toByteArray();
    }

    private static byte[] buildSyntheticLinearFile(int rx, int rz, byte[] chunkNbt) throws Exception {
        int index = (rx & 31) + ((rz & 31) << 5);

        byte[] uncompressed = new byte[8192 + chunkNbt.length];
        ByteBuffer indexBuf = ByteBuffer.wrap(uncompressed, 0, 8192);
        indexBuf.position(index * 8);
        indexBuf.putInt(chunkNbt.length);
        indexBuf.putInt(1700000000);

        System.arraycopy(chunkNbt, 0, uncompressed, 8192, chunkNbt.length);
        byte[] compressed = com.github.luben.zstd.Zstd.compress(uncompressed);

        byte[] fileBytes = new byte[32 + compressed.length + 8];
        ByteBuffer bb = ByteBuffer.wrap(fileBytes);
        bb.putLong(LinearRegionReader.LINEAR_SIGNATURE);
        bb.put((byte) 2);
        bb.putLong(System.currentTimeMillis());
        bb.put((byte) 1);
        bb.putShort((short) 1);
        bb.putInt(compressed.length);
        bb.putLong(0L);
        bb.put(compressed);
        bb.putLong(LinearRegionReader.LINEAR_SIGNATURE);

        return fileBytes;
    }

    @Test
    @DisplayName("LinearRegionReader.registerIfAvailable() dynamically enables .linear resolution")
    void dynamicRegistrationEnablesLinearResolution(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("region");
        Files.createDirectories(regionDir);

        byte[] nbt = buildMinimalChunkNbt(5, 7);
        byte[] linearData = buildSyntheticLinearFile(5 & 31, 7 & 31, nbt);
        Path linearFile = regionDir.resolve("r.0.0.linear");
        Files.write(linearFile, linearData);

        // Before registration: test disabled state by explicitly unregistering
        RegionFormatRegistry.unregister(".linear");
        RegionFileResolver.invalidateMemo();
        assertEquals(RegionFileResolver.resolve(tempDir, "", 5, 7).path(), regionDir.resolve("r.0.0.mca"));

        // Register if available
        assertTrue(LinearRegionReader.registerIfAvailable());
        assertTrue(RegionFormatRegistry.isRegistered(".linear"));

        // After registration: resolves .linear file and reader
        RegionFileResolver.invalidateMemo();
        RegionFileResolver.ResolvedRegion res = RegionFileResolver.resolveExisting(tempDir, "", 5, 7);
        assertNotNull(res);
        assertEquals(linearFile, res.path());
        assertEquals(LinearRegionReader.INSTANCE, res.reader());

        AnvilReader.ChunkEntry entry = res.reader().readChunk(Files.readAllBytes(linearFile), 5 & 31, 7 & 31);
        assertNotNull(entry);
        assertEquals(5, entry.root.get("xPos"));
        assertEquals(7, entry.root.get("zPos"));
    }

    @Test
    @DisplayName("AnvilReader decodes future-proof Mode 5 (Zstandard) compressed chunk sectors")
    void anvilReaderDecodesMode5ZstandardSector() throws Exception {
        byte[] nbt = buildMinimalChunkNbt(1, 2);
        byte[] zstdCompressed = com.github.luben.zstd.Zstd.compress(nbt);

        int payloadLength = 1 + zstdCompressed.length; // 1 byte compression mode + compressed bytes
        int totalSectorBytes = 4096;
        byte[] sector = new byte[totalSectorBytes];

        ByteBuffer bb = ByteBuffer.wrap(sector);
        bb.putInt(payloadLength);
        bb.put((byte) 5); // Mode 5 = Zstandard
        bb.put(zstdCompressed);

        // Build 2-sector Anvil file
        byte[] mca = new byte[8192 + 4096];
        // Table entry for (1, 2)
        int index = 1 + (2 << 5);
        int locOffset = index * 4;
        mca[locOffset] = 0;
        mca[locOffset + 1] = 0;
        mca[locOffset + 2] = 2; // sector offset 2 (offset 8192)
        mca[locOffset + 3] = 1; // 1 sector

        System.arraycopy(sector, 0, mca, 8192, 4096);

        AnvilReader.ChunkEntry entry = AnvilReader.readChunkEntry(mca, 1, 2);
        assertNotNull(entry);
        assertEquals(5, entry.compressionType);
        assertEquals(1, entry.root.get("xPos"));
        assertEquals(2, entry.root.get("zPos"));
    }
}

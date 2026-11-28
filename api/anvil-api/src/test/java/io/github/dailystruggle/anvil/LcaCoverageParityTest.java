package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies parity and fail-closed contracts across {@link LcaRegionFileStorage},
 * {@link LcaSectionCodec}, {@link LcaSectionBacking}, {@link LcaV2LegacyCodec}, and
 * {@link LcaRegionWriter} open-archive patching (`anvil-api-ADR-002`, `anvil-api-ADR-003`).
 *
 * @governedBy anvil-api-ADR-002
 * @governedBy anvil-api-ADR-003
 * @governedBy leafanvil-ADR-005
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaCoverageParityTest {

    @Test
    @DisplayName("containsDirectCoWCommittedMarker validates byte slices, heap buffers, and direct buffers")
    void directCoWCommittedMarkerDetection() {
        byte[] marker = LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG.getBytes(StandardCharsets.UTF_8);
        byte[] valid = new byte[marker.length + 12];
        System.arraycopy(marker, 0, valid, 5, marker.length);

        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(null, 0, 10));
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(marker, 0, marker.length));
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(new byte[600], 0, 600));
        assertTrue(LcaRegionFileStorage.containsDirectCoWCommittedMarker(valid, 0, valid.length));
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(new byte[64], 0, 64));

        // ByteBuffer overloads: null, short, oversized, heap, and direct
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(null));
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(ByteBuffer.wrap(marker)));
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(ByteBuffer.allocate(600)));
        assertTrue(LcaRegionFileStorage.containsDirectCoWCommittedMarker(ByteBuffer.wrap(valid)));
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(ByteBuffer.wrap(new byte[64])));

        ByteBuffer directValid = ByteBuffer.allocateDirect(valid.length);
        directValid.put(valid).flip();
        assertTrue(LcaRegionFileStorage.containsDirectCoWCommittedMarker(directValid));

        ByteBuffer directMiss = ByteBuffer.allocateDirect(64);
        directMiss.put(new byte[64]).flip();
        assertFalse(LcaRegionFileStorage.containsDirectCoWCommittedMarker(directMiss));
    }

    @Test
    @DisplayName("decodeAnvilChunkPayload handles GZIP, Zlib, uncompressed, LZ4, and rejects malformed inputs")
    void decodeAnvilChunkPayloadAllCompressionsAndGuards() throws IOException {
        byte[] sample = "LeafAnvil chunk payload sample data repeated repeated repeated 1234567890"
                .getBytes(StandardCharsets.UTF_8);

        // Compression 3: Uncompressed
        byte[] raw = LcaRegionFileStorage.decodeAnvilChunkPayload(3, sample, 0, sample.length);
        assertArrayEquals(sample, raw);

        // Compression 2: Zlib / Deflate
        byte[] zlibBytes;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (DeflaterOutputStream dos = new DeflaterOutputStream(bos)) {
                dos.write(sample);
            }
            zlibBytes = bos.toByteArray();
        }
        assertArrayEquals(sample, LcaRegionFileStorage.decodeAnvilChunkPayload(2, zlibBytes, 0, zlibBytes.length));

        // Compression 1: GZIP
        byte[] gzipBytes;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
                gos.write(sample);
            }
            gzipBytes = bos.toByteArray();
        }
        assertArrayEquals(sample, LcaRegionFileStorage.decodeAnvilChunkPayload(1, gzipBytes, 0, gzipBytes.length));

        // Compression 4: Mojang LZ4Block (raw uncompressed block: magic "LZ4Block", token=0x10, compLen, decompLen, xxHash32)
        byte[] lz4Payload = buildUncompressedLz4Block(sample);
        assertArrayEquals(sample, LcaRegionFileStorage.decodeAnvilChunkPayload(4, lz4Payload, 0, lz4Payload.length));

        // Invalid slices and unsupported compression IDs fail closed
        assertThrows(IOException.class, () -> LcaRegionFileStorage.decodeAnvilChunkPayload(3, null, 0, 1));
        assertThrows(IOException.class, () -> LcaRegionFileStorage.decodeAnvilChunkPayload(3, sample, -1, 1));
        assertThrows(IOException.class, () -> LcaRegionFileStorage.decodeAnvilChunkPayload(3, sample, 0, -1));
        assertThrows(
                IOException.class,
                () -> LcaRegionFileStorage.decodeAnvilChunkPayload(3, sample, sample.length, 1));
        assertThrows(
                IOException.class,
                () -> LcaRegionFileStorage.decodeAnvilChunkPayload(99, sample, 0, sample.length));
    }

    private static byte[] buildUncompressedLz4Block(byte[] sample) {
        // Minimal valid Mojang LZ4Block stream: RAW data block + 21-byte zero-length EOS block
        int len = sample.length;
        byte[] out = new byte[21 + len + 21];
        byte[] magic = "LZ4Block".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, out, 0, 8);
        out[8] = 0x10; // method = 1 (RAW), compressionLevel = 0
        putIntLE(out, 9, len);
        putIntLE(out, 13, len);
        int hash = xxHash32(sample, 0, len, 0x9747B28C) & 0x0FFFFFFF;
        putIntLE(out, 17, hash);
        System.arraycopy(sample, 0, out, 21, len);
        int eos = 21 + len;
        System.arraycopy(magic, 0, out, eos, 8);
        out[eos + 8] = 0x10; // EOS: compLen = 0, decompLen = 0, hash = 0
        return out;
    }

    private static void putIntLE(byte[] buf, int off, int val) {
        buf[off] = (byte) val;
        buf[off + 1] = (byte) (val >>> 8);
        buf[off + 2] = (byte) (val >>> 16);
        buf[off + 3] = (byte) (val >>> 24);
    }

    private static int xxHash32(byte[] data, int off, int len, int seed) {
        final int p1 = 0x9E3779B1;
        final int p2 = 0x85EBCA77;
        final int p3 = 0xC2B2AE3D;
        final int p4 = 0x27D4EB2F;
        final int p5 = 0x165667B1;
        int end = off + len;
        int h32;
        int i = off;
        if (len >= 16) {
            int v1 = seed + p1 + p2;
            int v2 = seed + p2;
            int v3 = seed;
            int v4 = seed - p1;
            int limit = end - 16;
            while (i <= limit) {
                v1 = Integer.rotateLeft(v1 + getIntLE(data, i) * p2, 13) * p1;
                i += 4;
                v2 = Integer.rotateLeft(v2 + getIntLE(data, i) * p2, 13) * p1;
                i += 4;
                v3 = Integer.rotateLeft(v3 + getIntLE(data, i) * p2, 13) * p1;
                i += 4;
                v4 = Integer.rotateLeft(v4 + getIntLE(data, i) * p2, 13) * p1;
                i += 4;
            }
            h32 = Integer.rotateLeft(v1, 1)
                    + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12)
                    + Integer.rotateLeft(v4, 18);
        } else {
            h32 = seed + p5;
        }
        h32 += len;
        while (i + 4 <= end) {
            h32 = Integer.rotateLeft(h32 + getIntLE(data, i) * p3, 17) * p4;
            i += 4;
        }
        while (i < end) {
            h32 = Integer.rotateLeft(h32 + (data[i++] & 0xFF) * p5, 11) * p1;
        }
        h32 ^= h32 >>> 15;
        h32 *= p2;
        h32 ^= h32 >>> 13;
        h32 *= p3;
        h32 ^= h32 >>> 16;
        return h32;
    }

    private static int getIntLE(byte[] buf, int off) {
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }

    @Test
    @DisplayName("Rich multi-biome, Starlight boundary, heightmap, and residual NBT chunks round-trip direct metadata and full NBT")
    void richChunkDirectMetadataBiomesHeightmapsAndReadAhead(@TempDir Path regionDir) throws IOException {
        byte[] chunkNbt = buildRichChunkNbt(0, 0, true);
        byte[] chunkNbtNoStarlight = buildRichChunkNbt(1, 0, false);

        // Write across 4 distinct regions so read-channel eviction and read-ahead eviction both trigger
        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(
                regionDir,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                -4,
                24,
                3953,
                0,
                false,
                false,
                2,
                0.35)) {
            writer.writeChunkNbtBytes(0, 0, chunkNbt);
            writer.writeChunkNbtBytes(1, 0, chunkNbtNoStarlight);
            writer.writeChunkNbtBytes(32, 0, buildRichChunkNbt(32, 0, true));
            writer.writeChunkNbtBytes(64, 0, buildRichChunkNbt(64, 0, true));
            writer.writeChunkNbtBytes(96, 0, buildRichChunkNbt(96, 0, true));

            // Exercise OpenArchivePatchResult: NOT_OPEN, CHUNK_ABSENT, PATCHED
            assertEquals(
                    LcaRegionWriter.OpenArchivePatchResult.NOT_OPEN,
                    LcaRegionWriter.tryPatchOpenLiveArchive(null, 0, 0, List.of(), s -> true));
            assertEquals(
                    LcaRegionWriter.OpenArchivePatchResult.NOT_OPEN,
                    LcaRegionWriter.tryPatchOpenLiveArchive(
                            regionDir.resolve("r.99.99.lca"), 0, 0, List.of(), s -> true));

            Path r00 = regionDir.resolve("r.0.0.lca");
            LcaRegionWriter.LcaLiveArchive openR00 = writer.getOrOpenRegion(0, 0, false);
            assertNotNull(openR00);
            assertEquals(
                    LcaRegionWriter.OpenArchivePatchResult.CHUNK_ABSENT,
                    LcaRegionWriter.tryPatchOpenLiveArchive(
                            r00,
                            5,
                            5,
                            List.of(new RegionFileReader.BlockPatch(5 * 16, 10, 5 * 16, "minecraft:gold_block")),
                            s -> true));
            assertEquals(
                    LcaRegionWriter.OpenArchivePatchResult.PATCHED,
                    LcaRegionWriter.tryPatchOpenLiveArchive(
                            r00,
                            0,
                            0,
                            List.of(new RegionFileReader.BlockPatch(0, 4, 0, "minecraft:gold_block")),
                            s -> true));
        }

        // Reopen fresh read-only storage with maxOpenRegions=2 and low readAheadTrigger=2
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(
                regionDir,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                -4,
                24,
                3953,
                0,
                false,
                false,
                2,
                0.35)) {
            reader.configureBufferedIo(false, 4, 65536, true, 2);
            assertTrue(reader.isBufferedIoEnabled());

            assertTrue(reader.doesChunkExist(0, 0));
            assertFalse(reader.doesChunkExist(7, 7));
            assertFalse(reader.doesChunkExist(500, 500)); // populates knownMissingRegions
            assertFalse(reader.doesChunkExist(500, 500)); // fast-path knownMissingRegions hit
            assertNull(reader.getChunkDataInputStream(500, 500));
            assertNull(reader.getChunkDataInputStream(7, 7));

            // Read chunk (0,0) and (1,0) via DataInputStream, direct metadata, and metadata-only NBT
            try (DataInputStream dis = reader.getChunkDataInputStream(0, 0)) {
                assertNotNull(dis);
                byte[] nbtBytes = dis.readAllBytes();
                assertTrue(nbtBytes.length > 0);
            }
            // Repeated reads trigger sequential read-ahead promotion and eviction across regions
            for (int r = 0; r < 4; r++) {
                int cx = r * 32;
                for (int k = 0; k < 3; k++) {
                    try (DataInputStream dis = reader.getChunkDataInputStream(cx, 0)) {
                        assertNotNull(dis);
                        assertTrue(dis.readAllBytes().length > 0);
                    }
                }
            }
            // Patched chunk (0,0) carries a chunk patch tail, so direct metadata fails closed to null
            LcaRegionReader.DeferredChunkSlab patchedSlab = reader.readDeferredChunkSlab(0, 0, name -> 1);
            assertNotNull(patchedSlab);
            assertNull(patchedSlab.decodeToDirectMeta().directMeta());

            // Unpatched rich chunk (32,0) decodes full direct metadata with Starlight, heightmaps, biomes, and residual NBT
            LcaRegionReader.DeferredChunkSlab slab32 = reader.readDeferredChunkSlab(32, 0, name -> 1);
            assertNotNull(slab32);
            LcaRegionReader.RawChunkFrameWithDirectMeta direct32 = slab32.decodeToDirectMeta();
            assertNotNull(direct32);
            assertNotNull(direct32.directMeta());
            assertTrue(direct32.directMeta().hasIsLightOn());
            assertTrue(direct32.directMeta().hasStarlightVersion());
            assertTrue(direct32.directMeta().hasResidualNbt());
            assertTrue(direct32.directMeta().heightmapCodes().length >= 3);
            assertTrue(direct32.directMeta().sections().length >= 5);

            LcaRegionReader.DeferredChunkSlab slab1 = reader.readDeferredChunkSlab(1, 0, name -> 1);
            assertNotNull(slab1);
            LcaRegionReader.RawChunkFrameWithDirectMeta direct1 = slab1.decodeToDirectMeta();
            assertNotNull(direct1);
            assertNotNull(direct1.directMeta());
            assertFalse(direct1.directMeta().hasStarlightVersion());

            LcaRegionReader.RawChunkFrameWithMetadata withMeta = reader.readRawChunkFrameWithMetadata(32, 0, name -> 1);
            assertNotNull(withMeta);
            assertTrue(withMeta.metadataNbtBytes().length > 0);

            // Toggle buffered I/O off to cover clear path
            reader.setBufferedIoEnabled(false);
            assertFalse(reader.isBufferedIoEnabled());
        }
    }

    private static byte[] buildRichChunkNbt(int chunkX, int chunkZ, boolean withStarlight) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 3953);
        root.put("xPos", chunkX);
        root.put("zPos", chunkZ);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 12345L);
        root.put("InhabitedTime", 6789L);
        root.put("isLightOn", (byte) 1);
        if (withStarlight) {
            root.put("starlight.light_version", 2);
        }

        // Heightmaps: standard + custom
        LinkedHashMap<String, Object> heightmaps = new LinkedHashMap<>();
        long[] hmData = new long[37];
        Arrays.fill(hmData, 0x0102030405060708L);
        heightmaps.put("MOTION_BLOCKING", hmData);
        heightmaps.put("MOTION_BLOCKING_NO_LEAVES", hmData);
        heightmaps.put("WORLD_SURFACE", hmData);
        heightmaps.put("CUSTOM_TEST_HM", hmData);
        root.put("Heightmaps", heightmaps);

        // PostProcessing list
        List<Object> postProc = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            postProc.add(new ArrayList<Short>());
        }
        root.put("PostProcessing", postProc);

        // Residual NBT fields (block_entities, block_ticks, fluid_ticks, custom tag)
        LinkedHashMap<String, Object> be = new LinkedHashMap<>();
        be.put("id", "minecraft:chest");
        be.put("x", chunkX * 16 + 2);
        be.put("y", 4);
        be.put("z", chunkZ * 16 + 3);
        be.put("keepPacked", (byte) 0);
        root.put("block_entities", List.of(be));

        LinkedHashMap<String, Object> bTick = new LinkedHashMap<>();
        bTick.put("i", "minecraft:stone");
        bTick.put("x", chunkX * 16 + 1);
        bTick.put("y", 5);
        bTick.put("z", chunkZ * 16 + 1);
        bTick.put("t", 10);
        bTick.put("p", 0);
        root.put("block_ticks", List.of(bTick));

        LinkedHashMap<String, Object> fTick = new LinkedHashMap<>();
        fTick.put("i", "minecraft:water");
        fTick.put("x", chunkX * 16 + 2);
        fTick.put("y", 5);
        fTick.put("z", chunkZ * 16 + 2);
        fTick.put("t", 5);
        fTick.put("p", 0);
        root.put("fluid_ticks", List.of(fTick));
        root.put("CustomMarker", "leaf-test");

        List<Object> sections = new ArrayList<>();
        // Low boundary section (Y = -5): all-zero sky, all-15 block
        LinkedHashMap<String, Object> lowBound = new LinkedHashMap<>();
        lowBound.put("Y", (byte) -5);
        lowBound.put("SkyLight", new byte[2048]);
        byte[] all15 = new byte[2048];
        Arrays.fill(all15, (byte) 0xFF);
        lowBound.put("BlockLight", all15);
        sections.add(lowBound);

        // Section 0: 1-biome uniform + mixed stone/dirt blocks + uniform 0/15 light
        sections.add(buildSectionNbt(0, List.of("minecraft:plains"), false, false));

        // Section 1: 4-biome paletted (<= 8 biomes -> 1B RLE path) + gradient light
        sections.add(buildSectionNbt(
                1,
                List.of("minecraft:plains", "minecraft:forest", "minecraft:desert", "minecraft:taiga"),
                true,
                false));

        // Section 2: 10-biome paletted (> 8 biomes -> VarInt+len RLE path) + high-entropy raw light
        sections.add(buildSectionNbt(
                2,
                List.of(
                        "minecraft:plains", "minecraft:forest", "minecraft:desert", "minecraft:taiga",
                        "minecraft:swamp", "minecraft:jungle", "minecraft:savanna", "minecraft:badlands",
                        "minecraft:birch_forest", "minecraft:snowy_plains"),
                false,
                true));

        // High boundary section (Y = 20): all-15 sky, all-zero block
        LinkedHashMap<String, Object> highBound = new LinkedHashMap<>();
        highBound.put("Y", (byte) 20);
        highBound.put("SkyLight", all15);
        highBound.put("BlockLight", new byte[2048]);
        sections.add(highBound);

        root.put("sections", sections);
        return Nbt.writeNamedRoot("", root);
    }

    private static LinkedHashMap<String, Object> buildSectionNbt(
            int sectionY, List<String> biomeNames, boolean gradientLight, boolean highEntropyLight) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
        sec.put("Y", (byte) sectionY);

        LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
        airEntry.put("Name", "minecraft:air");
        LinkedHashMap<String, Object> stoneEntry = new LinkedHashMap<>();
        stoneEntry.put("Name", "minecraft:stone");
        LinkedHashMap<String, Object> dirtEntry = new LinkedHashMap<>();
        dirtEntry.put("Name", "minecraft:dirt");
        long[] blockData = new long[256];
        for (int i = 0; i < 4096; i++) {
            int ord = (i < 1024) ? 1 : ((i % 13 == 0) ? 2 : 0);
            int w = i >>> 4;
            int sh = (i & 15) << 2;
            blockData[w] |= ((long) ord) << sh;
        }
        LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
        blockStates.put("palette", List.of(airEntry, stoneEntry, dirtEntry));
        blockStates.put("data", blockData);
        sec.put("block_states", blockStates);

        LinkedHashMap<String, Object> biomes = new LinkedHashMap<>();
        biomes.put("palette", biomeNames);
        if (biomeNames.size() > 1) {
            int bits = BiomePaletteSection.biomeBitsPerEntry(biomeNames.size());
            int entriesPerLong = 64 / bits;
            int numLongs = (64 + entriesPerLong - 1) / entriesPerLong;
            long[] bData = new long[numLongs];
            for (int c = 0; c < 64; c++) {
                int bIdx = c % biomeNames.size();
                int w = c / entriesPerLong;
                int s = (c % entriesPerLong) * bits;
                bData[w] |= ((long) bIdx) << s;
            }
            biomes.put("data", bData);
        }
        sec.put("biomes", biomes);

        if (gradientLight) {
            byte[] sky = new byte[2048];
            byte[] blk = new byte[2048];
            for (int i = 0; i < 2048; i++) {
                int y = (i >>> 7) & 15;
                sky[i] = (byte) ((y << 4) | y);
                blk[i] = (byte) (((15 - y) << 4) | (15 - y));
            }
            sec.put("SkyLight", sky);
            sec.put("BlockLight", blk);
        } else if (highEntropyLight) {
            byte[] sky = new byte[2048];
            byte[] blk = new byte[2048];
            for (int i = 0; i < 2048; i++) {
                sky[i] = (byte) ((i * 73 + 19) & 0xFF);
                blk[i] = (byte) ((i * 41 + 7) & 0xFF);
            }
            sec.put("SkyLight", sky);
            sec.put("BlockLight", blk);
        } else {
            byte[] all15 = new byte[2048];
            Arrays.fill(all15, (byte) 0xFF);
            sec.put("SkyLight", all15);
            sec.put("BlockLight", new byte[2048]);
        }
        return sec;
    }

    @Test
    @DisplayName("LcaSectionBacking.fromRawBitStorageSnapshot and V1 native compact frame parsing preserve parity")
    void sectionBackingRawSnapshotAndNativeCompactFrames() {
        // 1. Uniform empty/null rawWords path (auto counts + explicit counts)
        LcaSectionBacking uniformAuto = LcaSectionBacking.fromRawBitStorageSnapshot(
                null, 0, new int[] {7}, 15, 0,
                id -> id != 0, id -> id == 7, id -> id == 9);
        assertEquals(7, uniformAuto.getStateId(0, 0, 0));
        assertEquals(4096, uniformAuto.nonEmptyBlockCount());
        assertEquals(4096, uniformAuto.tickingBlockCount());
        assertEquals(0, uniformAuto.tickingFluidCount());

        LcaSectionBacking uniformEmptyPal = LcaSectionBacking.fromRawBitStorageSnapshot(
                new long[0], 0, null, 15, 15, 0, 0, 0, 0,
                id -> id != 0, id -> false, id -> false, false);
        assertEquals(0, uniformEmptyPal.getStateId(0, 0, 0));

        // 2. Paletted rawWords that turn out uniform after unpack
        long[] allZeroWords = new long[256];
        LcaSectionBacking uniformAfterUnpack = LcaSectionBacking.fromRawBitStorageSnapshot(
                allZeroWords, 4, new int[] {5, 6}, 15, 0,
                id -> id != 0, id -> id == 5, id -> id == 6);
        assertEquals(5, uniformAfterUnpack.getStateId(3, 4, 5));
        assertEquals(4096, uniformAfterUnpack.nonEmptyBlockCount());

        // 3. Direct (null palette) rawWords with short array (triggers zero-fill tail) + prebuildSlices=true
        long[] directWords = new long[64];
        directWords[0] = 1L | (2L << 15) | (3L << 30);
        LcaSectionBacking directBacking = LcaSectionBacking.fromRawBitStorageSnapshot(
                directWords, 15, null, 15, 15, 4, -1, -1, -1,
                id -> id != 0, id -> id == 2, id -> id == 3, true);
        assertEquals(1, directBacking.getStateId(0, 0, 0));
        assertEquals(2, directBacking.getStateId(1, 0, 0));
        assertEquals(3, directBacking.getStateId(2, 0, 0));
        assertEquals(0, directBacking.getStateId(15, 15, 15));
        assertEquals(3, directBacking.nonEmptyBlockCount());
        assertEquals(1, directBacking.tickingBlockCount());
        assertEquals(1, directBacking.tickingFluidCount());

        // 4. Paletted rawWords with explicit counts and prebuildSlices=false
        long[] palWords = new long[256];
        palWords[0] = 1L | (2L << 4) | (9L << 8); // index 9 out of palette -> clamped to 0
        LcaSectionBacking palBacking = LcaSectionBacking.fromRawBitStorageSnapshot(
                palWords, 4, new int[] {10, 20, 30}, 15, 15, 0, 100, 10, 5,
                id -> id != 0, id -> id == 20, id -> id == 30, false);
        assertEquals(20, palBacking.getStateId(0, 0, 0));
        assertEquals(30, palBacking.getStateId(1, 0, 0));
        assertEquals(0, palBacking.getStateId(2, 0, 0));
        assertEquals(10, palBacking.getStateId(3, 0, 0));
        assertEquals(100, palBacking.nonEmptyBlockCount());

        // 5. Hand-crafted V1 native compact frames covering nibble-packed (<=16), byte-packed (17..256),
        //    and short-packed (>256) minority palettes in CompactBaseIndex.tryParseNativeCompactFrame
        for (int minorityCount : new int[] {2, 20, 260}) {
            byte[] v1Frame = buildV1NativeCompactFrame(10, minorityCount);
            LcaSectionBacking parsed = new LcaSectionBacking(v1Frame, 10);
            for (int m = 0; m < minorityCount; m++) {
                int yzx = m * 5 + 1;
                int x = yzx & 15;
                int z = (yzx >>> 4) & 15;
                int y = (yzx >>> 8) & 15;
                assertEquals(100 + m, parsed.getStateId(x, y, z), "Mismatch for minorityCount=" + minorityCount);
            }
            assertEquals(10, parsed.getStateId(0, 0, 0));
        }

        // 6. Malformed V1 native frames fail closed cleanly without throwing
        byte[][] malformed = {
            new byte[] {(byte) 0x80}, // truncated majority VarInt
            new byte[] {10},          // missing packedLight
            new byte[] {10, 0, (byte) 0x80}, // truncated minorityCount
            new byte[] {10, 0, 1, 20, 0},    // occurrences == 0
            new byte[] {10, 0, 1, 20, 2, 5, 0}, // non-positive delta for k>0
            new byte[] {10, 0, 2, 20, 1, 5, 21, 1, 5}, // duplicate voxel position
            new byte[] {10, 0, 1, 20, 1, 5, 99} // trailing garbage byte
        };
        for (byte[] bad : malformed) {
            LcaSectionBacking fallback = new LcaSectionBacking(bad, 10);
            assertEquals(10, fallback.getStateId(0, 0, 0));
        }
    }

    private static byte[] buildV1NativeCompactFrame(int majorityStateId, int minorityCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, majorityStateId);
        out.write((15 << 4) | 0); // packedLight (sky=15, blk=0)
        writeVarInt(out, minorityCount);
        for (int m = 0; m < minorityCount; m++) {
            writeVarInt(out, 100 + m); // stateId
            writeVarInt(out, 1);       // occurrences = 1
            writeVarInt(out, m * 5 + 1); // yzx delta from 0
        }
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int v) {
        int val = v;
        while ((val & ~0x7F) != 0) {
            out.write((val & 0x7F) | 0x80);
            val >>>= 7;
        }
        out.write(val);
    }

    @Test
    @DisplayName("LcaV2LegacyCodec.decodeChunkPatchTailAppendedStatesV2 decodes appended states and rejects corrupt inputs")
    void v2LegacyPatchTailAppendedStates() throws CorruptRegionEntryException {
        // appendedStateCount == 0 is a no-op
        assertEquals(3, LcaV2LegacyCodec.decodeChunkPatchTailAppendedStatesV2(
                new byte[4], 3, 4, 0, false, null, null, false));

        // Missing slab-local palette fails closed
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaV2LegacyCodec.decodeChunkPatchTailAppendedStatesV2(
                        new byte[4], 0, 4, 1, false, null, null, true));

        // Valid payload: 2 appended states (static core 0x80 + custom "minecraft:copper_block" via 0x40),
        // with 2 appended props: static suffix (0x80 | 0) on idx 1, and global state ref (0x40, id=0) on idx 2
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0x80); // static core id 0
        bos.write(0x40); // minecraft:-prefixed UTF8 string
        byte[] nameBytes = "copper_block".getBytes(StandardCharsets.UTF_8);
        writeVarInt(bos, nameBytes.length);
        bos.write(nameBytes, 0, nameBytes.length);
        writeVarInt(bos, 2); // appendedPropCount = 2
        writeVarInt(bos, 1); // stateIdx = 1 (baseCount=1 + 0)
        bos.write(0x80);     // static prop suffix 0
        writeVarInt(bos, 2); // stateIdx = 2 (baseCount=1 + 1)
        bos.write(0x40);     // global state ID tag
        writeVarInt(bos, 0); // globalId = 0
        byte[] payload = bos.toByteArray();

        LcaSectionCodec.SlabLocalPalette localPal = new LcaSectionCodec.SlabLocalPalette(
                new ArrayList<>(List.of("minecraft:air")),
                new ArrayList<>(List.of("minecraft:air")),
                new ArrayList<>(List.of(List.of("minecraft:air"))),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>());
        List<String> regionStates = List.of("minecraft:copper_block[waxed=true]");
        int endPos = LcaV2LegacyCodec.decodeChunkPatchTailAppendedStatesV2(
                payload, 0, payload.length, 2, true, localPal, regionStates, true);
        assertEquals(payload.length, endPos);
        assertEquals(3, localPal.canonicalStates.size());
        assertEquals("minecraft:copper_block[waxed=true]", localPal.canonicalStates.get(2));

        // Invalid property tag (0x20) fails closed
        byte[] badTagPayload = Arrays.copyOf(payload, payload.length);
        badTagPayload[badTagPayload.length - 2] = 0x20;
        LcaSectionCodec.SlabLocalPalette badPal = new LcaSectionCodec.SlabLocalPalette(
                new ArrayList<>(List.of("minecraft:air")),
                new ArrayList<>(List.of("minecraft:air")),
                new ArrayList<>(List.of(List.of("minecraft:air"))),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>());
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaV2LegacyCodec.decodeChunkPatchTailAppendedStatesV2(
                        badTagPayload, 0, badTagPayload.length, 2, true, badPal, regionStates, true));
    }

    @Test
    @DisplayName("LcaSectionCodec.isV3ExtendedSectionFrame classifies standard, V3 extended, and malformed frames")
    void isV3ExtendedSectionFrameClassification() {
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(null));
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(new byte[1]));
        // Truncated Delta-Roaring mask
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(
                new byte[] {(byte) (LcaSectionCodec.BLOCK_MODE_DELTA_ROARING << 4), 1, 2}));
        // Non-Gray-plane standard uniform frame -> false
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(new byte[] {0x00, 1, 0}));
        // Invalid secExtFlags (0) when paletteLen == 0 -> false
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(
                new byte[] {(byte) (LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK << 4), 0, 0, 2}));
        // Uniform block with Gray-plane sky light carrying Eikonal sentinel -> true
        byte[] uniformWithEikonal = new byte[] {
            (byte) ((LcaSectionCodec.BLOCK_MODE_UNIFORM << 4) | (LcaSectionCodec.LIGHT_MODE_GRAY_PLANES << 2)),
            1, 0, // paletteLen=1, dictId=0
            (byte) 0x80, // biome template 0
            (byte) LcaV3Codec.SENTINEL_MODE_EIKONAL, // Eikonal header byte (truncated -> false, fails closed)
        };
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(uniformWithEikonal));

        // Valid uniform block with Gray-plane sky light (mode 0 all-zero -> not Eikonal -> false)
        byte[] uniformGrayZero = new byte[] {
            (byte) ((LcaSectionCodec.BLOCK_MODE_UNIFORM << 4) | (LcaSectionCodec.LIGHT_MODE_GRAY_PLANES << 2)),
            1, 0,
            (byte) 0x80,
            0x00
        };
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(uniformGrayZero));

        // FlatBitPack with Gray-plane sky light (no Eikonal) -> false
        byte[] flatGray = new byte[1 + 8 + 3 + 512 + 1 + 1];
        flatGray[0] = (byte) ((LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK << 4)
                | (LcaSectionCodec.LIGHT_MODE_GRAY_PLANES << 2));
        flatGray[9] = 2; // paletteLen=2
        flatGray[10] = 0;
        flatGray[11] = 1;
        flatGray[12 + 512] = (byte) 0x80; // biome template 0
        flatGray[12 + 512 + 1] = 0x00;    // Gray planeModes8 = 0
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(flatGray));

        // Delta-Roaring frame containing all 4 container tags + inline >8 biomes + const blk + Gray sky
        byte[] allContainers = buildAllContainerPayload();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write((LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK << 4)
                | (LcaSectionCodec.LIGHT_MODE_GRAY_PLANES << 2)
                | LcaSectionCodec.LIGHT_MODE_CONST);
        writeVarInt(frame, 10); // paletteLen = 10 (1 majority + 9 minority containers)
        for (int i = 0; i < 10; i++) {
            writeVarInt(frame, i);
        }
        frame.write(allContainers, 0, allContainers.length);
        // Inline biome payload with bLen = 10 (> 8 path)
        writeVarInt(frame, 10);
        for (int i = 0; i < 10; i++) {
            writeVarInt(frame, i);
        }
        writeVarInt(frame, 1); // runCount = 1
        writeVarInt(frame, 0); // pIdx = 0
        frame.write(63);       // len - 1 = 63
        frame.write(0x0F);     // const light byte (blkMode == CONST)
        frame.write(0x00);     // skyMode == GRAY_PLANES planeModes8 = 0
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(frame.toByteArray()));

        // Same container payload with <= 8 inline biomes (bLen = 3)
        ByteArrayOutputStream frame2 = new ByteArrayOutputStream();
        frame2.write((LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK << 4)
                | (LcaSectionCodec.LIGHT_MODE_GRAY_PLANES << 2));
        writeVarInt(frame2, 10);
        for (int i = 0; i < 10; i++) writeVarInt(frame2, i);
        frame2.write(allContainers, 0, allContainers.length);
        writeVarInt(frame2, 3); // bLen = 3 (<= 8)
        for (int i = 0; i < 3; i++) writeVarInt(frame2, i);
        writeVarInt(frame2, 2); // runCount = 2 (2 bytes)
        frame2.write(0x1F);
        frame2.write(0x3F);
        frame2.write(0x00); // sky Gray planeModes8 = 0
        assertFalse(LcaSectionCodec.isV3ExtendedSectionFrame(frame2.toByteArray()));
    }

    private static byte[] buildAllContainerPayload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 1. TAG_SINGLETON subTag 0 (single idx = 5)
        out.write((LcaSectionCodec.TAG_SINGLETON << 6));
        out.write(5);
        // 2. TAG_SINGLETON subTag 1 (1B runLen: idx=10, runLen=4 -> byte=2)
        out.write((LcaSectionCodec.TAG_SINGLETON << 6) | (1 << 4));
        out.write(10);
        out.write(2);
        // 3. TAG_SINGLETON subTag 2 (2B runLen: idx=1000=0x03E8, runLen=260 -> 258 = 0x0102)
        out.write((LcaSectionCodec.TAG_SINGLETON << 6) | (2 << 4) | 0x03);
        out.write(0xE8);
        out.write(0x01);
        out.write(0x02);
        // 4. TAG_SINGLETON subTag 3 (two-run with 2B gap >= 240: idx=1300=0x0514, lens=0x11, gap=250 -> 0xF0, 0xFA)
        out.write((LcaSectionCodec.TAG_SINGLETON << 6) | (3 << 4) | 0x05);
        out.write(0x14);
        out.write(0x11); // run0=2, run1=2
        out.write(0xF0);
        out.write(0xFA);
        // 5. TAG_BITMAP dense 512B
        out.write((LcaSectionCodec.TAG_BITMAP << 6) | (512 >>> 8));
        out.write(512 & 0xFF);
        byte[] dense512 = new byte[512];
        dense512[7] = 0x01; // bit 0 of word 0
        out.write(dense512, 0, dense512.length);
        // 6. TAG_BITMAP compact (0x1000 | 16 bytes = 2 words)
        int compactHdr = (LcaSectionCodec.TAG_BITMAP << 14) | 0x1000 | 16;
        out.write(compactHdr >>> 8);
        out.write(compactHdr & 0xFF);
        byte[] compact16 = new byte[16];
        compact16[15] = 0x02; // bit 1 of word 1 (idx 65)
        out.write(compact16, 0, compact16.length);
        // 7. TAG_BITMAP sparse (1 non-zero word: wordMask64=bit 2, bits64=bit 3 -> idx 131)
        int sparseHdr = (LcaSectionCodec.TAG_BITMAP << 14) | 1;
        out.write(sparseHdr >>> 8);
        out.write(sparseHdr & 0xFF);
        for (int b = 7; b >= 0; b--) out.write((b == 0) ? 0x04 : 0); // wordMask64 = 1L << 2
        for (int b = 7; b >= 0; b--) out.write((b == 0) ? 0x08 : 0); // bits64 = 1L << 3
        // 8. TAG_DELTA_ARRAY (2 entries: idx 700 via 2B gap 0xF2, 0xBC; then gap 10)
        int arrHdr = (LcaSectionCodec.TAG_DELTA_ARRAY << 14) | 2;
        out.write(arrHdr >>> 8);
        out.write(arrHdr & 0xFF);
        out.write(0xF2);
        out.write(0xBC); // 700
        out.write(10);   // 711
        // 9. TAG_DELTA_RUN (subMode=0: gMax=15, lMax=15; 2 runs exercising 2B gap overflow + 2B VarInt run length)
        int runHdr = (LcaSectionCodec.TAG_DELTA_RUN << 14) | 2;
        out.write(runHdr >>> 8);
        out.write(runHdr & 0xFF);
        // Run 0: adjGap=15 + 2B(0xF3, 0x00 -> 768) => start=783, lenM1=15 + 2B VarInt(130 -> 0x82, 0x01) => end=929
        out.write(0xFF);
        out.write(0xF3);
        out.write(0x00);
        out.write(0x82);
        out.write(0x01);
        // Run 1: adjGap=15 + 1B(5), lenM1=15 + 1B(3) => start=950, len=19
        out.write(0xFF);
        out.write(5);
        out.write(3);
        return out.toByteArray();
    }

    @Test
    @DisplayName("skipSectionBlockPayload and LcaModeABlockSection.containerContains cover all container tags without cache")
    void skipSectionBlockPayloadAndDirectContainerContains() throws CorruptRegionEntryException {
        byte[] allContainers = buildAllContainerPayload();
        assertEquals(
                allContainers.length,
                LcaSectionCodec.skipSectionBlockPayload(
                        allContainers, 0, allContainers.length, LcaSectionCodec.BLOCK_MODE_DELTA_ROARING, 0, 10));

        // FlatBitPack valid and truncated
        assertEquals(
                512,
                LcaSectionCodec.skipSectionBlockPayload(
                        new byte[512], 0, 512, LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK, 0, 2));
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaSectionCodec.skipSectionBlockPayload(
                        new byte[100], 0, 100, LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK, 0, 2));

        // Gray bitplanes valid and truncated
        byte[] grayOk = new byte[] {4, 1, 2, 3, 4};
        assertEquals(
                5,
                LcaSectionCodec.skipSectionBlockPayload(
                        grayOk,
                        0,
                        grayOk.length,
                        LcaSectionCodec.BLOCK_MODE_DELTA_ROARING,
                        LcaV3Codec.SEC_EXT_GRAY_BITPLANES,
                        4));
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaSectionCodec.skipSectionBlockPayload(
                        new byte[] {10, 1, 2},
                        0,
                        3,
                        LcaSectionCodec.BLOCK_MODE_DELTA_ROARING,
                        LcaV3Codec.SEC_EXT_GRAY_BITPLANES,
                        4));

        // Unsupported blockMode and truncated Delta-Roaring fail closed
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaSectionCodec.skipSectionBlockPayload(
                        allContainers, 0, allContainers.length, LcaSectionCodec.BLOCK_MODE_UNIFORM, 0, 2));
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaSectionCodec.skipSectionBlockPayload(
                        allContainers, 0, allContainers.length - 1, LcaSectionCodec.BLOCK_MODE_DELTA_ROARING, 0, 10));

        // Parse the same 9-container frame via LcaModeABlockSection and probe containerContains directly (cache cleared)
        ByteArrayOutputStream secFrame = new ByteArrayOutputStream();
        secFrame.write((LcaSectionCodec.CURVE_YZX << 6) | (LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK << 4));
        writeVarInt(secFrame, 10);
        for (int i = 0; i < 10; i++) writeVarInt(secFrame, i);
        secFrame.write(allContainers, 0, allContainers.length);
        byte[] frameBytes = secFrame.toByteArray();
        int[] palMap = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109};
        LcaModeABlockSection sec = LcaModeABlockSection.tryExtractFromChunkFrame(frameBytes, 0, frameBytes.length, palMap);
        assertNotNull(sec);

        // Probe hits and misses across every single container with cache cleared before each query
        int[] probeCoords = {0, 5, 10, 12, 65, 131, 700, 711, 783, 955, 1000, 1150, 1300, 1553, 2000, 4095};
        int[] materialized = new int[4096];
        sec.materializeOrdinals(materialized);
        for (int yzx : probeCoords) {
            LcaModeABlockSection.clearThreadLocalScratch();
            assertEquals(materialized[yzx], sec.queryVoxelOrdinal(yzx), "Direct containerContains mismatch at " + yzx);
        }
        int[] counts = new int[10];
        sec.countOrdinals(counts);
        assertTrue(counts[1] == 1 && counts[2] == 4 && counts[3] == 260 && counts[4] == 4);
    }

    @Test
    @DisplayName("Columnar block_entities codec round-trips mixed coordTypes and rejects corrupt buffers")
    void columnarBlockEntitiesMixedCoordTypesAndGuards() throws IOException {
        // 1. Multi-template with mixed out-of-chunk XYZ (coordType=0), Pos[3] (coordType=1), and no-coord (coordType=2)
        LinkedHashMap<String, Object> beOutXyz = new LinkedHashMap<>();
        beOutXyz.put("id", "minecraft:chest");
        beOutXyz.put("x", 999);
        beOutXyz.put("y", 64);
        beOutXyz.put("z", -500);
        beOutXyz.put("keepPacked", (byte) 0);

        LinkedHashMap<String, Object> bePosArr = new LinkedHashMap<>();
        bePosArr.put("id", "minecraft:sign");
        bePosArr.put("Pos", new int[] {12, 70, 14});

        LinkedHashMap<String, Object> beNoCoord = new LinkedHashMap<>();
        beNoCoord.put("id", "minecraft:banner");
        beNoCoord.put("CustomName", "Banner");

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("block_entities", List.of(beOutXyz, bePosArr, beNoCoord));
        byte[] nbtRoot = Nbt.writeNamedRoot("", root);
        // Skip root TAG_COMPOUND (1B) + empty root name len (2B) -> tagStart = 3, tagEnd = nbtRoot.length - 1
        ByteBuffer src = ByteBuffer.wrap(nbtRoot);
        byte[] encoded = LcaV3Codec.tryEncodeColumnarBlockEntitiesFromNbtSlice(src, 3, nbtRoot.length - 1, 0, 0);
        assertNotNull(encoded);

        LcaSectionCodec.ScratchArena arena = LcaSectionCodec.scratch();
        int outLen = LcaV3Codec.decodeColumnarBlockEntitiesToNbt(encoded, 0, encoded.length, 0, 0, arena, 0);
        assertTrue(outLen > 20);

        // 2. All in-chunk XYZ with 2 distinct templates (exercises allInChunkXYZ=true, multiTmpl=true)
        LinkedHashMap<String, Object> beIn1 = new LinkedHashMap<>();
        beIn1.put("id", "minecraft:chest");
        beIn1.put("x", 2);
        beIn1.put("y", 10);
        beIn1.put("z", 3);
        LinkedHashMap<String, Object> beIn2 = new LinkedHashMap<>();
        beIn2.put("id", "minecraft:furnace");
        beIn2.put("x", 4);
        beIn2.put("y", 12);
        beIn2.put("z", 5);
        LinkedHashMap<String, Object> root2 = new LinkedHashMap<>();
        root2.put("block_entities", List.of(beIn1, beIn2));
        byte[] nbtRoot2 = Nbt.writeNamedRoot("", root2);
        byte[] encoded2 = LcaV3Codec.tryEncodeColumnarBlockEntitiesFromNbtSlice(
                ByteBuffer.wrap(nbtRoot2), 3, nbtRoot2.length - 1, 0, 0);
        assertNotNull(encoded2);
        int outLen2 = LcaV3Codec.decodeColumnarBlockEntitiesToNbt(encoded2, 0, encoded2.length, 0, 0, arena, 0);
        assertTrue(outLen2 > 20);

        // 3. Corrupt inputs fail closed
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaV3Codec.decodeColumnarBlockEntitiesToNbt(new byte[] {0, 0}, 0, 2, 0, 0, arena, 0));
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaV3Codec.decodeColumnarBlockEntitiesToNbt(new byte[] {1, 0x02, 1}, 0, 3, 0, 0, arena, 0));
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaV3Codec.decodeColumnarBlockEntitiesToNbt(new byte[] {1, 0x00, 0}, 0, 3, 0, 0, arena, 0));
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaV3Codec.decodeColumnarBlockEntitiesToNbt(
                        new byte[] {1, 0x00, 1, 1, 0x01}, 0, 5, 0, 0, arena, 0)); // template not terminated by TAG_END
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaV3Codec.decodeColumnarBlockEntitiesToNbt(
                        new byte[] {1, 0x00, 1, 1, 0x00, 0x03}, 0, 6, 0, 0, arena, 0)); // coordType == 3 invalid
    }

    @Test
    @DisplayName("Streaming encoder handles non-ASCII modified UTF-8, scalar properties, legacy Level chunks, and ByteBuffer writes")
    void streamingEncoderUtf8ScalarPropsLegacyLevelAndByteBufferWrites(@TempDir Path regionDir) throws IOException {
        // 1. Chunk with non-ASCII 2-byte and 3-byte UTF-8 strings + numeric scalar block_state properties
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 3953);
        root.put("xPos", 2);
        root.put("zPos", 3);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        root.put("CustomUtf8", "café-leaf⚡-test");

        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        props.put("lit", (byte) 1);
        props.put("age", (short) 3);
        props.put("level", 7);
        props.put("seed", 99L);
        props.put("ratio", 0.5f);
        props.put("weight", 1.25d);
        props.put("facing", "north");

        LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
        airEntry.put("Name", "minecraft:air");
        LinkedHashMap<String, Object> propEntry = new LinkedHashMap<>();
        propEntry.put("Name", "minecraft:redstone_wire");
        propEntry.put("Properties", props);

        long[] bData = new long[256];
        bData[0] = 1L;
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        bs.put("palette", List.of(airEntry, propEntry));
        bs.put("data", bData);

        LinkedHashMap<String, Object> sec0 = new LinkedHashMap<>();
        sec0.put("Y", (byte) 0);
        sec0.put("block_states", bs);
        root.put("sections", List.of(sec0));
        byte[] utf8Chunk = Nbt.writeNamedRoot("", root);

        // 2. Legacy pre-1.18 Level-wrapped chunk NBT (exercises scanLevelCoords)
        LinkedHashMap<String, Object> legacyLevel = new LinkedHashMap<>();
        legacyLevel.put("xPos", 4);
        legacyLevel.put("zPos", 5);
        legacyLevel.put("Status", "full");
        LinkedHashMap<String, Object> legacyRoot = new LinkedHashMap<>();
        legacyRoot.put("DataVersion", 2586);
        legacyRoot.put("Level", legacyLevel);
        byte[] legacyBytes = Nbt.writeNamedRoot("", legacyRoot);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, false)) {
            // writeChunkNbtBuffer: heap, direct, direct-CoW-marker skip, null/empty delete
            assertTrue(storage.writeChunkNbtBuffer(2, 3, ByteBuffer.wrap(utf8Chunk)) > 0);

            ByteBuffer directBuf = ByteBuffer.allocateDirect(legacyBytes.length);
            directBuf.put(legacyBytes).flip();
            assertTrue(storage.writeChunkNbtBuffer(4, 5, directBuf) > 0);

            byte[] marker = (LcaRegionFileStorage.DIRECT_COW_COMMITTED_TAG + "skip").getBytes(StandardCharsets.UTF_8);
            assertEquals(0, storage.writeChunkNbtBuffer(2, 3, ByteBuffer.wrap(marker)));

            // Exercise all cached-live-archive read methods
            assertNotNull(storage.readRawChunkFrame(2, 3, name -> 1));
            assertNotNull(storage.readRawChunkFrameWithMetadata(2, 3, name -> 1));
            assertNotNull(storage.readRawChunkFrameWithDirectMeta(2, 3, name -> 1));
            assertNotNull(storage.readDeferredChunkSlab(2, 3, name -> 1));
            assertNotNull(storage.readChunkNbtBytes(2, 3));
            Map<String, Object> decoded = storage.readChunkCompound(2, 3);
            assertNotNull(decoded);
            assertEquals("café-leaf⚡-test", decoded.get("CustomUtf8"));

            // Delete via empty ByteBuffer
            assertEquals(0, storage.writeChunkNbtBuffer(4, 5, ByteBuffer.allocate(0)));
            assertFalse(storage.doesChunkExist(4, 5));
            assertEquals(0, storage.writeChunkNbtBuffer(4, 5, null));
        }

        // Reopen cold read-only storage to exercise non-cached paths of readRawChunkFrame / readChunkCompound / readChunkNbtBytes
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(regionDir, false)) {
            assertNotNull(reader.readRawChunkFrame(2, 3, name -> 1));
            assertNotNull(reader.readRawChunkFrameWithDirectMeta(2, 3, name -> 1));
            assertNotNull(reader.readChunkNbtBytes(2, 3));
            assertNotNull(reader.readChunkCompound(2, 3));
            assertNull(reader.readRawChunkFrame(999, 999, name -> 1));
            assertNull(reader.readRawChunkFrameWithMetadata(999, 999, name -> 1));
            assertNull(reader.readRawChunkFrameWithDirectMeta(999, 999, name -> 1));
            assertNull(reader.readDeferredChunkSlab(999, 999, name -> 1));
            assertNull(reader.readChunkNbtBytes(999, 999));
            assertNull(reader.readChunkCompound(999, 999));
        }
    }
}

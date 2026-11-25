package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies exact bit-for-bit parity of {@link LcaModeABlockSection} direct queries, container
 * header counting, selective coordinate emission, materialization, and {@link RawChunkFrameSnapshot}
 * extraction across all standard LCA Mode A curves and container tags.
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaModeABlockSectionTest {

    private static void assertSectionMatchesVoxels(LcaModeABlockSection sec, int[] expected) {
        assertNotNull(sec);
        assertEquals(4096, expected.length);

        // 1. Direct queryVoxelRuntimeId parity on all 4,096 voxels
        for (int yzx = 0; yzx < 4096; yzx++) {
            assertEquals(expected[yzx], sec.queryVoxelRuntimeId(yzx), "Mismatch at yzx=" + yzx);
        }

        // 2. countOrdinals parity (zero 4,096 unpack)
        int[] counts = new int[sec.paletteLength()];
        sec.countOrdinals(counts);
        int[] expectedCounts = new int[sec.paletteLength()];
        int totalCount = 0;
        for (int i = 0; i < counts.length; i++) {
            totalCount += counts[i];
        }
        assertEquals(4096, totalCount);
        for (int yzx = 0; yzx < 4096; yzx++) {
            int ord = sec.queryVoxelOrdinal(yzx);
            expectedCounts[ord]++;
        }
        assertArrayEquals(expectedCounts, counts, "countOrdinals mismatch");

        // 3. materializeOrdinals parity
        int[] mat = new int[4096];
        sec.materializeOrdinals(mat);
        for (int yzx = 0; yzx < 4096; yzx++) {
            assertEquals(expected[yzx], sec.paletteRuntimeId(mat[yzx]), "materialize mismatch at yzx=" + yzx);
        }

        // 4. forEachMatchingCoordinate parity for minority-only predicate (e.g. randomly ticking)
        boolean[] minorityPred = new boolean[sec.paletteLength()];
        for (int p = 1; p < minorityPred.length; p += 2) {
            minorityPred[p] = true;
        }
        BitSet actualMinorityCoords = new BitSet(4096);
        sec.forEachMatchingCoordinate(minorityPred, actualMinorityCoords::set);
        BitSet expectedMinorityCoords = new BitSet(4096);
        for (int yzx = 0; yzx < 4096; yzx++) {
            int ord = mat[yzx];
            if (minorityPred[ord]) {
                expectedMinorityCoords.set(yzx);
            }
        }
        assertEquals(expectedMinorityCoords, actualMinorityCoords, "Minority forEachMatchingCoordinate mismatch");

        // 5. forEachMatchingCoordinate parity when majority ordinal 0 also matches
        boolean[] majorityPred = new boolean[sec.paletteLength()];
        majorityPred[0] = true;
        BitSet actualMajCoords = new BitSet(4096);
        sec.forEachMatchingCoordinate(majorityPred, actualMajCoords::set);
        BitSet expectedMajCoords = new BitSet(4096);
        for (int yzx = 0; yzx < 4096; yzx++) {
            if (mat[yzx] == 0) {
                expectedMajCoords.set(yzx);
            }
        }
        assertEquals(expectedMajCoords, actualMajCoords, "Majority forEachMatchingCoordinate mismatch");
    }

    @Test
    @DisplayName("encodeFromRuntimeVoxels covers uniform, sparse singletons, runs, delta-arrays, bitmaps, and dense flat fallback")
    void encodeAndQueryAllModeAPatterns() {
        // Uniform -> null
        int[] uniform = new int[4096];
        Arrays.fill(uniform, 42);
        assertNull(LcaModeABlockSection.encodeFromRuntimeVoxels(uniform));

        // Pattern A: Sparse terrain with singletons, two-runs, short runs, and octant compaction
        int[] sparse = new int[4096];
        Arrays.fill(sparse, 10);
        sparse[5] = 20; // singleton
        for (int i = 100; i < 112; i++) sparse[i] = 30; // single run
        for (int i = 300; i < 305; i++) sparse[i] = 40; // two-run
        for (int i = 320; i < 326; i++) sparse[i] = 40;
        for (int i = 0; i < 40; i++) sparse[1024 + i * 7] = 50; // delta array
        LcaModeABlockSection secA = LcaModeABlockSection.encodeFromRuntimeVoxels(sparse);
        assertSectionMatchesVoxels(secA, sparse);
        assertTrue(secA.payloadByteLength() < 256, "Sparse Mode A payload must stay compact: " + secA.payloadByteLength());

        // Pattern B: Layered Overworld surface (10 states, full octree mask, Delta-Roaring + runs + bitmaps)
        int[] surface = new int[4096];
        Random rng = new Random(0x5EED1234L);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int yzx = (y << 8) | (z << 4) | x;
                    if (y < 6) surface[yzx] = 1; // stone
                    else if (y < 9) surface[yzx] = (rng.nextInt(8) == 0) ? 4 : 2; // dirt + gravel
                    else if (y == 9) surface[yzx] = 3; // grass_block
                    else if (y == 10 && rng.nextInt(5) == 0) surface[yzx] = 5 + rng.nextInt(4); // flowers/grass
                    else surface[yzx] = 0; // air
                }
            }
        }
        LcaModeABlockSection secB = LcaModeABlockSection.encodeFromRuntimeVoxels(surface);
        assertSectionMatchesVoxels(secB, surface);
        assertTrue(secB.payloadByteLength() < 1200, "Layered surface Mode A payload should beat flat 4-bit (2048 B): " + secB.payloadByteLength());

        // Pattern C: High-entropy random 16-state noise (triggers BLOCK_MODE_FLAT_BITPACK ceiling)
        int[] noisy = new int[4096];
        for (int i = 0; i < 4096; i++) {
            noisy[i] = 100 + rng.nextInt(16);
        }
        LcaModeABlockSection secC = LcaModeABlockSection.encodeFromRuntimeVoxels(noisy);
        assertSectionMatchesVoxels(secC, noisy);
        assertEquals(LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK, secC.blockMode());
        assertEquals(4, secC.flatBits());
        assertEquals(2048, secC.payloadByteLength());

        // Pattern D: >32 distinct states (exercises hash-table palette builder)
        int[] highCard = new int[4096];
        Arrays.fill(highCard, 1);
        for (int i = 0; i < 64; i++) {
            highCard[i * 19] = 1000 + i;
        }
        LcaModeABlockSection secD = LcaModeABlockSection.encodeFromRuntimeVoxels(highCard);
        assertSectionMatchesVoxels(secD, highCard);
    }

    @Test
    @DisplayName("RawChunkFrameSnapshot#tryExtractModeABlockSection reads standard Mode A .lca disk sections with zero disk bloat")
    void rawChunkFrameSnapshotExtractsStandardModeADiskSection(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("mode_a_direct_extract");
        Files.createDirectories(regionDir);

        // Build a chunk with a realistic non-uniform layered section (4 states)
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4189);
        root.put("xPos", 0);
        root.put("zPos", 0);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 10L);
        root.put("InhabitedTime", 5L);

        List<LinkedHashMap<String, Object>> palette = new ArrayList<>();
        String[] names = {"minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:air"};
        for (String name : names) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", name);
            palette.add(entry);
        }
        int[] expectedRuntime = new int[4096];
        int[] ordinals = new int[4096];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int yzx = (y << 8) | (z << 4) | x;
                    int ord = y < 10 ? 0 : (y < 12 ? 1 : (y == 12 ? 2 : 3));
                    ordinals[yzx] = ord;
                    expectedRuntime[yzx] = ord + 100;
                }
            }
        }
        long[] packedLongs = LcaSectionCodec.packBlockLongs(ordinals, 4);
        LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
        blockStates.put("palette", palette);
        blockStates.put("data", packedLongs);

        LinkedHashMap<String, Object> sec0 = new LinkedHashMap<>();
        sec0.put("Y", (byte) 0);
        sec0.put("block_states", blockStates);
        root.put("sections", List.of(sec0));

        Function<String, Integer> lookup = name -> switch (name) {
            case "minecraft:stone" -> 100;
            case "minecraft:dirt" -> 101;
            case "minecraft:grass_block" -> 102;
            case "minecraft:air" -> 103;
            default -> 0;
        };

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, false)) {
            assertFalse(storage.isDirectFlatBitPack());
            storage.writeChunkNbtBytes(0, 0, Nbt.writeNamedRoot("", root));
            RawChunkFrameSnapshot snap = storage.readRawChunkFrame(0, 0, lookup);
            assertNotNull(snap);

            int secIdx = 0 - snap.minSectionY();
            LcaModeABlockSection extracted = snap.tryExtractModeABlockSection(secIdx, lookup);
            assertSectionMatchesVoxels(extracted, expectedRuntime);
            assertNotEquals(LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK, extracted.blockMode(),
                    "Standard Mode A should encode layered terrain with Delta-Roaring, not FlatBitPack");
            assertTrue(extracted.payloadByteLength() < 128,
                    "Layered section should compress to < 128 B in Mode A, got " + extracted.payloadByteLength());
        }
    }

    @Test
    @DisplayName("Direct SWAR Gray-plane light decoding matches reference decoder bit-for-bit across gradients and refCodes")
    void directSwarGrayPlaneLightDecodingMatchesBitForBit(@TempDir Path tempDir) throws Exception {
        Path regionDir = tempDir.resolve("gray_plane_swar");
        Files.createDirectories(regionDir);

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4189);
        root.put("xPos", 0);
        root.put("zPos", 0);
        root.put("yPos", -4);
        root.put("Status", "minecraft:full");
        root.put("LastUpdate", 10L);
        root.put("InhabitedTime", 5L);

        List<LinkedHashMap<String, Object>> palette = new ArrayList<>();
        LinkedHashMap<String, Object> stone = new LinkedHashMap<>();
        stone.put("Name", "minecraft:stone");
        palette.add(stone);
        LinkedHashMap<String, Object> blockStates = new LinkedHashMap<>();
        blockStates.put("palette", palette);

        // 4 distinct lighting patterns that trigger mode=1 refCode=0/1/2, split62, all-1s (0xFF), and single-run planes
        byte[][] expectedSky = new byte[4][2048];
        byte[][] expectedBlk = new byte[4][2048];
        Random rng = new Random(0xC0FFEE42L);
        List<LinkedHashMap<String, Object>> sections = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int yzx = (y << 8) | (z << 4) | x;
                        int skyNib = switch (s) {
                            case 0 -> Math.min(15, Math.max(0, y)); // smooth vertical gradient (triggers refCode=1/2)
                            case 1 -> (y >= 8) ? 15 : Math.max(0, 15 - (8 - y) * 2 - (x >> 2)); // cave mouth gradient
                            case 2 -> ((x + z) < 16) ? 14 : 6; // high-value plateau (all-1s plane 3 + refCode=1/2)
                            default -> (y == 5 && x > 4 && x < 12) ? 12 : ((y > 5) ? 15 : rng.nextInt(4));
                        };
                        int blkNib = switch (s) {
                            case 0 -> Math.max(0, 14 - Math.abs(x - 8) - Math.abs(z - 8) - Math.abs(y - 8)); // torch falloff
                            case 1 -> (y < 4) ? 15 : ((y < 8) ? 7 : 0); // lava pool glow
                            case 2 -> (x & 1) == 0 ? 11 : 10; // alternating Gray bit 0 with shared upper planes
                            default -> Math.max(0, 15 - ((x + y + z) >> 1));
                        };
                        int byteIdx = yzx >>> 1;
                        if ((yzx & 1) == 0) {
                            expectedSky[s][byteIdx] = (byte) ((expectedSky[s][byteIdx] & 0xF0) | (skyNib & 0x0F));
                            expectedBlk[s][byteIdx] = (byte) ((expectedBlk[s][byteIdx] & 0xF0) | (blkNib & 0x0F));
                        } else {
                            expectedSky[s][byteIdx] = (byte) ((expectedSky[s][byteIdx] & 0x0F) | ((skyNib & 0x0F) << 4));
                            expectedBlk[s][byteIdx] = (byte) ((expectedBlk[s][byteIdx] & 0x0F) | ((blkNib & 0x0F) << 4));
                        }
                    }
                }
            }
            LinkedHashMap<String, Object> sec = new LinkedHashMap<>();
            sec.put("Y", (byte) s);
            sec.put("block_states", blockStates);
            sec.put("SkyLight", expectedSky[s]);
            sec.put("BlockLight", expectedBlk[s]);
            sections.add(sec);
        }
        root.put("sections", sections);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, false)) {
            storage.writeChunkNbtBytes(0, 0, Nbt.writeNamedRoot("", root));
            LcaRegionReader.DeferredChunkSlab slab = storage.readDeferredChunkSlab(0, 0, name -> 1);
            assertNotNull(slab);
            LcaDecodedChunkMeta meta = slab.decodeToDirectMeta().directMeta();
            assertNotNull(meta);
            for (int s = 0; s < 4; s++) {
                LcaDecodedChunkMeta.DecodedSectionMeta decSec = null;
                for (LcaDecodedChunkMeta.DecodedSectionMeta candidate : meta.sections()) {
                    if (candidate.sectionY() == s) {
                        decSec = candidate;
                        break;
                    }
                }
                assertNotNull(decSec, "Missing decoded section Y=" + s);
                assertEquals(LcaDecodedChunkMeta.LIGHT_ARRAY, decSec.skyLightKind());
                assertEquals(LcaDecodedChunkMeta.LIGHT_ARRAY, decSec.blockLightKind());
                assertArrayEquals(expectedSky[s], decSec.skyLightBytes(), "SkyLight mismatch at section Y=" + s);
                assertArrayEquals(expectedBlk[s], decSec.blockLightBytes(), "BlockLight mismatch at section Y=" + s);
            }
        }
    }

    @Test
    @DisplayName("ThreadLocal 4-slot L1 ordinal cache preserves exact parity across alternating pairs and slot eviction")
    void threadLocalReadCacheMaintainsParityAcrossAlternatingPairsAndEviction() {
        int[][] expected = new int[6][4096];
        LcaModeABlockSection[] sections = new LcaModeABlockSection[6];
        Random rng = new Random(0x13579BDF0L);
        for (int s = 0; s < 6; s++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int yzx = (y << 8) | (z << 4) | x;
                        expected[s][yzx] = (y < 6 + (s % 4)) ? 10 : ((rng.nextInt(11) == 0) ? (20 + (s * 3) + rng.nextInt(5)) : 11);
                    }
                }
            }
            sections[s] = LcaModeABlockSection.encodeFromRuntimeVoxels(expected[s]);
            assertNotNull(sections[s]);
            assertNotEquals(LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK, sections[s].blockMode());
        }

        // Simulate Starlight checkChunkEdge alternating across adjacent pairs (0,1), (2,3), (4,5) -> triggers promotion + eviction
        for (int pair = 0; pair < 3; pair++) {
            int a = pair * 2;
            int b = a + 1;
            for (int yzx = 0; yzx < 4096; yzx++) {
                assertEquals(expected[a][yzx], sections[a].queryVoxelRuntimeId(yzx), "Pair A mismatch s=" + a + " yzx=" + yzx);
                assertEquals(expected[b][yzx], sections[b].queryVoxelRuntimeId(yzx), "Pair B mismatch s=" + b + " yzx=" + yzx);
            }
        }
        // Re-query evicted section 0 to verify clean re-promotion
        for (int yzx = 0; yzx < 4096; yzx += 17) {
            assertEquals(expected[0][yzx], sections[0].queryVoxelRuntimeId(yzx));
        }
        LcaModeABlockSection.clearThreadLocalScratch();
        LcaSectionBacking.clearThreadLocalScratch();
        LcaSectionCodec.clearThreadLocalScratch();
        assertEquals(expected[0][0], sections[0].queryVoxelRuntimeId(0));
    }

    @Test
    @DisplayName("toWireSectionFrameBytes and encodeFlatBitpackFromSimpleBitStorage round-trip all bit widths and fail closed")
    void toWireAndEncodeFlatBitpackFromSimpleBitStorageParity() {
        // Empty palette guard
        assertThrows(
                IllegalArgumentException.class,
                () -> new LcaModeABlockSection(
                        LcaSectionCodec.CURVE_YZX,
                        LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK,
                        1,
                        0L,
                        new int[0],
                        new byte[512],
                        null));

        // Fail-closed guards on encodeFlatBitpackFromSimpleBitStorage
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(null, 4, new long[256]));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(new int[] {1, 2}, 4, null));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(new int[] {1}, 4, new long[256]));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(new int[257], 4, new long[256]));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(new int[] {1, 2}, 3, new long[256]));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(new int[] {1, 2}, 9, new long[512]));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(new int[] {1, 2}, 4, new long[10]));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(new int[] {-1, 2}, 4, new long[256]));
        assertNull(LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(
                new int[] {1, LcaSectionBacking.MAX_STATE_ID_19 + 1}, 4, new long[256]));

        // Cover storageBits=4->flatBits=1,2,3,4; storageBits=5->5; storageBits=6->6; storageBits=7->7; storageBits=8->8
        int[][] configs = {
            {4, 2},   // flatBits=1 (repack)
            {4, 4},   // flatBits=2 (repack)
            {4, 7},   // flatBits=3 (repack, cross-byte)
            {4, 12},  // flatBits=4 (1:1 fast path with palLen < 16 check)
            {4, 16},  // flatBits=4 (1:1 fast path with palLen == 16)
            {5, 20},  // flatBits=5 (repack, cross-byte)
            {6, 40},  // flatBits=6 (repack, cross-byte)
            {7, 80},  // flatBits=7 (repack, cross-byte)
            {8, 160}  // flatBits=8 (1:1 fast path)
        };
        Random rng = new Random(0xC0FFEE42L);
        for (int[] cfg : configs) {
            int storageBits = cfg[0];
            int palLen = cfg[1];
            int[] palette = new int[palLen];
            for (int i = 0; i < palLen; i++) {
                palette[i] = 100 + i * 3;
            }
            int entriesPerLong = 64 / storageBits;
            int expectedLongs = (4096 + entriesPerLong - 1) / entriesPerLong;
            long[] storageRaw = new long[expectedLongs];
            int[] expectedVoxels = new int[4096];
            for (int v = 0; v < 4096; v++) {
                // Leave first 256 voxels as ordinal 0 to exercise word == 0L skip branches
                int ord = (v < 256) ? 0 : rng.nextInt(palLen);
                expectedVoxels[v] = palette[ord];
                int longIdx = v / entriesPerLong;
                int bitIdx = (v % entriesPerLong) * storageBits;
                storageRaw[longIdx] |= ((long) ord) << bitIdx;
            }
            byte[] wire = LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(palette, storageBits, storageRaw);
            assertNotNull(wire, "Failed for storageBits=" + storageBits + " palLen=" + palLen);

            int[] identityMap = new int[100 + palLen * 3 + 1];
            for (int i = 0; i < identityMap.length; i++) {
                identityMap[i] = i;
            }
            LcaModeABlockSection parsed =
                    LcaModeABlockSection.tryExtractFromChunkFrame(wire, 0, wire.length, identityMap);
            assertNotNull(parsed, "Parse failed for storageBits=" + storageBits + " palLen=" + palLen);
            assertEquals(LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK, parsed.blockMode());
            assertEquals(palette[0], parsed.majorityRuntimeId());
            assertSectionMatchesVoxels(parsed, expectedVoxels);

            // toWireSectionFrameBytes round-trip
            byte[] rewired = parsed.toWireSectionFrameBytes();
            assertArrayEquals(wire, rewired);

            // Out-of-range ordinal inside storageRaw must fail closed with null
            if (palLen < (1 << storageBits)) {
                long[] badRaw = Arrays.copyOf(storageRaw, storageRaw.length);
                badRaw[badRaw.length - 1] |= ((long) palLen);
                assertNull(
                        LcaModeABlockSection.encodeFlatBitpackFromSimpleBitStorage(palette, storageBits, badRaw),
                        "Out-of-range ordinal must return null for storageBits=" + storageBits + " palLen=" + palLen);
            }
        }
    }

    @Test
    @DisplayName("toWireSectionFrameBytes, validation guards, and all container tags maintain exact parity")
    void toWireValidationAndContainerEdgeCases() {
        // Build a Delta-Roaring section with multiple container types (singleton, long run, two-run, delta-array, bitmap)
        int[] voxels = new int[4096];
        Arrays.fill(voxels, 10);
        voxels[3] = 20; // singleton
        for (int i = 20; i < 300; i++) voxels[i] = 30; // long run (>257 -> subTag 2)
        for (int i = 400; i < 406; i++) voxels[i] = 40; // two-run with wide gap (>=240)
        for (int i = 700; i < 705; i++) voxels[i] = 40;
        for (int i = 0; i < 900; i += 2) voxels[1024 + i] = 50; // dense bitmap / delta-run
        for (int i = 0; i < 35; i++) voxels[2100 + i * 19] = 60; // delta-array
        LcaModeABlockSection sec = LcaModeABlockSection.encodeFromRuntimeVoxels(voxels);
        assertNotNull(sec);
        assertSectionMatchesVoxels(sec, voxels);

        // toWireSectionFrameBytes round-trip for Delta-Roaring
        byte[] wire = sec.toWireSectionFrameBytes();
        int[] idMap = new int[100];
        for (int i = 0; i < idMap.length; i++) idMap[i] = i;
        LcaModeABlockSection roundTripped =
                LcaModeABlockSection.tryExtractFromChunkFrame(wire, 0, wire.length, idMap);
        assertNotNull(roundTripped);
        assertSectionMatchesVoxels(roundTripped, voxels);

        // Validation guards on countOrdinals / materializeOrdinals / forEachMatchingCoordinate
        assertThrows(IllegalArgumentException.class, () -> sec.countOrdinals(null));
        assertThrows(IllegalArgumentException.class, () -> sec.countOrdinals(new int[1]));
        assertThrows(IllegalArgumentException.class, () -> sec.materializeOrdinals(null));
        assertThrows(IllegalArgumentException.class, () -> sec.materializeOrdinals(new int[100]));
        List<Integer> emitted = new ArrayList<>();
        sec.forEachMatchingCoordinate(new boolean[1], emitted::add); // shorter than palette -> no-op
        assertTrue(emitted.isEmpty());
        sec.forEachMatchingCoordinate(new boolean[sec.paletteLength()], emitted::add); // all false -> no-op
        assertTrue(emitted.isEmpty());

        // Direct hand-crafted FLAT_BITPACK section with 3-bit packing to exercise tail bytes in readFlatOrdinal
        byte[] flat3 = new byte[1536];
        int[] pal3 = {10, 11, 12, 13, 14};
        LcaModeABlockSection flatSec3 = new LcaModeABlockSection(
                LcaSectionCodec.CURVE_YZX,
                LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK,
                3,
                -1L,
                pal3,
                flat3,
                null);
        assertEquals(10, flatSec3.queryVoxelRuntimeId(4095));
        int[] counts3 = new int[5];
        flatSec3.countOrdinals(counts3);
        assertEquals(4096, counts3[0]);
    }

    @Test
    @DisplayName("Array-bearing records implement deep value equality, hashCode, and readable toString")
    void arrayBearingRecordEqualityAndRawChunkFrameSnapshotEdgeCases() {
        // RawChunkFrameSnapshot.FlatBitPackSection
        RawChunkFrameSnapshot.FlatBitPackSection f1 =
                new RawChunkFrameSnapshot.FlatBitPackSection(new int[] {1, 2}, 4, new byte[] {3, 4});
        RawChunkFrameSnapshot.FlatBitPackSection f2 =
                new RawChunkFrameSnapshot.FlatBitPackSection(new int[] {1, 2}, 4, new byte[] {3, 4});
        RawChunkFrameSnapshot.FlatBitPackSection f3 =
                new RawChunkFrameSnapshot.FlatBitPackSection(new int[] {1, 3}, 4, new byte[] {3, 4});
        RawChunkFrameSnapshot.FlatBitPackSection f4 =
                new RawChunkFrameSnapshot.FlatBitPackSection(new int[] {1, 2}, 8, new byte[] {3, 4});
        RawChunkFrameSnapshot.FlatBitPackSection f5 =
                new RawChunkFrameSnapshot.FlatBitPackSection(new int[] {1, 2}, 4, new byte[] {3, 5});
        assertEquals(f1, f1);
        assertEquals(f1, f2);
        assertEquals(f1.hashCode(), f2.hashCode());
        assertNotEquals(f1, f3);
        assertNotEquals(f1, f4);
        assertNotEquals(f1, f5);
        assertNotEquals(f1, "other");
        assertTrue(f1.toString().contains("FlatBitPackSection"));

        // LcaDecodedChunkMeta.DecodedSectionMeta
        LcaDecodedChunkMeta.DecodedSectionMeta s1 = new LcaDecodedChunkMeta.DecodedSectionMeta(
                0,
                new int[] {0, 1},
                new long[] {10L, 20L},
                LcaDecodedChunkMeta.LIGHT_ARRAY,
                new byte[] {1, 2},
                2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO,
                null,
                1);
        LcaDecodedChunkMeta.DecodedSectionMeta s2 = new LcaDecodedChunkMeta.DecodedSectionMeta(
                0,
                new int[] {0, 1},
                new long[] {10L, 20L},
                LcaDecodedChunkMeta.LIGHT_ARRAY,
                new byte[] {1, 2},
                2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO,
                null,
                1);
        LcaDecodedChunkMeta.DecodedSectionMeta sDiff = new LcaDecodedChunkMeta.DecodedSectionMeta(
                1,
                new int[] {2},
                new long[] {30L},
                LcaDecodedChunkMeta.LIGHT_FULL_15,
                new byte[] {9},
                0,
                LcaDecodedChunkMeta.LIGHT_ABSENT,
                new byte[] {8},
                0);
        assertEquals(s1, s1);
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
        assertNotEquals(s1, sDiff);
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {9}, new long[] {10L, 20L}, LcaDecodedChunkMeta.LIGHT_ARRAY, new byte[] {1, 2}, 2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, null, 1));
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {0, 1}, new long[] {99L}, LcaDecodedChunkMeta.LIGHT_ARRAY, new byte[] {1, 2}, 2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, null, 1));
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {0, 1}, new long[] {10L, 20L}, LcaDecodedChunkMeta.LIGHT_ABSENT, new byte[] {1, 2}, 2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, null, 1));
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {0, 1}, new long[] {10L, 20L}, LcaDecodedChunkMeta.LIGHT_ARRAY, new byte[] {9, 9}, 2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, null, 1));
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {0, 1}, new long[] {10L, 20L}, LcaDecodedChunkMeta.LIGHT_ARRAY, new byte[] {1, 2}, 3,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, null, 1));
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {0, 1}, new long[] {10L, 20L}, LcaDecodedChunkMeta.LIGHT_ARRAY, new byte[] {1, 2}, 2,
                LcaDecodedChunkMeta.LIGHT_ARRAY, null, 1));
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {0, 1}, new long[] {10L, 20L}, LcaDecodedChunkMeta.LIGHT_ARRAY, new byte[] {1, 2}, 2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, new byte[] {5}, 1));
        assertNotEquals(s1, new LcaDecodedChunkMeta.DecodedSectionMeta(
                0, new int[] {0, 1}, new long[] {10L, 20L}, LcaDecodedChunkMeta.LIGHT_ARRAY, new byte[] {1, 2}, 2,
                LcaDecodedChunkMeta.LIGHT_UNINIT_ZERO, null, 9));
        assertNotEquals(s1, "other");
        assertTrue(s1.toString().contains("DecodedSectionMeta"));

        // LcaDecodedChunkMeta
        LcaDecodedChunkMeta m1 = new LcaDecodedChunkMeta(
                3953, 0, 0, -4, "minecraft:full", 100L, 20L, 72, 24, 2,
                List.of("minecraft:plains"), new int[] {0}, new String[] {"WORLD_SURFACE"},
                new long[][] {{1L, 2L}}, new LcaDecodedChunkMeta.DecodedSectionMeta[] {s1}, new byte[] {7, 8});
        LcaDecodedChunkMeta m2 = new LcaDecodedChunkMeta(
                3953, 0, 0, -4, "minecraft:full", 100L, 20L, 72, 24, 2,
                List.of("minecraft:plains"), new int[] {0}, new String[] {"WORLD_SURFACE"},
                new long[][] {{1L, 2L}}, new LcaDecodedChunkMeta.DecodedSectionMeta[] {s2}, new byte[] {7, 8});
        LcaDecodedChunkMeta mDiff = new LcaDecodedChunkMeta(
                3953, 1, 0, -4, "minecraft:full", 100L, 20L, 0, 0, 0,
                List.of("minecraft:desert"), new int[] {1}, new String[] {"MOTION_BLOCKING"},
                new long[][] {{3L}}, new LcaDecodedChunkMeta.DecodedSectionMeta[] {sDiff}, null);
        assertEquals(m1, m1);
        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertNotEquals(m1, mDiff);
        assertNotEquals(m1, "other");
        assertTrue(m1.hasIsLightOn());
        assertTrue(m1.hasStarlightVersion());
        assertTrue(m1.hasResidualNbt());
        assertFalse(mDiff.hasIsLightOn());
        assertFalse(mDiff.hasStarlightVersion());
        assertFalse(mDiff.hasResidualNbt());
        assertTrue(m1.toString().contains("LcaDecodedChunkMeta"));

        // RawChunkFrameSnapshot constructor guard, accessors, and RawChunkFrameWithMetadata
        assertThrows(
                IllegalArgumentException.class,
                () -> new RawChunkFrameSnapshot(new byte[10], 3953, -4, 4, 0, new int[0]));
        byte[] minimalFrame = new byte[24 + 8];
        RawChunkFrameSnapshot snap = new RawChunkFrameSnapshot(minimalFrame, 3953, -4, 4, 0, new int[] {0, 1});
        assertEquals(0L, snap.lastUpdateTick());
        assertEquals(0L, snap.inhabitedTimeTicks());
        assertEquals(0, snap.statusOrdinal());
        assertFalse(snap.hasResidualNbt());
        assertFalse(snap.hasIsLightOn());
        assertThrows(IndexOutOfBoundsException.class, () -> snap.sectionDirectoryEntry16(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> snap.sectionDirectoryEntry16(4));
        assertNull(snap.tryExtractModeABlockSection(-1, new int[4], new int[2]));
        assertNull(snap.tryExtractModeABlockSection(0, null, new int[2]));
        assertNull(snap.tryExtractModeABlockSection(0, new int[4], null));
        assertNull(snap.tryExtractFlatBitPackSection(-1, new int[4], new int[2]));
        assertNull(snap.tryExtractFlatBitPackSection(0, null, new int[2]));
        assertNull(snap.tryExtractFlatBitPackSection(0, new int[4], null));
        assertEquals(0, snap.sliceBlockOnlySectionFrameWithRuntimePalette(0, s -> 0).length);
        assertNull(RawChunkFrameSnapshot.rewriteBlockOnlySectionFramePaletteHeaderBytes(null, new int[2]));
        assertNull(RawChunkFrameSnapshot.rewriteBlockOnlySectionFramePaletteHeaderBytes(new byte[1], new int[2]));
        assertNull(RawChunkFrameSnapshot.rewriteBlockOnlySectionFramePaletteHeaderBytes(new byte[4], null));

        LcaRegionReader.RawChunkFrameWithMetadata rm1 =
                new LcaRegionReader.RawChunkFrameWithMetadata(snap, new byte[] {1, 2, 3});
        LcaRegionReader.RawChunkFrameWithMetadata rm2 =
                new LcaRegionReader.RawChunkFrameWithMetadata(snap, new byte[] {1, 2, 3});
        LcaRegionReader.RawChunkFrameWithMetadata rm3 =
                new LcaRegionReader.RawChunkFrameWithMetadata(snap, new byte[] {1, 2, 4});
        assertEquals(rm1, rm1);
        assertEquals(rm1, rm2);
        assertEquals(rm1.hashCode(), rm2.hashCode());
        assertNotEquals(rm1, rm3);
        assertNotEquals(rm1, "other");
        assertTrue(rm1.toString().contains("RawChunkFrameWithMetadata"));
    }
}

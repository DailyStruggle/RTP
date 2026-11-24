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
    }
}

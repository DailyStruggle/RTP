package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies zero-copy off-heap Mode A {@link LcaSectionBacking#fromOffHeapModeASlice} parity,
 * O(paletteSize) counts, 16-slot per-thread {@code ReadCache} promotion, vanilla wire serialization,
 * overlay mutation + off-thread compaction, {@link LcaSectionBacking#toDirectModeASectionRef()},
 * and direct read-ahead buffer reuse in {@link LcaRegionFileStorage#getChunkDataInputStream}.
 *
 * @governedBy anvil-api-ADR-007
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaOffHeapSectionBackingTest {

    private static final String[] BLOCKS = new String[] {
            "minecraft:air",
            "minecraft:stone",
            "minecraft:deepslate",
            "minecraft:dirt",
            "minecraft:grass_block[snowy=false]",
            "minecraft:water[level=0]",
            "minecraft:oak_sapling[stage=0]",
            "minecraft:bedrock"
    };

    private static final String[] BIOMES = new String[] {
            "minecraft:plains",
            "minecraft:forest"
    };

    private static LcaModeABlockSection.DirectModeASectionRef buildDirectDeltaRoaringRef(
            int[] yzxOrdinals, int paletteLen
    ) {
        LcaModeABlockSection encoded = LcaModeABlockSection.encodeFromRuntimeVoxels(yzxOrdinals);
        byte[] rawPayload = encoded.blockPayloadRaw();
        ByteBuffer direct = ByteBuffer.allocateDirect(rawPayload.length + 16);
        int offset = 8;
        direct.position(offset);
        direct.put(rawPayload);
        direct.clear();
        return new LcaModeABlockSection.DirectModeASectionRef(
                encoded.curveId(),
                encoded.blockMode(),
                encoded.flatBits(),
                encoded.octreeMask64(),
                paletteLen,
                direct,
                offset,
                rawPayload.length
        );
    }

    private static LcaModeABlockSection.DirectModeASectionRef buildDirectFlatRef(int[] yzxOrdinals, int paletteLen) {
        int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
        int flatByteCount = (4096 * flatBits + 7) >>> 3;
        byte[] packed = new byte[flatByteCount];
        long octreeMask = 0L;
        int bitPos = 0;
        for (int v = 0; v < 4096; v++) {
            int ord = yzxOrdinals[v];
            if (ord != 0) {
                octreeMask |= (1L << LcaSectionCodec.YZX_TO_OCTANT6[v]);
            }
            for (int b = 0; b < flatBits; b++) {
                if (((ord >>> b) & 1) != 0) {
                    int pos = bitPos + b;
                    packed[pos >>> 3] |= (byte) (1 << (pos & 7));
                }
            }
            bitPos += flatBits;
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(flatByteCount + 16);
        int offset = 8;
        direct.position(offset);
        direct.put(packed);
        direct.clear();
        return new LcaModeABlockSection.DirectModeASectionRef(
                LcaSectionCodec.CURVE_YZX,
                LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK,
                flatBits,
                octreeMask,
                paletteLen,
                direct,
                offset,
                flatByteCount
        );
    }

    private static byte[] serializeWire(LcaSectionBacking backing, int globalBits) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        backing.writeVanillaPalettedContainer(globalBits, new LcaSectionBacking.VanillaWireSink() {
            @Override
            public void writeByte(int value) {
                baos.write(value & 0xFF);
            }

            @Override
            public void writeVarInt(int value) {
                int v = value;
                while ((v & ~0x7F) != 0) {
                    baos.write((v & 0x7F) | 0x80);
                    v >>>= 7;
                }
                baos.write(v);
            }

            @Override
            public void writeLong(long value) {
                for (int s = 56; s >= 0; s -= 8) {
                    baos.write((int) ((value >>> s) & 0xFFL));
                }
            }
        });
        return baos.toByteArray();
    }

    @Test
    @DisplayName("fromOffHeapModeASlice matches bit-exact voxels, O(P) counts, queries, wire, mutations, and DirectModeASectionRef")
    void offHeapSectionBackingFullParityAndMutation(@TempDir Path tempDir) throws Exception {
        Map<String, Integer> runtimeIds = new HashMap<>();
        runtimeIds.put("minecraft:air", 0);
        runtimeIds.put("minecraft:stone", 10);
        runtimeIds.put("minecraft:deepslate", 20);
        runtimeIds.put("minecraft:dirt", 30);
        runtimeIds.put("minecraft:grass_block[snowy=false]", 40);
        runtimeIds.put("minecraft:water[level=0]", 50);
        runtimeIds.put("minecraft:oak_sapling[stage=0]", 60);
        runtimeIds.put("minecraft:bedrock", 70);
        Function<String, Integer> lookup = name -> runtimeIds.getOrDefault(name, 0);

        IntPredicate nonEmpty = id -> id != 0;
        IntPredicate tickingBlock = id -> id == 40 || id == 60;
        IntPredicate tickingFluid = id -> id == 50;

        // Section 1: Delta-Roaring sparse (air majority + grass + sapling + water)
        int[] roaringOrd = new int[4096];
        for (int v = 0; v < 4096; v++) {
            if (v % 53 == 0) {
                roaringOrd[v] = 1; // grass (ticking block)
            } else if (v % 101 == 0) {
                roaringOrd[v] = 2; // sapling (ticking block)
            } else if (v % 149 == 0) {
                roaringOrd[v] = 3; // water (ticking fluid)
            }
        }
        int[] roaringPalette = new int[] {0, 40, 60, 50};

        // Section 2: FlatBitPack dense (stone, deepslate, dirt, water)
        int[] flatOrd = new int[4096];
        for (int v = 0; v < 4096; v++) {
            flatOrd[v] = (v * 7 + (v >>> 4)) & 3;
        }
        int[] flatPalette = new int[] {10, 20, 30, 50};

        int sectionCount = 24;
        String[][] blockPalettes = new String[sectionCount][];
        LcaModeABlockSection.DirectModeASectionRef[] offHeapRefs = new LcaModeABlockSection.DirectModeASectionRef[sectionCount];
        int[][] preBlockIds = new int[sectionCount][];
        String[][] biomePalettes = new String[sectionCount][];
        int[][] preBiomeIds = new int[sectionCount][];
        long[][] blockData = new long[sectionCount][];
        long[][] biomeData = new long[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] blkLight = new byte[sectionCount][];

        preBlockIds[0] = new int[] {7}; // uniform bedrock
        preBiomeIds[0] = new int[] {0};

        preBlockIds[1] = new int[] {0, 4, 6, 5};
        blockPalettes[1] = new String[] {BLOCKS[0], BLOCKS[4], BLOCKS[6], BLOCKS[5]};
        offHeapRefs[1] = buildDirectDeltaRoaringRef(roaringOrd, 4);
        preBiomeIds[1] = new int[] {0};

        preBlockIds[2] = new int[] {1, 2, 3, 5};
        blockPalettes[2] = new String[] {BLOCKS[1], BLOCKS[2], BLOCKS[3], BLOCKS[5]};
        offHeapRefs[2] = buildDirectFlatRef(flatOrd, 4);
        preBiomeIds[2] = new int[] {1};

        for (int s = 3; s < sectionCount; s++) {
            preBlockIds[s] = new int[] {0};
            preBiomeIds[s] = new int[] {0};
        }

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(tempDir, false)) {
            writer.configureBufferedIo(true, 64, 4 * 1024 * 1024, true, 1);
            writer.bindPrioritizedDictionary(BLOCKS, BIOMES);
            writer.writeFreshChunkDirectOffHeapModeA(
                    2, 4, 4790, 123L, 45L, "minecraft:full",
                    blockPalettes, offHeapRefs, null, null, null, preBlockIds, null, null,
                    blockData, biomePalettes, preBiomeIds, biomeData, skyLight, blkLight, null, null
            );
            writer.flush();
        }

        LcaSectionBacking.clearThreadLocalScratch();
        long stagingBefore = LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies();

        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(tempDir, false)) {
            reader.configureBufferedIo(true, 64, 4 * 1024 * 1024, true, 1);
            LcaRegionReader.DirectOffHeapChunkView view = reader.readDirectOffHeapChunkView(2, 4, lookup);
            assertNotNull(view);
            view.bind();
            assertEquals(
                    stagingBefore,
                    LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies(),
                    "Zero heap chunk staging copies permitted on direct off-heap bind()"
            );

            // Verify Delta-Roaring section 1
            LcaModeABlockSection.OffHeapModeASlice slice1 = view.trySliceOffHeapModeASection(1);
            assertNotNull(slice1);
            assertTrue(slice1.slabBuf().isDirect());
            LcaSectionBacking backing1 = LcaSectionBacking.fromOffHeapModeASlice(
                    slice1, nonEmpty, tickingBlock, tickingFluid
            );
            assertTrue(backing1.isOffHeapModeABacked());
            assertTrue(backing1.isColdBaseFrameOnly());
            assertFalse(backing1.hasMaterializedBaseFrame());
            assertNotNull(backing1.toDirectModeASectionRef());

            int expectedNonEmpty1 = 0;
            int expectedTickBlock1 = 0;
            int expectedTickFluid1 = 0;
            int[] expectedVoxels1 = new int[4096];
            for (int v = 0; v < 4096; v++) {
                int id = roaringPalette[roaringOrd[v]];
                expectedVoxels1[v] = id;
                if (nonEmpty.test(id)) expectedNonEmpty1++;
                if (tickingBlock.test(id)) expectedTickBlock1++;
                if (tickingFluid.test(id)) expectedTickFluid1++;
            }
            assertEquals(expectedNonEmpty1, backing1.nonEmptyBlockCount());
            assertEquals(expectedTickBlock1, backing1.tickingBlockCount());
            assertEquals(expectedTickFluid1, backing1.tickingFluidCount());

            // Random reads (both cold direct query and ReadCache promoted reads)
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int pos = (y << 8) | (z << 4) | x;
                            assertEquals(expectedVoxels1[pos], backing1.getStateId(x, y, z));
                        }
                    }
                }
            }

            // Materialize, state counts, predicates, matching coordinates, minority iteration, wire serialization
            int[] mat1 = backing1.materializeEphemeralStateIds(new int[4096], null);
            assertArrayEquals(expectedVoxels1, mat1);

            Map<Integer, Integer> counts1 = new HashMap<>();
            backing1.forEachStateCount(counts1::put);
            assertEquals(expectedTickFluid1, counts1.get(50));
            assertEquals(4096 - expectedNonEmpty1, counts1.get(0));

            assertTrue(backing1.testMaybeHas(id -> id == 60));
            assertFalse(backing1.testMaybeHas(id -> id == 999));

            BitSet matchedSaplings = new BitSet(4096);
            backing1.forEachMatchingCoordinate(id -> id == 60, matchedSaplings::set);
            for (int v = 0; v < 4096; v++) {
                assertEquals(expectedVoxels1[v] == 60, matchedSaplings.get(v));
            }

            int[] reconstructed = new int[4096];
            assertTrue(backing1.forEachNonMajorityAndOverlayVoxel((p, id) -> reconstructed[p] = id));
            assertArrayEquals(expectedVoxels1, reconstructed);

            // Compare vanilla wire serialization size & bytes
            int wireSize1 = backing1.computeVanillaSerializedSize(15);
            byte[] wireBytes1 = serializeWire(backing1, 15);
            assertEquals(wireSize1, wireBytes1.length);
            assertArrayEquals(wireBytes1, backing1.cleanVanillaWireSlice(15));

            // Verify FlatBitPack section 2
            LcaModeABlockSection.OffHeapModeASlice slice2 = view.trySliceOffHeapModeASection(2);
            assertNotNull(slice2);
            LcaSectionBacking backing2 = LcaSectionBacking.fromOffHeapModeASlice(
                    slice2, nonEmpty, tickingBlock, tickingFluid
            );
            assertTrue(backing2.isOffHeapModeABacked());
            int[] expectedVoxels2 = new int[4096];
            for (int v = 0; v < 4096; v++) {
                expectedVoxels2[v] = flatPalette[flatOrd[v]];
            }
            for (int v = 0; v < 4096; v++) {
                int x = v & 15;
                int z = (v >>> 4) & 15;
                int y = (v >>> 8) & 15;
                assertEquals(expectedVoxels2[v], backing2.getStateId(x, y, z));
            }
            assertEquals(backing2.computeVanillaSerializedSize(15), serializeWire(backing2, 15).length);

            // Mutate section 1 -> toDirectModeASectionRef becomes null, counts/wire update accurately, compaction succeeds
            int prev = backing1.setAndGetPreviousStateId(1, 2, 3, 70, 1L, null);
            assertEquals(expectedVoxels1[(2 << 8) | (3 << 4) | 1], prev);
            assertNull(backing1.toDirectModeASectionRef(), "Dirty section must not export clean DirectModeASectionRef");
            assertEquals(70, backing1.getStateId(1, 2, 3));
            assertTrue(backing1.testMaybeHas(id -> id == 70));
            assertEquals(backing1.computeVanillaSerializedSize(15), serializeWire(backing1, 15).length);

            assertTrue(backing1.compactFrozenOverlayOffThread(15));
            assertEquals(70, backing1.getStateId(1, 2, 3));
            assertEquals(0, backing1.dirtyCount());

            // Verify getChunkDataInputStream reuses already-promoted direct read-ahead buffer cleanly
            try (DataInputStream dis = reader.getChunkDataInputStream(2, 4)) {
                assertNotNull(dis);
                byte[] nbtBytes = dis.readAllBytes();
                assertTrue(nbtBytes.length > 32, "Direct-buffer NBT decode must yield valid chunk NBT payload");
            }
        } finally {
            LcaSectionBacking.clearThreadLocalScratch();
        }
    }
}

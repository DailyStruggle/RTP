package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mechanical verification tests for zero-copy off-heap Mode A gather writes, direct off-heap
 * read-ahead activation, prioritized block/biome dictionaries with dynamic {@code dictSegments}
 * overflow, and {@code REQ-RTP-S-002} pin-release invariants (`anvil-api-ADR-007`).
 *
 * @governedBy anvil-api-ADR-007
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
class LcaZeroCopyOffHeapIoTest {

    private static final String[] PRIORITIZED_BLOCKS = new String[] {
            "minecraft:air",
            "minecraft:stone",
            "minecraft:deepslate",
            "minecraft:dirt",
            "minecraft:grass_block[snowy=false]",
            "minecraft:bedrock",
            "minecraft:water[level=0]",
            "minecraft:oak_log[axis=y]"
    };

    private static final String[] PRIORITIZED_BIOMES = new String[] {
            "minecraft:plains",
            "minecraft:forest",
            "minecraft:taiga",
            "minecraft:desert",
            "minecraft:ocean"
    };

    private static Function<String, Integer> buildRuntimeLookup(Map<String, Integer> table) {
        return name -> table.getOrDefault(name, 0);
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

    private static LcaModeABlockSection.DirectModeASectionRef buildDirectDeltaRoaringRef(
            int[] yzxOrdinals, int paletteLen
    ) {
        LcaModeABlockSection encoded = LcaModeABlockSection.encodeFromRuntimeVoxels(yzxOrdinals);
        byte[] rawPayload = encoded.blockPayloadRaw();
        ByteBuffer direct = ByteBuffer.allocateDirect(rawPayload.length + 32);
        int offset = 12;
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

    private static int readFlatOrdinal(ByteBuffer slabBuf, int payloadOffset, int flatBits, int yzx) {
        int bitPos = yzx * flatBits;
        int byteIdx = payloadOffset + (bitPos >>> 3);
        int shift = bitPos & 7;
        int mask = (1 << flatBits) - 1;
        int raw = (slabBuf.get(byteIdx) & 0xFF) >>> shift;
        if (shift + flatBits > 8) {
            raw |= (slabBuf.get(byteIdx + 1) & 0xFF) << (8 - shift);
        }
        return raw & mask;
    }

    @Test
    @DisplayName("Off-heap gather write and direct off-heap read round-trip Delta-Roaring, FlatBitPack, uniform, and novel dictSegments")
    void roundTripOffHeapGatherAndDirectRead(@TempDir Path tempDir) throws Exception {
        Map<String, Integer> runtimeIds = new HashMap<>();
        for (int i = 0; i < PRIORITIZED_BLOCKS.length; i++) {
            runtimeIds.put(PRIORITIZED_BLOCKS[i], 100 + i);
        }
        String novelState = "minecraft:waxed_oxidized_cut_copper_stairs[facing=north,half=top,shape=straight,waterlogged=false]";
        runtimeIds.put(novelState, 999);
        Function<String, Integer> lookup = buildRuntimeLookup(runtimeIds);

        int[] roaringExpected = new int[4096];
        for (int v = 0; v < 4096; v++) {
            roaringExpected[v] = (v % 37 == 0) ? 1 : ((v % 97 == 0) ? 2 : 0);
        }
        int[] flatExpected = new int[4096];
        for (int v = 0; v < 4096; v++) {
            flatExpected[v] = v & 3;
        }

        LcaModeABlockSection.DirectModeASectionRef roaringRef = buildDirectDeltaRoaringRef(roaringExpected, 3);
        LcaModeABlockSection.DirectModeASectionRef flatRef = buildDirectFlatRef(flatExpected, 4);

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

        // Section 0: uniform bedrock via prioritized ID
        preBlockIds[0] = new int[] {5};
        blockPalettes[0] = new String[] {"minecraft:bedrock"};
        preBiomeIds[0] = new int[] {0};

        // Section 1: clean off-heap Delta-Roaring (stone + deepslate + dirt)
        preBlockIds[1] = new int[] {1, 2, 3};
        blockPalettes[1] = new String[] {"minecraft:stone", "minecraft:deepslate", "minecraft:dirt"};
        offHeapRefs[1] = roaringRef;
        preBiomeIds[1] = new int[] {1};

        // Section 2: clean off-heap FlatBitPack (air, stone, dirt, grass_block)
        preBlockIds[2] = new int[] {0, 1, 3, 4};
        blockPalettes[2] = new String[] {"minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block[snowy=false]"};
        offHeapRefs[2] = flatRef;
        preBiomeIds[2] = new int[] {2};

        // Section 3: novel block state + novel biome outside prioritized dicts (tests dynamic dictSegments)
        blockPalettes[3] = new String[] {novelState};
        biomePalettes[3] = new String[] {"minecraft:cherry_grove"};

        // Sections 4..23: uniform air
        for (int s = 4; s < sectionCount; s++) {
            preBlockIds[s] = new int[] {0};
            preBiomeIds[s] = new int[] {0};
        }

        AtomicInteger pinReleaseCount = new AtomicInteger(0);
        try (LcaRegionFileStorage writerStorage = new LcaRegionFileStorage(tempDir, false)) {
            writerStorage.configureBufferedIo(true, 64, 4 * 1024 * 1024, true, 1);
            writerStorage.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);

            int written = writerStorage.writeFreshChunkDirectOffHeapModeA(
                    3, 5, 4325, 100L, 50L, "minecraft:full",
                    blockPalettes, offHeapRefs, null, null, null, preBlockIds, null, null,
                    blockData, biomePalettes, preBiomeIds, biomeData, skyLight, blkLight, null,
                    pinReleaseCount::incrementAndGet
            );
            assertTrue(written > 0, "Expected non-zero chunk payload bytes written");
            assertEquals(0, pinReleaseCount.get(), "Pins must remain held while staged in pendingGatherChunks");

            // RYOW check before flush
            LcaRegionReader.DeferredChunkSlab ryow = writerStorage.readDeferredChunkSlab(3, 5, lookup);
            assertNotNull(ryow, "RYOW read must succeed while chunk is staged in pendingGatherChunks");
            LcaRegionReader.RawChunkFrameWithDirectMeta ryowMeta = ryow.decodeToDirectMeta();
            assertNotNull(ryowMeta.directMeta());
            assertEquals(100 + 5, ryowMeta.frame().resolveInlineUniformRuntimeStateId(0, lookup));
            assertEquals(999, ryowMeta.frame().resolveInlineUniformRuntimeStateId(3, lookup),
                    () -> "entry3=0x" + Integer.toHexString(ryowMeta.frame().sectionDirectoryEntry16(3))
                            + " palMap=" + java.util.Arrays.toString(ryowMeta.frame().resolveEffectivePaletteToRuntimeIdMap(lookup)));

            writerStorage.flush();
            assertEquals(1, pinReleaseCount.get(), "Pins must release exactly once after flush()");
        }
        assertEquals(1, pinReleaseCount.get(), "close() must not double-release already flushed pins");

        // Fresh read-only storage: test direct off-heap read-ahead activation
        try (LcaRegionFileStorage readerStorage = new LcaRegionFileStorage(tempDir, false)) {
            readerStorage.configureBufferedIo(true, 64, 4 * 1024 * 1024, true, 1);

            LcaRegionReader.DirectOffHeapChunkView view = readerStorage.readDirectOffHeapChunkView(3, 5, lookup);
            assertNotNull(view, "DirectOffHeapChunkView must succeed for Mode A .lca chunk");
            view.decode();

            assertEquals(4325, view.dataVersion());
            assertNotNull(view.directMeta());
            assertEquals(100 + 5, view.resolveInlineUniformRuntimeStateId(0));
            assertEquals(999, view.resolveInlineUniformRuntimeStateId(3));
            int sec3BiomeId = view.directMeta().sections()[3].biomeGlobalDictIds()[0];
            assertEquals("minecraft:cherry_grove", view.directMeta().biomeKeys().get(sec3BiomeId));

            // Verify section 1 (Delta-Roaring off-heap slice)
            LcaModeABlockSection.OffHeapModeASlice sec1 = view.trySliceOffHeapModeASection(1);
            assertNotNull(sec1, "Section 1 must slice directly off-heap");
            assertTrue(sec1.slabBuf().isDirect(), "Read-ahead slabBuf must be direct off-heap memory");
            assertEquals(3, sec1.paletteRuntimeIds().length);
            assertEquals(101, sec1.paletteRuntimeIds()[0]);
            assertEquals(102, sec1.paletteRuntimeIds()[1]);
            assertEquals(103, sec1.paletteRuntimeIds()[2]);

            // Cross-check Delta-Roaring slice against materialized RawChunkFrameSnapshot
            LcaModeABlockSection matSec1 = view.materializeSnapshot().tryExtractModeABlockSection(1, lookup);
            assertNotNull(matSec1);
            for (int v = 0; v < 4096; v++) {
                assertEquals(roaringExpected[v], matSec1.queryVoxelOrdinal(v), "Roaring voxel mismatch at " + v);
            }

            // Verify section 2 (FlatBitPack off-heap slice)
            LcaModeABlockSection.OffHeapModeASlice sec2 = view.trySliceOffHeapModeASection(2);
            assertNotNull(sec2, "Section 2 must slice directly off-heap");
            assertTrue(sec2.slabBuf().isDirect());
            assertEquals(LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK, sec2.blockMode());
            assertEquals(2, sec2.flatBits());
            for (int v = 0; v < 4096; v++) {
                assertEquals(flatExpected[v], readFlatOrdinal(sec2.slabBuf(), sec2.payloadOffset(), sec2.flatBits(), v),
                        "FlatBitPack off-heap voxel mismatch at " + v);
            }
        }
    }

    @Test
    @DisplayName("S-002 pin release fires exactly once on overwrite coalesce, close, and synchronous writes")
    void pinReleaseContractOnOverwriteCloseAndSync(@TempDir Path tempDir) throws Exception {
        int[] pattern = new int[4096];
        for (int v = 0; v < 4096; v++) {
            pattern[v] = (v & 1);
        }
        LcaModeABlockSection.DirectModeASectionRef ref = buildDirectFlatRef(pattern, 2);

        String[][] palettes = new String[24][];
        int[][] preIds = new int[24][];
        LcaModeABlockSection.DirectModeASectionRef[] refs = new LcaModeABlockSection.DirectModeASectionRef[24];
        int[][] preBiomes = new int[24][];
        palettes[0] = new String[] {"minecraft:air", "minecraft:stone"};
        preIds[0] = new int[] {0, 1};
        refs[0] = ref;
        preBiomes[0] = new int[] {0};

        AtomicInteger pin1 = new AtomicInteger(0);
        AtomicInteger pin2 = new AtomicInteger(0);
        AtomicInteger pinSync = new AtomicInteger(0);

        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(tempDir, false)) {
            storage.configureBufferedIo(true, 64, 4 * 1024 * 1024, true, 1);
            storage.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);

            // First staged write
            storage.writeFreshChunkDirectOffHeapModeA(
                    0, 0, 4325, 1L, 0L, "minecraft:full",
                    palettes, refs, null, null, null, preIds, null, null,
                    null, null, preBiomes, null, null, null, null,
                    pin1::incrementAndGet
            );
            assertEquals(0, pin1.get());

            // Overwrite same chunk before flush -> pin1 must release immediately
            storage.writeFreshChunkDirectOffHeapModeA(
                    0, 0, 4325, 2L, 0L, "minecraft:full",
                    palettes, refs, null, null, null, preIds, null, null,
                    null, null, preBiomes, null, null, null, null,
                    pin2::incrementAndGet
            );
            assertEquals(1, pin1.get(), "Coalesced overwrite must release superseded gather pin immediately");
            assertEquals(0, pin2.get(), "Replacement gather pin must remain held until flush/close");
        }
        // close() flushes & releases pin2 exactly once
        assertEquals(1, pin1.get());
        assertEquals(1, pin2.get(), "close() must release pending gather pin exactly once");

        // Synchronous (unbuffered) write path releases immediately
        try (LcaRegionFileStorage syncStorage = new LcaRegionFileStorage(tempDir, false)) {
            syncStorage.configureBufferedIo(false, 1, 65536, false, 1);
            syncStorage.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);
            syncStorage.writeFreshChunkDirectOffHeapModeA(
                    1, 0, 4325, 3L, 0L, "minecraft:full",
                    palettes, refs, null, null, null, preIds, null, null,
                    null, null, preBiomes, null, null, null, null,
                    pinSync::incrementAndGet
            );
            assertEquals(1, pinSync.get(), "Unbuffered write must release pin immediately");
        }
        assertEquals(1, pinSync.get());
    }

    @Test
    @DisplayName("Concurrent positional readers stay tear-free during vectored gather flushes")
    void concurrentReadersAndVectoredGatherWriter(@TempDir Path tempDir) throws Exception {
        int[] pattern = new int[4096];
        for (int v = 0; v < 4096; v++) {
            pattern[v] = (v % 5 == 0) ? 1 : 0;
        }
        LcaModeABlockSection.DirectModeASectionRef ref = buildDirectDeltaRoaringRef(pattern, 2);

        String[][] palettes = new String[24][];
        int[][] preIds = new int[24][];
        LcaModeABlockSection.DirectModeASectionRef[] refs = new LcaModeABlockSection.DirectModeASectionRef[24];
        int[][] preBiomes = new int[24][];
        palettes[0] = new String[] {"minecraft:stone", "minecraft:deepslate"};
        preIds[0] = new int[] {1, 2};
        refs[0] = ref;
        preBiomes[0] = new int[] {0};

        Map<String, Integer> rt = Map.of("minecraft:air", 0, "minecraft:stone", 1, "minecraft:deepslate", 2);
        Function<String, Integer> lookup = buildRuntimeLookup(rt);

        // Seed 16 chunks first
        try (LcaRegionFileStorage seed = new LcaRegionFileStorage(tempDir, false)) {
            seed.configureBufferedIo(true, 8, 2 * 1024 * 1024, true, 1);
            seed.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);
            for (int i = 0; i < 16; i++) {
                seed.writeFreshChunkDirectOffHeapModeA(
                        i, 0, 4325, i, 0L, "minecraft:full",
                        palettes, refs, null, null, null, preIds, null, null,
                        null, null, preBiomes, null, null, null, null,
                        null
                );
            }
            seed.flush();
        }

        Path regionFile = tempDir.resolve("r.0.0.lca");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(tempDir, false)) {
            writer.configureBufferedIo(true, 4, 2 * 1024 * 1024, false, 1);
            writer.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);
            AtomicInteger totalPinsReleased = new AtomicInteger();

            Callable<Integer> readerTask = () -> {
                int reads = 0;
                for (int round = 0; round < 40; round++) {
                    int cx = round & 15;
                    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                            regionFile, java.nio.file.StandardOpenOption.READ)) {
                        LcaRegionReader.DirectOffHeapChunkView v =
                                LcaRegionReader.readDirectOffHeapChunkViewFromChannel(
                                        regionFile, ch, cx, 0, lookup);
                        if (v != null) {
                            v.decode();
                            assertNotNull(v.trySliceOffHeapModeASection(0));
                            reads++;
                        }
                    }
                }
                return reads;
            };

            List<Future<Integer>> readers = List.of(pool.submit(readerTask), pool.submit(readerTask));
            for (int w = 16; w < 48; w++) {
                writer.writeFreshChunkDirectOffHeapModeA(
                        w & 31, 1, 4325, w, 0L, "minecraft:full",
                        palettes, refs, null, null, null, preIds, null, null,
                        null, null, preBiomes, null, null, null, null,
                        totalPinsReleased::incrementAndGet
                );
            }
            writer.flush();
            assertEquals(32, totalPinsReleased.get(), "All 32 concurrent gather write pins must release");
            for (Future<Integer> f : readers) {
                assertTrue(f.get() > 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("DirectOffHeapChunkView#bind performs zero heap chunk copies and computes exact single-pass paletteVoxelCounts")
    void directBindZeroHeapCopyAndSinglePassPaletteVoxelCountsParity(@TempDir Path tempDir) throws Exception {
        int[] drVoxels = new int[4096];
        int[] expectedDrCounts = new int[3];
        for (int v = 0; v < 4096; v++) {
            int ord = (v % 19 == 0) ? 2 : ((v % 7 == 0) ? 1 : 0);
            drVoxels[v] = ord;
            expectedDrCounts[ord]++;
        }
        int[] flatVoxels = new int[4096];
        int[] expectedFlatCounts = new int[4];
        for (int v = 0; v < 4096; v++) {
            int ord = v & 3;
            flatVoxels[v] = ord;
            expectedFlatCounts[ord]++;
        }

        LcaModeABlockSection.DirectModeASectionRef drRef = buildDirectDeltaRoaringRef(drVoxels, 3);
        LcaModeABlockSection.DirectModeASectionRef flatRef = buildDirectFlatRef(flatVoxels, 4);

        String[][] palettes = new String[24][];
        int[][] preIds = new int[24][];
        LcaModeABlockSection.DirectModeASectionRef[] refs = new LcaModeABlockSection.DirectModeASectionRef[24];
        int[][] preBiomes = new int[24][];

        palettes[0] = new String[] {"minecraft:stone", "minecraft:deepslate", "minecraft:dirt"};
        preIds[0] = new int[] {1, 2, 3};
        refs[0] = drRef;
        preBiomes[0] = new int[] {0};

        palettes[1] = new String[] {
                "minecraft:stone", "minecraft:deepslate", "minecraft:dirt", "minecraft:grass_block[snowy=false]"
        };
        preIds[1] = new int[] {1, 2, 3, 4};
        refs[1] = flatRef;
        preBiomes[1] = new int[] {0};

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(tempDir, false)) {
            writer.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);
            writer.writeFreshChunkDirectOffHeapModeA(
                    3, 4, 4325, 100L, 0L, "minecraft:full",
                    palettes, refs, null, null, null, preIds, null, null,
                    null, null, preBiomes, null, null, null, null,
                    null
            );
            writer.flush();
        }

        Map<String, Integer> rt = new HashMap<>();
        for (int i = 0; i < PRIORITIZED_BLOCKS.length; i++) {
            rt.put(PRIORITIZED_BLOCKS[i], i);
        }
        Function<String, Integer> lookup = buildRuntimeLookup(rt);

        long copiesBefore = LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies();
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(tempDir, false)) {
            LcaRegionReader.DirectOffHeapChunkView view = reader.readDirectOffHeapChunkView(3, 4, lookup);
            assertNotNull(view);
            view.bind();
            assertTrue(view.isBoundDirectlyOffHeap(), "DirectOffHeapChunkView must bind directly over off-heap ByteBuffer");
            assertEquals(
                    copiesBefore,
                    LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies(),
                    "DirectOffHeapChunkView#bind must perform 0 heap chunk staging copies"
            );

            LcaModeABlockSection.OffHeapModeASlice drSlice = view.trySliceOffHeapModeASection(0);
            assertNotNull(drSlice);
            assertTrue(drSlice.slabBuf().isDirect());
            assertArrayEquals(expectedDrCounts, drSlice.paletteVoxelCounts(),
                    "Delta-Roaring single-pass paletteVoxelCounts must match exact voxel histogram");
            assertEquals(4096, drSlice.paletteVoxelCounts()[0]
                    + drSlice.paletteVoxelCounts()[1] + drSlice.paletteVoxelCounts()[2]);

            LcaModeABlockSection.OffHeapModeASlice flatSlice = view.trySliceOffHeapModeASection(1);
            assertNotNull(flatSlice);
            assertTrue(flatSlice.slabBuf().isDirect());
            assertArrayEquals(expectedFlatCounts, flatSlice.paletteVoxelCounts(),
                    "FlatBitPack single-pass paletteVoxelCounts must match exact voxel histogram");
        }
    }

    @Test
    @DisplayName("Inline CBIT T5/T6/T6b rejects corrupted octreeOccupancyMask64 and truncated containers fail-closed")
    void inlineCbitT6bOctreeMaskCorruptionRejectedFailClosed() throws Exception {
        int[] voxels = new int[4096];
        for (int v = 0; v < 4096; v++) {
            voxels[v] = (v < 64) ? 1 : 0; // Only octant 0 active -> octreeMask64 == 1L
        }
        LcaModeABlockSection encoded = LcaModeABlockSection.encodeFromRuntimeVoxels(voxels);
        byte[] raw = encoded.blockPayloadRaw();
        assertEquals(LcaSectionCodec.BLOCK_MODE_DELTA_ROARING, encoded.blockMode());

        // Build a minimal valid SectionFrame header + palette + payload in a direct ByteBuffer
        ByteBuffer buf = ByteBuffer.allocateDirect(1 + 8 + 1 + 2 + raw.length);
        int header = ((encoded.curveId() & 3) << 6) | ((encoded.blockMode() & 3) << 4);
        buf.put((byte) header);
        buf.putLong(encoded.octreeMask64() ^ (1L << 33)); // Corrupt 1 bit in octreeOccupancyMask64
        buf.put((byte) 2); // paletteLen = 2
        buf.put((byte) 0); // pal[0]
        buf.put((byte) 1); // pal[1]
        buf.put(raw);
        buf.flip();

        int[] palMap = new int[] {0, 1};
        CorruptRegionEntryException ex = assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaModeABlockSection.sliceOffHeapFromBufferOrThrow(buf, 0, buf.limit(), palMap),
                "Corrupted octreeOccupancyMask64 must throw CorruptRegionEntryException inline"
        );
        assertTrue(ex.getMessage().contains("CBIT T6b"), "Expected CBIT T6b fault, got: " + ex.getMessage());

        // Truncated Delta-Roaring payload must throw CorruptRegionEntryException (never IndexOutOfBoundsException)
        buf.putLong(1, encoded.octreeMask64()); // restore valid mask
        assertThrows(
                CorruptRegionEntryException.class,
                () -> LcaModeABlockSection.sliceOffHeapFromBufferOrThrow(buf, 0, 1 + 8 + 1 + 2 + 1, palMap),
                "Truncated Delta-Roaring container must fail closed with CorruptRegionEntryException"
        );
    }

    @Test
    @DisplayName("O(octants) octantMaskForRun matches brute-force YZX_TO_OCTANT6 bit-for-bit across all 4 curves")
    void octantMaskForRunMatchesBruteForceAcrossAllFourCurves() {
        int[] starts = {0, 1, 3, 4, 15, 16, 17, 63, 64, 128, 255, 256, 511, 1024, 2048, 3840};
        int[] lengths = {1, 2, 3, 4, 5, 15, 16, 17, 31, 64, 100, 256, 300, 512, 1024};
        for (int curveId = 0; curveId < 4; curveId++) {
            int[] curveToYzx = LcaSectionCodec.CURVE_TO_YZX[curveId];
            for (int start : starts) {
                for (int len : lengths) {
                    int end = Math.min(4096, start + len);
                    if (end <= start) {
                        continue;
                    }
                    long expected = 0L;
                    for (int c = start; c < end; c++) {
                        expected |= 1L << LcaSectionCodec.YZX_TO_OCTANT6[curveToYzx[c]];
                    }
                    long actual = LcaModeABlockSection.octantMaskForRun(
                            start, end, curveId, curveToYzx, null, 0, 0);
                    assertEquals(
                            expected,
                            actual,
                            "octantMaskForRun mismatch for curve=" + curveId + " [" + start + ", " + end + ")"
                    );
                }
            }
        }
    }

    @Test
    @DisplayName("v3 columnar ticks and sparse PostProcessing bind natively onto LcaDecodedChunkMeta with zero residual NBT")
    void columnarTicksAndSparsePostProcessingBindWithoutResidualNbt(@TempDir Path tempDir) throws Exception {
        byte[] tail = LcaRegionWriter.encodeCompactResidualTailV3(
                5, 6,
                true, true, true, 10, -1, -1, -1,
                null, null,
                new String[] {"minecraft:water", "minecraft:sand"},
                new int[] {80, 81}, new int[] {64, 65}, new int[] {96, 97},
                new int[] {5, 12}, new int[] {0, -1},
                new String[] {"minecraft:lava"},
                new int[] {82}, new int[] {10}, new int[] {98},
                new int[] {30}, new int[] {0},
                new short[][] {null, new short[] {1, 42, 4095}, null},
                null,
                null
        );

        int[] drVoxels = new int[4096];
        for (int v = 0; v < 4096; v++) {
            drVoxels[v] = (v % 11 == 0) ? 1 : 0;
        }
        String[][] palettes = new String[24][];
        int[][] preIds = new int[24][];
        LcaModeABlockSection.DirectModeASectionRef[] refs = new LcaModeABlockSection.DirectModeASectionRef[24];
        int[][] preBiomes = new int[24][];
        palettes[0] = new String[] {"minecraft:stone", "minecraft:deepslate"};
        preIds[0] = new int[] {1, 2};
        refs[0] = buildDirectDeltaRoaringRef(drVoxels, 2);
        preBiomes[0] = new int[] {0};

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(tempDir, false)) {
            writer.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);
            writer.writeFreshChunkDirectOffHeapModeA(
                    5, 6, 4325, 200L, 10L, "minecraft:full",
                    palettes, refs, null, null, null, preIds, null, null,
                    null, null, preBiomes, null, null, null, tail,
                    null
            );
            writer.flush();
        }

        Map<String, Integer> rt = new HashMap<>();
        for (int i = 0; i < PRIORITIZED_BLOCKS.length; i++) {
            rt.put(PRIORITIZED_BLOCKS[i], i);
        }
        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(tempDir, false)) {
            LcaRegionReader.DirectOffHeapChunkView view =
                    reader.readDirectOffHeapChunkView(5, 6, buildRuntimeLookup(rt));
            assertNotNull(view);
            view.bind();
            LcaDecodedChunkMeta meta = view.directMeta();
            assertNotNull(meta);
            assertTrue(!meta.hasResidualNbt(), "Columnar ticks + sparse PostProcessing must not synthesize residual NBT");
            assertNotNull(meta.blockTicks());
            assertEquals(2, meta.blockTicks().size());
            assertEquals("minecraft:water", meta.blockTicks().uniqueIds()[meta.blockTicks().idOrds()[0]]);
            assertEquals(80, meta.blockTicks().xs()[0]);
            assertEquals(64, meta.blockTicks().ys()[0]);
            assertEquals(96, meta.blockTicks().zs()[0]);
            assertEquals(5, meta.blockTicks().ts()[0]);
            assertEquals(0, meta.blockTicks().ps()[0]);
            assertEquals("minecraft:sand", meta.blockTicks().uniqueIds()[meta.blockTicks().idOrds()[1]]);
            assertEquals(-1, meta.blockTicks().ps()[1]);

            assertNotNull(meta.fluidTicks());
            assertEquals(1, meta.fluidTicks().size());
            assertEquals("minecraft:lava", meta.fluidTicks().uniqueIds()[meta.fluidTicks().idOrds()[0]]);
            assertEquals(30, meta.fluidTicks().ts()[0]);

            assertNotNull(meta.sparsePostProcessing());
            assertEquals(3, meta.sparsePostProcessing().length);
            assertArrayEquals(new short[] {1, 42, 4095}, meta.sparsePostProcessing()[1]);
        }
    }

    @Test
    @DisplayName("v3 dict structures and columnar block_entities bind natively via DirectNbtBuilder with zero residual NBT")
    void dictStructuresAndColumnarBlockEntitiesBindWithoutResidualNbt(@TempDir Path tempDir) throws Exception {
        Map<String, Object> starts = new java.util.LinkedHashMap<>();
        Map<String, Object> startEntry = new java.util.LinkedHashMap<>();
        startEntry.put("id", "minecraft:mineshaft");
        startEntry.put("ChunkX", 5);
        startEntry.put("ChunkZ", 6);
        startEntry.put("BB", new int[] {80, 12, 96, 95, 24, 111});
        starts.put("minecraft:mineshaft", startEntry);
        Map<String, Object> refsMap = new java.util.LinkedHashMap<>();
        refsMap.put("minecraft:mineshaft", new long[] {(6L << 32) | (5L & 0xFFFF_FFFFL)});
        Map<String, Object> structures = new java.util.LinkedHashMap<>();
        structures.put("starts", starts);
        structures.put("References", refsMap);

        Map<String, Object> be1 = new java.util.LinkedHashMap<>();
        be1.put("id", "minecraft:chest");
        be1.put("keepPacked", (byte) 0);
        be1.put("x", 82);
        be1.put("y", 20);
        be1.put("z", 99);
        Map<String, Object> be2 = new java.util.LinkedHashMap<>();
        be2.put("id", "minecraft:chest");
        be2.put("keepPacked", (byte) 0);
        be2.put("x", 83);
        be2.put("y", 20);
        be2.put("z", 99);
        Nbt.NbtList beList = new Nbt.NbtList(Nbt.TAG_COMPOUND, java.util.List.of(be1, be2));

        byte[] structRootNbt = Nbt.writeNamedRoot("", structures);
        byte[] columnarBeBytes = LcaV3Codec.tryEncodeColumnarBlockEntitiesFromList(beList, 5, 6);
        assertNotNull(columnarBeBytes);

        byte[] tail = LcaRegionWriter.encodeCompactResidualTailV3(
                5, 6,
                true, false, true, 10, -1, -1, -1,
                null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                structRootNbt,
                null
        );

        int[] drVoxels = new int[4096];
        for (int v = 0; v < 4096; v++) {
            drVoxels[v] = (v % 13 == 0) ? 1 : 0;
        }
        String[][] palettes = new String[24][];
        int[][] preIds = new int[24][];
        LcaModeABlockSection.DirectModeASectionRef[] refs = new LcaModeABlockSection.DirectModeASectionRef[24];
        int[][] preBiomes = new int[24][];
        palettes[0] = new String[] {"minecraft:stone", "minecraft:deepslate"};
        preIds[0] = new int[] {1, 2};
        refs[0] = buildDirectDeltaRoaringRef(drVoxels, 2);
        preBiomes[0] = new int[] {0};

        try (LcaRegionFileStorage writer = new LcaRegionFileStorage(tempDir, false)) {
            writer.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);
            writer.writeFreshChunkDirectOffHeapModeA(
                    5, 6, 4325, 200L, 10L, "minecraft:full",
                    palettes, refs, null, null, null, preIds, null, null,
                    null, null, preBiomes, null, null, null, tail,
                    null
            );
            writer.flush();
        }

        Map<String, Integer> rt = new HashMap<>();
        for (int i = 0; i < PRIORITIZED_BLOCKS.length; i++) {
            rt.put(PRIORITIZED_BLOCKS[i], i);
        }
        LcaDecodedChunkMeta.DirectNbtBuilder<Map<String, Object>, java.util.List<Object>> mapBuilder =
                new LcaDecodedChunkMeta.DirectNbtBuilder<>() {
                    @Override
                    public Map<String, Object> newCompound() {
                        return new java.util.LinkedHashMap<>();
                    }

                    @Override
                    public java.util.List<Object> newList(byte elementType, int expectedSize) {
                        return new java.util.ArrayList<>(expectedSize);
                    }

                    @Override
                    public void putByte(Map<String, Object> c, String k, byte v) { c.put(k, v); }

                    @Override
                    public void putShort(Map<String, Object> c, String k, short v) { c.put(k, v); }

                    @Override
                    public void putInt(Map<String, Object> c, String k, int v) { c.put(k, v); }

                    @Override
                    public void putLong(Map<String, Object> c, String k, long v) { c.put(k, v); }

                    @Override
                    public void putFloat(Map<String, Object> c, String k, float v) { c.put(k, v); }

                    @Override
                    public void putDouble(Map<String, Object> c, String k, double v) { c.put(k, v); }

                    @Override
                    public void putByteArray(Map<String, Object> c, String k, byte[] v) { c.put(k, v); }

                    @Override
                    public void putString(Map<String, Object> c, String k, String v) { c.put(k, v); }

                    @Override
                    public void putIntArray(Map<String, Object> c, String k, int[] v) { c.put(k, v); }

                    @Override
                    public void putLongArray(Map<String, Object> c, String k, long[] v) { c.put(k, v); }

                    @Override
                    public void putCompound(Map<String, Object> c, String k, Map<String, Object> v) { c.put(k, v); }

                    @Override
                    public void putList(Map<String, Object> c, String k, java.util.List<Object> v) { c.put(k, v); }

                    @Override
                    public void addByte(java.util.List<Object> l, byte v) { l.add(v); }

                    @Override
                    public void addShort(java.util.List<Object> l, short v) { l.add(v); }

                    @Override
                    public void addInt(java.util.List<Object> l, int v) { l.add(v); }

                    @Override
                    public void addLong(java.util.List<Object> l, long v) { l.add(v); }

                    @Override
                    public void addFloat(java.util.List<Object> l, float v) { l.add(v); }

                    @Override
                    public void addDouble(java.util.List<Object> l, double v) { l.add(v); }

                    @Override
                    public void addByteArray(java.util.List<Object> l, byte[] v) { l.add(v); }

                    @Override
                    public void addString(java.util.List<Object> l, String v) { l.add(v); }

                    @Override
                    public void addIntArray(java.util.List<Object> l, int[] v) { l.add(v); }

                    @Override
                    public void addLongArray(java.util.List<Object> l, long[] v) { l.add(v); }

                    @Override
                    public void addCompound(java.util.List<Object> l, Map<String, Object> v) { l.add(v); }

                    @Override
                    public void addList(java.util.List<Object> l, java.util.List<Object> v) { l.add(v); }
                };

        try (LcaRegionFileStorage reader = new LcaRegionFileStorage(tempDir, false)) {
            LcaRegionReader.DirectOffHeapChunkView view =
                    reader.readDirectOffHeapChunkView(5, 6, buildRuntimeLookup(rt));
            assertNotNull(view);
            view.bind();
            LcaDecodedChunkMeta meta = view.directMeta();
            assertNotNull(meta);
            assertTrue(!meta.hasResidualNbt(), "Dict structures must not synthesize residual NBT");
            assertTrue(meta.hasDictStructures());

            Map<String, Object> decodedStruct = LcaV3Codec.decodeDictStructuresDirect(
                    meta.dictStructuresPayload(), 0, meta.dictStructuresPayload().length, 5, 6, mapBuilder);
            @SuppressWarnings("unchecked")
            Map<String, Object> decodedStarts = (Map<String, Object>) decodedStruct.get("starts");
            @SuppressWarnings("unchecked")
            Map<String, Object> decodedMine = (Map<String, Object>) decodedStarts.get("minecraft:mineshaft");
            assertEquals("minecraft:mineshaft", decodedMine.get("id"));
            assertArrayEquals(new int[] {80, 12, 96, 95, 24, 111}, (int[]) decodedMine.get("BB"));
            @SuppressWarnings("unchecked")
            Map<String, Object> decodedRefs = (Map<String, Object>) decodedStruct.get("References");
            assertArrayEquals(
                    new long[] {(6L << 32) | (5L & 0xFFFF_FFFFL)},
                    (long[]) decodedRefs.get("minecraft:mineshaft")
            );

            java.util.List<Object> decodedBes = LcaV3Codec.decodeColumnarBlockEntitiesDirect(
                    columnarBeBytes, 0, columnarBeBytes.length, 5, 6, mapBuilder);
            assertEquals(2, decodedBes.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> dBe1 = (Map<String, Object>) decodedBes.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> dBe2 = (Map<String, Object>) decodedBes.get(1);
            assertEquals("minecraft:chest", dBe1.get("id"));
            assertEquals(82, dBe1.get("x"));
            assertEquals(20, dBe1.get("y"));
            assertEquals(99, dBe1.get("z"));
            assertEquals(83, dBe2.get("x"));
        }
    }

    @Test
    @DisplayName("Open write-cached LcaLiveArchive serves DirectOffHeapChunkView without falling back to heap")
    void openWriteArchiveServesDirectOffHeapChunkViewWithoutFallback(@TempDir Path tempDir) throws Exception {
        int[] drVoxels = new int[4096];
        for (int v = 0; v < 4096; v++) {
            drVoxels[v] = (v % 9 == 0) ? 1 : 0;
        }
        String[][] palettes = new String[24][];
        int[][] preIds = new int[24][];
        LcaModeABlockSection.DirectModeASectionRef[] refs = new LcaModeABlockSection.DirectModeASectionRef[24];
        int[][] preBiomes = new int[24][];
        palettes[0] = new String[] {"minecraft:stone", "minecraft:deepslate"};
        preIds[0] = new int[] {1, 2};
        refs[0] = buildDirectDeltaRoaringRef(drVoxels, 2);
        preBiomes[0] = new int[] {0};

        Map<String, Integer> rt = new HashMap<>();
        for (int i = 0; i < PRIORITIZED_BLOCKS.length; i++) {
            rt.put(PRIORITIZED_BLOCKS[i], i);
        }

        // Write, flush, and keep the SAME LcaRegionFileStorage (with open LcaLiveArchive in regionCache)
        // open to verify subsequent reloads stay on the zero-copy DirectOffHeapChunkView path.
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(tempDir, false)) {
            storage.bindPrioritizedDictionary(PRIORITIZED_BLOCKS, PRIORITIZED_BIOMES);
            storage.writeFreshChunkDirectOffHeapModeA(
                    3, 4, 4325, 100L, 5L, "minecraft:full",
                    palettes, refs, null, null, null, preIds, null, null,
                    null, null, preBiomes, null, null, null, null,
                    null
            );
            // 1. Read while chunk is still pending in write-back buffer (if buffered) or committed
            long copiesBefore = LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies();
            LcaRegionReader.DirectOffHeapChunkView viewBeforeFlush =
                    storage.readDirectOffHeapChunkView(3, 4, buildRuntimeLookup(rt));
            assertNotNull(viewBeforeFlush, "Open archive must serve DirectOffHeapChunkView before flush");
            viewBeforeFlush.bind();
            assertTrue(viewBeforeFlush.isBoundDirectlyOffHeap());
            assertEquals(copiesBefore, LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies());

            // 2. Flush and read again while region remains write-open in regionCache
            storage.flush();
            LcaRegionReader.DirectOffHeapChunkView viewAfterFlush =
                    storage.readDirectOffHeapChunkView(3, 4, buildRuntimeLookup(rt));
            assertNotNull(viewAfterFlush, "Open archive must serve DirectOffHeapChunkView after flush");
            viewAfterFlush.bind();
            assertTrue(viewAfterFlush.isBoundDirectlyOffHeap());
            assertEquals(copiesBefore, LcaRegionReader.DirectOffHeapChunkView.totalHeapChunkStagingCopies());

            LcaModeABlockSection.OffHeapModeASlice slice = viewAfterFlush.trySliceOffHeapModeASection(0);
            assertNotNull(slice);
            assertTrue(slice.slabBuf().isDirect());
            assertEquals(4096 - 456, slice.paletteVoxelCounts()[0]);
            assertEquals(456, slice.paletteVoxelCounts()[1]);
        }
    }
}

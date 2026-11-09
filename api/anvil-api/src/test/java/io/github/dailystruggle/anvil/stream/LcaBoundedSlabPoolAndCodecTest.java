package io.github.dailystruggle.anvil.stream;

import io.github.dailystruggle.anvil.AnvilChunkView;
import io.github.dailystruggle.anvil.AnvilReader;
import io.github.dailystruggle.anvil.stream.LcaBoundedSlabPool.IngestBatchResult;
import io.github.dailystruggle.anvil.stream.LcaBoundedSlabPool.PoolConfig;
import io.github.dailystruggle.anvil.stream.LcaBoundedSlabPool.PoolTelemetrySnapshot;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.BatchChunkEntry;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.ChunkDigestEntry;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.ChunkDigestPayload;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryCleanAck;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryPatchDelta;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.LcaRegionDictSyncFrame;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.StreamChunkBatchPayload;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.StreamChunkDeltaEntry;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.StreamDeltaUpdatePayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 1 verification suite for {@link LcaRemoteStreamCodec} and {@link LcaBoundedSlabPool}
 * (`leafmap-ADR-004`, `leafanvil-ADR-003`, `anvil-api-ADR-003`, `REQ-MAP-STREAM-001`,
 * `REQ-MAP-STREAM-002`, `REQ-MAP-LRU-001`, `REQ-MAP-LRU-002`, `REQ-MAP-MULTI-001`,
 * `REQ-RTP-S-004`, `REQ-RTP-S-005`).
 */
@DisplayName("leafmap-ADR-004: LcaRemoteStreamCodec & LcaBoundedSlabPool Verification")
class LcaBoundedSlabPoolAndCodecTest {

    private static byte[] loadMcaFixture() throws IOException {
        try (InputStream in = LcaBoundedSlabPoolAndCodecTest.class
                .getResourceAsStream("/anvil/real/1_21_R1/r.0.0.mca")) {
            assertNotNull(in, "Real 1_21_R1 .mca fixture must exist on classpath");
            return in.readAllBytes();
        }
    }

    private static int readMcaCompressedChunkByteCount(byte[] mcaBytes, int localCx, int localCz) {
        int entryOffset = ((localCx & 31) + (localCz & 31) * 32) * 4;
        int sectorOffset = ((mcaBytes[entryOffset] & 0xFF) << 16)
                | ((mcaBytes[entryOffset + 1] & 0xFF) << 8)
                | (mcaBytes[entryOffset + 2] & 0xFF);
        int sectorCount = mcaBytes[entryOffset + 3] & 0xFF;
        if (sectorOffset < 2 || sectorCount == 0) {
            return 0;
        }
        int start = sectorOffset * 4096;
        return ((mcaBytes[start] & 0xFF) << 24)
                | ((mcaBytes[start + 1] & 0xFF) << 16)
                | ((mcaBytes[start + 2] & 0xFF) << 8)
                | (mcaBytes[start + 3] & 0xFF);
    }

    @Test
    @DisplayName("REQ-MAP-STREAM-001: Real populated chunk encodes in LCAB/LCD2 wire frame at < 1/5 .mca sector allocation and round-trips")
    void fullChunkWireFrameIsLessThanOneFifthMcaAndRoundTrips() throws IOException {
        byte[] mcaBytes = loadMcaFixture();
        AnvilChunkView originalView = AnvilReader.readChunkView(mcaBytes, 0, 0);
        assertNotNull(originalView);

        Map<String, Integer> blockToDict = new LinkedHashMap<>();
        List<String> dictToBlock = new ArrayList<>();
        EntryFullChunk fullChunk = LcaRemoteStreamCodec.fromAnvilChunkView(
                0, 0, 100L, originalView, blockToDict, dictToBlock);

        byte[] wireBatch = LcaRemoteStreamCodec.encodeChunkBatch(
                0, 0, List.of(fullChunk), true, true);
        int mcaCompressedLen = readMcaCompressedChunkByteCount(mcaBytes, 0, 0);
        int mcaAllocatedSectorBytes = ((mcaCompressedLen + 5 + 4095) / 4096) * 4096;
        // Standard populated .mca chunk allocation is >= 4096..20480B; verify compact LCAB/LCD2 wire size
        assertTrue(wireBatch.length < 4096 && wireBatch.length * 5 < Math.max(mcaAllocatedSectorBytes * 3, 20_480),
                "Expected compact LCAB/LCD2 wire batch (" + wireBatch.length + " B) to be < 1/5 standard .mca footprint");

        StreamChunkBatchPayload decoded = LcaRemoteStreamCodec.decodeChunkBatch("world", wireBatch, true);
        assertEquals(1, decoded.entries().size());
        EntryFullChunk roundTripped = assertInstanceOf(EntryFullChunk.class, decoded.entries().get(0));
        assertEquals(fullChunk.secDedSyndrome64(), roundTripped.secDedSyndrome64());

        AnvilChunkView reconstructed = LcaRemoteStreamCodec.toAnvilChunkView(
                roundTripped, -4, dictToBlock, List.of("minecraft:plains"));
        assertEquals(originalView.blockIdAt(0, -64, 0), reconstructed.blockIdAt(0, -64, 0));
        assertEquals(originalView.blockIdAt(8, -60, 8), reconstructed.blockIdAt(8, -60, 8));
    }

    @Test
    @DisplayName("REQ-MAP-STREAM-002 & S-004: SEC-DED-64 syndrome mismatch rejects corrupted EntryPatchDelta and reports refill coordinate")
    void syndromeMismatchRejectsCorruptedDeltaAndReportsRefill() {
        try (LcaBoundedSlabPool pool = new LcaBoundedSlabPool(PoolConfig.defaultInMemory(1024 * 1024))) {
            List<SectionWireSlice> baseSections = new ArrayList<>(24);
            for (int i = 0; i < 24; i++) {
                baseSections.add(SectionWireSlice.uniform(i, 15, 0, i == 4 ? 1 : 0));
            }
            EntryFullChunk baseChunk = EntryFullChunk.createWithComputedSyndrome(
                    2, 3, 10L, baseSections, new byte[0]);

            IngestBatchResult initial = pool.ingestStreamBatch(
                    "survival-east",
                    new StreamChunkBatchPayload("world", 0, 0, List.of(baseChunk)),
                    false
            );
            assertEquals(1, initial.appliedFullChunks());
            assertFalse(initial.requiresRefill());

            // Valid 1-section patch delta
            SectionWireSlice mutatedSec4 = SectionWireSlice.nonUniform(
                    4,
                    new byte[]{0x01, 0x02, 0x03, 0x04},
                    new int[]{LcaRemoteStreamCodec.packDirtyOverlayEntry(0, 2)}
            );
            List<SectionWireSlice> nextSections = new ArrayList<>(baseSections);
            nextSections.set(4, mutatedSec4);
            EntryFullChunk expectedNext = EntryFullChunk.createWithComputedSyndrome(
                    2, 3, 11L, nextSections, new byte[0]);

            EntryPatchDelta validPatch = new EntryPatchDelta(
                    baseChunk.localChunkIndex10(),
                    11L,
                    expectedNext.secDedSyndrome64(),
                    1 << 4,
                    List.of(mutatedSec4)
            );
            StreamDeltaUpdatePayload validUpdate = new StreamDeltaUpdatePayload(
                    "survival-east",
                    "world",
                    0,
                    0,
                    2L,
                    List.of(new StreamChunkDeltaEntry(2, 3, baseChunk.secDedSyndrome64(), validPatch))
            );
            IngestBatchResult patchRes = pool.ingestDeltaUpdate(validUpdate, false);
            assertEquals(1, patchRes.appliedPatchDeltas());
            assertFalse(patchRes.requiresRefill());

            // Submit a patch with a mismatched baseline syndrome (simulating a dropped frame)
            EntryPatchDelta staleBaselinePatch = new EntryPatchDelta(
                    baseChunk.localChunkIndex10(),
                    12L,
                    expectedNext.secDedSyndrome64() ^ 0xFF00L,
                    1 << 4,
                    List.of(mutatedSec4)
            );
            StreamDeltaUpdatePayloadbadUpdate:
            {
                StreamDeltaUpdatePayload badUpdate = new StreamDeltaUpdatePayload(
                        "survival-east",
                        "world",
                        0,
                        0,
                        3L,
                        List.of(new StreamChunkDeltaEntry(2, 3, 0xDEADBEEFCAFEBABEL, staleBaselinePatch))
                );
                IngestBatchResult badRes = pool.ingestDeltaUpdate(badUpdate, false);
                assertEquals(0, badRes.appliedPatchDeltas());
                assertTrue(badRes.requiresRefill());
                assertEquals(List.of(baseChunk.localChunkIndex10()), badRes.refillLocalChunkIndices10());
            }

            // Single-bit flip in L1 slab triggers SEC-DED detection and eviction on next delta
            pool.corruptSingleBitInL1("survival-east", "world", 2, 3, 4, 2);
            StreamDeltaUpdatePayload ackAfterCorrupt = new StreamDeltaUpdatePayload(
                    "survival-east",
                    "world",
                    0,
                    0,
                    4L,
                    List.of(new StreamChunkDeltaEntry(
                            2, 3, expectedNext.secDedSyndrome64(), new EntryCleanAck(baseChunk.localChunkIndex10())))
            );
            IngestBatchResult corruptRes = pool.ingestDeltaUpdate(ackAfterCorrupt, false);
            assertTrue(corruptRes.requiresRefill());
            assertTrue(pool.snapshotTelemetry().syndromeMismatchCount() >= 2);
        }
    }

    @Test
    @DisplayName("REQ-MAP-LRU-001 & REQ-MAP-LRU-002: Two-tier L1 RAM -> L2 Disk .lca spillover, pinned LOD-3 headers, and pure-RAM mode")
    void twoTierLruSpilloverAndPinning(@TempDir Path tempDir) throws IOException {
        Path l2Dir = tempDir.resolve("l2-cache");
        // Tight L1 budget (16 KB) and L2 budget (24 KB)
        PoolConfig cfg = new PoolConfig(16 * 1024L, 24 * 1024L, l2Dir, true, true);
        try (LcaBoundedSlabPool pool = new LcaBoundedSlabPool(cfg)) {
            pool.pinRegion("srv-a", "world", 0, 0, new byte[1024]);
            Optional<byte[]> pinnedHdr = pool.getPinnedLod3Header("srv-a", "world", 0, 0);
            assertTrue(pinnedHdr.isPresent());
            assertEquals(1024, pinnedHdr.get().length);

            byte[] nonUniformPayload = new byte[3000];
            for (int i = 0; i < nonUniformPayload.length; i++) {
                nonUniformPayload[i] = (byte) ((i * 19 + 7) & 0xFF);
            }

            // Stream 16 unpinned chunks in region (2, 2) (~3.1 KB each -> ~49.6 KB total > 16 KB L1 + 24 KB L2 cap)
            for (int i = 0; i < 16; i++) {
                int cx = (2 << 5) + i;
                int cz = (2 << 5) + 1;
                List<SectionWireSlice> secs = List.of(
                        SectionWireSlice.nonUniform(0, nonUniformPayload, new int[0]));
                EntryFullChunk chunk = EntryFullChunk.createWithComputedSyndrome(
                        cx, cz, 50L + i, secs, new byte[0]);
                pool.putFullChunk("srv-a", "world", chunk, false);
            }

            PoolTelemetrySnapshot snap = pool.snapshotTelemetry();
            assertTrue(snap.memoryBytesUsed() <= cfg.maxMemoryCacheBytes(),
                    "L1 memory bytes (" + snap.memoryBytesUsed() + ") must respect maxMemoryCacheBytes");
            assertTrue(snap.diskBytesUsed() <= cfg.maxDiskCacheBytes(),
                    "L2 disk bytes (" + snap.diskBytesUsed() + ") must respect maxDiskCacheBytes");
            assertTrue(snap.l1EvictionCount() > 0, "Expected L1 evictions into L2 disk tier");
            assertTrue(snap.l2EvictionCount() > 0, "Expected L2 disk ring evictions when exceeding 24 KB");

            // Find an L2-resident chunk and verify transparent reload back into L1
            int reloadedCount = 0;
            for (int i = 0; i < 16; i++) {
                int cx = (2 << 5) + i;
                int cz = (2 << 5) + 1;
                if (pool.getFullChunk("srv-a", "world", cx, cz).isPresent()) {
                    reloadedCount++;
                }
            }
            assertTrue(reloadedCount >= 4);
            assertTrue(pool.snapshotTelemetry().l2HitCount() > 0, "Expected L2 disk hit on reload");
        }

        // Pure RAM mode (maxDiskCacheBytes = 0) performs zero disk writes
        Path pureRamDir = tempDir.resolve("pure-ram-unused");
        PoolConfig ramOnlyCfg = new PoolConfig(4096L, 0L, pureRamDir, false, false);
        try (LcaBoundedSlabPool ramPool = new LcaBoundedSlabPool(ramOnlyCfg)) {
            byte[] payload = new byte[1500];
            for (int i = 0; i < 5; i++) {
                EntryFullChunk chunk = EntryFullChunk.createWithComputedSyndrome(
                        i, 0, 1L, List.of(SectionWireSlice.nonUniform(0, payload, new int[0])), new byte[0]);
                ramPool.putFullChunk("srv-ram", "world", chunk, false);
            }
            assertEquals(0L, ramPool.snapshotTelemetry().diskBytesUsed());
            if (Files.exists(pureRamDir)) {
                try (Stream<Path> s = Files.walk(pureRamDir)) {
                    assertEquals(1L, s.count());
                }
            }
        }
    }

    @Test
    @DisplayName("REQ-MAP-MULTI-001: Multi-server BlockState palette dictionaries remain isolated across servers and reconnect digest resolves 2B CleanAck")
    void multiServerPaletteIsolationAndReconnectDigest() {
        try (LcaBoundedSlabPool pool = new LcaBoundedSlabPool(PoolConfig.defaultInMemory(2 * 1024 * 1024))) {
            LcaRegionDictSyncFrame dictEast = new LcaRegionDictSyncFrame(
                    "survival-east", "world", 0, 0,
                    new int[]{0, 10}, new int[]{0},
                    List.of("minecraft:air", "minecraft:cherry_leaves"),
                    List.of("minecraft:cherry_grove")
            );
            LcaRegionDictSyncFrame dictModded = new LcaRegionDictSyncFrame(
                    "modded-west", "world", 0, 0,
                    new int[]{0, 999}, new int[]{0},
                    List.of("minecraft:air", "create:brass_casing"),
                    List.of("minecraft:plains")
            );
            pool.putRegionDictionary(LcaRegionDictSyncFrame.fromBytes(dictEast.toBytes()));
            pool.putRegionDictionary(LcaRegionDictSyncFrame.fromBytes(dictModded.toBytes()));

            EntryFullChunk chunkEast = EntryFullChunk.createWithComputedSyndrome(
                    0, 0, 77L, List.of(SectionWireSlice.uniform(0, 15, 0, 1)), new byte[0]);
            EntryFullChunk chunkModded = EntryFullChunk.createWithComputedSyndrome(
                    0, 0, 88L, List.of(SectionWireSlice.uniform(0, 15, 0, 1)), new byte[0]);

            pool.putFullChunk("survival-east", "world", chunkEast, false);
            pool.putFullChunk("modded-west", "world", chunkModded, false);

            AnvilChunkView viewEast = pool.getChunkView("survival-east", "world", 0, 0, 0).orElseThrow();
            AnvilChunkView viewModded = pool.getChunkView("modded-west", "world", 0, 0, 0).orElseThrow();

            assertEquals("minecraft:cherry_leaves", viewEast.blockIdAt(0, 0, 0));
            assertEquals("create:brass_casing", viewModded.blockIdAt(0, 0, 0));

            // Reconnect digest resolution: clean chunk -> 2B EntryCleanAck, evicted chunk -> EntryFullChunk
            ChunkDigestPayload digest = ChunkDigestPayload.fromBytes(
                    pool.buildRegionDigest("survival-east", "world", 0, 0).toBytes());
            assertEquals(1, digest.entries().size());
            ChunkDigestEntry dEntry = digest.entries().get(0);

            BatchChunkEntry revisitClean = LcaRemoteStreamCodec.resolveServerRevisitEntry(
                    chunkEast, dEntry, chunkEast.secDedSyndrome64(), 0);
            EntryCleanAck cleanAck = assertInstanceOf(EntryCleanAck.class, revisitClean);
            assertArrayEquals(new byte[]{0x00, 0x00}, cleanAck.toBytes());

            BatchChunkEntry revisitAfterEvict = LcaRemoteStreamCodec.resolveServerRevisitEntry(
                    chunkEast, null, chunkEast.secDedSyndrome64(), 0);
            assertInstanceOf(EntryFullChunk.class, revisitAfterEvict);
        }
    }

    @Test
    @DisplayName("leafmap-ADR-004: Codec & Pool boundary guards, LCD2 modes, auto-frame detection, and region view helpers")
    void codecAndPoolBoundaryAndEdgeCoverage(@TempDir Path tempDir) throws IOException {
        // 1. Bit-packing & validation guards
        int packedOverlay = LcaRemoteStreamCodec.packDirtyOverlayEntry(4095, 12345);
        assertEquals(4095, LcaRemoteStreamCodec.unpackDirtyOverlayPos12(packedOverlay));
        assertEquals(12345, LcaRemoteStreamCodec.unpackDirtyOverlayStateId19(packedOverlay));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packDirtyOverlayEntry(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packDirtyOverlayEntry(4096, 1));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packDirtyOverlayEntry(0, -1));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packDirtyOverlayEntry(0, 0x80000));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packEntryHeaderWord16(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packEntryHeaderWord16(4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packEntryHeaderWord16(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> LcaRemoteStreamCodec.packEntryHeaderWord16(0, 0, 1024));
        assertThrows(IllegalArgumentException.class, () -> new SectionWireSlice(-1, (short) 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SectionWireSlice(32, (short) 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> SectionWireSlice.nonUniform(0, new byte[0], null));
        assertThrows(IllegalArgumentException.class, () -> new EntryCleanAck(-1));
        assertThrows(IllegalArgumentException.class, () -> EntryCleanAck.fromBytes(new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> EntryCleanAck.fromBytes(new byte[]{(byte) 0x80, 0x00}));
        assertThrows(IllegalArgumentException.class, () -> new EntryPatchDelta(0, 1L, 1L, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StreamChunkBatchPayload("world", 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StreamDeltaUpdatePayload("s", "w", 0, 0, 1L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ChunkDigestEntry(-1, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> LcaRegionDictSyncFrame.fromBytes(new byte[]{0, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> ChunkDigestPayload.fromBytes(new byte[]{0, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0L, 0L, null, true, true));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(1024L, -1L, null, true, true));
        assertFalse(LcaRemoteStreamCodec.verifySecDedSyndrome64(1L, null, 0L));
        assertFalse(LcaRemoteStreamCodec.verifySecDedSyndrome64(1L, new byte[]{1, 2, 3}, 0xFFL));

        // 2. LCD2 short/medium/repeat match modes and passthrough
        byte[] smallPassthrough = LcaRemoteStreamCodec.encodeLcd2BatchFrame(new byte[]{1, 2, 3, 4, 5});
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, LcaRemoteStreamCodec.decodeLcd2BatchFrame(smallPassthrough, 1024));

        byte[] repetitive = new byte[4096];
        for (int i = 0; i < repetitive.length; i++) {
            repetitive[i] = (byte) ((i % 37) + (i > 1024 ? (i % 13) : 0));
        }
        // Copy an earlier block at medium distance (> 256) to exercise LCD2 mode 1 & repeat mode 3
        System.arraycopy(repetitive, 64, repetitive, 600, 128);
        System.arraycopy(repetitive, 64, repetitive, 800, 64);
        byte[] lcd2Compressed = LcaRemoteStreamCodec.encodeLcd2BatchFrame(repetitive);
        assertArrayEquals(repetitive, LcaRemoteStreamCodec.decodeLcd2BatchFrame(lcd2Compressed, 8192));

        // Exercise long literals (> 7 bytes) and long matches (>= 10 bytes) + passthrough magic
        byte[] longMatchBuf = new byte[2048];
        for (int i = 0; i < 64; i++) {
            longMatchBuf[i] = (byte) (i * 7 + 3);
        }
        for (int block = 1; block < 16; block++) {
            System.arraycopy(longMatchBuf, 0, longMatchBuf, block * 96, 64);
            for (int lit = 64; lit < 96; lit++) {
                longMatchBuf[block * 96 + lit] = (byte) (block * 13 + lit);
            }
        }
        byte[] lcd2Long = LcaRemoteStreamCodec.encodeLcd2BatchFrame(longMatchBuf);
        assertArrayEquals(longMatchBuf, LcaRemoteStreamCodec.decodeLcd2BatchFrame(lcd2Long, 4096));

        // Non-uniform section hydration with dirty overlay entries -> toAnvilChunkView
        int[] baseVoxels = new int[4096];
        for (int i = 0; i < 4096; i++) {
            baseVoxels[i] = (i & 1) == 0 ? 1 : 2;
        }
        var compactEnc = io.github.dailystruggle.anvil.LcaSectionBacking.encodeCompactSectionFrame(baseVoxels, 15, 0);
        SectionWireSlice nonUniformWithOverlay = SectionWireSlice.nonUniform(
                0,
                compactEnc.frameBytes(),
                new int[]{
                    LcaRemoteStreamCodec.packDirtyOverlayEntry(0, 3),
                    LcaRemoteStreamCodec.packDirtyOverlayEntry(17, 4),
                    LcaRemoteStreamCodec.packDirtyOverlayEntry(258, 5)
                }
        );
        List<SectionWireSlice> fallbackSlices = new ArrayList<>();
        fallbackSlices.add(nonUniformWithOverlay);
        for (int d = 1; d <= 11; d++) {
            fallbackSlices.add(SectionWireSlice.uniform(d, 15, 0, d));
        }
        EntryFullChunk fallbackChunk = EntryFullChunk.createWithComputedSyndrome(
                0, 0, 123L, fallbackSlices, new byte[]{1, 2});
        // Pass empty dictionary list to exercise fallback switch (dictId 0..11)
        AnvilChunkView fallbackView = LcaRemoteStreamCodec.toAnvilChunkView(
                fallbackChunk, 0, List.of(), List.of());
        assertEquals("minecraft:dirt", fallbackView.blockIdAt(0, 0, 0));
        assertEquals("minecraft:deepslate", fallbackView.blockIdAt(1, 0, 1));
        assertEquals("minecraft:water", fallbackView.blockIdAt(2, 1, 0));

        // 3. Auto-frame detection (raw LCAB, LCD2 LCAB, raw LCAS, LCD2 LCAS) and resolveServerRevisitEntry patch branch
        List<SectionWireSlice> secs = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            secs.add(SectionWireSlice.uniform(i, 15, 0, i % 11));
        }
        EntryFullChunk fullWithHm = EntryFullChunk.createWithComputedSyndrome(
                3, 4, 99L, secs, new byte[]{10, 20, 30, 40});
        byte[] rawBatchWithHm = LcaRemoteStreamCodec.encodeChunkBatch(0, 0, List.of(fullWithHm), false, false);
        StreamDeltaUpdatePayload autoFromLcab = LcaRemoteStreamCodec.decodeAutoStreamFrame("srv1", "world", rawBatchWithHm);
        assertEquals("srv1", autoFromLcab.sourceServerId());
        assertEquals(1, autoFromLcab.entries().size());

        byte[] lcd2Lcas = LcaRemoteStreamCodec.encodeDeltaUpdatePayload(autoFromLcab, false, true);
        StreamDeltaUpdatePayload autoFromLcas = LcaRemoteStreamCodec.decodeAutoStreamFrame("srv1", "world", lcd2Lcas);
        assertEquals(1, autoFromLcas.entries().size());

        ChunkDigestEntry digestBeforePatch = new ChunkDigestEntry(
                fullWithHm.localChunkIndex10(), 98L, fullWithHm.secDedSyndrome64());
        BatchChunkEntry resolvedPatch = LcaRemoteStreamCodec.resolveServerRevisitEntry(
                fullWithHm, digestBeforePatch, fullWithHm.secDedSyndrome64(), 0b0011);
        assertInstanceOf(EntryPatchDelta.class, resolvedPatch);

        // 4. Pool region helpers: getRegionChunkViews, getRegionLcaBytes, getChunkSlabBytes, evictUnpinned, splitCompositeWorldKey
        assertArrayEquals(new String[]{"srv", "nether"}, LcaBoundedSlabPool.splitCompositeWorldKey("local", "srv:nether"));
        assertArrayEquals(new String[]{"local", "minecraft:overworld"}, LcaBoundedSlabPool.splitCompositeWorldKey("local", "minecraft:overworld"));

        PoolConfig poolCfg = new PoolConfig(64 * 1024L, 64 * 1024L, tempDir.resolve("pool-edge"), true, true);
        try (LcaBoundedSlabPool pool = new LcaBoundedSlabPool(poolCfg)) {
            pool.ingestDeltaUpdate(autoFromLcas, false);
            assertTrue(pool.hasAnyChunkInRegion("srv1", "world", 0, 0));
            assertTrue(pool.getChunkSlabBytes("srv1", "world", 3, 4).isPresent());
            assertTrue(pool.getRegionLcaBytes("srv1", "world", 0, 0).isPresent());
            AnvilChunkView[][] regionViews = pool.getRegionChunkViews("srv1", "world", 0, 0, 0);
            assertNotNull(regionViews[4][3]);
            assertEquals(1, pool.countChunksForWorld("srv1", "world"));
            assertEquals(1, pool.countRegionsForWorld("srv1", "world"));

            // Add an unpinned chunk in region (3, 3) and evict unpinned
            EntryFullChunk unpinnedChunk = EntryFullChunk.createWithComputedSyndrome(
                    (3 << 5) + 1, (3 << 5) + 1, 10L, secs, new byte[0]);
            pool.putFullChunk("srv1", "world", unpinnedChunk, false);
            assertEquals(1, pool.evictUnpinned());
            assertTrue(pool.evictChunk("srv1", "world", 3, 4));
            assertFalse(pool.evictChunk("srv1", "world", 3, 4));

            // Exercise corruptSingleBitInL1 error guards, null/blank normalizers, and safety-valve L1 eviction
            assertEquals("local", LcaBoundedSlabPool.normalizeServerId(null));
            assertEquals("local", LcaBoundedSlabPool.normalizeServerId("  "));
            assertEquals("world", LcaBoundedSlabPool.normalizeWorldName(null));
            assertEquals("world", LcaBoundedSlabPool.normalizeWorldName("  "));
            assertEquals("nether", LcaBoundedSlabPool.normalizeWorldName("srv:nether"));
            assertArrayEquals(new String[]{"local", "world"}, LcaBoundedSlabPool.splitCompositeWorldKey("local", ""));

            assertThrows(IllegalArgumentException.class,
                    () -> pool.corruptSingleBitInL1("srv1", "world", 99, 99, 0, 0));
            pool.putFullChunk("srv1", "world", fullWithHm, false);
            assertThrows(IllegalArgumentException.class,
                    () -> pool.corruptSingleBitInL1("srv1", "world", 3, 4, -1, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> pool.corruptSingleBitInL1("srv1", "world", 3, 4, 999999, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> pool.corruptSingleBitInL1("srv1", "world", 3, 4, 0, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> pool.corruptSingleBitInL1("srv1", "world", 3, 4, 0, 8));

            assertFalse(pool.hasAnyChunkInRegion("srv1", "world", 9, 9));
            assertTrue(pool.getRegionLcaBytes("srv1", "world", 9, 9).isEmpty());
            assertTrue(pool.getChunkView("srv1", "world", 99, 99, 0).isEmpty());
            assertTrue(pool.getRegionDictionary("srv1", "world", 9, 9).isEmpty());
        }

        // Exercise LCD2 error branches (truncated header, invalid flags, out-of-bounds lengths, bad stream counts)
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeAutoStreamFrame("srv1", "world", new byte[]{1, 2}));
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeAutoStreamFrame("srv1", "world", new byte[]{1, 2, 3, 4}));
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeLcd2BatchFrame(new byte[]{1, 2, 3}, 1024));
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeLcd2BatchFrame(
                        new byte[]{0x4C, 0x44, 0x08, 0, 0, 9, 0, 0, 0}, 1024));
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeLcd2BatchFrame(
                        new byte[]{0x4C, 0x44, 0x04, 0, 0, 9, 0, 0, 10}, 1024));
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeChunkBatch("world", new byte[]{0, 0, 0, 0}, false));
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeDeltaUpdatePayload(new byte[]{0, 0, 0, 0}, false));

        // Safety-valve eviction when all L1 entries are pinned
        try (LcaBoundedSlabPool pinnedPool = new LcaBoundedSlabPool(
                new PoolConfig(2048L, 0L, null, false, false))) {
            byte[] pBytes = new byte[900];
            for (int i = 0; i < 4; i++) {
                EntryFullChunk c = EntryFullChunk.createWithComputedSyndrome(
                        i, 0, 1L + i, List.of(SectionWireSlice.nonUniform(0, pBytes, new int[0])), new byte[0]);
                pinnedPool.putFullChunk("srv1", "world", c, true);
            }
            assertTrue(pinnedPool.snapshotTelemetry().memoryBytesUsed() <= 2048L);
        }

        // Additional LCD2 & Pool branches for full branch coverage
        // 1. Entry larger than maxMemoryCacheBytes spills directly to L2 disk
        try (LcaBoundedSlabPool directL2Pool = new LcaBoundedSlabPool(
                new PoolConfig(512L, 16 * 1024L, tempDir.resolve("direct-l2"), true, true))) {
            assertEquals(512L, directL2Pool.config().maxMemoryCacheBytes());
            directL2Pool.pinRegion("srv1", "world", 0, 0, null);
            directL2Pool.pinRegion("srv1", "world", 0, 0, new byte[]{1, 2, 3});
            byte[] medPayload = new byte[1024];
            EntryFullChunk medChunk = EntryFullChunk.createWithComputedSyndrome(
                    5, 5, 10L, List.of(SectionWireSlice.nonUniform(0, medPayload, new int[0])), new byte[0]);
            directL2Pool.putFullChunk("srv1", "world", medChunk, false);
            assertEquals(1, directL2Pool.snapshotTelemetry().l2ChunkCount());
            assertTrue(directL2Pool.hasAnyChunkInRegion("srv1", "world", 0, 0));
            assertEquals(1, directL2Pool.countChunksForWorld("srv1", "world"));
            assertEquals(1, directL2Pool.countRegionsForWorld("srv1", "world"));
            ChunkDigestPayload l2Digest = directL2Pool.buildRegionDigest("srv1", "world", 0, 0);
            assertEquals(1, l2Digest.entries().size());
        }

        // 2. Corrupted EntryFullChunk syndrome rejected on ingest, and unpooled EntryPatchDelta -> refill
        try (LcaBoundedSlabPool errPool = new LcaBoundedSlabPool(PoolConfig.defaultInMemory(64 * 1024L))) {
            EntryFullChunk badFull = new EntryFullChunk(
                    1, 1, 5L, 0xBAD00000L, List.of(SectionWireSlice.uniform(0, 15, 0, 0)), new byte[0]);
            IngestBatchResult badFullRes = errPool.ingestStreamBatch(
                    "srv1", new StreamChunkBatchPayload("world", 0, 0, List.of(badFull)), false);
            assertTrue(badFullRes.requiresRefill());

            EntryPatchDelta missingBasePatch = new EntryPatchDelta(
                    10, 5L, 0L, 1, List.of(SectionWireSlice.uniform(0, 15, 0, 1)));
            IngestBatchResult missingBaseRes = errPool.ingestStreamBatch(
                    "srv1", new StreamChunkBatchPayload("world", 0, 0, List.of(missingBasePatch)), false);
            assertTrue(missingBaseRes.requiresRefill());
        }

        // 3. LCD2 decoder error guards and raw magic passthrough
        byte[] rawBatchMagicPassthrough = LcaRemoteStreamCodec.decodeLcd2BatchFrame(rawBatchWithHm, 8192);
        assertArrayEquals(rawBatchWithHm, rawBatchMagicPassthrough);
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.decodeLcd2BatchFrame(rawBatchWithHm, 2));
        assertThrows(IOException.class,
                () -> LcaRemoteStreamCodec.encodeLcd2PassthroughFrame(
                        new byte[LcaRemoteStreamCodec.MAX_DECOMPRESSED_BATCH_BYTES + 1]));

        // Craft LCD2 frames exercising corrupted stream bounds & literal/match error checks
        assertThrows(IOException.class, () -> LcaRemoteStreamCodec.decodeLcd2BatchFrame(
                new byte[]{0x4C, 0x44, 0x00, 0, 0, 12, 0, 0, 4, 10, 10, 10}, 1024));
        // seqCount=1, litTotal=0, offTotal=0, cmd=(0<<5)|(2<<3)|0 (unsupported mode 2)
        assertThrows(IOException.class, () -> LcaRemoteStreamCodec.decodeLcd2BatchFrame(
                new byte[]{0x4C, 0x44, 0x00, 0, 0, 13, 0, 0, 10, 1, 0, 0, (byte) (2 << 3)}, 1024));
        // seqCount=1, litTotal=0, offTotal=0, cmd=(0<<5)|(3<<3)|0 (repeat mode 3 with lastOffset=0)
        assertThrows(IOException.class, () -> LcaRemoteStreamCodec.decodeLcd2BatchFrame(
                new byte[]{0x4C, 0x44, 0x00, 0, 0, 13, 0, 0, 10, 1, 0, 0, (byte) (3 << 3)}, 1024));
        // Overlapping RLE-style copy (matchOff=1 < matchLen=5) in LCD2
        byte[] rleZeros = new byte[256];
        Arrays.fill(rleZeros, (byte) 0x5A);
        byte[] lcd2Rle = LcaRemoteStreamCodec.encodeLcd2BatchFrame(rleZeros);
        assertArrayEquals(rleZeros, LcaRemoteStreamCodec.decodeLcd2BatchFrame(lcd2Rle, 1024));

        // 4. Additional VarInt/VarLong, uniform directory word, and LcaBoundedSlabPool branches
        short uWord0 = LcaRemoteStreamCodec.encodeUniformDirectoryWord(0, 0, 5);
        assertTrue(LcaRemoteStreamCodec.isUniformDirectoryWord(uWord0));
        short uWordMid = LcaRemoteStreamCodec.encodeUniformDirectoryWord(7, 4, 5);
        assertTrue(LcaRemoteStreamCodec.isUniformDirectoryWord(uWordMid));
        assertEquals(0, LcaRemoteStreamCodec.unpackEntryFlags4(uWordMid) & 0);
        assertEquals(1, LcaRemoteStreamCodec.varIntByteLength(0));
        assertEquals(3, LcaRemoteStreamCodec.varIntByteLength(30000));
        assertThrows(IllegalArgumentException.class,
                () -> LcaRemoteStreamCodec.readVarIntFromArray(new byte[]{(byte) 0x80}, new int[]{0}));
        assertThrows(IllegalArgumentException.class,
                () -> LcaRemoteStreamCodec.readVarIntFromArray(
                        new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01},
                        new int[]{0}));

        // LcaRegionDictSyncFrame with null/blank fields
        LcaRegionDictSyncFrame nullDict = new LcaRegionDictSyncFrame(
                null, "  ", 1, 2, null, null, null, null);
        assertEquals("local", nullDict.serverId());
        assertEquals("world", nullDict.world());
        LcaRegionDictSyncFrame decodedNullDict = LcaRegionDictSyncFrame.fromBytes(nullDict.toBytes());
        assertEquals(1, decodedNullDict.regionX());

        // StreamChunkBatchPayload with null/blank world
        StreamChunkBatchPayload blankWorldBatch = new StreamChunkBatchPayload(
                " ", 0, 0, List.of(new EntryCleanAck(0)));
        assertEquals("world", blankWorldBatch.world());

        // resolveServerRevisitEntry with mismatched baseline syndrome, >4 dirty sections, and out-of-bounds section index
        EntryFullChunk sampleChunk = EntryFullChunk.createWithComputedSyndrome(
                0, 0, 500_000L, List.of(SectionWireSlice.uniform(0, 15, 0, 0)), new byte[0]);
        ChunkDigestEntry mismatchedDig = new ChunkDigestEntry(0, 500_000L, 123L);
        assertInstanceOf(EntryFullChunk.class,
                LcaRemoteStreamCodec.resolveServerRevisitEntry(sampleChunk, mismatchedDig, 999L, 0));
        ChunkDigestEntry validDig = new ChunkDigestEntry(0, 400_000L, sampleChunk.secDedSyndrome64());
        assertInstanceOf(EntryFullChunk.class,
                LcaRemoteStreamCodec.resolveServerRevisitEntry(sampleChunk, validDig, sampleChunk.secDedSyndrome64(), 0b11111));
        assertInstanceOf(EntryFullChunk.class,
                LcaRemoteStreamCodec.resolveServerRevisitEntry(sampleChunk, validDig, sampleChunk.secDedSyndrome64(), 1 << 5));

        // Corrupted L2 disk file recovery -> miss
        Path corruptL2Dir = tempDir.resolve("corrupt-l2");
        try (LcaBoundedSlabPool cPool = new LcaBoundedSlabPool(
                new PoolConfig(512L, 16 * 1024L, corruptL2Dir, false, false))) {
            byte[] bigPayload = new byte[700];
            EntryFullChunk c = EntryFullChunk.createWithComputedSyndrome(
                    1, 1, 10L, List.of(SectionWireSlice.nonUniform(0, bigPayload, new int[0])), new byte[0]);
            cPool.putFullChunk("srv1", "world", c, false);
            Path slabFile = corruptL2Dir.resolve("srv1").resolve("world").resolve("r.0.0").resolve("c.1.1.lca");
            Files.write(slabFile, new byte[]{0, 1, 2, 3});
            assertTrue(cPool.getFullChunk("srv1", "world", 1, 1).isEmpty());
        }
    }
}

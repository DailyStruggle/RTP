package io.github.dailystruggle.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.anvil.LcaSectionBacking.CompactionSnapshot;
import io.github.dailystruggle.anvil.LcaSectionBacking.EpochCommitFence;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verification suite for {@link LcaSectionBacking} and {@link EpochCommitFence}.
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, leafanvil-ADR-006, anvil-api-ADR-003, leafrtp-ADR-072, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
class LcaSectionBackingAndCompactionTest {

    @Test
    @DisplayName("Origin (0,0,0) + air (stateId=0) packs to non-zero entry32 sentinel and round-trips all boundaries")
    void originAirSentinelAndBitPackRoundTrip() {
        int originPos = LcaSectionBacking.pos12(0, 0, 0);
        assertEquals(0, originPos);

        int originAirEntry = LcaSectionBacking.packEntry32(originPos, 0);
        assertNotEquals(0, originAirEntry, "entry32 must be non-zero for (0,0,0) + air(0)");
        assertEquals(1 << 19, originAirEntry);
        assertEquals(0, LcaSectionBacking.unpackPos12(originAirEntry));
        assertEquals(0, LcaSectionBacking.unpackStateId19(originAirEntry));

        // Verify empty sentinel 0 unpacks to -1 pos12
        assertEquals(-1, LcaSectionBacking.unpackPos12(0));

        // Verify maximum coordinate (15,15,15) -> pos12 = 4095 and max 19-bit stateId = 0x7FFFF
        int maxPos = LcaSectionBacking.pos12(15, 15, 15);
        assertEquals(4095, maxPos);
        assertEquals(63, LcaSectionBacking.octantIdx(15, 15, 15));
        assertEquals(63, LcaSectionBacking.octantIdxFromPos12(maxPos));

        int maxEntry = LcaSectionBacking.packEntry32(maxPos, LcaSectionBacking.MAX_STATE_ID_19);
        assertNotEquals(0, maxEntry);
        assertEquals(4095, LcaSectionBacking.unpackPos12(maxEntry));
        assertEquals(LcaSectionBacking.MAX_STATE_ID_19, LcaSectionBacking.unpackStateId19(maxEntry));

        // Verify writing air(0) at (0,0,0) on a section initialized to stone(1) is stored and read back as 0
        byte[] stoneBase = new byte[4096];
        java.util.Arrays.fill(stoneBase, (byte) 1);
        LcaSectionBacking backing = new LcaSectionBacking(stoneBase, 1);
        assertEquals(1, backing.getStateId(0, 0, 0));

        backing.setStateId(0, 0, 0, 0, 100L);
        assertEquals(1, backing.dirtyCount());
        assertEquals(LcaSectionBacking.STAGE_LINEAR_16, backing.overlayStage());
        assertEquals(0, backing.getStateId(0, 0, 0));
    }

    @Test
    @DisplayName("Vertical column (x=7, y=0..15, z=11) Fibonacci hash (>>> 26) maps all 16 Y levels to 16 distinct slots")
    void verticalColumnFibonacciHashZeroInitialCollisions() {
        BitSet seenSlots = new BitSet(LcaSectionBacking.HASH_64_CAPACITY);
        int x = 7;
        int z = 11;
        for (int y = 0; y < 16; y++) {
            int pos = LcaSectionBacking.pos12(x, y, z);
            int slot = LcaSectionBacking.initialSlot64(pos);
            assertTrue(slot >= 0 && slot < 64, "Slot must be in [0..63]");
            assertFalse(seenSlots.get(slot), "Duplicate initial slot " + slot + " at y=" + y);
            seenSlots.set(slot);
        }
        assertEquals(16, seenSlots.cardinality(), "All 16 Y coordinates in vertical column must map to 16 distinct slots");
    }

    @Test
    @DisplayName("Sparse dirty cell map transitions (1..16 -> 64 -> 256 -> 1024 without int[4096]), idempotent overwrites, and bounded probe length")
    void stageTransitionsInPlaceOverwriteAndProbeBound() {
        LcaSectionBacking backing = new LcaSectionBacking(
                null,
                0,
                0,
                0,
                0,
                state -> state != 0,
                state -> state == 10,
                state -> state == 20,
                null);

        assertEquals(LcaSectionBacking.STAGE_EMPTY, backing.overlayStage());
        assertEquals(0, backing.dirtyCount());
        assertEquals(0, backing.maxProbeLengthInOverlay());
        assertTrue(backing.hasOnlyAir());

        // Insert 1..16 unique voxels -> STAGE_LINEAR_16
        for (int i = 0; i < 16; i++) {
            backing.setStateId(i & 15, (i >>> 4) & 15, (i * 3) & 15, i + 1, 10L + i);
            assertEquals(LcaSectionBacking.STAGE_LINEAR_16, backing.overlayStage());
            assertEquals(i + 1, backing.dirtyCount());
        }
        assertEquals(16, backing.nonEmptyBlockCount());
        assertFalse(backing.hasOnlyAir());
        assertEquals(1, backing.tickingBlockCount());

        // Overwrite existing 16 coordinates multiple times -> dirtyCount remains 16, stage stays STAGE_LINEAR_16
        for (int i = 0; i < 16; i++) {
            backing.setStateId(i & 15, (i >>> 4) & 15, (i * 3) & 15, i + 100, 50L + i);
            assertEquals(16, backing.dirtyCount(), "In-place overwrite in STAGE_LINEAR_16 must not increment dirtyCount");
            assertEquals(LcaSectionBacking.STAGE_LINEAR_16, backing.overlayStage());
            assertEquals(i + 100, backing.getStateId(i & 15, (i >>> 4) & 15, (i * 3) & 15));
        }
        assertEquals(0, backing.tickingBlockCount());

        // Insert 17th..48th unique voxels -> STAGE_HASH_64 (capped at 48 / 64 = 75% load factor)
        for (int i = 16; i < 48; i++) {
            int x = i & 15;
            int y = (i >>> 2) & 15;
            int z = (i >>> 1) & 15;
            backing.setStateId(x, y, z, i + 200, 100L + i);
            assertEquals(LcaSectionBacking.STAGE_HASH_64, backing.overlayStage());
            assertEquals(i + 1, backing.dirtyCount());
        }
        assertEquals(48, backing.dirtyCount());
        assertFalse(backing.isReadyForQuietWindowCompaction(150L, 20L));
        assertTrue(backing.isReadyForQuietWindowCompaction(167L, 20L));

        int maxProbesAt48 = backing.maxProbeLengthInOverlay();
        assertTrue(maxProbesAt48 >= 1 && maxProbesAt48 <= 24, "Bounded probe length at D=48 (75% load): " + maxProbesAt48);

        // In-place overwrite at D=48 in STAGE_HASH_64 must NOT promote to STAGE_BULK_4096
        backing.setStateId(0, 0, 0, 777, 200L);
        assertEquals(48, backing.dirtyCount(), "In-place overwrite at D=48 must keep dirtyCount=48");
        assertEquals(LcaSectionBacking.STAGE_HASH_64, backing.overlayStage());
        assertEquals(777, backing.getStateId(0, 0, 0));

        // 49th unique voxel resizes sparse open-addressed hash table to 256 slots (NEVER int[4096]) and triggers compaction readiness
        backing.setStateId(15, 15, 15, 20, 201L);
        assertEquals(49, backing.dirtyCount());
        assertEquals(LcaSectionBacking.STAGE_SPARSE_HASH_EXT, backing.overlayStage());
        assertEquals(256, backing.activeOverlayCapacity(), "49th edit must resize sparse hash table to 256 slots, never int[4096]");
        assertTrue(backing.maxProbeLengthInOverlay() <= 16);
        assertTrue(backing.isReadyForQuietWindowCompaction(201L, 1000L));
        assertEquals(1, backing.tickingFluidCount());

        // In-place overwrite in sparse extended stage must not increment dirtyCount
        backing.setStateId(15, 15, 15, 0, 202L);
        assertEquals(49, backing.dirtyCount());
        assertEquals(0, backing.getStateId(15, 15, 15));
        assertEquals(0, backing.tickingFluidCount());
        assertEquals(48, backing.nonEmptyBlockCount());

        // 50 and 500 unique edits scale sparsely (256 -> 1024 slots) without allocating int[4096]
        int added = 49;
        for (int p = 0; p < 4096 && added < 500; p++) {
            int x = p & 15;
            int z = (p >>> 4) & 15;
            int y = (p >>> 8) & 15;
            if (backing.activeOverlay().getStateId(p) < 0) {
                backing.recordDirtyVoxel(p, 0, (p % 250) + 1, 300L + added);
                added++;
                if (added == 50) {
                    assertEquals(256, backing.activeOverlayCapacity(), "50 dirty cells must occupy 256 sparse slots");
                }
            }
        }
        assertEquals(500, backing.dirtyCount());
        assertEquals(1024, backing.activeOverlayCapacity(), "500 dirty cells must occupy 1024 sparse slots (< 4096)");
        assertEquals(500, backing.exportMergedDirtyOverlayEntries().length);
    }

    @Test
    @DisplayName("3-layer read visibility (activeOverlay -> frozenOverlay -> baseFrame) and ABA revert tombstone proof")
    void threeLayerReadVisibilityAndAbaTombstoneProof() {
        byte[] initialBase = new byte[4096];
        initialBase[LcaSectionBacking.pos12(2, 2, 2)] = 5; // oak planks in baseFrame
        LcaSectionBacking backing = new LcaSectionBacking(initialBase, 0);

        // 1. Mutate (0,0,0) from air(0) -> stone(1) in initial activeOverlay
        backing.setStateId(0, 0, 0, 1, 10L);
        // Also mutate (4,4,4) -> cobblestone(4)
        backing.setStateId(4, 4, 4, 4, 11L);
        assertEquals(1, backing.getStateId(0, 0, 0));
        assertEquals(4, backing.getStateId(4, 4, 4));
        assertEquals(5, backing.getStateId(2, 2, 2));

        // 2. Begin compaction snapshot -> moves current overlay to frozenOverlay (overlayA), installs fresh activeOverlay (overlayB)
        CompactionSnapshot snapshot = backing.beginCompactionSnapshot();
        assertNotNull(snapshot.frozenOverlay());
        assertEquals(2, snapshot.frozenOverlay().dirtyCount());
        assertEquals(0, backing.dirtyCount());
        assertEquals(LcaSectionBacking.STAGE_EMPTY, backing.overlayStage());

        // Reads still see frozenOverlay (overlayA) values and baseFrame values
        assertEquals(1, backing.getStateId(0, 0, 0), "Should hit frozenOverlay (overlayA)");
        assertEquals(4, backing.getStateId(4, 4, 4), "Should hit frozenOverlay (overlayA)");
        assertEquals(5, backing.getStateId(2, 2, 2), "Should hit baseFrame via octant fast path");

        // 3. ABA sequence during active compaction: revert (0,0,0) from stone(1) back to air(0) (which matches old baseFrame=0!)
        backing.setStateId(0, 0, 0, 0, 12L);
        // Also write (8,8,8) -> glass(9) in activeOverlay (overlayB)
        backing.setStateId(8, 8, 8, 9, 13L);

        // DURING compaction: (0,0,0) MUST read 0 from overlayB tombstone (not 1 from overlayA!)
        assertEquals(0, backing.getStateId(0, 0, 0), "overlayB air(0) tombstone must shadow overlayA stone(1) during compaction");
        assertEquals(4, backing.getStateId(4, 4, 4), "Unmodified (4,4,4) must still read from overlayA");
        assertEquals(9, backing.getStateId(8, 8, 8), "New write (8,8,8) must read from overlayB");
        assertEquals(5, backing.getStateId(2, 2, 2), "Unmodified octant (2,2,2) must read from baseFrame");

        // 4. Background compaction worker builds newBaseFrame by applying frozenOverlay (overlayA) onto snapshot.baseFrame()
        byte[] compactedBase = java.util.Arrays.copyOf(snapshot.baseFrame(), snapshot.baseFrame().length);
        snapshot.frozenOverlay().forEachEntry((pos12, stateId19) -> compactedBase[pos12] = (byte) stateId19);
        assertEquals(1, compactedBase[LcaSectionBacking.pos12(0, 0, 0)], "compactedBase has stone(1) baked in from overlayA");
        assertEquals(4, compactedBase[LcaSectionBacking.pos12(4, 4, 4)]);

        // Complete compaction atomically swaps in compactedBase and drops frozenOverlay (overlayA), keeping overlayB
        assertTrue(backing.completeCompaction(snapshot.epoch(), compactedBase, 0));
        assertNull(backing.frozenOverlay());
        assertEquals(0L, backing.frozenOctantMask());

        // AFTER completeCompaction: (0,0,0) MUST STILL read 0 (air) from overlayB, never resurrecting stone(1) from compactedBase!
        assertEquals(0, backing.getStateId(0, 0, 0), "overlayB air(0) tombstone must prevent ABA stone(1) resurrection after completeCompaction");
        assertEquals(4, backing.getStateId(4, 4, 4), "(4,4,4) now served directly from compactedBase");
        assertEquals(9, backing.getStateId(8, 8, 8), "(8,8,8) still served from overlayB");
        assertEquals(5, backing.getStateId(2, 2, 2), "(2,2,2) still served from compactedBase");
    }

    @Test
    @DisplayName("Coordinate masking, non-pinning ephemeral Copy-on-Read snapshot, and Moonrise ticking coordinate callbacks")
    void coordinateMaskingNonPinningEphemeralCopyAndMoonriseCallbacks() {
        byte[] baseFrame = new byte[]{1, 2, 3}; // compact 3-byte frame slice
        BitSet tickingBlockCoords = new BitSet(4096);
        BitSet tickingFluidCoords = new BitSet(4096);

        LcaSectionBacking backing = new LcaSectionBacking(
                baseFrame,
                1,
                0,
                0,
                0,
                state -> state != 0,
                state -> state == 77,
                state -> state == 88,
                pos12 -> 1);

        backing.setTickingCoordinateCallback(new LcaSectionBacking.TickingCoordinateCallback() {
            @Override
            public void onTickingBlockFlip(int pos12, int oldStateId, int newStateId, boolean nowTickingBlock) {
                tickingBlockCoords.set(pos12, nowTickingBlock);
            }

            @Override
            public void onTickingFluidFlip(int pos12, int oldStateId, int newStateId, boolean nowTickingFluid) {
                tickingFluidCoords.set(pos12, nowTickingFluid);
            }
        });

        // Write with unmasked world coordinates (e.g. x=163, y=-61, z=98) -> must mask & 15 cleanly
        int prev = backing.setAndGetPreviousStateId(163, -61, 98, 77, 10L, null);
        assertEquals(1, prev);
        assertEquals(77, backing.getStateId(163 & 15, (-61) & 15, 98 & 15));
        assertEquals(77, backing.getStateId(163, -61, 98));
        int expectedPos = LcaSectionBacking.pos12(163, -61, 98);
        assertTrue(tickingBlockCoords.get(expectedPos));
        assertEquals(1, backing.tickingBlockCount());

        // Flip to ticking fluid (88) -> removes from tickingBlockCoords, adds to tickingFluidCoords
        backing.setStateId(163, -61, 98, 88, 11L);
        assertFalse(tickingBlockCoords.get(expectedPos));
        assertTrue(tickingFluidCoords.get(expectedPos));
        assertEquals(0, backing.tickingBlockCount());
        assertEquals(1, backing.tickingFluidCount());

        // Materialize ephemeral Copy-on-Read snapshot (as used by Sodium WorldSlice / ChunkRenderRebuildTask)
        int[] snapshot = backing.materializeEphemeralStateIds(null, null);
        assertEquals(4096, snapshot.length);
        assertEquals(88, snapshot[expectedPos]);
        assertEquals(1, backing.ephemeralCopyCount());
        // Verify baseFrame reference and compact length are untouched (zero permanent inflation)
        assertSame(baseFrame, backing.baseFrame());
        assertEquals(3, backing.baseFrame().length);
        assertEquals(1, backing.dirtyCount());
    }

    @Test
    @DisplayName("Concurrent multi-threaded compaction vs. markUnloaded (bit 63) and EpochCommitFence stress test")
    void concurrentCompactionUnloadAndEpochCommitFenceStress() throws Exception {
        int iterations = 250;
        int workerCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        AtomicInteger staleSectionCommits = new AtomicInteger();
        AtomicInteger staleFenceCommits = new AtomicInteger();

        try {
            for (int iter = 0; iter < iterations; iter++) {
                final int chunkIdx = iter & 1023;
                byte[] initialBase = new byte[4096];
                LcaSectionBacking backing = new LcaSectionBacking(initialBase, 0);
                EpochCommitFence fence = new EpochCommitFence();
                AtomicReference<byte[]> regionSlabSlot = new AtomicReference<>(initialBase);

                backing.setStateId(1, 2, 3, 11, 1L);
                CompactionSnapshot snap1 = backing.beginCompactionSnapshot();

                // Supersede snap1 on every odd iteration before workers race
                CompactionSnapshot snap2;
                if ((iter & 1) == 1) {
                    backing.setStateId(1, 2, 3, 22, 2L);
                    snap2 = backing.beginCompactionSnapshot();
                } else {
                    snap2 = null;
                }

                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch doneGate = new CountDownLatch(workerCount);

                for (int w = 0; w < workerCount; w++) {
                    final int workerId = w;
                    pool.submit(() -> {
                        try {
                            startGate.await();
                            CompactionSnapshot targetSnap = (workerId % 2 == 1 && snap2 != null) ? snap2 : snap1;
                            byte[] candidateBase = new byte[4096];
                            targetSnap.frozenOverlay().forEachEntry((p, s) -> candidateBase[p] = (byte) s);

                            if (backing.completeCompaction(targetSnap.epoch(), candidateBase, 0)) {
                                if (snap2 != null && targetSnap == snap1) {
                                    staleSectionCommits.incrementAndGet();
                                }
                            }

                            if (fence.tryCommitChunkEpoch(chunkIdx, targetSnap.epoch())) {
                                while (true) {
                                    byte[] prevSlab = regionSlabSlot.get();
                                    int prevEpoch = prevSlab[4095] & 0xFF;
                                    if (prevEpoch >= (int) targetSnap.epoch()) {
                                        break;
                                    }
                                    candidateBase[4095] = (byte) targetSnap.epoch();
                                    if (regionSlabSlot.compareAndSet(prevSlab, candidateBase)) {
                                        break;
                                    }
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                startGate.countDown();
                // Main thread concurrently marks section unloaded and commits UNLOADED tombstone to fence
                long unloadedEpoch = backing.markUnloaded();
                assertTrue((unloadedEpoch & LcaSectionBacking.UNLOADED_BIT) != 0L);
                assertTrue(backing.isUnloaded());

                byte[] authoritativeUnloadSlab = new byte[4096];
                authoritativeUnloadSlab[0] = (byte) 99;
                authoritativeUnloadSlab[4095] = (byte) 127;
                long tombstone = fence.commitUnloadTombstone(chunkIdx, unloadedEpoch);
                regionSlabSlot.set(authoritativeUnloadSlab);
                assertTrue((tombstone & LcaSectionBacking.UNLOADED_BIT) != 0L);

                assertTrue(doneGate.await(5, TimeUnit.SECONDS));

                // After unload tombstone is committed, any further background completeCompaction or tryCommitChunkEpoch MUST fail
                assertFalse(backing.completeCompaction(snap1.epoch(), new byte[4096], 0));
                if (snap2 != null) {
                    assertFalse(backing.completeCompaction(snap2.epoch(), new byte[4096], 0));
                }
                assertFalse(fence.tryCommitChunkEpoch(chunkIdx, snap1.epoch()));
                assertFalse(fence.tryCommitChunkEpoch(chunkIdx, snap1.epoch() + 100L));
                assertFalse(fence.tryCommitChunkEpoch(chunkIdx, unloadedEpoch));
                assertTrue(fence.isUnloadedTombstoneCommitted(chunkIdx));
                assertEquals((byte) 99, regionSlabSlot.get()[0], "Background worker must never overwrite slab after unload tombstone");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, staleSectionCommits.get(), "Zero stale section compaction commits allowed");
        assertEquals(0, staleFenceCommits.get(), "Zero stale EpochCommitFence commits allowed");
    }

    @Test
    @DisplayName("CompactBaseIndex POPCNT rank directory resolves O(1) reads, testMaybeHas, forEachStateCount, and minority scatter without decoder")
    void compactBaseIndexPopcntRankLookupAndPaletteQueries() {
        int[] voxels = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        java.util.Arrays.fill(voxels, 1); // majority state = 1
        voxels[LcaSectionBacking.pos12(0, 0, 0)] = 10;
        voxels[LcaSectionBacking.pos12(15, 15, 15)] = 20;
        voxels[LcaSectionBacking.pos12(3, 7, 11)] = 30;
        voxels[LcaSectionBacking.pos12(4, 7, 11)] = 30;

        LcaSectionBacking.EncodedCompactFrame encoded =
                LcaSectionBacking.encodeCompactSectionFrame(voxels, 15, 0);
        assertEquals(1, encoded.majorityStateId());
        assertTrue(encoded.frameBytes().length > 0 && encoded.frameBytes().length < 64);

        // Construct with null decoder so CompactBaseIndex serves all lookups natively in O(1)
        LcaSectionBacking backing = new LcaSectionBacking(encoded.frameBytes(), encoded.majorityStateId(), null);
        for (int pos = 0; pos < LcaSectionBacking.BULK_4096_CAPACITY; pos++) {
            int x = pos & 15;
            int z = (pos >>> 4) & 15;
            int y = (pos >>> 8) & 15;
            assertEquals(voxels[pos], backing.getStateId(x, y, z), "Mismatch at pos12=" + pos);
        }

        assertTrue(backing.testMaybeHas(id -> id == 1));
        assertTrue(backing.testMaybeHas(id -> id == 10));
        assertTrue(backing.testMaybeHas(id -> id == 20));
        assertTrue(backing.testMaybeHas(id -> id == 30));
        assertFalse(backing.testMaybeHas(id -> id == 99));

        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        backing.forEachStateCount(counts::put);
        assertEquals(4092, counts.get(1));
        assertEquals(1, counts.get(10));
        assertEquals(1, counts.get(20));
        assertEquals(2, counts.get(30));

        // Overwrite the only occurrence of state 10 at (0,0,0) with state 55
        backing.setStateId(0, 0, 0, 55, 10L);
        assertFalse(backing.testMaybeHas(id -> id == 10), "Overwritten singleton minority state must no longer match maybeHas");
        assertTrue(backing.testMaybeHas(id -> id == 55), "Newly written overlay state must match maybeHas");

        counts.clear();
        backing.forEachStateCount(counts::put);
        assertEquals(4092, counts.get(1));
        assertNull(counts.get(10));
        assertEquals(1, counts.get(55));
        assertEquals(1, counts.get(20));
        assertEquals(2, counts.get(30));

        int[] reconstructed = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        java.util.Arrays.fill(reconstructed, backing.uniformStateId());
        assertTrue(backing.forEachNonMajorityAndOverlayVoxel((pos12, stateId) -> reconstructed[pos12] = stateId));
        assertEquals(55, reconstructed[LcaSectionBacking.pos12(0, 0, 0)]);
        assertEquals(20, reconstructed[LcaSectionBacking.pos12(15, 15, 15)]);
        assertEquals(30, reconstructed[LcaSectionBacking.pos12(3, 7, 11)]);
        assertEquals(1, reconstructed[LcaSectionBacking.pos12(8, 8, 8)]);
    }

    @Test
    @DisplayName("Single-pass fromDirectVoxels defers baseFrame[] allocation until requested and directly serializes clean/mutated vanilla wire format without copy()")
    void directCompactIndexLazyBaseFrameAndDirectVanillaWireSerialization() {
        int[] voxels = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        java.util.Arrays.fill(voxels, 1);
        voxels[LcaSectionBacking.pos12(0, 0, 0)] = 10;
        voxels[LcaSectionBacking.pos12(15, 15, 15)] = 20;
        voxels[LcaSectionBacking.pos12(3, 7, 11)] = 30;
        voxels[LcaSectionBacking.pos12(4, 7, 11)] = 30;

        LcaSectionBacking direct = LcaSectionBacking.fromDirectVoxels(
                voxels,
                15,
                0,
                LcaSectionBacking.BULK_4096_CAPACITY,
                0,
                0,
                id -> id != 0,
                id -> false,
                id -> false
        );
        assertFalse(direct.isBaseUniform());
        assertFalse(direct.hasMaterializedBaseFrame(), "Direct single-pass index must not eagerly allocate byte[] baseFrame");
        assertEquals(1, direct.uniformStateId());
        assertEquals(10, direct.getStateId(0, 0, 0));
        assertEquals(20, direct.getStateId(15, 15, 15));
        assertEquals(30, direct.getStateId(3, 7, 11));
        assertEquals(1, direct.getStateId(8, 8, 8));
        assertFalse(direct.hasMaterializedBaseFrame(), "Voxel reads must use CompactBaseIndex without materializing baseFrame");

        // Direct clean vanilla wire sizing & serialization (4 distinct states -> 4 bits per entry, 16 voxels/long -> 256 longs)
        int expectedCleanSize = 1
                + LcaSectionBacking.varIntByteSize(4)
                + LcaSectionBacking.varIntByteSize(1)
                + LcaSectionBacking.varIntByteSize(10)
                + LcaSectionBacking.varIntByteSize(20)
                + LcaSectionBacking.varIntByteSize(30)
                + (256 * 8);
        assertEquals(expectedCleanSize, direct.computeVanillaSerializedSize(15));
        assertEquals(0, direct.ephemeralCopyCount(), "Direct wire sizing must not trigger ephemeral copy");

        java.io.ByteArrayOutputStream cleanOut = new java.io.ByteArrayOutputStream();
        direct.writeVanillaPalettedContainer(15, new LcaSectionBacking.VanillaWireSink() {
            @Override
            public void writeByte(int b) {
                cleanOut.write(b & 0xFF);
            }

            @Override
            public void writeVarInt(int v) {
                int val = v;
                while ((val & ~0x7F) != 0) {
                    cleanOut.write((val & 0x7F) | 0x80);
                    val >>>= 7;
                }
                cleanOut.write(val & 0x7F);
            }

            @Override
            public void writeLong(long word) {
                for (int shift = 56; shift >= 0; shift -= 8) {
                    cleanOut.write((int) (word >>> shift) & 0xFF);
                }
            }
        });
        assertEquals(expectedCleanSize, cleanOut.size());
        assertEquals(0, direct.ephemeralCopyCount(), "Direct clean wire write must not trigger ephemeral copy");
        assertFalse(direct.hasMaterializedBaseFrame(), "Direct wire write must not materialize baseFrame");

        // Mutate section (overwrite singleton 10 -> 55 and add new state 77 at (1,0,0)) and verify direct mutated wire sizing/write
        direct.setStateId(0, 0, 0, 55, 10L);
        direct.setStateId(1, 0, 0, 77, 11L);
        int expectedMutatedSize = 1
                + LcaSectionBacking.varIntByteSize(5)
                + LcaSectionBacking.varIntByteSize(1)
                + LcaSectionBacking.varIntByteSize(20)
                + LcaSectionBacking.varIntByteSize(30)
                + LcaSectionBacking.varIntByteSize(55)
                + LcaSectionBacking.varIntByteSize(77)
                + (256 * 8);
        assertEquals(expectedMutatedSize, direct.computeVanillaSerializedSize(15));
        java.io.ByteArrayOutputStream mutOut = new java.io.ByteArrayOutputStream();
        direct.writeVanillaPalettedContainer(15, new LcaSectionBacking.VanillaWireSink() {
            @Override
            public void writeByte(int b) {
                mutOut.write(b & 0xFF);
            }

            @Override
            public void writeVarInt(int v) {
                int val = v;
                while ((val & ~0x7F) != 0) {
                    mutOut.write((val & 0x7F) | 0x80);
                    val >>>= 7;
                }
                mutOut.write(val & 0x7F);
            }

            @Override
            public void writeLong(long word) {
                for (int shift = 56; shift >= 0; shift -= 8) {
                    mutOut.write((int) (word >>> shift) & 0xFF);
                }
            }
        });
        assertEquals(expectedMutatedSize, mutOut.size());
        assertEquals(0, direct.ephemeralCopyCount(), "Direct mutated wire write must not trigger ephemeral copy");

        // Lazy baseFrame materialization on demand must match encodeCompactSectionFrame byte-for-byte
        LcaSectionBacking.EncodedCompactFrame eager = LcaSectionBacking.encodeCompactSectionFrame(voxels, 15, 0);
        byte[] lazyBytes = direct.baseFrame();
        assertTrue(direct.hasMaterializedBaseFrame());
        org.junit.jupiter.api.Assertions.assertArrayEquals(eager.frameBytes(), lazyBytes);
    }

    @Test
    @DisplayName("fromRawBitStorageSnapshot unpacks packed long[] words off-thread, pre-builds cleanVanillaWireSlice + baseFrame, and compactFrozenOverlayOffThread folds frozen overlays without losing concurrent writes")
    void rawBitStorageSnapshotAndOffThreadFrozenOverlayCompaction() throws Exception {
        int[] palette = {1, 10, 20, 30, 40}; // 5 states -> 4 bits per entry (16 entries per 64-bit long -> 256 longs)
        long[] rawWords = new long[256];
        // Voxel 0 -> palette[1] = 10; Voxel 1 -> palette[2] = 20; Voxel 15 -> palette[3] = 30
        rawWords[0] = (1L) | (2L << 4) | (3L << 60);
        // Voxel 4095 (word 255, entry 15) -> palette[4] = 40
        rawWords[255] = (4L << 60);

        LcaSectionBacking backing = LcaSectionBacking.fromRawBitStorageSnapshot(
                rawWords,
                4,
                palette,
                15,
                15,
                0,
                id -> id != 0,
                id -> id == 20,
                id -> id == 30
        );
        assertFalse(backing.isBaseUniform());
        assertTrue(backing.hasPrebuiltCleanWireSlice(), "Background factory must pre-build cleanVanillaWireSlice");
        assertTrue(backing.hasMaterializedBaseFrame(), "Background factory must pre-build compact .lca baseFrame");
        assertEquals(LcaSectionBacking.BULK_4096_CAPACITY, backing.nonEmptyBlockCount());
        assertEquals(1, backing.tickingBlockCount());
        assertEquals(1, backing.tickingFluidCount());
        assertEquals(10, backing.getStateId(0, 0, 0));
        assertEquals(20, backing.getStateId(1, 0, 0));
        assertEquals(30, backing.getStateId(15, 0, 0));
        assertEquals(40, backing.getStateId(15, 15, 15));
        assertEquals(1, backing.getStateId(8, 8, 8));

        byte[] cleanSliceBefore = backing.cleanVanillaWireSlice(15);
        assertNotNull(cleanSliceBefore);
        assertEquals(backing.computeVanillaSerializedSize(15), cleanSliceBefore.length);

        // Mutate section on main thread -> cleanVanillaWireSlice must return null while dirtyCount > 0
        backing.setStateId(0, 0, 0, 77, 100L);
        backing.setStateId(2, 0, 0, 88, 101L);
        assertEquals(2, backing.dirtyCount());
        assertNull(backing.cleanVanillaWireSlice(15), "Dirty section must not serve stale clean wire slice");

        // Take lock-free CompactionSnapshot on main thread, then write a concurrent mutation to activeOverlay
        CompactionSnapshot snap = backing.beginCompactionSnapshot();
        assertEquals(0, backing.dirtyCount(), "activeOverlay must be reset after beginCompactionSnapshot");
        backing.setStateId(5, 5, 5, 99, 102L);
        assertEquals(1, backing.dirtyCount(), "Concurrent main-thread write must land in fresh activeOverlay");

        // Fold frozenOverlay off-thread on a background worker
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            boolean compacted = worker.submit(() -> backing.compactFrozenOverlayOffThread(snap, 15)).get(5, TimeUnit.SECONDS);
            assertTrue(compacted, "Off-thread frozenOverlay compaction must succeed");
        } finally {
            worker.shutdownNow();
        }

        // Verify both compacted frozenOverlay mutations AND concurrent activeOverlay mutation are preserved
        assertEquals(77, backing.getStateId(0, 0, 0));
        assertEquals(88, backing.getStateId(2, 0, 0));
        assertEquals(99, backing.getStateId(5, 5, 5));
        assertNull(backing.frozenOverlay(), "frozenOverlay must be cleared after completeCompaction");

        // Compact the remaining activeOverlay mutation off-thread so the section becomes 100% clean
        assertTrue(backing.compactFrozenOverlayOffThread(15));
        assertEquals(0, backing.dirtyCount());
        assertNull(backing.frozenOverlay());
        assertEquals(99, backing.getStateId(5, 5, 5));
        byte[] cleanSliceAfter = backing.cleanVanillaWireSlice(15);
        assertNotNull(cleanSliceAfter, "Clean wire slice must be immediately available after off-thread compaction");
        assertEquals(backing.computeVanillaSerializedSize(15), cleanSliceAfter.length);

        LcaSectionBacking.EncodedCompactFrame f1 = new LcaSectionBacking.EncodedCompactFrame(1, new byte[]{10, 20});
        LcaSectionBacking.EncodedCompactFrame f2 = new LcaSectionBacking.EncodedCompactFrame(1, new byte[]{10, 20});
        LcaSectionBacking.EncodedCompactFrame f3 = new LcaSectionBacking.EncodedCompactFrame(2, new byte[]{10, 21});
        assertEquals(f1, f2);
        assertEquals(f1.hashCode(), f2.hashCode());
        assertNotEquals(f1, f3);
        assertFalse(f1.equals("other"));
        assertTrue(f1.toString().contains("EncodedCompactFrame["));

        CompactionSnapshot cs1 = new CompactionSnapshot(new byte[]{1, 2}, 0, snap.frozenOverlay(), snap.epoch());
        CompactionSnapshot cs2 = new CompactionSnapshot(new byte[]{1, 2}, 0, snap.frozenOverlay(), snap.epoch());
        CompactionSnapshot cs3 = new CompactionSnapshot(new byte[]{1, 3}, 0, snap.frozenOverlay(), snap.epoch());
        assertEquals(cs1, cs2);
        assertEquals(cs1.hashCode(), cs2.hashCode());
        assertNotEquals(cs1, cs3);
        assertFalse(cs1.equals("other"));
        assertTrue(cs1.toString().contains("CompactionSnapshot["));
    }

    @Test
    @DisplayName("Cold baseFrame-only demotion preserves lossless reads/wire slices, rejects dirty demotion, keeps CompactBaseIndex null on edit, and updates AIMD editScore")
    void coldBaseFrameDemotionZeroWriteHydrationAndAimdScoring() throws Exception {
        int[] voxels = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        java.util.Arrays.fill(voxels, 1);
        voxels[LcaSectionBacking.pos12(2, 3, 4)] = 17;
        voxels[LcaSectionBacking.pos12(14, 9, 1)] = 42;
        voxels[LcaSectionBacking.pos12(7, 15, 12)] = 105;

        LcaSectionBacking backing = LcaSectionBacking.fromDirectVoxels(
                voxels,
                13,
                6,
                LcaSectionBacking.BULK_4096_CAPACITY,
                1,
                0,
                id -> id != 0,
                id -> id == 42,
                id -> false
        );
        assertTrue(backing.hasHydratedCompactBaseIndex());
        assertFalse(backing.isColdBaseFrameOnly());
        int hydratedFootprint = backing.estimatedBaseFootprintBytes();

        // Demote clean section to cold .lca baseFrame-only mode
        assertTrue(backing.evictCompactBaseIndexToColdBaseFrame(), "Clean non-uniform slab must demote to cold baseFrame");
        assertFalse(backing.hasHydratedCompactBaseIndex());
        assertTrue(backing.isColdBaseFrameOnly());
        assertTrue(backing.hasMaterializedBaseFrame());
        int coldFootprint = backing.estimatedBaseFootprintBytes();
        assertTrue(coldFootprint > 0 && coldFootprint < hydratedFootprint,
                "Cold baseFrame footprint (" + coldFootprint + ") must be smaller than hydrated index (" + hydratedFootprint + ")");

        // Cold reads, ephemeral meshing, and clean vanilla wire slices must remain lossless without re-pinning CompactBaseIndex
        assertEquals(17, backing.getStateId(2, 3, 4));
        assertEquals(42, backing.getStateId(14, 9, 1));
        assertEquals(105, backing.getStateId(7, 15, 12));
        assertEquals(1, backing.getStateId(0, 0, 0));
        assertFalse(backing.hasHydratedCompactBaseIndex(), "Read-only getStateId must keep cold slab unhydrated");

        byte[] coldWireSlice = backing.cleanVanillaWireSlice(15);
        assertNotNull(coldWireSlice);
        assertEquals(backing.computeVanillaSerializedSize(15), coldWireSlice.length);
        assertFalse(backing.hasHydratedCompactBaseIndex(), "Transient vanilla wire slice must keep cold slab unhydrated");

        int[] materialized = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        backing.materializeEphemeralStateIds(materialized, null);
        org.junit.jupiter.api.Assertions.assertArrayEquals(voxels, materialized);

        // Mutating a voxel must invoke listener outside lock, resolve exact previousStateId WITHOUT hydrating CompactBaseIndex, and increment AIMD score
        AtomicInteger callbackCount = new AtomicInteger();
        backing.setSectionMutationListener((sec, tick) -> callbackCount.incrementAndGet());
        assertEquals(0, backing.editScore());

        int prevTicking = backing.setAndGetPreviousStateId(14, 9, 1, 88, 200L, null);
        assertEquals(42, prevTicking, "Cold edit must resolve exact previous minority state without pinning CompactBaseIndex");
        assertEquals(0, backing.tickingBlockCount(), "Overwriting ticking block 42 -> 88 must decrement tickingBlockCount");
        assertFalse(backing.hasHydratedCompactBaseIndex(), "Voxel edit must NOT hydrate CompactBaseIndex");
        assertEquals(1, callbackCount.get());
        assertEquals(LcaSectionBacking.DEFAULT_AIMD_ADDITIVE_INCREMENT, backing.editScore());

        // 100 repeated overwrites of the same cell update its single sparse slot in place (dirtyCount stays 1)
        for (int r = 0; r < 100; r++) {
            backing.recordDirtyVoxel(LcaSectionBacking.pos12(14, 9, 1), 88 + (r & 1), 88 + ((r + 1) & 1), 200L);
        }
        assertEquals(1, backing.dirtyCount(), "100 overwrites of the same coordinate must update 1 slot in place");
        backing.recordDirtyVoxel(LcaSectionBacking.pos12(14, 9, 1), 88, 88, 200L);
        assertFalse(backing.hasHydratedCompactBaseIndex(), "recordDirtyVoxel must leave CompactBaseIndex == null");

        // Fail-closed S-004 invariant: dirty slab must refuse demotion to cold baseFrame
        assertFalse(backing.evictCompactBaseIndexToColdBaseFrame(), "Dirty slab must never demote before overlay compaction");

        // Second edit increases AIMD score additively; multiplicative decay halves score
        backing.setStateId(0, 0, 0, 99, 201L);
        assertFalse(backing.hasHydratedCompactBaseIndex(), "Second edit must also leave CompactBaseIndex == null");
        assertEquals(103 * LcaSectionBacking.DEFAULT_AIMD_ADDITIVE_INCREMENT, backing.editScore());
        assertEquals((103 * LcaSectionBacking.DEFAULT_AIMD_ADDITIVE_INCREMENT) >>> 1, backing.applyMultiplicativeScoreDecay(1, 1));

        // Freeze snapshot on main thread and compact overlay on a dedicated background worker thread; cold section stays cold!
        CompactionSnapshot snap = backing.beginCompactionSnapshot();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            boolean compacted = worker.submit(() -> backing.compactFrozenOverlayOffThread(snap, 15))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(compacted, "Background worker must compact frozen overlay into baseFrame");
        } finally {
            worker.shutdownNow();
        }
        assertEquals(0, backing.dirtyCount());
        assertNull(backing.frozenOverlay());
        assertFalse(backing.hasHydratedCompactBaseIndex(), "Compacting a cold section must keep CompactBaseIndex == null");
        assertTrue(backing.isColdBaseFrameOnly());
        assertEquals(88, backing.getStateId(14, 9, 1));
        assertEquals(99, backing.getStateId(0, 0, 0));
        assertEquals(105, backing.getStateId(7, 15, 12));
    }

    @Test
    @DisplayName("Cold baseFrame-only slabs execute testMaybeHas, forEachStateCount, forEachNonMajorityAndOverlayVoxel, and writeVanillaPalettedContainer without 4096-voxel materialization")
    void coldBaseFrameBulkQueriesAndVanillaWireFastPathsAvoid4096Materialization() {
        int[] voxels = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        java.util.Arrays.fill(voxels, 1);
        voxels[LcaSectionBacking.pos12(0, 0, 0)] = 17;
        voxels[LcaSectionBacking.pos12(14, 9, 1)] = 42;
        voxels[LcaSectionBacking.pos12(7, 15, 12)] = 105;
        voxels[LcaSectionBacking.pos12(8, 15, 12)] = 105;

        LcaSectionBacking backing = LcaSectionBacking.fromDirectVoxels(
                voxels,
                15,
                0,
                LcaSectionBacking.BULK_4096_CAPACITY,
                1,
                0,
                id -> id != 0,
                id -> id == 42,
                id -> false
        );
        byte[] expectedCleanWire = backing.cleanVanillaWireSlice(15);
        assertNotNull(expectedCleanWire);

        assertTrue(backing.evictCompactBaseIndexToColdBaseFrame());
        assertTrue(backing.isColdBaseFrameOnly());
        assertFalse(backing.hasHydratedCompactBaseIndex());
        assertEquals(0, backing.ephemeralCopyCount());

        AtomicInteger predCalls = new AtomicInteger();
        assertFalse(backing.testMaybeHas(id -> {
            predCalls.incrementAndGet();
            return id == 999;
        }));
        assertEquals(4, predCalls.get(), "Cold testMaybeHas must scan only the 4 palette entries, not 4096 voxels");
        assertTrue(backing.testMaybeHas(id -> id == 17));
        assertTrue(backing.testMaybeHas(id -> id == 1));
        assertEquals(0, backing.ephemeralCopyCount(), "Cold testMaybeHas must not materialize 4096 voxels");
        assertFalse(backing.hasHydratedCompactBaseIndex());

        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        backing.forEachStateCount(counts::put);
        assertEquals(4, counts.size());
        assertEquals(4092, counts.get(1));
        assertEquals(1, counts.get(17));
        assertEquals(1, counts.get(42));
        assertEquals(2, counts.get(105));
        assertEquals(0, backing.ephemeralCopyCount(), "Cold forEachStateCount must not materialize 4096 voxels");
        assertFalse(backing.hasHydratedCompactBaseIndex());

        java.io.ByteArrayOutputStream coldOut = new java.io.ByteArrayOutputStream();
        backing.writeVanillaPalettedContainer(15, new LcaSectionBacking.VanillaWireSink() {
            @Override
            public void writeByte(int b) {
                coldOut.write(b & 0xFF);
            }

            @Override
            public void writeVarInt(int v) {
                int val = v;
                while ((val & ~0x7F) != 0) {
                    coldOut.write((val & 0x7F) | 0x80);
                    val >>>= 7;
                }
                coldOut.write(val & 0x7F);
            }

            @Override
            public void writeLong(long word) {
                for (int shift = 56; shift >= 0; shift -= 8) {
                    coldOut.write((int) (word >>> shift) & 0xFF);
                }
            }
        });
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                expectedCleanWire,
                coldOut.toByteArray(),
                "Cold writeVanillaPalettedContainer must emit canonical CompactBaseIndex wire bytes"
        );
        assertEquals(0, backing.ephemeralCopyCount(), "Cold writeVanillaPalettedContainer must not trigger ephemeral copy");
        assertFalse(backing.hasHydratedCompactBaseIndex());

        int[] reconstructed = new int[LcaSectionBacking.BULK_4096_CAPACITY];
        java.util.Arrays.fill(reconstructed, backing.uniformStateId());
        assertTrue(
                backing.forEachNonMajorityAndOverlayVoxel((pos12, stateId) -> reconstructed[pos12] = stateId),
                "Cold forEachNonMajorityAndOverlayVoxel must stream minority voxels transiently from baseFrame"
        );
        org.junit.jupiter.api.Assertions.assertArrayEquals(voxels, reconstructed);
        assertFalse(backing.hasHydratedCompactBaseIndex());

        // Sparse-dirty cold slab (CompactBaseIndex remains null across edits) must also avoid 4096-voxel materialization
        backing.setStateId(0, 0, 0, 55, 10L);
        assertFalse(backing.hasHydratedCompactBaseIndex());
        int copiesBeforeDirtyQueries = backing.ephemeralCopyCount();

        assertFalse(backing.testMaybeHas(id -> id == 17));
        assertTrue(backing.testMaybeHas(id -> id == 55));

        counts.clear();
        backing.forEachStateCount(counts::put);
        assertNull(counts.get(17));
        assertEquals(4092, counts.get(1));
        assertEquals(1, counts.get(55));
        assertEquals(1, counts.get(42));
        assertEquals(2, counts.get(105));

        int expectedDirtySize = 1
                + LcaSectionBacking.varIntByteSize(4)
                + LcaSectionBacking.varIntByteSize(1)
                + LcaSectionBacking.varIntByteSize(42)
                + LcaSectionBacking.varIntByteSize(105)
                + LcaSectionBacking.varIntByteSize(55)
                + (256 * 8);
        assertEquals(expectedDirtySize, backing.computeVanillaSerializedSize(15));
        assertEquals(copiesBeforeDirtyQueries, backing.ephemeralCopyCount(), "Sparse-dirty cold queries must not materialize 4096 voxels");
        assertFalse(backing.hasHydratedCompactBaseIndex());

        // Exercise frozenOverlay + activeOverlay cold fast paths without 4096-voxel materialization
        backing.beginCompactionSnapshot();
        backing.recordDirtyVoxel(LcaSectionBacking.pos12(1, 0, 0), 77, 11L);
        assertTrue(backing.testMaybeHas(id -> id == 77));
        assertTrue(backing.testMaybeHas(id -> id == 55));
        assertFalse(backing.testMaybeHas(id -> id == 17));
        counts.clear();
        backing.forEachStateCount(counts::put);
        assertEquals(4091, counts.get(1));
        assertEquals(1, counts.get(55));
        assertEquals(1, counts.get(77));
        assertTrue(backing.computeVanillaSerializedSize(15) > 0);
        assertTrue(backing.forEachNonMajorityAndOverlayVoxel((pos12, stateId) -> reconstructed[pos12] = stateId));
        assertEquals(77, reconstructed[LcaSectionBacking.pos12(1, 0, 0)]);

        // Exercise > HASH_64_MAX_LOAD and > 256 distinct states fallback + SparseDirtyCellMap growth + bounds checks
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> backing.recordDirtyVoxel(0, 1, -1, 12L)
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> backing.recordDirtyVoxel(0, 1, LcaSectionBacking.MAX_STATE_ID_19 + 1, 12L)
        );
        for (int i = 2; i < 300; i++) {
            backing.recordDirtyVoxel(i, 1, 200 + i, 12L);
        }
        assertTrue(backing.testMaybeHas(id -> id == 450));
        counts.clear();
        backing.forEachStateCount(counts::put);
        assertEquals(1, counts.get(450));
        assertTrue(backing.computeVanillaSerializedSize(15) > 0);
        coldOut.reset();
        backing.writeVanillaPalettedContainer(15, new LcaSectionBacking.VanillaWireSink() {
            @Override
            public void writeByte(int b) {
                coldOut.write(b & 0xFF);
            }

            @Override
            public void writeVarInt(int v) {
                coldOut.write(v & 0x7F);
            }

            @Override
            public void writeLong(long word) {
                coldOut.write((int) word & 0xFF);
            }
        });
        assertTrue(coldOut.size() > 0);

        // Exercise custom defaultBaseFrameDecoder and legacy byte[4096] fallback guards in resolveTransientOrHydratedIndex
        LcaSectionBacking customDecoded = new LcaSectionBacking(
                new byte[]{1, 2, 3},
                1,
                pos -> pos == 0 ? 17 : 1
        );
        assertFalse(customDecoded.forEachNonMajorityAndOverlayVoxel((p, s) -> {}));
        assertTrue(customDecoded.testMaybeHas(id -> id == 17));
        counts.clear();
        customDecoded.forEachStateCount(counts::put);
        assertEquals(1, counts.get(17));
        assertEquals(4095, counts.get(1));

        byte[] raw4096 = new byte[LcaSectionBacking.BULK_4096_CAPACITY];
        java.util.Arrays.fill(raw4096, (byte) 2);
        raw4096[0] = 9;
        LcaSectionBacking rawBacking = new LcaSectionBacking(raw4096, 2);
        assertFalse(rawBacking.forEachNonMajorityAndOverlayVoxel((p, s) -> {}));
        assertTrue(rawBacking.testMaybeHas(id -> id == 9));
        counts.clear();
        rawBacking.forEachStateCount(counts::put);
        assertEquals(1, counts.get(9));
        assertEquals(4095, counts.get(2));
    }

    @Test
    @DisplayName("Gate 1 Repro: .lca SectionFrame in LcaSectionBacking decodes voxels, counts non-empty blocks, and preserves Starlight boundary light state")
    void lcaSectionFrameColdBackingAndStarlightBoundaryStateRoundTrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path regionDir = tempDir.resolve("region");
        java.nio.file.Files.createDirectories(regionDir);
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(regionDir, true)) {
            java.util.LinkedHashMap<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("DataVersion", 4671);
            root.put("xPos", 0);
            root.put("yPos", -4);
            root.put("zPos", 0);
            root.put("Status", "minecraft:full");
            root.put("isLightOn", (byte) 1);
            root.put("starlight.light_version", 10);

            java.util.List<Object> secs = new java.util.ArrayList<>();
            java.util.LinkedHashMap<String, Object> lowBoundary = new java.util.LinkedHashMap<>();
            lowBoundary.put("Y", (byte) -5);
            lowBoundary.put("starlight.skylight_state", 1);
            lowBoundary.put("starlight.blocklight_state", 1);
            secs.add(lowBoundary);

            java.util.LinkedHashMap<String, Object> caveSec = new java.util.LinkedHashMap<>();
            caveSec.put("Y", (byte) -1);
            java.util.LinkedHashMap<String, Object> bs = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, Object> pStone = new java.util.LinkedHashMap<>();
            pStone.put("Name", "minecraft:stone");
            java.util.LinkedHashMap<String, Object> pCaveAir = new java.util.LinkedHashMap<>();
            pCaveAir.put("Name", "minecraft:cave_air");
            bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, java.util.List.of(pStone, pCaveAir)));
            long[] words = new long[256];
            words[0] = 1L;
            bs.put("data", words);
            caveSec.put("block_states", bs);
            caveSec.put("starlight.skylight_state", 1);
            caveSec.put("starlight.blocklight_state", 1);
            secs.add(caveSec);

            java.util.LinkedHashMap<String, Object> highBoundary = new java.util.LinkedHashMap<>();
            highBoundary.put("Y", (byte) 20);
            byte[] fullSky = new byte[2048];
            java.util.Arrays.fill(fullSky, (byte) 0xFF);
            highBoundary.put("SkyLight", fullSky);
            highBoundary.put("starlight.skylight_state", 2);
            secs.add(highBoundary);

            root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, secs));
            storage.writeChunkCompound(0, 0, root);
            storage.flush();

            RawChunkFrameSnapshot snap = storage.readRawChunkFrame(
                    0, 0, name -> "minecraft:stone".equals(name) ? 1 : 0);
            assertNotNull(snap);
            byte[] caveSlice = snap.sliceSectionFrameWithRuntimePalette(
                    3, name -> "minecraft:stone".equals(name) ? 1 : 0);
            assertTrue(caveSlice.length > 0);

            LcaSectionBacking coldBacking = new LcaSectionBacking(caveSlice, 0);
            assertEquals(4095, coldBacking.nonEmptyBlockCount(),
                    "Cave section with 4095 stone + 1 cave_air must count 4095 non-empty blocks (not 0!)");
            assertFalse(coldBacking.hasOnlyAir(),
                    "Cave section must NEVER report hasOnlyAir()==true on load!");
            assertEquals(0, coldBacking.getStateId(0, 0, 0));
            assertEquals(1, coldBacking.getStateId(1, 0, 0));

            // Exercise LcaSectionBacking.fromColdSectionFrame with explicit & computed counts + predicates
            LcaSectionBacking coldFromExplicit = LcaSectionBacking.fromColdSectionFrame(
                    caveSlice, 0, 4095, 12, 3, id -> id != 0, id -> id == 1, id -> false);
            assertEquals(4095, coldFromExplicit.nonEmptyBlockCount());
            assertEquals(12, coldFromExplicit.tickingBlockCount());
            assertEquals(3, coldFromExplicit.tickingFluidCount());
            assertEquals(1, coldFromExplicit.uniformStateId());

            LcaSectionBacking coldComputed = LcaSectionBacking.fromColdSectionFrame(
                    caveSlice, 0, -1, -1, -1, id -> id != 0, id -> id == 1, id -> id == 1);
            assertEquals(4095, coldComputed.nonEmptyBlockCount());
            assertEquals(4095, coldComputed.tickingBlockCount());
            assertEquals(4095, coldComputed.tickingFluidCount());

            LcaSectionBacking coldEmptyUniform = LcaSectionBacking.fromColdSectionFrame(
                    new byte[0], 5, -1, -1, -1, id -> id != 0, id -> id == 5, id -> id == 5);
            assertEquals(4096, coldEmptyUniform.nonEmptyBlockCount());
            assertEquals(4096, coldEmptyUniform.tickingBlockCount());
            assertEquals(4096, coldEmptyUniform.tickingFluidCount());

            // Exercise Starlight underground dark section CoW splice -> uniform air + light overrides
            assertTrue(snap.hasIsLightOn());
            int airDictId = storage.registerBlockStateInRegion(0, 0, "minecraft:air");
            int[] uniformAir4096 = new int[4096];
            java.util.Arrays.fill(uniformAir4096, airDictId);
            byte[] torchBlk = new byte[2048];
            torchBlk[0] = (byte) 0x0E;
            byte[] splicedFrame = storage.commitAndGetSplicedChunkFrameCoW(
                    0,
                    0,
                    snap.chunkFrame(),
                    (1 << 3) | (1 << 2),
                    secIdx -> secIdx == 3 ? uniformAir4096 : null, // mine out section 3 to uniform airDictId
                    secIdx -> null,
                    secIdx -> secIdx == 2 ? torchBlk : null,
                    null
            );
            assertNotNull(splicedFrame);
            java.util.LinkedHashMap<String, Object> splicedRoot = Nbt.readRootCompound(storage.readChunkNbtBytes(0, 0));
            Nbt.NbtList splicedSecs = (Nbt.NbtList) splicedRoot.get("sections");
            boolean foundMinedDarkSec = false;
            boolean foundTorchSec = false;
            for (Object item : splicedSecs.items) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> sMap = (java.util.Map<String, Object>) item;
                int sy = ((Number) sMap.get("Y")).intValue();
                if (sy == -1) {
                    foundMinedDarkSec = true;
                    assertEquals(2, ((Number) sMap.get("starlight.skylight_state")).intValue(),
                            "Mined-out Starlight underground section must keep explicit zero SkyLight (state=2) instead of reverting to NULL_EXTRUDE!");
                } else if (sy == -2) {
                    foundTorchSec = true;
                    byte[] blk = (byte[]) sMap.get("BlockLight");
                    assertNotNull(blk);
                    assertEquals((byte) 0x0E, blk[0]);
                }
            }
            assertTrue(foundMinedDarkSec && airDictId >= 0);
            assertTrue(foundTorchSec);

            byte[] lcaFileBytes = java.nio.file.Files.readAllBytes(regionDir.resolve("r.0.0.lca"));
            AnvilReader.ChunkEntry structRead = LcaRegionReader.INSTANCE.readChunk(lcaFileBytes, 0, 0);
            assertNotNull(structRead);
            Nbt.NbtList readSecs = (Nbt.NbtList) structRead.root.get("sections");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> readLow = (java.util.Map<String, Object>) readSecs.items.get(0);
            assertEquals((byte) -5, ((Number) readLow.get("Y")).byteValue());
            assertEquals(2, ((Number) readLow.get("starlight.skylight_state")).intValue(),
                    "Compact low boundary section with 2048B SkyLight must emit starlight.skylight_state=2");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> readHigh = (java.util.Map<String, Object>) readSecs.items.get(readSecs.items.size() - 1);
            assertEquals((byte) 20, ((Number) readHigh.get("Y")).byteValue());
            assertEquals(2, ((Number) readHigh.get("starlight.skylight_state")).intValue(),
                    "Compact high boundary section with 2048B SkyLight must emit starlight.skylight_state=2");

            // Cover uniform, flat-bitpack, multi-biome, property-suffix, and invalid-frame branches in LcaSectionCodec
            int[] outOrd = new int[4096];
            byte[] lightBox = new byte[1];
            assertEquals(0, LcaSectionCodec.decodeSectionFrameBlockOrdinals(null, 0, outOrd, lightBox).length);
            assertEquals(0, LcaSectionCodec.decodeSectionFrameBlockOrdinals(new byte[]{0x10, 0x00, 0x00}, 3, outOrd, lightBox).length);
            LcaSectionCodec.EncodedSection uniformEnc = LcaSectionCodec.encodeSection(
                    new int[]{7}, null, new int[]{1, 2}, new long[]{0x0123456789ABCDEFL},
                    LcaRegionWriter.ALL_ZERO_LIGHT_2048, fullSky, -1, -1);
            int[] uniformPal = LcaSectionCodec.decodeSectionFrameBlockOrdinals(
                    uniformEnc.frameBytes, uniformEnc.frameBytes.length, outOrd, lightBox);
            assertEquals(1, uniformPal.length);
            assertEquals(7, uniformPal[0]);

            // Flat-bitpack section (high-entropy 2-state checkerboard)
            long[] checkerWords = new long[256];
            java.util.Arrays.fill(checkerWords, 0x1010101010101010L);
            LcaSectionCodec.EncodedSection flatEnc = LcaSectionCodec.encodeSection(
                    new int[]{3, 9}, checkerWords, null, null, null, null, -1, 0);
            int[] flatPal = LcaSectionCodec.decodeSectionFrameBlockOrdinals(
                    flatEnc.frameBytes, flatEnc.frameBytes.length, outOrd, lightBox);
            assertEquals(2, flatPal.length);

            // Slab-local palette with custom block property -> global dict ID mapping
            java.util.LinkedHashMap<String, Object> propChunk = new java.util.LinkedHashMap<>();
            propChunk.put("DataVersion", 4671);
            propChunk.put("xPos", 1);
            propChunk.put("yPos", -4);
            propChunk.put("zPos", 0);
            propChunk.put("Status", "minecraft:full");
            java.util.LinkedHashMap<String, Object> propSec = new java.util.LinkedHashMap<>();
            propSec.put("Y", (byte) 0);
            java.util.LinkedHashMap<String, Object> propBs = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, Object> customEntry = new java.util.LinkedHashMap<>();
            customEntry.put("Name", "minecraft:oak_fence");
            java.util.LinkedHashMap<String, Object> customProps = new java.util.LinkedHashMap<>();
            customProps.put("east", "true");
            customProps.put("waterlogged", "true");
            customEntry.put("Properties", customProps);
            propBs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, java.util.List.of(customEntry, pStone)));
            propBs.put("data", words);
            propSec.put("block_states", propBs);
            propChunk.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, java.util.List.of(propSec)));
            storage.writeChunkCompound(1, 0, propChunk);
            RawChunkFrameSnapshot propSnap = storage.readRawChunkFrame(
                    1, 0, name -> name.startsWith("minecraft:oak_fence") ? 77 : 1);
            assertNotNull(propSnap);
            assertFalse(propSnap.hasIsLightOn());
            byte[] propSlice = propSnap.sliceSectionFrameWithRuntimePalette(
                    4, name -> name.startsWith("minecraft:oak_fence") ? 77 : 1);
            LcaSectionBacking propBacking = LcaSectionBacking.fromColdSectionFrame(
                    propSlice, 0, -1, -1, -1, null, null, null);
            assertEquals(4096, propBacking.nonEmptyBlockCount());

            // Exercise RawChunkFrameSnapshot inline uniform resolution, residual tail slicing, and record contracts
            assertEquals(0, propSnap.resolveInlineUniformRuntimeStateId(0, name -> 0));
            assertTrue(snap.sliceResidualTailBytes().length > 0);
            assertEquals(snap, new RawChunkFrameSnapshot(
                    snap.chunkFrame().clone(),
                    snap.dataVersion(),
                    snap.minSectionY(),
                    snap.sectionCount(),
                    snap.residualNbtRelOffset(),
                    snap.dictIdToRuntimeId() != null ? snap.dictIdToRuntimeId().clone() : null));
            assertEquals(snap.hashCode(), snap.hashCode());
            assertTrue(snap.toString().contains("RawChunkFrameSnapshot"));
            assertEquals(1, RawChunkFrameSnapshot.rewriteSectionFramePaletteHeaderBytes(new byte[]{0x00}, new int[]{1}).length);

            // Cover RawChunkFrameSnapshot non-residual and truncated-residual hasIsLightOn() branches
            byte[] noResFrame = new byte[24 + (24 << 1)];
            RawChunkFrameSnapshot noResSnap = new RawChunkFrameSnapshot(noResFrame, 4671, -4, 24, 0, null);
            assertFalse(noResSnap.hasResidualNbt());
            assertFalse(noResSnap.hasIsLightOn());
            assertEquals(0, noResSnap.sliceResidualTailBytes().length);

            byte[] shortResFrame = new byte[24 + (24 << 1) + 1];
            shortResFrame[21] = 1; // chunkFlags bit 0 = hasResidualNbt
            RawChunkFrameSnapshot shortResSnap = new RawChunkFrameSnapshot(shortResFrame, 4671, -4, 24, 0, null);
            assertTrue(shortResSnap.hasResidualNbt());
            assertFalse(shortResSnap.hasIsLightOn());
            assertFalse(snap.equals(noResSnap));
            assertFalse(snap.equals("not-a-snapshot"));
            assertTrue(snap.equals(snap));
            assertEquals(1, RawChunkFrameSnapshot.rewriteSectionFramePaletteHeaderBytes(new byte[]{0x10}, null).length);
            assertEquals(5, RawChunkFrameSnapshot.rewriteSectionFramePaletteHeaderBytes(new byte[]{0x10, 1, 2, 3, 4}, new int[]{9}).length);
            assertEquals(0, noResSnap.sliceSectionFrame(0).length);
            assertEquals(0, noResSnap.sliceSectionFrameWithRuntimePalette(0, null).length);
            assertTrue(new RawChunkFrameSnapshot(noResFrame, 4671, -4, 24, 0, new int[]{1}).toString().contains("dictEntries=1"));
            assertTrue(noResSnap.toString().contains("dictEntries=0"));

            // Exercise inline-uniform resolution, palette-header rewrite edge cases, and equals field mismatches
            byte[] inlineFrame = new byte[24 + (24 << 1)];
            inlineFrame[24] = (byte) 0x80; // section 0 entry = 0x8001 (big-endian)
            inlineFrame[25] = 1;
            inlineFrame[26] = (byte) 0x80; // section 1 entry = 0x8005
            inlineFrame[27] = 5;
            RawChunkFrameSnapshot inlineSnap = new RawChunkFrameSnapshot(inlineFrame, 4671, -4, 24, 0, new int[]{10, 42});
            assertEquals(42, inlineSnap.resolveInlineUniformRuntimeStateId(0, null));
            assertEquals(5, inlineSnap.resolveInlineUniformRuntimeStateId(1, null));
            assertEquals(0, inlineSnap.sliceSectionFrame(0).length);
            RawChunkFrameSnapshot inlineNoDict = new RawChunkFrameSnapshot(inlineFrame, 4671, -4, 24, 0, null);
            assertEquals(1, inlineNoDict.resolveInlineUniformRuntimeStateId(0, null));
            assertFalse(inlineSnap.equals(new RawChunkFrameSnapshot(inlineFrame, 4672, -4, 24, 0, new int[]{10, 42})));
            assertFalse(inlineSnap.equals(new RawChunkFrameSnapshot(inlineFrame, 4671, -3, 24, 0, new int[]{10, 42})));
            assertFalse(inlineSnap.equals(new RawChunkFrameSnapshot(inlineFrame, 4671, -4, 20, 0, new int[]{10, 42})));
            assertFalse(inlineSnap.equals(new RawChunkFrameSnapshot(inlineFrame, 4671, -4, 24, 1, new int[]{10, 42})));
            assertFalse(inlineSnap.equals(new RawChunkFrameSnapshot(noResFrame, 4671, -4, 24, 0, new int[]{10, 42})));
            assertFalse(inlineSnap.equals(inlineNoDict));
            // rewriteSectionFramePaletteHeaderBytes: blockMode=0, k=0, k=4097, k=1 with out-of-range diskId & bodyLen=0
            assertEquals(2, RawChunkFrameSnapshot.rewriteSectionFramePaletteHeaderBytes(new byte[]{0x00, 0x00}, new int[]{1}).length);
            assertEquals(3, RawChunkFrameSnapshot.rewriteSectionFramePaletteHeaderBytes(new byte[]{0x00, (byte) 0x81, 0x20}, new int[]{1}).length);
            byte[] rewrittenEmptyBody = RawChunkFrameSnapshot.rewriteSectionFramePaletteHeaderBytes(
                    new byte[]{0x00, 0x01, 0x05}, new int[]{99});
            assertEquals(3, rewrittenEmptyBody.length);
            assertEquals(5, rewrittenEmptyBody[2]);
            // sliceResidualTailBytes when residualNbtRelOffset points past frame length
            byte[] pastResFrame = new byte[24 + (24 << 1)];
            pastResFrame[21] = 1;
            RawChunkFrameSnapshot pastResSnap = new RawChunkFrameSnapshot(pastResFrame, 4671, -4, 24, 9999, null);
            assertEquals(0, pastResSnap.sliceResidualTailBytes().length);

            // Exercise >256-state short-ordinal CompactBaseIndex branch on .lca SectionFrame
            int[] pal300 = new int[300];
            for (int i = 0; i < 300; i++) {
                pal300[i] = i + 1;
            }
            // 9 bits per entry -> 7 entries per long -> ceil(4096 / 7) = 586 longs
            long[] data300 = new long[586];
            for (int v = 0; v < 300; v++) {
                int w = v / 7;
                int sub = (v % 7) * 9;
                data300[w] |= ((long) v) << sub;
            }
            LcaSectionCodec.EncodedSection enc300 = LcaSectionCodec.encodeSection(
                    pal300, data300, null, null, fullSky, LcaRegionWriter.ALL_ZERO_LIGHT_2048, -1, 0);
            LcaSectionBacking backing300 = LcaSectionBacking.fromColdSectionFrame(
                    enc300.frameBytes, 0, -1, -1, -1, id -> id != 0, id -> (id & 1) == 0, id -> false);
            assertEquals(4096, backing300.nonEmptyBlockCount());
            assertTrue(backing300.tickingBlockCount() > 0);
            assertEquals(10, backing300.getStateId(9, 0, 0));
        }
    }
}

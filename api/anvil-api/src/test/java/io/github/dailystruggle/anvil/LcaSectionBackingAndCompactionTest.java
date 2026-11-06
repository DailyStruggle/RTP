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
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, leafrtp-ADR-072, ADR-115
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
    @DisplayName("Overlay stage transitions (1..16 -> 17..48 at <=75% load -> 49+ bulk), idempotent overwrites, and bounded probe length")
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

        // 49th unique voxel promotes to STAGE_BULK_4096 and triggers immediate compaction readiness
        backing.setStateId(15, 15, 15, 20, 201L);
        assertEquals(49, backing.dirtyCount());
        assertEquals(LcaSectionBacking.STAGE_BULK_4096, backing.overlayStage());
        assertEquals(1, backing.maxProbeLengthInOverlay());
        assertTrue(backing.isReadyForQuietWindowCompaction(201L, 1000L));
        assertEquals(1, backing.tickingFluidCount());

        // In-place overwrite in STAGE_BULK_4096 must not increment dirtyCount
        backing.setStateId(15, 15, 15, 0, 202L);
        assertEquals(49, backing.dirtyCount());
        assertEquals(0, backing.getStateId(15, 15, 15));
        assertEquals(0, backing.tickingFluidCount());
        assertEquals(48, backing.nonEmptyBlockCount());
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
}

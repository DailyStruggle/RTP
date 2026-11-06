package io.github.dailystruggle.anvil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Lock-free L1-resident section dirty overlay and double-buffered compaction state machine for LCA chunks.
 *
 * <p>Implements 32-bit packed voxel overlay entries ({@code entry32 == 0} empty sentinel with {@code (pos12 + 1) << 19}),
 * staged progression ({@code int[16]} linear scan to {@code int[64]} Fibonacci hash at {@code <= 75%} load factor to
 * {@code int[4096]} direct table), 64-bit octant fast-path read filtering, authoritative {@code overlayB} revert
 * tombstones during compaction, release-ordered scalar counter publication after {@code dirtyOverlay} updates,
 * non-pinning ephemeral {@code Copy-on-Read} snapshots for client meshing, Paper/Folia {@code Moonrise}
 * ticking-list delta callbacks, and {@code UNLOADED} bit-63 epoch fencing.
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, anvil-api-ADR-003, leafrtp-ADR-072, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
public final class LcaSectionBacking {

    public static final int STAGE_EMPTY = 0;
    public static final int STAGE_LINEAR_16 = 1;
    public static final int STAGE_HASH_64 = 2;
    public static final int STAGE_BULK_4096 = 3;

    public static final int LINEAR_16_CAPACITY = 16;
    public static final int HASH_64_CAPACITY = 64;
    public static final int HASH_64_MAX_LOAD = 48;
    public static final int BULK_4096_CAPACITY = 4096;

    public static final int MAX_POS_12 = 4095;
    public static final int MAX_STATE_ID_19 = 0x7FFFF;

    public static final int FIBONACCI_HASH_MULTIPLIER = 0x9E3779B9;
    public static final int FIBONACCI_HASH_SHIFT = 26;

    public static final long UNLOADED_BIT = 1L << 63;
    public static final long EPOCH_MAGNITUDE_MASK = Long.MAX_VALUE;

    private static final ThreadLocal<int[]> THREAD_LOCAL_EPHEMERAL_VOXELS =
            ThreadLocal.withInitial(() -> new int[BULK_4096_CAPACITY]);

    private static final VarHandle COMMITTED_STATE;
    private static final VarHandle ACTIVE_OVERLAY;
    private static final VarHandle NON_EMPTY_BLOCK_COUNT;
    private static final VarHandle TICKING_BLOCK_COUNT;
    private static final VarHandle TICKING_FLUID_COUNT;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            COMMITTED_STATE = lookup.findVarHandle(
                    LcaSectionBacking.class, "committedState", SectionSnapshotState.class);
            ACTIVE_OVERLAY = lookup.findVarHandle(
                    LcaSectionBacking.class, "activeOverlay", OverlayTable.class);
            NON_EMPTY_BLOCK_COUNT = lookup.findVarHandle(
                    LcaSectionBacking.class, "nonEmptyBlockCount", int.class);
            TICKING_BLOCK_COUNT = lookup.findVarHandle(
                    LcaSectionBacking.class, "tickingBlockCount", int.class);
            TICKING_FLUID_COUNT = lookup.findVarHandle(
                    LcaSectionBacking.class, "tickingFluidCount", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Callback notified when a voxel coordinate transitions into or out of random-ticking block/fluid
     * state, enabling zero-copy synchronization with Paper/Folia {@code Moonrise} {@code SimpleRandomTickList}.
     */
    public interface TickingCoordinateCallback {
        void onTickingBlockFlip(int pos12, int oldStateId, int newStateId, boolean nowTickingBlock);

        default void onTickingFluidFlip(int pos12, int oldStateId, int newStateId, boolean nowTickingFluid) {
        }
    }

    private final IntPredicate nonEmptyPredicate;
    private final IntPredicate tickingBlockPredicate;
    private final IntPredicate tickingFluidPredicate;
    private final IntUnaryOperator defaultBaseFrameDecoder;
    private final AtomicInteger ephemeralCopyCount = new AtomicInteger();

    private volatile SectionSnapshotState committedState;
    private volatile OverlayTable activeOverlay;
    private volatile long lastMutatedTick;
    private volatile TickingCoordinateCallback tickingCoordinateCallback;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int nonEmptyBlockCount;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int tickingBlockCount;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int tickingFluidCount;

    public LcaSectionBacking() {
        this(null, 0);
    }

    public LcaSectionBacking(byte[] baseFrame, int uniformStateId) {
        this(baseFrame, uniformStateId, null);
    }

    public LcaSectionBacking(
            byte[] baseFrame,
            int uniformStateId,
            IntUnaryOperator defaultBaseFrameDecoder) {
        this(
                baseFrame,
                uniformStateId,
                uniformStateId != 0 ? BULK_4096_CAPACITY : 0,
                0,
                0,
                stateId -> stateId != 0,
                stateId -> false,
                stateId -> false,
                defaultBaseFrameDecoder);
    }

    public LcaSectionBacking(
            byte[] baseFrame,
            int uniformStateId,
            int nonEmptyBlockCount,
            int tickingBlockCount,
            int tickingFluidCount,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate,
            IntUnaryOperator defaultBaseFrameDecoder) {
        this.nonEmptyPredicate = Objects.requireNonNull(nonEmptyPredicate, "nonEmptyPredicate");
        this.tickingBlockPredicate = Objects.requireNonNull(tickingBlockPredicate, "tickingBlockPredicate");
        this.tickingFluidPredicate = Objects.requireNonNull(tickingFluidPredicate, "tickingFluidPredicate");
        this.defaultBaseFrameDecoder = defaultBaseFrameDecoder;
        this.committedState = new SectionSnapshotState(baseFrame, uniformStateId, null, 0L, 1L);
        this.activeOverlay = new OverlayTable();
        this.nonEmptyBlockCount = nonEmptyBlockCount;
        this.tickingBlockCount = tickingBlockCount;
        this.tickingFluidCount = tickingFluidCount;
    }

    /**
     * Installs an optional callback invoked whenever a coordinate's random-ticking status changes
     * (used by Paper/Folia {@code Moonrise} {@code SimpleRandomTickList} adapters).
     */
    public void setTickingCoordinateCallback(TickingCoordinateCallback callback) {
        this.tickingCoordinateCallback = callback;
    }

    public TickingCoordinateCallback tickingCoordinateCallback() {
        return this.tickingCoordinateCallback;
    }

    /**
     * Encodes section-local coordinates {@code (x, y, z)} in {@code 0..15} into a 12-bit YZX index {@code 0..4095}.
     * Always masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry.
     */
    public static int pos12(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /**
     * Encodes section-local coordinates {@code (x, y, z)} into a 6-bit {@code 4x4x4} octant index {@code 0..63}.
     * Always masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry.
     */
    public static int octantIdx(int x, int y, int z) {
        return (((y & 15) >>> 2) << 4) | (((z & 15) >>> 2) << 2) | ((x & 15) >>> 2);
    }

    /**
     * Extracts the 6-bit {@code 4x4x4} octant index {@code 0..63} directly from a 12-bit {@code pos12}.
     */
    public static int octantIdxFromPos12(int pos12) {
        int maskedPos = pos12 & MAX_POS_12;
        return (((maskedPos >>> 10) & 3) << 4) | (((maskedPos >>> 6) & 3) << 2) | ((maskedPos >>> 2) & 3);
    }

    /**
     * Packs a 12-bit position and 19-bit block state ID into a non-zero 32-bit overlay entry.
     *
     * <p>Shifts {@code (pos12 + 1)} into bits {@code 31..19} so {@code entry32 == 0} is strictly reserved as the
     * empty-slot sentinel even when {@code pos12 == 0} and {@code stateId19 == 0} (air at origin).
     */
    public static int packEntry32(int pos12, int stateId19) {
        if (pos12 < 0 || pos12 > MAX_POS_12) {
            throw new IllegalArgumentException("pos12 out of range [0..4095]: " + pos12);
        }
        if (stateId19 < 0 || stateId19 > MAX_STATE_ID_19) {
            throw new IllegalArgumentException("stateId19 out of range [0..524287]: " + stateId19);
        }
        return ((pos12 + 1) << 19) | (stateId19 & MAX_STATE_ID_19);
    }

    /**
     * Unpacks the 12-bit voxel coordinate {@code 0..4095} from a non-zero {@code entry32} (returns {@code -1} for {@code 0}).
     */
    public static int unpackPos12(int entry32) {
        return (entry32 >>> 19) - 1;
    }

    /**
     * Unpacks the 19-bit block state ID {@code 0..524287} from an {@code entry32}.
     */
    public static int unpackStateId19(int entry32) {
        return entry32 & MAX_STATE_ID_19;
    }

    /**
     * Computes the initial slot in {@code int[64]} using 32-bit Fibonacci hashing ({@code >>> 26}).
     */
    public static int initialSlot64(int pos12) {
        return (((pos12 & MAX_POS_12) * FIBONACCI_HASH_MULTIPLIER) >>> FIBONACCI_HASH_SHIFT) & 63;
    }

    /**
     * Writes a block state into the active overlay ({@code overlayB}), masking coordinates at entry
     * and publishing {@code nonEmptyBlockCount} with release semantics strictly after updating
     * {@code dirtyOverlay} and {@code dirtyOctantMask64}.
     */
    public void setStateId(int x, int y, int z, int stateId19, long currentTick) {
        setAndGetPreviousStateId(x, y, z, stateId19, currentTick, this.defaultBaseFrameDecoder);
    }

    /**
     * Writes a block state into the active overlay ({@code overlayB}) using the supplied {@code baseFrameDecoder} for
     * prior-state scalar counter resolution.
     */
    public void setStateId(
            int x,
            int y,
            int z,
            int stateId19,
            long currentTick,
            IntUnaryOperator baseFrameDecoder) {
        setAndGetPreviousStateId(x, y, z, stateId19, currentTick, baseFrameDecoder);
    }

    /**
     * Atomically masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry, reads the prior state ID,
     * updates {@code dirtyOverlay} and {@code dirtyOctantMask64}, and then publishes {@code nonEmptyBlockCount},
     * {@code tickingBlockCount}, and {@code tickingFluidCount} with release semantics.
     */
    public synchronized int setAndGetPreviousStateId(
            int x,
            int y,
            int z,
            int stateId19,
            long currentTick,
            IntUnaryOperator baseFrameDecoder) {
        if (stateId19 < 0 || stateId19 > MAX_STATE_ID_19) {
            throw new IllegalArgumentException("stateId19 out of range [0..524287]: " + stateId19);
        }
        int mx = x & 15;
        int my = y & 15;
        int mz = z & 15;
        int previousStateId = getStateId(mx, my, mz, baseFrameDecoder);
        int pos = pos12(mx, my, mz);
        int octant = octantIdx(mx, my, mz);

        // 1. Update dirtyOverlay and dirtyOctantMask64 FIRST
        this.activeOverlay.put(pos, octant, stateId19);
        this.lastMutatedTick = currentTick;

        // 2. Publish scalar counters (nonEmptyBlockCount, tickingBlockCount, tickingFluidCount) AFTER
        // dirtyOverlay and dirtyOctantMask64 with release semantics so readers checking hasOnlyAir()
        // never observe a non-empty count before the overlay slot is visible.
        if (previousStateId != stateId19) {
            publishScalarCountersRelease(pos, previousStateId, stateId19);
        }
        return previousStateId;
    }

    private void publishScalarCountersRelease(int pos12, int oldState, int newState) {
        TickingCoordinateCallback callback = this.tickingCoordinateCallback;

        boolean wasTickingBlock = this.tickingBlockPredicate.test(oldState);
        boolean isTickingBlock = this.tickingBlockPredicate.test(newState);
        if (wasTickingBlock != isTickingBlock) {
            int nextTickingBlocks = Math.max(0, this.tickingBlockCount + (isTickingBlock ? 1 : -1));
            TICKING_BLOCK_COUNT.setRelease(this, nextTickingBlocks);
            if (callback != null) {
                callback.onTickingBlockFlip(pos12, oldState, newState, isTickingBlock);
            }
        }

        boolean wasTickingFluid = this.tickingFluidPredicate.test(oldState);
        boolean isTickingFluid = this.tickingFluidPredicate.test(newState);
        if (wasTickingFluid != isTickingFluid) {
            int nextTickingFluids = Math.max(0, this.tickingFluidCount + (isTickingFluid ? 1 : -1));
            TICKING_FLUID_COUNT.setRelease(this, nextTickingFluids);
            if (callback != null) {
                callback.onTickingFluidFlip(pos12, oldState, newState, isTickingFluid);
            }
        }

        boolean wasNonEmpty = this.nonEmptyPredicate.test(oldState);
        boolean isNonEmpty = this.nonEmptyPredicate.test(newState);
        if (wasNonEmpty != isNonEmpty) {
            int nextNonEmpty = Math.max(0, this.nonEmptyBlockCount + (isNonEmpty ? 1 : -1));
            NON_EMPTY_BLOCK_COUNT.setRelease(this, nextNonEmpty);
        }
    }

    /**
     * Applies a batch of section-local packed short updates (matching Mojang's
     * {@code ClientboundSectionBlocksUpdatePacket} {@code (x << 8) | (z << 4) | y} or {@code pos12})
     * directly into {@code activeOverlay}.
     */
    public synchronized void applySectionBlockUpdates(
            int[] pos12Locations,
            int[] stateIds19,
            long currentTick,
            IntUnaryOperator baseFrameDecoder) {
        Objects.requireNonNull(pos12Locations, "pos12Locations");
        Objects.requireNonNull(stateIds19, "stateIds19");
        if (pos12Locations.length != stateIds19.length) {
            throw new IllegalArgumentException("pos12Locations.length != stateIds19.length");
        }
        for (int i = 0; i < pos12Locations.length; i++) {
            int pos = pos12Locations[i] & MAX_POS_12;
            int x = pos & 15;
            int z = (pos >>> 4) & 15;
            int y = (pos >>> 8) & 15;
            setAndGetPreviousStateId(x, y, z, stateIds19[i], currentTick, baseFrameDecoder);
        }
    }

    /**
     * 3-layer lock-free voxel state lookup using the configured default base frame decoder.
     */
    public int getStateId(int x, int y, int z) {
        return getStateId(x, y, z, this.defaultBaseFrameDecoder);
    }

    /**
     * 3-layer lock-free read path: masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry ->
     * octant mask fast path -> {@code activeOverlay} ({@code overlayB}) ->
     * {@code frozenOverlay} ({@code overlayA}) -> {@code baseFrame} (or {@code uniformStateId}).
     */
    public int getStateId(int x, int y, int z, IntUnaryOperator baseFrameDecoder) {
        int mx = x & 15;
        int my = y & 15;
        int mz = z & 15;
        int octant = octantIdx(mx, my, mz);
        long octantBit = 1L << octant;

        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);

        long activeMask = active.octantMask();
        long frozenMask = snap.frozenOctantMask;

        int pos = pos12(mx, my, mz);
        if (((activeMask | frozenMask) & octantBit) == 0L) {
            return decodeFromBaseFrame(pos, snap.baseFrame, snap.uniformStateId, baseFrameDecoder);
        }

        if ((activeMask & octantBit) != 0L) {
            int activeState = active.getStateId(pos);
            if (activeState >= 0) {
                return activeState;
            }
        }

        OverlayTable frozen = snap.frozenOverlay;
        if (frozen != null && (frozenMask & octantBit) != 0L) {
            int frozenState = frozen.getStateId(pos);
            if (frozenState >= 0) {
                return frozenState;
            }
        }

        return decodeFromBaseFrame(pos, snap.baseFrame, snap.uniformStateId, baseFrameDecoder);
    }

    /**
     * Materializes an ephemeral 4,096-entry state ID snapshot for external meshing callers
     * (such as {@code Sodium} {@code WorldSlice} / {@code ChunkRenderRebuildTask} or vanilla
     * {@code SectionCompiler}) using a transient buffer or thread-local scratch array.
     *
     * <p>Strictly preserves the cold {@code (baseFrame, dirtyOverlay)} representation on this
     * {@link LcaSectionBacking} without permanently pinning an inflated {@code SimpleBitStorage}
     * {@code long[]} back onto the owning section ({@code leafanvil-ADR-002} Section 5.B.3).
     */
    public int[] materializeEphemeralStateIds(int[] destinationOrNull, IntUnaryOperator baseFrameDecoder) {
        int[] out = (destinationOrNull != null && destinationOrNull.length >= BULK_4096_CAPACITY)
                ? destinationOrNull
                : THREAD_LOCAL_EPHEMERAL_VOXELS.get();
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);

        byte[] base = snap.baseFrame;
        int uniform = snap.uniformStateId;
        IntUnaryOperator decoder = baseFrameDecoder != null ? baseFrameDecoder : this.defaultBaseFrameDecoder;

        if (base == null || base.length == 0) {
            Arrays.fill(out, 0, BULK_4096_CAPACITY, uniform);
        } else if (decoder != null) {
            for (int pos = 0; pos < BULK_4096_CAPACITY; pos++) {
                out[pos] = decoder.applyAsInt(pos);
            }
        } else if (base.length >= BULK_4096_CAPACITY) {
            for (int pos = 0; pos < BULK_4096_CAPACITY; pos++) {
                out[pos] = base[pos] & 0xFF;
            }
        } else {
            Arrays.fill(out, 0, BULK_4096_CAPACITY, uniform);
        }

        OverlayTable frozen = snap.frozenOverlay;
        if (frozen != null && frozen.dirtyCount() > 0) {
            frozen.forEachEntry((pos12, stateId19) -> out[pos12 & MAX_POS_12] = stateId19);
        }
        if (active != null && active.dirtyCount() > 0) {
            active.forEachEntry((pos12, stateId19) -> out[pos12 & MAX_POS_12] = stateId19);
        }

        this.ephemeralCopyCount.incrementAndGet();
        return out;
    }

    /**
     * Returns the number of ephemeral {@code Copy-on-Read} snapshots materialized from this section.
     */
    public int ephemeralCopyCount() {
        return this.ephemeralCopyCount.get();
    }

    /**
     * Exports the merged {@code frozenOverlay} ({@code overlayA}) + {@code activeOverlay} ({@code overlayB})
     * as packed 32-bit {@code entry32} words for zero-transcode wire streaming ({@code leafanvil-ADR-003}).
     */
    public int[] exportMergedDirtyOverlayEntries() {
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        OverlayTable frozen = snap.frozenOverlay;
        if ((frozen == null || frozen.dirtyCount() == 0) && (active == null || active.dirtyCount() == 0)) {
            return new int[0];
        }
        Map<Integer, Integer> merged = new LinkedHashMap<>();
        if (frozen != null && frozen.dirtyCount() > 0) {
            frozen.forEachEntry(merged::put);
        }
        if (active != null && active.dirtyCount() > 0) {
            active.forEachEntry(merged::put);
        }
        if (merged.isEmpty()) {
            return new int[0];
        }
        int[] entries = new int[merged.size()];
        int idx = 0;
        for (Map.Entry<Integer, Integer> entry : merged.entrySet()) {
            entries[idx++] = packEntry32(entry.getKey(), entry.getValue());
        }
        return entries;
    }

    private int decodeFromBaseFrame(
            int pos12,
            byte[] baseFrame,
            int uniformStateId,
            IntUnaryOperator baseFrameDecoder) {
        if (baseFrame == null || baseFrame.length == 0) {
            return uniformStateId;
        }
        IntUnaryOperator decoder = baseFrameDecoder != null ? baseFrameDecoder : this.defaultBaseFrameDecoder;
        if (decoder != null) {
            return decoder.applyAsInt(pos12 & MAX_POS_12);
        }
        if (baseFrame.length >= BULK_4096_CAPACITY) {
            return baseFrame[pos12 & MAX_POS_12] & 0xFF;
        }
        return uniformStateId;
    }

    /**
     * Evaluates whether the section should be compacted now due to exceeding the {@code int[64]} load ceiling
     * ({@code dirtyCount > 48}) or remaining unmutated across {@code quietWindowTicks}.
     */
    public boolean isReadyForQuietWindowCompaction(long currentTick, long quietWindowTicks) {
        int count = this.activeOverlay.dirtyCount();
        if (count > HASH_64_MAX_LOAD) {
            return true;
        }
        return count > 0 && (currentTick - this.lastMutatedTick) >= quietWindowTicks;
    }

    /**
     * Atomically freezes the current {@code activeOverlay} into {@code frozenOverlay} ({@code overlayA}), installs a
     * fresh {@code activeOverlay} ({@code overlayB}), and returns an immutable {@link CompactionSnapshot}.
     */
    public synchronized CompactionSnapshot beginCompactionSnapshot() {
        while (true) {
            SectionSnapshotState current = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
            if ((current.chunkEpoch & UNLOADED_BIT) != 0L) {
                throw new IllegalStateException("Cannot begin compaction snapshot on an UNLOADED section");
            }

            OverlayTable currentActive = this.activeOverlay;
            OverlayTable frozenToInstall;
            long nextEpoch;
            if (current.frozenOverlay == null) {
                frozenToInstall = currentActive.freezeCopy();
                nextEpoch = current.chunkEpoch;
            } else {
                frozenToInstall = current.frozenOverlay.mergeWithNewer(currentActive);
                nextEpoch = current.chunkEpoch + 1L;
            }

            OverlayTable freshActive = new OverlayTable();
            SectionSnapshotState nextState = new SectionSnapshotState(
                    current.baseFrame,
                    current.uniformStateId,
                    frozenToInstall,
                    frozenToInstall.octantMask(),
                    nextEpoch);

            if (COMMITTED_STATE.compareAndSet(this, current, nextState)) {
                ACTIVE_OVERLAY.setRelease(this, freshActive);
                return new CompactionSnapshot(
                        nextState.baseFrame,
                        nextState.uniformStateId,
                        frozenToInstall,
                        nextEpoch);
            }
        }
    }

    /**
     * Atomically installs {@code (newBaseFrame, newUniformStateId, null frozenOverlay, expectedEpoch + 1)} if and only
     * if {@code chunkEpoch == expectedEpoch} and bit 63 ({@code UNLOADED}) has not been set.
     */
    public boolean completeCompaction(long expectedEpoch, byte[] newBaseFrame, int newUniformStateId) {
        if ((expectedEpoch & UNLOADED_BIT) != 0L) {
            return false;
        }
        while (true) {
            SectionSnapshotState current = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
            if ((current.chunkEpoch & UNLOADED_BIT) != 0L || current.chunkEpoch != expectedEpoch) {
                return false;
            }
            SectionSnapshotState next = new SectionSnapshotState(
                    newBaseFrame,
                    newUniformStateId,
                    null,
                    0L,
                    expectedEpoch + 1L);
            if (COMMITTED_STATE.compareAndSet(this, current, next)) {
                return true;
            }
        }
    }

    /**
     * Atomically sets bit 63 ({@code UNLOADED_BIT}) on {@code chunkEpoch}, fencing off any in-flight background
     * compaction from committing to this section.
     */
    public long markUnloaded() {
        while (true) {
            SectionSnapshotState current = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
            if ((current.chunkEpoch & UNLOADED_BIT) != 0L) {
                return current.chunkEpoch;
            }
            long unloadedEpoch = current.chunkEpoch | UNLOADED_BIT;
            SectionSnapshotState next = new SectionSnapshotState(
                    current.baseFrame,
                    current.uniformStateId,
                    current.frozenOverlay,
                    current.frozenOctantMask,
                    unloadedEpoch);
            if (COMMITTED_STATE.compareAndSet(this, current, next)) {
                return unloadedEpoch;
            }
        }
    }

    public boolean isUnloaded() {
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        return (snap.chunkEpoch & UNLOADED_BIT) != 0L;
    }

    public long chunkEpoch() {
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        return snap.chunkEpoch;
    }

    public int overlayStage() {
        return ((OverlayTable) ACTIVE_OVERLAY.getAcquire(this)).stage();
    }

    public int dirtyCount() {
        return ((OverlayTable) ACTIVE_OVERLAY.getAcquire(this)).dirtyCount();
    }

    public long dirtyOctantMask64() {
        return ((OverlayTable) ACTIVE_OVERLAY.getAcquire(this)).octantMask();
    }

    public long activeOctantMask() {
        return ((OverlayTable) ACTIVE_OVERLAY.getAcquire(this)).octantMask();
    }

    public long frozenOctantMask() {
        return ((SectionSnapshotState) COMMITTED_STATE.getAcquire(this)).frozenOctantMask;
    }

    public int maxProbeLengthInOverlay() {
        return ((OverlayTable) ACTIVE_OVERLAY.getAcquire(this)).maxProbeLength();
    }

    public long lastMutatedTick() {
        return this.lastMutatedTick;
    }

    public int nonEmptyBlockCount() {
        return (int) NON_EMPTY_BLOCK_COUNT.getAcquire(this);
    }

    public boolean hasOnlyAir() {
        return nonEmptyBlockCount() == 0;
    }

    public int tickingBlockCount() {
        return (int) TICKING_BLOCK_COUNT.getAcquire(this);
    }

    public int tickingFluidCount() {
        return (int) TICKING_FLUID_COUNT.getAcquire(this);
    }

    public byte[] baseFrame() {
        return ((SectionSnapshotState) COMMITTED_STATE.getAcquire(this)).baseFrame;
    }

    public int uniformStateId() {
        return ((SectionSnapshotState) COMMITTED_STATE.getAcquire(this)).uniformStateId;
    }

    public OverlayTable activeOverlay() {
        return (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
    }

    public OverlayTable frozenOverlay() {
        return ((SectionSnapshotState) COMMITTED_STATE.getAcquire(this)).frozenOverlay;
    }

    private static final class SectionSnapshotState {
        final byte[] baseFrame;
        final int uniformStateId;
        final OverlayTable frozenOverlay;
        final long frozenOctantMask;
        final long chunkEpoch;

        SectionSnapshotState(
                byte[] baseFrame,
                int uniformStateId,
                OverlayTable frozenOverlay,
                long frozenOctantMask,
                long chunkEpoch) {
            this.baseFrame = baseFrame;
            this.uniformStateId = uniformStateId;
            this.frozenOverlay = frozenOverlay;
            this.frozenOctantMask = frozenOctantMask;
            this.chunkEpoch = chunkEpoch;
        }
    }

    /**
     * Immutable compaction input captured by {@link #beginCompactionSnapshot()}.
     */
    public record CompactionSnapshot(
            byte[] baseFrame,
            int uniformStateId,
            OverlayTable frozenOverlay,
            long epoch) {
    }

    /**
     * Staged L1-resident overlay table supporting {@code STAGE_EMPTY (0)}, {@code STAGE_LINEAR_16 (1)},
     * {@code STAGE_HASH_64 (2)} (Fibonacci hash, capped at 48 entries / 75% load factor), and
     * {@code STAGE_BULK_4096 (3)}.
     */
    public static final class OverlayTable {
        private static final int[] EMPTY_SLOTS = new int[0];

        private volatile int stage;
        private volatile int dirtyCount;
        private volatile long octantMask;
        private volatile int[] slots;

        public OverlayTable() {
            this.stage = STAGE_EMPTY;
            this.dirtyCount = 0;
            this.octantMask = 0L;
            this.slots = EMPTY_SLOTS;
        }

        private OverlayTable(int stage, int dirtyCount, long octantMask, int[] slots) {
            this.stage = stage;
            this.dirtyCount = dirtyCount;
            this.octantMask = octantMask;
            this.slots = slots;
        }

        public int stage() {
            return this.stage;
        }

        public int dirtyCount() {
            return this.dirtyCount;
        }

        public long octantMask() {
            return this.octantMask;
        }

        /**
         * Returns the 19-bit state ID for {@code pos12}, or {@code -1} on miss.
         */
        public int getStateId(int pos12) {
            int maskedPos = pos12 & MAX_POS_12;
            int currentStage = this.stage;
            int[] table = this.slots;
            if (currentStage == STAGE_EMPTY || table.length == 0) {
                return -1;
            }
            if (currentStage == STAGE_LINEAR_16) {
                int limit = Math.min(this.dirtyCount, table.length);
                for (int i = 0; i < limit; i++) {
                    int entry = table[i];
                    if (entry != 0 && unpackPos12(entry) == maskedPos) {
                        return unpackStateId19(entry);
                    }
                }
                return -1;
            }
            if (currentStage == STAGE_HASH_64) {
                int slot = initialSlot64(maskedPos);
                for (int probes = 0; probes < HASH_64_CAPACITY; probes++) {
                    int entry = table[slot];
                    if (entry == 0) {
                        return -1;
                    }
                    if (unpackPos12(entry) == maskedPos) {
                        return unpackStateId19(entry);
                    }
                    slot = (slot + 1) & 63;
                }
                return -1;
            }
            int entry = table[maskedPos];
            return entry == 0 ? -1 : unpackStateId19(entry);
        }

        synchronized void put(int pos12, int octantIdx, int stateId19) {
            int maskedPos = pos12 & MAX_POS_12;
            int maskedOctant = octantIdx & 63;
            int packed = packEntry32(maskedPos, stateId19);
            int currentStage = this.stage;

            if (currentStage == STAGE_EMPTY) {
                int[] linear = new int[LINEAR_16_CAPACITY];
                linear[0] = packed;
                this.slots = linear;
                this.dirtyCount = 1;
                this.stage = STAGE_LINEAR_16;
                this.octantMask |= (1L << maskedOctant);
                return;
            }

            if (currentStage == STAGE_LINEAR_16) {
                int[] linear = this.slots;
                int count = this.dirtyCount;
                for (int i = 0; i < count; i++) {
                    if (unpackPos12(linear[i]) == maskedPos) {
                        linear[i] = packed;
                        this.octantMask |= (1L << maskedOctant);
                        return;
                    }
                }
                if (count < LINEAR_16_CAPACITY) {
                    linear[count] = packed;
                    this.dirtyCount = count + 1;
                    this.octantMask |= (1L << maskedOctant);
                    return;
                }
                int[] hash64 = new int[HASH_64_CAPACITY];
                for (int i = 0; i < LINEAR_16_CAPACITY; i++) {
                    insertIntoHash64(hash64, linear[i]);
                }
                insertIntoHash64(hash64, packed);
                this.slots = hash64;
                this.dirtyCount = LINEAR_16_CAPACITY + 1;
                this.stage = STAGE_HASH_64;
                this.octantMask |= (1L << maskedOctant);
                return;
            }

            if (currentStage == STAGE_HASH_64) {
                int[] hash64 = this.slots;
                int slot = initialSlot64(maskedPos);
                for (int probes = 0; probes < HASH_64_CAPACITY; probes++) {
                    int existing = hash64[slot];
                    if (existing == 0) {
                        if (this.dirtyCount < HASH_64_MAX_LOAD) {
                            hash64[slot] = packed;
                            this.dirtyCount++;
                            this.octantMask |= (1L << maskedOctant);
                            return;
                        }
                        break;
                    }
                    if (unpackPos12(existing) == maskedPos) {
                        hash64[slot] = packed;
                        this.octantMask |= (1L << maskedOctant);
                        return;
                    }
                    slot = (slot + 1) & 63;
                }

                int[] bulk = new int[BULK_4096_CAPACITY];
                for (int i = 0; i < HASH_64_CAPACITY; i++) {
                    int entry = hash64[i];
                    if (entry != 0) {
                        bulk[unpackPos12(entry)] = entry;
                    }
                }
                bulk[maskedPos] = packed;
                this.slots = bulk;
                this.dirtyCount = HASH_64_MAX_LOAD + 1;
                this.stage = STAGE_BULK_4096;
                this.octantMask |= (1L << maskedOctant);
                return;
            }

            int[] bulk = this.slots;
            int existing = bulk[maskedPos];
            bulk[maskedPos] = packed;
            if (existing == 0) {
                this.dirtyCount++;
            }
            this.octantMask |= (1L << maskedOctant);
        }

        private static void insertIntoHash64(int[] hash64, int packedEntry) {
            int pos = unpackPos12(packedEntry);
            int slot = initialSlot64(pos);
            while (hash64[slot] != 0) {
                if (unpackPos12(hash64[slot]) == pos) {
                    hash64[slot] = packedEntry;
                    return;
                }
                slot = (slot + 1) & 63;
            }
            hash64[slot] = packedEntry;
        }

        /**
         * Computes the worst-case probe length across all currently occupied slots in this overlay.
         */
        public int maxProbeLength() {
            int currentStage = this.stage;
            int[] table = this.slots;
            if (currentStage == STAGE_EMPTY || this.dirtyCount == 0) {
                return 0;
            }
            if (currentStage == STAGE_LINEAR_16) {
                return this.dirtyCount;
            }
            if (currentStage == STAGE_HASH_64) {
                int maxProbes = 0;
                for (int slot = 0; slot < HASH_64_CAPACITY; slot++) {
                    int entry = table[slot];
                    if (entry != 0) {
                        int pos = unpackPos12(entry);
                        int start = initialSlot64(pos);
                        int probes = ((slot - start) & 63) + 1;
                        if (probes > maxProbes) {
                            maxProbes = probes;
                        }
                    }
                }
                return maxProbes;
            }
            return 1;
        }

        OverlayTable freezeCopy() {
            if (this.stage == STAGE_EMPTY) {
                return new OverlayTable();
            }
            return new OverlayTable(
                    this.stage,
                    this.dirtyCount,
                    this.octantMask,
                    Arrays.copyOf(this.slots, this.slots.length));
        }

        OverlayTable mergeWithNewer(OverlayTable newer) {
            OverlayTable merged = freezeCopy();
            newer.forEachEntry((pos12, stateId19) ->
                    merged.put(pos12, octantIdxFromPos12(pos12), stateId19));
            return merged;
        }

        /**
         * Visits all dirty {@code (pos12, stateId19)} pairs stored in this overlay table.
         */
        public void forEachEntry(OverlayEntryConsumer consumer) {
            Objects.requireNonNull(consumer, "consumer");
            int currentStage = this.stage;
            int[] table = this.slots;
            if (currentStage == STAGE_EMPTY || table.length == 0) {
                return;
            }
            if (currentStage == STAGE_LINEAR_16) {
                int limit = Math.min(this.dirtyCount, table.length);
                for (int i = 0; i < limit; i++) {
                    int entry = table[i];
                    if (entry != 0) {
                        consumer.accept(unpackPos12(entry), unpackStateId19(entry));
                    }
                }
                return;
            }
            for (int entry : table) {
                if (entry != 0) {
                    consumer.accept(unpackPos12(entry), unpackStateId19(entry));
                }
            }
        }
    }

    @FunctionalInterface
    public interface OverlayEntryConsumer {
        void accept(int pos12, int stateId19);
    }

    /**
     * Region-wide per-chunk epoch commit fence ({@code 1024} chunk slots per {@code 32x32} region).
     *
     * <p>Prevents stale background compaction workers from overwriting newer committed chunk slabs or resurrecting
     * chunks whose {@code UNLOADED} bit-63 tombstone has already been committed by main-thread unload/save.
     */
    public static final class EpochCommitFence {
        public static final int CHUNK_COUNT = 1024;

        private final AtomicLongArray lastCommittedEpoch = new AtomicLongArray(CHUNK_COUNT);

        /**
         * Attempts to advance {@code lastCommittedEpoch[chunkIdx1024]} to {@code candidateEpoch} for a background flush.
         *
         * <p>Strips bit 63 when comparing epoch magnitude and rejects any candidate carrying {@code UNLOADED_BIT},
         * any candidate whose magnitude is {@code <= lastCommittedEpoch[chunkIdx1024]}, or any slot where the
         * main-thread {@code UNLOADED} tombstone is already set.
         */
        public boolean tryCommitChunkEpoch(int chunkIdx1024, long candidateEpoch) {
            checkChunkIndex(chunkIdx1024);
            if ((candidateEpoch & UNLOADED_BIT) != 0L) {
                return false;
            }
            long candidateMagnitude = candidateEpoch & EPOCH_MAGNITUDE_MASK;
            while (true) {
                long current = this.lastCommittedEpoch.get(chunkIdx1024);
                if ((current & UNLOADED_BIT) != 0L) {
                    return false;
                }
                long currentMagnitude = current & EPOCH_MAGNITUDE_MASK;
                if (candidateMagnitude <= currentMagnitude) {
                    return false;
                }
                if (this.lastCommittedEpoch.compareAndSet(chunkIdx1024, current, candidateMagnitude)) {
                    return true;
                }
            }
        }

        /**
         * Commits an authoritative main-thread unload/save tombstone (setting bit 63) at {@code chunkIdx1024}.
         */
        public long commitUnloadTombstone(int chunkIdx1024, long unloadEpoch) {
            checkChunkIndex(chunkIdx1024);
            long unloadMagnitude = unloadEpoch & EPOCH_MAGNITUDE_MASK;
            while (true) {
                long current = this.lastCommittedEpoch.get(chunkIdx1024);
                long currentMagnitude = current & EPOCH_MAGNITUDE_MASK;
                long nextMagnitude = Math.max(currentMagnitude, unloadMagnitude);
                long next = nextMagnitude | UNLOADED_BIT;
                if (this.lastCommittedEpoch.compareAndSet(chunkIdx1024, current, next)) {
                    return next;
                }
            }
        }

        /**
         * Marks {@code chunkIdx1024} with the {@code UNLOADED_BIT} tombstone while preserving its current epoch magnitude.
         */
        public long commitUnloadTombstone(int chunkIdx1024) {
            checkChunkIndex(chunkIdx1024);
            while (true) {
                long current = this.lastCommittedEpoch.get(chunkIdx1024);
                if ((current & UNLOADED_BIT) != 0L) {
                    return current;
                }
                long next = current | UNLOADED_BIT;
                if (this.lastCommittedEpoch.compareAndSet(chunkIdx1024, current, next)) {
                    return next;
                }
            }
        }

        public long getLastCommittedEpoch(int chunkIdx1024) {
            checkChunkIndex(chunkIdx1024);
            return this.lastCommittedEpoch.get(chunkIdx1024);
        }

        public long getLastCommittedMagnitude(int chunkIdx1024) {
            checkChunkIndex(chunkIdx1024);
            return this.lastCommittedEpoch.get(chunkIdx1024) & EPOCH_MAGNITUDE_MASK;
        }

        public boolean isUnloadedTombstoneCommitted(int chunkIdx1024) {
            checkChunkIndex(chunkIdx1024);
            return (this.lastCommittedEpoch.get(chunkIdx1024) & UNLOADED_BIT) != 0L;
        }

        private static void checkChunkIndex(int chunkIdx1024) {
            if (chunkIdx1024 < 0 || chunkIdx1024 >= CHUNK_COUNT) {
                throw new IndexOutOfBoundsException("chunkIdx1024 out of range [0..1023]: " + chunkIdx1024);
            }
        }
    }
}

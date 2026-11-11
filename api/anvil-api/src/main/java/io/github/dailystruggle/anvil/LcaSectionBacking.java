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

    private static final OverlayTable EMPTY_OVERLAY = new OverlayTable();
    private static final ThreadLocal<long[]> THREAD_LOCAL_WORD_MASKS_64 =
            ThreadLocal.withInitial(() -> new long[64]);
    private static final ThreadLocal<CompactEncodeScratch> THREAD_ENCODE_SCRATCH =
            ThreadLocal.withInitial(CompactEncodeScratch::new);

    /**
     * Consumer for {@code (pos12, stateId19)} pairs or {@code (stateId19, count)} pairs.
     */
    @FunctionalInterface
    public interface IntPairConsumer {
        void accept(int first, int second);
    }

    /**
     * Result of encoding a 4,096-voxel section into a compact `.lca` `baseFrame`.
     */
    public record EncodedCompactFrame(int majorityStateId, byte[] frameBytes) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EncodedCompactFrame that)) return false;
            return majorityStateId == that.majorityStateId && Arrays.equals(frameBytes, that.frameBytes);
        }

        @Override
        public int hashCode() {
            return 31 * Integer.hashCode(majorityStateId) + Arrays.hashCode(frameBytes);
        }

        @Override
        public String toString() {
            return "EncodedCompactFrame[majorityStateId=" + majorityStateId
                    + ", frameBytes=" + Arrays.toString(frameBytes) + "]";
        }
    }

    private static final class CompactEncodeScratch {
        final int[] distinctStates = new int[BULK_4096_CAPACITY];
        final int[] stateCounts = new int[BULK_4096_CAPACITY];
        final int[] headPos = new int[BULK_4096_CAPACITY];
        final int[] tailPos = new int[BULK_4096_CAPACITY];
        final int[] nextPos = new int[BULK_4096_CAPACITY];
        final int[] hashSlotToDistinctIdx = new int[8192];
        final int[] hashSlotEpoch = new int[8192];
        int currentEpoch = 1;
        byte[] outBuf = new byte[16_384];
    }

    /**
     * Encodes a 4,096-entry YZX state ID array into a compact `.lca` `baseFrame` using thread-local
     * primitive scratch tables (zero per-call heap allocations beyond the returned `byte[]`).
     */
    public static EncodedCompactFrame encodeCompactSectionFrame(
            int[] voxels4096,
            int skyConst4,
            int blkConst4
    ) {
        CompactBaseIndex idx = CompactBaseIndex.fromVoxelsSinglePass(voxels4096, skyConst4, blkConst4);
        if (idx == null) {
            int majorityStateId = (voxels4096 != null && voxels4096.length > 0)
                    ? Math.max(0, voxels4096[0])
                    : 0;
            return new EncodedCompactFrame(majorityStateId, new byte[0]);
        }
        return new EncodedCompactFrame(idx.majorityStateId, idx.getOrEncodeBaseFrame());
    }

    /**
     * Decodes a compact `.lca` `baseFrame` (either `LCA_V002` container-encoded or legacy delta-varint)
     * into `outVoxels4096[0..4096)`, returning the resolved majority state ID. If `baseFrame` is empty
     * or invalid/truncated, fills `outVoxels4096` with `fallbackMajorityStateId`.
     */
    public static int decodeCompactBaseFrameInto(
            byte[] baseFrame,
            int fallbackMajorityStateId,
            int[] outVoxels4096
    ) {
        Objects.requireNonNull(outVoxels4096, "outVoxels4096");
        if (outVoxels4096.length < BULK_4096_CAPACITY) {
            throw new IllegalArgumentException("outVoxels4096.length < 4096: " + outVoxels4096.length);
        }
        int safeFallback = Math.max(0, fallbackMajorityStateId);
        if (baseFrame == null || baseFrame.length == 0) {
            Arrays.fill(outVoxels4096, 0, BULK_4096_CAPACITY, safeFallback);
            return safeFallback;
        }
        if (baseFrame.length == BULK_4096_CAPACITY) {
            for (int i = 0; i < BULK_4096_CAPACITY; i++) {
                outVoxels4096[i] = baseFrame[i] & 0xFF;
            }
            return safeFallback;
        }
        CompactBaseIndex idx = CompactBaseIndex.tryParse(baseFrame);
        if (idx == null) {
            Arrays.fill(outVoxels4096, 0, BULK_4096_CAPACITY, safeFallback);
            return safeFallback;
        }
        idx.scatterInto(outVoxels4096);
        return idx.majorityStateId;
    }
    public interface VanillaWireSink {
        void writeByte(int b);

        void writeVarInt(int v);

        void writeLong(long word);
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Returns the number of bytes required to encode {@code value} as a Minecraft unsigned VarInt.
     */
    public static int varIntByteSize(int value) {
        if ((value & ~0x7F) == 0) {
            return 1;
        }
        if ((value & ~0x3FFF) == 0) {
            return 2;
        }
        if ((value & ~0x1FFFFF) == 0) {
            return 3;
        }
        if ((value & ~0xFFFFFFF) == 0) {
            return 4;
        }
        return 5;
    }

    /**
     * Single-pass factory that builds a {@link CompactBaseIndex}-backed {@link LcaSectionBacking}
     * directly from a 4,096-entry state ID array without encoding or retaining a duplicate
     * {@code byte[] baseFrame} in memory. The serialized VarInt {@code baseFrame} is lazily
     * materialized only if {@link #baseFrame()} or {@link #beginCompactionSnapshot()} is invoked.
     */
    public static LcaSectionBacking fromDirectVoxels(
            int[] voxels4096,
            int skyConst4,
            int blkConst4,
            int nonEmptyBlockCount,
            int tickingBlockCount,
            int tickingFluidCount,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        CompactBaseIndex idx = CompactBaseIndex.fromVoxelsSinglePass(voxels4096, skyConst4, blkConst4);
        if (idx == null) {
            int uniformId = (voxels4096 != null && voxels4096.length > 0) ? Math.max(0, voxels4096[0]) : 0;
            return new LcaSectionBacking(
                    EMPTY_BYTES,
                    uniformId,
                    nonEmptyBlockCount,
                    tickingBlockCount,
                    tickingFluidCount,
                    nonEmptyPredicate,
                    tickingBlockPredicate,
                    tickingFluidPredicate,
                    null
            );
        }
        return new LcaSectionBacking(
                idx,
                idx.majorityStateId,
                nonEmptyBlockCount,
                tickingBlockCount,
                tickingFluidCount,
                nonEmptyPredicate,
                tickingBlockPredicate,
                tickingFluidPredicate
        );
    }

    /**
     * Off-thread factory that unpacks a cloned Mojang {@code BitStorage} {@code long[]} word array
     * and palette state-ID table in a single pass on a background worker thread, building
     * {@link CompactBaseIndex}, scalar block/fluid counts, and pre-built clean network/disk slices
     * without touching live NMS objects.
     */
    public static LcaSectionBacking fromRawBitStorageSnapshot(
            long[] rawWords,
            int bitsPerEntry,
            int[] paletteStateIds,
            int skyConst4,
            int blkConst4,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        return fromRawBitStorageSnapshot(
                rawWords,
                bitsPerEntry,
                paletteStateIds,
                15,
                skyConst4,
                blkConst4,
                -1,
                -1,
                -1,
                nonEmptyPredicate,
                tickingBlockPredicate,
                tickingFluidPredicate
        );
    }

    /**
     * Off-thread factory that unpacks a cloned Mojang {@code BitStorage} {@code long[]} word array
     * and palette state-ID table in a single pass on a background worker thread, building
     * {@link CompactBaseIndex}, scalar block/fluid counts, and pre-built clean network/disk slices
     * for {@code globalPaletteBits}.
     */
    public static LcaSectionBacking fromRawBitStorageSnapshot(
            long[] rawWords,
            int bitsPerEntry,
            int[] paletteStateIds,
            int globalPaletteBits,
            int skyConst4,
            int blkConst4,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        return fromRawBitStorageSnapshot(
                rawWords,
                bitsPerEntry,
                paletteStateIds,
                globalPaletteBits,
                skyConst4,
                blkConst4,
                -1,
                -1,
                -1,
                nonEmptyPredicate,
                tickingBlockPredicate,
                tickingFluidPredicate
        );
    }

    /**
     * Off-thread factory that unpacks a cloned Mojang {@code BitStorage} {@code long[]} word array
     * and palette state-ID table in a single pass on a background worker thread, computing scalar
     * counts in $O(|P|)$ when {@code initialNonEmptyCount < 0} and pre-building clean network and
     * disk slices.
     */
    public static LcaSectionBacking fromRawBitStorageSnapshot(
            long[] rawWords,
            int bitsPerEntry,
            int[] paletteStateIds,
            int globalPaletteBits,
            int skyConst4,
            int blkConst4,
            int initialNonEmptyCount,
            int initialTickingBlockCount,
            int initialTickingFluidCount,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        return fromRawBitStorageSnapshot(
                rawWords,
                bitsPerEntry,
                paletteStateIds,
                globalPaletteBits,
                skyConst4,
                blkConst4,
                initialNonEmptyCount,
                initialTickingBlockCount,
                initialTickingFluidCount,
                nonEmptyPredicate,
                tickingBlockPredicate,
                tickingFluidPredicate,
                true
        );
    }

    /**
     * Off-thread factory that unpacks a cloned Mojang {@code BitStorage} {@code long[]} word array
     * and palette state-ID table in a single pass on a background worker thread, optionally
     * deferring {@code cleanVanillaWireSlice} and {@code baseFrame} byte-array materialization for
     * cold already-streamed sections to minimize heap footprint.
     */
    public static LcaSectionBacking fromRawBitStorageSnapshot(
            long[] rawWords,
            int bitsPerEntry,
            int[] paletteStateIds,
            int globalPaletteBits,
            int skyConst4,
            int blkConst4,
            int initialNonEmptyCount,
            int initialTickingBlockCount,
            int initialTickingFluidCount,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate,
            boolean prebuildSlices
    ) {
        Objects.requireNonNull(nonEmptyPredicate, "nonEmptyPredicate");
        Objects.requireNonNull(tickingBlockPredicate, "tickingBlockPredicate");
        Objects.requireNonNull(tickingFluidPredicate, "tickingFluidPredicate");

        if (rawWords == null || rawWords.length == 0 || bitsPerEntry <= 0) {
            int uniformId = (paletteStateIds != null && paletteStateIds.length > 0)
                    ? Math.max(0, paletteStateIds[0])
                    : 0;
            int nonEmpty = initialNonEmptyCount >= 0
                    ? initialNonEmptyCount
                    : (nonEmptyPredicate.test(uniformId) ? BULK_4096_CAPACITY : 0);
            int tickingBlocks = initialTickingBlockCount >= 0
                    ? initialTickingBlockCount
                    : (tickingBlockPredicate.test(uniformId) ? BULK_4096_CAPACITY : 0);
            int tickingFluids = initialTickingFluidCount >= 0
                    ? initialTickingFluidCount
                    : (tickingFluidPredicate.test(uniformId) ? BULK_4096_CAPACITY : 0);
            return new LcaSectionBacking(
                    EMPTY_BYTES,
                    uniformId,
                    nonEmpty,
                    tickingBlocks,
                    tickingFluids,
                    nonEmptyPredicate,
                    tickingBlockPredicate,
                    tickingFluidPredicate,
                    null
            );
        }

        int[] voxels = THREAD_LOCAL_EPHEMERAL_VOXELS.get();
        int valuesPerLong = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1L;
        int pos = 0;
        if (paletteStateIds != null && paletteStateIds.length > 0) {
            int palLen = paletteStateIds.length;
            for (int w = 0; w < rawWords.length && pos < BULK_4096_CAPACITY; w++) {
                long word = rawWords[w];
                int limit = Math.min(BULK_4096_CAPACITY, pos + valuesPerLong);
                while (pos < limit) {
                    int pIdx = (int) (word & mask);
                    word >>>= bitsPerEntry;
                    voxels[pos++] = (pIdx >= 0 && pIdx < palLen) ? Math.max(0, paletteStateIds[pIdx]) : 0;
                }
            }
        } else {
            for (int w = 0; w < rawWords.length && pos < BULK_4096_CAPACITY; w++) {
                long word = rawWords[w];
                int limit = Math.min(BULK_4096_CAPACITY, pos + valuesPerLong);
                while (pos < limit) {
                    int stateId = (int) (word & mask);
                    word >>>= bitsPerEntry;
                    voxels[pos++] = Math.max(0, stateId);
                }
            }
        }
        if (pos < BULK_4096_CAPACITY) {
            Arrays.fill(voxels, pos, BULK_4096_CAPACITY, 0);
        }

        CompactBaseIndex idx = CompactBaseIndex.fromVoxelsSinglePass(voxels, skyConst4, blkConst4);
        if (idx == null) {
            int uniformId = Math.max(0, voxels[0]);
            int nonEmpty = initialNonEmptyCount >= 0
                    ? initialNonEmptyCount
                    : (nonEmptyPredicate.test(uniformId) ? BULK_4096_CAPACITY : 0);
            int tickingBlocks = initialTickingBlockCount >= 0
                    ? initialTickingBlockCount
                    : (tickingBlockPredicate.test(uniformId) ? BULK_4096_CAPACITY : 0);
            int tickingFluids = initialTickingFluidCount >= 0
                    ? initialTickingFluidCount
                    : (tickingFluidPredicate.test(uniformId) ? BULK_4096_CAPACITY : 0);
            return new LcaSectionBacking(
                    EMPTY_BYTES,
                    uniformId,
                    nonEmpty,
                    tickingBlocks,
                    tickingFluids,
                    nonEmptyPredicate,
                    tickingBlockPredicate,
                    tickingFluidPredicate,
                    null
            );
        }

        int nonEmpty = initialNonEmptyCount;
        int tickingBlocks = initialTickingBlockCount;
        int tickingFluids = initialTickingFluidCount;
        if (nonEmpty < 0 || tickingBlocks < 0 || tickingFluids < 0) {
            int calcNonEmpty = 0;
            int calcTickBlock = 0;
            int calcTickFluid = 0;
            int[] pal = idx.paletteStateIds;
            int[] counts = idx.paletteCounts;
            for (int i = 0; i < pal.length; i++) {
                int st = pal[i];
                int c = counts[i];
                if (c <= 0) {
                    continue;
                }
                if (nonEmptyPredicate.test(st)) {
                    calcNonEmpty += c;
                }
                if (tickingBlockPredicate.test(st)) {
                    calcTickBlock += c;
                }
                if (tickingFluidPredicate.test(st)) {
                    calcTickFluid += c;
                }
            }
            if (nonEmpty < 0) {
                nonEmpty = calcNonEmpty;
            }
            if (tickingBlocks < 0) {
                tickingBlocks = calcTickBlock;
            }
            if (tickingFluids < 0) {
                tickingFluids = calcTickFluid;
            }
        }

        if (prebuildSlices) {
            idx.getOrBuildCleanVanillaWireSlice(globalPaletteBits);
            idx.getOrEncodeBaseFrame();
        }
        return new LcaSectionBacking(
                idx,
                idx.majorityStateId,
                nonEmpty,
                tickingBlocks,
                tickingFluids,
                nonEmptyPredicate,
                tickingBlockPredicate,
                tickingFluidPredicate
        );
    }

    private static int writeVarIntToArray(byte[] buf, int cursor, int value) {
        int v = value;
        int c = cursor;
        while ((v & ~0x7F) != 0) {
            buf[c++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[c++] = (byte) (v & 0x7F);
        return c;
    }

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
                 computeDefaultNonEmptyCount(baseFrame, uniformStateId),
                0,
                0,
                stateId -> stateId != 0,
                stateId -> false,
                stateId -> false,
                defaultBaseFrameDecoder);
    }

    private static int computeDefaultNonEmptyCount(byte[] baseFrame, int uniformStateId) {
        CompactBaseIndex parsed = CompactBaseIndex.tryParse(baseFrame);
        if (parsed != null) {
            int nonEmpty = 0;
            int[] pal = parsed.paletteStateIds;
            int[] counts = parsed.paletteCounts;
            for (int i = 0; i < pal.length; i++) {
                if (pal[i] != 0 && counts[i] > 0) {
                    nonEmpty += counts[i];
                }
            }
            return nonEmpty;
        }
        return uniformStateId != 0 ? BULK_4096_CAPACITY : 0;
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
        CompactBaseIndex parsedIndex = CompactBaseIndex.tryParse(baseFrame);
        int resolvedUniformId = (parsedIndex != null && uniformStateId == 0)
                ? parsedIndex.majorityStateId
                : uniformStateId;
        this.committedState = new SectionSnapshotState(
                baseFrame,
                resolvedUniformId,
                parsedIndex,
                null,
                0L,
                1L);
        this.activeOverlay = EMPTY_OVERLAY;
        this.nonEmptyBlockCount = nonEmptyBlockCount;
        this.tickingBlockCount = tickingBlockCount;
        this.tickingFluidCount = tickingFluidCount;
    }

    private LcaSectionBacking(
            CompactBaseIndex directCompactIndex,
            int uniformStateId,
            int nonEmptyBlockCount,
            int tickingBlockCount,
            int tickingFluidCount,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate) {
        this.nonEmptyPredicate = Objects.requireNonNull(nonEmptyPredicate, "nonEmptyPredicate");
        this.tickingBlockPredicate = Objects.requireNonNull(tickingBlockPredicate, "tickingBlockPredicate");
        this.tickingFluidPredicate = Objects.requireNonNull(tickingFluidPredicate, "tickingFluidPredicate");
        this.defaultBaseFrameDecoder = null;
        this.committedState = new SectionSnapshotState(
                null,
                uniformStateId,
                Objects.requireNonNull(directCompactIndex, "directCompactIndex"),
                null,
                0L,
                1L);
        this.activeOverlay = EMPTY_OVERLAY;
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

        // 1. Update dirtyOverlay and dirtyOctantMask64 FIRST (lazy-allocating on first mutation)
        OverlayTable currentOverlay = this.activeOverlay;
        if (currentOverlay == EMPTY_OVERLAY) {
            OverlayTable allocated = new OverlayTable();
            allocated.put(pos, octant, stateId19);
            ACTIVE_OVERLAY.setRelease(this, allocated);
        } else {
            currentOverlay.put(pos, octant, stateId19);
        }
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
     * Applies an array of packed 32-bit `dirtyOverlay` entries (`((pos12 + 1) << 19) | stateId19`)
     * from an inter-server or wire section delta directly into this section's `activeOverlay`.
     */
    public synchronized int applyPackedOverlayEntries(int[] packedOverlayEntries, long currentTick) {
        if (packedOverlayEntries == null || packedOverlayEntries.length == 0) {
            return 0;
        }
        int applied = 0;
        for (int packed : packedOverlayEntries) {
            if (packed == 0) {
                continue;
            }
            int pos12 = unpackPos12(packed) & MAX_POS_12;
            int stateId19 = unpackStateId19(packed);
            int x = pos12 & 15;
            int z = (pos12 >>> 4) & 15;
            int y = (pos12 >>> 8) & 15;
            setAndGetPreviousStateId(x, y, z, stateId19, currentTick, this.defaultBaseFrameDecoder);
            applied++;
        }
        return applied;
    }

    /**
     * 3-layer lock-free voxel state lookup using the configured default base frame decoder.
     */
    public int getStateId(int x, int y, int z) {
        OverlayTable active = this.activeOverlay;
        SectionSnapshotState snap = this.committedState;
        int pos = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        if (active == EMPTY_OVERLAY && snap.frozenOctantMask == 0L) {
            CompactBaseIndex idx = snap.compactIndex;
            if (idx != null && this.defaultBaseFrameDecoder == null) {
                return idx.lookupUnmasked(pos);
            }
            return decodeFromBaseFrame(pos, snap, this.defaultBaseFrameDecoder);
        }
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
            return decodeFromBaseFrame(pos, snap, baseFrameDecoder);
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

        return decodeFromBaseFrame(pos, snap, baseFrameDecoder);
    }

    /**
     * Evaluates {@code stateIdPredicate} against the distinct block state IDs present in this
     * section in $O(|P| + |\text{dirty}|)$ time (`1..20` palette entries) without materializing
     * 4,096 voxels when backed by {@link CompactBaseIndex}.
     */
    public boolean testMaybeHas(IntPredicate stateIdPredicate) {
        Objects.requireNonNull(stateIdPredicate, "stateIdPredicate");
        if (hasOnlyAir()) {
            return stateIdPredicate.test(0);
        }
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        OverlayTable frozen = snap.frozenOverlay;
        int activeDirty = active != null ? active.dirtyCount() : 0;
        int frozenDirty = frozen != null ? frozen.dirtyCount() : 0;

        CompactBaseIndex idx = snap.compactIndex;
        if (snap.isUniformBase()) {
            if (activeDirty == 0 && frozenDirty == 0) {
                return stateIdPredicate.test(snap.uniformStateId);
            }
            int overwritten = 0;
            if (activeDirty > 0 && frozenDirty == 0) {
                boolean[] matched = new boolean[1];
                int[] count = new int[1];
                active.forEachEntry((pos12, stateId19) -> {
                    count[0]++;
                    if (!matched[0] && stateIdPredicate.test(stateId19)) {
                        matched[0] = true;
                    }
                });
                if (matched[0]) {
                    return true;
                }
                overwritten = count[0];
            } else {
                int[] merged = exportMergedDirtyOverlayEntries();
                overwritten = merged.length;
                for (int entry : merged) {
                    if (stateIdPredicate.test(unpackStateId19(entry))) {
                        return true;
                    }
                }
            }
            return overwritten < BULK_4096_CAPACITY && stateIdPredicate.test(snap.uniformStateId);
        }

        if (idx != null) {
            if (activeDirty == 0 && frozenDirty == 0) {
                int[] pal = idx.paletteStateIds;
                for (int i = 0; i < pal.length; i++) {
                    if (stateIdPredicate.test(pal[i])) {
                        return true;
                    }
                }
                return false;
            }
            int[] remCounts = idx.paletteCounts.clone();
            if (frozenDirty == 0) {
                boolean[] matched = new boolean[1];
                active.forEachEntry((pos12, stateId19) -> {
                    if (!matched[0] && stateIdPredicate.test(stateId19)) {
                        matched[0] = true;
                    }
                    int baseOrd = idx.lookupOrdinal(pos12);
                    remCounts[baseOrd]--;
                });
                if (matched[0]) {
                    return true;
                }
            } else {
                int[] merged = exportMergedDirtyOverlayEntries();
                for (int entry : merged) {
                    int stateId19 = unpackStateId19(entry);
                    if (stateIdPredicate.test(stateId19)) {
                        return true;
                    }
                    int baseOrd = idx.lookupOrdinal(unpackPos12(entry));
                    remCounts[baseOrd]--;
                }
            }
            int[] pal = idx.paletteStateIds;
            for (int i = 0; i < pal.length; i++) {
                if (remCounts[i] > 0 && stateIdPredicate.test(pal[i])) {
                    return true;
                }
            }
            return false;
        }

        int[] voxels = materializeEphemeralStateIds(null, null);
        int lastTested = Integer.MIN_VALUE;
        for (int i = 0; i < BULK_4096_CAPACITY; i++) {
            int s = voxels[i];
            if (s != lastTested) {
                lastTested = s;
                if (stateIdPredicate.test(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reports exact {@code (stateId19, count)} pairs for all states present in this section in
     * $O(|P| + |\text{dirty}|)$ time when backed by {@link CompactBaseIndex}.
     */
    public void forEachStateCount(IntPairConsumer stateIdAndCountConsumer) {
        Objects.requireNonNull(stateIdAndCountConsumer, "stateIdAndCountConsumer");
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        OverlayTable frozen = snap.frozenOverlay;
        int activeDirty = active != null ? active.dirtyCount() : 0;
        int frozenDirty = frozen != null ? frozen.dirtyCount() : 0;

        if (snap.isUniformBase() && activeDirty == 0 && frozenDirty == 0) {
            stateIdAndCountConsumer.accept(snap.uniformStateId, BULK_4096_CAPACITY);
            return;
        }

        CompactBaseIndex idx = snap.compactIndex;
        if (idx != null && activeDirty == 0 && frozenDirty == 0) {
            int[] pal = idx.paletteStateIds;
            int[] counts = idx.paletteCounts;
            for (int i = 0; i < pal.length; i++) {
                if (counts[i] > 0) {
                    stateIdAndCountConsumer.accept(pal[i], counts[i]);
                }
            }
            return;
        }

        if (idx != null && (activeDirty + frozenDirty) <= HASH_64_MAX_LOAD) {
            int baseLen = idx.paletteStateIds.length;
            int maxExtra = activeDirty + frozenDirty;
            int[] stateIds = Arrays.copyOf(idx.paletteStateIds, baseLen + maxExtra);
            int[] counts = Arrays.copyOf(idx.paletteCounts, baseLen + maxExtra);
            int[] lenBox = {baseLen};
            OverlayEntryConsumer applyDelta = (pos12, newStateId) -> {
                int oldOrd = idx.lookupOrdinal(pos12);
                counts[oldOrd]--;
                int curLen = lenBox[0];
                for (int i = 0; i < curLen; i++) {
                    if (stateIds[i] == newStateId) {
                        counts[i]++;
                        return;
                    }
                }
                stateIds[curLen] = newStateId;
                counts[curLen] = 1;
                lenBox[0] = curLen + 1;
            };
            if (frozenDirty == 0) {
                active.forEachEntry(applyDelta);
            } else {
                for (int entry : exportMergedDirtyOverlayEntries()) {
                    applyDelta.accept(unpackPos12(entry), unpackStateId19(entry));
                }
            }
            int finalLen = lenBox[0];
            for (int i = 0; i < finalLen; i++) {
                if (counts[i] > 0) {
                    stateIdAndCountConsumer.accept(stateIds[i], counts[i]);
                }
            }
            return;
        }

        int[] voxels = materializeEphemeralStateIds(null, null);
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < BULK_4096_CAPACITY; i++) {
            map.merge(voxels[i], 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            stateIdAndCountConsumer.accept(e.getKey(), e.getValue());
        }
    }

    /**
     * Visits only minority voxels from {@code baseFrame} ({@code != uniformStateId}) followed by
     * {@code frozenOverlay} and {@code activeOverlay} entries in $O(|\text{minority}| + |\text{dirty}|)$
     * time when backed by {@link CompactBaseIndex}, avoiding 4,096-voxel scans during
     * {@code PalettedContainer#copy()}.
     */
    public boolean forEachNonMajorityAndOverlayVoxel(IntPairConsumer pos12AndStateIdConsumer) {
        Objects.requireNonNull(pos12AndStateIdConsumer, "pos12AndStateIdConsumer");
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        byte[] base = snap.baseFrame;
        CompactBaseIndex idx = snap.compactIndex;
        if (base != null && base.length > 0 && idx == null) {
            return false;
        }
        if (idx != null) {
            idx.forEachMinorityVoxel(pos12AndStateIdConsumer);
        }
        OverlayTable frozen = snap.frozenOverlay;
        if (frozen != null && frozen.dirtyCount() > 0) {
            frozen.forEachEntry(pos12AndStateIdConsumer::accept);
        }
        if (active != null && active.dirtyCount() > 0) {
            active.forEachEntry(pos12AndStateIdConsumer::accept);
        }
        this.ephemeralCopyCount.incrementAndGet();
        return true;
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
        int[] out = materializeStateIdsInternal(destinationOrNull, baseFrameDecoder);
        this.ephemeralCopyCount.incrementAndGet();
        return out;
    }

    private int[] materializeStateIdsInternal(int[] destinationOrNull, IntUnaryOperator baseFrameDecoder) {
        int[] out = (destinationOrNull != null && destinationOrNull.length >= BULK_4096_CAPACITY)
                ? destinationOrNull
                : THREAD_LOCAL_EPHEMERAL_VOXELS.get();
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);

        byte[] base = snap.baseFrame;
        int uniform = snap.uniformStateId;
        IntUnaryOperator decoder = baseFrameDecoder != null ? baseFrameDecoder : this.defaultBaseFrameDecoder;

        if (snap.compactIndex != null && baseFrameDecoder == null) {
            snap.compactIndex.scatterInto(out);
        } else if (snap.isUniformBase()) {
            Arrays.fill(out, 0, BULK_4096_CAPACITY, uniform);
        } else if (decoder != null) {
            for (int pos = 0; pos < BULK_4096_CAPACITY; pos++) {
                out[pos] = decoder.applyAsInt(pos);
            }
        } else if (base != null && base.length >= BULK_4096_CAPACITY) {
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

        return out;
    }

    /**
     * Computes the exact Mojang {@code PalettedContainer<BlockState>#getSerializedSize()} byte count
     * directly from {@link CompactBaseIndex} and active overlays without calling {@code copy()} or
     * caching {@code byte[] wireSlice} buffers.
     */
    public int computeVanillaSerializedSize(int globalPaletteBits) {
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        OverlayTable frozen = snap.frozenOverlay;
        int activeDirty = active != null ? active.dirtyCount() : 0;
        int frozenDirty = frozen != null ? frozen.dirtyCount() : 0;

        if (snap.isUniformBase() && activeDirty == 0 && frozenDirty == 0) {
            return 1 + varIntByteSize(snap.uniformStateId);
        }

        CompactBaseIndex idx = snap.compactIndex;
        if (idx != null && activeDirty == 0 && frozenDirty == 0) {
            byte[] prebuilt = idx.cleanVanillaWireSlice;
            if (prebuilt != null && idx.cleanVanillaWireGlobalBits == globalPaletteBits) {
                return prebuilt.length;
            }
            int[] pal = idx.paletteStateIds;
            int[] counts = idx.paletteCounts;
            int distinctCount = 0;
            int singleStateId = snap.uniformStateId;
            int paletteVarIntBytes = 0;
            for (int i = 0; i < pal.length; i++) {
                if (counts[i] > 0) {
                    distinctCount++;
                    singleStateId = pal[i];
                    paletteVarIntBytes += varIntByteSize(pal[i]);
                }
            }
            return computeVanillaSizeFromPaletteStats(distinctCount, singleStateId, paletteVarIntBytes, globalPaletteBits);
        }

        if (idx != null && (activeDirty + frozenDirty) <= HASH_64_MAX_LOAD) {
            int baseLen = idx.paletteStateIds.length;
            int maxExtra = activeDirty + frozenDirty;
            int[] stateIds = Arrays.copyOf(idx.paletteStateIds, baseLen + maxExtra);
            int[] counts = Arrays.copyOf(idx.paletteCounts, baseLen + maxExtra);
            int[] lenBox = {baseLen};
            OverlayEntryConsumer applyDelta = (pos12, newStateId) -> {
                int oldOrd = idx.lookupOrdinal(pos12);
                counts[oldOrd]--;
                int curLen = lenBox[0];
                for (int i = 0; i < curLen; i++) {
                    if (stateIds[i] == newStateId) {
                        counts[i]++;
                        return;
                    }
                }
                stateIds[curLen] = newStateId;
                counts[curLen] = 1;
                lenBox[0] = curLen + 1;
            };
            if (frozenDirty == 0) {
                active.forEachEntry(applyDelta);
            } else {
                for (int entry : exportMergedDirtyOverlayEntries()) {
                    applyDelta.accept(unpackPos12(entry), unpackStateId19(entry));
                }
            }
            int finalLen = lenBox[0];
            int distinctCount = 0;
            int singleStateId = snap.uniformStateId;
            int paletteVarIntBytes = 0;
            for (int i = 0; i < finalLen; i++) {
                if (counts[i] > 0) {
                    distinctCount++;
                    singleStateId = stateIds[i];
                    paletteVarIntBytes += varIntByteSize(stateIds[i]);
                }
            }
            return computeVanillaSizeFromPaletteStats(distinctCount, singleStateId, paletteVarIntBytes, globalPaletteBits);
        }

        int[] voxels = materializeStateIdsInternal(null, null);
        CompactEncodeScratch scratch = THREAD_ENCODE_SCRATCH.get();
        int epoch = ++scratch.currentEpoch;
        if (epoch <= 0) {
            Arrays.fill(scratch.hashSlotEpoch, 0);
            epoch = 1;
            scratch.currentEpoch = 1;
        }
        int[] distinctStates = scratch.distinctStates;
        int[] hashSlotToDistinctIdx = scratch.hashSlotToDistinctIdx;
        int[] hashSlotEpoch = scratch.hashSlotEpoch;
        int distinctCount = 0;
        int paletteVarIntBytes = 0;
        for (int pos = 0; pos < BULK_4096_CAPACITY; pos++) {
            int stateId = voxels[pos];
            int slot = ((stateId * FIBONACCI_HASH_MULTIPLIER) >>> 19) & 8191;
            while (hashSlotEpoch[slot] == epoch && distinctStates[hashSlotToDistinctIdx[slot]] != stateId) {
                slot = (slot + 1) & 8191;
            }
            if (hashSlotEpoch[slot] != epoch) {
                int dIdx = distinctCount++;
                hashSlotEpoch[slot] = epoch;
                hashSlotToDistinctIdx[slot] = dIdx;
                distinctStates[dIdx] = stateId;
                paletteVarIntBytes += varIntByteSize(stateId);
            }
        }
        int singleStateId = distinctCount > 0 ? distinctStates[0] : snap.uniformStateId;
        return computeVanillaSizeFromPaletteStats(distinctCount, singleStateId, paletteVarIntBytes, globalPaletteBits);
    }

    private static int computeVanillaSizeFromPaletteStats(
            int distinctCount,
            int singleStateId,
            int paletteVarIntBytes,
            int globalPaletteBits
    ) {
        if (distinctCount <= 1) {
            return 1 + varIntByteSize(Math.max(0, singleStateId));
        }
        if (distinctCount <= 256) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinctCount - 1));
            int valuesPerLong = 64 / bits;
            int wordCount = (BULK_4096_CAPACITY + valuesPerLong - 1) / valuesPerLong;
            return 1 + varIntByteSize(distinctCount) + paletteVarIntBytes + (wordCount * 8);
        }
        int bits = Math.max(9, globalPaletteBits);
        int valuesPerLong = 64 / bits;
        int wordCount = (BULK_4096_CAPACITY + valuesPerLong - 1) / valuesPerLong;
        return 1 + (wordCount * 8);
    }

    /**
     * Directly writes a Mojang {@code PalettedContainer<BlockState>} wire payload to {@code sink}
     * from {@link CompactBaseIndex} and active overlays without calling {@code copy()} or
     * allocating {@code byte[] wireSlice} buffers.
     */
    public void writeVanillaPalettedContainer(int globalPaletteBits, VanillaWireSink sink) {
        Objects.requireNonNull(sink, "sink");
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        OverlayTable frozen = snap.frozenOverlay;
        int activeDirty = active != null ? active.dirtyCount() : 0;
        int frozenDirty = frozen != null ? frozen.dirtyCount() : 0;

        if (snap.isUniformBase() && activeDirty == 0 && frozenDirty == 0) {
            sink.writeByte(0);
            sink.writeVarInt(Math.max(0, snap.uniformStateId));
            return;
        }

        CompactBaseIndex idx = snap.compactIndex;
        if (idx != null && activeDirty == 0 && frozenDirty == 0) {
            int[] pal = idx.paletteStateIds;
            int distinctCount = pal.length;
            if (distinctCount <= 1) {
                sink.writeByte(0);
                sink.writeVarInt(Math.max(0, distinctCount == 1 ? pal[0] : snap.uniformStateId));
                return;
            }
            if (distinctCount <= 256) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinctCount - 1));
                sink.writeByte(bits);
                sink.writeVarInt(distinctCount);
                for (int i = 0; i < distinctCount; i++) {
                    sink.writeVarInt(pal[i]);
                }
                if (bits == 4) {
                    idx.writeClean4BitWords(sink);
                    return;
                }
                int[] ordinals4096 = THREAD_LOCAL_EPHEMERAL_VOXELS.get();
                idx.scatterOrdinalsInto(ordinals4096);
                writePackedWordsFromArray(ordinals4096, bits, sink);
                return;
            }
        }

        int[] voxels = materializeStateIdsInternal(null, null);
        CompactEncodeScratch scratch = THREAD_ENCODE_SCRATCH.get();
        int epoch = ++scratch.currentEpoch;
        if (epoch <= 0) {
            Arrays.fill(scratch.hashSlotEpoch, 0);
            epoch = 1;
            scratch.currentEpoch = 1;
        }
        int[] distinctStates = scratch.distinctStates;
        int[] voxelOrdinals = scratch.nextPos;
        int[] hashSlotToDistinctIdx = scratch.hashSlotToDistinctIdx;
        int[] hashSlotEpoch = scratch.hashSlotEpoch;
        int distinctCount = 0;

        for (int pos = 0; pos < BULK_4096_CAPACITY; pos++) {
            int stateId = voxels[pos];
            int slot = ((stateId * FIBONACCI_HASH_MULTIPLIER) >>> 19) & 8191;
            while (hashSlotEpoch[slot] == epoch && distinctStates[hashSlotToDistinctIdx[slot]] != stateId) {
                slot = (slot + 1) & 8191;
            }
            int dIdx;
            if (hashSlotEpoch[slot] != epoch) {
                dIdx = distinctCount++;
                hashSlotEpoch[slot] = epoch;
                hashSlotToDistinctIdx[slot] = dIdx;
                distinctStates[dIdx] = stateId;
            } else {
                dIdx = hashSlotToDistinctIdx[slot];
            }
            voxelOrdinals[pos] = dIdx;
        }

        if (distinctCount <= 1) {
            sink.writeByte(0);
            sink.writeVarInt(Math.max(0, distinctCount == 1 ? distinctStates[0] : snap.uniformStateId));
            return;
        }
        if (distinctCount <= 256) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinctCount - 1));
            sink.writeByte(bits);
            sink.writeVarInt(distinctCount);
            for (int i = 0; i < distinctCount; i++) {
                sink.writeVarInt(distinctStates[i]);
            }
            writePackedWordsFromArray(voxelOrdinals, bits, sink);
            return;
        }
        int bits = Math.max(9, globalPaletteBits);
        sink.writeByte(bits);
        writePackedWordsFromArray(voxels, bits, sink);
    }

    private static void writePackedWordsFromArray(int[] values4096, int bits, VanillaWireSink sink) {
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int pos = 0;
        while (pos < BULK_4096_CAPACITY) {
            long word = 0L;
            int shift = 0;
            int limit = Math.min(BULK_4096_CAPACITY, pos + valuesPerLong);
            while (pos < limit) {
                word |= ((long) values4096[pos++] & mask) << shift;
                shift += bits;
            }
            sink.writeLong(word);
        }
    }

    /**
     * Returns the pre-built clean Mojang {@code PalettedContainer<BlockState>} wire slice when this
     * section has zero uncompacted overlay entries ({@code dirtyCount == 0 && frozenOverlay == null}),
     * or {@code null} when the section currently holds uncompacted mutations.
     */
    public byte[] cleanVanillaWireSlice(int globalPaletteBits) {
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        if (active != null && active.dirtyCount() != 0) {
            return null;
        }
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (snap.frozenOverlay != null && snap.frozenOverlay.dirtyCount() != 0) {
            return null;
        }
        if (snap.compactIndex != null) {
            return snap.compactIndex.getOrBuildCleanVanillaWireSlice(globalPaletteBits);
        }
        if (snap.isUniformBase()) {
            return snap.getOrBuildUniformWireSlice();
        }
        return null;
    }

    /**
     * Returns an already pre-built clean vanilla wire slice if present on the current committed
     * state (`O(1)`, zero allocation), or {@code null} if dirty or not yet materialized.
     */
    public byte[] peekPrebuiltCleanVanillaWireSlice(int globalPaletteBits) {
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        if (active != null && active.dirtyCount() != 0) {
            return null;
        }
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (snap.frozenOverlay != null && snap.frozenOverlay.dirtyCount() != 0) {
            return null;
        }
        if (snap.compactIndex != null) {
            byte[] cached = snap.compactIndex.cleanVanillaWireSlice;
            if (cached != null && snap.compactIndex.cleanVanillaWireGlobalBits == globalPaletteBits) {
                return cached;
            }
            return null;
        }
        return snap.cleanUniformWireSlice;
    }

    /**
     * Returns {@code true} if a pre-built clean vanilla wire slice is already cached on the current
     * committed state without allocating.
     */
    public boolean hasPrebuiltCleanWireSlice() {
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (snap.compactIndex != null) {
            return snap.compactIndex.cleanVanillaWireSlice != null;
        }
        return snap.cleanUniformWireSlice != null;
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
            SectionSnapshotState snap,
            IntUnaryOperator baseFrameDecoder) {
        if (snap.compactIndex != null && baseFrameDecoder == null) {
            return snap.compactIndex.lookup(pos12);
        }
        byte[] baseFrame = snap.baseFrame;
        int uniformStateId = snap.uniformStateId;
        if (snap.isUniformBase()) {
            return uniformStateId;
        }
        IntUnaryOperator decoder = baseFrameDecoder != null ? baseFrameDecoder : this.defaultBaseFrameDecoder;
        if (decoder != null) {
            return decoder.applyAsInt(pos12 & MAX_POS_12);
        }
        if (snap.compactIndex != null) {
            return snap.compactIndex.lookup(pos12);
        }
        if (baseFrame != null && baseFrame.length >= BULK_4096_CAPACITY) {
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

            SectionSnapshotState nextState = new SectionSnapshotState(
                    current.baseFrame,
                    current.uniformStateId,
                    current.compactIndex,
                    frozenToInstall,
                    frozenToInstall.octantMask(),
                    nextEpoch);

            if (COMMITTED_STATE.compareAndSet(this, current, nextState)) {
                ACTIVE_OVERLAY.setRelease(this, EMPTY_OVERLAY);
                return new CompactionSnapshot(
                        nextState.resolveBaseFrameBytes(),
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
        CompactBaseIndex newIndex = CompactBaseIndex.tryParse(newBaseFrame);
        return completeCompactionWithIndex(expectedEpoch, newBaseFrame, newUniformStateId, newIndex);
    }

    private boolean completeCompactionWithIndex(
            long expectedEpoch,
            byte[] newBaseFrame,
            int newUniformStateId,
            CompactBaseIndex newIndex
    ) {
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
                    newIndex,
                    null,
                    0L,
                    expectedEpoch + 1L);
            if (COMMITTED_STATE.compareAndSet(this, current, next)) {
                return true;
            }
        }
    }

    /**
     * Folds the current {@code frozenOverlay} (freezing {@code activeOverlay} first if needed)
     * directly into {@link CompactBaseIndex} on a background worker thread and pre-builds the
     * clean network wire slice and compact {@code .lca} {@code baseFrame} before atomically
     * committing via {@link #COMMITTED_STATE}.
     */
    public boolean compactFrozenOverlayOffThread(int globalPaletteBits) {
        SectionSnapshotState current = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if ((current.chunkEpoch & UNLOADED_BIT) != 0L) {
            return false;
        }
        CompactionSnapshot snap;
        if (current.frozenOverlay == null || current.frozenOverlay.dirtyCount() == 0) {
            OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
            if (active == null || active.dirtyCount() == 0) {
                return false;
            }
            try {
                snap = beginCompactionSnapshot();
            } catch (IllegalStateException unloadedEx) {
                return false;
            }
        } else {
            snap = new CompactionSnapshot(
                    current.baseFrame != null ? current.baseFrame : EMPTY_BYTES,
                    current.uniformStateId,
                    current.frozenOverlay,
                    current.chunkEpoch
            );
        }
        return compactFrozenOverlayOffThread(snap, globalPaletteBits);
    }

    /**
     * Folds the supplied {@link CompactionSnapshot}'s {@code frozenOverlay} directly into
     * {@link CompactBaseIndex} on a background worker thread and pre-builds the clean network
     * wire slice and compact {@code .lca} {@code baseFrame} before atomically committing.
     */
    public boolean compactFrozenOverlayOffThread(CompactionSnapshot snap, int globalPaletteBits) {
        if (snap == null || (snap.epoch() & UNLOADED_BIT) != 0L) {
            return false;
        }
        OverlayTable frozen = snap.frozenOverlay();
        if (frozen == null || frozen.dirtyCount() == 0) {
            return false;
        }
        SectionSnapshotState current = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if ((current.chunkEpoch & UNLOADED_BIT) != 0L || current.chunkEpoch != snap.epoch()) {
            return false;
        }

        int[] voxels = THREAD_LOCAL_EPHEMERAL_VOXELS.get();
        if (current.compactIndex != null) {
            current.compactIndex.scatterInto(voxels);
        } else if (current.baseFrame != null && current.baseFrame.length >= BULK_4096_CAPACITY) {
            for (int i = 0; i < BULK_4096_CAPACITY; i++) {
                voxels[i] = current.baseFrame[i] & 0xFF;
            }
        } else if (this.defaultBaseFrameDecoder != null && !current.isUniformBase()) {
            for (int i = 0; i < BULK_4096_CAPACITY; i++) {
                voxels[i] = this.defaultBaseFrameDecoder.applyAsInt(i);
            }
        } else {
            Arrays.fill(voxels, 0, BULK_4096_CAPACITY, current.uniformStateId);
        }

        frozen.forEachEntry((pos12, stateId) -> voxels[pos12 & MAX_POS_12] = stateId);

        int skyConst4 = current.compactIndex != null ? ((current.compactIndex.packedLightByte >>> 4) & 0xF) : 15;
        int blkConst4 = current.compactIndex != null ? (current.compactIndex.packedLightByte & 0xF) : 0;
        CompactBaseIndex newIndex = CompactBaseIndex.fromVoxelsSinglePass(voxels, skyConst4, blkConst4);
        if (newIndex == null) {
            int uniformId = Math.max(0, voxels[0]);
            return completeCompactionWithIndex(snap.epoch(), EMPTY_BYTES, uniformId, null);
        }

        newIndex.getOrBuildCleanVanillaWireSlice(globalPaletteBits);
        byte[] newBaseFrame = newIndex.getOrEncodeBaseFrame();
        return completeCompactionWithIndex(snap.epoch(), newBaseFrame, newIndex.majorityStateId, newIndex);
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
                    current.compactIndex,
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
        return ((SectionSnapshotState) COMMITTED_STATE.getAcquire(this)).resolveBaseFrameBytes();
    }

    /**
     * Returns {@code true} if the base snapshot is uniform (single state ID across all 4,096 voxels)
     * without materializing a lazy {@code baseFrame}.
     */
    public boolean isBaseUniform() {
        return ((SectionSnapshotState) COMMITTED_STATE.getAcquire(this)).isUniformBase();
    }

    /**
     * Returns {@code true} if both the base snapshot is uniform and no overlay mutations exist,
     * without materializing a lazy {@code baseFrame}.
     */
    public boolean isCleanUniform() {
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        if (active != null && active.dirtyCount() != 0) {
            return false;
        }
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (snap.frozenOverlay != null && snap.frozenOverlay.dirtyCount() != 0) {
            return false;
        }
        return snap.isUniformBase();
    }

    /**
     * Returns {@code true} if a serialized {@code byte[] baseFrame} has been materialized in RAM.
     */
    public boolean hasMaterializedBaseFrame() {
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (snap.baseFrame != null) {
            return true;
        }
        return snap.compactIndex != null && snap.compactIndex.lazyBaseFrame != null;
    }

    /**
     * Estimates the in-memory byte footprint of the base representation without forcing lazy
     * {@code byte[] baseFrame} materialization.
     */
    public int estimatedBaseFootprintBytes() {
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (snap.compactIndex != null) {
            return snap.compactIndex.estimatedFootprintBytes();
        }
        return snap.baseFrame != null ? snap.baseFrame.length : 0;
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

    /**
     * Rank-indexed 64-bit word directory (`POPCNT` bit-rank index) over a compact `.lca`
     * `baseFrame`, providing branch-minimal $O(1)$ random voxel reads (`~4–10` CPU instructions)
     * and $O(|P|)$ palette count queries with zero `int[4096]` decompression buffers.
     */
    private static final class CompactBaseIndex {
        final int majorityStateId;
        final int[] paletteStateIds;
        final int[] paletteCounts;
        final long nonUniformWordMask64;
        final long[] minorityBitmasks;
        final short[] wordRankOffsets;
        final byte[] minorityOrdinalsByte;
        final short[] minorityOrdinalsShort;
        final boolean nibblePackedOrdinals;
        final byte packedLightByte;
        volatile byte[] lazyBaseFrame;
        volatile byte[] cleanVanillaWireSlice;
        volatile int cleanVanillaWireGlobalBits;

        private CompactBaseIndex(
                int majorityStateId,
                int[] paletteStateIds,
                int[] paletteCounts,
                long nonUniformWordMask64,
                long[] minorityBitmasks,
                short[] wordRankOffsets,
                byte[] minorityOrdinalsByte,
                short[] minorityOrdinalsShort,
                byte packedLightByte,
                byte[] lazyBaseFrame
        ) {
            this.majorityStateId = majorityStateId;
            this.paletteStateIds = paletteStateIds;
            this.paletteCounts = paletteCounts;
            this.nonUniformWordMask64 = nonUniformWordMask64;
            this.minorityBitmasks = minorityBitmasks;
            this.wordRankOffsets = wordRankOffsets;
            this.minorityOrdinalsByte = minorityOrdinalsByte;
            this.minorityOrdinalsShort = minorityOrdinalsShort;
            this.nibblePackedOrdinals = paletteStateIds.length <= 16 && minorityOrdinalsByte != null;
            this.packedLightByte = packedLightByte;
            this.lazyBaseFrame = lazyBaseFrame;
        }

        int readOrdinalAtRank(int rank) {
            if (this.nibblePackedOrdinals) {
                int b = this.minorityOrdinalsByte[rank >>> 1] & 0xFF;
                return (b >>> ((rank & 1) << 2)) & 0xF;
            }
            if (this.minorityOrdinalsByte != null) {
                return this.minorityOrdinalsByte[rank] & 0xFF;
            }
            return this.minorityOrdinalsShort[rank] & 0xFFFF;
        }

        static CompactBaseIndex fromVoxelsSinglePass(int[] voxels4096, int skyConst4, int blkConst4) {
            if (voxels4096 == null || voxels4096.length < BULK_4096_CAPACITY) {
                return null;
            }
            CompactEncodeScratch scratch = THREAD_ENCODE_SCRATCH.get();
            int epoch = ++scratch.currentEpoch;
            if (epoch <= 0) {
                Arrays.fill(scratch.hashSlotEpoch, 0);
                epoch = 1;
                scratch.currentEpoch = 1;
            }
            int[] distinctStates = scratch.distinctStates;
            int[] stateCounts = scratch.stateCounts;
            int[] voxelDistinctIdx = scratch.nextPos;
            int[] hashSlotToDistinctIdx = scratch.hashSlotToDistinctIdx;
            int[] hashSlotEpoch = scratch.hashSlotEpoch;
            int distinctCount = 0;

            for (int pos = 0; pos < BULK_4096_CAPACITY; pos++) {
                int stateId = voxels4096[pos];
                int slot = ((stateId * FIBONACCI_HASH_MULTIPLIER) >>> 19) & 8191;
                while (hashSlotEpoch[slot] == epoch
                        && distinctStates[hashSlotToDistinctIdx[slot]] != stateId) {
                    slot = (slot + 1) & 8191;
                }
                int idx;
                if (hashSlotEpoch[slot] != epoch) {
                    idx = distinctCount++;
                    hashSlotEpoch[slot] = epoch;
                    hashSlotToDistinctIdx[slot] = idx;
                    distinctStates[idx] = stateId;
                    stateCounts[idx] = 1;
                } else {
                    idx = hashSlotToDistinctIdx[slot];
                    stateCounts[idx]++;
                }
                voxelDistinctIdx[pos] = idx;
            }

            if (distinctCount <= 1) {
                return null;
            }

            int majorityIdx = 0;
            int majorityCount = -1;
            for (int i = 0; i < distinctCount; i++) {
                if (stateCounts[i] > majorityCount) {
                    majorityCount = stateCounts[i];
                    majorityIdx = i;
                }
            }
            int majorityStateId = distinctStates[majorityIdx];
            int minorityCount = distinctCount - 1;
            int[] paletteStateIds = new int[1 + minorityCount];
            int[] paletteCounts = new int[1 + minorityCount];
            paletteStateIds[0] = majorityStateId;
            paletteCounts[0] = majorityCount;

            int[] distinctToOrd = scratch.headPos;
            distinctToOrd[majorityIdx] = 0;
            int ordCounter = 1;
            for (int i = 0; i < distinctCount; i++) {
                if (i == majorityIdx) {
                    continue;
                }
                paletteStateIds[ordCounter] = distinctStates[i];
                paletteCounts[ordCounter] = stateCounts[i];
                distinctToOrd[i] = ordCounter++;
            }

            int totalMinority = BULK_4096_CAPACITY - majorityCount;
            boolean useByteOrdinals = paletteStateIds.length <= 256;
            boolean nibblePacked = paletteStateIds.length <= 16;
            byte[] ordinalsByte = useByteOrdinals
                    ? new byte[nibblePacked ? ((totalMinority + 1) >>> 1) : totalMinority]
                    : null;
            short[] ordinalsShort = useByteOrdinals ? null : new short[totalMinority];

            long[] tempWordMasks = THREAD_LOCAL_WORD_MASKS_64.get();
            long wordMask64 = 0L;
            int rank = 0;
            for (int w = 0; w < 64; w++) {
                int basePos = w << 6;
                long voxMask = 0L;
                for (int b = 0; b < 64; b++) {
                    int ord = distinctToOrd[voxelDistinctIdx[basePos | b]];
                    if (ord != 0) {
                        voxMask |= (1L << b);
                        if (nibblePacked) {
                            int byteIdx = rank >>> 1;
                            int shift = (rank & 1) << 2;
                            ordinalsByte[byteIdx] = (byte) ((ordinalsByte[byteIdx] & ~(0xF << shift))
                                    | ((ord & 0xF) << shift));
                            rank++;
                        } else if (ordinalsByte != null) {
                            ordinalsByte[rank++] = (byte) ord;
                        } else {
                            ordinalsShort[rank++] = (short) ord;
                        }
                    }
                }
                tempWordMasks[w] = voxMask;
                if (voxMask != 0L) {
                    wordMask64 |= (1L << w);
                }
            }

            int nonUniformWords = Long.bitCount(wordMask64);
            long[] minorityBitmasks = new long[nonUniformWords];
            short[] wordRankOffsets = new short[nonUniformWords];
            int runningRank = 0;
            long remWords = wordMask64;
            int slotIdx = 0;
            while (remWords != 0L) {
                int w = Long.numberOfTrailingZeros(remWords);
                remWords &= (remWords - 1L);
                long voxMask = tempWordMasks[w];
                minorityBitmasks[slotIdx] = voxMask;
                wordRankOffsets[slotIdx] = (short) runningRank;
                runningRank += Long.bitCount(voxMask);
                slotIdx++;
            }

            byte packedLight = (byte) (((skyConst4 & 0xF) << 4) | (blkConst4 & 0xF));
            return new CompactBaseIndex(
                    majorityStateId,
                    paletteStateIds,
                    paletteCounts,
                    wordMask64,
                    minorityBitmasks,
                    wordRankOffsets,
                    ordinalsByte,
                    ordinalsShort,
                    packedLight,
                    null
            );
        }

        static CompactBaseIndex tryParse(byte[] baseFrame) {
            if (baseFrame == null || baseFrame.length == 0 || baseFrame.length == BULK_4096_CAPACITY) {
                return null;
            }
            int len = baseFrame.length;
            int cursor = 0;
            int majorityStateId = 0;
            int shift = 0;
            while (true) {
                if (cursor >= len || shift > 28) {
                    return null;
                }
                int b = baseFrame[cursor++] & 0xFF;
                majorityStateId |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            if (majorityStateId < 0 || majorityStateId > MAX_STATE_ID_19 || cursor >= len) {
                return null;
            }
            byte packedLight = baseFrame[cursor++];
            if (cursor + 1 < len && baseFrame[cursor] == 0x00) {
                return tryParseV2Containers(baseFrame, cursor + 1, len, majorityStateId, packedLight);
            }
            int minorityCount = 0;
            shift = 0;
            while (true) {
                if (cursor >= len || shift > 28) {
                    return null;
                }
                int b = baseFrame[cursor++] & 0xFF;
                minorityCount |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            if (minorityCount < 0 || minorityCount > BULK_4096_CAPACITY) {
                return null;
            }
            int bodyStartCursor = cursor;
            int[] paletteStateIds = new int[1 + minorityCount];
            int[] paletteCounts = new int[1 + minorityCount];
            paletteStateIds[0] = majorityStateId;

            long[] tempWordMasks = THREAD_LOCAL_WORD_MASKS_64.get();
            Arrays.fill(tempWordMasks, 0L);
            long wordMask64 = 0L;
            int totalMinority = 0;

            for (int m = 0; m < minorityCount; m++) {
                int stateId = 0;
                shift = 0;
                while (true) {
                    if (cursor >= len || shift > 28) {
                        return null;
                    }
                    int b = baseFrame[cursor++] & 0xFF;
                    stateId |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                int occurrences = 0;
                shift = 0;
                while (true) {
                    if (cursor >= len || shift > 28) {
                        return null;
                    }
                    int b = baseFrame[cursor++] & 0xFF;
                    occurrences |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                if (stateId < 0 || stateId > MAX_STATE_ID_19
                        || occurrences <= 0 || totalMinority + occurrences > BULK_4096_CAPACITY) {
                    return null;
                }
                paletteStateIds[1 + m] = stateId;
                paletteCounts[1 + m] = occurrences;
                totalMinority += occurrences;

                int pos = 0;
                for (int k = 0; k < occurrences; k++) {
                    int delta = 0;
                    shift = 0;
                    while (true) {
                        if (cursor >= len || shift > 28) {
                            return null;
                        }
                        int b = baseFrame[cursor++] & 0xFF;
                        delta |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    pos += delta;
                    if (pos < 0 || pos >= BULK_4096_CAPACITY) {
                        return null;
                    }
                    int w = pos >>> 6;
                    wordMask64 |= (1L << w);
                    tempWordMasks[w] |= (1L << (pos & 63));
                }
            }
            if (cursor != len) {
                return null;
            }

            int nonUniformWords = Long.bitCount(wordMask64);
            long[] minorityBitmasks = new long[nonUniformWords];
            short[] wordRankOffsets = new short[nonUniformWords];
            int runningRank = 0;
            long remWords = wordMask64;
            int slotIdx = 0;
            while (remWords != 0L) {
                int w = Long.numberOfTrailingZeros(remWords);
                remWords &= (remWords - 1L);
                long voxMask = tempWordMasks[w];
                minorityBitmasks[slotIdx] = voxMask;
                wordRankOffsets[slotIdx] = (short) runningRank;
                runningRank += Long.bitCount(voxMask);
                slotIdx++;
            }
            paletteCounts[0] = Math.max(0, BULK_4096_CAPACITY - runningRank);

            boolean useByteOrdinals = paletteStateIds.length <= 256;
            boolean nibblePacked = paletteStateIds.length <= 16;
            byte[] ordinalsByte = useByteOrdinals
                    ? new byte[nibblePacked ? ((runningRank + 1) >>> 1) : runningRank]
                    : null;
            short[] ordinalsShort = useByteOrdinals ? null : new short[runningRank];

            cursor = bodyStartCursor;
            for (int m = 0; m < minorityCount; m++) {
                int skipByte;
                do {
                    skipByte = baseFrame[cursor++] & 0xFF;
                } while ((skipByte & 0x80) != 0);
                int occurrences = paletteCounts[1 + m];
                do {
                    skipByte = baseFrame[cursor++] & 0xFF;
                } while ((skipByte & 0x80) != 0);
                int ord = m + 1;
                int pos = 0;
                for (int k = 0; k < occurrences; k++) {
                    int delta = 0;
                    shift = 0;
                    while (true) {
                        int b = baseFrame[cursor++] & 0xFF;
                        delta |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    pos += delta;
                    int w = pos >>> 6;
                    int wSlot = Long.bitCount(wordMask64 & ((1L << w) - 1L));
                    long voxMask = minorityBitmasks[wSlot];
                    int rank = (wordRankOffsets[wSlot] & 0xFFFF)
                            + Long.bitCount(voxMask & ((1L << (pos & 63)) - 1L));
                    if (nibblePacked) {
                        int byteIdx = rank >>> 1;
                        int nibShift = (rank & 1) << 2;
                        ordinalsByte[byteIdx] = (byte) ((ordinalsByte[byteIdx] & ~(0xF << nibShift))
                                | ((ord & 0xF) << nibShift));
                    } else if (ordinalsByte != null) {
                        ordinalsByte[rank] = (byte) ord;
                    } else {
                        ordinalsShort[rank] = (short) ord;
                    }
                }
            }

            return new CompactBaseIndex(
                    majorityStateId,
                    paletteStateIds,
                    paletteCounts,
                    wordMask64,
                    minorityBitmasks,
                    wordRankOffsets,
                    ordinalsByte,
                    ordinalsShort,
                    packedLight,
                    baseFrame
            );
        }

        private static CompactBaseIndex tryParseV2Containers(
                byte[] baseFrame,
                int cursor,
                int len,
                int majorityStateId,
                byte packedLight
        ) {
            int minorityCount = 0;
            int shift = 0;
            while (true) {
                if (cursor >= len || shift > 28) {
                    return null;
                }
                int b = baseFrame[cursor++] & 0xFF;
                minorityCount |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            if (minorityCount <= 0 || minorityCount > BULK_4096_CAPACITY - 1) {
                return null;
            }
            int paletteLen = 1 + minorityCount;
            int[] paletteStateIds = new int[paletteLen];
            int[] paletteCounts = new int[paletteLen];
            paletteStateIds[0] = majorityStateId;
            for (int m = 1; m <= minorityCount; m++) {
                int stateId = 0;
                shift = 0;
                while (true) {
                    if (cursor >= len || shift > 28) {
                        return null;
                    }
                    int b = baseFrame[cursor++] & 0xFF;
                    stateId |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                if (stateId < 0 || stateId > MAX_STATE_ID_19) {
                    return null;
                }
                paletteStateIds[m] = stateId;
            }
            int[] yzxOrd = THREAD_ENCODE_SCRATCH.get().nextPos;
            int endCursor = LcaSectionCodec.decodeSectionBlockContainersV2(
                    baseFrame, cursor, len, paletteLen, yzxOrd);
            if (endCursor != len) {
                return null;
            }

            long[] tempWordMasks = THREAD_LOCAL_WORD_MASKS_64.get();
            long wordMask64 = 0L;
            int totalMinority = 0;
            for (int w = 0; w < 64; w++) {
                int basePos = w << 6;
                long voxMask = 0L;
                for (int b = 0; b < 64; b++) {
                    int ord = yzxOrd[basePos | b];
                    paletteCounts[ord]++;
                    if (ord != 0) {
                        voxMask |= (1L << b);
                        totalMinority++;
                    }
                }
                tempWordMasks[w] = voxMask;
                if (voxMask != 0L) {
                    wordMask64 |= (1L << w);
                }
            }

            int nonUniformWords = Long.bitCount(wordMask64);
            long[] minorityBitmasks = new long[nonUniformWords];
            short[] wordRankOffsets = new short[nonUniformWords];
            boolean useByteOrdinals = paletteLen <= 256;
            boolean nibblePacked = paletteLen <= 16;
            byte[] ordinalsByte = useByteOrdinals
                    ? new byte[nibblePacked ? ((totalMinority + 1) >>> 1) : totalMinority]
                    : null;
            short[] ordinalsShort = useByteOrdinals ? null : new short[totalMinority];

            int runningRank = 0;
            long remWords = wordMask64;
            int slotIdx = 0;
            while (remWords != 0L) {
                int w = Long.numberOfTrailingZeros(remWords);
                remWords &= (remWords - 1L);
                long voxMask = tempWordMasks[w];
                minorityBitmasks[slotIdx] = voxMask;
                wordRankOffsets[slotIdx] = (short) runningRank;
                int basePos = w << 6;
                long bits = voxMask;
                while (bits != 0L) {
                    int b = Long.numberOfTrailingZeros(bits);
                    bits &= (bits - 1L);
                    int ord = yzxOrd[basePos | b];
                    if (nibblePacked) {
                        int byteIdx = runningRank >>> 1;
                        int nibShift = (runningRank & 1) << 2;
                        ordinalsByte[byteIdx] = (byte) ((ordinalsByte[byteIdx] & ~(0xF << nibShift))
                                | ((ord & 0xF) << nibShift));
                    } else if (ordinalsByte != null) {
                        ordinalsByte[runningRank] = (byte) ord;
                    } else {
                        ordinalsShort[runningRank] = (short) ord;
                    }
                    runningRank++;
                }
                slotIdx++;
            }

            return new CompactBaseIndex(
                    majorityStateId,
                    paletteStateIds,
                    paletteCounts,
                    wordMask64,
                    minorityBitmasks,
                    wordRankOffsets,
                    ordinalsByte,
                    ordinalsShort,
                    packedLight,
                    baseFrame
            );
        }

        byte[] getOrEncodeBaseFrame() {
            byte[] existing = this.lazyBaseFrame;
            if (existing != null) {
                return existing;
            }
            int minorityCount = this.paletteStateIds.length - 1;
            if (minorityCount <= 0) {
                this.lazyBaseFrame = EMPTY_BYTES;
                return EMPTY_BYTES;
            }
            CompactEncodeScratch scratch = THREAD_ENCODE_SCRATCH.get();
            int[] yzxOrd = scratch.nextPos;
            Arrays.fill(yzxOrd, 0, BULK_4096_CAPACITY, 0);

            long wordMask = this.nonUniformWordMask64;
            int slot = 0;
            while (wordMask != 0L) {
                int w = Long.numberOfTrailingZeros(wordMask);
                wordMask &= (wordMask - 1L);
                int basePos = w << 6;
                long voxMask = this.minorityBitmasks[slot];
                int rank = this.wordRankOffsets[slot] & 0xFFFF;
                slot++;
                while (voxMask != 0L) {
                    int b = Long.numberOfTrailingZeros(voxMask);
                    voxMask &= (voxMask - 1L);
                    yzxOrd[basePos | b] = readOrdinalAtRank(rank++);
                }
            }

            int maxBytes = 64 + (minorityCount * 6) + 16_384;
            if (scratch.outBuf.length < maxBytes) {
                scratch.outBuf = new byte[Math.max(maxBytes, scratch.outBuf.length << 1)];
            }
            byte[] outBuf = scratch.outBuf;
            int cursor = 0;
            cursor = writeVarIntToArray(outBuf, cursor, this.majorityStateId);
            outBuf[cursor++] = this.packedLightByte;
            outBuf[cursor++] = 0x00;
            cursor = writeVarIntToArray(outBuf, cursor, minorityCount);
            for (int ord = 1; ord <= minorityCount; ord++) {
                cursor = writeVarIntToArray(outBuf, cursor, this.paletteStateIds[ord]);
            }
            cursor = LcaSectionCodec.encodeSectionBlockContainersV2(
                    yzxOrd, this.paletteStateIds.length, outBuf, cursor);
            byte[] encoded = Arrays.copyOf(outBuf, cursor);
            this.lazyBaseFrame = encoded;
            return encoded;
        }

        byte[] getOrBuildCleanVanillaWireSlice(int globalPaletteBits) {
            byte[] cached = this.cleanVanillaWireSlice;
            if (cached != null && this.cleanVanillaWireGlobalBits == globalPaletteBits) {
                return cached;
            }
            int[] pal = this.paletteStateIds;
            int distinctCount = pal.length;
            if (distinctCount <= 1) {
                int singleId = Math.max(0, distinctCount == 1 ? pal[0] : this.majorityStateId);
                byte[] out = new byte[1 + varIntByteSize(singleId)];
                out[0] = 0;
                writeVarIntToArray(out, 1, singleId);
                this.cleanVanillaWireGlobalBits = globalPaletteBits;
                this.cleanVanillaWireSlice = out;
                return out;
            }
            int paletteVarIntBytes = 0;
            for (int i = 0; i < distinctCount; i++) {
                paletteVarIntBytes += varIntByteSize(pal[i]);
            }
            int totalBytes = computeVanillaSizeFromPaletteStats(
                    distinctCount, this.majorityStateId, paletteVarIntBytes, globalPaletteBits);
            byte[] out = new byte[totalBytes];
            int[] cursorBox = {0};
            VanillaWireSink arraySink = new VanillaWireSink() {
                @Override
                public void writeByte(int b) {
                    out[cursorBox[0]++] = (byte) b;
                }

                @Override
                public void writeVarInt(int v) {
                    cursorBox[0] = writeVarIntToArray(out, cursorBox[0], v);
                }

                @Override
                public void writeLong(long word) {
                    int c = cursorBox[0];
                    out[c] = (byte) (word >>> 56);
                    out[c + 1] = (byte) (word >>> 48);
                    out[c + 2] = (byte) (word >>> 40);
                    out[c + 3] = (byte) (word >>> 32);
                    out[c + 4] = (byte) (word >>> 24);
                    out[c + 5] = (byte) (word >>> 16);
                    out[c + 6] = (byte) (word >>> 8);
                    out[c + 7] = (byte) word;
                    cursorBox[0] = c + 8;
                }
            };
            if (distinctCount <= 256) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinctCount - 1));
                arraySink.writeByte(bits);
                arraySink.writeVarInt(distinctCount);
                for (int i = 0; i < distinctCount; i++) {
                    arraySink.writeVarInt(pal[i]);
                }
                if (bits == 4) {
                    writeClean4BitWords(arraySink);
                } else {
                    int[] ordinals4096 = THREAD_LOCAL_EPHEMERAL_VOXELS.get();
                    scatterOrdinalsInto(ordinals4096);
                    writePackedWordsFromArray(ordinals4096, bits, arraySink);
                }
            } else {
                int bits = Math.max(9, globalPaletteBits);
                arraySink.writeByte(bits);
                int[] voxels4096 = THREAD_LOCAL_EPHEMERAL_VOXELS.get();
                scatterInto(voxels4096);
                writePackedWordsFromArray(voxels4096, bits, arraySink);
            }
            this.cleanVanillaWireGlobalBits = globalPaletteBits;
            this.cleanVanillaWireSlice = out;
            return out;
        }

        int estimatedFootprintBytes() {
            int ordBytes = this.minorityOrdinalsByte != null
                    ? this.minorityOrdinalsByte.length
                    : (this.minorityOrdinalsShort != null ? this.minorityOrdinalsShort.length * 2 : 0);
            return 32
                    + (this.paletteStateIds.length * 8)
                    + (this.minorityBitmasks.length * 10)
                    + ordBytes;
        }

        void scatterOrdinalsInto(int[] out4096) {
            Arrays.fill(out4096, 0, BULK_4096_CAPACITY, 0);
            long wordMask = this.nonUniformWordMask64;
            int slot = 0;
            while (wordMask != 0L) {
                int w = Long.numberOfTrailingZeros(wordMask);
                wordMask &= (wordMask - 1L);
                int basePos = w << 6;
                long voxMask = this.minorityBitmasks[slot];
                int rank = this.wordRankOffsets[slot] & 0xFFFF;
                slot++;
                while (voxMask != 0L) {
                    int b = Long.numberOfTrailingZeros(voxMask);
                    voxMask &= (voxMask - 1L);
                    int ord = readOrdinalAtRank(rank++);
                    out4096[basePos | b] = ord;
                }
            }
        }

        void writeClean4BitWords(VanillaWireSink sink) {
            long wordMask = this.nonUniformWordMask64;
            int slot = 0;
            for (int w = 0; w < 64; w++) {
                if ((wordMask & (1L << w)) == 0L) {
                    sink.writeLong(0L);
                    sink.writeLong(0L);
                    sink.writeLong(0L);
                    sink.writeLong(0L);
                    continue;
                }
                long voxMask = this.minorityBitmasks[slot];
                int rank = this.wordRankOffsets[slot] & 0xFFFF;
                slot++;
                long w0 = 0L;
                long w1 = 0L;
                long w2 = 0L;
                long w3 = 0L;
                while (voxMask != 0L) {
                    int b = Long.numberOfTrailingZeros(voxMask);
                    voxMask &= (voxMask - 1L);
                    long ord = readOrdinalAtRank(rank++) & 0xFFFFL;
                    int shift = (b & 15) << 2;
                    switch (b >>> 4) {
                        case 0 -> w0 |= (ord << shift);
                        case 1 -> w1 |= (ord << shift);
                        case 2 -> w2 |= (ord << shift);
                        default -> w3 |= (ord << shift);
                    }
                }
                sink.writeLong(w0);
                sink.writeLong(w1);
                sink.writeLong(w2);
                sink.writeLong(w3);
            }
        }

        int lookupOrdinal(int pos12) {
            return lookupOrdinalUnmasked(pos12 & MAX_POS_12);
        }

        int lookupOrdinalUnmasked(int masked) {
            int w = masked >>> 6;
            long wBit = 1L << w;
            if ((this.nonUniformWordMask64 & wBit) == 0L) {
                return 0;
            }
            int slot = Long.bitCount(this.nonUniformWordMask64 & (wBit - 1L));
            long voxMask = this.minorityBitmasks[slot];
            long bBit = 1L << (masked & 63);
            if ((voxMask & bBit) == 0L) {
                return 0;
            }
            int rank = (this.wordRankOffsets[slot] & 0xFFFF) + Long.bitCount(voxMask & (bBit - 1L));
            return readOrdinalAtRank(rank);
        }

        int lookup(int pos12) {
            return this.paletteStateIds[lookupOrdinalUnmasked(pos12 & MAX_POS_12)];
        }

        int lookupUnmasked(int pos12) {
            return this.paletteStateIds[lookupOrdinalUnmasked(pos12)];
        }

        void scatterInto(int[] out4096) {
            Arrays.fill(out4096, 0, BULK_4096_CAPACITY, this.majorityStateId);
            long wordMask = this.nonUniformWordMask64;
            int slot = 0;
            while (wordMask != 0L) {
                int w = Long.numberOfTrailingZeros(wordMask);
                wordMask &= (wordMask - 1L);
                int basePos = w << 6;
                long voxMask = this.minorityBitmasks[slot];
                int rank = this.wordRankOffsets[slot] & 0xFFFF;
                slot++;
                while (voxMask != 0L) {
                    int b = Long.numberOfTrailingZeros(voxMask);
                    voxMask &= (voxMask - 1L);
                    int ord = readOrdinalAtRank(rank++);
                    out4096[basePos | b] = this.paletteStateIds[ord];
                }
            }
        }

        void forEachMinorityVoxel(IntPairConsumer consumer) {
            long wordMask = this.nonUniformWordMask64;
            int slot = 0;
            while (wordMask != 0L) {
                int w = Long.numberOfTrailingZeros(wordMask);
                wordMask &= (wordMask - 1L);
                int basePos = w << 6;
                long voxMask = this.minorityBitmasks[slot];
                int rank = this.wordRankOffsets[slot] & 0xFFFF;
                slot++;
                while (voxMask != 0L) {
                    int b = Long.numberOfTrailingZeros(voxMask);
                    voxMask &= (voxMask - 1L);
                    int ord = readOrdinalAtRank(rank++);
                    consumer.accept(basePos | b, this.paletteStateIds[ord]);
                }
            }
        }
    }

    private static final class SectionSnapshotState {
        final byte[] baseFrame;
        final int uniformStateId;
        final CompactBaseIndex compactIndex;
        final OverlayTable frozenOverlay;
        final long frozenOctantMask;
        final long chunkEpoch;
        volatile byte[] cleanUniformWireSlice;

        SectionSnapshotState(
                byte[] baseFrame,
                int uniformStateId,
                CompactBaseIndex compactIndex,
                OverlayTable frozenOverlay,
                long frozenOctantMask,
                long chunkEpoch) {
            this.baseFrame = baseFrame;
            this.uniformStateId = uniformStateId;
            this.compactIndex = compactIndex;
            this.frozenOverlay = frozenOverlay;
            this.frozenOctantMask = frozenOctantMask;
            this.chunkEpoch = chunkEpoch;
        }

        boolean isUniformBase() {
            return this.compactIndex == null && (this.baseFrame == null || this.baseFrame.length == 0);
        }

        byte[] resolveBaseFrameBytes() {
            if (this.baseFrame != null) {
                return this.baseFrame;
            }
            if (this.compactIndex != null) {
                return this.compactIndex.getOrEncodeBaseFrame();
            }
            return EMPTY_BYTES;
        }

        byte[] getOrBuildUniformWireSlice() {
            byte[] cached = this.cleanUniformWireSlice;
            if (cached != null) {
                return cached;
            }
            int id = Math.max(0, this.uniformStateId);
            byte[] slice = new byte[1 + varIntByteSize(id)];
            slice[0] = 0;
            writeVarIntToArray(slice, 1, id);
            this.cleanUniformWireSlice = slice;
            return slice;
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
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CompactionSnapshot that)) return false;
            return uniformStateId == that.uniformStateId
                    && epoch == that.epoch
                    && Objects.equals(frozenOverlay, that.frozenOverlay)
                    && Arrays.equals(baseFrame, that.baseFrame);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(uniformStateId, frozenOverlay, epoch);
            return 31 * result + Arrays.hashCode(baseFrame);
        }

        @Override
        public String toString() {
            return "CompactionSnapshot[baseFrame=" + Arrays.toString(baseFrame)
                    + ", uniformStateId=" + uniformStateId
                    + ", frozenOverlay=" + frozenOverlay
                    + ", epoch=" + epoch + "]";
        }
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

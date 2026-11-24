package io.github.dailystruggle.anvil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Lock-free L1-resident sparse dirty cell map and double-buffered compaction state machine for `.lca` sections.
 *
 * <p>Implements 32-bit packed voxel modifier entries ({@code entry32 == 0} empty sentinel with {@code (pos12 + 1) << 19}),
 * flat resizable power-of-two open-addressed sparse tables ({@code int[16]} -> {@code int[64]} -> {@code int[256]} ->
 * {@code int[1024]} -> {@code int[8192]} at {@code <= 75%} load factor with zero {@code CompactBaseIndex} hydration on write
 * and zero {@code 16 KiB} {@code int[4096]} slab inflation), 64-bit read fast-path filtering, authoritative {@code overlayB}
 * revert tombstones during compaction, release-ordered scalar counter publication, non-pinning ephemeral {@code Copy-on-Read}
 * snapshots, Paper/Folia {@code Moonrise} ticking-list delta callbacks, and {@code UNLOADED} bit-63 epoch fencing.
 *
 * @governedBy leafanvil-ADR-002, leafanvil-ADR-003, leafanvil-ADR-005, leafanvil-ADR-006, anvil-api-ADR-003, leafrtp-ADR-072, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
public final class LcaSectionBacking {

    public static final int STAGE_EMPTY = 0;
    public static final int STAGE_LINEAR_16 = 1;
    public static final int STAGE_HASH_64 = 2;
    public static final int STAGE_SPARSE_HASH_EXT = 3;
    @Deprecated
    public static final int STAGE_BULK_4096 = STAGE_SPARSE_HASH_EXT;

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

    public static final int DEFAULT_AIMD_ADDITIVE_INCREMENT = 16;
    public static final int MAX_AIMD_EDIT_SCORE = 1_048_576;

    private static final ThreadLocal<int[]> THREAD_LOCAL_EPHEMERAL_VOXELS =
            ThreadLocal.withInitial(() -> new int[BULK_4096_CAPACITY]);

    private static final OverlayTable EMPTY_OVERLAY = new OverlayTable();
    private static final ThreadLocal<long[]> THREAD_LOCAL_WORD_MASKS_64 =
            ThreadLocal.withInitial(() -> new long[64]);
    private static final ThreadLocal<SoftReference<int[]>> THREAD_DECODE_YZX_4096 =
            new ThreadLocal<>();
    private static final ThreadLocal<SoftReference<CompactEncodeScratch>> THREAD_ENCODE_SCRATCH =
            new ThreadLocal<>();

    private static int[] decodeYzx4096Scratch() {
        SoftReference<int[]> ref = THREAD_DECODE_YZX_4096.get();
        int[] buf = ref != null ? ref.get() : null;
        if (buf == null) {
            buf = new int[BULK_4096_CAPACITY];
            THREAD_DECODE_YZX_4096.set(new SoftReference<>(buf));
        }
        return buf;
    }

    private static CompactEncodeScratch encodeScratch() {
        SoftReference<CompactEncodeScratch> ref = THREAD_ENCODE_SCRATCH.get();
        CompactEncodeScratch scratch = ref != null ? ref.get() : null;
        if (scratch == null) {
            scratch = new CompactEncodeScratch();
            THREAD_ENCODE_SCRATCH.set(new SoftReference<>(scratch));
        }
        return scratch;
    }

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
    private static final VarHandle AIMD_EDIT_SCORE;

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
            AIMD_EDIT_SCORE = lookup.findVarHandle(
                    LcaSectionBacking.class, "aimdEditScore", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Listener invoked outside {@link LcaSectionBacking}'s intrinsic monitor whenever a voxel
     * mutation commits to {@code activeOverlay}, allowing {@code LcaInMemoryChunkCoordinator}
     * to admit or refresh this 3D slab in its bounded AIMD LRU cache without lock inversion.
     */
    @FunctionalInterface
    public interface SectionMutationListener {
        void onSectionMutated(LcaSectionBacking backing, long currentTick);
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
    private volatile SectionMutationListener sectionMutationListener;
    private volatile int aimdAdditiveIncrement = DEFAULT_AIMD_ADDITIVE_INCREMENT;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int nonEmptyBlockCount;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int tickingBlockCount;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int tickingFluidCount;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile int aimdEditScore;
    private volatile int uniformSkyLight2 = -1;

    public int uniformSkyLight2() {
        return uniformSkyLight2;
    }

    public LcaSectionBacking withUniformSkyLight2(int skyLight2) {
        this.uniformSkyLight2 = (skyLight2 >= 0 && skyLight2 <= 3) ? skyLight2 : -1;
        return this;
    }

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

    /**
     * Constructs an unhydrated cold {@link LcaSectionBacking} (`compactIndex == null`) directly
     * around a runtime-palette `.lca` `SectionFrame` slice (`leafanvil-ADR-005`, `leafanvil-ADR-006`).
     *
     * <p>When {@code initialNonEmptyCount >= 0} is supplied (e.g. from a live NMS
     * {@code LevelChunkSection}), avoids eagerly unpacking the 4,096-voxel curve body in $O(1)$
     * while preserving exact {@link #hasOnlyAir()} / {@link #nonEmptyBlockCount()} semantics for
     * chunk lighting. When {@code initialNonEmptyCount < 0}, transiently parses the `.lca`
     * `SectionFrame` once to compute exact predicate counts without pinning `CompactBaseIndex` in RAM.</p>
     */
    public static LcaSectionBacking fromColdSectionFrame(
            byte[] coldSectionFrame,
            int fallbackUniformStateId,
            int initialNonEmptyCount,
            int initialTickingBlockCount,
            int initialTickingFluidCount,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        IntPredicate nonEmptyPred = nonEmptyPredicate != null ? nonEmptyPredicate : (id -> id != 0);
        IntPredicate tickBlockPred = tickingBlockPredicate != null ? tickingBlockPredicate : (id -> false);
        IntPredicate tickFluidPred = tickingFluidPredicate != null ? tickingFluidPredicate : (id -> false);
        byte[] safeFrame = coldSectionFrame != null ? coldSectionFrame : EMPTY_BYTES;
        int resolvedUniformId = fallbackUniformStateId;
        int nonEmpty = initialNonEmptyCount;
        int tickBlocks = Math.max(0, initialTickingBlockCount);
        int tickFluids = Math.max(0, initialTickingFluidCount);

        if (safeFrame.length == 0) {
            if (nonEmpty < 0) {
                nonEmpty = nonEmptyPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
                if (initialTickingBlockCount < 0) {
                    tickBlocks = tickBlockPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
                }
                if (initialTickingFluidCount < 0) {
                    tickFluids = tickFluidPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
                }
            }
        } else if (nonEmpty < 0) {
            CompactBaseIndex parsed = CompactBaseIndex.tryParse(safeFrame);
            if (parsed != null) {
                if (resolvedUniformId == 0) {
                    resolvedUniformId = parsed.majorityStateId;
                }
                int ne = 0;
                int tb = 0;
                int tf = 0;
                int[] pal = parsed.paletteStateIds;
                int[] counts = parsed.paletteCounts;
                for (int i = 0; i < pal.length; i++) {
                    int c = counts[i];
                    if (c > 0) {
                        int st = pal[i];
                        if (nonEmptyPred.test(st)) ne += c;
                        if (tickBlockPred.test(st)) tb += c;
                        if (tickFluidPred.test(st)) tf += c;
                    }
                }
                nonEmpty = ne;
                if (initialTickingBlockCount < 0) tickBlocks = tb;
                if (initialTickingFluidCount < 0) tickFluids = tf;
            } else {
                nonEmpty = nonEmptyPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
            }
        } else if (resolvedUniformId == 0) {
            int peekedMajority = peekSectionFrameDominantStateId(safeFrame);
            if (peekedMajority >= 0) {
                resolvedUniformId = peekedMajority;
            }
        }

        return new LcaSectionBacking(
                safeFrame,
                resolvedUniformId,
                null,
                Math.max(0, nonEmpty),
                tickBlocks,
                tickFluids,
                nonEmptyPred,
                tickBlockPred,
                tickFluidPred,
                null
        );
    }

    /**
     * Constructs an immediately hydrated {@link LcaSectionBacking} from a runtime-palette `.lca`
     * `SectionFrame` slice, parsing {@link CompactBaseIndex} exactly once, computing predicate
     * counts from it, and dropping the temporary {@code coldSectionFrame} byte array
     * ({@code baseFrame == null}) so it is never retained alongside {@link CompactBaseIndex}
     * (`goldleaf-ADR-005` Stage 4, `leafanvil-ADR-006`).
     */
    public static LcaSectionBacking fromColdSectionFrameHydrated(
            byte[] coldSectionFrame,
            int fallbackUniformStateId,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        IntPredicate nonEmptyPred = nonEmptyPredicate != null ? nonEmptyPredicate : (id -> id != 0);
        IntPredicate tickBlockPred = tickingBlockPredicate != null ? tickingBlockPredicate : (id -> false);
        IntPredicate tickFluidPred = tickingFluidPredicate != null ? tickingFluidPredicate : (id -> false);
        int resolvedUniformId = fallbackUniformStateId;

        if (coldSectionFrame == null || coldSectionFrame.length == 0) {
            int nonEmpty = nonEmptyPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
            int tickBlocks = tickBlockPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
            int tickFluids = tickFluidPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
            return new LcaSectionBacking(
                    EMPTY_BYTES,
                    resolvedUniformId,
                    null,
                    nonEmpty,
                    tickBlocks,
                    tickFluids,
                    nonEmptyPred,
                    tickBlockPred,
                    tickFluidPred,
                    null
            );
        }

        CompactBaseIndex parsed = CompactBaseIndex.tryParse(coldSectionFrame);
        if (parsed != null) {
            if (resolvedUniformId == 0) {
                resolvedUniformId = parsed.majorityStateId;
            }
            int ne = 0;
            int tb = 0;
            int tf = 0;
            int[] pal = parsed.paletteStateIds;
            int[] counts = parsed.paletteCounts;
            for (int i = 0; i < pal.length; i++) {
                int c = counts[i];
                if (c > 0) {
                    int st = pal[i];
                    if (nonEmptyPred.test(st)) ne += c;
                    if (tickBlockPred.test(st)) tb += c;
                    if (tickFluidPred.test(st)) tf += c;
                }
            }
            if (pal.length <= 1) {
                int singleId = pal.length == 1 ? pal[0] : resolvedUniformId;
                return new LcaSectionBacking(
                        EMPTY_BYTES,
                        singleId,
                        null,
                        ne,
                        tb,
                        tf,
                        nonEmptyPred,
                        tickBlockPred,
                        tickFluidPred,
                        null
                );
            }
            return new LcaSectionBacking(
                    parsed,
                    resolvedUniformId,
                    ne,
                    tb,
                    tf,
                    nonEmptyPred,
                    tickBlockPred,
                    tickFluidPred
            );
        }

        int nonEmpty = nonEmptyPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
        int tickBlocks = tickBlockPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
        int tickFluids = tickFluidPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
        return new LcaSectionBacking(
                coldSectionFrame,
                resolvedUniformId,
                null,
                nonEmpty,
                tickBlocks,
                tickFluids,
                nonEmptyPred,
                tickBlockPred,
                tickFluidPred,
                null
        );
    }

    /**
     * Parses a compressed {@code .lca} {@code SectionFrame} once into a hydrated {@link CompactBaseIndex}
     * while retaining {@code coldSectionFrame} as {@code baseFrame}. Callers can immediately run
     * block-counting / ticking-coordinate extraction in a single pass and then invoke
     * {@link #evictCompactBaseIndexToColdBaseFrame()} in O(1) with zero re-encoding to park the
     * section in {@code COLD_LCA} residency ({@code goldleaf-ADR-005} Stage 4, {@code leafanvil-ADR-006}).
     */
    public static LcaSectionBacking fromColdSectionFrameWithBaseAndIndex(
            byte[] coldSectionFrame,
            int fallbackUniformStateId,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        IntPredicate nonEmptyPred = nonEmptyPredicate != null ? nonEmptyPredicate : (id -> id != 0);
        IntPredicate tickBlockPred = tickingBlockPredicate != null ? tickingBlockPredicate : (id -> false);
        IntPredicate tickFluidPred = tickingFluidPredicate != null ? tickingFluidPredicate : (id -> false);
        int resolvedUniformId = fallbackUniformStateId;

        if (coldSectionFrame == null || coldSectionFrame.length == 0) {
            int nonEmpty = nonEmptyPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
            int tickBlocks = tickBlockPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
            int tickFluids = tickFluidPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
            return new LcaSectionBacking(
                    EMPTY_BYTES,
                    resolvedUniformId,
                    null,
                    nonEmpty,
                    tickBlocks,
                    tickFluids,
                    nonEmptyPred,
                    tickBlockPred,
                    tickFluidPred,
                    null
            );
        }

        CompactBaseIndex parsed = CompactBaseIndex.tryParse(coldSectionFrame);
        if (parsed != null) {
            if (resolvedUniformId == 0) {
                resolvedUniformId = parsed.majorityStateId;
            }
            int ne = 0;
            int tb = 0;
            int tf = 0;
            int[] pal = parsed.paletteStateIds;
            int[] counts = parsed.paletteCounts;
            for (int i = 0; i < pal.length; i++) {
                int c = counts[i];
                if (c > 0) {
                    int st = pal[i];
                    if (nonEmptyPred.test(st)) ne += c;
                    if (tickBlockPred.test(st)) tb += c;
                    if (tickFluidPred.test(st)) tf += c;
                }
            }
            if (pal.length <= 1) {
                int singleId = pal.length == 1 ? pal[0] : resolvedUniformId;
                return new LcaSectionBacking(
                        EMPTY_BYTES,
                        singleId,
                        null,
                        ne,
                        tb,
                        tf,
                        nonEmptyPred,
                        tickBlockPred,
                        tickFluidPred,
                        null
                );
            }
            return new LcaSectionBacking(
                    coldSectionFrame,
                    resolvedUniformId,
                    parsed,
                    ne,
                    tb,
                    tf,
                    nonEmptyPred,
                    tickBlockPred,
                    tickFluidPred,
                    null
            );
        }

        int nonEmpty = nonEmptyPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
        int tickBlocks = tickBlockPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
        int tickFluids = tickFluidPred.test(resolvedUniformId) ? BULK_4096_CAPACITY : 0;
        return new LcaSectionBacking(
                coldSectionFrame,
                resolvedUniformId,
                null,
                nonEmpty,
                tickBlocks,
                tickFluids,
                nonEmptyPred,
                tickBlockPred,
                tickFluidPred,
                null
        );
    }

    /**
     * Encodes a 4,096-voxel YZX runtime block-state array directly into a cold {@code UNIFORM}
     * ({@code 0 B}) or {@code COLD_LCA} (compressed {@code baseFrame} only, {@code compactIndex == null})
     * {@link LcaSectionBacking} suitable for off-tick compaction of {@code HOT_MUTABLE} or freshly
     * generated sections ({@code goldleaf-ADR-005} Stage 4, {@code leafanvil-ADR-006}).
     */
    public static LcaSectionBacking fromRuntimeVoxelsCold(
            int[] voxels4096,
            IntPredicate nonEmptyPredicate,
            IntPredicate tickingBlockPredicate,
            IntPredicate tickingFluidPredicate
    ) {
        if (voxels4096 == null || voxels4096.length != BULK_4096_CAPACITY) {
            throw new IllegalArgumentException(
                    "voxels4096 must have length " + BULK_4096_CAPACITY
                            + ", got " + (voxels4096 == null ? "null" : voxels4096.length));
        }
        IntPredicate nonEmptyPred = nonEmptyPredicate != null ? nonEmptyPredicate : (id -> id != 0);
        IntPredicate tickBlockPred = tickingBlockPredicate != null ? tickingBlockPredicate : (id -> false);
        IntPredicate tickFluidPred = tickingFluidPredicate != null ? tickingFluidPredicate : (id -> false);

        CompactBaseIndex index = CompactBaseIndex.fromVoxelsSinglePass(voxels4096, 15, 0);
        if (index == null) {
            int uniformId = Math.max(0, voxels4096[0]);
            int ne = nonEmptyPred.test(uniformId) ? BULK_4096_CAPACITY : 0;
            int tb = tickBlockPred.test(uniformId) ? BULK_4096_CAPACITY : 0;
            int tf = tickFluidPred.test(uniformId) ? BULK_4096_CAPACITY : 0;
            return new LcaSectionBacking(
                    EMPTY_BYTES,
                    uniformId,
                    null,
                    ne,
                    tb,
                    tf,
                    nonEmptyPred,
                    tickBlockPred,
                    tickFluidPred,
                    null
            );
        }

        int ne = 0;
        int tb = 0;
        int tf = 0;
        int[] pal = index.paletteStateIds;
        int[] counts = index.paletteCounts;
        for (int i = 0; i < pal.length; i++) {
            int c = counts[i];
            if (c > 0) {
                int st = pal[i];
                if (nonEmptyPred.test(st)) ne += c;
                if (tickBlockPred.test(st)) tb += c;
                if (tickFluidPred.test(st)) tf += c;
            }
        }
        byte[] coldBase = index.getOrEncodeBaseFrame();
        return new LcaSectionBacking(
                coldBase,
                index.majorityStateId,
                null,
                ne,
                tb,
                tf,
                nonEmptyPred,
                tickBlockPred,
                tickFluidPred,
                null
        );
    }

    private static int peekSectionFrameDominantStateId(byte[] frame) {
        if (frame == null || frame.length < 3) {
            return -1;
        }
        int pos = 0;
        int hdr = frame[pos++] & 0xFF;
        int blockMode = (hdr >>> 4) & 3;
        if (blockMode != 0 && blockMode != 3) {
            if (pos + 8 > frame.length) {
                return -1;
            }
            pos += 8;
        }
        int k = readUnsignedVarIntOrNeg1(frame, pos, frame.length);
        if (k == 0) {
            // v3 SectionFrame extension escape: 0x00, secExtFlags, actual paletteLen
            pos++;
            if (pos >= frame.length) {
                return -1;
            }
            pos++; // secExtFlags
            k = readUnsignedVarIntOrNeg1(frame, pos, frame.length);
        }
        if (k < 1 || k > BULK_4096_CAPACITY) {
            return -1;
        }
        pos += LcaSectionCodec.varIntLen(k);
        int dom = readUnsignedVarIntOrNeg1(frame, pos, frame.length);
        return (dom >= 0 && dom <= MAX_STATE_ID_19) ? dom : -1;
    }

    private static int readUnsignedVarIntOrNeg1(byte[] buf, int pos, int limit) {
        int val = 0;
        int shift = 0;
        while (pos < limit && shift <= 28) {
            int b = buf[pos++] & 0xFF;
            val |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return val >= 0 ? val : -1;
            }
            shift += 7;
        }
        return -1;
    }

    private LcaSectionBacking(
            byte[] baseFrame,
            int uniformStateId,
            CompactBaseIndex optionalIndex,
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
        this.committedState = new SectionSnapshotState(
                baseFrame,
                uniformStateId,
                optionalIndex,
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

    public void setSectionMutationListener(SectionMutationListener listener) {
        this.sectionMutationListener = listener;
    }

    public SectionMutationListener sectionMutationListener() {
        return this.sectionMutationListener;
    }

    public void setAimdAdditiveIncrement(int additiveIncrement) {
        if (additiveIncrement <= 0) {
            throw new IllegalArgumentException("additiveIncrement must be >= 1: " + additiveIncrement);
        }
        this.aimdAdditiveIncrement = additiveIncrement;
    }

    public int aimdAdditiveIncrement() {
        return this.aimdAdditiveIncrement;
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
     * Instant $O(1)$ sparse dirty cell write when the caller (e.g. NMS {@code PalettedContainer#getAndSet},
     * {@code LcaAgentSectionCarrier}, or {@code MixinPalettedContainer}) already knows {@code prevStateId19}.
     * Never reads {@code baseFrame} and never hydrates {@link CompactBaseIndex}.
     */
    public void recordDirtyVoxel(int pos12, int prevStateId19, int newStateId19, long currentTick) {
        recordDirtyVoxelSynchronized(pos12, prevStateId19, newStateId19, currentTick);
        SectionMutationListener listener = this.sectionMutationListener;
        if (listener != null) {
            listener.onSectionMutated(this, currentTick);
        }
    }

    /**
     * Instant $O(1)$ sparse dirty cell write without hydrating {@link CompactBaseIndex}.
     */
    public void recordDirtyVoxel(int pos12, int newStateId19, long currentTick) {
        int maskedPos = pos12 & MAX_POS_12;
        setAndGetPreviousStateId(
                maskedPos & 15,
                (maskedPos >>> 8) & 15,
                (maskedPos >>> 4) & 15,
                newStateId19,
                currentTick,
                this.defaultBaseFrameDecoder);
    }

    private synchronized void recordDirtyVoxelSynchronized(
            int pos12,
            int prevStateId19,
            int newStateId19,
            long currentTick
    ) {
        if (newStateId19 < 0 || newStateId19 > MAX_STATE_ID_19) {
            throw new IllegalArgumentException("stateId19 out of range [0..524287]: " + newStateId19);
        }
        int maskedPos = pos12 & MAX_POS_12;
        int octant = octantIdxFromPos12(maskedPos);
        OverlayTable currentOverlay = this.activeOverlay;
        int priorOverlayState;
        if (currentOverlay == EMPTY_OVERLAY) {
            OverlayTable allocated = new OverlayTable();
            priorOverlayState = allocated.put(maskedPos, octant, newStateId19);
            ACTIVE_OVERLAY.setRelease(this, allocated);
        } else {
            priorOverlayState = currentOverlay.put(maskedPos, octant, newStateId19);
        }
        this.lastMutatedTick = currentTick;
        recordEditScoreIncrement(this.aimdAdditiveIncrement);
        int effectivePrev = priorOverlayState >= 0 ? priorOverlayState : prevStateId19;
        if (effectivePrev >= 0 && effectivePrev != newStateId19) {
            publishScalarCountersRelease(maskedPos, effectivePrev, newStateId19);
        }
    }

    /**
     * Atomically masks {@code (x & 15)}, {@code (y & 15)}, {@code (z & 15)} at entry, updates the
     * sparse dirty cell map in $O(1)$ without hydrating {@link CompactBaseIndex}, publishes scalar
     * counters with release semantics, increments the AIMD edit score, and notifies any registered
     * {@link SectionMutationListener} outside {@code this} monitor.
     */
    public int setAndGetPreviousStateId(
            int x,
            int y,
            int z,
            int stateId19,
            long currentTick,
            IntUnaryOperator baseFrameDecoder) {
        int previousStateId = setAndGetPreviousStateIdSynchronized(
                x, y, z, stateId19, currentTick, baseFrameDecoder);
        SectionMutationListener listener = this.sectionMutationListener;
        if (listener != null) {
            listener.onSectionMutated(this, currentTick);
        }
        return previousStateId;
    }

    private synchronized int setAndGetPreviousStateIdSynchronized(
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
        int pos = pos12(mx, my, mz);
        int octant = octantIdx(mx, my, mz);

        // 1. Update sparse dirty cell map FIRST (lazy-allocating on first mutation, zero CompactBaseIndex hydration)
        OverlayTable currentOverlay = this.activeOverlay;
        int priorInActive;
        if (currentOverlay == EMPTY_OVERLAY) {
            OverlayTable allocated = new OverlayTable();
            priorInActive = allocated.put(pos, octant, stateId19);
            ACTIVE_OVERLAY.setRelease(this, allocated);
        } else {
            priorInActive = currentOverlay.put(pos, octant, stateId19);
        }
        int previousStateId;
        if (priorInActive >= 0) {
            previousStateId = priorInActive;
        } else {
            SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
            OverlayTable frozen = snap.frozenOverlay;
            int priorInFrozen = (frozen != null && (snap.frozenOctantMask & (1L << octant)) != 0L)
                    ? frozen.getStateId(pos)
                    : -1;
            previousStateId = priorInFrozen >= 0
                    ? priorInFrozen
                    : decodeFromBaseFrame(pos, snap, baseFrameDecoder != null ? baseFrameDecoder : this.defaultBaseFrameDecoder);
        }
        this.lastMutatedTick = currentTick;
        recordEditScoreIncrement(this.aimdAdditiveIncrement);

        // 2. Publish scalar counters (nonEmptyBlockCount, tickingBlockCount, tickingFluidCount) AFTER
        // dirtyOverlay with release semantics so readers checking hasOnlyAir() never observe a
        // non-empty count before the dirty entry is visible.
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

    private CompactBaseIndex resolveTransientOrHydratedIndex(SectionSnapshotState snap) {
        CompactBaseIndex idx = snap.compactIndex;
        if (idx != null || this.defaultBaseFrameDecoder != null) {
            return idx;
        }
        byte[] base = snap.baseFrame;
        if (base != null && base.length > 0 && base.length < BULK_4096_CAPACITY) {
            return CompactBaseIndex.tryParse(base);
        }
        return null;
    }

    /**
     * Emits every 12-bit YZX coordinate {@code pos12} whose current block state ID satisfies
     * {@code stateIdPredicate}. For clean hydrated sections where the majority state does not
     * match (e.g. random-ticking block scans on air/stone/deepslate sections), walks only the
     * {@link CompactBaseIndex} minority bitmasks without materializing a 4,096-int array.
     */
    public void forEachMatchingCoordinate(IntPredicate stateIdPredicate, IntConsumer pos12Consumer) {
        Objects.requireNonNull(stateIdPredicate, "stateIdPredicate");
        Objects.requireNonNull(pos12Consumer, "pos12Consumer");
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        OverlayTable frozen = snap.frozenOverlay;
        int activeDirty = active != null ? active.dirtyCount() : 0;
        int frozenDirty = frozen != null ? frozen.dirtyCount() : 0;

        if (snap.isUniformBase() && activeDirty == 0 && frozenDirty == 0) {
            if (stateIdPredicate.test(Math.max(0, snap.uniformStateId))) {
                for (int pos12 = 0; pos12 < BULK_4096_CAPACITY; pos12++) {
                    pos12Consumer.accept(pos12);
                }
            }
            return;
        }

        CompactBaseIndex idx = snap.compactIndex;
        if (idx != null && activeDirty == 0 && frozenDirty == 0) {
            idx.forEachMatchingCoordinate(stateIdPredicate, pos12Consumer);
            return;
        }

        int[] voxels = materializeStateIdsInternal(null, null);
        int lastId = -1;
        boolean lastMatch = false;
        for (int pos12 = 0; pos12 < BULK_4096_CAPACITY; pos12++) {
            int id = voxels[pos12];
            if (id != lastId) {
                lastId = id;
                lastMatch = stateIdPredicate.test(id);
            }
            if (lastMatch) {
                pos12Consumer.accept(pos12);
            }
        }
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

        final CompactBaseIndex idx = resolveTransientOrHydratedIndex(snap);
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

        final CompactBaseIndex idx = (activeDirty + frozenDirty) <= HASH_64_MAX_LOAD
                ? resolveTransientOrHydratedIndex(snap)
                : snap.compactIndex;
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
        CompactBaseIndex idx = resolveTransientOrHydratedIndex(snap);
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
        } else if (base != null && base.length > 0) {
            decodeCompactBaseFrameInto(base, uniform, out);
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

        final CompactBaseIndex idx = (activeDirty + frozenDirty) <= HASH_64_MAX_LOAD
                ? resolveTransientOrHydratedIndex(snap)
                : snap.compactIndex;
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
        CompactEncodeScratch scratch = encodeScratch();
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

        CompactBaseIndex idx = (activeDirty == 0 && frozenDirty == 0)
                ? resolveTransientOrHydratedIndex(snap)
                : snap.compactIndex;
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
        CompactEncodeScratch scratch = encodeScratch();
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
        if (snap.baseFrame != null && snap.baseFrame.length > 0 && snap.baseFrame.length < BULK_4096_CAPACITY) {
            CompactBaseIndex transientIdx = CompactBaseIndex.tryParse(snap.baseFrame);
            if (transientIdx != null) {
                return transientIdx.getOrBuildCleanVanillaWireSlice(globalPaletteBits);
            }
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
        int frozenCount = frozen != null ? frozen.dirtyCount() : 0;
        int activeCount = active != null ? active.dirtyCount() : 0;
        if (frozenCount == 0 && activeCount == 0) {
            return new int[0];
        }
        if (frozenCount == 0) {
            return active.toCompactPackedArray();
        }
        if (activeCount == 0) {
            return frozen.toCompactPackedArray();
        }
        SparseDirtyCellMap merged = frozen.copySparseMap();
        active.forEachEntry(merged::put);
        return merged.toCompactPackedArray();
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
        if (baseFrame != null && baseFrame.length > 0) {
            CompactBaseIndex parsed = CompactBaseIndex.tryParse(baseFrame);
            if (parsed != null) {
                return parsed.lookup(pos12);
            }
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
        SectionSnapshotState initial = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        CompactBaseIndex newIndex = initial.compactIndex != null ? CompactBaseIndex.tryParse(newBaseFrame) : null;
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
        int skyConst4 = 15;
        int blkConst4 = 0;
        if (current.compactIndex != null) {
            current.compactIndex.scatterInto(voxels);
            skyConst4 = (current.compactIndex.packedLightByte >>> 4) & 0xF;
            blkConst4 = current.compactIndex.packedLightByte & 0xF;
        } else if (current.baseFrame != null && current.baseFrame.length >= BULK_4096_CAPACITY) {
            for (int i = 0; i < BULK_4096_CAPACITY; i++) {
                voxels[i] = current.baseFrame[i] & 0xFF;
            }
        } else if (current.baseFrame != null && current.baseFrame.length > 0 && this.defaultBaseFrameDecoder == null) {
            CompactBaseIndex parsedCold = CompactBaseIndex.tryParse(current.baseFrame);
            if (parsedCold != null) {
                parsedCold.scatterInto(voxels);
                skyConst4 = (parsedCold.packedLightByte >>> 4) & 0xF;
                blkConst4 = parsedCold.packedLightByte & 0xF;
            } else {
                Arrays.fill(voxels, 0, BULK_4096_CAPACITY, current.uniformStateId);
            }
        } else if (this.defaultBaseFrameDecoder != null && !current.isUniformBase()) {
            for (int i = 0; i < BULK_4096_CAPACITY; i++) {
                voxels[i] = this.defaultBaseFrameDecoder.applyAsInt(i);
            }
        } else {
            Arrays.fill(voxels, 0, BULK_4096_CAPACITY, current.uniformStateId);
        }

        frozen.forEachEntry((pos12, stateId) -> voxels[pos12 & MAX_POS_12] = stateId);

        CompactBaseIndex newIndex = CompactBaseIndex.fromVoxelsSinglePass(voxels, skyConst4, blkConst4);
        if (newIndex == null) {
            int uniformId = Math.max(0, voxels[0]);
            return completeCompactionWithIndex(snap.epoch(), EMPTY_BYTES, uniformId, null);
        }

        boolean keepHydrated = current.compactIndex != null;
        if (keepHydrated) {
            newIndex.getOrBuildCleanVanillaWireSlice(globalPaletteBits);
        }
        byte[] newBaseFrame = newIndex.getOrEncodeBaseFrame();
        return completeCompactionWithIndex(
                snap.epoch(), newBaseFrame, newIndex.majorityStateId, keepHydrated ? newIndex : null);
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

    public int activeOverlayCapacity() {
        return ((OverlayTable) ACTIVE_OVERLAY.getAcquire(this)).tableCapacity();
    }

    public int activeOverlayAllocatedBytes() {
        return ((OverlayTable) ACTIVE_OVERLAY.getAcquire(this)).allocatedSlotBytes();
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
     * Returns {@code true} when an unpacked {@link CompactBaseIndex} is currently resident on this section.
     */
    public boolean hasHydratedCompactBaseIndex() {
        return ((SectionSnapshotState) COMMITTED_STATE.getAcquire(this)).compactIndex != null;
    }

    /**
     * Returns {@code true} when this section is a clean non-uniform 3D slab holding only its compact
     * {@code .lca} {@code baseFrame} ({@code compactIndex == null}).
     */
    public boolean isColdBaseFrameOnly() {
        OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
        if (active != null && active.dirtyCount() != 0) {
            return false;
        }
        SectionSnapshotState snap = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (snap.frozenOverlay != null && snap.frozenOverlay.dirtyCount() != 0) {
            return false;
        }
        return snap.compactIndex == null && snap.baseFrame != null && snap.baseFrame.length > 0;
    }

    /**
     * Hydrates {@link CompactBaseIndex} strictly in-memory from this section's resident compact
     * {@code .lca} {@code baseFrame} via lock-free CAS when currently cold ({@code REQ-RTP-S-005}).
     */
    public boolean hydrateCompactBaseIndexIfNeeded() {
        SectionSnapshotState initial = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
        if (initial.compactIndex != null) {
            return true;
        }
        if ((initial.chunkEpoch & UNLOADED_BIT) != 0L
                || initial.baseFrame == null
                || initial.baseFrame.length == 0
                || initial.baseFrame.length >= BULK_4096_CAPACITY) {
            return false;
        }
        CompactBaseIndex parsed = CompactBaseIndex.tryParse(initial.baseFrame);
        if (parsed == null) {
            return false;
        }
        while (true) {
            SectionSnapshotState current = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
            if (current.compactIndex != null) {
                return true;
            }
            if ((current.chunkEpoch & UNLOADED_BIT) != 0L || current.baseFrame != initial.baseFrame) {
                return false;
            }
            SectionSnapshotState next = new SectionSnapshotState(
                    current.baseFrame,
                    current.uniformStateId,
                    parsed,
                    current.frozenOverlay,
                    current.frozenOctantMask,
                    current.chunkEpoch);
            if (COMMITTED_STATE.compareAndSet(this, current, next)) {
                return true;
            }
        }
    }

    /**
     * Demotes a clean non-uniform section from hydrated {@link CompactBaseIndex} residency to sealed
     * {@code .lca} {@code baseFrame}-only cold mode ({@code leafanvil-ADR-006}). Refuses demotion
     * whenever {@code activeOverlay} or {@code frozenOverlay} contains uncompacted edits ({@code REQ-RTP-S-004}).
     */
    public boolean evictCompactBaseIndexToColdBaseFrame() {
        while (true) {
            OverlayTable active = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
            if (active != null && active.dirtyCount() != 0) {
                return false;
            }
            SectionSnapshotState current = (SectionSnapshotState) COMMITTED_STATE.getAcquire(this);
            if ((current.chunkEpoch & UNLOADED_BIT) != 0L || current.compactIndex == null) {
                return false;
            }
            if (current.frozenOverlay != null && current.frozenOverlay.dirtyCount() != 0) {
                return false;
            }
            byte[] encodedBase = current.resolveBaseFrameBytes();
            if (encodedBase == null || encodedBase.length == 0) {
                return false;
            }
            OverlayTable recheckActive = (OverlayTable) ACTIVE_OVERLAY.getAcquire(this);
            if (recheckActive != null && recheckActive.dirtyCount() != 0) {
                return false;
            }
            SectionSnapshotState next = new SectionSnapshotState(
                    encodedBase,
                    current.uniformStateId,
                    null,
                    null,
                    0L,
                    current.chunkEpoch);
            if (COMMITTED_STATE.compareAndSet(this, current, next)) {
                return true;
            }
        }
    }

    /**
     * Returns the current AIMD edit-count score for 3D slab cache retention ({@code leafanvil-ADR-006}).
     */
    public int editScore() {
        return (int) AIMD_EDIT_SCORE.getAcquire(this);
    }

    /**
     * Additively increments this section's AIMD edit score, clamping at {@link #MAX_AIMD_EDIT_SCORE}.
     */
    public int recordEditScoreIncrement(int additiveIncrement) {
        if (additiveIncrement <= 0) {
            throw new IllegalArgumentException("additiveIncrement must be >= 1: " + additiveIncrement);
        }
        while (true) {
            int cur = (int) AIMD_EDIT_SCORE.getAcquire(this);
            int next = Math.min(MAX_AIMD_EDIT_SCORE, cur + additiveIncrement);
            if (AIMD_EDIT_SCORE.compareAndSet(this, cur, next)) {
                return next;
            }
        }
    }

    /**
     * Multiplicatively decays this section's AIMD edit score via {@code (score * numerator) >>> shift}.
     */
    public int applyMultiplicativeScoreDecay(int numerator, int shift) {
        if (numerator < 0 || shift < 0 || shift > 30 || numerator > (1 << shift)) {
            throw new IllegalArgumentException(
                    "Invalid multiplicative decay fraction (" + numerator + " / 2^" + shift + ")");
        }
        while (true) {
            int cur = (int) AIMD_EDIT_SCORE.getAcquire(this);
            int next = (int) (((long) cur * numerator) >>> shift);
            if (AIMD_EDIT_SCORE.compareAndSet(this, cur, next)) {
                return next;
            }
        }
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
            CompactEncodeScratch scratch = encodeScratch();
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
            CompactBaseIndex nativeParsed = tryParseNativeCompactFrame(baseFrame);
            if (nativeParsed != null) {
                return nativeParsed;
            }
            return tryParseLcaSectionFrame(baseFrame);
        }

        private static CompactBaseIndex tryParseNativeCompactFrame(byte[] baseFrame) {
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
            // Packed light byte only uses skyConst4 in high nibble and blkConst4 in low nibble;
            // V2 native CompactBaseIndex always has 0x00 sentinel next and consumes exact len bytes.
            if (cursor + 1 < len && baseFrame[cursor] == 0x00) {
                CompactBaseIndex v2 = tryParseV2Containers(baseFrame, cursor + 1, len, majorityStateId, packedLight);
                if (v2 != null) {
                    return v2;
                }
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
                    if (k > 0 && delta <= 0) {
                        return null;
                    }
                    pos += delta;
                    if (pos < 0 || pos >= BULK_4096_CAPACITY) {
                        return null;
                    }
                    int w = pos >>> 6;
                    long bit = 1L << (pos & 63);
                    if ((tempWordMasks[w] & bit) != 0L) {
                        return null;
                    }
                    wordMask64 |= (1L << w);
                    tempWordMasks[w] |= bit;
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
            int[] yzxOrd = encodeScratch().nextPos;
            int endCursor = LcaSectionCodec.decodeSectionBlockContainersV2(
                    baseFrame, cursor, len, paletteLen, yzxOrd);
            if (endCursor != len) {
                return null;
            }
            return buildIndexFromDecodedPaletteAndOrdinals(paletteStateIds, yzxOrd, packedLight, baseFrame);
        }

        private static CompactBaseIndex buildIndexFromDecodedPaletteAndOrdinals(
                int[] paletteStateIds,
                int[] yzxOrd,
                byte packedLight,
                byte[] lazyFrameOrNull
        ) {
            int paletteLen = paletteStateIds.length;
            int majorityStateId = paletteStateIds[0];
            if (paletteLen == 1) {
                return new CompactBaseIndex(
                        majorityStateId,
                        paletteStateIds,
                        new int[]{BULK_4096_CAPACITY},
                        0L,
                        new long[0],
                        new short[0],
                        new byte[0],
                        null,
                        packedLight,
                        lazyFrameOrNull
                );
            }
            int[] paletteCounts = new int[paletteLen];
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
                    lazyFrameOrNull
            );
        }

        private static CompactBaseIndex tryParseLcaSectionFrame(byte[] baseFrame) {
            if (baseFrame == null || baseFrame.length < 3 || baseFrame.length == BULK_4096_CAPACITY) {
                return null;
            }
            int[] yzxOrd = decodeYzx4096Scratch();
            byte[] lightBox = new byte[1];
            int[] paletteStateIds = LcaSectionCodec.decodeSectionFrameBlockOrdinals(
                    baseFrame, baseFrame.length, yzxOrd, lightBox);
            if (paletteStateIds == null || paletteStateIds.length == 0) {
                return null;
            }
            return buildIndexFromDecodedPaletteAndOrdinals(paletteStateIds, yzxOrd, lightBox[0], null);
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
            CompactEncodeScratch scratch = encodeScratch();
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

        void forEachMatchingCoordinate(IntPredicate stateIdPredicate, IntConsumer pos12Consumer) {
            int palLen = this.paletteStateIds.length;
            boolean[] matches = new boolean[palLen];
            boolean anyMatch = false;
            for (int i = 0; i < palLen; i++) {
                if (this.paletteCounts[i] > 0 && stateIdPredicate.test(this.paletteStateIds[i])) {
                    matches[i] = true;
                    anyMatch = true;
                }
            }
            if (!anyMatch) {
                return;
            }
            if (!matches[0]) {
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
                        if (matches[ord]) {
                            pos12Consumer.accept(basePos | b);
                        }
                    }
                }
                return;
            }
            long wordMask = this.nonUniformWordMask64;
            int slot = 0;
            for (int w = 0; w < 64; w++) {
                int basePos = w << 6;
                if (((wordMask >>> w) & 1L) == 0L) {
                    for (int b = 0; b < 64; b++) {
                        pos12Consumer.accept(basePos | b);
                    }
                } else {
                    long voxMask = this.minorityBitmasks[slot];
                    int rank = this.wordRankOffsets[slot] & 0xFFFF;
                    slot++;
                    for (int b = 0; b < 64; b++) {
                        if (((voxMask >>> b) & 1L) == 0L) {
                            pos12Consumer.accept(basePos | b);
                        } else {
                            int ord = readOrdinalAtRank(rank++);
                            if (matches[ord]) {
                                pos12Consumer.accept(basePos | b);
                            }
                        }
                    }
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
     * Flat resizable power-of-two open-addressed primitive {@code (pos12 -> runtimeStateId19)} table
     * ({@code 0 -> 16 -> 64 -> 256 -> 1024 -> 8192} slots, never allocating {@code int[4096]} dense slabs).
     * Packed entry word: {@code ((pos12 + 1) << 19) | (runtimeStateId19 & 0x7FFFF)}, {@code 0 == empty}.
     */
    static final class SparseDirtyCellMap {
        private static final int[] EMPTY_TABLE = new int[0];

        private volatile int[] table;
        private volatile int size;

        SparseDirtyCellMap() {
            this.table = EMPTY_TABLE;
            this.size = 0;
        }

        private SparseDirtyCellMap(int[] table, int size) {
            this.table = table;
            this.size = size;
        }

        int size() {
            return this.size;
        }

        int capacity() {
            return this.table.length;
        }

        int stageCode() {
            int cap = this.table.length;
            if (cap == 0 || this.size == 0) {
                return STAGE_EMPTY;
            }
            if (cap <= LINEAR_16_CAPACITY) {
                return STAGE_LINEAR_16;
            }
            if (cap <= HASH_64_CAPACITY) {
                return STAGE_HASH_64;
            }
            return STAGE_SPARSE_HASH_EXT;
        }

        private static int initialSlot(int pos12, int cap) {
            int shift = Integer.numberOfLeadingZeros(cap) + 1;
            return (((pos12 & MAX_POS_12) * FIBONACCI_HASH_MULTIPLIER) >>> shift) & (cap - 1);
        }

        private static int maxLoadForCapacity(int cap) {
            if (cap == LINEAR_16_CAPACITY) {
                return LINEAR_16_CAPACITY;
            }
            if (cap >= 8192) {
                return BULK_4096_CAPACITY;
            }
            return (cap * 3) >>> 2;
        }

        private static int nextCapacity(int currentCap) {
            if (currentCap == 0) return 16;
            if (currentCap == 16) return 64;
            if (currentCap == 64) return 256;
            if (currentCap == 256) return 1024;
            return 8192;
        }

        int get(int pos12) {
            int maskedPos = pos12 & MAX_POS_12;
            int[] tab = this.table;
            int cap = tab.length;
            if (cap == 0 || this.size == 0) {
                return -1;
            }
            if (cap == LINEAR_16_CAPACITY) {
                int limit = Math.min(this.size, LINEAR_16_CAPACITY);
                for (int i = 0; i < limit; i++) {
                    int entry = tab[i];
                    if (entry != 0 && unpackPos12(entry) == maskedPos) {
                        return unpackStateId19(entry);
                    }
                }
                return -1;
            }
            int mask = cap - 1;
            int slot = initialSlot(maskedPos, cap);
            for (int probes = 0; probes < cap; probes++) {
                int entry = tab[slot];
                if (entry == 0) {
                    return -1;
                }
                if (unpackPos12(entry) == maskedPos) {
                    return unpackStateId19(entry);
                }
                slot = (slot + 1) & mask;
            }
            return -1;
        }

        synchronized int put(int pos12, int runtimeStateId19) {
            int maskedPos = pos12 & MAX_POS_12;
            int packed = packEntry32(maskedPos, runtimeStateId19);
            int[] tab = this.table;
            int cap = tab.length;

            if (cap == 0) {
                int[] next = new int[LINEAR_16_CAPACITY];
                next[0] = packed;
                this.table = next;
                this.size = 1;
                return -1;
            }

            if (cap == LINEAR_16_CAPACITY) {
                int count = this.size;
                for (int i = 0; i < count; i++) {
                    int existing = tab[i];
                    if (unpackPos12(existing) == maskedPos) {
                        int prevState = unpackStateId19(existing);
                        tab[i] = packed;
                        return prevState;
                    }
                }
                if (count < LINEAR_16_CAPACITY) {
                    tab[count] = packed;
                    this.size = count + 1;
                    return -1;
                }
                rehashTo(nextCapacity(cap), packed);
                return -1;
            }

            int mask = cap - 1;
            int slot = initialSlot(maskedPos, cap);
            int maxLoad = maxLoadForCapacity(cap);
            for (int probes = 0; probes < cap; probes++) {
                int existing = tab[slot];
                if (existing == 0) {
                    if (this.size < maxLoad) {
                        tab[slot] = packed;
                        this.size++;
                        return -1;
                    }
                    break;
                }
                if (unpackPos12(existing) == maskedPos) {
                    int prevState = unpackStateId19(existing);
                    tab[slot] = packed;
                    return prevState;
                }
                slot = (slot + 1) & mask;
            }

            rehashTo(nextCapacity(cap), packed);
            return -1;
        }

        private void rehashTo(int newCap, int additionalPackedEntry) {
            int[] oldTab = this.table;
            int[] newTab = new int[newCap];
            int mask = newCap - 1;
            int newSize = 0;
            for (int entry : oldTab) {
                if (entry != 0) {
                    int pos = unpackPos12(entry);
                    int s = initialSlot(pos, newCap);
                    while (newTab[s] != 0) {
                        s = (s + 1) & mask;
                    }
                    newTab[s] = entry;
                    newSize++;
                }
            }
            int addPos = unpackPos12(additionalPackedEntry);
            int s = initialSlot(addPos, newCap);
            while (newTab[s] != 0) {
                if (unpackPos12(newTab[s]) == addPos) {
                    newTab[s] = additionalPackedEntry;
                    this.table = newTab;
                    this.size = newSize;
                    return;
                }
                s = (s + 1) & mask;
            }
            newTab[s] = additionalPackedEntry;
            this.table = newTab;
            this.size = newSize + 1;
        }

        int maxProbeLength() {
            int[] tab = this.table;
            int cap = tab.length;
            int count = this.size;
            if (cap == 0 || count == 0) {
                return 0;
            }
            if (cap == LINEAR_16_CAPACITY) {
                return count;
            }
            int mask = cap - 1;
            int maxProbes = 0;
            for (int slot = 0; slot < cap; slot++) {
                int entry = tab[slot];
                if (entry != 0) {
                    int start = initialSlot(unpackPos12(entry), cap);
                    int probes = ((slot - start) & mask) + 1;
                    if (probes > maxProbes) {
                        maxProbes = probes;
                    }
                }
            }
            return maxProbes;
        }

        int[] toCompactPackedArray() {
            int[] tab = this.table;
            int cap = tab.length;
            int count = this.size;
            if (cap == 0 || count == 0) {
                return new int[0];
            }
            if (cap == LINEAR_16_CAPACITY) {
                int limit = Math.min(count, LINEAR_16_CAPACITY);
                return Arrays.copyOf(tab, limit);
            }
            int[] dense = new int[count];
            int idx = 0;
            for (int entry : tab) {
                if (entry != 0) {
                    if (idx == dense.length) {
                        dense = Arrays.copyOf(dense, dense.length + 8);
                    }
                    dense[idx++] = entry;
                }
            }
            return idx == dense.length ? dense : Arrays.copyOf(dense, idx);
        }

        void forEachEntry(OverlayEntryConsumer consumer) {
            int[] tab = this.table;
            int cap = tab.length;
            if (cap == 0 || this.size == 0) {
                return;
            }
            if (cap == LINEAR_16_CAPACITY) {
                int limit = Math.min(this.size, LINEAR_16_CAPACITY);
                for (int i = 0; i < limit; i++) {
                    int entry = tab[i];
                    if (entry != 0) {
                        consumer.accept(unpackPos12(entry), unpackStateId19(entry));
                    }
                }
                return;
            }
            for (int entry : tab) {
                if (entry != 0) {
                    consumer.accept(unpackPos12(entry), unpackStateId19(entry));
                }
            }
        }

        synchronized SparseDirtyCellMap copy() {
            if (this.size == 0 || this.table.length == 0) {
                return new SparseDirtyCellMap();
            }
            return new SparseDirtyCellMap(Arrays.copyOf(this.table, this.table.length), this.size);
        }
    }

    /**
     * Sparse dirty cell modifier wrapper backed by {@link SparseDirtyCellMap} (`16 -> 64 -> 256 -> 1024 -> 8192`
     * open-addressed primitive slots, zero `int[4096]` full-slab inflation).
     */
    public static final class OverlayTable {
        private final SparseDirtyCellMap sparseMap;
        private volatile long octantMask;

        public OverlayTable() {
            this.sparseMap = new SparseDirtyCellMap();
            this.octantMask = 0L;
        }

        private OverlayTable(SparseDirtyCellMap sparseMap, long octantMask) {
            this.sparseMap = sparseMap;
            this.octantMask = octantMask;
        }

        public int stage() {
            return this.sparseMap.stageCode();
        }

        public int dirtyCount() {
            return this.sparseMap.size();
        }

        public int tableCapacity() {
            return this.sparseMap.capacity();
        }

        public int allocatedSlotBytes() {
            return this.sparseMap.capacity() << 2;
        }

        public long octantMask() {
            return this.octantMask;
        }

        /**
         * Returns the 19-bit state ID for {@code pos12}, or {@code -1} on miss.
         */
        public int getStateId(int pos12) {
            return this.sparseMap.get(pos12);
        }

        synchronized int put(int pos12, int octantIdx, int stateId19) {
            int prev = this.sparseMap.put(pos12, stateId19);
            this.octantMask |= (1L << (octantIdx & 63));
            return prev;
        }

        public int[] toCompactPackedArray() {
            return this.sparseMap.toCompactPackedArray();
        }

        SparseDirtyCellMap copySparseMap() {
            return this.sparseMap.copy();
        }

        /**
         * Computes the worst-case open-addressed probe length across all occupied slots.
         */
        public int maxProbeLength() {
            return this.sparseMap.maxProbeLength();
        }

        OverlayTable freezeCopy() {
            if (this.sparseMap.size() == 0) {
                return new OverlayTable();
            }
            return new OverlayTable(this.sparseMap.copy(), this.octantMask);
        }

        OverlayTable mergeWithNewer(OverlayTable newer) {
            OverlayTable merged = freezeCopy();
            newer.forEachEntry((pos12, stateId19) ->
                    merged.put(pos12, octantIdxFromPos12(pos12), stateId19));
            return merged;
        }

        /**
         * Visits all dirty {@code (pos12, stateId19)} pairs stored in this sparse modifier table.
         */
        public void forEachEntry(OverlayEntryConsumer consumer) {
            Objects.requireNonNull(consumer, "consumer");
            this.sparseMap.forEachEntry(consumer);
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

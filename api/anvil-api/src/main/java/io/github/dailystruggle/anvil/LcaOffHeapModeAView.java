package io.github.dailystruggle.anvil;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Zero-copy off-heap Mode A block section view held by {@link LcaSectionBacking} directly over a
 * validated {@link LcaModeABlockSection.OffHeapModeASlice} (`anvil-api-ADR-007`).
 *
 * <p>Provides zero-copy random queries, O(paletteSize) counts and predicates via single-pass
 * {@code paletteVoxelCounts}, a 16-slot 2-way set-associative {@link WeakReference} per-thread
 * {@code ReadCache} with a 4-entry recent-owner probe (`REQ-RTP-S-002`), and direct vanilla wire
 * serialization without ever copying the Mode A section payload into heap {@code byte[]}.</p>
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-007
 * @governedBy ADR-115
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
final class LcaOffHeapModeAView {

    private static final int SECTION_VOXELS = LcaSectionCodec.SECTION_VOXELS;
    private static final int MAX_CACHED_PALETTE = 256;

    /**
     * 16-slot 2-way set-associative per-thread L1 ordinal cache (`64 KiB` of reusable
     * {@code byte[4096]} grids per worker thread) keyed by {@link WeakReference} so unloaded
     * off-heap slabs reclaim cleanly (`REQ-RTP-S-002`). Uses a 4-entry recent-owner probe so
     * alternating neighbor boundary sweeps promote both sections after 2 hits without thrashing.
     */
    private static final class ReadCache {
        private static final int SETS = 8;
        private static final int SLOTS = SETS << 1; // 16
        private static final int RECENT_SLOTS = 4;

        @SuppressWarnings("unchecked")
        private final WeakReference<LcaOffHeapModeAView>[] owners = new WeakReference[SLOTS];
        private final byte[][] grids = new byte[SLOTS][];
        private int evictToggleBits;

        @SuppressWarnings("unchecked")
        private final WeakReference<LcaOffHeapModeAView>[] recentOwners = new WeakReference[RECENT_SLOTS];
        private final byte[] recentHits = new byte[RECENT_SLOTS];
        private int recentNext;

        @SuppressWarnings("java:S1168")
        byte[] lookup(LcaOffHeapModeAView owner) {
            int set = (System.identityHashCode(owner) >>> 4) & (SETS - 1);
            int base = set << 1;
            WeakReference<LcaOffHeapModeAView> r0 = this.owners[base];
            if (r0 != null && r0.refersTo(owner)) {
                return this.grids[base];
            }
            WeakReference<LcaOffHeapModeAView> r1 = this.owners[base + 1];
            if (r1 != null && r1.refersTo(owner)) {
                return this.grids[base + 1];
            }
            return null;
        }

        @SuppressWarnings("java:S1168")
        byte[] tryPromote(LcaOffHeapModeAView owner) {
            for (int i = 0; i < RECENT_SLOTS; i++) {
                WeakReference<LcaOffHeapModeAView> ref = this.recentOwners[i];
                if (ref != null && ref.refersTo(owner)) {
                    if (++this.recentHits[i] < 2) {
                        return null;
                    }
                    this.recentOwners[i] = null;
                    this.recentHits[i] = 0;
                    int set = (System.identityHashCode(owner) >>> 4) & (SETS - 1);
                    int base = set << 1;
                    WeakReference<LcaOffHeapModeAView> w0 = this.owners[base];
                    WeakReference<LcaOffHeapModeAView> w1 = this.owners[base + 1];
                    int slot;
                    if (w0 == null || w0.get() == null) {
                        slot = base;
                    } else if (w1 == null || w1.get() == null) {
                        slot = base + 1;
                    } else {
                        int way = (this.evictToggleBits >>> set) & 1;
                        this.evictToggleBits ^= (1 << set);
                        slot = base + way;
                    }
                    byte[] grid = this.grids[slot];
                    if (grid == null) {
                        grid = new byte[SECTION_VOXELS];
                        this.grids[slot] = grid;
                    }
                    owner.materializeOrdinalBytes(grid);
                    this.owners[slot] = new WeakReference<>(owner);
                    return grid;
                }
            }
            int idx = this.recentNext;
            this.recentNext = (idx + 1) & (RECENT_SLOTS - 1);
            this.recentOwners[idx] = new WeakReference<>(owner);
            this.recentHits[idx] = 1;
            return null;
        }
    }

    private static final ThreadLocal<ReadCache> READ_CACHE = ThreadLocal.withInitial(ReadCache::new);

    static void clearThreadLocalScratch() {
        READ_CACHE.remove();
    }

    private final LcaModeABlockSection.OffHeapModeASlice slice;
    private final ByteBuffer slabBuf;
    private final boolean littleEndianBuf;
    private final int payloadOffset;
    private final int payloadLength;
    private final int curveId;
    private final int blockMode;
    private final int flatBits;
    private final long octreeMask64;
    private final byte activeSlabMask4;
    private final int[] paletteRuntimeIds;
    private final int[] paletteVoxelCounts;
    private final short[] containerOffsets;
    private final int activeVoxels;
    private final int[] chunkBase;
    private final int chunkShift;
    private final int chunkMask;
    private final int[] yzxToCurve;
    private final int[] curveToYzx;
    private volatile byte[] lazyCompactBaseFrame;

    LcaOffHeapModeAView(LcaModeABlockSection.OffHeapModeASlice slice) {
        this.slice = Objects.requireNonNull(slice, "slice");
        this.slabBuf = slice.slabBuf();
        this.littleEndianBuf = this.slabBuf.order() == ByteOrder.LITTLE_ENDIAN;
        this.payloadOffset = slice.payloadOffset();
        this.payloadLength = slice.payloadLength();
        this.curveId = slice.curveId() & 3;
        this.blockMode = slice.blockMode() & 3;
        this.flatBits = slice.flatBits();
        this.octreeMask64 = slice.octreeMask64();
        this.paletteRuntimeIds = slice.paletteRuntimeIds();
        this.paletteVoxelCounts = slice.paletteVoxelCounts();
        this.containerOffsets = slice.containerOffsets();
        this.yzxToCurve = LcaSectionCodec.YZX_TO_CURVE[this.curveId];
        this.curveToYzx = LcaSectionCodec.CURVE_TO_YZX[this.curveId];

        int slabMask = 0;
        for (int octY = 0; octY < 4; octY++) {
            if (((this.octreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                slabMask |= (1 << octY);
            }
        }
        this.activeSlabMask4 = (byte) slabMask;

        int[] base = null;
        int shift = 0;
        int mask = 0;
        int active = SECTION_VOXELS;
        if (this.blockMode != LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK
                && this.octreeMask64 != -1L && this.octreeMask64 != 0L) {
            if (this.curveId == LcaSectionCodec.CURVE_H3) {
                int k = Long.bitCount(this.octreeMask64);
                base = new int[k];
                long m = this.octreeMask64;
                int idx = 0;
                while (m != 0L) {
                    base[idx++] = Long.numberOfTrailingZeros(m) << 6;
                    m &= (m - 1L);
                }
                shift = 6;
                mask = 63;
                active = k << 6;
            } else if (this.curveId == LcaSectionCodec.CURVE_YZX || this.curveId == LcaSectionCodec.CURVE_Y_H2) {
                int sCount = Integer.bitCount(slabMask);
                if (sCount > 0 && sCount < 4) {
                    base = new int[sCount];
                    int idx = 0;
                    for (int octY = 0; octY < 4; octY++) {
                        if ((slabMask & (1 << octY)) != 0) {
                            base[idx++] = octY << 10;
                        }
                    }
                    shift = 10;
                    mask = 1023;
                    active = sCount << 10;
                }
            }
        }
        this.chunkBase = base;
        this.chunkShift = shift;
        this.chunkMask = mask;
        this.activeVoxels = active;
    }

    LcaModeABlockSection.OffHeapModeASlice slice() {
        return this.slice;
    }

    int majorityStateId() {
        return this.paletteRuntimeIds[0];
    }

    int[] paletteRuntimeIds() {
        return this.paletteRuntimeIds;
    }

    int[] paletteVoxelCounts() {
        return this.paletteVoxelCounts;
    }

    int payloadLength() {
        return this.payloadLength;
    }

    int estimatedFootprintBytes() {
        // Off-heap payload is referenced zero-copy; heap footprint is palette/offset arrays only.
        return 64 + (this.paletteRuntimeIds.length << 3)
                + (this.containerOffsets != null ? (this.containerOffsets.length << 1) : 0);
    }

    LcaModeABlockSection.DirectModeASectionRef toDirectModeASectionRef() {
        return new LcaModeABlockSection.DirectModeASectionRef(
                this.curveId,
                this.blockMode,
                this.flatBits,
                this.octreeMask64,
                this.paletteRuntimeIds.length,
                this.slabBuf,
                this.payloadOffset,
                this.payloadLength
        );
    }

    int getStateId(int pos12) {
        int yzx = pos12 & 4095;
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            return this.paletteRuntimeIds[readFlatOrdinal(yzx)];
        }
        if (((this.octreeMask64 >>> LcaSectionCodec.YZX_TO_OCTANT6[yzx]) & 1L) == 0L) {
            return this.paletteRuntimeIds[0];
        }
        if (this.paletteRuntimeIds.length <= MAX_CACHED_PALETTE) {
            ReadCache cache = READ_CACHE.get();
            byte[] grid = cache.lookup(this);
            if (grid != null) {
                return this.paletteRuntimeIds[grid[yzx] & 0xFF];
            }
            byte[] promoted = cache.tryPromote(this);
            if (promoted != null) {
                return this.paletteRuntimeIds[promoted[yzx] & 0xFF];
            }
        }
        return this.paletteRuntimeIds[queryDeltaRoaringOrdinal(yzx)];
    }

    int queryVoxelOrdinal(int yzx) {
        int pos = yzx & 4095;
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            return readFlatOrdinal(pos);
        }
        if (((this.octreeMask64 >>> LcaSectionCodec.YZX_TO_OCTANT6[pos]) & 1L) == 0L) {
            return 0;
        }
        return queryDeltaRoaringOrdinal(pos);
    }

    boolean testMaybeHas(IntPredicate stateIdPredicate) {
        int[] pal = this.paletteRuntimeIds;
        int[] counts = this.paletteVoxelCounts;
        for (int i = 0; i < pal.length; i++) {
            if (counts[i] > 0 && stateIdPredicate.test(pal[i])) {
                return true;
            }
        }
        return false;
    }

    void forEachStateCount(LcaSectionBacking.IntPairConsumer consumer) {
        int[] pal = this.paletteRuntimeIds;
        int[] counts = this.paletteVoxelCounts;
        for (int i = 0; i < pal.length; i++) {
            int c = counts[i];
            if (c > 0) {
                consumer.accept(pal[i], c);
            }
        }
    }

    void forEachMatchingCoordinate(IntPredicate stateIdPredicate, IntConsumer pos12Consumer) {
        int[] pal = this.paletteRuntimeIds;
        int[] counts = this.paletteVoxelCounts;
        int palLen = pal.length;
        boolean majorityMatches = counts[0] > 0 && stateIdPredicate.test(pal[0]);
        boolean anyMinorityMatch = false;
        boolean[] matches = new boolean[palLen];
        matches[0] = majorityMatches;
        for (int i = 1; i < palLen; i++) {
            if (counts[i] > 0 && stateIdPredicate.test(pal[i])) {
                matches[i] = true;
                anyMinorityMatch = true;
            }
        }
        if (!majorityMatches && !anyMinorityMatch) {
            return;
        }
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK || majorityMatches) {
            int[] voxels = LcaSectionBacking.THREAD_LOCAL_EPHEMERAL_VOXELS.get();
            materializeOrdinals(voxels);
            for (int pos = 0; pos < SECTION_VOXELS; pos++) {
                if (matches[voxels[pos]]) {
                    pos12Consumer.accept(pos);
                }
            }
            return;
        }
        int endLimit = this.payloadOffset + this.payloadLength;
        int minorityCount = palLen - 1;
        for (int m = 0; m < minorityCount; m++) {
            if (!matches[m + 1]) {
                continue;
            }
            int cStart = this.payloadOffset + (this.containerOffsets[m] & 0xFFFF);
            int cEnd = (m + 1 < minorityCount)
                    ? (this.payloadOffset + (this.containerOffsets[m + 1] & 0xFFFF))
                    : endLimit;
            emitContainerCoordinates(cStart, cEnd, pos12Consumer);
        }
    }

    boolean forEachNonMajorityVoxel(LcaSectionBacking.IntPairConsumer pos12AndStateIdConsumer) {
        int[] pal = this.paletteRuntimeIds;
        int palLen = pal.length;
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            for (int pos = 0; pos < SECTION_VOXELS; pos++) {
                int ord = readFlatOrdinal(pos);
                if (ord != 0) {
                    pos12AndStateIdConsumer.accept(pos, pal[ord]);
                }
            }
            return true;
        }
        int endLimit = this.payloadOffset + this.payloadLength;
        int minorityCount = palLen - 1;
        for (int m = 0; m < minorityCount; m++) {
            int stateId = pal[m + 1];
            int cStart = this.payloadOffset + (this.containerOffsets[m] & 0xFFFF);
            int cEnd = (m + 1 < minorityCount)
                    ? (this.payloadOffset + (this.containerOffsets[m + 1] & 0xFFFF))
                    : endLimit;
            emitContainerCoordinates(cStart, cEnd, pos -> pos12AndStateIdConsumer.accept(pos, stateId));
        }
        return true;
    }

    void materializeStateIds(int[] outYzx4096) {
        int[] pal = this.paletteRuntimeIds;
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            for (int pos = 0; pos < SECTION_VOXELS; pos++) {
                outYzx4096[pos] = pal[readFlatOrdinal(pos)];
            }
            return;
        }
        Arrays.fill(outYzx4096, 0, SECTION_VOXELS, pal[0]);
        int endLimit = this.payloadOffset + this.payloadLength;
        int minorityCount = pal.length - 1;
        for (int m = 0; m < minorityCount; m++) {
            int stateId = pal[m + 1];
            int cStart = this.payloadOffset + (this.containerOffsets[m] & 0xFFFF);
            int cEnd = (m + 1 < minorityCount)
                    ? (this.payloadOffset + (this.containerOffsets[m + 1] & 0xFFFF))
                    : endLimit;
            fillContainerInts(cStart, cEnd, outYzx4096, stateId);
        }
    }

    void materializeOrdinals(int[] outYzx4096) {
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            for (int pos = 0; pos < SECTION_VOXELS; pos++) {
                outYzx4096[pos] = readFlatOrdinal(pos);
            }
            return;
        }
        Arrays.fill(outYzx4096, 0, SECTION_VOXELS, 0);
        int endLimit = this.payloadOffset + this.payloadLength;
        int minorityCount = this.paletteRuntimeIds.length - 1;
        for (int m = 0; m < minorityCount; m++) {
            int ord = m + 1;
            int cStart = this.payloadOffset + (this.containerOffsets[m] & 0xFFFF);
            int cEnd = (m + 1 < minorityCount)
                    ? (this.payloadOffset + (this.containerOffsets[m + 1] & 0xFFFF))
                    : endLimit;
            fillContainerInts(cStart, cEnd, outYzx4096, ord);
        }
    }

    void materializeOrdinalBytes(byte[] outYzx4096) {
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            for (int pos = 0; pos < SECTION_VOXELS; pos++) {
                outYzx4096[pos] = (byte) readFlatOrdinal(pos);
            }
            return;
        }
        Arrays.fill(outYzx4096, 0, SECTION_VOXELS, (byte) 0);
        int endLimit = this.payloadOffset + this.payloadLength;
        int minorityCount = this.paletteRuntimeIds.length - 1;
        for (int m = 0; m < minorityCount; m++) {
            byte ord = (byte) (m + 1);
            int cStart = this.payloadOffset + (this.containerOffsets[m] & 0xFFFF);
            int cEnd = (m + 1 < minorityCount)
                    ? (this.payloadOffset + (this.containerOffsets[m + 1] & 0xFFFF))
                    : endLimit;
            fillContainerBytes(cStart, cEnd, outYzx4096, ord);
        }
    }

    int computeCleanVanillaSerializedSize(int globalPaletteBits) {
        int[] pal = this.paletteRuntimeIds;
        int distinctCount = pal.length;
        if (distinctCount <= 1) {
            return 1 + LcaSectionBacking.varIntByteSize(Math.max(0, distinctCount == 1 ? pal[0] : 0));
        }
        if (distinctCount <= 256) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinctCount - 1));
            int size = 1 + LcaSectionBacking.varIntByteSize(distinctCount);
            for (int id : pal) {
                size += LcaSectionBacking.varIntByteSize(id);
            }
            int entriesPerLong = 64 / bits;
            int wordCount = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            return size + (wordCount << 3);
        }
        int gBits = Math.max(9, globalPaletteBits);
        int entriesPerLong = 64 / gBits;
        int wordCount = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
        return 1 + (wordCount << 3);
    }

    void writeCleanVanillaPalettedContainer(int globalPaletteBits, LcaSectionBacking.VanillaWireSink sink) {
        int[] pal = this.paletteRuntimeIds;
        int distinctCount = pal.length;
        if (distinctCount <= 1) {
            sink.writeByte(0);
            sink.writeVarInt(Math.max(0, distinctCount == 1 ? pal[0] : 0));
            return;
        }
        int[] scratch = LcaSectionBacking.THREAD_LOCAL_EPHEMERAL_VOXELS.get();
        if (distinctCount <= 256) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(distinctCount - 1));
            sink.writeByte(bits);
            sink.writeVarInt(distinctCount);
            for (int id : pal) {
                sink.writeVarInt(id);
            }
            materializeOrdinals(scratch);
            writePackedWords(scratch, bits, sink);
            return;
        }
        int gBits = Math.max(9, globalPaletteBits);
        sink.writeByte(gBits);
        materializeStateIds(scratch);
        writePackedWords(scratch, gBits, sink);
    }

    byte[] buildCleanVanillaWireSlice(int globalPaletteBits) {
        int size = computeCleanVanillaSerializedSize(globalPaletteBits);
        byte[] out = new byte[size];
        int[] cursor = {0};
        writeCleanVanillaPalettedContainer(globalPaletteBits, new LcaSectionBacking.VanillaWireSink() {
            @Override
            public void writeByte(int value) {
                out[cursor[0]++] = (byte) value;
            }

            @Override
            public void writeVarInt(int value) {
                int v = value;
                while ((v & ~0x7F) != 0) {
                    out[cursor[0]++] = (byte) ((v & 0x7F) | 0x80);
                    v >>>= 7;
                }
                out[cursor[0]++] = (byte) v;
            }

            @Override
            public void writeLong(long value) {
                int p = cursor[0];
                out[p] = (byte) (value >>> 56);
                out[p + 1] = (byte) (value >>> 48);
                out[p + 2] = (byte) (value >>> 40);
                out[p + 3] = (byte) (value >>> 32);
                out[p + 4] = (byte) (value >>> 24);
                out[p + 5] = (byte) (value >>> 16);
                out[p + 6] = (byte) (value >>> 8);
                out[p + 7] = (byte) value;
                cursor[0] = p + 8;
            }
        });
        return cursor[0] == out.length ? out : Arrays.copyOf(out, cursor[0]);
    }

    byte[] resolveLazyCompactBaseFrame() {
        byte[] cached = this.lazyCompactBaseFrame;
        if (cached != null) {
            return cached;
        }
        int[] voxels = new int[SECTION_VOXELS];
        materializeStateIds(voxels);
        byte[] encoded = LcaSectionBacking.encodeCompactSectionFrame(voxels, 15, 0).frameBytes();
        this.lazyCompactBaseFrame = encoded;
        return encoded;
    }

    private static void writePackedWords(int[] values4096, int bits, LcaSectionBacking.VanillaWireSink sink) {
        int entriesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int idx = 0;
        while (idx < SECTION_VOXELS) {
            long word = 0L;
            int limit = Math.min(entriesPerLong, SECTION_VOXELS - idx);
            for (int j = 0; j < limit; j++) {
                word |= ((long) values4096[idx++] & mask) << (j * bits);
            }
            sink.writeLong(word);
        }
    }

    private int readFlatOrdinal(int yzx) {
        int bitPos = yzx * this.flatBits;
        int bytePos = this.payloadOffset + (bitPos >>> 3);
        int bitOff = bitPos & 7;
        int endLimit = this.payloadOffset + this.payloadLength;
        int word = this.slabBuf.get(bytePos) & 0xFF;
        if (bytePos + 1 < endLimit) {
            word |= (this.slabBuf.get(bytePos + 1) & 0xFF) << 8;
        }
        if (bytePos + 2 < endLimit) {
            word |= (this.slabBuf.get(bytePos + 2) & 0xFF) << 16;
        }
        return (word >>> bitOff) & ((1 << this.flatBits) - 1);
    }

    private int mapYzxToActiveCurveIdx(int yzx) {
        int rawCurve = this.yzxToCurve[yzx];
        if (this.chunkBase == null) {
            return rawCurve;
        }
        if (this.curveId == LcaSectionCodec.CURVE_H3) {
            int oct = rawCurve >>> 6;
            long belowMask = (1L << oct) - 1L;
            int activeOctIdx = Long.bitCount(this.octreeMask64 & belowMask);
            return (activeOctIdx << 6) | (rawCurve & 63);
        }
        int octY = rawCurve >>> 10;
        int belowSlabMask = (1 << octY) - 1;
        int activeSlabIdx = Integer.bitCount((this.activeSlabMask4 & 0x0F) & belowSlabMask);
        return (activeSlabIdx << 10) | (rawCurve & 1023);
    }

    private int queryDeltaRoaringOrdinal(int yzx) {
        int targetActive = mapYzxToActiveCurveIdx(yzx);
        if (targetActive < 0 || targetActive >= this.activeVoxels) {
            return 0;
        }
        int endLimit = this.payloadOffset + this.payloadLength;
        int minorityCount = this.paletteRuntimeIds.length - 1;
        for (int m = 0; m < minorityCount; m++) {
            int cStart = this.payloadOffset + (this.containerOffsets[m] & 0xFFFF);
            int cEnd = (m + 1 < minorityCount)
                    ? (this.payloadOffset + (this.containerOffsets[m + 1] & 0xFFFF))
                    : endLimit;
            if (containerContains(cStart, cEnd, targetActive)) {
                return m + 1;
            }
        }
        return 0;
    }

    private boolean containerContains(int pos, int limit, int target) {
        if (pos + 2 > limit) {
            return false;
        }
        ByteBuffer buf = this.slabBuf;
        int hdr = ((buf.get(pos) & 0xFF) << 8) | (buf.get(pos + 1) & 0xFF);
        pos += 2;
        int tag = (hdr >>> 14) & 3;
        int countOrIdx = hdr & 0x3FFF;
        return switch (tag) {
            case LcaSectionCodec.TAG_SINGLETON -> singletonContains(buf, pos, limit, countOrIdx, target);
            case LcaSectionCodec.TAG_BITMAP -> bitmapContains(buf, pos, limit, countOrIdx, target);
            case LcaSectionCodec.TAG_DELTA_ARRAY -> deltaArrayContains(buf, pos, limit, countOrIdx, target);
            default -> deltaRunContains(buf, pos, limit, countOrIdx, target);
        };
    }

    private static boolean singletonContains(ByteBuffer buf, int pos, int limit, int countOrIdx, int target) {
        int subTag = (countOrIdx >>> 12) & 3;
        int idx12 = countOrIdx & 0x0FFF;
        if (subTag == 0) {
            return idx12 == target;
        }
        if (subTag == 1) {
            if (pos >= limit) {
                return false;
            }
            int runLen = (buf.get(pos) & 0xFF) + 2;
            return target >= idx12 && target < idx12 + runLen;
        }
        if (subTag == 2) {
            if (pos + 2 > limit) {
                return false;
            }
            int runLen = (((buf.get(pos) & 0xFF) << 8) | (buf.get(pos + 1) & 0xFF)) + 2;
            return target >= idx12 && target < idx12 + runLen;
        }
        if (pos + 2 > limit) {
            return false;
        }
        int lens = buf.get(pos++) & 0xFF;
        int runLen0 = (lens >>> 4) + 1;
        int runLen1 = (lens & 0x0F) + 1;
        if (target >= idx12 && target < idx12 + runLen0) {
            return true;
        }
        int adjGap = buf.get(pos++) & 0xFF;
        if (adjGap >= 240) {
            if (pos >= limit) {
                return false;
            }
            adjGap = ((adjGap & 0x0F) << 8) | (buf.get(pos) & 0xFF);
        }
        int start1 = idx12 + runLen0 + 1 + adjGap;
        return target >= start1 && target < start1 + runLen1;
    }

    private boolean bitmapContains(ByteBuffer buf, int pos, int limit, int countOrIdx, int target) {
        int w = target >>> 6;
        int bit = target & 63;
        if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
            int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
            int wordOff = w << 3;
            if (wordOff + 8 > byteLen || pos + wordOff + 8 > limit) {
                return false;
            }
            long bits64 = readLongBE(buf, this.littleEndianBuf, pos + wordOff);
            return ((bits64 >>> bit) & 1L) != 0L;
        }
        if (pos + 8 > limit) {
            return false;
        }
        long wordMask64 = readLongBE(buf, this.littleEndianBuf, pos);
        if (((wordMask64 >>> w) & 1L) == 0L) {
            return false;
        }
        long belowMask = (w == 0) ? 0L : ((1L << w) - 1L);
        int wordIdx = Long.bitCount(wordMask64 & belowMask);
        int wordOff = pos + 8 + (wordIdx << 3);
        if (wordOff + 8 > limit) {
            return false;
        }
        long bits64 = readLongBE(buf, this.littleEndianBuf, wordOff);
        return ((bits64 >>> bit) & 1L) != 0L;
    }

    private static boolean deltaArrayContains(ByteBuffer buf, int pos, int limit, int countOrIdx, int target) {
        int cursor = 0;
        for (int r = 0; r < countOrIdx && pos < limit; r++) {
            int adjGap = buf.get(pos++) & 0xFF;
            if (adjGap >= 240) {
                if (pos >= limit) {
                    return false;
                }
                adjGap = ((adjGap & 0x0F) << 8) | (buf.get(pos++) & 0xFF);
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            if (cursor == target) {
                return true;
            }
            if (cursor > target) {
                return false;
            }
            cursor++;
        }
        return false;
    }

    private static boolean deltaRunContains(ByteBuffer buf, int pos, int limit, int countOrIdx, int target) {
        int subMode = (countOrIdx >>> 12) & 3;
        int runCount = countOrIdx & 0x0FFF;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        int cursor = 0;
        for (int r = 0; r < runCount && pos < limit; r++) {
            int tok = buf.get(pos++) & 0xFF;
            int adjGap = (tok >>> lBits) & gMax;
            int lenM1 = tok & lMax;
            if (adjGap == gMax) {
                if (pos >= limit) {
                    return false;
                }
                int b0 = buf.get(pos++) & 0xFF;
                if (b0 < 240) {
                    adjGap += b0;
                } else {
                    if (pos >= limit) {
                        return false;
                    }
                    adjGap += ((b0 & 0x0F) << 8) | (buf.get(pos++) & 0xFF);
                }
            }
            if (lenM1 == lMax) {
                if (pos < limit && buf.get(pos) >= 0) {
                    lenM1 += buf.get(pos++);
                } else {
                    int val = 0;
                    int shift = 0;
                    while (pos < limit) {
                        int b = buf.get(pos++) & 0xFF;
                        val |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    lenM1 += val;
                }
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            int runEnd = cursor + lenM1 + 1;
            if (target < cursor) {
                return false;
            }
            if (target < runEnd) {
                return true;
            }
            cursor = runEnd;
        }
        return false;
    }

    private void emitContainerCoordinates(int pos, int limit, IntConsumer consumer) {
        if (pos + 2 > limit) {
            return;
        }
        ByteBuffer buf = this.slabBuf;
        int hdr = ((buf.get(pos) & 0xFF) << 8) | (buf.get(pos + 1) & 0xFF);
        pos += 2;
        int tag = (hdr >>> 14) & 3;
        int countOrIdx = hdr & 0x3FFF;
        switch (tag) {
            case LcaSectionCodec.TAG_SINGLETON -> emitSingletonCoords(buf, pos, limit, countOrIdx, consumer);
            case LcaSectionCodec.TAG_BITMAP -> emitBitmapCoords(buf, pos, limit, countOrIdx, consumer);
            case LcaSectionCodec.TAG_DELTA_ARRAY -> emitDeltaArrayCoords(buf, pos, limit, countOrIdx, consumer);
            default -> emitDeltaRunCoords(buf, pos, limit, countOrIdx, consumer);
        }
    }

    private void emitSingletonCoords(ByteBuffer buf, int pos, int limit, int countOrIdx, IntConsumer consumer) {
        int subTag = (countOrIdx >>> 12) & 3;
        int idx12 = countOrIdx & 0x0FFF;
        if (subTag == 0) {
            consumer.accept(activeCurveToYzx(idx12));
            return;
        }
        if (subTag == 1) {
            if (pos >= limit) {
                return;
            }
            int runLen = (buf.get(pos) & 0xFF) + 2;
            emitRunCoords(idx12, idx12 + runLen, consumer);
            return;
        }
        if (subTag == 2) {
            if (pos + 2 > limit) {
                return;
            }
            int runLen = (((buf.get(pos) & 0xFF) << 8) | (buf.get(pos + 1) & 0xFF)) + 2;
            emitRunCoords(idx12, idx12 + runLen, consumer);
            return;
        }
        if (pos + 2 > limit) {
            return;
        }
        int lens = buf.get(pos++) & 0xFF;
        int runLen0 = (lens >>> 4) + 1;
        int runLen1 = (lens & 0x0F) + 1;
        int adjGap = buf.get(pos++) & 0xFF;
        if (adjGap >= 240 && pos < limit) {
            adjGap = ((adjGap & 0x0F) << 8) | (buf.get(pos) & 0xFF);
        }
        int end0 = idx12 + runLen0;
        int start1 = end0 + 1 + adjGap;
        emitRunCoords(idx12, end0, consumer);
        emitRunCoords(start1, start1 + runLen1, consumer);
    }

    private void emitBitmapCoords(ByteBuffer buf, int pos, int limit, int countOrIdx, IntConsumer consumer) {
        if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
            int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
            int wordCount = Math.min(byteLen >>> 3, (limit - pos) >>> 3);
            for (int w = 0; w < wordCount; w++) {
                long bits64 = readLongBE(buf, this.littleEndianBuf, pos);
                pos += 8;
                int baseC = w << 6;
                while (bits64 != 0L) {
                    int b = Long.numberOfTrailingZeros(bits64);
                    bits64 &= (bits64 - 1L);
                    consumer.accept(activeCurveToYzx(baseC | b));
                }
            }
            return;
        }
        if (pos + 8 > limit) {
            return;
        }
        long wordMask64 = readLongBE(buf, this.littleEndianBuf, pos);
        pos += 8;
        while (wordMask64 != 0L && pos + 8 <= limit) {
            int w = Long.numberOfTrailingZeros(wordMask64);
            wordMask64 &= (wordMask64 - 1L);
            long bits64 = readLongBE(buf, this.littleEndianBuf, pos);
            pos += 8;
            int baseC = w << 6;
            while (bits64 != 0L) {
                int b = Long.numberOfTrailingZeros(bits64);
                bits64 &= (bits64 - 1L);
                consumer.accept(activeCurveToYzx(baseC | b));
            }
        }
    }

    private void emitDeltaArrayCoords(ByteBuffer buf, int pos, int limit, int countOrIdx, IntConsumer consumer) {
        int cursor = 0;
        for (int r = 0; r < countOrIdx && pos < limit; r++) {
            int adjGap = buf.get(pos++) & 0xFF;
            if (adjGap >= 240 && pos < limit) {
                adjGap = ((adjGap & 0x0F) << 8) | (buf.get(pos++) & 0xFF);
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            consumer.accept(activeCurveToYzx(cursor++));
        }
    }

    private void emitDeltaRunCoords(ByteBuffer buf, int pos, int limit, int countOrIdx, IntConsumer consumer) {
        int subMode = (countOrIdx >>> 12) & 3;
        int runCount = countOrIdx & 0x0FFF;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        int cursor = 0;
        for (int r = 0; r < runCount && pos < limit; r++) {
            int tok = buf.get(pos++) & 0xFF;
            int adjGap = (tok >>> lBits) & gMax;
            int lenM1 = tok & lMax;
            if (adjGap == gMax && pos < limit) {
                int b0 = buf.get(pos++) & 0xFF;
                if (b0 < 240) {
                    adjGap += b0;
                } else if (pos < limit) {
                    adjGap += ((b0 & 0x0F) << 8) | (buf.get(pos++) & 0xFF);
                }
            }
            if (lenM1 == lMax && pos < limit) {
                if (buf.get(pos) >= 0) {
                    lenM1 += buf.get(pos++);
                } else {
                    int val = 0;
                    int shift = 0;
                    while (pos < limit) {
                        int b = buf.get(pos++) & 0xFF;
                        val |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        shift += 7;
                    }
                    lenM1 += val;
                }
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            int runEnd = cursor + lenM1 + 1;
            emitRunCoords(cursor, runEnd, consumer);
            cursor = runEnd;
        }
    }

    private void emitRunCoords(int start, int end, IntConsumer consumer) {
        int clampedEnd = Math.min(end, this.activeVoxels);
        for (int c = Math.max(0, start); c < clampedEnd; c++) {
            consumer.accept(activeCurveToYzx(c));
        }
    }

    private void fillContainerInts(int pos, int limit, int[] outYzx4096, int value) {
        emitContainerCoordinates(pos, limit, yzx -> outYzx4096[yzx] = value);
    }

    private void fillContainerBytes(int pos, int limit, byte[] outYzx4096, byte value) {
        emitContainerCoordinates(pos, limit, yzx -> outYzx4096[yzx] = value);
    }

    private int activeCurveToYzx(int activeCurveIdx) {
        int h = (this.chunkBase != null)
                ? (this.chunkBase[activeCurveIdx >>> this.chunkShift] | (activeCurveIdx & this.chunkMask))
                : activeCurveIdx;
        return this.curveToYzx[h & 4095];
    }

    private static long readLongBE(ByteBuffer buf, boolean littleEndian, int pos) {
        long v = buf.getLong(pos);
        return littleEndian ? Long.reverseBytes(v) : v;
    }
}

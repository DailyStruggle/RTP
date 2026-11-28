package io.github.dailystruggle.anvil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Immutable direct in-memory view over an unmodified standard LCA Mode A block section payload
 * ({@code BLOCK_MODE_DELTA_ROARING}, {@code BLOCK_MODE_DELTA_ROARING_FULL_MASK}, or
 * {@code BLOCK_MODE_FLAT_BITPACK} with {@code secExtFlags == 0}).
 *
 * <p>Provides true Disk = Memory parity with standard LCA Mode A (~451 B/section average) without
 * VarInt palette rewriting, without {@code int[4096]} warm-reload unpacking, and without
 * {@code CompactBaseIndex} sidecar allocations:
 * <ul>
 *   <li>{@link #queryVoxelOrdinal(int)} rejects majority octants in O(1) via {@code octreeMask64},
 *       answers {@code TAG_SINGLETON}, {@code TAG_BITMAP}, and {@code BLOCK_MODE_FLAT_BITPACK}
 *       in O(1), and performs sorted early-exit scans on {@code TAG_DELTA_ARRAY} / {@code TAG_RUNS}.</li>
 *   <li>{@link #countOrdinals(int[])} reads exact per-state counts directly from container headers
 *       and word popcounts without unpacking 4,096 voxels.</li>
 *   <li>{@link #forEachMatchingCoordinate(boolean[], IntConsumer)} decodes only minority containers
 *       whose palette state matches the predicate.</li>
 * </ul>
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-007
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaModeABlockSection {

    public static final int SECTION_VOXELS = LcaSectionCodec.SECTION_VOXELS;

    private static final VarHandle INT_LE_VIEW =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final ThreadLocal<ReadCache> READ_CACHE = ThreadLocal.withInitial(ReadCache::new);

    /** Releases the current thread's L1 Mode A read cache (including strong section refs). */
    public static void clearThreadLocalScratch() {
        READ_CACHE.remove();
    }

    /**
     * Fixed 4-slot per-thread L1 ordinal cache for immutable {@code DELTA_ROARING} sections
     * ({@code 16 KiB} of reusable {@code byte[4096]} buffers per worker thread, zero per-chunk
     * heap overhead). Uses a 2-slot recent-miss tracker so alternating section-pair sweeps
     * (e.g. Starlight {@code checkChunkEdge}) promote on their 2nd hit while 1-off queries never
     * pay materialization cost.
     */
    private static final class ReadCache {
        LcaModeABlockSection s0;
        LcaModeABlockSection s1;
        LcaModeABlockSection s2;
        LcaModeABlockSection s3;
        final byte[] b0 = new byte[SECTION_VOXELS];
        final byte[] b1 = new byte[SECTION_VOXELS];
        final byte[] b2 = new byte[SECTION_VOXELS];
        final byte[] b3 = new byte[SECTION_VOXELS];
        int nextEvict;
        LcaModeABlockSection recent0;
        LcaModeABlockSection recent1;
        final int[] scratchInt4096 = new int[SECTION_VOXELS];

        @SuppressWarnings("java:S1168")
        byte[] lookup(LcaModeABlockSection target) {
            if (s0 == target) return b0;
            if (s1 == target) return b1;
            if (s2 == target) return b2;
            if (s3 == target) return b3;
            return null;
        }

        @SuppressWarnings("java:S1168")
        byte[] tryPromote(LcaModeABlockSection target) {
            if (recent0 == target || recent1 == target) {
                if (recent0 == target) {
                    recent0 = null;
                } else {
                    recent1 = null;
                }
                int slot = nextEvict;
                nextEvict = (slot + 1) & 3;
                byte[] dst;
                if (slot == 0) {
                    s0 = target;
                    dst = b0;
                } else if (slot == 1) {
                    s1 = target;
                    dst = b1;
                } else if (slot == 2) {
                    s2 = target;
                    dst = b2;
                } else {
                    s3 = target;
                    dst = b3;
                }
                int[] tmp = scratchInt4096;
                target.materializeOrdinals(tmp);
                for (int i = 0; i < SECTION_VOXELS; i++) {
                    dst[i] = (byte) tmp[i];
                }
                return dst;
            }
            recent1 = recent0;
            recent0 = target;
            return null;
        }
    }

    private final byte curveId;
    private final byte blockMode;
    private final byte flatBits;
    private final byte activeSlabMask4;
    private final long octreeMask64;
    private final int[] paletteRuntimeIds;
    private final byte[] blockPayload;
    private final short[] containerOffsets;

    LcaModeABlockSection(
            int curveId,
            int blockMode,
            int flatBits,
            long octreeMask64,
            int[] paletteRuntimeIds,
            byte[] blockPayload,
            short[] containerOffsets
    ) {
        this.curveId = (byte) (curveId & 3);
        this.blockMode = (byte) (blockMode & 3);
        this.flatBits = (byte) flatBits;
        this.octreeMask64 = octreeMask64;
        this.paletteRuntimeIds = Objects.requireNonNull(paletteRuntimeIds, "paletteRuntimeIds");
        if (paletteRuntimeIds.length == 0) {
            throw new IllegalArgumentException("paletteRuntimeIds must not be empty");
        }
        this.blockPayload = Objects.requireNonNull(blockPayload, "blockPayload");
        this.containerOffsets = containerOffsets;
        int slabMask = 0;
        for (int octY = 0; octY < 4; octY++) {
            if (((octreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                slabMask |= (1 << octY);
            }
        }
        this.activeSlabMask4 = (byte) slabMask;
    }

    /**
     * Parses and validates the unmodified standard Mode A block payload from a section slice,
     * returning {@code null} for uniform sections, v3 {@code secExtFlags != 0} frames, or
     * truncated/malformed payloads.
     */
    static LcaModeABlockSection tryExtractFromChunkFrame(
            byte[] chunkFrame,
            int start,
            int limit,
            int[] palMap
    ) {
        if (chunkFrame == null || palMap == null || start < 0 || limit <= start + 2 || limit > chunkFrame.length) {
            return null;
        }
        int pos = start;
        int sectionHeader = chunkFrame[pos++] & 0xFF;
        int curveId = (sectionHeader >>> 6) & 3;
        int blockMode = (sectionHeader >>> 4) & 3;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_UNIFORM) {
            return null;
        }
        long octreeMask64;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            octreeMask64 = -1L;
        } else {
            if (pos + 8 > limit) {
                return null;
            }
            octreeMask64 = LcaSectionCodec.readLongBE(chunkFrame, pos);
            pos += 8;
        }
        try {
            int paletteLen = LcaSectionCodec.readVarInt(chunkFrame, pos, limit);
            pos += LcaSectionCodec.varIntLen(paletteLen);
            if (paletteLen < 2 || paletteLen > SECTION_VOXELS) {
                // paletteLen == 0 is the v3 secExtFlags escape (RankExcl / GrayBitplanes) -> fallback.
                return null;
            }
            int[] mappedIds = new int[paletteLen];
            for (int i = 0; i < paletteLen; i++) {
                int diskId = LcaSectionCodec.readVarInt(chunkFrame, pos, limit);
                pos += LcaSectionCodec.varIntLen(diskId);
                if (diskId < 0 || diskId >= palMap.length) {
                    return null;
                }
                int runtimeId = palMap[diskId];
                if (runtimeId < 0 || runtimeId > LcaSectionBacking.MAX_STATE_ID_19) {
                    return null;
                }
                mappedIds[i] = runtimeId;
            }
            return sliceValidatedPayload(chunkFrame, pos, limit, curveId, blockMode, octreeMask64, mappedIds);
        } catch (CorruptRegionEntryException ex) {
            return null;
        }
    }

    /**
     * Encodes 4,096 runtime block state IDs into a standard LCA Mode A {@link LcaModeABlockSection}
     * (identical to standard Mode A disk encoding: Delta-Roaring + optimal curve, with FlatBitPack
     * used only when smaller). Returns {@code null} if the section is uniform.
     */
    public static LcaModeABlockSection encodeFromRuntimeVoxels(int[] voxels4096) {
        if (voxels4096 == null || voxels4096.length < SECTION_VOXELS) {
            return null;
        }
        int first = voxels4096[0];
        boolean uniform = true;
        for (int i = 1; i < SECTION_VOXELS; i++) {
            if (voxels4096[i] != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            return null;
        }

        // Build distinct palette + frequencies (small linear scan for common <=16-state sections,
        // falling back to open-addressed table for high-cardinality sections).
        int[] uniqueIds = new int[32];
        int[] freqs = new int[32];
        int[] rawIdx = new int[SECTION_VOXELS];
        int uniqueCount = 0;

        int[] hashKeys = null;
        int[] hashVals = null;
        int hashMask = 0;

        for (int i = 0; i < SECTION_VOXELS; i++) {
            int st = voxels4096[i];
            int found = -1;
            if (hashKeys == null) {
                for (int j = 0; j < uniqueCount; j++) {
                    if (uniqueIds[j] == st) {
                        found = j;
                        break;
                    }
                }
                if (found < 0) {
                    if (uniqueCount == uniqueIds.length) {
                        // Switch to hash table for >32 distinct states
                        int cap = 128;
                        hashKeys = new int[cap];
                        hashVals = new int[cap];
                        Arrays.fill(hashKeys, Integer.MIN_VALUE);
                        hashMask = cap - 1;
                        for (int j = 0; j < uniqueCount; j++) {
                            int slot = mix32(uniqueIds[j]) & hashMask;
                            while (hashKeys[slot] != Integer.MIN_VALUE) {
                                slot = (slot + 1) & hashMask;
                            }
                            hashKeys[slot] = uniqueIds[j];
                            hashVals[slot] = j;
                        }
                        uniqueIds = Arrays.copyOf(uniqueIds, uniqueIds.length << 1);
                        freqs = Arrays.copyOf(freqs, freqs.length << 1);
                    } else {
                        found = uniqueCount++;
                        uniqueIds[found] = st;
                    }
                }
            }
            if (found < 0 && hashKeys != null) {
                int slot = mix32(st) & hashMask;
                while (hashKeys[slot] != Integer.MIN_VALUE) {
                    if (hashKeys[slot] == st) {
                        found = hashVals[slot];
                        break;
                    }
                    slot = (slot + 1) & hashMask;
                }
                if (found < 0) {
                    if ((uniqueCount + 1) * 2 >= hashKeys.length) {
                        int cap = hashKeys.length << 1;
                        int[] nk = new int[cap];
                        int[] nv = new int[cap];
                        Arrays.fill(nk, Integer.MIN_VALUE);
                        int nm = cap - 1;
                        for (int j = 0; j < uniqueCount; j++) {
                            int ns = mix32(uniqueIds[j]) & nm;
                            while (nk[ns] != Integer.MIN_VALUE) {
                                ns = (ns + 1) & nm;
                            }
                            nk[ns] = uniqueIds[j];
                            nv[ns] = j;
                        }
                        hashKeys = nk;
                        hashVals = nv;
                        hashMask = nm;
                    }
                    if (uniqueCount == uniqueIds.length) {
                        uniqueIds = Arrays.copyOf(uniqueIds, uniqueIds.length << 1);
                        freqs = Arrays.copyOf(freqs, freqs.length << 1);
                    }
                    found = uniqueCount++;
                    uniqueIds[found] = st;
                    int ins = mix32(st) & hashMask;
                    while (hashKeys[ins] != Integer.MIN_VALUE) {
                        ins = (ins + 1) & hashMask;
                    }
                    hashKeys[ins] = st;
                    hashVals[ins] = found;
                }
            }
            freqs[found]++;
            rawIdx[i] = found;
        }
        if (uniqueCount <= 1) {
            return null;
        }

        // Sort palette descending by frequency (majority at ordinal 0)
        final int[] finalFreqs = freqs;
        final int[] finalUniqueIds = uniqueIds;
        Integer[] order = new Integer[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = Integer.compare(finalFreqs[b], finalFreqs[a]);
            return cmp != 0 ? cmp : Integer.compare(finalUniqueIds[a], finalUniqueIds[b]);
        });
        int[] sortedIds = new int[uniqueCount];
        int[] oldToNew = new int[uniqueCount];
        for (int i = 0; i < uniqueCount; i++) {
            int old = order[i];
            sortedIds[i] = uniqueIds[old];
            oldToNew[old] = i;
        }
        int[] yzxOrd = new int[SECTION_VOXELS];
        for (int i = 0; i < SECTION_VOXELS; i++) {
            yzxOrd[i] = oldToNew[rawIdx[i]];
        }

        byte[] encoded = new byte[SECTION_VOXELS * 2 + 64];
        int endPos = LcaSectionCodec.encodeSectionBlockContainersV2(yzxOrd, uniqueCount, encoded, 0);
        if (endPos <= 1) {
            return null;
        }
        int hdr = encoded[0] & 0xFF;
        int curveId = (hdr >>> 6) & 3;
        int blockMode = (hdr >>> 4) & 3;
        int payloadStart = 1;
        long octreeMask64;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            octreeMask64 = -1L;
        } else {
            octreeMask64 = LcaSectionCodec.readLongBE(encoded, 1);
            payloadStart = 9;
        }
        return sliceValidatedPayload(encoded, payloadStart, endPos, curveId, blockMode, octreeMask64, sortedIds);
    }

    /**
     * Wraps and validates a pre-encoded standard LCA Mode A Delta-Roaring block payload
     * ({@link LcaSectionCodec#BLOCK_MODE_DELTA_ROARING} or
     * {@link LcaSectionCodec#BLOCK_MODE_DELTA_ROARING_FULL_MASK}), such as one emitted directly by
     * a GPU VRAM packing kernel, with zero CPU re-encoding ({@code anvil-api-ADR-003},
     * {@code goldleaf-ADR-005}). Returns {@code null} if the payload is invalid or truncated.
     */
    public static LcaModeABlockSection fromPreEncodedDeltaRoaring(
            byte[] buf,
            int offset,
            int length,
            int curveId,
            int blockMode,
            long octreeMask64,
            int[] paletteRuntimeIds
    ) {
        if (buf == null || paletteRuntimeIds == null || paletteRuntimeIds.length < 2
                || offset < 0 || length <= 0 || offset + length > buf.length) {
            return null;
        }
        if (blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING
                && blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            return null;
        }
        long effectiveMask = (blockMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK)
                ? -1L
                : octreeMask64;
        return sliceValidatedPayload(
                buf, offset, offset + length, curveId & 3, blockMode, effectiveMask, paletteRuntimeIds.clone());
    }

    private static LcaModeABlockSection sliceValidatedPayload(
            byte[] buf,
            int payloadStart,
            int limit,
            int curveId,
            int blockMode,
            long octreeMask64,
            int[] paletteRuntimeIds
    ) {
        int paletteLen = paletteRuntimeIds.length;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
            int flatByteCount = (SECTION_VOXELS * flatBits + 7) >>> 3;
            if (flatByteCount <= 0 || payloadStart + flatByteCount > limit) {
                return null;
            }
            byte[] payload = Arrays.copyOfRange(buf, payloadStart, payloadStart + flatByteCount);
            return new LcaModeABlockSection(curveId, blockMode, flatBits, octreeMask64, paletteRuntimeIds, payload, null);
        }
        if (blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING
                && blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            return null;
        }

        int activeVoxels = computeActiveVoxels(curveId, octreeMask64);
        int minorityCount = paletteLen - 1;
        short[] offsets = new short[minorityCount];
        int pos = payloadStart;
        try {
            for (int p = 1; p < paletteLen; p++) {
                int relOff = pos - payloadStart;
                if (relOff < 0 || relOff > Short.MAX_VALUE) {
                    return null;
                }
                offsets[p - 1] = (short) relOff;
                pos = skipMinorityContainer(buf, pos, limit, activeVoxels);
                if (pos < 0 || pos > limit) {
                    return null;
                }
            }
        } catch (CorruptRegionEntryException ex) {
            return null;
        }
        int payloadLen = pos - payloadStart;
        if (payloadLen <= 0 || payloadLen > Short.MAX_VALUE) {
            return null;
        }
        byte[] payload = Arrays.copyOfRange(buf, payloadStart, pos);
        return new LcaModeABlockSection(curveId, blockMode, 0, octreeMask64, paletteRuntimeIds, payload, offsets);
    }

    public int curveId() {
        return this.curveId & 3;
    }

    public int blockMode() {
        return this.blockMode & 3;
    }

    public int flatBits() {
        return this.flatBits & 0xFF;
    }

    public long octreeMask64() {
        return this.octreeMask64;
    }

    public int paletteLength() {
        return this.paletteRuntimeIds.length;
    }

    public int paletteRuntimeId(int ordinal) {
        return this.paletteRuntimeIds[ordinal];
    }

    public int[] paletteRuntimeIds() {
        return this.paletteRuntimeIds;
    }

    public int majorityRuntimeId() {
        return this.paletteRuntimeIds[0];
    }

    public int payloadByteLength() {
        return this.blockPayload.length;
    }

    byte[] blockPayloadRaw() {
        return this.blockPayload;
    }

    /**
     * Serializes this already-encoded Mode A block section directly into a standalone `.lca`
     * {@code SectionFrame} byte array ({@code skyMode = LIGHT_MODE_ABSENT},
     * {@code blkMode = LIGHT_MODE_ABSENT}) with zero voxel decompression or re-encoding
     * ({@code anvil-api-ADR-003}, {@code goldleaf-ADR-005}).
     */
    public byte[] toWireSectionFrameBytes() {
        int palLen = this.paletteRuntimeIds.length;
        int cId = this.curveId & 3;
        int bMode = this.blockMode & 3;
        int palVarIntBytes = LcaSectionCodec.varIntLen(palLen);
        for (int i = 0; i < palLen; i++) {
            palVarIntBytes += LcaSectionCodec.varIntLen(this.paletteRuntimeIds[i]);
        }
        boolean hasMask = bMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK;
        int totalLen = 1 + (hasMask ? 8 : 0) + palVarIntBytes + this.blockPayload.length;
        byte[] out = new byte[totalLen];
        int pos = 0;
        out[pos++] = (byte) ((cId << 6) | (bMode << 4));
        if (hasMask) {
            long mask = this.octreeMask64;
            out[pos++] = (byte) (mask >>> 56);
            out[pos++] = (byte) (mask >>> 48);
            out[pos++] = (byte) (mask >>> 40);
            out[pos++] = (byte) (mask >>> 32);
            out[pos++] = (byte) (mask >>> 24);
            out[pos++] = (byte) (mask >>> 16);
            out[pos++] = (byte) (mask >>> 8);
            out[pos++] = (byte) mask;
        }
        pos = writeVarInt(out, pos, palLen);
        for (int i = 0; i < palLen; i++) {
            pos = writeVarInt(out, pos, this.paletteRuntimeIds[i]);
        }
        System.arraycopy(this.blockPayload, 0, out, pos, this.blockPayload.length);
        return out;
    }

    /**
     * Emits a standard `.lca` {@code BLOCK_MODE_FLAT_BITPACK} ({@code CURVE_YZX}) {@code SectionFrame}
     * directly from Minecraft's in-memory {@code SimpleBitStorage.getRaw()} {@code long[]} and
     * palette runtime IDs — avoiding 4,096-voxel {@code BlockState} unpacking, Hilbert curve search,
     * and Delta-Roaring container encoding ({@code goldleaf-ADR-005}, {@code S-005}).
     *
     * <p>Returns {@code null} if inputs are out of bounds so callers can fail closed to standard
     * encoding.</p>
     */
    @SuppressWarnings("java:S1168")
    public static byte[] encodeFlatBitpackFromSimpleBitStorage(
            int[] paletteRuntimeIds,
            int storageBits,
            long[] storageRaw
    ) {
        if (paletteRuntimeIds == null || storageRaw == null) {
            return null;
        }
        int palLen = paletteRuntimeIds.length;
        if (palLen < 2 || palLen > 256 || storageBits < 4 || storageBits > 8) {
            return null;
        }
        int entriesPerLong = 64 / storageBits;
        int expectedLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
        if (storageRaw.length < expectedLongs) {
            return null;
        }
        int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(palLen - 1));
        int flatByteCount = (SECTION_VOXELS * flatBits + 7) >>> 3;
        int palVarIntBytes = LcaSectionCodec.varIntLen(palLen);
        for (int i = 0; i < palLen; i++) {
            int id = paletteRuntimeIds[i];
            if (id < 0 || id > LcaSectionBacking.MAX_STATE_ID_19) {
                return null;
            }
            palVarIntBytes += LcaSectionCodec.varIntLen(id);
        }
        int headerAndMask = 1 + 8 + palVarIntBytes;
        byte[] out = new byte[headerAndMask + flatByteCount];
        out[0] = (byte) ((LcaSectionCodec.CURVE_YZX << 6) | (LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK << 4));
        int pos = 9;
        pos = writeVarInt(out, pos, palLen);
        for (int i = 0; i < palLen; i++) {
            pos = writeVarInt(out, pos, paletteRuntimeIds[i]);
        }

        long octreeMask64 = 0L;
        if (storageBits == 4 && flatBits == 4) {
            // Exact 1:1 little-endian layout (16 4-bit nibbles per long = 8 bytes per long, 256 longs = 2,048 B).
            int dst = pos;
            for (int w = 0; w < 256; w++) {
                long word = storageRaw[w];
                if (word != 0L) {
                    int baseIdx = w << 4;
                    if ((word & 0xFFFFL) != 0L) octreeMask64 |= 1L << LcaSectionCodec.YZX_TO_OCTANT6[baseIdx];
                    if ((word & 0xFFFF0000L) != 0L) octreeMask64 |= 1L << LcaSectionCodec.YZX_TO_OCTANT6[baseIdx | 4];
                    if ((word & 0xFFFF00000000L) != 0L) octreeMask64 |= 1L << LcaSectionCodec.YZX_TO_OCTANT6[baseIdx | 8];
                    if ((word & 0xFFFF000000000000L) != 0L) octreeMask64 |= 1L << LcaSectionCodec.YZX_TO_OCTANT6[baseIdx | 12];
                    if (palLen < 16) {
                        for (int k = 0; k < 16; k++) {
                            if (((int) (word >>> (k << 2)) & 0xF) >= palLen) {
                                return null;
                            }
                        }
                    }
                }
                out[dst] = (byte) word;
                out[dst + 1] = (byte) (word >>> 8);
                out[dst + 2] = (byte) (word >>> 16);
                out[dst + 3] = (byte) (word >>> 24);
                out[dst + 4] = (byte) (word >>> 32);
                out[dst + 5] = (byte) (word >>> 40);
                out[dst + 6] = (byte) (word >>> 48);
                out[dst + 7] = (byte) (word >>> 56);
                dst += 8;
            }
        } else if (storageBits == 8 && flatBits == 8) {
            // Exact 1:1 little-endian layout (8 bytes per long, 512 longs = 4,096 B).
            int dst = pos;
            for (int w = 0; w < 512; w++) {
                long word = storageRaw[w];
                if (word != 0L) {
                    int baseIdx = w << 3;
                    for (int k = 0; k < 8; k++) {
                        int val = (int) (word >>> (k << 3)) & 0xFF;
                        if (val >= palLen) {
                            return null;
                        }
                        if (val != 0) {
                            octreeMask64 |= 1L << LcaSectionCodec.YZX_TO_OCTANT6[baseIdx + k];
                        }
                    }
                }
                out[dst] = (byte) word;
                out[dst + 1] = (byte) (word >>> 8);
                out[dst + 2] = (byte) (word >>> 16);
                out[dst + 3] = (byte) (word >>> 24);
                out[dst + 4] = (byte) (word >>> 32);
                out[dst + 5] = (byte) (word >>> 40);
                out[dst + 6] = (byte) (word >>> 48);
                out[dst + 7] = (byte) (word >>> 56);
                dst += 8;
            }
        } else {
            // Direct bit-repack from SimpleBitStorage longs into tightly packed flatBits bytes
            // (e.g. 4-bit storage -> 1/2/3-bit wire payload = 512/1024/1536 B, zero BlockState lookups).
            long mask = (1L << storageBits) - 1L;
            int vox = 0;
            int bitPos = 0;
            for (int w = 0; w < expectedLongs && vox < SECTION_VOXELS; w++) {
                long word = storageRaw[w];
                int lim = Math.min(SECTION_VOXELS, vox + entriesPerLong);
                if (word == 0L) {
                    bitPos += (lim - vox) * flatBits;
                    vox = lim;
                    continue;
                }
                while (vox < lim) {
                    int val = (int) (word & mask);
                    word >>>= storageBits;
                    if (val >= palLen) {
                        return null;
                    }
                    if (val != 0) {
                        octreeMask64 |= 1L << LcaSectionCodec.YZX_TO_OCTANT6[vox];
                        int byteIdx = pos + (bitPos >>> 3);
                        int bitOff = bitPos & 7;
                        int shifted = val << bitOff;
                        out[byteIdx] |= (byte) shifted;
                        if (bitOff + flatBits > 8) {
                            out[byteIdx + 1] |= (byte) (shifted >>> 8);
                        }
                    }
                    bitPos += flatBits;
                    vox++;
                }
            }
        }

        // Write computed CBIT T6b octreeMask64 at offset 1..8 (big-endian).
        out[1] = (byte) (octreeMask64 >>> 56);
        out[2] = (byte) (octreeMask64 >>> 48);
        out[3] = (byte) (octreeMask64 >>> 40);
        out[4] = (byte) (octreeMask64 >>> 32);
        out[5] = (byte) (octreeMask64 >>> 24);
        out[6] = (byte) (octreeMask64 >>> 16);
        out[7] = (byte) (octreeMask64 >>> 8);
        out[8] = (byte) octreeMask64;
        return out;
    }

    private static int writeVarInt(byte[] out, int pos, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[pos++] = (byte) v;
        return pos;
    }

    /**
     * Queries the palette ordinal at YZX index {@code yzx} ({@code (y << 8) | (z << 4) | x})
     * directly over the unmodified Mode A payload without unpacking 4,096 voxels.
     */
    public int queryVoxelOrdinal(int yzx) {
        int pos12 = yzx & 0xFFF;
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            return readFlatOrdinal(pos12);
        }
        // O(1) majority rejection via octreeMask64 (valid across all 4 curves per CBIT T6b)
        if (((this.octreeMask64 >>> LcaSectionCodec.YZX_TO_OCTANT6[pos12]) & 1L) == 0L) {
            return 0;
        }
        if (this.paletteRuntimeIds.length <= 256) {
            ReadCache cache = READ_CACHE.get();
            byte[] cached = cache.lookup(this);
            if (cached != null) {
                return cached[pos12] & 0xFF;
            }
            byte[] promoted = cache.tryPromote(this);
            if (promoted != null) {
                return promoted[pos12] & 0xFF;
            }
        }
        int activeIdx = mapYzxToActiveCurveIdx(pos12);
        if (activeIdx < 0) {
            return 0;
        }
        byte[] buf = this.blockPayload;
        int limit = buf.length;
        short[] offsets = this.containerOffsets;
        int minorityCount = offsets.length;
        for (int i = 0; i < minorityCount; i++) {
            int off = offsets[i] & 0x7FFF;
            if (containerContains(buf, off, limit, activeIdx)) {
                return i + 1;
            }
        }
        return 0;
    }

    public int queryVoxelRuntimeId(int yzx) {
        int len = this.paletteRuntimeIds.length;
        if (len == 0) {
            throw new IllegalStateException("paletteRuntimeIds must not be empty");
        }
        int ord = queryVoxelOrdinal(yzx);
        return this.paletteRuntimeIds[ord >= 0 && ord < len ? ord : 0];
    }

    /**
     * Computes exact per-palette-ordinal voxel counts directly from Mode A container headers
     * and word popcounts, without unpacking {@code int[4096]}.
     */
    public void countOrdinals(int[] outCounts) {
        int palLen = this.paletteRuntimeIds.length;
        if (outCounts == null || outCounts.length < palLen) {
            throw new IllegalArgumentException("outCounts length < paletteLength");
        }
        Arrays.fill(outCounts, 0, palLen, 0);
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            countFlatOrdinals(outCounts, palLen);
            return;
        }
        byte[] buf = this.blockPayload;
        int limit = buf.length;
        short[] offsets = this.containerOffsets;
        int minoritySum = 0;
        for (int i = 0; i < offsets.length; i++) {
            int c = countContainer(buf, offsets[i] & 0x7FFF, limit);
            outCounts[i + 1] = c;
            minoritySum += c;
        }
        outCounts[0] = Math.max(0, SECTION_VOXELS - minoritySum);
    }

    /**
     * Invokes {@code yzxConsumer} for every voxel whose palette ordinal satisfies
     * {@code matchingOrdinal[ord] == true}. When {@code matchingOrdinal[0] == false} (e.g.
     * {@code isRandomlyTicking()}), only the matching minority containers are decoded.
     */
    public void forEachMatchingCoordinate(boolean[] matchingOrdinal, IntConsumer yzxConsumer) {
        Objects.requireNonNull(matchingOrdinal, "matchingOrdinal");
        Objects.requireNonNull(yzxConsumer, "yzxConsumer");
        int palLen = this.paletteRuntimeIds.length;
        if (matchingOrdinal.length < palLen) {
            return;
        }
        boolean anyMatch = false;
        for (int i = 0; i < palLen; i++) {
            if (matchingOrdinal[i]) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) {
            return;
        }
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK || matchingOrdinal[0]) {
            int[] ordinals = new int[SECTION_VOXELS];
            materializeOrdinals(ordinals);
            for (int yzx = 0; yzx < SECTION_VOXELS; yzx++) {
                int ord = ordinals[yzx];
                if (ord < palLen && matchingOrdinal[ord]) {
                    yzxConsumer.accept(yzx);
                }
            }
            return;
        }

        int cId = this.curveId & 3;
        int[] curveToYzx = LcaSectionCodec.CURVE_TO_YZX[cId];
        int[] chunkBase = buildChunkBase(cId, this.octreeMask64);
        int chunkShift = (cId == LcaSectionCodec.CURVE_H3) ? 6 : 10;
        int chunkMask = (1 << chunkShift) - 1;

        byte[] buf = this.blockPayload;
        int limit = buf.length;
        short[] offsets = this.containerOffsets;
        for (int i = 0; i < offsets.length; i++) {
            if (!matchingOrdinal[i + 1]) {
                continue;
            }
            emitContainerCoordinates(
                    buf, offsets[i] & 0x7FFF, limit, curveToYzx, chunkBase, chunkShift, chunkMask, yzxConsumer);
        }
    }

    /**
     * Materializes all 4,096 YZX palette ordinals into {@code outYzx4096}.
     */
    public void materializeOrdinals(int[] outYzx4096) {
        if (outYzx4096 == null || outYzx4096.length < SECTION_VOXELS) {
            throw new IllegalArgumentException("outYzx4096 length < 4096");
        }
        if (this.blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            int palLen = this.paletteRuntimeIds.length;
            for (int i = 0; i < SECTION_VOXELS; i++) {
                int ord = readFlatOrdinal(i);
                outYzx4096[i] = ord < palLen ? ord : 0;
            }
            return;
        }
        Arrays.fill(outYzx4096, 0, SECTION_VOXELS, 0);
        int cId = this.curveId & 3;
        int[] curveToYzx = LcaSectionCodec.CURVE_TO_YZX[cId];
        int[] chunkBase = buildChunkBase(cId, this.octreeMask64);
        int chunkShift = (cId == LcaSectionCodec.CURVE_H3) ? 6 : 10;
        int chunkMask = (1 << chunkShift) - 1;
        byte[] buf = this.blockPayload;
        int limit = buf.length;
        short[] offsets = this.containerOffsets;
        for (int i = 0; i < offsets.length; i++) {
            int ord = i + 1;
            applyContainerToOrdinals(
                    buf, offsets[i] & 0x7FFF, limit, ord, curveToYzx, chunkBase, chunkShift, chunkMask, outYzx4096);
        }
    }

    private int readFlatOrdinal(int index) {
        int bits = this.flatBits & 0xFF;
        if (bits == 1) {
            return (this.blockPayload[index >>> 3] >>> (index & 7)) & 1;
        }
        if (bits == 2) {
            return (this.blockPayload[index >>> 2] >>> ((index & 3) << 1)) & 3;
        }
        if (bits == 4) {
            return (this.blockPayload[index >>> 1] >>> ((index & 1) << 2)) & 0xF;
        }
        if (bits == 8) {
            return this.blockPayload[index] & 0xFF;
        }
        int bitPos = index * bits;
        int byteIdx = bitPos >>> 3;
        int bitOff = bitPos & 7;
        int mask = (1 << bits) - 1;
        int word;
        if (byteIdx + 4 <= this.blockPayload.length) {
            word = (int) INT_LE_VIEW.get(this.blockPayload, byteIdx);
        } else {
            word = (this.blockPayload[byteIdx] & 0xFF);
            if (byteIdx + 1 < this.blockPayload.length) {
                word |= (this.blockPayload[byteIdx + 1] & 0xFF) << 8;
            }
            if (byteIdx + 2 < this.blockPayload.length) {
                word |= (this.blockPayload[byteIdx + 2] & 0xFF) << 16;
            }
        }
        return (word >>> bitOff) & mask;
    }

    private void countFlatOrdinals(int[] outCounts, int palLen) {
        int bits = this.flatBits & 0xFF;
        if (bits == 1) {
            int ones = 0;
            byte[] flat = this.blockPayload;
            for (int off = 0; off < 512; off += 4) {
                ones += Integer.bitCount((int) INT_LE_VIEW.get(flat, off));
            }
            outCounts[0] = SECTION_VOXELS - ones;
            if (palLen > 1) {
                outCounts[1] = ones;
            }
            return;
        }
        if (bits == 2) {
            int c0 = 0, c1 = 0, c2 = 0, c3 = 0;
            byte[] flat = this.blockPayload;
            for (int off = 0; off < 1024; off += 4) {
                int w = (int) INT_LE_VIEW.get(flat, off);
                int lo = w & 0x55555555;
                int hi = (w >>> 1) & 0x55555555;
                c0 += Integer.bitCount((~lo & ~hi) & 0x55555555);
                c1 += Integer.bitCount(lo & ~hi);
                c2 += Integer.bitCount(~lo & hi);
                c3 += Integer.bitCount(lo & hi);
            }
            outCounts[0] = c0;
            if (palLen > 1) outCounts[1] = c1;
            if (palLen > 2) outCounts[2] = c2;
            if (palLen > 3) outCounts[3] = c3;
            return;
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int ord = readFlatOrdinal(i);
            outCounts[ord < palLen ? ord : 0]++;
        }
    }

    private int mapYzxToActiveCurveIdx(int yzx) {
        int cId = this.curveId & 3;
        int curvePos = LcaSectionCodec.YZX_TO_CURVE[cId][yzx];
        if (this.octreeMask64 == -1L || this.octreeMask64 == 0L || cId == LcaSectionCodec.CURVE_XZY) {
            return curvePos;
        }
        if (cId == LcaSectionCodec.CURVE_H3) {
            int oct = curvePos >>> 6;
            if (((this.octreeMask64 >>> oct) & 1L) == 0L) {
                return -1;
            }
            long lowerMask = (1L << oct) - 1L;
            return (Long.bitCount(this.octreeMask64 & lowerMask) << 6) | (curvePos & 63);
        }
        // CURVE_YZX or CURVE_Y_H2: Y-slab compaction (4 slabs of 1,024 voxels)
        int slabMask = this.activeSlabMask4 & 0x0F;
        if (slabMask == 0x0F) {
            return curvePos;
        }
        int octY = curvePos >>> 10;
        if ((slabMask & (1 << octY)) == 0) {
            return -1;
        }
        int slabRank = Integer.bitCount(slabMask & ((1 << octY) - 1));
        return (slabRank << 10) | (curvePos & 1023);
    }

    private static int computeActiveVoxels(int curveId, long octreeMask64) {
        if (octreeMask64 == -1L || octreeMask64 == 0L) {
            return SECTION_VOXELS;
        }
        if (curveId == LcaSectionCodec.CURVE_H3) {
            return Long.bitCount(octreeMask64) << 6;
        }
        if (curveId == LcaSectionCodec.CURVE_YZX || curveId == LcaSectionCodec.CURVE_Y_H2) {
            int sCount = 0;
            for (int octY = 0; octY < 4; octY++) {
                if (((octreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                    sCount++;
                }
            }
            return sCount < 4 ? (sCount << 10) : SECTION_VOXELS;
        }
        return SECTION_VOXELS;
    }

    @SuppressWarnings("java:S1168")
    private static int[] buildChunkBase(int curveId, long octreeMask64) {
        if (octreeMask64 == -1L || octreeMask64 == 0L) {
            return null;
        }
        if (curveId == LcaSectionCodec.CURVE_H3) {
            int k = Long.bitCount(octreeMask64);
            int[] base = new int[k];
            long m = octreeMask64;
            int idx = 0;
            while (m != 0L) {
                base[idx++] = Long.numberOfTrailingZeros(m) << 6;
                m &= (m - 1L);
            }
            return base;
        }
        if (curveId == LcaSectionCodec.CURVE_YZX || curveId == LcaSectionCodec.CURVE_Y_H2) {
            int sCount = 0;
            for (int octY = 0; octY < 4; octY++) {
                if (((octreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                    sCount++;
                }
            }
            if (sCount < 4) {
                int[] base = new int[sCount];
                int idx = 0;
                for (int octY = 0; octY < 4; octY++) {
                    if (((octreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                        base[idx++] = octY << 10;
                    }
                }
                return base;
            }
        }
        return null;
    }

    private static int skipMinorityContainer(byte[] buf, int pos, int limit, int activeVoxels)
            throws CorruptRegionEntryException {
        if (pos + 2 > limit) {
            return -1;
        }
        int word16 = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        int tag = (word16 >>> 14) & 3;
        int countOrIdx = word16 & 0x3FFF;
        if (tag == LcaSectionCodec.TAG_SINGLETON) {
            int subTag = (countOrIdx >>> 12) & 3;
            if (subTag == 0) {
                return (countOrIdx & 0x0FFF) < activeVoxels ? pos : -1;
            }
            if (subTag == 1) {
                return pos + 1 <= limit ? pos + 1 : -1;
            }
            if (subTag == 2) {
                return pos + 2 <= limit ? pos + 2 : -1;
            }
            if (pos + 2 > limit) {
                return -1;
            }
            pos++;
            int b0 = buf[pos++] & 0xFF;
            if (b0 >= 240) {
                if (pos >= limit) {
                    return -1;
                }
                pos++;
            }
            return pos;
        }
        if (tag == LcaSectionCodec.TAG_BITMAP) {
            if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
                int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
                if ((byteLen & 7) != 0 || (byteLen << 3) > activeVoxels || pos + byteLen > limit) {
                    return -1;
                }
                return pos + byteLen;
            }
            int nonZeroWords = countOrIdx;
            int payBytes = 8 + (nonZeroWords << 3);
            if (nonZeroWords < 1 || nonZeroWords > 64 || pos + payBytes > limit) {
                return -1;
            }
            return pos + payBytes;
        }
        if (tag == LcaSectionCodec.TAG_DELTA_ARRAY) {
            for (int r = 0; r < countOrIdx; r++) {
                if (pos >= limit) {
                    return -1;
                }
                if ((buf[pos++] & 0xFF) >= 240) {
                    if (pos >= limit) {
                        return -1;
                    }
                    pos++;
                }
            }
            return pos;
        }
        // TAG_DELTA_RUN
        int subMode = (countOrIdx >>> 12) & 3;
        int runCount = countOrIdx & 0x0FFF;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        for (int r = 0; r < runCount; r++) {
            if (pos >= limit) {
                return -1;
            }
            int tok = buf[pos++] & 0xFF;
            int adjGap = (tok >>> lBits) & gMax;
            int lenM1 = tok & lMax;
            if (adjGap == gMax) {
                if (pos >= limit) {
                    return -1;
                }
                if ((buf[pos++] & 0xFF) >= 240) {
                    if (pos >= limit) {
                        return -1;
                    }
                    pos++;
                }
            }
            if (lenM1 == lMax) {
                if (pos < limit && buf[pos] >= 0) {
                    pos++;
                } else {
                    int extra = LcaSectionCodec.readVarInt(buf, pos, limit);
                    pos += LcaSectionCodec.varIntLen(extra);
                }
            }
        }
        return pos;
    }

    private static boolean containerContains(byte[] buf, int pos, int limit, int target) {
        int word16 = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        int tag = (word16 >>> 14) & 3;
        int countOrIdx = word16 & 0x3FFF;
        if (tag == LcaSectionCodec.TAG_SINGLETON) {
            int subTag = (countOrIdx >>> 12) & 3;
            int idx12 = countOrIdx & 0x0FFF;
            if (subTag == 0) {
                return target == idx12;
            }
            if (subTag == 1) {
                int runLen = (buf[pos] & 0xFF) + 2;
                return target >= idx12 && target < idx12 + runLen;
            }
            if (subTag == 2) {
                int runLen = (((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF)) + 2;
                return target >= idx12 && target < idx12 + runLen;
            }
            int lens = buf[pos++] & 0xFF;
            int runLen0 = (lens >>> 4) + 1;
            int runLen1 = (lens & 0x0F) + 1;
            int adjGap = buf[pos++] & 0xFF;
            if (adjGap >= 240) {
                adjGap = ((adjGap & 0x0F) << 8) | (buf[pos] & 0xFF);
            }
            int end0 = idx12 + runLen0;
            if (target >= idx12 && target < end0) {
                return true;
            }
            int start1 = end0 + 1 + adjGap;
            return target >= start1 && target < start1 + runLen1;
        }
        if (tag == LcaSectionCodec.TAG_BITMAP) {
            int w = target >>> 6;
            int bit = target & 63;
            if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
                int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
                if (w >= (byteLen >>> 3)) {
                    return false;
                }
                long bits64 = LcaSectionCodec.readLongBE(buf, pos + (w << 3));
                return ((bits64 >>> bit) & 1L) != 0L;
            }
            long wordMask64 = LcaSectionCodec.readLongBE(buf, pos);
            if (((wordMask64 >>> w) & 1L) == 0L) {
                return false;
            }
            int rank = Long.bitCount(wordMask64 & ((1L << w) - 1L));
            long bits64 = LcaSectionCodec.readLongBE(buf, pos + 8 + (rank << 3));
            return ((bits64 >>> bit) & 1L) != 0L;
        }
        if (tag == LcaSectionCodec.TAG_DELTA_ARRAY) {
            int cursor = 0;
            for (int r = 0; r < countOrIdx; r++) {
                int adjGap = buf[pos++] & 0xFF;
                if (adjGap >= 240) {
                    adjGap = ((adjGap & 0x0F) << 8) | (buf[pos++] & 0xFF);
                }
                cursor += (r == 0) ? adjGap : (adjGap + 1);
                if (cursor >= target) {
                    return cursor == target;
                }
                cursor++;
            }
            return false;
        }
        // TAG_DELTA_RUN
        int subMode = (countOrIdx >>> 12) & 3;
        int runCount = countOrIdx & 0x0FFF;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        int cursor = 0;
        for (int r = 0; r < runCount; r++) {
            int tok = buf[pos++] & 0xFF;
            int adjGap = (tok >>> lBits) & gMax;
            int lenM1 = tok & lMax;
            if (adjGap == gMax) {
                int b0 = buf[pos++] & 0xFF;
                if (b0 < 240) {
                    adjGap += b0;
                } else {
                    adjGap += ((b0 & 0x0F) << 8) | (buf[pos++] & 0xFF);
                }
            }
            if (lenM1 == lMax) {
                if (pos < limit && buf[pos] >= 0) {
                    lenM1 += buf[pos++];
                } else {
                    int extra = readVarIntUnchecked(buf, pos, limit);
                    pos += LcaSectionCodec.varIntLen(extra);
                    lenM1 += extra;
                }
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            if (target < cursor) {
                return false;
            }
            int runEnd = cursor + lenM1 + 1;
            if (target < runEnd) {
                return true;
            }
            cursor = runEnd;
        }
        return false;
    }

    private static int countContainer(byte[] buf, int pos, int limit) {
        int word16 = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        int tag = (word16 >>> 14) & 3;
        int countOrIdx = word16 & 0x3FFF;
        if (tag == LcaSectionCodec.TAG_SINGLETON) {
            int subTag = (countOrIdx >>> 12) & 3;
            if (subTag == 0) {
                return 1;
            }
            if (subTag == 1) {
                return (buf[pos] & 0xFF) + 2;
            }
            if (subTag == 2) {
                return (((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF)) + 2;
            }
            int lens = buf[pos] & 0xFF;
            return (lens >>> 4) + 1 + (lens & 0x0F) + 1;
        }
        if (tag == LcaSectionCodec.TAG_BITMAP) {
            int wordCount;
            if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
                int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
                wordCount = byteLen >>> 3;
            } else {
                wordCount = countOrIdx;
                pos += 8;
            }
            int sum = 0;
            for (int w = 0; w < wordCount; w++) {
                sum += Long.bitCount(LcaSectionCodec.readLongBE(buf, pos));
                pos += 8;
            }
            return sum;
        }
        if (tag == LcaSectionCodec.TAG_DELTA_ARRAY) {
            return countOrIdx;
        }
        // TAG_DELTA_RUN
        int subMode = (countOrIdx >>> 12) & 3;
        int runCount = countOrIdx & 0x0FFF;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        int sum = 0;
        for (int r = 0; r < runCount; r++) {
            int tok = buf[pos++] & 0xFF;
            int adjGap = (tok >>> lBits) & gMax;
            int lenM1 = tok & lMax;
            if (adjGap == gMax && (buf[pos++] & 0xFF) >= 240) {
                pos++;
            }
            if (lenM1 == lMax) {
                if (pos < limit && buf[pos] >= 0) {
                    lenM1 += buf[pos++];
                } else {
                    int extra = readVarIntUnchecked(buf, pos, limit);
                    pos += LcaSectionCodec.varIntLen(extra);
                    lenM1 += extra;
                }
            }
            sum += lenM1 + 1;
        }
        return sum;
    }

    private static void emitContainerCoordinates(
            byte[] buf,
            int pos,
            int limit,
            int[] curveToYzx,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            IntConsumer yzxConsumer
    ) {
        int word16 = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        int tag = (word16 >>> 14) & 3;
        int countOrIdx = word16 & 0x3FFF;
        if (tag == LcaSectionCodec.TAG_SINGLETON) {
            int subTag = (countOrIdx >>> 12) & 3;
            int idx12 = countOrIdx & 0x0FFF;
            if (subTag == 0) {
                int h = chunkBase != null ? (chunkBase[idx12 >>> chunkShift] | (idx12 & chunkMask)) : idx12;
                yzxConsumer.accept(curveToYzx[h]);
            } else if (subTag == 1 || subTag == 2) {
                int runLen = (subTag == 1)
                        ? ((buf[pos] & 0xFF) + 2)
                        : ((((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF)) + 2);
                emitRun(idx12, idx12 + runLen, curveToYzx, chunkBase, chunkShift, chunkMask, yzxConsumer);
            } else {
                int lens = buf[pos++] & 0xFF;
                int runLen0 = (lens >>> 4) + 1;
                int runLen1 = (lens & 0x0F) + 1;
                int adjGap = buf[pos++] & 0xFF;
                if (adjGap >= 240) {
                    adjGap = ((adjGap & 0x0F) << 8) | (buf[pos] & 0xFF);
                }
                int end0 = idx12 + runLen0;
                emitRun(idx12, end0, curveToYzx, chunkBase, chunkShift, chunkMask, yzxConsumer);
                int start1 = end0 + 1 + adjGap;
                emitRun(start1, start1 + runLen1, curveToYzx, chunkBase, chunkShift, chunkMask, yzxConsumer);
            }
            return;
        }
        if (tag == LcaSectionCodec.TAG_BITMAP) {
            if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
                int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
                int wordCount = byteLen >>> 3;
                for (int w = 0; w < wordCount; w++) {
                    long bits64 = LcaSectionCodec.readLongBE(buf, pos);
                    pos += 8;
                    if (bits64 == 0L) {
                        continue;
                    }
                    int baseC = w << 6;
                    int baseH = chunkBase != null ? (chunkBase[baseC >>> chunkShift] | (baseC & chunkMask)) : baseC;
                    while (bits64 != 0L) {
                        int bit = Long.numberOfTrailingZeros(bits64);
                        bits64 &= (bits64 - 1L);
                        yzxConsumer.accept(curveToYzx[baseH + bit]);
                    }
                }
            } else {
                long wordMask64 = LcaSectionCodec.readLongBE(buf, pos);
                pos += 8;
                while (wordMask64 != 0L) {
                    int w = Long.numberOfTrailingZeros(wordMask64);
                    wordMask64 &= (wordMask64 - 1L);
                    long bits64 = LcaSectionCodec.readLongBE(buf, pos);
                    pos += 8;
                    int baseC = w << 6;
                    int baseH = chunkBase != null ? (chunkBase[baseC >>> chunkShift] | (baseC & chunkMask)) : baseC;
                    while (bits64 != 0L) {
                        int bit = Long.numberOfTrailingZeros(bits64);
                        bits64 &= (bits64 - 1L);
                        yzxConsumer.accept(curveToYzx[baseH + bit]);
                    }
                }
            }
            return;
        }
        if (tag == LcaSectionCodec.TAG_DELTA_ARRAY) {
            int cursor = 0;
            for (int r = 0; r < countOrIdx; r++) {
                int adjGap = buf[pos++] & 0xFF;
                if (adjGap >= 240) {
                    adjGap = ((adjGap & 0x0F) << 8) | (buf[pos++] & 0xFF);
                }
                cursor += (r == 0) ? adjGap : (adjGap + 1);
                int c = cursor++;
                int h = chunkBase != null ? (chunkBase[c >>> chunkShift] | (c & chunkMask)) : c;
                yzxConsumer.accept(curveToYzx[h]);
            }
            return;
        }
        // TAG_DELTA_RUN
        int subMode = (countOrIdx >>> 12) & 3;
        int runCount = countOrIdx & 0x0FFF;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        int cursor = 0;
        for (int r = 0; r < runCount; r++) {
            int tok = buf[pos++] & 0xFF;
            int adjGap = (tok >>> lBits) & gMax;
            int lenM1 = tok & lMax;
            if (adjGap == gMax) {
                int b0 = buf[pos++] & 0xFF;
                if (b0 < 240) {
                    adjGap += b0;
                } else {
                    adjGap += ((b0 & 0x0F) << 8) | (buf[pos++] & 0xFF);
                }
            }
            if (lenM1 == lMax) {
                if (pos < limit && buf[pos] >= 0) {
                    lenM1 += buf[pos++];
                } else {
                    int extra = readVarIntUnchecked(buf, pos, limit);
                    pos += LcaSectionCodec.varIntLen(extra);
                    lenM1 += extra;
                }
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            int runEnd = cursor + lenM1 + 1;
            emitRun(cursor, runEnd, curveToYzx, chunkBase, chunkShift, chunkMask, yzxConsumer);
            cursor = runEnd;
        }
    }

    private static void applyContainerToOrdinals(
            byte[] buf,
            int pos,
            int limit,
            int ord,
            int[] curveToYzx,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            int[] outYzx4096
    ) {
        emitContainerCoordinates(buf, pos, limit, curveToYzx, chunkBase, chunkShift, chunkMask, yzx -> outYzx4096[yzx] = ord);
    }

    private static void emitRun(
            int start,
            int end,
            int[] curveToYzx,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            IntConsumer yzxConsumer
    ) {
        if (chunkBase == null) {
            for (int c = start; c < end; c++) {
                yzxConsumer.accept(curveToYzx[c]);
            }
            return;
        }
        int c = start;
        while (c < end) {
            int blockBase = chunkBase[c >>> chunkShift];
            int inBlock = c & chunkMask;
            int blockEnd = Math.min(end, (c & ~chunkMask) + chunkMask + 1);
            int count = blockEnd - c;
            int h = blockBase | inBlock;
            for (int k = 0; k < count; k++) {
                yzxConsumer.accept(curveToYzx[h + k]);
            }
            c = blockEnd;
        }
    }

    private static int readVarIntUnchecked(byte[] buf, int pos, int limit) {
        int val = 0;
        int shift = 0;
        while (pos < limit) {
            int b = buf[pos++] & 0xFF;
            val |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return val;
            }
            shift += 7;
        }
        return val;
    }

    private static int mix32(int x) {
        x ^= (x >>> 16);
        x *= 0x7feb352d;
        x ^= (x >>> 15);
        x *= 0x846ca68b;
        return x ^ (x >>> 16);
    }

    /**
     * Zero-copy off-heap slice descriptor over an uncompressed Mode A block section payload inside
     * a direct {@link ByteBuffer} ({@code anvil-api-ADR-007}). Never allocates or copies the
     * section's {@code byte[]} payload.
     */
    public record OffHeapModeASlice(
            int curveId,
            int blockMode,
            int flatBits,
            long octreeMask64,
            int[] paletteRuntimeIds,
            ByteBuffer slabBuf,
            int payloadOffset,
            int payloadLength,
            short[] containerOffsets,
            int[] paletteVoxelCounts
    ) {
        public OffHeapModeASlice {
            Objects.requireNonNull(paletteRuntimeIds, "paletteRuntimeIds");
            Objects.requireNonNull(slabBuf, "slabBuf");
            Objects.requireNonNull(paletteVoxelCounts, "paletteVoxelCounts");
            if (paletteVoxelCounts.length != paletteRuntimeIds.length) {
                throw new IllegalArgumentException(
                        "paletteVoxelCounts length mismatch: " + paletteVoxelCounts.length
                                + " != " + paletteRuntimeIds.length);
            }
            if (payloadOffset < 0 || payloadLength <= 0 || payloadOffset + payloadLength > slabBuf.capacity()) {
                throw new IllegalArgumentException(
                        "Invalid OffHeapModeASlice bounds: off=" + payloadOffset
                                + " len=" + payloadLength + " cap=" + slabBuf.capacity());
            }
        }
    }

    /**
     * Zero-copy off-heap Mode A block payload reference for ordered vectored gather writes
     * ({@code anvil-api-ADR-007}). {@link #slicePayload()} uses absolute {@link ByteBuffer#slice(int, int)}
     * so concurrent readers on the same backing slab never mutate buffer position or limit.
     */
    public record DirectModeASectionRef(
            int curveId,
            int blockMode,
            int flatBits,
            long octreeMask64,
            int paletteLength,
            ByteBuffer payloadBuf,
            int payloadOffset,
            int payloadLength
    ) {
        public DirectModeASectionRef {
            Objects.requireNonNull(payloadBuf, "payloadBuf");
            if (paletteLength < 2 || paletteLength > SECTION_VOXELS) {
                throw new IllegalArgumentException("Invalid paletteLength: " + paletteLength);
            }
            if (payloadOffset < 0 || payloadLength <= 0 || payloadOffset + payloadLength > payloadBuf.capacity()) {
                throw new IllegalArgumentException(
                        "Invalid DirectModeASectionRef bounds: off=" + payloadOffset
                                + " len=" + payloadLength + " cap=" + payloadBuf.capacity());
            }
        }

        public ByteBuffer slicePayload() {
            return payloadBuf.slice(payloadOffset, payloadLength);
        }
    }

    /**
     * Validates and slices an uncompressed Mode A block section directly from an absolute range in
     * a {@link ByteBuffer} with zero {@code byte[]} allocation or copy ({@code anvil-api-ADR-007}).
     * Enforces inline {@code CBIT T5/T6/T6b} verification and computes single-pass
     * {@code paletteVoxelCounts} in L1 cache. Returns {@code null} on uniform sections, unsupported
     * v3 escapes, or corruption (call {@link #sliceOffHeapFromBufferOrThrow} to propagate
     * {@link CorruptRegionEntryException} directly).
     */
    public static OffHeapModeASlice trySliceOffHeapFromBuffer(
            ByteBuffer chunkFrame,
            int start,
            int limit,
            int[] palMap
    ) {
        try {
            return sliceOffHeapFromBufferOrThrow(chunkFrame, start, limit, palMap);
        } catch (CorruptRegionEntryException ex) {
            return null;
        }
    }

    /**
     * Validates and slices an uncompressed Mode A block section directly from an absolute range in
     * a {@link ByteBuffer} with zero heap staging copy ({@code anvil-api-ADR-007}). Enforces inline
     * {@code CBIT T5} (VarInt/bounds), {@code CBIT T6} (run/bitmap cursor bounds), and
     * {@code CBIT T6b} ({@code octreeOccupancyMask64} syndrome verification) while accumulating
     * exact per-palette voxel counts in a single sequential pass.
     *
     * @throws CorruptRegionEntryException on any {@code CBIT T5/T6/T6b/T7} corruption
     */
    public static OffHeapModeASlice sliceOffHeapFromBufferOrThrow(
            ByteBuffer chunkFrame,
            int start,
            int limit,
            int[] palMap
    ) throws CorruptRegionEntryException {
        if (chunkFrame == null || palMap == null || start < 0 || limit <= start + 2 || limit > chunkFrame.capacity()) {
            throw new CorruptRegionEntryException(
                    "CBIT T5: Invalid off-heap Mode A slice bounds (" + start + ".." + limit + ")");
        }
        int pos = start;
        int sectionHeader = chunkFrame.get(pos++) & 0xFF;
        int curveId = (sectionHeader >>> 6) & 3;
        int blockMode = (sectionHeader >>> 4) & 3;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_UNIFORM) {
            return null;
        }
        long octreeMask64;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            octreeMask64 = -1L;
        } else {
            if (pos + 8 > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated octreeOccupancyMask64 in off-heap slice");
            }
            octreeMask64 = readLongBEBuffer(chunkFrame, pos);
            pos += 8;
        }
        int paletteLen = readVarIntBuffer(chunkFrame, pos, limit);
        pos += LcaSectionCodec.varIntLen(paletteLen);
        if (paletteLen == 0) {
            // v3 secExtFlags escape (e.g. SEC_EXT_GRAY_BITPLANES) is not a plain Mode A slice.
            return null;
        }
        if (paletteLen < 2 || paletteLen > SECTION_VOXELS) {
            throw new CorruptRegionEntryException("CBIT T7: Invalid off-heap section paletteLen " + paletteLen);
        }
        int[] mappedIds = new int[paletteLen];
        for (int i = 0; i < paletteLen; i++) {
            int diskId = readVarIntBuffer(chunkFrame, pos, limit);
            pos += LcaSectionCodec.varIntLen(diskId);
            if (diskId < 0 || diskId >= palMap.length) {
                throw new CorruptRegionEntryException("CBIT T7: Section diskId " + diskId + " >= " + palMap.length);
            }
            int runtimeId = palMap[diskId];
            if (runtimeId < 0 || runtimeId > LcaSectionBacking.MAX_STATE_ID_19) {
                throw new CorruptRegionEntryException("CBIT T7: Invalid mapped runtimeId " + runtimeId);
            }
            mappedIds[i] = runtimeId;
        }
        return validateAndSlicePayloadFromBuffer(
                chunkFrame, pos, limit, curveId, blockMode, octreeMask64, mappedIds, null);
    }

    /**
     * Single-pass inline L1-cache-resident validator and voxel-counter over an already-parsed
     * section's off-heap Mode A block payload. Enforces {@code CBIT T5/T6/T6b/T7} and writes the
     * exact post-payload byte offset into {@code endPosOut[0]} when provided.
     */
    static OffHeapModeASlice validateAndSlicePayloadFromBuffer(
            ByteBuffer chunkFrame,
            int payloadStart,
            int limit,
            int curveId,
            int blockMode,
            long octreeMask64,
            int[] mappedIds,
            int[] endPosOut
    ) throws CorruptRegionEntryException {
        int paletteLen = mappedIds.length;
        if (blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) {
            int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
            int flatByteCount = (SECTION_VOXELS * flatBits + 7) >>> 3;
            if (flatByteCount <= 0 || payloadStart + flatByteCount > limit) {
                throw new CorruptRegionEntryException("CBIT T6: Truncated FlatBitPack off-heap payload");
            }
            int[] counts = new int[paletteLen];
            long decodedMask = validateAndCountFlatBitPackBuffer(
                    chunkFrame, payloadStart, flatByteCount, flatBits, paletteLen, counts);
            if (decodedMask != octreeMask64) {
                throw new CorruptRegionEntryException(
                        "CBIT T6b: FlatBitPack octreeOccupancyMask64 syndrome mismatch (expected=0x"
                                + Long.toHexString(octreeMask64)
                                + " actual=0x" + Long.toHexString(decodedMask) + ")");
            }
            if (endPosOut != null) {
                endPosOut[0] = payloadStart + flatByteCount;
            }
            return new OffHeapModeASlice(
                    curveId, blockMode, flatBits, octreeMask64, mappedIds,
                    chunkFrame, payloadStart, flatByteCount, null, counts);
        }
        if (blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING
                && blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            throw new CorruptRegionEntryException("CBIT: Unsupported Mode A blockEncodingMode " + blockMode);
        }

        int[] chunkBase = null;
        int chunkShift = 0;
        int chunkMask = 0;
        int activeVoxels = SECTION_VOXELS;
        if (octreeMask64 != -1L && octreeMask64 != 0L) {
            if (curveId == LcaSectionCodec.CURVE_H3) {
                int k = Long.bitCount(octreeMask64);
                chunkBase = LcaSectionCodec.scratch().activeOctantBase;
                long m = octreeMask64;
                int idx = 0;
                while (m != 0L) {
                    chunkBase[idx++] = Long.numberOfTrailingZeros(m) << 6;
                    m &= (m - 1L);
                }
                chunkShift = 6;
                chunkMask = 63;
                activeVoxels = k << 6;
            } else if (curveId == LcaSectionCodec.CURVE_YZX || curveId == LcaSectionCodec.CURVE_Y_H2) {
                int sCount = 0;
                for (int octY = 0; octY < 4; octY++) {
                    if (((octreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                        sCount++;
                    }
                }
                if (sCount < 4) {
                    chunkBase = LcaSectionCodec.scratch().activeOctantBase;
                    int idx = 0;
                    for (int octY = 0; octY < 4; octY++) {
                        if (((octreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                            chunkBase[idx++] = octY << 10;
                        }
                    }
                    chunkShift = 10;
                    chunkMask = 1023;
                    activeVoxels = sCount << 10;
                }
            }
        }

        int[] curveToYzx = LcaSectionCodec.CURVE_TO_YZX[curveId & 3];
        int minorityCount = paletteLen - 1;
        short[] offsets = new short[minorityCount];
        int[] counts = new int[paletteLen];
        long decodedOctreeMask64 = 0L;
        int minoritySum = 0;
        int pos = payloadStart;
        int[] countOut = new int[1];
        long[] maskOut = new long[1];

        for (int p = 1; p < paletteLen; p++) {
            int relOff = pos - payloadStart;
            if (relOff < 0 || relOff > Short.MAX_VALUE) {
                throw new CorruptRegionEntryException("CBIT T6: Delta-Roaring container offset overflow: " + relOff);
            }
            offsets[p - 1] = (short) relOff;
            pos = validateAndCountMinorityContainerBuffer(
                    chunkFrame, pos, limit, activeVoxels, curveId, curveToYzx,
                    chunkBase, chunkShift, chunkMask, countOut, maskOut);
            int c = countOut[0];
            counts[p] = c;
            minoritySum += c;
            if (minoritySum > SECTION_VOXELS) {
                throw new CorruptRegionEntryException(
                        "CBIT T6: Minority voxel count exceeded 4096 (" + minoritySum + ")");
            }
            decodedOctreeMask64 |= maskOut[0];
        }
        counts[0] = SECTION_VOXELS - minoritySum;
        if (decodedOctreeMask64 != octreeMask64) {
            throw new CorruptRegionEntryException(
                    "CBIT T6b: Section octreeOccupancyMask64 syndrome mismatch (expected=0x"
                            + Long.toHexString(octreeMask64)
                            + " actual=0x" + Long.toHexString(decodedOctreeMask64) + ")");
        }
        int payloadLen = pos - payloadStart;
        if (payloadLen <= 0 || payloadLen > Short.MAX_VALUE) {
            throw new CorruptRegionEntryException("CBIT T6: Invalid Delta-Roaring payload length: " + payloadLen);
        }
        if (endPosOut != null) {
            endPosOut[0] = pos;
        }
        return new OffHeapModeASlice(
                curveId, blockMode, 0, octreeMask64, mappedIds,
                chunkFrame, payloadStart, payloadLen, offsets, counts);
    }

    private static long validateAndCountFlatBitPackBuffer(
            ByteBuffer buf,
            int base,
            int flatByteCount,
            int flatBits,
            int paletteLen,
            int[] counts
    ) throws CorruptRegionEntryException {
        int[] yzxToOct = LcaSectionCodec.YZX_TO_OCTANT6;
        long decodedMask = 0L;
        if (flatBits == 1) {
            for (int bIdx = 0; bIdx < 512; bIdx++) {
                int b = buf.get(base + bIdx) & 0xFF;
                if (b == 0) {
                    counts[0] += 8;
                    continue;
                }
                int vBase = bIdx << 3;
                for (int bit = 0; bit < 8; bit++) {
                    int val = (b >>> bit) & 1;
                    counts[val]++;
                    if (val != 0) {
                        decodedMask |= 1L << yzxToOct[vBase + bit];
                    }
                }
            }
            return decodedMask;
        }
        if (flatBits == 2) {
            for (int bIdx = 0; bIdx < 1024; bIdx++) {
                int b = buf.get(base + bIdx) & 0xFF;
                if (b == 0) {
                    counts[0] += 4;
                    continue;
                }
                int vBase = bIdx << 2;
                for (int k = 0; k < 4; k++) {
                    int val = (b >>> (k << 1)) & 3;
                    if (val >= paletteLen) {
                        throw new CorruptRegionEntryException(
                                "CBIT T7: FlatBitPack palette index " + val + " >= " + paletteLen);
                    }
                    counts[val]++;
                    if (val != 0) {
                        decodedMask |= 1L << yzxToOct[vBase + k];
                    }
                }
            }
            return decodedMask;
        }
        if (flatBits == 4) {
            for (int bIdx = 0; bIdx < 2048; bIdx++) {
                int b = buf.get(base + bIdx) & 0xFF;
                if (b == 0) {
                    counts[0] += 2;
                    continue;
                }
                int vBase = bIdx << 1;
                int v0 = b & 0x0F;
                if (v0 >= paletteLen) {
                    throw new CorruptRegionEntryException(
                            "CBIT T7: FlatBitPack palette index " + v0 + " >= " + paletteLen);
                }
                counts[v0]++;
                if (v0 != 0) {
                    decodedMask |= 1L << yzxToOct[vBase];
                }
                int v1 = (b >>> 4) & 0x0F;
                if (v1 >= paletteLen) {
                    throw new CorruptRegionEntryException(
                            "CBIT T7: FlatBitPack palette index " + v1 + " >= " + paletteLen);
                }
                counts[v1]++;
                if (v1 != 0) {
                    decodedMask |= 1L << yzxToOct[vBase + 1];
                }
            }
            return decodedMask;
        }
        if (flatBits == 8) {
            for (int v = 0; v < SECTION_VOXELS; v++) {
                int val = buf.get(base + v) & 0xFF;
                if (val >= paletteLen) {
                    throw new CorruptRegionEntryException(
                            "CBIT T7: FlatBitPack palette index " + val + " >= " + paletteLen);
                }
                counts[val]++;
                if (val != 0) {
                    decodedMask |= 1L << yzxToOct[v];
                }
            }
            return decodedMask;
        }
        int mask = (1 << flatBits) - 1;
        int limit = base + flatByteCount;
        int bitPos = 0;
        for (int v = 0; v < SECTION_VOXELS; v++) {
            int absOff = base + (bitPos >>> 3);
            int bitOff = bitPos & 7;
            int word = buf.get(absOff) & 0xFF;
            if (absOff + 1 < limit) {
                word |= (buf.get(absOff + 1) & 0xFF) << 8;
            }
            if (absOff + 2 < limit) {
                word |= (buf.get(absOff + 2) & 0xFF) << 16;
            }
            if (absOff + 3 < limit) {
                word |= (buf.get(absOff + 3) & 0xFF) << 24;
            }
            int val = (word >>> bitOff) & mask;
            if (val >= paletteLen) {
                throw new CorruptRegionEntryException(
                        "CBIT T7: FlatBitPack palette index " + val + " >= " + paletteLen);
            }
            counts[val]++;
            if (val != 0) {
                decodedMask |= 1L << yzxToOct[v];
            }
            bitPos += flatBits;
        }
        return decodedMask;
    }

    private static long readLongBEBuffer(ByteBuffer buf, int off) {
        return ((long) (buf.get(off) & 0xFF) << 56)
                | ((long) (buf.get(off + 1) & 0xFF) << 48)
                | ((long) (buf.get(off + 2) & 0xFF) << 40)
                | ((long) (buf.get(off + 3) & 0xFF) << 32)
                | ((long) (buf.get(off + 4) & 0xFF) << 24)
                | ((long) (buf.get(off + 5) & 0xFF) << 16)
                | ((long) (buf.get(off + 6) & 0xFF) << 8)
                | ((long) (buf.get(off + 7) & 0xFF));
    }

    private static int readVarIntBuffer(ByteBuffer buf, int pos, int limit) throws CorruptRegionEntryException {
        int val = 0;
        int shift = 0;
        while (pos < limit) {
            int b = buf.get(pos++) & 0xFF;
            val |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return val;
            }
            shift += 7;
            if (shift > 28) {
                throw new CorruptRegionEntryException("CBIT T5: VarInt overflow in off-heap Mode A slice");
            }
        }
        throw new CorruptRegionEntryException("CBIT T5: Truncated VarInt in off-heap Mode A slice");
    }

    /** L1-resident octant LUTs (`2.5 KiB` total) for O(octants) inline CBIT T6b verification across all 4 curves. */
    private static final byte[] Y_H2_BLOCK16_TO_OCTANT6 = new byte[256];
    private static final byte[] YZX_BLOCK4_TO_OCTANT6 = new byte[1024];
    private static final byte[] XZY_BLOCK4_TO_OCTANT6 = new byte[1024];
    private static final long[] Y_SLAB_OCTANT_MASK = new long[4];
    private static final long[] YZ_ROW_OCTANT_MASK = new long[16];
    private static final long[] XZ_COL_OCTANT_MASK = new long[16];

    static {
        for (int b = 0; b < 256; b++) {
            int h = b << 4;
            int oct = LcaSectionCodec.YZX_TO_OCTANT6[LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_Y_H2][h]];
            Y_H2_BLOCK16_TO_OCTANT6[b] = (byte) oct;
            Y_SLAB_OCTANT_MASK[h >>> 10] |= 1L << oct;
        }
        for (int b = 0; b < 1024; b++) {
            int h = b << 2;
            int yzxOct = LcaSectionCodec.YZX_TO_OCTANT6[h];
            YZX_BLOCK4_TO_OCTANT6[b] = (byte) yzxOct;
            YZ_ROW_OCTANT_MASK[((h >>> 10) << 2) | ((h >>> 6) & 3)] |= 1L << yzxOct;

            int xzyOct = LcaSectionCodec.YZX_TO_OCTANT6[LcaSectionCodec.CURVE_TO_YZX[LcaSectionCodec.CURVE_XZY][h]];
            XZY_BLOCK4_TO_OCTANT6[b] = (byte) xzyOct;
            XZ_COL_OCTANT_MASK[((h >>> 10) << 2) | ((h >>> 6) & 3)] |= 1L << xzyOct;
        }
    }

    private static long octantBitForCurvePos(int curveId, int h) {
        return switch (curveId) {
            case LcaSectionCodec.CURVE_H3 -> 1L << (h >>> 6);
            case LcaSectionCodec.CURVE_Y_H2 -> 1L << (Y_H2_BLOCK16_TO_OCTANT6[h >>> 4] & 0x3F);
            case LcaSectionCodec.CURVE_YZX -> 1L << (YZX_BLOCK4_TO_OCTANT6[h >>> 2] & 0x3F);
            default -> 1L << (XZY_BLOCK4_TO_OCTANT6[h >>> 2] & 0x3F);
        };
    }

    private static long octantMaskForBitmapWord64(int curveId, int baseH, long bits64) {
        if (curveId == LcaSectionCodec.CURVE_H3) {
            return 1L << (baseH >>> 6);
        }
        if (curveId == LcaSectionCodec.CURVE_Y_H2) {
            int b = baseH >>> 4;
            long mask = 0L;
            if ((bits64 & 0x0000_0000_0000_FFFFL) != 0L) {
                mask |= 1L << (Y_H2_BLOCK16_TO_OCTANT6[b] & 0x3F);
            }
            if ((bits64 & 0x0000_0000_FFFF_0000L) != 0L) {
                mask |= 1L << (Y_H2_BLOCK16_TO_OCTANT6[b + 1] & 0x3F);
            }
            if ((bits64 & 0x0000_FFFF_0000_0000L) != 0L) {
                mask |= 1L << (Y_H2_BLOCK16_TO_OCTANT6[b + 2] & 0x3F);
            }
            if ((bits64 & 0xFFFF_0000_0000_0000L) != 0L) {
                mask |= 1L << (Y_H2_BLOCK16_TO_OCTANT6[b + 3] & 0x3F);
            }
            return mask;
        }
        byte[] lut = (curveId == LcaSectionCodec.CURVE_YZX) ? YZX_BLOCK4_TO_OCTANT6 : XZY_BLOCK4_TO_OCTANT6;
        int b = baseH >>> 2;
        long mask = 0L;
        long m = bits64;
        for (int n = 0; m != 0L; n++, m >>>= 4) {
            if ((m & 0xFL) != 0L) {
                mask |= 1L << (lut[b + n] & 0x3F);
            }
        }
        return mask;
    }

    static long octantMaskForRun(
            int cursor,
            int runEnd,
            int curveId,
            int[] curveToYzx,
            int[] chunkBase,
            int chunkShift,
            int chunkMask
    ) {
        if (curveId == LcaSectionCodec.CURVE_H3) {
            if (chunkBase == null) {
                int sOct = cursor >>> 6;
                int eOct = (runEnd - 1) >>> 6;
                return (-1L >>> (63 - (eOct - sOct))) << sOct;
            }
            int sRank = cursor >>> 6;
            int eRank = (runEnd - 1) >>> 6;
            long mask = 0L;
            for (int r = sRank; r <= eRank; r++) {
                mask |= 1L << (chunkBase[r] >>> 6);
            }
            return mask;
        }
        if (curveId == LcaSectionCodec.CURVE_Y_H2) {
            long mask = 0L;
            int c = cursor;
            while (c < runEnd) {
                int h = (chunkBase != null) ? (chunkBase[c >>> chunkShift] | (c & chunkMask)) : c;
                if ((c & 0xFF) == 0 && c + 256 <= runEnd) {
                    mask |= Y_SLAB_OCTANT_MASK[h >>> 10];
                    c += 256;
                } else {
                    mask |= 1L << (Y_H2_BLOCK16_TO_OCTANT6[h >>> 4] & 0x3F);
                    c = (c | 15) + 1;
                }
            }
            return mask;
        }
        if (curveId == LcaSectionCodec.CURVE_YZX) {
            long mask = 0L;
            int c = cursor;
            while (c < runEnd) {
                int h = (chunkBase != null) ? (chunkBase[c >>> chunkShift] | (c & chunkMask)) : c;
                if ((c & 0xFF) == 0 && c + 256 <= runEnd) {
                    mask |= Y_SLAB_OCTANT_MASK[h >>> 10];
                    c += 256;
                } else if ((c & 15) == 0 && c + 16 <= runEnd) {
                    mask |= YZ_ROW_OCTANT_MASK[((h >>> 10) << 2) | ((h >>> 6) & 3)];
                    c += 16;
                } else {
                    mask |= 1L << (YZX_BLOCK4_TO_OCTANT6[h >>> 2] & 0x3F);
                    c = (c | 3) + 1;
                }
            }
            return mask;
        }
        // CURVE_XZY: every 4 consecutive Y voxels share one octant; 16-voxel Y columns span 4 octants
        long mask = 0L;
        int c = cursor;
        while (c < runEnd) {
            int h = (chunkBase != null) ? (chunkBase[c >>> chunkShift] | (c & chunkMask)) : c;
            if ((c & 15) == 0 && c + 16 <= runEnd) {
                mask |= XZ_COL_OCTANT_MASK[((h >>> 10) << 2) | ((h >>> 6) & 3)];
                c += 16;
            } else {
                mask |= 1L << (XZY_BLOCK4_TO_OCTANT6[h >>> 2] & 0x3F);
                c = (c | 3) + 1;
            }
        }
        return mask;
    }

    private static int validateAndCountMinorityContainerBuffer(
            ByteBuffer buf,
            int pos,
            int limit,
            int activeVoxels,
            int curveId,
            int[] curveToYzx,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            int[] countOut,
            long[] maskOut
    ) throws CorruptRegionEntryException {
        if (pos + 2 > limit) {
            throw new CorruptRegionEntryException("CBIT T6: Truncated minority container header");
        }
        int word16 = ((buf.get(pos) & 0xFF) << 8) | (buf.get(pos + 1) & 0xFF);
        pos += 2;
        int tag = (word16 >>> 14) & 3;
        int countOrIdx = word16 & 0x3FFF;
        return switch (tag) {
            case LcaSectionCodec.TAG_SINGLETON -> validateSingletonContainerBuffer(
                    buf, pos, limit, countOrIdx, activeVoxels, curveId, curveToYzx,
                    chunkBase, chunkShift, chunkMask, countOut, maskOut);
            case LcaSectionCodec.TAG_BITMAP -> validateBitmapContainerBuffer(
                    buf, pos, limit, countOrIdx, activeVoxels, curveId,
                    chunkBase, chunkShift, chunkMask, countOut, maskOut);
            case LcaSectionCodec.TAG_DELTA_ARRAY -> validateDeltaArrayContainerBuffer(
                    buf, pos, limit, countOrIdx, activeVoxels, curveId,
                    chunkBase, chunkShift, chunkMask, countOut, maskOut);
            default -> validateDeltaRunContainerBuffer(
                    buf, pos, limit, countOrIdx, activeVoxels, curveId, curveToYzx,
                    chunkBase, chunkShift, chunkMask, countOut, maskOut);
        };
    }

    private static int validateSingletonContainerBuffer(
            ByteBuffer buf,
            int pos,
            int limit,
            int countOrIdx,
            int activeVoxels,
            int curveId,
            int[] curveToYzx,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            int[] countOut,
            long[] maskOut
    ) throws CorruptRegionEntryException {
        int subTag = (countOrIdx >>> 12) & 3;
        int idx12 = countOrIdx & 0x0FFF;
        if (subTag == 0) {
            if (idx12 >= activeVoxels) {
                throw new CorruptRegionEntryException("CBIT T6: Singleton index out of bounds: " + idx12);
            }
            int h = (chunkBase != null) ? (chunkBase[idx12 >>> chunkShift] | (idx12 & chunkMask)) : idx12;
            countOut[0] = 1;
            maskOut[0] = octantBitForCurvePos(curveId, h);
            return pos;
        }
        if (subTag == 1 || subTag == 2) {
            int runLen;
            if (subTag == 1) {
                if (pos >= limit) {
                    throw new CorruptRegionEntryException("CBIT T6: Truncated 1B single-run");
                }
                runLen = (buf.get(pos++) & 0xFF) + 2;
            } else {
                if (pos + 2 > limit) {
                    throw new CorruptRegionEntryException("CBIT T6: Truncated 2B single-run");
                }
                runLen = (((buf.get(pos) & 0xFF) << 8) | (buf.get(pos + 1) & 0xFF)) + 2;
                pos += 2;
            }
            if (idx12 + runLen > activeVoxels) {
                throw new CorruptRegionEntryException("CBIT T6: Single-run overflow: " + (idx12 + runLen));
            }
            countOut[0] = runLen;
            maskOut[0] = octantMaskForRun(
                    idx12, idx12 + runLen, curveId, curveToYzx, chunkBase, chunkShift, chunkMask);
            return pos;
        }
        if (pos + 2 > limit) {
            throw new CorruptRegionEntryException("CBIT T6: Truncated two-run singleton header");
        }
        int lens = buf.get(pos++) & 0xFF;
        int runLen0 = (lens >>> 4) + 1;
        int runLen1 = (lens & 0x0F) + 1;
        int adjGap = buf.get(pos++) & 0xFF;
        if (adjGap >= 240) {
            if (pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T6: Truncated 2B two-run gap");
            }
            adjGap = ((adjGap & 0x0F) << 8) | (buf.get(pos++) & 0xFF);
        }
        int end0 = idx12 + runLen0;
        int start1 = end0 + 1 + adjGap;
        int end1 = start1 + runLen1;
        if (idx12 < 0 || end0 > activeVoxels || start1 < 0 || end1 > activeVoxels) {
            throw new CorruptRegionEntryException("CBIT T6: Two-run index out of bounds: " + end1);
        }
        countOut[0] = runLen0 + runLen1;
        maskOut[0] = octantMaskForRun(idx12, end0, curveId, curveToYzx, chunkBase, chunkShift, chunkMask)
                | octantMaskForRun(start1, end1, curveId, curveToYzx, chunkBase, chunkShift, chunkMask);
        return pos;
    }

    private static int validateBitmapContainerBuffer(
            ByteBuffer buf,
            int pos,
            int limit,
            int countOrIdx,
            int activeVoxels,
            int curveId,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            int[] countOut,
            long[] maskOut
    ) throws CorruptRegionEntryException {
        int count = 0;
        long mask = 0L;
        if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
            int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
            if ((byteLen & 7) != 0 || (byteLen << 3) > activeVoxels || pos + byteLen > limit) {
                throw new CorruptRegionEntryException("CBIT T6: Truncated or invalid TAG_BITMAP container");
            }
            int wordCount = byteLen >>> 3;
            for (int w = 0; w < wordCount; w++) {
                long bits64 = readLongBEBuffer(buf, pos);
                pos += 8;
                if (bits64 == 0L) {
                    continue;
                }
                count += Long.bitCount(bits64);
                int baseC = w << 6;
                int baseH = (chunkBase != null)
                        ? (chunkBase[baseC >>> chunkShift] | (baseC & chunkMask))
                        : baseC;
                mask |= octantMaskForBitmapWord64(curveId, baseH, bits64);
            }
            countOut[0] = count;
            maskOut[0] = mask;
            return pos;
        }
        int nonZeroWords = countOrIdx;
        int payBytes = 8 + (nonZeroWords << 3);
        if (nonZeroWords < 1 || nonZeroWords > 64 || pos + payBytes > limit) {
            throw new CorruptRegionEntryException("CBIT T6: Truncated Word-Masked TAG_BITMAP container");
        }
        long wordMask64 = readLongBEBuffer(buf, pos);
        pos += 8;
        if (Long.bitCount(wordMask64) != nonZeroWords) {
            throw new CorruptRegionEntryException("CBIT T6: Word-Masked TAG_BITMAP popcount mismatch");
        }
        int maxWords = activeVoxels >>> 6;
        while (wordMask64 != 0L) {
            int w = Long.numberOfTrailingZeros(wordMask64);
            wordMask64 &= (wordMask64 - 1L);
            if (w >= maxWords) {
                throw new CorruptRegionEntryException(
                        "CBIT T6: Word-Masked TAG_BITMAP word out of bounds: " + w);
            }
            long bits64 = readLongBEBuffer(buf, pos);
            pos += 8;
            if (bits64 == 0L) {
                continue;
            }
            count += Long.bitCount(bits64);
            int baseC = w << 6;
            int baseH = (chunkBase != null)
                    ? (chunkBase[baseC >>> chunkShift] | (baseC & chunkMask))
                    : baseC;
            mask |= octantMaskForBitmapWord64(curveId, baseH, bits64);
        }
        countOut[0] = count;
        maskOut[0] = mask;
        return pos;
    }

    private static int validateDeltaArrayContainerBuffer(
            ByteBuffer buf,
            int pos,
            int limit,
            int countOrIdx,
            int activeVoxels,
            int curveId,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            int[] countOut,
            long[] maskOut
    ) throws CorruptRegionEntryException {
        int cursor = 0;
        long mask = 0L;
        for (int r = 0; r < countOrIdx; r++) {
            if (pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T6: Truncated TAG_DELTA_ARRAY");
            }
            int adjGap = buf.get(pos++) & 0xFF;
            if (adjGap >= 240) {
                if (pos >= limit) {
                    throw new CorruptRegionEntryException("CBIT T6: Truncated 2-byte TAG_DELTA_ARRAY gap");
                }
                adjGap = ((adjGap & 0x0F) << 8) | (buf.get(pos++) & 0xFF);
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            if (cursor < 0 || cursor >= activeVoxels) {
                throw new CorruptRegionEntryException("CBIT T6: TAG_DELTA_ARRAY cursor overflow: " + cursor);
            }
            int c = cursor++;
            int h = (chunkBase != null) ? (chunkBase[c >>> chunkShift] | (c & chunkMask)) : c;
            mask |= octantBitForCurvePos(curveId, h);
        }
        countOut[0] = countOrIdx;
        maskOut[0] = mask;
        return pos;
    }

    private static int validateDeltaRunContainerBuffer(
            ByteBuffer buf,
            int pos,
            int limit,
            int countOrIdx,
            int activeVoxels,
            int curveId,
            int[] curveToYzx,
            int[] chunkBase,
            int chunkShift,
            int chunkMask,
            int[] countOut,
            long[] maskOut
    ) throws CorruptRegionEntryException {
        int subMode = (countOrIdx >>> 12) & 3;
        int runCount = countOrIdx & 0x0FFF;
        int lBits = 4 - subMode;
        int gMax = (1 << (4 + subMode)) - 1;
        int lMax = (1 << lBits) - 1;
        int cursor = 0;
        int count = 0;
        long mask = 0L;
        for (int r = 0; r < runCount; r++) {
            if (pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T6: Truncated TAG_DELTA_RUN");
            }
            int tok = buf.get(pos++) & 0xFF;
            int adjGap = (tok >>> lBits) & gMax;
            int lenM1 = tok & lMax;
            if (adjGap == gMax) {
                if (pos >= limit) {
                    throw new CorruptRegionEntryException("CBIT T6: Truncated 1-byte TAG_DELTA_RUN gap");
                }
                int b0 = buf.get(pos++) & 0xFF;
                if (b0 < 240) {
                    adjGap += b0;
                } else {
                    if (pos >= limit) {
                        throw new CorruptRegionEntryException("CBIT T6: Truncated 2-byte TAG_DELTA_RUN gap");
                    }
                    adjGap += ((b0 & 0x0F) << 8) | (buf.get(pos++) & 0xFF);
                }
            }
            if (lenM1 == lMax) {
                if (pos < limit && buf.get(pos) >= 0) {
                    lenM1 += buf.get(pos++);
                } else {
                    int extra = readVarIntBuffer(buf, pos, limit);
                    pos += LcaSectionCodec.varIntLen(extra);
                    lenM1 += extra;
                }
            }
            cursor += (r == 0) ? adjGap : (adjGap + 1);
            int runLen = lenM1 + 1;
            int runEnd = cursor + runLen;
            if (cursor < 0 || runEnd > activeVoxels) {
                throw new CorruptRegionEntryException(
                        "CBIT T6: Delta-Roaring run cursor exceeded " + activeVoxels + " (" + runEnd + ")");
            }
            count += runLen;
            mask |= octantMaskForRun(cursor, runEnd, curveId, curveToYzx, chunkBase, chunkShift, chunkMask);
            cursor = runEnd;
        }
        countOut[0] = count;
        maskOut[0] = mask;
        return pos;
    }
}

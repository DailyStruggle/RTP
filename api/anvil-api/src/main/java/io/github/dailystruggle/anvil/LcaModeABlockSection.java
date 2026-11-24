package io.github.dailystruggle.anvil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaModeABlockSection {

    public static final int SECTION_VOXELS = LcaSectionCodec.SECTION_VOXELS;

    private static final VarHandle INT_LE_VIEW =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final ThreadLocal<ReadCache> READ_CACHE = ThreadLocal.withInitial(ReadCache::new);

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

        byte[] lookup(LcaModeABlockSection target) {
            if (s0 == target) return b0;
            if (s1 == target) return b1;
            if (s2 == target) return b2;
            if (s3 == target) return b3;
            return null;
        }

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
        int ord = queryVoxelOrdinal(yzx);
        return this.paletteRuntimeIds[ord < this.paletteRuntimeIds.length ? ord : 0];
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
            if (adjGap == gMax) {
                if ((buf[pos++] & 0xFF) >= 240) {
                    pos++;
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
}

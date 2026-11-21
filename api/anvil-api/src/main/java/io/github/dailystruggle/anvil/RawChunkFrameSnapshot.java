package io.github.dailystruggle.anvil;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable cold `.lca` `ChunkFrame` snapshot shared zero-copy between disk slabs, server memory,
 * GPU Stage K3 output, and conditional `LCA_NET_V1` wire slicing (`anvil-api-ADR-003`,
 * `leafanvil-ADR-005`, `leafanvil-ADR-006`).
 *
 * <p>Retains the canonical uncompressed `.lca` `ChunkFrame` byte buffer in the region dictionary ID
 * namespace alongside an optional cached {@code dictIdToRuntimeId} lookup table so clean chunks
 * require zero Mojang NBT materialization and zero background section re-encoding.</p>
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public record RawChunkFrameSnapshot(
        byte[] chunkFrame,
        int dataVersion,
        int minSectionY,
        int sectionCount,
        int residualNbtRelOffset,
        int[] dictIdToRuntimeId,
        int formatVersion
) {
    public RawChunkFrameSnapshot(
            byte[] chunkFrame,
            int dataVersion,
            int minSectionY,
            int sectionCount,
            int residualNbtRelOffset,
            int[] dictIdToRuntimeId
    ) {
        this(chunkFrame, dataVersion, minSectionY, sectionCount, residualNbtRelOffset, dictIdToRuntimeId,
                LcaRegionReader.LCA_FORMAT_VERSION);
    }

    public RawChunkFrameSnapshot {
        Objects.requireNonNull(chunkFrame, "chunkFrame");
        if (chunkFrame.length < 24 + (Math.max(1, sectionCount) << 1)) {
            throw new IllegalArgumentException(
                    "chunkFrame too short (" + chunkFrame.length + ") for sectionCount=" + sectionCount);
        }
    }

    public long lastUpdateTick() {
        return LcaSectionCodec.readLongBE(chunkFrame, 4);
    }

    public long inhabitedTimeTicks() {
        return LcaSectionCodec.readLongBE(chunkFrame, 12);
    }

    public int statusOrdinal() {
        return chunkFrame[20] & 0xFF;
    }

    public int chunkFlags() {
        return chunkFrame[21] & 0xFF;
    }

    public boolean hasResidualNbt() {
        return (chunkFlags() & 1) != 0;
    }

    /**
     * Returns {@code true} when this chunk's residual header records {@code isLightOn == 1}
     * ({@code bpFlags & 8}), so direct CoW saves can distinguish already-lit chunks from
     * pre-light / newly generated snapshots without full NBT deserialization.
     */
    public boolean hasIsLightOn() {
        if (!hasResidualNbt()) {
            return false;
        }
        int resStart = residualRelStart();
        if (resStart + 2 <= chunkFrame.length) {
            int bpFlags = LcaSectionCodec.readUint16BE(chunkFrame, resStart);
            if ((bpFlags & 8) != 0) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSlabLocalPalette() {
        return (chunkFlags() & 2) != 0;
    }

    public boolean isScale4B() {
        return (chunkFlags() & 4) != 0;
    }

    public int sectionDirectoryEnd() {
        return 24 + (sectionCount << 1);
    }

    public int residualRelStart() {
        int dirEnd = sectionDirectoryEnd();
        if (!hasResidualNbt()) {
            return chunkFrame.length;
        }
        int unitShift = isScale4B() ? 2 : 0;
        return Math.min(chunkFrame.length, dirEnd + (residualNbtRelOffset << unitShift));
    }

    public int sectionDirectoryEntry16(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IndexOutOfBoundsException("sectionIndex=" + sectionIndex + " out of [0.." + (sectionCount - 1) + "]");
        }
        return LcaSectionCodec.readUint16BE(chunkFrame, 24 + (sectionIndex << 1));
    }

    /**
     * Computes the `ChunkFrame`-relative start offset for each non-uniform section (`-1` for empty
     * or inline uniform sections).
     */
    public int[] computeSectionRelStarts() {
        int[] entries = new int[sectionCount];
        try {
            return LcaRegionReader.computeSectionRelStarts(
                    chunkFrame,
                    0,
                    chunkFrame.length,
                    sectionCount,
                    sectionDirectoryEnd(),
                    residualRelStart(),
                    isScale4B(),
                    entries
            );
        } catch (CorruptRegionEntryException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Slices the exact encoded `SectionFrame` bytes for `sectionIndex` from {@link #chunkFrame}, or
     * returns an empty byte array when `sectionIndex` is empty (`0x0000`) or inline uniform (`bit 15 == 1`).
     */
    public byte[] sliceSectionFrame(int sectionIndex) {
        int entry16 = sectionDirectoryEntry16(sectionIndex);
        if (entry16 == 0 || (entry16 & 0x8000) != 0) {
            return LcaSectionCodec.EMPTY_BYTES;
        }
        int[] relStarts = computeSectionRelStarts();
        int start = relStarts[sectionIndex];
        if (start < 0 || start >= chunkFrame.length) {
            return LcaSectionCodec.EMPTY_BYTES;
        }
        int end = LcaRegionReader.findSectionRelEnd(
                relStarts, sectionIndex, sectionCount, residualRelStart(), chunkFrame.length);
        if (end <= start) {
            return LcaSectionCodec.EMPTY_BYTES;
        }
        return Arrays.copyOfRange(chunkFrame, start, end);
    }

    /**
     * Resolves the host runtime `BlockState` registry ID for an inline uniform section entry
     * (`bit 15 == 1`), or returns `0` for empty (`0x0000`) / unmapped entries.
     */
    public int resolveInlineUniformRuntimeStateId(
            int sectionIndex,
            java.util.function.Function<String, Integer> fallbackRuntimeLookup
    ) {
        int entry16 = sectionDirectoryEntry16(sectionIndex);
        if (entry16 == 0 || (entry16 & 0x8000) == 0) {
            return 0;
        }
        int paletteIdx = hasSlabLocalPalette() ? (entry16 & 0x07FF) : (entry16 & 0x1FFF);
        int[] palMap = buildEffectivePaletteToRuntimeIdMap(fallbackRuntimeLookup);
        return (palMap != null && paletteIdx >= 0 && paletteIdx < palMap.length) ? palMap[paletteIdx] : paletteIdx;
    }

    /**
     * Slices the encoded `SectionFrame` for `sectionIndex` and rewrites only its `4-32 B`
     * `VarInt[K]` palette header from disk dictionary / slab-local indices to host runtime
     * `BlockState` IDs without unpacking the Hilbert / Delta-Roaring voxel body (`leafanvil-ADR-005`).
     */
    public byte[] sliceSectionFrameWithRuntimePalette(
            int sectionIndex,
            java.util.function.Function<String, Integer> fallbackRuntimeLookup
    ) {
        byte[] rawSlice = sliceSectionFrame(sectionIndex);
        if (rawSlice.length == 0) {
            return rawSlice;
        }
        int[] palMap = buildEffectivePaletteToRuntimeIdMap(fallbackRuntimeLookup);
        if (palMap == null) {
            return rawSlice;
        }
        return rewriteSectionFramePaletteHeaderBytes(rawSlice, palMap);
    }

    private int[] buildEffectivePaletteToRuntimeIdMap(
            java.util.function.Function<String, Integer> fallbackRuntimeLookup
    ) {
        if (!hasSlabLocalPalette()) {
            return dictIdToRuntimeId;
        }
        if (fallbackRuntimeLookup == null && dictIdToRuntimeId == null) {
            return null;
        }
        try {
            int[] relStarts = computeSectionRelStarts();
            int limitRel = LcaSectionCodec.findFirstSectionRelStart(relStarts, sectionCount, residualRelStart());
            return LcaSectionCodec.decodeSlabLocalPaletteRuntimeIds(
                    chunkFrame, 0, sectionDirectoryEnd(), limitRel,
                    formatVersion, dictIdToRuntimeId, fallbackRuntimeLookup);
        } catch (CorruptRegionEntryException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static byte[] rewriteSectionFramePaletteHeaderBytes(byte[] sectionFrame, int[] paletteIdxToRuntimeId) {
        Objects.requireNonNull(sectionFrame, "sectionFrame");
        if (sectionFrame.length < 2 || paletteIdxToRuntimeId == null) {
            return sectionFrame;
        }
        try {
            int pos = 0;
            int sectionHeader = sectionFrame[pos++] & 0xFF;
            int blockMode = (sectionHeader >>> 4) & 3;
            if (blockMode != 0 && blockMode != 3) {
                if (pos + 8 > sectionFrame.length) {
                    return sectionFrame;
                }
                pos += 8;
            }
            int prefixLen = pos;
            int k = LcaSectionCodec.readVarInt(sectionFrame, pos, sectionFrame.length);
            pos += LcaSectionCodec.varIntLen(k);
            if (k == 0) {
                if ((blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING
                        && blockMode != LcaSectionCodec.BLOCK_MODE_DELTA_ROARING_FULL_MASK)
                        || pos >= sectionFrame.length) {
                    return sectionFrame;
                }
                int secExtFlags = sectionFrame[pos++] & 0xFF;
                if (!LcaV3Codec.isValidSecExtFlags(secExtFlags) || pos >= sectionFrame.length) {
                    return sectionFrame;
                }
                prefixLen = pos;
                k = LcaSectionCodec.readVarInt(sectionFrame, pos, sectionFrame.length);
                pos += LcaSectionCodec.varIntLen(k);
                if (k < 2 || k > 4096) {
                    return sectionFrame;
                }
            } else if (k < 1 || k > 4096) {
                return sectionFrame;
            }
            int[] mappedIds = new int[k];
            int newPalBytes = LcaSectionCodec.varIntLen(k);
            for (int i = 0; i < k; i++) {
                int diskId = LcaSectionCodec.readVarInt(sectionFrame, pos, sectionFrame.length);
                pos += LcaSectionCodec.varIntLen(diskId);
                int runtimeId = (diskId >= 0 && diskId < paletteIdxToRuntimeId.length)
                        ? paletteIdxToRuntimeId[diskId]
                        : diskId;
                mappedIds[i] = runtimeId;
                newPalBytes += LcaSectionCodec.varIntLen(runtimeId);
            }
            int bodyStart = pos;
            int bodyLen = sectionFrame.length - bodyStart;
            byte[] out = new byte[prefixLen + newPalBytes + bodyLen];
            System.arraycopy(sectionFrame, 0, out, 0, prefixLen);
            int writePos = LcaSectionCodec.writeVarIntToBuf(out, prefixLen, k);
            for (int i = 0; i < k; i++) {
                writePos = LcaSectionCodec.writeVarIntToBuf(out, writePos, mappedIds[i]);
            }
            if (bodyLen > 0) {
                System.arraycopy(sectionFrame, bodyStart, out, writePos, bodyLen);
            }
            return out;
        } catch (CorruptRegionEntryException e) {
            return sectionFrame;
        }
    }

    /**
     * Returns {@code true} if the standalone {@code SectionFrame} uses a {@code 0x0003}-only
     * encoding ({@code secExtFlags} {@code 0x01} Rank-Excluded, {@code 0x02} Gray Bitplanes,
     * or Eikonal light residuals) requiring {@code CAP_LCA_V3_SECTIONS} on the wire.
     */
    public static boolean isV3ExtendedSectionFrame(byte[] sectionFrame) {
        return LcaSectionCodec.isV3ExtendedSectionFrame(sectionFrame);
    }

    /**
     * Re-encodes a {@code 0x0003} {@code secExtFlags} ({@code 0x01} or {@code 0x02}) block payload
     * into a {@code 0x0002}-compatible {@code SectionFrame} while preserving trailing biome and
     * light bytes verbatim. Returns {@code sectionFrame} unchanged when no {@code secExtFlags}
     * block escape is present.
     */
    public static byte[] downgradeV3BlockSectionFrameToV2(byte[] sectionFrame) {
        return LcaSectionCodec.downgradeV3BlockSectionFrameToV2(sectionFrame);
    }

    /**
     * Slices the compact residual NBT tail bytes (heightmaps, block entities, ticks, structures, PDC)
     * from {@link #chunkFrame}, or returns an empty byte array when no residual tail is present.
     */
    public byte[] sliceResidualTailBytes() {
        if (!hasResidualNbt()) {
            return LcaSectionCodec.EMPTY_BYTES;
        }
        int resStart = residualRelStart();
        if (resStart >= chunkFrame.length) {
            return LcaSectionCodec.EMPTY_BYTES;
        }
        int[] relStarts = computeSectionRelStarts();
        int resEnd = LcaSectionCodec.findResidualRelEnd(relStarts, sectionCount, resStart, chunkFrame.length);
        if (resEnd <= resStart) {
            return LcaSectionCodec.EMPTY_BYTES;
        }
        return Arrays.copyOfRange(chunkFrame, resStart, resEnd);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RawChunkFrameSnapshot that)) return false;
        return dataVersion == that.dataVersion
                && minSectionY == that.minSectionY
                && sectionCount == that.sectionCount
                && residualNbtRelOffset == that.residualNbtRelOffset
                && Arrays.equals(chunkFrame, that.chunkFrame)
                && Arrays.equals(dictIdToRuntimeId, that.dictIdToRuntimeId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(dataVersion, minSectionY, sectionCount, residualNbtRelOffset);
        result = 31 * result + Arrays.hashCode(chunkFrame);
        result = 31 * result + Arrays.hashCode(dictIdToRuntimeId);
        return result;
    }

    @Override
    public String toString() {
        return "RawChunkFrameSnapshot[frameBytes=" + chunkFrame.length
                + ", dataVersion=" + dataVersion
                + ", minSectionY=" + minSectionY
                + ", sectionCount=" + sectionCount
                + ", residualNbtRelOffset=" + residualNbtRelOffset
                + ", dictEntries=" + (dictIdToRuntimeId != null ? dictIdToRuntimeId.length : 0)
                + "]";
    }
}

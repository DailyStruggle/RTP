package io.github.dailystruggle.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-pass streaming binary Mojang NBT chunk visitor and `.lca` `ChunkFrame` slab encoder
 * (`leafanvil-ADR-005` Workstream 1).
 *
 * <p>Visits uncompressed binary Mojang NBT ({@code byte[]} or {@link ByteBuffer}) in a single
 * forward pass directly into {@link LcaSectionCodec.ScratchArena} and primitive section arrays,
 * supporting both legacy ({@code DataVersion < 5006}: {@code "Name"} / {@code "Properties"}) and
 * modern ({@code DataVersion >= 5006}: {@code "id"} / {@code "properties"}, {@code ADR-113})
 * block-state palette schemas while slicing non-voxel residual compounds ({@code block_entities},
 * {@code block_ticks}, {@code fluid_ticks}, {@code structures}, {@code BukkitValues} PDC) verbatim
 * into {@code residualNbtPayload} without materializing intermediate {@link LinkedHashMap} or
 * {@link ArrayList} NBT trees.</p>
 *
 * @governedBy leafanvil-ADR-005
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-113
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaStreamingNbtEncoder {

    private static final String AIR_STATE = "minecraft:air";
    private static final String PLAINS_BIOME = "minecraft:plains";

    private LcaStreamingNbtEncoder() {}

    /**
     * Parses uncompressed binary Mojang NBT from {@code uncompressedNbtBytes[offset .. offset + length)}
     * and encodes a `.lca` `ChunkFrame` slab payload for {@code archive} without allocating a root
     * {@code Map<String, Object>} tree.
     */
    public static byte[] encodeChunkSlab(
            LcaRegionWriter.LcaLiveArchive archive,
            int fallbackRegionX,
            int fallbackRegionZ,
            byte[] uncompressedNbtBytes,
            int offset,
            int length
    ) throws IOException {
        if (uncompressedNbtBytes == null || length <= 0) {
            return new byte[0];
        }
        if (offset < 0 || offset + length > uncompressedNbtBytes.length) {
            throw new IOException(
                    "Invalid NBT slice bounds: offset=" + offset + ", length=" + length
                            + ", arrayLength=" + uncompressedNbtBytes.length);
        }
        return encodeChunkSlab(
                archive,
                fallbackRegionX,
                fallbackRegionZ,
                ByteBuffer.wrap(uncompressedNbtBytes, offset, length));
    }

    /**
     * Parses uncompressed binary Mojang NBT from a zero-copy {@link ByteBuffer} view (heap or direct)
     * without mutating the caller's buffer position/limit, returning the encoded `.lca` `ChunkFrame`
     * slab payload.
     */
    public static byte[] encodeChunkSlab(
            LcaRegionWriter.LcaLiveArchive archive,
            int fallbackRegionX,
            int fallbackRegionZ,
            ByteBuffer uncompressedNbt
    ) throws IOException {
        if (archive == null) {
            throw new IllegalArgumentException("archive must not be null");
        }
        if (uncompressedNbt == null || !uncompressedNbt.hasRemaining()) {
            return new byte[0];
        }
        StreamedChunk parsed = parseChunk(
                uncompressedNbt,
                archive.minSectionY(),
                archive.sectionCount(),
                archive.defaultDataVersion(),
                archive.storageKind() == LcaRegionReader.STORAGE_KIND_REGION_VOXEL);
        int rx = parsed.resolveLocalX(fallbackRegionX);
        int rz = parsed.resolveLocalZ(fallbackRegionZ);
        return encodeStreamedChunk(archive, rx, rz, parsed);
    }

    /**
     * Encodes a chunk from {@code uncompressedNbt} for an explicit region-local {@code (rx, rz)} slot.
     */
    static byte[] encodeChunkSlabAtLocalCoords(
            LcaRegionWriter.LcaLiveArchive archive,
            int rx,
            int rz,
            ByteBuffer uncompressedNbt
    ) throws IOException {
        if (uncompressedNbt == null || !uncompressedNbt.hasRemaining()) {
            throw new IOException("Empty uncompressed chunk NBT buffer");
        }
        StreamedChunk parsed = parseChunk(
                uncompressedNbt,
                archive.minSectionY(),
                archive.sectionCount(),
                archive.defaultDataVersion(),
                archive.storageKind() == LcaRegionReader.STORAGE_KIND_REGION_VOXEL);
        return encodeStreamedChunk(archive, rx & 31, rz & 31, parsed);
    }

    static byte[] encodeStreamedChunk(
            LcaRegionWriter.LcaLiveArchive archive,
            int rx,
            int rz,
            StreamedChunk chunk
    ) throws IOException {
        int sectionCount = archive.sectionCount();
        boolean hasVoxelSections = chunk.hasVoxelSections;
        boolean factoredV3 = archive.isFactoredV3Dict();
        boolean useSlabLocalPalette = hasVoxelSections && (factoredV3 || archive.totalDictionaryStates() < 8192);

        Map<String, Integer> localStateMap = useSlabLocalPalette ? new LinkedHashMap<>(32) : null;
        List<String> localStateList = useSlabLocalPalette ? new ArrayList<>(32) : null;
        List<Integer> localStateGlobalIds = useSlabLocalPalette ? new ArrayList<>(32) : null;
        Map<String, Integer> localBiomeMap = useSlabLocalPalette ? new LinkedHashMap<>(8) : null;
        List<String> localBiomeList = useSlabLocalPalette ? new ArrayList<>(8) : null;
        List<Integer> localBiomeGlobalIds = (useSlabLocalPalette && factoredV3) ? new ArrayList<>(8) : null;
        if (useSlabLocalPalette) {
            localStateMap.put(AIR_STATE, 0);
            localStateList.add(AIR_STATE);
            localStateGlobalIds.add(factoredV3 ? archive.registerOrGetBlockStateId(AIR_STATE) : -1);
            localBiomeMap.put(PLAINS_BIOME, 0);
            localBiomeList.add(PLAINS_BIOME);
            if (factoredV3) {
                localBiomeGlobalIds.add(archive.registerOrGetBiomeId(PLAINS_BIOME));
            }
        }

        int chunkDefaultBiomeDictId = -1;
        int bestBiomeCount = 0;
        int[] biomeCandidateIds = new int[8];
        int[] biomeCandidateCounts = new int[8];
        int biomeCandidatesUsed = 0;

        int[][] sectionBiomeIds = new int[sectionCount][];
        long[][] sectionBiomeData = new long[sectionCount][];
        List<LcaSectionCodec.BiomeTemplate> rawTemplates =
                useSlabLocalPalette ? new ArrayList<>(8) : Collections.emptyList();
        int[] rawTemplateCounts = useSlabLocalPalette ? new int[24] : null;
        int[] sectionRawTemplateIdx = new int[sectionCount];
        Arrays.fill(sectionRawTemplateIdx, -1);

        if (hasVoxelSections) {
            for (int idx = 0; idx < sectionCount; idx++) {
                StreamedSection sec = chunk.sectionsByIdx[idx];
                if (sec == null) {
                    continue;
                }
                int[] bIds = resolveBiomeDictIds(
                        archive, sec.biomePalette, localBiomeMap, localBiomeList, localBiomeGlobalIds);
                long[] bData = sec.biomeData;
                sectionBiomeIds[idx] = bIds;
                sectionBiomeData[idx] = bData;

                if (useSlabLocalPalette && bIds != null && bIds.length > 0) {
                    int foundT = -1;
                    for (int t = 0; t < rawTemplates.size(); t++) {
                        if (rawTemplates.get(t).matches(bIds, bData)) {
                            foundT = t;
                            break;
                        }
                    }
                    if (foundT < 0 && rawTemplates.size() < 24) {
                        foundT = rawTemplates.size();
                        rawTemplates.add(
                                new LcaSectionCodec.BiomeTemplate(bIds, bData, Collections.emptyList(), null));
                    }
                    if (foundT >= 0) {
                        rawTemplateCounts[foundT]++;
                        sectionRawTemplateIdx[idx] = foundT;
                    }
                }

                if (bIds != null && bIds.length == 1 && bIds[0] < 30) {
                    int singleBId = bIds[0];
                    int foundSlot = -1;
                    for (int k = 0; k < biomeCandidatesUsed; k++) {
                        if (biomeCandidateIds[k] == singleBId) {
                            foundSlot = k;
                            break;
                        }
                    }
                    if (foundSlot < 0 && biomeCandidatesUsed < biomeCandidateIds.length) {
                        foundSlot = biomeCandidatesUsed++;
                        biomeCandidateIds[foundSlot] = singleBId;
                    }
                    if (foundSlot >= 0) {
                        int cnt = ++biomeCandidateCounts[foundSlot];
                        if (cnt > bestBiomeCount) {
                            bestBiomeCount = cnt;
                            chunkDefaultBiomeDictId = singleBId;
                        }
                    }
                }
            }
        }

        List<LcaSectionCodec.BiomeTemplate> sortedBiomeTemplates = Collections.emptyList();
        int[] oldToSortedTemplate = null;
        if (useSlabLocalPalette && !rawTemplates.isEmpty()) {
            int tSize = rawTemplates.size();
            Integer[] tOrder = new Integer[tSize];
            for (int t = 0; t < tSize; t++) {
                tOrder[t] = t;
            }
            Arrays.sort(tOrder, (a, b) -> Integer.compare(rawTemplateCounts[b], rawTemplateCounts[a]));
            sortedBiomeTemplates = new ArrayList<>(tSize);
            oldToSortedTemplate = new int[tSize];
            for (int newIdx = 0; newIdx < tSize; newIdx++) {
                int oldIdx = tOrder[newIdx];
                oldToSortedTemplate[oldIdx] = newIdx;
                sortedBiomeTemplates.add(rawTemplates.get(oldIdx));
            }
        }

        LcaSectionCodec.ScratchArena arena = LcaSectionCodec.scratch();
        LcaSectionCodec.EncodedSection[] encodedSections = new LcaSectionCodec.EncodedSection[sectionCount];
        int totalSectionPayloadBytes = 0;
        int formatVersion = archive.formatVersion();
        boolean allowV3Eikonal = formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3
                && archive.storageKind() != 3
                && (archive.writeModeBLcd2() || (LcaV3Codec.currentFeatureMask() & LcaV3Codec.FEATURE_EIKONAL_MODE_A) != 0);
        int[] modeBLitLen = archive.writeModeBLcd2() ? archive.atomicDictLitLen() : null;
        byte[] cachedLightV1 = null;
        int cachedLightV1Len = 0;

        for (int i = 0; i < sectionCount; i++) {
            StreamedSection sec = chunk.sectionsByIdx[i];
            if (sec == null || sec.blockPalette == null || sec.blockPalette.length == 0) {
                continue;
            }
            int[] blockDictIds = resolveBlockPaletteIds(
                    archive, sec.blockPalette, localStateMap, localStateList, localStateGlobalIds);
            if (blockDictIds == null || blockDictIds.length == 0) {
                continue;
            }
            long[] blockData = sec.blockData;
            if (blockData != null && blockData.length <= arena.packedBlockLongs.length) {
                System.arraycopy(blockData, 0, arena.packedBlockLongs, 0, blockData.length);
            }
            int secTmplIdx = (oldToSortedTemplate != null && sectionRawTemplateIdx[i] >= 0)
                    ? oldToSortedTemplate[sectionRawTemplateIdx[i]]
                    : -1;

            byte[] secLightV1 = null;
            if (allowV3Eikonal && (sec.skyLight != null || sec.blockLight != null)) {
                if (useSlabLocalPalette) {
                    int curSize = localStateList.size();
                    if (cachedLightV1 == null || cachedLightV1.length < curSize) {
                        cachedLightV1 = cachedLightV1 == null
                                ? new byte[Math.max(64, curSize)]
                                : Arrays.copyOf(cachedLightV1, Math.max(curSize, cachedLightV1.length << 1));
                    }
                    for (int sIdx = cachedLightV1Len; sIdx < curSize; sIdx++) {
                        cachedLightV1[sIdx] = LcaV3Codec.classifyLightV1(localStateList.get(sIdx));
                    }
                    cachedLightV1Len = curSize;
                    secLightV1 = cachedLightV1;
                } else {
                    secLightV1 = archive.buildGlobalStatePackedLightV1();
                }
            }

            LcaSectionCodec.EncodedSection enc = LcaSectionCodec.encodeSection(
                    blockDictIds,
                    blockData,
                    sectionBiomeIds[i],
                    sectionBiomeData[i],
                    sec.skyLight,
                    sec.blockLight,
                    chunkDefaultBiomeDictId,
                    secTmplIdx,
                    formatVersion,
                    secLightV1,
                    modeBLitLen,
                    archive.isDirectFlatBitPack());
            encodedSections[i] = enc;
            if (enc.inlineEntry16 == 0 && enc.frameBytes.length > 0) {
                totalSectionPayloadBytes += enc.frameBytes.length;
            }
        }

        boolean effectiveModeB = archive.isWriteModeBLcd2();
        byte[] residualNbtBytes = buildCompactResidualTail(
                chunk, archive.minSectionY(), sectionCount, !effectiveModeB, arena,
                (archive.regionX() << 5) + rx, (archive.regionZ() << 5) + rz, formatVersion);

        int headerLen = 24 + (sectionCount << 1);
        byte[] chunkFrame = LcaRegionWriter.LcaLiveArchive.assembleChunkFrame(
                rx, rz, sectionCount, chunk.chunkDataVersion, chunk.lastUpdate, chunk.inhabitedTime,
                chunk.statusOrdinal, chunkDefaultBiomeDictId, useSlabLocalPalette, factoredV3,
                localStateList, localStateGlobalIds, localBiomeList, localBiomeGlobalIds,
                sortedBiomeTemplates, encodedSections, totalSectionPayloadBytes, residualNbtBytes);

        if (effectiveModeB) {
            return archive.encodeModeBWithAtomicDictForStreaming(chunkFrame, headerLen);
        }
        return chunkFrame;
    }

    private static int[] resolveBlockPaletteIds(
            LcaRegionWriter.LcaLiveArchive archive,
            String[] paletteStates,
            Map<String, Integer> localStateMap,
            List<String> localStateList,
            List<Integer> localStateGlobalIds
    ) {
        boolean factoredV3 = archive.isFactoredV3Dict();
        int[] out = new int[paletteStates.length];
        for (int i = 0; i < paletteStates.length; i++) {
            String canonical = paletteStates[i];
            if (localStateMap != null) {
                Integer locId = localStateMap.get(canonical);
                if (locId == null) {
                    locId = localStateList.size();
                    localStateMap.put(canonical, locId);
                    localStateList.add(canonical);
                    int globalId;
                    if (factoredV3) {
                        globalId = archive.registerOrGetBlockStateId(canonical);
                    } else {
                        int bracket = canonical.indexOf('[');
                        globalId = -1;
                        if (bracket >= 0
                                && !LcaV2LegacyCodec.STATIC_CORE_PROP_TO_ID.containsKey(canonical.substring(bracket))) {
                            globalId = archive.registerOrGetBlockStateId(canonical);
                        }
                    }
                    localStateGlobalIds.add(globalId);
                }
                out[i] = locId;
            } else {
                out[i] = archive.registerOrGetBlockStateId(canonical);
            }
        }
        return out;
    }

    private static int[] resolveBiomeDictIds(
            LcaRegionWriter.LcaLiveArchive archive,
            String[] biomePalette,
            Map<String, Integer> localBiomeMap,
            List<String> localBiomeList,
            List<Integer> localBiomeGlobalIds
    ) {
        if (biomePalette == null || biomePalette.length == 0) {
            return null;
        }
        int[] out = new int[biomePalette.length];
        for (int i = 0; i < biomePalette.length; i++) {
            String biomeId = biomePalette[i] != null ? biomePalette[i] : PLAINS_BIOME;
            if (localBiomeMap != null) {
                Integer existing = localBiomeMap.get(biomeId);
                if (existing == null) {
                    int id = localBiomeList.size();
                    localBiomeMap.put(biomeId, id);
                    localBiomeList.add(biomeId);
                    if (localBiomeGlobalIds != null) {
                        localBiomeGlobalIds.add(archive.registerOrGetBiomeId(biomeId));
                    }
                    out[i] = id;
                } else {
                    out[i] = existing;
                }
            } else {
                out[i] = archive.registerOrGetBiomeId(biomeId);
            }
        }
        return out;
    }

    private static byte[] buildCompactResidualTail(
            StreamedChunk chunk,
            int minSectionY,
            int sectionCount,
            boolean allowDeflateResidual,
            LcaSectionCodec.ScratchArena arena,
            int chunkX,
            int chunkZ,
            int formatVersion
    ) throws IOException {
        boolean hasVoxelSections = chunk.hasVoxelSections;
        int bpFlags = hasVoxelSections ? 0 : 2048;
        int extFlags = 0;
        byte[] extBlockTicks = null;
        byte[] extFluidTicks = null;
        byte[] extBlockEntities = null;
        int ppEmptyCount = 0;
        int starlightVer = 0;
        int lowBoundaryFlags = -1;
        int highBoundaryFlags = -1;
        byte[] mbnlPrefix = null;
        List<String> secHmKeys = new ArrayList<>(6);
        List<byte[]> secHmPayloads = new ArrayList<>(6);

        int rawOutPos = 0;
        byte[] rawNbtScratch = arena.ensureNbtCap(Math.max(1024, chunk.sourceBuf.remaining() + 128));
        // Reserve 3-byte root TAG_Compound header (0x0A, 0x00, 0x00)
        rawNbtScratch[rawOutPos++] = Nbt.TAG_COMPOUND;
        rawNbtScratch[rawOutPos++] = 0;
        rawNbtScratch[rawOutPos++] = 0;
        int initialHeaderPos = rawOutPos;

        for (int i = 0; i < chunk.rootTagCount; i++) {
            String k = chunk.rootTagKeys[i];
            int start = chunk.rootTagStarts[i];
            int end = chunk.rootTagEnds[i];
            int flag = chunk.rootTagCompactFlags[i];
            int aux = chunk.rootTagAuxInts[i];

            if (!hasVoxelSections) {
                if ("DataVersion".equals(k)) {
                    continue;
                }
                rawNbtScratch = appendRawSlice(arena, rawNbtScratch, rawOutPos, chunk.sourceBuf, start, end);
                rawOutPos += (end - start);
                continue;
            }

            if ("sections".equals(k)
                    || "Sections".equals(k)
                    || "DataVersion".equals(k)
                    || "LastUpdate".equals(k)
                    || "InhabitedTime".equals(k)
                    || "xPos".equals(k)
                    || "zPos".equals(k)
                    || "yPos".equals(k)
                    || ("Status".equals(k)
                            && chunk.statusOrdinal != LcaSectionCodec.STATUS_ORDINAL_RESIDUAL
                            && chunk.statusOrdinal != LcaSectionCodec.STATUS_ORDINAL_ABSENT)) {
                continue;
            }

            if (flag == 1 || flag == 2 || flag == 4 || flag == 8 || flag == 16) {
                bpFlags |= flag;
                continue;
            }
            if (flag == 32) {
                bpFlags |= 32;
                ppEmptyCount = aux;
                continue;
            }
            if (flag == 64) {
                bpFlags |= 64;
                starlightVer = aux;
                continue;
            }
            if (flag == 512) {
                // Heightmaps compound
                if (chunk.heightmapKeys != null && !chunk.heightmapKeys.isEmpty()) {
                    List<long[]> seenLongs = new ArrayList<>(chunk.heightmapKeys.size());
                    int mbnlIdx = chunk.heightmapKeys.indexOf("MOTION_BLOCKING_NO_LEAVES");
                    if (mbnlIdx >= 0) {
                        long[] mbnlLongs = chunk.heightmapValues.get(mbnlIdx);
                        seenLongs.add(mbnlLongs);
                        mbnlPrefix = LcaRegionWriter.LcaLiveArchive.encodeDeltaHeightmapLongs(mbnlLongs);
                    }
                    for (int h = 0; h < chunk.heightmapKeys.size(); h++) {
                        String hmKey = chunk.heightmapKeys.get(h);
                        long[] longs = chunk.heightmapValues.get(h);
                        if ("MOTION_BLOCKING_NO_LEAVES".equals(hmKey) && mbnlPrefix != null) {
                            continue;
                        }
                        int dupIdx = -1;
                        for (int prevIdx = 0; prevIdx < seenLongs.size(); prevIdx++) {
                            if (Arrays.equals(seenLongs.get(prevIdx), longs)) {
                                dupIdx = prevIdx;
                                break;
                            }
                        }
                        byte[] hmPayload;
                        if (dupIdx >= 0) {
                            hmPayload = new byte[] {(byte) 0xFE, (byte) dupIdx};
                        } else if (!seenLongs.isEmpty()) {
                            byte[] bestCross = null;
                            for (int refIdx = 0; refIdx < seenLongs.size(); refIdx++) {
                                long[] candBase = seenLongs.get(refIdx);
                                if (candBase.length == longs.length) {
                                    byte[] cross = LcaRegionWriter.LcaLiveArchive
                                            .encodeCrossDeltaHeightmapLongs(candBase, refIdx, longs);
                                    if (bestCross == null || cross.length < bestCross.length) {
                                        bestCross = cross;
                                    }
                                }
                            }
                            hmPayload = (bestCross != null)
                                    ? bestCross
                                    : LcaRegionWriter.LcaLiveArchive.encodeDeltaHeightmapLongs(longs);
                        } else {
                            hmPayload = LcaRegionWriter.LcaLiveArchive.encodeDeltaHeightmapLongs(longs);
                        }
                        seenLongs.add(longs);
                        secHmKeys.add(hmKey);
                        secHmPayloads.add(hmPayload);
                    }
                    if (!secHmKeys.isEmpty()) {
                        bpFlags |= 512;
                    }
                }
                continue;
            }

            if (formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3) {
                if ("block_ticks".equals(k)) {
                    byte[] encodedBt = LcaV3Codec.tryEncodeColumnarTicksFromNbtSlice(
                            chunk.sourceBuf, start, end, chunkX, chunkZ);
                    if (encodedBt != null) {
                        extBlockTicks = encodedBt;
                        extFlags |= LcaV3Codec.EXT_FLAG_COLUMNAR_BLOCK_TICKS;
                        continue;
                    }
                } else if ("fluid_ticks".equals(k)) {
                    byte[] encodedFt = LcaV3Codec.tryEncodeColumnarTicksFromNbtSlice(
                            chunk.sourceBuf, start, end, chunkX, chunkZ);
                    if (encodedFt != null) {
                        extFluidTicks = encodedFt;
                        extFlags |= LcaV3Codec.EXT_FLAG_COLUMNAR_FLUID_TICKS;
                        continue;
                    }
                } else if ("block_entities".equals(k)) {
                    byte[] encodedBe = LcaV3Codec.tryEncodeColumnarBlockEntitiesFromNbtSlice(
                            chunk.sourceBuf, start, end, chunkX, chunkZ);
                    if (encodedBe != null) {
                        extBlockEntities = encodedBe;
                        extFlags |= LcaV3Codec.EXT_FLAG_COLUMNAR_BLOCK_ENTITIES;
                        continue;
                    }
                }
            }

            rawNbtScratch = appendRawSlice(arena, rawNbtScratch, rawOutPos, chunk.sourceBuf, start, end);
            rawOutPos += (end - start);
        }

        if (hasVoxelSections && chunk.lowBoundarySections != null && !chunk.lowBoundarySections.isEmpty()) {
            lowBoundaryFlags = tryEncodeCompactBoundarySection(chunk.lowBoundarySections, minSectionY - 1);
            if (lowBoundaryFlags >= 0) {
                bpFlags |= 128;
            } else {
                rawOutPos = appendBoundarySectionListTag(
                        arena, "_lca_boundary_low", chunk.lowBoundarySections, chunk.sourceBuf, rawOutPos);
                rawNbtScratch = arena.nbtOutBuf;
            }
        }
        if (hasVoxelSections && chunk.highBoundarySections != null && !chunk.highBoundarySections.isEmpty()) {
            highBoundaryFlags = tryEncodeCompactBoundarySection(
                    chunk.highBoundarySections, minSectionY + sectionCount);
            if (highBoundaryFlags >= 0) {
                bpFlags |= 256;
            } else {
                rawOutPos = appendBoundarySectionListTag(
                        arena, "_lca_boundary_high", chunk.highBoundarySections, chunk.sourceBuf, rawOutPos);
                rawNbtScratch = arena.nbtOutBuf;
            }
        }

        byte[] nbtBytes;
        if (rawOutPos == initialHeaderPos) {
            nbtBytes = new byte[0];
        } else {
            rawNbtScratch = arena.ensureNbtCap(rawOutPos + 1);
            rawNbtScratch[rawOutPos++] = Nbt.TAG_END;
            nbtBytes = Arrays.copyOf(rawNbtScratch, rawOutPos);
        }

        return LcaRegionWriter.LcaLiveArchive.assembleResidualTailBytes(
                nbtBytes,
                allowDeflateResidual,
                bpFlags,
                extFlags,
                mbnlPrefix,
                ppEmptyCount,
                starlightVer,
                lowBoundaryFlags,
                highBoundaryFlags,
                secHmKeys,
                secHmPayloads,
                extBlockTicks,
                extFluidTicks,
                extBlockEntities);
    }

    private static byte[] appendRawSlice(
            LcaSectionCodec.ScratchArena arena,
            byte[] dst,
            int dstPos,
            ByteBuffer src,
            int start,
            int end
    ) {
        int len = end - start;
        if (len <= 0) {
            return dst;
        }
        byte[] ensured = arena.ensureNbtCap(dstPos + len + 16);
        ByteBuffer dup = src.duplicate();
        dup.position(start).limit(end);
        dup.get(ensured, dstPos, len);
        return ensured;
    }

    private static int appendBoundarySectionListTag(
            LcaSectionCodec.ScratchArena arena,
            String tagName,
            List<BoundarySectionSlice> slices,
            ByteBuffer src,
            int dstPos
    ) {
        byte[] nameUtf = tagName.getBytes(StandardCharsets.UTF_8);
        int totalBody = 0;
        for (BoundarySectionSlice s : slices) {
            totalBody += (s.payloadEnd - s.payloadStart);
        }
        byte[] buf = arena.ensureNbtCap(dstPos + 3 + nameUtf.length + 5 + totalBody + 16);
        buf[dstPos++] = Nbt.TAG_LIST;
        buf[dstPos++] = (byte) ((nameUtf.length >>> 8) & 0xFF);
        buf[dstPos++] = (byte) (nameUtf.length & 0xFF);
        System.arraycopy(nameUtf, 0, buf, dstPos, nameUtf.length);
        dstPos += nameUtf.length;
        buf[dstPos++] = Nbt.TAG_COMPOUND;
        int count = slices.size();
        buf[dstPos++] = (byte) ((count >>> 24) & 0xFF);
        buf[dstPos++] = (byte) ((count >>> 16) & 0xFF);
        buf[dstPos++] = (byte) ((count >>> 8) & 0xFF);
        buf[dstPos++] = (byte) (count & 0xFF);
        ByteBuffer dup = src.duplicate();
        for (BoundarySectionSlice s : slices) {
            int len = s.payloadEnd - s.payloadStart;
            dup.position(s.payloadStart).limit(s.payloadEnd);
            dup.get(buf, dstPos, len);
            dstPos += len;
        }
        return dstPos;
    }

    private static int tryEncodeCompactBoundarySection(List<BoundarySectionSlice> list, int expectedY) {
        if (list.size() != 1) {
            return -1;
        }
        BoundarySectionSlice sec = list.get(0);
        if (!sec.hasY || sec.sectionY != expectedY || sec.hasBlockStates) {
            return -1;
        }
        if (sec.skyMode < 0 || sec.blkMode < 0) {
            return -1;
        }
        return sec.skyMode | (sec.blkMode << 2);
    }

    // ----------------------------------------------------------------------------------------- single-pass NBT parser

    static StreamedChunk parseChunk(
            ByteBuffer rawInput,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean voxelStorageKind
    ) throws IOException {
        ByteBuffer buf = rawInput.duplicate().order(ByteOrder.BIG_ENDIAN);
        requireRemaining(buf, 3, "root header");
        byte rootType = buf.get();
        if (rootType != Nbt.TAG_COMPOUND) {
            throw new IOException("Expected root TAG_Compound (10), got " + rootType);
        }
        int rootNameLen = Short.toUnsignedInt(buf.getShort());
        requireRemaining(buf, rootNameLen, "root name");
        buf.position(buf.position() + rootNameLen);

        StreamedChunk out = new StreamedChunk(buf, sectionCount, defaultDataVersion);
        boolean parsedPrimarySections = false;

        while (true) {
            requireRemaining(buf, 1, "root child tag type");
            int tagStart = buf.position();
            byte childType = buf.get();
            if (childType == Nbt.TAG_END) {
                break;
            }
            String key = readModifiedUtf8(buf);

            switch (key) {
                case "DataVersion" -> {
                    if (isNumericTag(childType)) {
                        int dv = (int) readNumericTagAsLong(buf, childType);
                        if (dv > 0) {
                            out.chunkDataVersion = dv;
                        }
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "LastUpdate" -> {
                    if (isNumericTag(childType)) {
                        out.lastUpdate = readNumericTagAsLong(buf, childType);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "InhabitedTime" -> {
                    if (isNumericTag(childType)) {
                        out.inhabitedTime = readNumericTagAsLong(buf, childType);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "xPos" -> {
                    if (isNumericTag(childType)) {
                        out.directXPos = (int) readNumericTagAsLong(buf, childType);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "zPos" -> {
                    if (isNumericTag(childType)) {
                        out.directZPos = (int) readNumericTagAsLong(buf, childType);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "yPos" -> {
                    skipPayload(buf, childType, 1);
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "Status" -> {
                    if (childType == Nbt.TAG_STRING) {
                        String status = readModifiedUtf8(buf);
                        out.statusOrdinal = LcaRegionReader.encodeStatusOrdinal(status);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "Position" -> {
                    if (childType == Nbt.TAG_INT_ARRAY) {
                        requireRemaining(buf, 4, "Position int[] length");
                        int len = buf.getInt();
                        validateArrayLength(buf, len, 4, "Position");
                        if (len >= 2) {
                            int px = buf.getInt();
                            int pz = buf.getInt();
                            out.positionCoords = new int[] {px, pz};
                            buf.position(buf.position() + (len - 2) * 4);
                        } else {
                            buf.position(buf.position() + len * 4);
                        }
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "Level" -> {
                    if (childType == Nbt.TAG_COMPOUND) {
                        scanLevelCoords(buf, out, 1);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "sections", "Sections" -> {
                    boolean isPrimary = "sections".equals(key) || !parsedPrimarySections;
                    if (voxelStorageKind && childType == Nbt.TAG_LIST && isPrimary) {
                        if ("sections".equals(key)) {
                            parsedPrimarySections = true;
                            // Reset if lowercase "sections" arrived after uppercase "Sections"
                            Arrays.fill(out.sectionsByIdx, null);
                            out.lowBoundarySections = null;
                            out.highBoundarySections = null;
                        }
                        out.hasVoxelSections = true;
                        parseSectionsList(buf, out, minSectionY, sectionCount);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
                case "block_entities" -> {
                    int flag = parsePossiblyEmptyList(buf, childType, 1);
                    out.recordRootTag(key, tagStart, buf.position(), flag, 0);
                }
                case "block_ticks" -> {
                    int flag = parsePossiblyEmptyList(buf, childType, 2);
                    out.recordRootTag(key, tagStart, buf.position(), flag, 0);
                }
                case "fluid_ticks" -> {
                    int flag = parsePossiblyEmptyList(buf, childType, 4);
                    out.recordRootTag(key, tagStart, buf.position(), flag, 0);
                }
                case "isLightOn" -> {
                    int flag = 0;
                    if (childType == Nbt.TAG_BYTE) {
                        requireRemaining(buf, 1, "isLightOn");
                        byte b = buf.get();
                        if (b == (byte) 1) {
                            flag = 8;
                        }
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), flag, 0);
                }
                case "structures" -> {
                    int flag = 0;
                    if (childType == Nbt.TAG_COMPOUND && scanIsEmptyVanillaStructures(buf, 1)) {
                        flag = 16;
                    } else if (childType != Nbt.TAG_COMPOUND) {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), flag, 0);
                }
                case "PostProcessing" -> {
                    int emptyListCount = -1;
                    if (childType == Nbt.TAG_LIST) {
                        emptyListCount = scanAllEmptySubLists(buf, 1);
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(
                            key,
                            tagStart,
                            buf.position(),
                            emptyListCount >= 0 ? 32 : 0,
                            Math.max(0, emptyListCount));
                }
                case "starlight.light_version" -> {
                    int flag = 0;
                    int ver = 0;
                    if (childType == Nbt.TAG_INT) {
                        requireRemaining(buf, 4, "starlight.light_version");
                        int iv = buf.getInt();
                        if (iv >= 0 && iv <= 1024) {
                            flag = 64;
                            ver = iv;
                        }
                    } else {
                        skipPayload(buf, childType, 1);
                    }
                    out.recordRootTag(key, tagStart, buf.position(), flag, ver);
                }
                case "Heightmaps" -> {
                    if (childType == Nbt.TAG_COMPOUND) {
                        parseHeightmapsCompound(buf, out, 1);
                        out.recordRootTag(key, tagStart, buf.position(), 512, 0);
                    } else {
                        skipPayload(buf, childType, 1);
                        out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                    }
                }
                default -> {
                    skipPayload(buf, childType, 1);
                    out.recordRootTag(key, tagStart, buf.position(), 0, 0);
                }
            }
        }
        return out;
    }

    private static void parseSectionsList(
            ByteBuffer buf, StreamedChunk out, int minSectionY, int sectionCount
    ) throws IOException {
        requireRemaining(buf, 5, "sections TAG_List header");
        byte elemType = buf.get();
        int n = buf.getInt();
        if (n < 0 || n > Nbt.MAX_ARRAY_LENGTH) {
            throw new IOException("Malformed sections TAG_List length: " + n);
        }
        if (elemType == Nbt.TAG_END) {
            if (n > 0) {
                throw new IOException("sections TAG_List declared TAG_End with nonzero length " + n);
            }
            return;
        }
        if (elemType != Nbt.TAG_COMPOUND) {
            for (int i = 0; i < n; i++) {
                skipPayload(buf, elemType, 2);
            }
            return;
        }

        StringBuilder stateSb = new StringBuilder(64);
        for (int i = 0; i < n; i++) {
            int secPayloadStart = buf.position();
            boolean hasY = false;
            int sy = 0;
            boolean hasBlockStatesCompound = false;
            String[] blockPalette = null;
            long[] blockData = null;
            String[] biomePalette = null;
            long[] biomeData = null;
            byte[] skyLight = null;
            byte[] blockLight = null;
            int skyMode = 0;
            int blkMode = 0;
            boolean hasSkyLightTag = false;
            boolean hasBlockLightTag = false;
            int starlightSkyState = -1;
            int starlightBlkState = -1;

            while (true) {
                requireRemaining(buf, 1, "section child tag");
                byte t = buf.get();
                if (t == Nbt.TAG_END) {
                    break;
                }
                String k = readModifiedUtf8(buf);
                switch (k) {
                    case "Y" -> {
                        if (isNumericTag(t)) {
                            sy = (int) readNumericTagAsLong(buf, t);
                            hasY = true;
                        } else {
                            skipPayload(buf, t, 3);
                        }
                    }
                    case "block_states" -> {
                        if (t == Nbt.TAG_COMPOUND) {
                            hasBlockStatesCompound = true;
                            while (true) {
                                requireRemaining(buf, 1, "block_states child tag");
                                byte bst = buf.get();
                                if (bst == Nbt.TAG_END) {
                                    break;
                                }
                                String bsk = readModifiedUtf8(buf);
                                if ("palette".equals(bsk) && bst == Nbt.TAG_LIST) {
                                    blockPalette = parseBlockStatePaletteList(buf, stateSb);
                                } else if ("data".equals(bsk) && bst == Nbt.TAG_LONG_ARRAY) {
                                    blockData = readLongArrayPayload(buf);
                                } else {
                                    skipPayload(buf, bst, 4);
                                }
                            }
                        } else {
                            skipPayload(buf, t, 3);
                        }
                    }
                    case "biomes" -> {
                        if (t == Nbt.TAG_COMPOUND) {
                            while (true) {
                                requireRemaining(buf, 1, "biomes child tag");
                                byte bt = buf.get();
                                if (bt == Nbt.TAG_END) {
                                    break;
                                }
                                String bk = readModifiedUtf8(buf);
                                if ("palette".equals(bk) && bt == Nbt.TAG_LIST) {
                                    biomePalette = parseBiomePaletteList(buf);
                                } else if ("data".equals(bk) && bt == Nbt.TAG_LONG_ARRAY) {
                                    biomeData = readLongArrayPayload(buf);
                                } else {
                                    skipPayload(buf, bt, 4);
                                }
                            }
                        } else {
                            skipPayload(buf, t, 3);
                        }
                    }
                    case "SkyLight" -> {
                        hasSkyLightTag = true;
                        if (t == Nbt.TAG_BYTE_ARRAY) {
                            byte[] arr = readByteArrayPayload(buf);
                            if (arr.length == 2048) {
                                skyLight = arr;
                                skyMode = checkUniformLight0Or15(arr);
                            } else {
                                skyMode = -1;
                            }
                        } else {
                            skyMode = -1;
                            skipPayload(buf, t, 3);
                        }
                    }
                    case "BlockLight" -> {
                        hasBlockLightTag = true;
                        if (t == Nbt.TAG_BYTE_ARRAY) {
                            byte[] arr = readByteArrayPayload(buf);
                            if (arr.length == 2048) {
                                blockLight = arr;
                                blkMode = checkUniformLight0Or15(arr);
                            } else {
                                blkMode = -1;
                            }
                        } else {
                            blkMode = -1;
                            skipPayload(buf, t, 3);
                        }
                    }
                    case "starlight.skylight_state" -> {
                        if (isNumericTag(t)) {
                            starlightSkyState = (int) readNumericTagAsLong(buf, t);
                        } else {
                            skipPayload(buf, t, 3);
                        }
                    }
                    case "starlight.blocklight_state" -> {
                        if (isNumericTag(t)) {
                            starlightBlkState = (int) readNumericTagAsLong(buf, t);
                        } else {
                            skipPayload(buf, t, 3);
                        }
                    }
                    default -> skipPayload(buf, t, 3);
                }
            }
            int secPayloadEnd = buf.position();
            if (!hasY) {
                continue;
            }
            int idx = sy - minSectionY;
            if (hasBlockStatesCompound && idx >= 0 && idx < sectionCount) {
                byte[] resolvedSky = skyLight != null
                        ? skyLight
                        : (!hasSkyLightTag && starlightSkyState == 1 ? LcaRegionWriter.ALL_ZERO_LIGHT_2048 : null);
                byte[] resolvedBlk = blockLight != null
                        ? blockLight
                        : (!hasBlockLightTag && starlightBlkState == 1 ? LcaRegionWriter.ALL_ZERO_LIGHT_2048 : null);
                out.sectionsByIdx[idx] = new StreamedSection(
                        blockPalette, blockData, biomePalette, biomeData, resolvedSky, resolvedBlk);
            } else {
                if ((starlightSkyState >= 0 && starlightSkyState != 1)
                        || (starlightBlkState >= 0 && starlightBlkState != 1)) {
                    skyMode = -1;
                    blkMode = -1;
                } else {
                    if (!hasSkyLightTag && starlightSkyState == 1) {
                        skyMode = 1;
                    }
                    if (!hasBlockLightTag && starlightBlkState == 1) {
                        blkMode = 1;
                    }
                }
                if (sy < minSectionY) {
                    if (out.lowBoundarySections == null) {
                        out.lowBoundarySections = new ArrayList<>(2);
                    }
                    out.lowBoundarySections.add(new BoundarySectionSlice(
                            secPayloadStart, secPayloadEnd, true, sy, hasBlockStatesCompound, skyMode, blkMode));
                } else {
                    if (out.highBoundarySections == null) {
                        out.highBoundarySections = new ArrayList<>(2);
                    }
                    out.highBoundarySections.add(new BoundarySectionSlice(
                            secPayloadStart, secPayloadEnd, true, sy, hasBlockStatesCompound, skyMode, blkMode));
                }
            }
        }
    }

    private static String[] parseBlockStatePaletteList(
            ByteBuffer buf, StringBuilder sb
    ) throws IOException {
        requireRemaining(buf, 5, "block_states.palette header");
        byte elemType = buf.get();
        int count = buf.getInt();
        if (count < 0 || count > Nbt.MAX_ARRAY_LENGTH) {
            throw new IOException("Malformed block_states.palette length: " + count);
        }
        if (count == 0) {
            return null;
        }
        requireRemaining(buf, count, "block_states.palette entries");
        String[] out = new String[count];
        if (elemType == Nbt.TAG_STRING) {
            for (int i = 0; i < count; i++) {
                out[i] = readModifiedUtf8(buf);
            }
            return out;
        }
        if (elemType != Nbt.TAG_COMPOUND) {
            for (int i = 0; i < count; i++) {
                skipPayload(buf, elemType, 5);
                out[i] = AIR_STATE;
            }
            return out;
        }

        String[] propKeys = new String[16];
        String[] propVals = new String[16];
        for (int i = 0; i < count; i++) {
            String name = null;
            String id = null;
            String emptyName = null;
            int propCount = 0;
            boolean parsedPrimaryProps = false;

            while (true) {
                requireRemaining(buf, 1, "palette entry tag");
                byte pt = buf.get();
                if (pt == Nbt.TAG_END) {
                    break;
                }
                String pk = readModifiedUtf8(buf);
                if ("Name".equals(pk) && pt == Nbt.TAG_STRING) {
                    name = readModifiedUtf8(buf);
                } else if ("id".equals(pk) && pt == Nbt.TAG_STRING) {
                    id = readModifiedUtf8(buf);
                } else if (pk.isEmpty() && pt == Nbt.TAG_STRING) {
                    emptyName = readModifiedUtf8(buf);
                } else if (("Properties".equals(pk) || "properties".equals(pk)) && pt == Nbt.TAG_COMPOUND) {
                    boolean takeProps = "Properties".equals(pk) || !parsedPrimaryProps;
                    if (takeProps) {
                        if ("Properties".equals(pk)) {
                            parsedPrimaryProps = true;
                        }
                        propCount = 0;
                        while (true) {
                            requireRemaining(buf, 1, "Properties child tag");
                            byte vt = buf.get();
                            if (vt == Nbt.TAG_END) {
                                break;
                            }
                            String propKey = readModifiedUtf8(buf);
                            String propVal = readScalarTagAsString(buf, vt, 6);
                            if (propCount == propKeys.length) {
                                propKeys = Arrays.copyOf(propKeys, propKeys.length << 1);
                                propVals = Arrays.copyOf(propVals, propVals.length << 1);
                            }
                            propKeys[propCount] = propKey;
                            propVals[propCount] = propVal;
                            propCount++;
                        }
                    } else {
                        skipPayload(buf, pt, 5);
                    }
                } else {
                    skipPayload(buf, pt, 5);
                }
            }

            String resolvedName = name != null ? name : (id != null ? id : (emptyName != null ? emptyName : AIR_STATE));
            if (propCount == 0) {
                out[i] = resolvedName;
            } else {
                sb.setLength(0);
                sb.append(resolvedName).append('[');
                for (int p = 0; p < propCount; p++) {
                    if (p > 0) {
                        sb.append(',');
                    }
                    sb.append(propKeys[p]).append('=').append(propVals[p]);
                }
                sb.append(']');
                out[i] = sb.toString();
            }
        }
        return out;
    }

    private static String[] parseBiomePaletteList(ByteBuffer buf) throws IOException {
        requireRemaining(buf, 5, "biomes.palette header");
        byte elemType = buf.get();
        int count = buf.getInt();
        if (count < 0 || count > Nbt.MAX_ARRAY_LENGTH) {
            throw new IOException("Malformed biomes.palette length: " + count);
        }
        if (count == 0) {
            return null;
        }
        requireRemaining(buf, count, "biomes.palette entries");
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            if (elemType == Nbt.TAG_STRING) {
                out[i] = readModifiedUtf8(buf);
            } else {
                skipPayload(buf, elemType, 5);
                out[i] = PLAINS_BIOME;
            }
        }
        return out;
    }

    private static void parseHeightmapsCompound(
            ByteBuffer buf, StreamedChunk out, int depth
    ) throws IOException {
        checkDepth(depth);
        if (out.heightmapKeys == null) {
            out.heightmapKeys = new ArrayList<>(6);
            out.heightmapValues = new ArrayList<>(6);
        }
        while (true) {
            requireRemaining(buf, 1, "Heightmaps tag");
            byte t = buf.get();
            if (t == Nbt.TAG_END) {
                break;
            }
            String k = readModifiedUtf8(buf);
            if (t == Nbt.TAG_LONG_ARRAY) {
                long[] arr = readLongArrayPayload(buf);
                int existing = out.heightmapKeys.indexOf(k);
                if (existing >= 0) {
                    out.heightmapValues.set(existing, arr);
                } else {
                    out.heightmapKeys.add(k);
                    out.heightmapValues.add(arr);
                }
            } else {
                skipPayload(buf, t, depth + 1);
            }
        }
    }

    private static int parsePossiblyEmptyList(ByteBuffer buf, byte childType, int emptyFlag) throws IOException {
        if (childType != Nbt.TAG_LIST) {
            skipPayload(buf, childType, 1);
            return 0;
        }
        requireRemaining(buf, 5, "TAG_List header");
        byte elemType = buf.get();
        int len = buf.getInt();
        if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
            throw new IOException("Malformed TAG_List length: " + len);
        }
        if (elemType == Nbt.TAG_END) {
            if (len > 0) {
                throw new IOException("TAG_List declared TAG_End with nonzero length " + len);
            }
            return emptyFlag;
        }
        if (len == 0) {
            return emptyFlag;
        }
        for (int i = 0; i < len; i++) {
            skipPayload(buf, elemType, 2);
        }
        return 0;
    }

    private static boolean scanIsEmptyVanillaStructures(ByteBuffer buf, int depth) throws IOException {
        checkDepth(depth);
        int childCount = 0;
        boolean emptyRefs = false;
        boolean emptyStarts = false;
        while (true) {
            requireRemaining(buf, 1, "structures child tag");
            byte t = buf.get();
            if (t == Nbt.TAG_END) {
                break;
            }
            String k = readModifiedUtf8(buf);
            childCount++;
            if (t == Nbt.TAG_COMPOUND && ("References".equals(k) || "Starts".equals(k))) {
                requireRemaining(buf, 1, "structures inner tag");
                byte first = buf.get();
                if (first == Nbt.TAG_END) {
                    if ("References".equals(k)) {
                        emptyRefs = true;
                    } else {
                        emptyStarts = true;
                    }
                } else {
                    // Non-empty compound: skip current child + rest of inner compound
                    readModifiedUtf8(buf);
                    skipPayload(buf, first, depth + 2);
                    skipRemainingCompound(buf, depth + 1);
                }
            } else {
                skipPayload(buf, t, depth + 1);
            }
        }
        return childCount == 2 && emptyRefs && emptyStarts;
    }

    private static int scanAllEmptySubLists(ByteBuffer buf, int depth) throws IOException {
        checkDepth(depth);
        requireRemaining(buf, 5, "PostProcessing TAG_List");
        byte elemType = buf.get();
        int len = buf.getInt();
        if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
            throw new IOException("Malformed PostProcessing length: " + len);
        }
        if (elemType == Nbt.TAG_END) {
            if (len > 0) {
                throw new IOException("PostProcessing declared TAG_End with nonzero length " + len);
            }
            return -1;
        }
        if (len == 0 || len > 127 || elemType != Nbt.TAG_LIST) {
            for (int i = 0; i < len; i++) {
                skipPayload(buf, elemType, depth + 1);
            }
            return -1;
        }
        boolean allEmpty = true;
        for (int i = 0; i < len; i++) {
            requireRemaining(buf, 5, "PostProcessing sub-list");
            byte subElem = buf.get();
            int subLen = buf.getInt();
            if (subLen < 0 || subLen > Nbt.MAX_ARRAY_LENGTH) {
                throw new IOException("Malformed PostProcessing sub-list length: " + subLen);
            }
            if (subElem == Nbt.TAG_END && subLen > 0) {
                throw new IOException("PostProcessing sub-list declared TAG_End with nonzero length " + subLen);
            }
            if (subLen > 0) {
                allEmpty = false;
                for (int j = 0; j < subLen; j++) {
                    skipPayload(buf, subElem, depth + 2);
                }
            }
        }
        return allEmpty ? len : -1;
    }

    private static void scanLevelCoords(ByteBuffer buf, StreamedChunk out, int depth) throws IOException {
        checkDepth(depth);
        while (true) {
            requireRemaining(buf, 1, "Level child tag");
            byte t = buf.get();
            if (t == Nbt.TAG_END) {
                break;
            }
            String k = readModifiedUtf8(buf);
            if ("xPos".equals(k) && isNumericTag(t)) {
                out.levelXPos = (int) readNumericTagAsLong(buf, t);
            } else if ("zPos".equals(k) && isNumericTag(t)) {
                out.levelZPos = (int) readNumericTagAsLong(buf, t);
            } else {
                skipPayload(buf, t, depth + 1);
            }
        }
    }

    private static int checkUniformLight0Or15(byte[] b2048) {
        if (b2048 == null) {
            return 0;
        }
        if (b2048.length != 2048) {
            return -1;
        }
        byte first = b2048[0];
        if (first != 0 && first != (byte) 0xFF) {
            return -1;
        }
        long expectedWord = first == 0 ? 0L : -1L;
        for (int off = 0; off < 2048; off += 8) {
            if ((long) LcaRegionReader.LONG_BE_VIEW.get(b2048, off) != expectedWord) {
                return -1;
            }
        }
        return first == 0 ? 1 : 2;
    }

    private static byte[] readByteArrayPayload(ByteBuffer buf) throws IOException {
        requireRemaining(buf, 4, "TAG_Byte_Array length");
        int len = buf.getInt();
        validateArrayLength(buf, len, 1, "TAG_Byte_Array");
        byte[] arr = new byte[len];
        buf.get(arr);
        return arr;
    }

    private static long[] readLongArrayPayload(ByteBuffer buf) throws IOException {
        requireRemaining(buf, 4, "TAG_Long_Array length");
        int len = buf.getInt();
        validateArrayLength(buf, len, 8, "TAG_Long_Array");
        long[] arr = new long[len];
        for (int i = 0; i < len; i++) {
            arr[i] = buf.getLong();
        }
        return arr;
    }

    private static boolean isNumericTag(byte type) {
        return type >= Nbt.TAG_BYTE && type <= Nbt.TAG_DOUBLE;
    }

    private static long readNumericTagAsLong(ByteBuffer buf, byte type) throws IOException {
        return switch (type) {
            case Nbt.TAG_BYTE -> {
                requireRemaining(buf, 1, "TAG_Byte");
                yield buf.get();
            }
            case Nbt.TAG_SHORT -> {
                requireRemaining(buf, 2, "TAG_Short");
                yield buf.getShort();
            }
            case Nbt.TAG_INT -> {
                requireRemaining(buf, 4, "TAG_Int");
                yield buf.getInt();
            }
            case Nbt.TAG_LONG -> {
                requireRemaining(buf, 8, "TAG_Long");
                yield buf.getLong();
            }
            case Nbt.TAG_FLOAT -> {
                requireRemaining(buf, 4, "TAG_Float");
                yield (long) buf.getFloat();
            }
            case Nbt.TAG_DOUBLE -> {
                requireRemaining(buf, 8, "TAG_Double");
                yield (long) buf.getDouble();
            }
            default -> throw new IOException("Not a numeric NBT tag: " + type);
        };
    }

    private static String readScalarTagAsString(ByteBuffer buf, byte type, int depth) throws IOException {
        return switch (type) {
            case Nbt.TAG_STRING -> readModifiedUtf8(buf);
            case Nbt.TAG_BYTE -> {
                requireRemaining(buf, 1, "TAG_Byte");
                yield Byte.toString(buf.get());
            }
            case Nbt.TAG_SHORT -> {
                requireRemaining(buf, 2, "TAG_Short");
                yield Short.toString(buf.getShort());
            }
            case Nbt.TAG_INT -> {
                requireRemaining(buf, 4, "TAG_Int");
                yield Integer.toString(buf.getInt());
            }
            case Nbt.TAG_LONG -> {
                requireRemaining(buf, 8, "TAG_Long");
                yield Long.toString(buf.getLong());
            }
            case Nbt.TAG_FLOAT -> {
                requireRemaining(buf, 4, "TAG_Float");
                yield Float.toString(buf.getFloat());
            }
            case Nbt.TAG_DOUBLE -> {
                requireRemaining(buf, 8, "TAG_Double");
                yield Double.toString(buf.getDouble());
            }
            default -> {
                skipPayload(buf, type, depth);
                yield "";
            }
        };
    }

    private static void skipPayload(ByteBuffer buf, byte type, int depth) throws IOException {
        checkDepth(depth);
        switch (type) {
            case Nbt.TAG_BYTE -> {
                requireRemaining(buf, 1, "TAG_Byte");
                buf.position(buf.position() + 1);
            }
            case Nbt.TAG_SHORT -> {
                requireRemaining(buf, 2, "TAG_Short");
                buf.position(buf.position() + 2);
            }
            case Nbt.TAG_INT, Nbt.TAG_FLOAT -> {
                requireRemaining(buf, 4, "TAG_Int/Float");
                buf.position(buf.position() + 4);
            }
            case Nbt.TAG_LONG, Nbt.TAG_DOUBLE -> {
                requireRemaining(buf, 8, "TAG_Long/Double");
                buf.position(buf.position() + 8);
            }
            case Nbt.TAG_BYTE_ARRAY -> {
                requireRemaining(buf, 4, "TAG_Byte_Array");
                int len = buf.getInt();
                validateArrayLength(buf, len, 1, "TAG_Byte_Array");
                buf.position(buf.position() + len);
            }
            case Nbt.TAG_STRING -> {
                requireRemaining(buf, 2, "TAG_String");
                int len = Short.toUnsignedInt(buf.getShort());
                requireRemaining(buf, len, "TAG_String bytes");
                buf.position(buf.position() + len);
            }
            case Nbt.TAG_LIST -> {
                requireRemaining(buf, 5, "TAG_List");
                byte elemType = buf.get();
                int len = buf.getInt();
                if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
                    throw new IOException("Malformed TAG_List length: " + len);
                }
                if (elemType == Nbt.TAG_END) {
                    if (len > 0) {
                        throw new IOException("TAG_List declared TAG_End with nonzero length " + len);
                    }
                    break;
                }
                int fixedElemSize = fixedScalarPayloadSize(elemType);
                if (fixedElemSize > 0) {
                    validateArrayLength(buf, len, fixedElemSize, "TAG_List");
                    buf.position(buf.position() + len * fixedElemSize);
                } else {
                    requireRemaining(buf, len, "TAG_List elements");
                    for (int i = 0; i < len; i++) {
                        skipPayload(buf, elemType, depth + 1);
                    }
                }
            }
            case Nbt.TAG_COMPOUND -> skipRemainingCompound(buf, depth);
            case Nbt.TAG_INT_ARRAY -> {
                requireRemaining(buf, 4, "TAG_Int_Array");
                int len = buf.getInt();
                validateArrayLength(buf, len, 4, "TAG_Int_Array");
                buf.position(buf.position() + len * 4);
            }
            case Nbt.TAG_LONG_ARRAY -> {
                requireRemaining(buf, 4, "TAG_Long_Array");
                int len = buf.getInt();
                validateArrayLength(buf, len, 8, "TAG_Long_Array");
                buf.position(buf.position() + len * 8);
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    private static void skipRemainingCompound(ByteBuffer buf, int depth) throws IOException {
        checkDepth(depth);
        while (true) {
            requireRemaining(buf, 1, "TAG_Compound child");
            byte childType = buf.get();
            if (childType == Nbt.TAG_END) {
                return;
            }
            requireRemaining(buf, 2, "TAG_Compound child name length");
            int nameLen = Short.toUnsignedInt(buf.getShort());
            requireRemaining(buf, nameLen, "TAG_Compound child name");
            buf.position(buf.position() + nameLen);
            skipPayload(buf, childType, depth + 1);
        }
    }

    private static int fixedScalarPayloadSize(byte type) {
        return switch (type) {
            case Nbt.TAG_BYTE -> 1;
            case Nbt.TAG_SHORT -> 2;
            case Nbt.TAG_INT, Nbt.TAG_FLOAT -> 4;
            case Nbt.TAG_LONG, Nbt.TAG_DOUBLE -> 8;
            default -> -1;
        };
    }

    private static String readModifiedUtf8(ByteBuffer buf) throws IOException {
        requireRemaining(buf, 2, "Modified-UTF8 length");
        int ulen = Short.toUnsignedInt(buf.getShort());
        if (ulen == 0) {
            return "";
        }
        requireRemaining(buf, ulen, "Modified-UTF8 payload");
        // Fast ASCII scan (99.9% of Minecraft block/biome/tag identifiers)
        boolean pureAscii = true;
        int basePos = buf.position();
        for (int i = 0; i < ulen; i++) {
            int b = buf.get(basePos + i) & 0xFF;
            if (b == 0 || b >= 0x80) {
                pureAscii = false;
                break;
            }
        }
        if (pureAscii) {
            byte[] ascii = new byte[ulen];
            buf.get(ascii);
            return new String(ascii, StandardCharsets.ISO_8859_1);
        }
        return decodeModifiedUtf8Slow(buf, ulen);
    }

    private static String decodeModifiedUtf8Slow(ByteBuffer buf, int ulen) throws IOException {
        byte[] bytearr = new byte[ulen];
        buf.get(bytearr);
        char[] chararr = new char[ulen];
        int count = 0;
        int chararrCount = 0;
        while (count < ulen) {
            int c = bytearr[count] & 0xFF;
            switch (c >> 4) {
                case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                    count++;
                    chararr[chararrCount++] = (char) c;
                }
                case 12, 13 -> {
                    count += 2;
                    if (count > ulen) {
                        throw new IOException("Malformed Modified-UTF8: partial 2-byte char");
                    }
                    int char2 = bytearr[count - 1];
                    if ((char2 & 0xC0) != 0x80) {
                        throw new IOException("Malformed Modified-UTF8 continuation byte");
                    }
                    chararr[chararrCount++] = (char) (((c & 0x1F) << 6) | (char2 & 0x3F));
                }
                case 14 -> {
                    count += 3;
                    if (count > ulen) {
                        throw new IOException("Malformed Modified-UTF8: partial 3-byte char");
                    }
                    int char2 = bytearr[count - 2];
                    int char3 = bytearr[count - 1];
                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80)) {
                        throw new IOException("Malformed Modified-UTF8 continuation bytes");
                    }
                    chararr[chararrCount++] = (char)
                            (((c & 0x0F) << 12) | ((char2 & 0x3F) << 6) | (char3 & 0x3F));
                }
                default -> throw new IOException("Malformed Modified-UTF8 lead byte: 0x" + Integer.toHexString(c));
            }
        }
        return new String(chararr, 0, chararrCount);
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > Nbt.MAX_DEPTH) {
            throw new IOException("NBT nesting depth exceeded limit (" + Nbt.MAX_DEPTH + ")");
        }
    }

    private static void validateArrayLength(
            ByteBuffer buf, int count, int elemBytes, String tagLabel
    ) throws IOException {
        if (count < 0 || count > Nbt.MAX_ARRAY_LENGTH) {
            throw new IOException("Malformed " + tagLabel + " length: " + count);
        }
        long byteCount = (long) count * elemBytes;
        if (byteCount > buf.remaining()) {
            throw new IOException(
                    "Truncated " + tagLabel + ": requires " + byteCount + " bytes, remaining=" + buf.remaining());
        }
    }

    private static void requireRemaining(ByteBuffer buf, int needed, String context) throws IOException {
        if (buf.remaining() < needed) {
            throw new IOException(
                    "Truncated binary NBT at " + context + " (needed " + needed + ", remaining " + buf.remaining() + ")");
        }
    }

    // ----------------------------------------------------------------------------------------- flyweight records

    static final class StreamedSection {
        final String[] blockPalette;
        final long[] blockData;
        final String[] biomePalette;
        final long[] biomeData;
        final byte[] skyLight;
        final byte[] blockLight;

        StreamedSection(
                String[] blockPalette,
                long[] blockData,
                String[] biomePalette,
                long[] biomeData,
                byte[] skyLight,
                byte[] blockLight
        ) {
            this.blockPalette = blockPalette;
            this.blockData = blockData;
            this.biomePalette = biomePalette;
            this.biomeData = biomeData;
            this.skyLight = skyLight;
            this.blockLight = blockLight;
        }
    }

    static final class BoundarySectionSlice {
        final int payloadStart;
        final int payloadEnd;
        final boolean hasY;
        final int sectionY;
        final boolean hasBlockStates;
        final int skyMode;
        final int blkMode;

        BoundarySectionSlice(
                int payloadStart,
                int payloadEnd,
                boolean hasY,
                int sectionY,
                boolean hasBlockStates,
                int skyMode,
                int blkMode
        ) {
            this.payloadStart = payloadStart;
            this.payloadEnd = payloadEnd;
            this.hasY = hasY;
            this.sectionY = sectionY;
            this.hasBlockStates = hasBlockStates;
            this.skyMode = skyMode;
            this.blkMode = blkMode;
        }
    }

    static final class StreamedChunk {
        final ByteBuffer sourceBuf;
        final StreamedSection[] sectionsByIdx;
        int chunkDataVersion;
        long lastUpdate;
        long inhabitedTime;
        int statusOrdinal = LcaRegionReader.encodeStatusOrdinal(null);
        boolean hasVoxelSections;

        Integer directXPos;
        Integer directZPos;
        int[] positionCoords;
        Integer levelXPos;
        Integer levelZPos;

        List<BoundarySectionSlice> lowBoundarySections;
        List<BoundarySectionSlice> highBoundarySections;
        List<String> heightmapKeys;
        List<long[]> heightmapValues;

        String[] rootTagKeys = new String[24];
        int[] rootTagStarts = new int[24];
        int[] rootTagEnds = new int[24];
        int[] rootTagCompactFlags = new int[24];
        int[] rootTagAuxInts = new int[24];
        int rootTagCount;

        StreamedChunk(ByteBuffer sourceBuf, int sectionCount, int defaultDataVersion) {
            this.sourceBuf = sourceBuf;
            this.sectionsByIdx = new StreamedSection[sectionCount];
            this.chunkDataVersion = defaultDataVersion;
        }

        void recordRootTag(String key, int start, int end, int compactFlag, int auxInt) {
            // Preserve LinkedHashMap overwrite-in-place semantics if a malformed stream repeats a root key
            for (int i = 0; i < rootTagCount; i++) {
                if (rootTagKeys[i].equals(key)) {
                    rootTagStarts[i] = start;
                    rootTagEnds[i] = end;
                    rootTagCompactFlags[i] = compactFlag;
                    rootTagAuxInts[i] = auxInt;
                    return;
                }
            }
            if (rootTagCount == rootTagKeys.length) {
                int nextCap = rootTagKeys.length << 1;
                rootTagKeys = Arrays.copyOf(rootTagKeys, nextCap);
                rootTagStarts = Arrays.copyOf(rootTagStarts, nextCap);
                rootTagEnds = Arrays.copyOf(rootTagEnds, nextCap);
                rootTagCompactFlags = Arrays.copyOf(rootTagCompactFlags, nextCap);
                rootTagAuxInts = Arrays.copyOf(rootTagAuxInts, nextCap);
            }
            rootTagKeys[rootTagCount] = key;
            rootTagStarts[rootTagCount] = start;
            rootTagEnds[rootTagCount] = end;
            rootTagCompactFlags[rootTagCount] = compactFlag;
            rootTagAuxInts[rootTagCount] = auxInt;
            rootTagCount++;
        }

        int resolveLocalX(int fallbackRegionX) {
            if (directXPos != null) {
                return directXPos & 31;
            }
            if (positionCoords != null && positionCoords.length >= 2) {
                return positionCoords[0] & 31;
            }
            if (levelXPos != null) {
                return levelXPos & 31;
            }
            return (fallbackRegionX << 5) & 31;
        }

        int resolveLocalZ(int fallbackRegionZ) {
            if (directZPos != null) {
                return directZPos & 31;
            }
            if (positionCoords != null && positionCoords.length >= 2) {
                return positionCoords[1] & 31;
            }
            if (levelZPos != null) {
                return levelZPos & 31;
            }
            return (fallbackRegionZ << 5) & 31;
        }
    }
}

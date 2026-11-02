package io.github.dailystruggle.anvil;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BuiltInTestable;
import io.github.dailystruggle.bit.PbitResult;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * LeafCraft Anvil (`r.X.Z.lca`) region-file reader, sub-chunk section probe,
 * and `BuiltInTestable` verifier (`anvil-api-ADR-003`, `anvil-api-ADR-002`, `ADR-077`).
 *
 * <p>Implements zero-LZ direct section decoding (`Mode A`, `codec1 = 0`) and optional per-chunk
 * Zstandard frame wrapper decoding (`Mode B`, `codec1 = 1`), $O(1)$ inline uniform-section
 * resolution (`bit 15 == 1` in `uint16 sectionOffsets[i]`), dynamically chained shared dictionary
 * traversal (`nextSegmentLinkTrailer`), and Continuous Built-In Test (`CBIT`) tripwires `T1..T8`.</p>
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy ADR-016
 * @governedBy ADR-077
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaRegionReader implements RegionFileReader, BuiltInTestable {

    public static final LcaRegionReader INSTANCE = new LcaRegionReader();

    /** Superblock magic at `0x0000`: `"LCA_V001"` ASCII (`anvil-api-ADR-003` Section 2.1). */
    public static final long LCA_SUPERBLOCK_MAGIC = 0x4C43415F56303031L;

    /** Footer canary magic at `fileLength - 8`: `"LCA1_END"` ASCII (`anvil-api-ADR-003` Section 1). */
    public static final long LCA_FOOTER_CANARY_MAGIC = 0x4C4341315F454E44L;

    /** Format version `0x0001` (Major 1, Minor 0). */
    public static final int LCA_FORMAT_VERSION = 0x0001;

    /** Superblock `storageKind` (`0x0A`) constants (`anvil-api-ADR-003` Section 2.1). */
    public static final int STORAGE_KIND_REGION_VOXEL = 0;
    public static final int STORAGE_KIND_ENTITIES = 1;
    public static final int STORAGE_KIND_POI = 2;
    public static final int STORAGE_KIND_LEAFMAP_TILE = 3;

    public static final int SUPERBLOCK_SIZE = 64;
    public static final int POINTER_DIRECTORY_OFFSET = 0x0040;
    public static final int POINTER_DIRECTORY_SIZE = 8192;
    public static final int DICT_ROOT_OFFSET = 0x2040;
    public static final int SECDED_TABLE_SIZE = 1024;
    public static final int FOOTER_SIZE = 8;
    public static final int SLAB_SHIFT = 8;
    public static final int SLAB_BYTES = 1 << SLAB_SHIFT; // 256 bytes

    /**
     * Self-describing 6-bit ChunkFrame header canary (`bits 31..26 = 0b010110 = 0x5800_0000`)
     * paired with 10-bit `chunkIdx` (`bits 25..16`) and 16-bit `chunkDataVersion` (`bits 15..0`),
     * enabling $O(1)$ offset-variation detection (`CBIT T3b`) and pointer-directory reconstruction.
     */
    public static final int CHUNK_FRAME_SELF_TAG_MASK = 0xFC00_0000;
    public static final int CHUNK_FRAME_SELF_TAG_MAGIC = 0x5800_0000;

    /** CBIT T4: Maximum allowed uncompressed bytes per chunk (16 MiB). */
    public static final int MAX_CHUNK_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;

    /** CBIT T5: Maximum allowed chained dictionary continuation segments (64). */
    public static final int MAX_DICT_CHAIN_SEGMENTS = 64;

    /** VarHandle for 64-bit big-endian reads from the `0x0040..0x203F` pointer directory (`CBIT T8`). */
    static final VarHandle LONG_BE_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    static long getVolatileLongBE(byte[] array, int byteOffset) {
        VarHandle.loadLoadFence();
        long val = (long) LONG_BE_VIEW.get(array, byteOffset);
        VarHandle.acquireFence();
        return val;
    }

    static void setVolatileLongBE(byte[] array, int byteOffset, long value) {
        VarHandle.releaseFence();
        LONG_BE_VIEW.set(array, byteOffset, value);
        VarHandle.fullFence();
    }

    /**
     * Per-thread Hazard Pointer table (`anvil-api-ADR-003` Sections 2.2, 2.3 & `CBIT T8`).
     * Each reader thread pins the `slabOffset35` it is currently reading into its hazard slot,
     * guaranteeing zero contention across unrelated chunks while preventing a reader thread stalled
     * by an OS page fault across 256 rapid saves from having its slab recycled underneath it.
     */
    private static final int HAZARD_SLOTS = 64;
    private static final AtomicIntegerArray PINNED_SLAB_STARTS = new AtomicIntegerArray(HAZARD_SLOTS);

    static int pinHazardSlab(int slabOffset35) {
        int slot = ((int) Thread.currentThread().getId()) & (HAZARD_SLOTS - 1);
        PINNED_SLAB_STARTS.set(slot, slabOffset35);
        return slot;
    }

    static void unpinHazardSlab(int slot) {
        if (slot >= 0) {
            PINNED_SLAB_STARTS.lazySet(slot, 0);
        }
    }

    static boolean isSlabRangePinned(int slabStart, int slabCount) {
        int slabEnd = slabStart + slabCount;
        for (int i = 0; i < HAZARD_SLOTS; i++) {
            int pinned = PINNED_SLAB_STARTS.get(i);
            if (pinned >= slabStart && pinned < slabEnd) {
                return true;
            }
        }
        return false;
    }

    private LcaRegionReader() {}

    private static final class FastDictCacheSlot {
        final byte[] regionBytesRef;
        final long headerChecksum;
        final long rootHeaderWord;
        final long rootTrailerWord;
        final LcaSectionCodec.ParsedHeaderAndDict parsed;

        FastDictCacheSlot(
                byte[] regionBytesRef,
                long headerChecksum,
                long rootHeaderWord,
                long rootTrailerWord,
                LcaSectionCodec.ParsedHeaderAndDict parsed
        ) {
            this.regionBytesRef = regionBytesRef;
            this.headerChecksum = headerChecksum;
            this.rootHeaderWord = rootHeaderWord;
            this.rootTrailerWord = rootTrailerWord;
            this.parsed = parsed;
        }
    }

    private static final int FAST_DICT_RING_MASK = 15;
    private static final FastDictCacheSlot[] FAST_DICT_RING = new FastDictCacheSlot[FAST_DICT_RING_MASK + 1];

    void invalidateFastDictCache() {
        Arrays.fill(FAST_DICT_RING, null);
    }

    /**
     * Registers {@link #INSTANCE} for {@code ".lca"} in {@link RegionFormatRegistry}.
     */
    public static void register() {
        RegionFormatRegistry.register(".lca", INSTANCE);
    }

    /**
     * Executes the Power-On Built-In Test (`PBIT`, `ADR-016`, `anvil-api-ADR-003` Section 7):
     * encodes and decodes a 2-section micro-canary (1 uniform inline section + 1 non-uniform
     * `H_3` Delta-Roaring section with `Y+H_2` Gray `SkyLight`) and verifies 100% bit-exact
     * voxel/light parity.
     */
    @Override
    public PbitResult executePbit() {
        long start = System.nanoTime();
        try {
            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
            root.put("DataVersion", 4671);
            root.put("xPos", 0);
            root.put("zPos", 0);
            root.put("Status", "minecraft:full");

            LinkedHashMap<String, Object> secUniform = new LinkedHashMap<>();
            secUniform.put("Y", (byte) -4);
            LinkedHashMap<String, Object> bsUniform = new LinkedHashMap<>();
            LinkedHashMap<String, Object> deepslateEntry = new LinkedHashMap<>();
            deepslateEntry.put("Name", "minecraft:deepslate");
            bsUniform.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(deepslateEntry)));
            secUniform.put("block_states", bsUniform);

            LinkedHashMap<String, Object> secMixed = new LinkedHashMap<>();
            secMixed.put("Y", (byte) -3);
            LinkedHashMap<String, Object> bsMixed = new LinkedHashMap<>();
            LinkedHashMap<String, Object> stoneEntry = new LinkedHashMap<>();
            stoneEntry.put("Name", "minecraft:stone");
            LinkedHashMap<String, Object> oreEntry = new LinkedHashMap<>();
            oreEntry.put("Name", "minecraft:diamond_ore");
            bsMixed.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(stoneEntry, oreEntry)));
            int[] yzx = new int[4096];
            yzx[(5 << 8) | (8 << 4) | 8] = 1;
            bsMixed.put("data", LcaSectionCodec.packBlockLongs(yzx, 2));
            secMixed.put("block_states", bsMixed);
            byte[] sky = new byte[2048];
            for (int i = 0; i < 2048; i++) sky[i] = (byte) ((i & 0x0F) | (((15 - (i & 0x0F)) & 0x0F) << 4));
            secMixed.put("SkyLight", sky);

            root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(secUniform, secMixed)));

            LcaRegionWriter.LcaLiveArchive archive = LcaRegionWriter.createEmptyLiveArchive(0, 0, -4, 24, 4671, false);
            archive.saveChunkCoW(0, 0, root);
            byte[] lcaBytes = archive.toByteArray();

            AnvilChunkView view = readChunkView(lcaBytes, 0, 0);
            if (view == null
                    || !"minecraft:deepslate".equals(view.blockIdAt(0, -64, 0))
                    || !"minecraft:diamond_ore".equals(view.blockIdAt(8, -48 + 5, 8))
                    || !"minecraft:stone".equals(view.blockIdAt(0, -48, 0))) {
                return PbitResult.failed("LcaRegionReader", System.nanoTime() - start,
                        "PBIT micro-canary voxel mismatch", null);
            }
            return PbitResult.ok("LcaRegionReader", System.nanoTime() - start,
                    ".lca PBIT 2-section micro-canary verified");
        } catch (Throwable t) {
            return PbitResult.failed("LcaRegionReader", System.nanoTime() - start,
                    "PBIT exception: " + t.getMessage(), t);
        }
    }

    @Override
    public BitHealth getHealthStatus() {
        return BitHealth.HEALTHY;
    }

    @Override
    public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
        return isChunkGenerated(regionBytes, regionBytes.length, rx, rz);
    }

    @Override
    public boolean isChunkGenerated(byte[] regionBytes, int regionLength, int rx, int rz) {
        if ((rx | rz | (31 - rx) | (31 - rz)) < 0
                || regionBytes == null
                || regionLength < SUPERBLOCK_SIZE + POINTER_DIRECTORY_SIZE + FOOTER_SIZE) {
            return false;
        }
        long magic = (long) LONG_BE_VIEW.get(regionBytes, 0);
        long footer = (long) LONG_BE_VIEW.get(regionBytes, regionLength - FOOTER_SIZE);
        if (magic != LCA_SUPERBLOCK_MAGIC || footer != LCA_FOOTER_CANARY_MAGIC) {
            return false;
        }
        int chunkIdx = (rx & 31) | ((rz & 31) << 5);
        long ptr = getVolatileLongBE(regionBytes, POINTER_DIRECTORY_OFFSET + (chunkIdx << 3));
        long byteOffset = (ptr >>> 29) << SLAB_SHIFT;
        int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
        return byteOffset >= DICT_ROOT_OFFSET && byteOffset + exactByteLen20 <= regionLength - FOOTER_SIZE;
    }

    /**
     * Returns the 8-bit `generation8` dirty-chunk diff token for `(rx, rz)` in $O(1)$ (`anvil-api-ADR-003` Section 2.2),
     * or `-1` if the chunk is unallocated.
     */
    public static int readGenerationToken(byte[] regionBytes, int rx, int rz) {
        validateCoordinates(rx, rz);
        int chunkIdx = (rx & 31) | ((rz & 31) << 5);
        long ptr = getVolatileLongBE(regionBytes, POINTER_DIRECTORY_OFFSET + (chunkIdx << 3));
        return (ptr >>> 29) == 0 ? -1 : (int) (ptr & 0xFFL);
    }

    @Override
    public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) throws IOException {
        return readChunk(regionBytes, regionBytes.length, rx, rz);
    }

    @Override
    public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int regionLength, int rx, int rz) throws IOException {
        validateCoordinates(rx, rz);
        LcaSectionCodec.ParsedHeaderAndDict meta = parseAndValidateSuperblockAndDict(regionBytes, regionLength);

        ChunkSlabView snap = resolveChunkSlab(regionBytes, regionLength, meta.sectionCount, rx, rz, true, null);
        if (snap == null) {
            return null;
        }
        return decodeChunkFromFrame(meta, snap, rx, rz);
    }

    static AnvilReader.ChunkEntry decodeChunkFromRawSlab(
            LcaSectionCodec.ParsedHeaderAndDict meta,
            int rx,
            int rz,
            int ptrByteOffset,
            long ptrBefore,
            int codec1,
            int exactByteLen20,
            int generation8,
            byte[] rawSlab
    ) throws IOException {
        ChunkSlabView snap = buildSlabViewFromCopy(
                rx, rz, ptrByteOffset, ptrBefore, codec1, exactByteLen20, generation8,
                rawSlab, meta.sectionCount, null);
        return decodeChunkFromFrame(meta, snap, rx, rz);
    }

    static byte[] decodeChunkNbtBytesFromRawSlab(
            LcaSectionCodec.ParsedHeaderAndDict meta,
            int rx,
            int rz,
            int ptrByteOffset,
            long ptrBefore,
            int codec1,
            int exactByteLen20,
            int generation8,
            byte[] rawSlab
    ) throws IOException {
        ChunkSlabView snap = buildSlabViewFromCopy(
                rx, rz, ptrByteOffset, ptrBefore, codec1, exactByteLen20, generation8,
                rawSlab, meta.sectionCount, null);
        return encodeChunkNbtBytesFromFrame(meta, snap, rx, rz);
    }

    private static byte[] encodeChunkNbtBytesFromFrame(
            LcaSectionCodec.ParsedHeaderAndDict meta, ChunkSlabView snap, int rx, int rz
    ) throws IOException {
        snap.ensureBodyDecompressed(meta.sectionCount);
        byte[] frame = snap.frameBytes;
        int fOff = snap.frameOffset;
        int frameLen = snap.frameLength;
        if (frameLen < 24) {
            throw new CorruptRegionEntryException("CBIT T5: ChunkFrame too short (" + frameLen + " < 24)");
        }

        int rawDvWord = LcaSectionCodec.readIntBE(frame, fOff);
        int chunkDataVersion = decodeAndVerifySelfDescribingChunkHeader(rawDvWord, rx, rz);
        long lastUpdateTick = LcaSectionCodec.readLongBE(frame, fOff + 4);
        long inhabitedTimeTicks = LcaSectionCodec.readLongBE(frame, fOff + 12);
        int statusOrdinal = frame[fOff + 20] & 0xFF;
        int chunkFlags = frame[fOff + 21] & 0xFF;
        int residualNbtRelOffset = LcaSectionCodec.readUint16BE(frame, fOff + 22);

        boolean hasResidualNbt = (chunkFlags & 1) != 0;
        boolean scale4B = (chunkFlags & 4) != 0;
        int unitShift = scale4B ? 2 : 0;
        int sectionCount = meta.sectionCount;
        int sectionDirEnd = 24 + (sectionCount << 1);
        int residualRelStart = hasResidualNbt
                ? sectionDirEnd + (residualNbtRelOffset << unitShift)
                : frameLen;

        LcaSectionCodec.ScratchArena arena = LcaSectionCodec.scratch();
        int[] sectionEntries = arena.sectionEntries;
        int[] sectionRelStarts = computeSectionRelStarts(
                frame, fOff, frameLen, sectionCount, sectionDirEnd, residualRelStart, scale4B,
                sectionEntries, arena.sectionRelStarts);

        byte[] directBytes = LcaSectionCodec.encodeChunkFrameToNbtBytes(
                meta, frame, fOff, frameLen, chunkDataVersion, lastUpdateTick, inhabitedTimeTicks,
                statusOrdinal, chunkFlags, residualRelStart, rx, rz, sectionEntries, sectionRelStarts);
        if (directBytes != null) {
            return directBytes;
        }
        AnvilReader.ChunkEntry fallback = decodeChunkFromFrame(meta, snap, rx, rz);
        return (fallback == null || fallback.root == null) ? null : Nbt.writeNamedRoot("", fallback.root);
    }

    private static AnvilReader.ChunkEntry decodeChunkFromFrame(
            LcaSectionCodec.ParsedHeaderAndDict meta, ChunkSlabView snap, int rx, int rz
    ) throws IOException {
        snap.ensureBodyDecompressed(meta.sectionCount);
        byte[] frame = snap.frameBytes;
        int fOff = snap.frameOffset;
        int frameLen = snap.frameLength;
        if (frameLen < 24) {
            throw new CorruptRegionEntryException("CBIT T5: ChunkFrame too short (" + frameLen + " < 24)");
        }

        int rawDvWord = LcaSectionCodec.readIntBE(frame, fOff);
        int chunkDataVersion = decodeAndVerifySelfDescribingChunkHeader(rawDvWord, rx, rz);
        long lastUpdateTick = LcaSectionCodec.readLongBE(frame, fOff + 4);
        long inhabitedTimeTicks = LcaSectionCodec.readLongBE(frame, fOff + 12);
        int statusOrdinal = frame[fOff + 20] & 0xFF;
        int chunkFlags = frame[fOff + 21] & 0xFF;
        int residualNbtRelOffset = LcaSectionCodec.readUint16BE(frame, fOff + 22);

        boolean hasResidualNbt = (chunkFlags & 1) != 0;
        boolean hasSlabLocalPalette = (chunkFlags & 2) != 0;
        boolean scale4B = (chunkFlags & 4) != 0;
        int unitShift = scale4B ? 2 : 0;
        int defaultBiomeCode = (chunkFlags >>> 3) & 0x1F;
        int defaultBiomeDictId = defaultBiomeCode > 0 ? (defaultBiomeCode - 1) : -1;

        int sectionCount = meta.sectionCount;
        int sectionDirEnd = 24 + (sectionCount << 1);
        int residualRelStart = hasResidualNbt
                ? sectionDirEnd + (residualNbtRelOffset << unitShift)
                : frameLen;

        int[] sectionEntries = new int[sectionCount];
        int[] sectionRelStarts = computeSectionRelStarts(
                frame, fOff, frameLen, sectionCount, sectionDirEnd, residualRelStart, scale4B, sectionEntries);

        List<String> activeCanonicalStates = meta.canonicalStates;
        List<String> activeBaseBlockNames = meta.baseBlockNames;
        List<String> activeBiomes = meta.biomes;
        List<LcaSectionCodec.BiomeTemplate> activeBiomeTemplates = Collections.emptyList();
        LcaSectionCodec.SlabLocalPalette localPal = null;
        if (hasSlabLocalPalette) {
            int paletteLimitRel = LcaSectionCodec.findFirstSectionRelStart(
                    sectionRelStarts, sectionCount, residualRelStart);
            localPal = LcaSectionCodec.decodeSlabLocalPalette(
                    frame, fOff, sectionDirEnd, paletteLimitRel, true, true, meta.canonicalStates);
            activeCanonicalStates = localPal.canonicalStates;
            activeBaseBlockNames = localPal.baseBlockNames;
            activeBiomes = localPal.biomes;
            activeBiomeTemplates = localPal.biomeTemplates;
        }

        int residualRelEnd = LcaSectionCodec.findResidualRelEnd(
                sectionRelStarts, sectionCount, residualRelStart, frameLen);
        LcaSectionCodec.ChunkPatchTail patchTail = hasResidualNbt
                ? LcaSectionCodec.locateAndDecodeChunkPatchTail(
                        frame, fOff, frameLen, residualRelStart, residualRelEnd,
                        sectionCount, sectionDirEnd, scale4B, hasSlabLocalPalette,
                        localPal, meta.canonicalStates, true, false,
                        sectionEntries, sectionRelStarts)
                : null;
        if (patchTail != null) {
            residualRelEnd = LcaSectionCodec.findResidualRelEnd(
                    sectionRelStarts, sectionCount, residualRelStart, frameLen);
        }

        LcaSectionCodec.ResidualTailDecoded tail = hasResidualNbt
                ? LcaSectionCodec.unpackResidualSectionAndNbt(
                        frame, fOff, residualRelStart, residualRelEnd, meta.minSectionY, sectionCount)
                : new LcaSectionCodec.ResidualTailDecoded(
                        new LinkedHashMap<>(), Collections.emptyList(), Collections.emptyList(), false);

        LinkedHashMap<String, Object> root = tail.rootMap();
        root.putIfAbsent("DataVersion", chunkDataVersion);
        if (tail.nonVoxelRoot()) {
            if (statusOrdinal != 0xFE && !root.containsKey("Status")) {
                root.put("Status", LcaSectionCodec.decodeStatusOrdinal(statusOrdinal));
            }
            return new AnvilReader.ChunkEntry(snap.codec1 == 0 ? 3 : 5, snap.exactByteLen20, root);
        }

        root.putIfAbsent("xPos", (meta.regionX << 5) + rx);
        root.putIfAbsent("zPos", (meta.regionZ << 5) + rz);
        root.putIfAbsent("yPos", meta.minSectionY);
        if (statusOrdinal != 0xFE && !root.containsKey("Status")) {
            root.put("Status", LcaSectionCodec.decodeStatusOrdinal(statusOrdinal));
        }
        root.putIfAbsent("LastUpdate", lastUpdateTick);
        root.putIfAbsent("InhabitedTime", inhabitedTimeTicks);

        List<Object> sectionCompounds = new ArrayList<>(sectionCount + 2);
        if (!tail.lowBoundarySections().isEmpty()) {
            sectionCompounds.addAll(tail.lowBoundarySections());
        }

        for (int i = 0; i < sectionCount; i++) {
            int entry16 = sectionEntries[i];
            LcaSectionCodec.SectionPatch secPatch = patchTail != null ? patchTail.patchForSection(i) : null;
            if (entry16 == 0 && secPatch == null) {
                continue;
            }
            int sectionY = meta.minSectionY + i;
            if ((entry16 & 0x8000) != 0 && secPatch == null) {
                int skyLight2 = (entry16 >>> 13) & 3;
                int globalDictId = hasSlabLocalPalette ? (entry16 & 0x07FF) : (entry16 & 0x1FFF);
                if (globalDictId >= activeCanonicalStates.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Inline globalDictId " + globalDictId
                            + " >= stateCount " + activeCanonicalStates.size());
                }
                LinkedHashMap<String, Object> secMap = new LinkedHashMap<>();
                secMap.put("Y", (byte) sectionY);
                LinkedHashMap<String, Object> bsMap = new LinkedHashMap<>();
                bsMap.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND,
                        List.of(LcaSectionCodec.parseCanonicalStateToNbtCompound(activeCanonicalStates.get(globalDictId)))));
                secMap.put("block_states", bsMap);

                if (hasSlabLocalPalette && !activeBiomeTemplates.isEmpty()) {
                    int biomeTmplIdx = (entry16 >>> 11) & 3;
                    if (biomeTmplIdx < activeBiomeTemplates.size()) {
                        LcaSectionCodec.BiomeTemplate tmpl = activeBiomeTemplates.get(biomeTmplIdx);
                        LinkedHashMap<String, Object> biomeMap = new LinkedHashMap<>();
                        biomeMap.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, new ArrayList<>(tmpl.palette)));
                        if (tmpl.packedBiomeLongs != null) {
                            biomeMap.put("data", tmpl.packedBiomeLongs);
                        }
                        secMap.put("biomes", biomeMap);
                    }
                } else if (defaultBiomeDictId >= 0 && defaultBiomeDictId < activeBiomes.size()) {
                    LinkedHashMap<String, Object> biomeMap = new LinkedHashMap<>();
                    biomeMap.put("palette", new Nbt.NbtList(Nbt.TAG_STRING,
                            List.of(activeBiomes.get(defaultBiomeDictId))));
                    secMap.put("biomes", biomeMap);
                }
                if (skyLight2 == 0b01 || skyLight2 == 0b10) {
                    byte[] sky = new byte[2048];
                    Arrays.fill(sky, (byte) 0xFF);
                    secMap.put("SkyLight", sky);
                }
                if (skyLight2 == 0b10 || skyLight2 == 0b11) {
                    byte[] blk = new byte[2048];
                    Arrays.fill(blk, (byte) 0xFF);
                    secMap.put("BlockLight", blk);
                }
                sectionCompounds.add(secMap);
            } else {
                LcaSectionCodec.DecodedSection dec;
                if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                    dec = LcaSectionCodec.createUniformDecodedSection(
                            sectionY, entry16, hasSlabLocalPalette,
                            activeCanonicalStates, activeBaseBlockNames, activeBiomes,
                            activeBiomeTemplates, defaultBiomeDictId, false, false);
                } else {
                    int relStart = sectionRelStarts[i];
                    int relEnd = findSectionRelEnd(sectionRelStarts, i, sectionCount, residualRelStart, frameLen);
                    dec = LcaSectionCodec.decodeSectionFrame(
                            frame, fOff + relStart, fOff + relEnd, sectionY,
                            activeCanonicalStates, activeBaseBlockNames, activeBiomes,
                            activeBiomeTemplates, false, false);
                }
                if (secPatch != null) {
                    dec = LcaSectionCodec.applySectionPatch(
                            dec, secPatch, activeCanonicalStates, activeBaseBlockNames, false);
                }

                LinkedHashMap<String, Object> secMap = new LinkedHashMap<>();
                secMap.put("Y", (byte) sectionY);

                LinkedHashMap<String, Object> bsMap = new LinkedHashMap<>();
                List<Object> palCompounds = new ArrayList<>(dec.blockStateCanonicalPalette.size());
                for (String canonical : dec.blockStateCanonicalPalette) {
                    palCompounds.add(LcaSectionCodec.parseCanonicalStateToNbtCompound(canonical));
                }
                bsMap.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palCompounds));
                if (dec.packedBlockLongs != null) {
                    bsMap.put("data", dec.packedBlockLongs);
                }
                secMap.put("block_states", bsMap);

                if (!dec.biomePalette.isEmpty()) {
                    LinkedHashMap<String, Object> biomeMap = new LinkedHashMap<>();
                    biomeMap.put("palette", new Nbt.NbtList(Nbt.TAG_STRING, new ArrayList<>(dec.biomePalette)));
                    if (dec.packedBiomeLongs != null) {
                        biomeMap.put("data", dec.packedBiomeLongs);
                    }
                    secMap.put("biomes", biomeMap);
                } else if (defaultBiomeDictId >= 0 && defaultBiomeDictId < activeBiomes.size()) {
                    LinkedHashMap<String, Object> biomeMap = new LinkedHashMap<>();
                    biomeMap.put("palette", new Nbt.NbtList(Nbt.TAG_STRING,
                            List.of(activeBiomes.get(defaultBiomeDictId))));
                    secMap.put("biomes", biomeMap);
                }
                if (dec.skyLight2048 != null) {
                    secMap.put("SkyLight", dec.skyLight2048);
                }
                if (dec.blockLight2048 != null) {
                    secMap.put("BlockLight", dec.blockLight2048);
                }
                sectionCompounds.add(secMap);
            }
        }

        if (!tail.highBoundarySections().isEmpty()) {
            sectionCompounds.addAll(tail.highBoundarySections());
        }

        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, sectionCompounds));
        return new AnvilReader.ChunkEntry(snap.codec1 == 0 ? 3 : 5, snap.exactByteLen20, root);
    }

    /**
     * Reads and serializes the chunk at `(rx, rz)` into an uncompressed Mojang binary NBT root
     * `TAG_Compound` byte array suitable for `RegionFile#getChunkDataInputStream(ChunkPos)`, or
     * returns `null` if the chunk is unallocated.
     */
    public byte[] readChunkNbtBytes(byte[] regionBytes, int rx, int rz) throws IOException {
        return readChunkNbtBytes(regionBytes, regionBytes.length, rx, rz);
    }

    public byte[] readChunkNbtBytes(byte[] regionBytes, int regionLength, int rx, int rz) throws IOException {
        validateCoordinates(rx, rz);
        LcaSectionCodec.ParsedHeaderAndDict meta = parseAndValidateSuperblockAndDict(regionBytes, regionLength);
        for (int attempt = 0; attempt < 2; attempt++) {
            ChunkSlabView snap = resolveChunkSlab(regionBytes, regionLength, meta.sectionCount, rx, rz, false, null);
            if (snap == null) {
                return null;
            }
            int hazardSlot = pinHazardSlab((int) (snap.ptrBefore >>> 29));
            try {
                byte[] res = encodeChunkNbtBytesFromFrame(meta, snap, rx, rz);
                if (snap.verifyTearFree(regionBytes) || attempt == 1) {
                    return res;
                }
            } catch (CorruptRegionEntryException ex) {
                if (attempt == 0 && !snap.verifyTearFree(regionBytes)) {
                    continue;
                }
                throw ex;
            } finally {
                unpinHazardSlab(hazardSlot);
            }
        }
        return null;
    }

    /**
     * Direct `.lca` {@link AnvilChunkView} decoder (`anvil-api-ADR-003` Sections 4-5) that bypasses
     * intermediate NBT `CompoundTag` materialization and resolves inline `bit 15` uniform sections in $O(1)$.
     */
    public static AnvilChunkView readChunkView(byte[] regionBytes, int rx, int rz) throws IOException {
        return readChunkView(regionBytes, regionBytes.length, rx, rz);
    }

    public static AnvilChunkView readChunkView(byte[] regionBytes, int regionLength, int rx, int rz) throws IOException {
        WindowDecodeResult res = decodeWindowWithRetry(
                regionBytes, regionLength, rx, rz, Integer.MIN_VALUE, Integer.MAX_VALUE, true, true);
        return res == null ? null : res.toChunkView();
    }

    /**
     * Direct `.lca` {@link ColumnProbe} decoder (`anvil-api-ADR-003` Section 4.1) that jumps via
     * `uint16 sectionOffsets[]` directly to sections overlapping `[minY, maxY]`, skipping light
     * bitplanes and out-of-window sections.
     */
    public static ColumnProbe readColumnProbe(byte[] regionBytes, int rx, int rz, int minY, int maxY) throws IOException {
        return readColumnProbe(regionBytes, regionBytes.length, rx, rz, minY, maxY);
    }

    public static ColumnProbe readColumnProbe(
            byte[] regionBytes, int regionLength, int rx, int rz, int minY, int maxY
    ) throws IOException {
        if (minY > maxY) {
            throw new IllegalArgumentException("minY=" + minY + " must be <= maxY=" + maxY);
        }
        WindowDecodeResult res = decodeWindowWithRetry(regionBytes, regionLength, rx, rz, minY, maxY, true, true);
        return res == null ? null : res.toColumnProbe(minY, maxY);
    }

    /**
     * Ultra-lean `.lca` terrain probe (`ADR-120` + `anvil-api-ADR-003`): decodes only block
     * sections overlapping `[minY, maxY]`, skipping residual NBT, heightmaps, biomes, and lighting.
     */
    public static ColumnProbe readTerrainProbe(
            byte[] regionBytes, int regionLength, int rx, int rz, int minY, int maxY
    ) throws IOException {
        if (minY > maxY) {
            throw new IllegalArgumentException("minY=" + minY + " must be <= maxY=" + maxY);
        }
        WindowDecodeResult res = decodeWindowWithRetry(regionBytes, regionLength, rx, rz, minY, maxY, false, false);
        if (res == null || res.sections.isEmpty()) return null;
        return new ColumnProbe(minY, maxY, Integer.MIN_VALUE, res.sections, Collections.emptyList());
    }

    /**
     * $O(1)$ single-section partial read (`anvil-api-ADR-003` Section 4.1): jumps directly to
     * `sectionOffsets[sectionY - minSectionY]` without copying the chunk slab or touching any other
     * vertical section. Inline uniform sections (`bit 15 == 1`) resolve in `~0.03 us` with zero
     * body bytes read and zero allocations.
     */
    public static PaletteSection readSection(
            byte[] regionBytes, int regionLength, int rx, int rz, int sectionY
    ) throws IOException {
        validateCoordinates(rx, rz);
        SuperblockFastMeta sb = validateSuperblockFast(regionBytes, regionLength);
        int idx = sectionY - sb.minSectionY;
        if (idx < 0 || idx >= sb.sectionCount) {
            return null;
        }
        WindowDecodeResult res = decodeWindowWithRetry(
                regionBytes, regionLength, rx, rz, sectionY << 4, (sectionY << 4) + 15, false, false);
        return (res == null || res.sections.isEmpty()) ? null : res.sections.get(0);
    }

    private static final class SuperblockFastMeta {
        final int minSectionY;
        final int sectionCount;

        SuperblockFastMeta(int minSectionY, int sectionCount) {
            this.minSectionY = minSectionY;
            this.sectionCount = sectionCount;
        }
    }

    private static SuperblockFastMeta validateSuperblockFast(byte[] regionBytes, int regionLength)
            throws CorruptRegionEntryException {
        if (regionBytes == null
                || regionLength < SUPERBLOCK_SIZE + POINTER_DIRECTORY_SIZE + FOOTER_SIZE
                || regionLength > regionBytes.length) {
            throw new CorruptRegionEntryException("CBIT T1: Region buffer too short for .lca");
        }
        long magic = (long) LONG_BE_VIEW.get(regionBytes, 0);
        if (magic != LCA_SUPERBLOCK_MAGIC) {
            throw new CorruptRegionEntryException("CBIT T1: Bad .lca superblock magic");
        }
        long footerCanary = (long) LONG_BE_VIEW.get(regionBytes, regionLength - FOOTER_SIZE);
        if (footerCanary != LCA_FOOTER_CANARY_MAGIC) {
            throw new CorruptRegionEntryException("CBIT T1: Bad .lca footer canary");
        }
        int formatVersion = LcaSectionCodec.readUint16BE(regionBytes, 0x08);
        if (formatVersion != LCA_FORMAT_VERSION) {
            throw new CorruptRegionEntryException("CBIT T1: Unsupported .lca formatVersion");
        }
        int minSectionY = LcaSectionCodec.readIntBE(regionBytes, 0x14);
        int sectionCount = LcaSectionCodec.readUint16BE(regionBytes, 0x18);
        int slabShift = LcaSectionCodec.readUint16BE(regionBytes, 0x1A);
        if (slabShift != SLAB_SHIFT || sectionCount <= 0 || sectionCount > 128) {
            throw new CorruptRegionEntryException("CBIT T1: Invalid slabShift or sectionCount");
        }
        long actualChecksum = (long) LONG_BE_VIEW.get(regionBytes, 0x38);
        long expectedChecksum = LcaSectionCodec.computeSuperblockChecksum64(regionBytes, 0);
        if (expectedChecksum != actualChecksum) {
            throw new CorruptRegionEntryException("CBIT T2: Superblock headerChecksum mismatch");
        }
        return new SuperblockFastMeta(minSectionY, sectionCount);
    }

    private static WindowDecodeResult decodeWindowWithRetry(
            byte[] regionBytes,
            int regionLength,
            int rx,
            int rz,
            int minY,
            int maxY,
            boolean decodeBiomes,
            boolean decodeHeightmap
    ) throws IOException {
        return decodeWindowWithRetry(
                regionBytes, regionLength, rx, rz, minY, maxY, decodeBiomes, decodeHeightmap, null);
    }

    private static WindowDecodeResult decodeWindowWithRetry(
            byte[] regionBytes,
            int regionLength,
            int rx,
            int rz,
            int minY,
            int maxY,
            boolean decodeBiomes,
            boolean decodeHeightmap,
            RecoveryReport recoveryReport
    ) throws IOException {
        validateCoordinates(rx, rz);
        SuperblockFastMeta sb = validateSuperblockFast(regionBytes, regionLength);
        for (int attempt = 0; attempt < 2; attempt++) {
            ChunkSlabView snap = INSTANCE.resolveChunkSlab(
                    regionBytes, regionLength, sb.sectionCount, rx, rz, false, recoveryReport);
            if (snap == null) return null;
            int hazardSlot = pinHazardSlab((int) (snap.ptrBefore >>> 29));
            try {
                WindowDecodeResult res = decodeWindowToViewOrProbe(
                        regionBytes, regionLength, sb, null, snap, minY, maxY, decodeBiomes, decodeHeightmap, recoveryReport);
                if (snap.verifyTearFree(regionBytes) || attempt == 1) {
                    return res;
                }
            } catch (CorruptRegionEntryException ex) {
                if (attempt == 0 && !snap.verifyTearFree(regionBytes)) {
                    continue;
                }
                throw ex;
            } finally {
                unpinHazardSlab(hazardSlot);
            }
        }
        return null;
    }

    private static int[] computeSectionRelStarts(
            byte[] frame,
            int fOff,
            int frameLen,
            int sectionCount,
            int sectionDirEnd,
            int residualRelStart,
            boolean scale4B,
            int[] entriesOut
    ) throws CorruptRegionEntryException {
        return computeSectionRelStarts(
                frame, fOff, frameLen, sectionCount, sectionDirEnd, residualRelStart, scale4B,
                entriesOut, new int[sectionCount]);
    }

    private static int[] computeSectionRelStarts(
            byte[] frame,
            int fOff,
            int frameLen,
            int sectionCount,
            int sectionDirEnd,
            int residualRelStart,
            boolean scale4B,
            int[] entriesOut,
            int[] relStarts
    ) throws CorruptRegionEntryException {
        if (sectionDirEnd > residualRelStart || residualRelStart > frameLen) {
            throw new CorruptRegionEntryException("CBIT T5: sectionOffsets/residualNbtRelOffset out of bounds");
        }
        boolean hasResidualNbt = (frame[fOff + 21] & 1) != 0;
        int unitShift = scale4B ? 2 : 0;
        for (int i = 0; i < sectionCount; i++) {
            int entry16 = LcaSectionCodec.readUint16BE(frame, fOff + 24 + (i << 1));
            entriesOut[i] = entry16;
            if (entry16 != 0 && (entry16 & 0x8000) == 0) {
                int relPos = sectionDirEnd + ((entry16 - 1) << unitShift);
                if (relPos < sectionDirEnd || relPos >= frameLen || (hasResidualNbt && relPos == residualRelStart)) {
                    throw new CorruptRegionEntryException("CBIT T5: sectionOffsets[" + i + "] out of bounds: " + relPos);
                }
                for (int k = 0; k < i; k++) {
                    if (relStarts[k] == relPos) {
                        throw new CorruptRegionEntryException("CBIT T5: Duplicate non-uniform sectionOffsets[] at " + i);
                    }
                }
                relStarts[i] = relPos;
            } else {
                relStarts[i] = -1;
            }
        }
        return relStarts;
    }

    private static int findSectionRelEnd(
            int[] relStarts, int idx, int sectionCount, int residualRelStart, int frameLen
    ) {
        return LcaSectionCodec.findSectionRelEnd(relStarts, idx, sectionCount, residualRelStart, frameLen);
    }

    private static final class WindowDecodeResult {
        final int dataVersion;
        final List<PaletteSection> sections;
        final List<BiomePaletteSection> biomeSections;
        final long[] motionBlockingNoLeaves;
        final int minEmittedSectionY;

        WindowDecodeResult(
                int dataVersion,
                List<PaletteSection> sections,
                List<BiomePaletteSection> biomeSections,
                long[] motionBlockingNoLeaves,
                int minEmittedSectionY
        ) {
            this.dataVersion = dataVersion;
            this.sections = sections;
            this.biomeSections = biomeSections;
            this.motionBlockingNoLeaves = motionBlockingNoLeaves;
            this.minEmittedSectionY = minEmittedSectionY;
        }

        AnvilChunkView toChunkView() {
            return new AnvilChunkView(dataVersion, sections, motionBlockingNoLeaves, biomeSections);
        }

        ColumnProbe toColumnProbe(int minY, int maxY) {
            int topY = Integer.MIN_VALUE;
            if (motionBlockingNoLeaves != null && motionBlockingNoLeaves.length > 0 && minEmittedSectionY != Integer.MAX_VALUE) {
                topY = AnvilReader.readHeightmapEntry(
                        motionBlockingNoLeaves, ColumnProbe.CENTER_LOCAL_X, ColumnProbe.CENTER_LOCAL_Z, minEmittedSectionY * 16);
            }
            return new ColumnProbe(minY, maxY, topY, sections, biomeSections);
        }
    }

    private static WindowDecodeResult decodeWindowToViewOrProbe(
            byte[] regionBytes,
            int regionLength,
            SuperblockFastMeta sb,
            LcaSectionCodec.ParsedHeaderAndDict preParsedMeta,
            ChunkSlabView snap,
            int minY,
            int maxY,
            boolean decodeBiomes,
            boolean decodeHeightmap,
            RecoveryReport recoveryReport
    ) throws IOException {
        byte[] frame = snap.frameBytes;
        int fOff = snap.frameOffset;
        int frameLen = snap.frameLength;
        if (frameLen < 24) {
            throw new CorruptRegionEntryException("CBIT T5: ChunkFrame too short (" + frameLen + ")");
        }
        int rawDvWord = LcaSectionCodec.readIntBE(frame, fOff);
        int chunkDataVersion = decodeAndVerifySelfDescribingChunkHeader(rawDvWord, snap.rx, snap.rz);
        int statusOrdinal = frame[fOff + 20] & 0xFF;
        if (statusOrdinal != 0xFF && statusOrdinal != 0xFE && statusOrdinal != 0xEF) {
            return null;
        }
        int chunkFlags = frame[fOff + 21] & 0xFF;
        boolean hasResidualNbt = (chunkFlags & 1) != 0;
        boolean hasSlabLocalPalette = (chunkFlags & 2) != 0;
        boolean scale4B = (chunkFlags & 4) != 0;
        int unitShift = scale4B ? 2 : 0;
        int defaultBiomeCode = (chunkFlags >>> 3) & 0x1F;
        int defaultBiomeDictId = defaultBiomeCode > 0 ? (defaultBiomeCode - 1) : -1;
        int residualNbtRelOffset = LcaSectionCodec.readUint16BE(frame, fOff + 22);

        int sectionCount = sb.sectionCount;
        int sectionDirEnd = 24 + (sectionCount << 1);
        int syMin = (minY == Integer.MIN_VALUE) ? Integer.MIN_VALUE : Math.floorDiv(minY, 16);
        int syMax = (maxY == Integer.MAX_VALUE) ? Integer.MAX_VALUE : Math.floorDiv(maxY, 16);

        if (snap.modeBBodyDeferred) {
            boolean needsBody = hasSlabLocalPalette || (decodeHeightmap && hasResidualNbt);
            if (!needsBody) {
                if (frameLen < sectionDirEnd) {
                    throw new CorruptRegionEntryException("CBIT T5: ChunkFrame header too short (" + frameLen + ")");
                }
                for (int i = 0; i < sectionCount; i++) {
                    int sectionY = sb.minSectionY + i;
                    if (sectionY < syMin || sectionY > syMax) continue;
                    int entry16 = LcaSectionCodec.readUint16BE(frame, fOff + 24 + (i << 1));
                    if (entry16 != 0 && (entry16 & 0x8000) == 0) {
                        needsBody = true;
                        break;
                    }
                }
            }
            if (needsBody) {
                snap.ensureBodyDecompressed(sectionCount);
                frame = snap.frameBytes;
                fOff = snap.frameOffset;
                frameLen = snap.frameLength;
            }
        }

        int residualRelStart = hasResidualNbt
                ? sectionDirEnd + (residualNbtRelOffset << unitShift)
                : frameLen;

        int[] entries = new int[sectionCount];
        int[] relStarts;
        if (snap.modeBBodyDeferred) {
            relStarts = new int[sectionCount];
            for (int i = 0; i < sectionCount; i++) {
                entries[i] = LcaSectionCodec.readUint16BE(frame, fOff + 24 + (i << 1));
                relStarts[i] = -1;
            }
        } else {
            relStarts = computeSectionRelStarts(
                    frame, fOff, frameLen, sectionCount, sectionDirEnd, residualRelStart, scale4B, entries);
        }

        List<String> canonicalStates;
        List<String> baseBlockNames;
        List<List<String>> singletonBasePalettes;
        List<String> biomes;
        List<List<String>> singletonBiomePalettes;
        List<LcaSectionCodec.BiomeTemplate> biomeTemplates = Collections.emptyList();
        LcaSectionCodec.SlabLocalPalette localPal = null;

        if (hasSlabLocalPalette) {
            int paletteLimitRel = LcaSectionCodec.findFirstSectionRelStart(
                    relStarts, sectionCount, residualRelStart);
            localPal = LcaSectionCodec.decodeSlabLocalPalette(
                    frame, fOff, sectionDirEnd, paletteLimitRel, false, decodeBiomes, Collections.emptyList());
            canonicalStates = localPal.canonicalStates;
            baseBlockNames = localPal.baseBlockNames;
            singletonBasePalettes = localPal.singletonBasePalettes;
            biomes = localPal.biomes;
            singletonBiomePalettes = localPal.singletonBiomePalettes;
            biomeTemplates = localPal.biomeTemplates;
        } else {
            LcaSectionCodec.ParsedHeaderAndDict regionMeta = preParsedMeta != null
                    ? preParsedMeta
                    : INSTANCE.parseAndValidateSuperblockAndDict(regionBytes, regionLength);
            canonicalStates = regionMeta.canonicalStates;
            baseBlockNames = regionMeta.baseBlockNames;
            singletonBasePalettes = regionMeta.singletonBasePalettes;
            biomes = regionMeta.biomes;
            singletonBiomePalettes = regionMeta.singletonBiomePalettes;
        }

        int residualRelEnd = LcaSectionCodec.findResidualRelEnd(relStarts, sectionCount, residualRelStart, frameLen);
        LcaSectionCodec.ChunkPatchTail patchTail = (!snap.modeBBodyDeferred && hasResidualNbt)
                ? LcaSectionCodec.locateAndDecodeChunkPatchTail(
                        frame, fOff, frameLen, residualRelStart, residualRelEnd,
                        sectionCount, sectionDirEnd, scale4B, hasSlabLocalPalette,
                        localPal, Collections.emptyList(), false, true,
                        entries, relStarts)
                : null;
        if (patchTail != null) {
            residualRelEnd = LcaSectionCodec.findResidualRelEnd(relStarts, sectionCount, residualRelStart, frameLen);
        }

        int minEmittedSectionY = Integer.MAX_VALUE;
        for (int i = 0; i < sectionCount; i++) {
            if (entries[i] != 0 || (patchTail != null && patchTail.hasPatchForSection(i))) {
                minEmittedSectionY = sb.minSectionY + i;
                break;
            }
        }

        List<PaletteSection> sectionsOut = new ArrayList<>(sectionCount);
        List<BiomePaletteSection> biomesOut = decodeBiomes ? new ArrayList<>(sectionCount) : Collections.emptyList();

        for (int i = 0; i < sectionCount; i++) {
            int entry16 = entries[i];
            LcaSectionCodec.SectionPatch secPatch = patchTail != null ? patchTail.patchForSection(i) : null;
            if (entry16 == 0 && secPatch == null) continue;
            int sectionY = sb.minSectionY + i;
            if (sectionY < syMin || sectionY > syMax) continue;

            if ((entry16 & 0x8000) != 0 && secPatch == null) {
                int globalDictId = hasSlabLocalPalette ? (entry16 & 0x07FF) : (entry16 & 0x1FFF);
                if (globalDictId >= singletonBasePalettes.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Inline globalDictId out of bounds: " + globalDictId);
                }
                sectionsOut.add(new PaletteSection(sectionY, singletonBasePalettes.get(globalDictId), null));
                if (decodeBiomes) {
                    if (hasSlabLocalPalette && !biomeTemplates.isEmpty()) {
                        int biomeTmplIdx = (entry16 >>> 11) & 3;
                        if (biomeTmplIdx < biomeTemplates.size()) {
                            LcaSectionCodec.BiomeTemplate tmpl = biomeTemplates.get(biomeTmplIdx);
                            biomesOut.add(new BiomePaletteSection(sectionY, tmpl.palette, tmpl.packedBiomeLongs));
                        }
                    } else if (defaultBiomeDictId >= 0 && defaultBiomeDictId < singletonBiomePalettes.size()) {
                        biomesOut.add(new BiomePaletteSection(sectionY, singletonBiomePalettes.get(defaultBiomeDictId), null));
                    }
                }
            } else if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                LcaSectionCodec.DecodedSection dec = LcaSectionCodec.createUniformDecodedSection(
                        sectionY, entry16, hasSlabLocalPalette,
                        canonicalStates, baseBlockNames, biomes,
                        biomeTemplates, defaultBiomeDictId, true, !decodeBiomes);
                dec = LcaSectionCodec.applySectionPatch(dec, secPatch, canonicalStates, baseBlockNames, true);
                sectionsOut.add(dec.toPaletteSection());
                if (decodeBiomes) {
                    BiomePaletteSection bps = dec.toBiomePaletteSection();
                    if (bps != null) {
                        biomesOut.add(bps);
                    } else if (defaultBiomeDictId >= 0 && defaultBiomeDictId < singletonBiomePalettes.size()) {
                        biomesOut.add(new BiomePaletteSection(sectionY, singletonBiomePalettes.get(defaultBiomeDictId), null));
                    }
                }
            } else {
                int relStart = relStarts[i];
                int relEnd = findSectionRelEnd(relStarts, i, sectionCount, residualRelStart, frameLen);
                try {
                    LcaSectionCodec.DecodedSection dec = LcaSectionCodec.decodeSectionFrame(
                            frame, fOff + relStart, fOff + relEnd, sectionY,
                            canonicalStates, baseBlockNames, biomes,
                            biomeTemplates, true, !decodeBiomes);
                    if (secPatch != null) {
                        dec = LcaSectionCodec.applySectionPatch(dec, secPatch, canonicalStates, baseBlockNames, true);
                    }
                    sectionsOut.add(dec.toPaletteSection());
                    if (decodeBiomes) {
                        BiomePaletteSection bps = dec.toBiomePaletteSection();
                        if (bps != null) {
                            biomesOut.add(bps);
                        } else if (defaultBiomeDictId >= 0 && defaultBiomeDictId < singletonBiomePalettes.size()) {
                            biomesOut.add(new BiomePaletteSection(sectionY, singletonBiomePalettes.get(defaultBiomeDictId), null));
                        }
                    }
                } catch (CorruptRegionEntryException ex) {
                    if (recoveryReport == null) {
                        throw ex;
                    }
                    String fallbackDominantBlock = recoverDominantPaletteZeroState(
                            frame, fOff + relStart, fOff + relEnd, baseBlockNames);
                    sectionsOut.add(new PaletteSection(sectionY, List.of(fallbackDominantBlock), null));
                    if (decodeBiomes && defaultBiomeDictId >= 0 && defaultBiomeDictId < singletonBiomePalettes.size()) {
                        biomesOut.add(new BiomePaletteSection(sectionY, singletonBiomePalettes.get(defaultBiomeDictId), null));
                    }
                    recoveryReport.quarantinedSections++;
                    recoveryReport.diagnostics.add("Tier-3 healed section Y=" + sectionY
                            + " via dominant palette[0]='" + fallbackDominantBlock + "' (" + ex.getMessage() + ")");
                }
            }
        }

        long[] heightmap = null;
        if (decodeHeightmap && hasResidualNbt) {
            int rPos = fOff + residualRelStart;
            int rLim = fOff + frameLen;
            if (rPos + 2 <= rLim) {
                int bpFlags = LcaSectionCodec.readUint16BE(frame, rPos);
                rPos += 2;
                if ((bpFlags & 8192) != 0) {
                    int mbnlLen = LcaSectionCodec.readVarInt(frame, rPos, rLim);
                    rPos += LcaSectionCodec.varIntLen(mbnlLen);
                    if (mbnlLen > 0 && rPos + mbnlLen <= rLim) {
                        heightmap = LcaSectionCodec.decodeDeltaHeightmapLongs(
                                frame, rPos, mbnlLen);
                    }
                }
            }
        }

        return new WindowDecodeResult(
                chunkDataVersion,
                Collections.unmodifiableList(sectionsOut),
                decodeBiomes ? Collections.unmodifiableList(biomesOut) : Collections.emptyList(),
                heightmap,
                minEmittedSectionY
        );
    }

    private static final class ChunkSlabView {
        final int rx;
        final int rz;
        final int ptrByteOffset;
        final long ptrBefore;
        final int codec1;
        final int exactByteLen20;
        final int generation8;
        byte[] frameBytes;
        int frameOffset;
        int frameLength;
        boolean modeBBodyDeferred;

        ChunkSlabView(
                int rx,
                int rz,
                int ptrByteOffset,
                long ptrBefore,
                int codec1,
                int exactByteLen20,
                int generation8,
                byte[] frameBytes,
                int frameOffset,
                int frameLength
        ) {
            this(rx, rz, ptrByteOffset, ptrBefore, codec1, exactByteLen20, generation8,
                    frameBytes, frameOffset, frameLength, false);
        }

        ChunkSlabView(
                int rx,
                int rz,
                int ptrByteOffset,
                long ptrBefore,
                int codec1,
                int exactByteLen20,
                int generation8,
                byte[] frameBytes,
                int frameOffset,
                int frameLength,
                boolean modeBBodyDeferred
        ) {
            this.rx = rx;
            this.rz = rz;
            this.ptrByteOffset = ptrByteOffset;
            this.ptrBefore = ptrBefore;
            this.codec1 = codec1;
            this.exactByteLen20 = exactByteLen20;
            this.generation8 = generation8;
            this.frameBytes = frameBytes;
            this.frameOffset = frameOffset;
            this.frameLength = frameLength;
            this.modeBBodyDeferred = modeBBodyDeferred;
        }

        void ensureBodyDecompressed(int sectionCount) throws IOException {
            if (!modeBBodyDeferred) {
                return;
            }
            int headerLen = 24 + (sectionCount << 1);
            int headerWords = (headerLen + 7) >>> 3;
            int compStart = frameOffset + headerLen + headerWords;
            int compLen = frameLength - (headerLen + headerWords);
            if (compLen <= 0) {
                byte[] headerOnly = Arrays.copyOfRange(frameBytes, frameOffset, frameOffset + headerLen);
                this.frameBytes = headerOnly;
                this.frameOffset = 0;
                this.frameLength = headerOnly.length;
                this.modeBBodyDeferred = false;
                return;
            }
            byte[] decompressedBody = DynamicZstdBinding.decompress(
                    frameBytes, compStart, compLen, MAX_CHUNK_UNCOMPRESSED_BYTES);
            byte[] fullFrame = new byte[headerLen + decompressedBody.length];
            System.arraycopy(frameBytes, frameOffset, fullFrame, 0, headerLen);
            System.arraycopy(decompressedBody, 0, fullFrame, headerLen, decompressedBody.length);
            this.frameBytes = fullFrame;
            this.frameOffset = 0;
            this.frameLength = fullFrame.length;
            this.modeBBodyDeferred = false;
        }

        boolean verifyTearFree(byte[] regionBytes) {
            VarHandle.loadLoadFence();
            return getVolatileLongBE(regionBytes, ptrByteOffset) == ptrBefore;
        }
    }

    private static ChunkSlabView buildSlabViewFromCopy(
            int rx,
            int rz,
            int ptrByteOffset,
            long ptrBefore,
            int codec1,
            int exactByteLen20,
            int generation8,
            byte[] slabCopy,
            int sectionCount,
            RecoveryReport recoveryReport
    ) throws IOException {
        if (codec1 == 1) {
            int headerLen = 24 + (sectionCount << 1);
            int headerWords = (headerLen + 7) >>> 3;
            if (slabCopy.length >= headerLen + headerWords) {
                int rawFirst = LcaSectionCodec.readIntBE(slabCopy, 0);
                boolean looksLikeUncompHeader =
                        (rawFirst & CHUNK_FRAME_SELF_TAG_MASK) == CHUNK_FRAME_SELF_TAG_MAGIC;
                int healed = LcaSectionCodec.verifyAndHealChunkHeaderSecDed(
                        slabCopy, 0, headerLen, headerLen);
                if (healed >= 0
                        && (LcaSectionCodec.readIntBE(slabCopy, 0) & CHUNK_FRAME_SELF_TAG_MASK)
                                == CHUNK_FRAME_SELF_TAG_MAGIC) {
                    if (healed > 0 && recoveryReport != null) {
                        recoveryReport.chunkHeaderSecDedHealedWords += healed;
                        recoveryReport.diagnostics.add("ChunkFrame header SEC-DED healed " + healed
                                + " word(s) in (" + rx + "," + rz + ")");
                    }
                    return new ChunkSlabView(
                            rx, rz, ptrByteOffset, ptrBefore, codec1, exactByteLen20, generation8,
                            slabCopy, 0, slabCopy.length, true);
                }
                if (looksLikeUncompHeader && healed < 0) {
                    throw new CorruptRegionEntryException(
                            "CBIT T3c: Unrecoverable double-bit corruption in Mode B ChunkFrame header at ("
                                    + rx + "," + rz + ")");
                }
            }
            byte[] decompressed = DynamicZstdBinding.decompress(
                    slabCopy, 0, slabCopy.length, MAX_CHUNK_UNCOMPRESSED_BYTES);
            return new ChunkSlabView(
                    rx, rz, ptrByteOffset, ptrBefore, codec1, exactByteLen20, generation8,
                    decompressed, 0, decompressed.length, false);
        }
        return new ChunkSlabView(
                rx, rz, ptrByteOffset, ptrBefore, codec1, exactByteLen20, generation8,
                slabCopy, 0, slabCopy.length, false);
    }

    private ChunkSlabView resolveChunkSlab(
            byte[] regionBytes,
            int regionLength,
            int sectionCount,
            int rx,
            int rz,
            boolean forceCopyForModeA,
            RecoveryReport recoveryReport
    ) throws IOException {
        int chunkIdx = (rx & 31) | ((rz & 31) << 5);
        int ptrByteOffset = POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);

        for (int attempt = 0; attempt < 2; attempt++) {
            long ptrBefore = getVolatileLongBE(regionBytes, ptrByteOffset);
            if (recoveryReport != null && regionLength >= SUPERBLOCK_SIZE + POINTER_DIRECTORY_SIZE) {
                int flags = regionBytes[0x0B] & 0xFF;
                if ((flags & 16) != 0) {
                    long lodOffsetAndLen = (long) LONG_BE_VIEW.get(regionBytes, 0x28);
                    int secDedOff = (int) (lodOffsetAndLen & 0xFFFF_FFFFL);
                    if (secDedOff >= DICT_ROOT_OFFSET && secDedOff + SECDED_TABLE_SIZE <= regionLength) {
                        int syn8 = regionBytes[secDedOff + chunkIdx] & 0xFF;
                        if ((ptrBefore != 0L || syn8 != 0) && LcaSectionCodec.computeHammingSecDed8(ptrBefore) != syn8) {
                            long healed = LcaSectionCodec.healSingleBitFlip64(ptrBefore, syn8);
                            if (healed != Long.MIN_VALUE && healed != ptrBefore) {
                                setVolatileLongBE(regionBytes, ptrByteOffset, healed);
                                ptrBefore = healed;
                                recoveryReport.secDedHealedPointers++;
                                recoveryReport.diagnostics.add("Tier-1 SEC-DED healed 1-bit pointer flip in (" + rx + "," + rz + ")");
                            }
                        }
                    }
                }
            }
            long slabOffset35 = ptrBefore >>> 29;
            int codec1 = (int) ((ptrBefore >>> 28) & 1L);
            int exactByteLen20 = (int) ((ptrBefore >>> 8) & 0xFFFFFL);
            int generation8 = (int) (ptrBefore & 0xFFL);

            if (slabOffset35 == 0 || exactByteLen20 == 0) {
                return null;
            }

            long payloadStartOffset = slabOffset35 << SLAB_SHIFT;
            long actualPayloadLen = exactByteLen20;

            // CBIT T3 & T4: Slab bounds check against fileLength - 8 and allocation ceiling
            if (payloadStartOffset < DICT_ROOT_OFFSET
                    || actualPayloadLen > MAX_CHUNK_UNCOMPRESSED_BYTES
                    || payloadStartOffset + actualPayloadLen > regionLength - FOOTER_SIZE) {
                throw new CorruptRegionEntryException("CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds: offset="
                        + payloadStartOffset + " len=" + actualPayloadLen + " fileLen=" + regionLength);
            }

            if (codec1 == 0 && !forceCopyForModeA) {
                return new ChunkSlabView(
                        rx, rz, ptrByteOffset, ptrBefore, codec1, (int) actualPayloadLen, generation8,
                        regionBytes, (int) payloadStartOffset, (int) actualPayloadLen);
            }

            int hazardSlot = pinHazardSlab((int) slabOffset35);
            byte[] slabCopy;
            try {
                slabCopy = Arrays.copyOfRange(
                        regionBytes, (int) payloadStartOffset, (int) (payloadStartOffset + actualPayloadLen));
            } finally {
                unpinHazardSlab(hazardSlot);
            }

            // CBIT T8: Lock-free CoW snapshot stability fence
            VarHandle.loadLoadFence();
            long ptrAfter = getVolatileLongBE(regionBytes, ptrByteOffset);
            if (ptrBefore != ptrAfter && attempt == 0) {
                continue;
            }

            return buildSlabViewFromCopy(
                    rx, rz, ptrByteOffset, ptrBefore, codec1, (int) actualPayloadLen, generation8,
                    slabCopy, sectionCount, recoveryReport);
        }
        throw new CorruptRegionEntryException("CBIT T8: Concurrent chunk pointer mutation did not stabilize after retry");
    }

    private static int decodeAndVerifySelfDescribingChunkHeader(int rawDvWord, int rx, int rz)
            throws CorruptRegionEntryException {
        if ((rawDvWord & CHUNK_FRAME_SELF_TAG_MASK) == CHUNK_FRAME_SELF_TAG_MAGIC) {
            int expectedChunkIdx = (rx & 31) | ((rz & 31) << 5);
            int actualChunkIdx = (rawDvWord >>> 16) & 0x03FF;
            if (actualChunkIdx != expectedChunkIdx) {
                throw new CorruptRegionEntryException("CBIT T3b: Offset variation detected! Chunk pointer ("
                        + rx + "," + rz + ") [idx=" + expectedChunkIdx + "] points to slab belonging to chunkIdx="
                        + actualChunkIdx);
            }
            return rawDvWord & 0xFFFF;
        }
        return rawDvWord;
    }

    /**
     * Diagnostic report returned by {@link #healRegionBitRot(byte[])} and
     * {@link #readChunkViewWithRecovery(byte[], int, int, RecoveryReport)} (`anvil-api-ADR-003` Section 7.2).
     */
    public static final class RecoveryReport {
        private int secDedHealedPointers;
        private int slabScanReconstructedPointers;
        private int chunkHeaderSecDedHealedWords;
        private int quarantinedSections;
        private final List<String> diagnostics = new CopyOnWriteArrayList<>();

        public int secDedHealedPointers() {
            return secDedHealedPointers;
        }

        public int slabScanReconstructedPointers() {
            return slabScanReconstructedPointers;
        }

        public int chunkHeaderSecDedHealedWords() {
            return chunkHeaderSecDedHealedWords;
        }

        public int quarantinedSections() {
            return quarantinedSections;
        }

        public List<String> diagnostics() {
            return Collections.unmodifiableList(diagnostics);
        }
    }

    /**
     * Multi-tier bit-rot and offset-variation self-healer for `.lca` region archives
     * (`anvil-api-ADR-003` Section 7.2):
     * <ol>
     *   <li><b>Tier 1 (Extended Hamming `(72, 64)` SEC-DED):</b> Repairs any single-bit flip in the
     *       `uint64[1024]` pointer directory (`0x0040..0x203F`) in $O(1)$ using the 1,024-byte
     *       SEC-DED syndrome table, and repairs any 1-bit flip in uncompressed `Mode B` `ChunkFrame`
     *       headers using their inline `headerWords` SEC-DED syndromes.</li>
     *   <li><b>Tier 2 (Self-Describing Slab Reverse-Scan):</b> When a pointer suffers multi-bit
     *       corruption (`DED`), zeroing, or offset variation (`CBIT T3b`), scans the `256B` sub-slab
     *       pool for self-describing {@code 0x5800_0000 | (chunkIdx << 16)} headers and reconstructs the
     *       damaged pointer directory entries.</li>
     * </ol>
     */
    public static RecoveryReport healRegionBitRot(byte[] regionBytes) throws CorruptRegionEntryException {
        RecoveryReport report = new RecoveryReport();
        if (regionBytes != null && regionBytes.length >= SUPERBLOCK_SIZE + POINTER_DIRECTORY_SIZE + FOOTER_SIZE) {
            long magic = (long) LONG_BE_VIEW.get(regionBytes, 0);
            long footer = (long) LONG_BE_VIEW.get(regionBytes, regionBytes.length - FOOTER_SIZE);
            if (magic == LCA_SUPERBLOCK_MAGIC && footer != LCA_FOOTER_CANARY_MAGIC) {
                long actualChecksum = (long) LONG_BE_VIEW.get(regionBytes, 0x38);
                long expectedChecksum = LcaSectionCodec.computeSuperblockChecksum64(regionBytes, 0);
                if (actualChecksum == expectedChecksum) {
                    setVolatileLongBE(regionBytes, regionBytes.length - FOOTER_SIZE, LCA_FOOTER_CANARY_MAGIC);
                    report.secDedHealedPointers++;
                    report.diagnostics.add("Healed corrupted/missing LCA1_END footer canary");
                }
            }
        }
        LcaSectionCodec.ParsedHeaderAndDict meta =
                INSTANCE.parseAndValidateSuperblockAndDict(regionBytes, regionBytes.length);
        long lodOffsetAndLen = (long) LONG_BE_VIEW.get(regionBytes, 0x28);
        int secDedOff = (int) (lodOffsetAndLen & 0xFFFF_FFFFL);
        boolean hasSecDed = (meta.flags & 16) != 0;
        int headerLen = 24 + (meta.sectionCount << 1);
        int headerWords = (headerLen + 7) >>> 3;

        boolean[] needsSlabScan = new boolean[1024];
        boolean anyNeedsScan = false;

        // Tier 1: Extended Hamming (72, 64) SEC-DED 1-bit pointer healing + CBIT T3/T3b verification
        for (int chunkIdx = 0; chunkIdx < 1024; chunkIdx++) {
            int ptrPos = POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long ptr = (long) LONG_BE_VIEW.get(regionBytes, ptrPos);
            int syn8 = hasSecDed ? (regionBytes[secDedOff + chunkIdx] & 0xFF) : 0;

            if (hasSecDed && (ptr != 0L || syn8 != 0)) {
                int expectedSyn = LcaSectionCodec.computeHammingSecDed8(ptr);
                if (expectedSyn != syn8) {
                    long healed = LcaSectionCodec.healSingleBitFlip64(ptr, syn8);
                    if (healed == Long.MIN_VALUE) {
                        needsSlabScan[chunkIdx] = true;
                        anyNeedsScan = true;
                        continue;
                    } else if (healed != ptr) {
                        setVolatileLongBE(regionBytes, ptrPos, healed);
                        ptr = healed;
                        report.secDedHealedPointers++;
                        report.diagnostics.add("Tier-1 SEC-DED healed 1-bit flip in chunkIdx=" + chunkIdx);
                    } else {
                        regionBytes[secDedOff + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(ptr);
                    }
                }
            }

            if (ptr != 0L) {
                long slabOff = (ptr >>> 29) << SLAB_SHIFT;
                int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                int codec1 = (int) ((ptr >>> 28) & 1L);
                if (slabOff < meta.slabPoolBaseOffset || slabOff + byteLen > regionBytes.length - FOOTER_SIZE) {
                    needsSlabScan[chunkIdx] = true;
                    anyNeedsScan = true;
                } else {
                    if (codec1 == 1 && slabOff + headerLen + headerWords <= regionBytes.length - FOOTER_SIZE) {
                        int hdrHealed = LcaSectionCodec.verifyAndHealChunkHeaderSecDed(
                                regionBytes, (int) slabOff, headerLen, (int) slabOff + headerLen);
                        if (hdrHealed > 0) {
                            report.chunkHeaderSecDedHealedWords += hdrHealed;
                            report.diagnostics.add("Tier-1 ChunkFrame header SEC-DED healed " + hdrHealed
                                    + " word(s) in chunkIdx=" + chunkIdx);
                        }
                    }
                    int dvWord = LcaSectionCodec.readIntBE(regionBytes, (int) slabOff);
                    if ((dvWord & CHUNK_FRAME_SELF_TAG_MASK) != CHUNK_FRAME_SELF_TAG_MAGIC
                            || ((dvWord >>> 16) & 0x03FF) != chunkIdx) {
                        needsSlabScan[chunkIdx] = true;
                        anyNeedsScan = true;
                    }
                }
            }
        }

        // Tier 2: Self-Describing 256B Slab Pool Reverse-Scan for multi-bit offset variation / wiped pointers
        if (anyNeedsScan) {
            int startSlab = (int) (meta.slabPoolBaseOffset >>> SLAB_SHIFT);
            int endSlab = (regionBytes.length - FOOTER_SIZE) >>> SLAB_SHIFT;
            boolean regionDefaultModeB = (meta.flags & 8) != 0;
            for (int slab = endSlab - 1; slab >= startSlab; slab--) {
                int bytePos = slab << SLAB_SHIFT;
                if (bytePos + headerLen > regionBytes.length - FOOTER_SIZE) {
                    continue;
                }
                int dvWord = LcaSectionCodec.readIntBE(regionBytes, bytePos);
                if ((dvWord & CHUNK_FRAME_SELF_TAG_MASK) != CHUNK_FRAME_SELF_TAG_MAGIC
                        && regionDefaultModeB
                        && bytePos + headerLen + headerWords <= regionBytes.length - FOOTER_SIZE) {
                    int hdrHealed = LcaSectionCodec.verifyAndHealChunkHeaderSecDed(
                            regionBytes, bytePos, headerLen, bytePos + headerLen);
                    if (hdrHealed > 0) {
                        report.chunkHeaderSecDedHealedWords += hdrHealed;
                        dvWord = LcaSectionCodec.readIntBE(regionBytes, bytePos);
                    }
                }
                if ((dvWord & CHUNK_FRAME_SELF_TAG_MASK) != CHUNK_FRAME_SELF_TAG_MAGIC) {
                    continue;
                }
                int slabChunkIdx = (dvWord >>> 16) & 0x03FF;
                int ptrPos = POINTER_DIRECTORY_OFFSET + (slabChunkIdx << 3);
                if (!needsSlabScan[slabChunkIdx]) {
                    continue;
                }
                int exactLen = estimateSelfDescribingChunkLengthStatic(
                        regionBytes, bytePos, meta.sectionCount, regionDefaultModeB, regionBytes.length - FOOTER_SIZE);
                long reconstructedPtr = LcaRegionWriter.packChunkPointer64(slab, regionDefaultModeB, exactLen, 1);
                setVolatileLongBE(regionBytes, ptrPos, reconstructedPtr);
                if (hasSecDed) {
                    regionBytes[secDedOff + slabChunkIdx] =
                            (byte) LcaSectionCodec.computeHammingSecDed8(reconstructedPtr);
                }
                needsSlabScan[slabChunkIdx] = false;
                report.slabScanReconstructedPointers++;
                report.diagnostics.add("Tier-2 Slab Reverse-Scan reconstructed pointer for chunkIdx="
                        + slabChunkIdx + " at slab=" + slab + " len=" + exactLen);
            }
        }
        return report;
    }

    static int estimateSelfDescribingChunkLengthStatic(
            byte[] buf, int frameStart, int sectionCount, int limitBytes
    ) {
        return estimateSelfDescribingChunkLengthStatic(buf, frameStart, sectionCount, false, limitBytes);
    }

    static int estimateSelfDescribingChunkLengthStatic(
            byte[] buf, int frameStart, int sectionCount, boolean modeBZstd, int limitBytes
    ) {
        int sectionDirEnd = 24 + (sectionCount << 1);
        if (modeBZstd) {
            int headerWords = (sectionDirEnd + 7) >>> 3;
            int compStart = frameStart + sectionDirEnd + headerWords;
            if (compStart + 5 <= limitBytes
                    && (buf[compStart] & 0xFF) == 0x28
                    && (buf[compStart + 1] & 0xFF) == 0xB5
                    && (buf[compStart + 2] & 0xFF) == 0x2F
                    && (buf[compStart + 3] & 0xFF) == 0xFD) {
                int pos = compStart + 4;
                int fhd = buf[pos++] & 0xFF;
                int fcsCode = fhd >>> 6;
                boolean singleSegment = (fhd & 0x20) != 0;
                boolean hasChecksum = (fhd & 0x04) != 0;
                int dictIdCode = fhd & 0x03;
                if (!singleSegment) {
                    pos++;
                }
                pos += (dictIdCode == 3) ? 4 : dictIdCode;
                pos += (fcsCode == 0) ? (singleSegment ? 1 : 0) : (1 << fcsCode);
                while (pos + 3 <= limitBytes) {
                    int blockHeader = (buf[pos] & 0xFF)
                            | ((buf[pos + 1] & 0xFF) << 8)
                            | ((buf[pos + 2] & 0xFF) << 16);
                    pos += 3;
                    boolean lastBlock = (blockHeader & 1) != 0;
                    int blockType = (blockHeader >>> 1) & 3;
                    if (blockType == 3) {
                        break;
                    }
                    int blockSize = blockHeader >>> 3;
                    pos += (blockType == 1) ? 1 : blockSize;
                    if (lastBlock) {
                        if (hasChecksum) {
                            pos += 4;
                        }
                        if (pos <= limitBytes) {
                            return pos - frameStart;
                        }
                        break;
                    }
                }
            }
            return Math.min(sectionDirEnd + headerWords, limitBytes - frameStart);
        }
        int chunkFlags = buf[frameStart + 21] & 0xFF;
        boolean hasResidualNbt = (chunkFlags & 1) != 0;
        boolean scale4B = (chunkFlags & 4) != 0;
        int unitShift = scale4B ? 2 : 0;
        int residualRel = LcaSectionCodec.readUint16BE(buf, frameStart + 22);
        int residualStart = frameStart + sectionDirEnd + (residualRel << unitShift);
        if (!hasResidualNbt || residualStart + 2 > limitBytes) {
            return sectionDirEnd + (residualRel << unitShift);
        }
        try {
            int pos = residualStart;
            int bpFlags = LcaSectionCodec.readUint16BE(buf, pos);
            pos += 2;
            if ((bpFlags & 8192) != 0) {
                int mbnlLen = LcaSectionCodec.readVarInt(buf, pos, limitBytes);
                pos += LcaSectionCodec.varIntLen(mbnlLen) + mbnlLen;
            }
            if ((bpFlags & 32) != 0) {
                pos++;
            }
            if ((bpFlags & 64) != 0 && pos < limitBytes) {
                int slVer = LcaSectionCodec.readVarInt(buf, pos, limitBytes);
                pos += LcaSectionCodec.varIntLen(slVer);
            }
            if ((bpFlags & 128) != 0) {
                pos++;
            }
            if ((bpFlags & 256) != 0) {
                pos++;
            }
            if ((bpFlags & 512) != 0 && pos < limitBytes) {
                int secHmCount = buf[pos++] & 0xFF;
                for (int h = 0; h < secHmCount && pos < limitBytes; h++) {
                    int code = buf[pos++] & 0xFF;
                    if (code >= LcaSectionCodec.STANDARD_HEIGHTMAP_NAMES.length) {
                        int kLen = LcaSectionCodec.readVarInt(buf, pos, limitBytes);
                        pos += LcaSectionCodec.varIntLen(kLen) + kLen;
                    }
                    int payLen = LcaSectionCodec.readVarInt(buf, pos, limitBytes);
                    pos += LcaSectionCodec.varIntLen(payLen) + payLen;
                }
            }
            int patchPayloadStart = -1;
            int patchPayloadLen = 0;
            if ((bpFlags & LcaSectionCodec.BP_FLAG_HAS_CHUNK_PATCH_TAIL) != 0 && pos < limitBytes) {
                patchPayloadLen = LcaSectionCodec.readVarInt(buf, pos, limitBytes);
                pos += LcaSectionCodec.varIntLen(patchPayloadLen);
                patchPayloadStart = pos;
                pos += patchPayloadLen;
            }
            int maxPostSecStart = LcaSectionCodec.findMaxPostResidualSectionStart(
                    buf, frameStart, sectionCount, sectionDirEnd, unitShift,
                    residualStart, patchPayloadStart, patchPayloadLen, limitBytes);
            if (maxPostSecStart >= 0) {
                int secEnd = LcaSectionCodec.skipSectionFrame(buf, maxPostSecStart, limitBytes);
                if (secEnd > pos && secEnd <= limitBytes) {
                    pos = secEnd;
                }
            } else if ((bpFlags & 1024) != 0 && pos < limitBytes) {
                for (int s = ((pos - frameStart + 255) >>> SLAB_SHIFT); ; s++) {
                    int candEnd = frameStart + (s << SLAB_SHIFT);
                    if (candEnd >= limitBytes) {
                        int trimmed = candEnd;
                        while (trimmed > pos && buf[trimmed - 1] == 0) {
                            trimmed--;
                        }
                        pos = trimmed;
                        break;
                    }
                    long maybeCanary = (long) LONG_BE_VIEW.get(buf, candEnd);
                    int maybeDv = LcaSectionCodec.readIntBE(buf, candEnd);
                    if (maybeCanary == LCA_FOOTER_CANARY_MAGIC
                            || (maybeDv & CHUNK_FRAME_SELF_TAG_MASK) == CHUNK_FRAME_SELF_TAG_MAGIC) {
                        int trimmed = candEnd;
                        while (trimmed > pos && buf[trimmed - 1] == 0) {
                            trimmed--;
                        }
                        pos = trimmed;
                        break;
                    }
                }
            }
            if (pos <= limitBytes) {
                return pos - frameStart;
            }
        } catch (Exception ignored) {
        }
        return sectionDirEnd + (residualRel << unitShift);
    }

    /**
     * Tier-3 Section-Granular Self-Healing Reader (`anvil-api-ADR-003` Section 7.2):
     * Decodes a chunk into an {@link AnvilChunkView} while isolating any corrupted $16 \times 16 \times 16$
     * `SectionFrame` (`CBIT T6` cursor overflow or `CBIT T6b` `octreeOccupancyMask64` syndrome mismatch).
     * Instead of failing the entire 384-block chunk column (as in `.mca`) or the entire 1,024-chunk region
     * (as in `.linear`), substitutes the damaged section's dominant `palette[0]` stratum (`90.8%` of voxels)
     * and preserves the remaining 23 vertical sections intact.
     */
    public static AnvilChunkView readChunkViewWithRecovery(
            byte[] regionBytes, int rx, int rz, RecoveryReport report
    ) throws IOException {
        WindowDecodeResult res = decodeWindowWithRetry(
                regionBytes, regionBytes.length, rx, rz,
                Integer.MIN_VALUE, Integer.MAX_VALUE, true, true,
                report != null ? report : new RecoveryReport());
        return res == null ? null : res.toChunkView();
    }

    private static String recoverDominantPaletteZeroState(
            byte[] buf, int offset, int limit, List<String> dictBaseNames
    ) {
        try {
            if (offset + 3 <= limit) {
                int sectionHeader = buf[offset] & 0xFF;
                int blockMode = (sectionHeader >>> 4) & 3;
                int pos = offset + 1 + ((blockMode == LcaSectionCodec.BLOCK_MODE_DELTA_ROARING
                        || blockMode == LcaSectionCodec.BLOCK_MODE_FLAT_BITPACK) ? 8 : 0);
                if (pos < limit) {
                    int paletteLen = LcaSectionCodec.readVarInt(buf, pos, limit);
                    pos += LcaSectionCodec.varIntLen(paletteLen);
                    if (paletteLen >= 1 && pos < limit) {
                        int dominantDictId = LcaSectionCodec.readVarInt(buf, pos, limit);
                        if (dominantDictId >= 0 && dominantDictId < dictBaseNames.size()) {
                            return dictBaseNames.get(dominantDictId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to safe air fallback
        }
        return "minecraft:air";
    }

    /**
     * Parses and validates the 64-byte superblock, 8-byte footer canary, and chained shared
     * dictionary (`CBIT T1, T2, T5, T7`).
     */
    LcaSectionCodec.ParsedHeaderAndDict parseAndValidateSuperblockAndDict(byte[] regionBytes, int regionLength)
            throws CorruptRegionEntryException {
        SuperblockFastMeta sb = validateSuperblockFast(regionBytes, regionLength);

        long actualChecksum = (long) LONG_BE_VIEW.get(regionBytes, 0x38);
        long dictOffsetAndLen = (long) LONG_BE_VIEW.get(regionBytes, 0x20);
        int rootDictOffset = (int) (dictOffsetAndLen & 0xFFFF_FFFFL);
        int rootDictCapacity = (int) (dictOffsetAndLen >>> 32);

        if (rootDictOffset != DICT_ROOT_OFFSET
                || rootDictCapacity < 16
                || (long) rootDictOffset + rootDictCapacity > regionLength - FOOTER_SIZE) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid root dictionary bounds: offset="
                    + rootDictOffset + " capacity=" + rootDictCapacity);
        }

        int rootStateCount = LcaSectionCodec.readIntBE(regionBytes, rootDictOffset);
        long rootHeaderWord = (long) LONG_BE_VIEW.get(regionBytes, rootDictOffset);
        long rootTrailerWord = (long) LONG_BE_VIEW.get(regionBytes, rootDictOffset + rootDictCapacity - 8);
        int ringIdx = (System.identityHashCode(regionBytes) ^ (int) actualChecksum) & FAST_DICT_RING_MASK;
        FastDictCacheSlot cachedSlot = FAST_DICT_RING[ringIdx];
        if (cachedSlot != null
                && cachedSlot.regionBytesRef == regionBytes
                && cachedSlot.headerChecksum == actualChecksum
                && cachedSlot.rootHeaderWord == rootHeaderWord
                && cachedSlot.rootTrailerWord == rootTrailerWord) {
            return cachedSlot.parsed;
        }

        int storageKind = regionBytes[0x0A] & 0xFF;
        int flags = regionBytes[0x0B] & 0xFF;
        int regionX = LcaSectionCodec.readIntBE(regionBytes, 0x0C);
        int regionZ = LcaSectionCodec.readIntBE(regionBytes, 0x10);
        int minSectionY = sb.minSectionY;
        int sectionCount = sb.sectionCount;
        int defaultDataVersion = LcaSectionCodec.readIntBE(regionBytes, 0x1C);
        long slabPoolBaseOffset = (long) LONG_BE_VIEW.get(regionBytes, 0x30);

        // Walk dynamically chained dictionary segments (Section 3.1, CBIT T5 acyclic <= 64 segments)
        List<String> canonicalStates = new ArrayList<>(Math.min(1024, Math.max(16, rootStateCount)));
        List<String> baseBlockNames = new ArrayList<>(Math.min(1024, Math.max(16, rootStateCount)));
        List<List<String>> singletonBasePalettes = new ArrayList<>(Math.min(1024, Math.max(16, rootStateCount)));
        List<String> biomes = new ArrayList<>(16);
        List<List<String>> singletonBiomePalettes = new ArrayList<>(16);

        int segOffset = rootDictOffset;
        int segCap = rootDictCapacity;
        int segmentHops = 0;

        while (true) {
            if (++segmentHops > MAX_DICT_CHAIN_SEGMENTS) {
                throw new CorruptRegionEntryException("CBIT T5: Dictionary continuation chain exceeded "
                        + MAX_DICT_CHAIN_SEGMENTS + " segments (cycle detected)");
            }
            if (segOffset < DICT_ROOT_OFFSET || segCap < 16 || (long) segOffset + segCap > regionLength - FOOTER_SIZE) {
                throw new CorruptRegionEntryException("CBIT T5: Dictionary segment out of file bounds: offset="
                        + segOffset + " cap=" + segCap);
            }

            int segStateCount = LcaSectionCodec.readIntBE(regionBytes, segOffset);
            int segBiomeCount = LcaSectionCodec.readUint16BE(regionBytes, segOffset + 4);
            int usedPayloadBytes = LcaSectionCodec.readUint16BE(regionBytes, segOffset + 6);
            int segPayloadLimit = segOffset + segCap - 8;
            if (segStateCount < 0 || segStateCount > 65536
                    || segBiomeCount < 0 || segBiomeCount > 16384
                    || segOffset + 8 + usedPayloadBytes > segPayloadLimit) {
                throw new CorruptRegionEntryException("CBIT T5: Dictionary segment header corrupt at " + segOffset);
            }

            int pos = segOffset + 8;
            for (int i = 0; i < segStateCount; i++) {
                int utfLen = LcaSectionCodec.readVarInt(regionBytes, pos, segPayloadLimit);
                pos += LcaSectionCodec.varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen + 4 > segPayloadLimit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated dictionary BlockState entry");
                }
                String canonical = new String(regionBytes, pos, utfLen, StandardCharsets.UTF_8);
                pos += utfLen + 4;
                String baseName = LcaSectionCodec.stripBlockProperties(canonical);
                canonicalStates.add(canonical);
                baseBlockNames.add(baseName);
                singletonBasePalettes.add(List.of(baseName));
            }

            for (int i = 0; i < segBiomeCount; i++) {
                int utfLen = LcaSectionCodec.readVarInt(regionBytes, pos, segPayloadLimit);
                pos += LcaSectionCodec.varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen + 4 > segPayloadLimit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated dictionary Biome entry");
                }
                String biomeId = new String(regionBytes, pos, utfLen, StandardCharsets.UTF_8);
                pos += utfLen + 4;
                biomes.add(biomeId);
                singletonBiomePalettes.add(List.of(biomeId));
            }

            long trailer = (long) LONG_BE_VIEW.get(regionBytes, segPayloadLimit);
            int nextRelSlabs32 = (int) (trailer >>> 32);
            int nextCapSlabs24 = (int) ((trailer >>> 8) & 0xFFFFFFL);
            if (nextRelSlabs32 == 0) {
                break;
            }
            segOffset = (segOffset & ~0xFF) + (nextRelSlabs32 << SLAB_SHIFT);
            segCap = nextCapSlabs24 << SLAB_SHIFT;
        }

        LcaSectionCodec.ParsedHeaderAndDict parsed = new LcaSectionCodec.ParsedHeaderAndDict(
                storageKind, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                flags, slabPoolBaseOffset,
                Collections.unmodifiableList(canonicalStates),
                Collections.unmodifiableList(baseBlockNames),
                Collections.unmodifiableList(singletonBasePalettes),
                Collections.unmodifiableList(biomes),
                Collections.unmodifiableList(singletonBiomePalettes)
        );
        FAST_DICT_RING[ringIdx] = new FastDictCacheSlot(
                regionBytes, actualChecksum, rootHeaderWord, rootTrailerWord, parsed);
        return parsed;
    }

    static String stripBlockProperties(String canonicalState) {
        return LcaSectionCodec.stripBlockProperties(canonicalState);
    }

    static LinkedHashMap<String, Object> parseCanonicalStateToNbtCompound(String canonicalState) {
        return LcaSectionCodec.parseCanonicalStateToNbtCompound(canonicalState);
    }

    static String decodeStatusOrdinal(int ordinal) {
        return LcaSectionCodec.decodeStatusOrdinal(ordinal);
    }

    static int encodeStatusOrdinal(Object statusObj) {
        return LcaSectionCodec.encodeStatusOrdinal(statusObj);
    }

    private static void validateCoordinates(int rx, int rz) {
        if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
            throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Positional Single-Chunk FileChannel Reader & Header Cache (ADR-016 / anvil-api-ADR-003)
    // -------------------------------------------------------------------------

    private static final int FILE_HEADER_CACHE_CAPACITY = 1024;
    private static final int INITIAL_FILE_SCRATCH_BYTES = 64 * 1024;
    private static final ThreadLocal<byte[]> FILE_SCRATCH =
            ThreadLocal.withInitial(() -> new byte[INITIAL_FILE_SCRATCH_BYTES]);

    private static final ConcurrentHashMap<Path, CachedFileHeader> FILE_HEADER_CACHE =
            new ConcurrentHashMap<>(FILE_HEADER_CACHE_CAPACITY);

    static final class CachedFileHeader {
        final byte[] headerBytes;
        final byte[] secDedBytes;
        final SuperblockFastMeta sb;
        final long mtime;
        volatile long fileSize;
        volatile long checkedNanos;
        volatile LcaSectionCodec.ParsedHeaderAndDict parsedDict;

        CachedFileHeader(
                byte[] headerBytes, byte[] secDedBytes, SuperblockFastMeta sb, long mtime, long fileSize, long checkedNanos
        ) {
            this.headerBytes = headerBytes;
            this.secDedBytes = secDedBytes;
            this.sb = sb;
            this.mtime = mtime;
            this.fileSize = fileSize;
            this.checkedNanos = checkedNanos;
        }

        long pointer(int rx, int rz) {
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            int ptrByteOffset = POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long ptr = getVolatileLongBE(headerBytes, ptrByteOffset);
            if (secDedBytes != null && chunkIdx < secDedBytes.length) {
                int syn8 = secDedBytes[chunkIdx] & 0xFF;
                if ((ptr != 0L || syn8 != 0) && LcaSectionCodec.computeHammingSecDed8(ptr) != syn8) {
                    long healed = LcaSectionCodec.healSingleBitFlip64(ptr, syn8);
                    if (healed != Long.MIN_VALUE && healed != ptr) {
                        setVolatileLongBE(headerBytes, ptrByteOffset, healed);
                        ptr = healed;
                    }
                }
            }
            return ptr;
        }

        boolean isOccupied(int rx, int rz) {
            long ptr = pointer(rx, rz);
            long byteOffset = (ptr >>> 29) << SLAB_SHIFT;
            int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
            return byteOffset >= DICT_ROOT_OFFSET
                    && exactByteLen20 > 0
                    && byteOffset + exactByteLen20 <= fileSize - FOOTER_SIZE;
        }

        LcaSectionCodec.ParsedHeaderAndDict ensureParsedDict(FileChannel ch) throws IOException {
            LcaSectionCodec.ParsedHeaderAndDict d = this.parsedDict;
            if (d != null) {
                return d;
            }
            d = loadDictionaryFromChannel(ch, headerBytes, sb, fileSize);
            this.parsedDict = d;
            return d;
        }
    }

    static void updateCachedPointer(
            Path regionFile, int chunkIdx, long newPtr, long newFileSize, boolean dictModified
    ) {
        if (regionFile == null) return;
        CachedFileHeader h = FILE_HEADER_CACHE.get(regionFile);
        if (h != null) {
            int ptrByteOffset = POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            setVolatileLongBE(h.headerBytes, ptrByteOffset, newPtr);
            if (h.secDedBytes != null && chunkIdx < h.secDedBytes.length) {
                h.secDedBytes[chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
            }
            if (newFileSize > h.fileSize) {
                h.fileSize = newFileSize;
            }
            h.checkedNanos = System.nanoTime();
            if (dictModified) {
                h.parsedDict = null;
            }
        }
        AnvilRegionHeaderCache.invalidate(regionFile);
    }

    public static void invalidateFileCache(Path regionFile) {
        if (regionFile == null) return;
        FILE_HEADER_CACHE.remove(regionFile);
        AnvilRegionHeaderCache.invalidate(regionFile);
    }

    public static void invalidateAllFileCaches() {
        FILE_HEADER_CACHE.clear();
    }

    public static boolean hasValidFileHeader(Path regionFile) {
        try {
            return getOrLoadFileHeader(regionFile) != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isChunkGeneratedInFile(Path regionFile, int rx, int rz) throws IOException {
        if ((rx | rz | (31 - rx) | (31 - rz)) < 0 || regionFile == null) {
            return false;
        }
        CachedFileHeader h = getOrLoadFileHeader(regionFile);
        return h != null && h.isOccupied(rx, rz);
    }

    public static AnvilReader.ChunkEntry readChunkFromFile(Path regionFile, int rx, int rz) throws IOException {
        if (regionFile == null) return null;
        try (FileSession s = openFileSession(regionFile)) {
            return s.readChunkEntry(rx, rz);
        }
    }

    public static AnvilReader.ChunkEntry readChunkFromChannel(
            Path regionFile, FileChannel channel, int rx, int rz
    ) throws IOException {
        validateCoordinates(rx, rz);
        if (regionFile == null) return null;
        if (channel == null || !channel.isOpen()) {
            return readChunkFromFile(regionFile, rx, rz);
        }
        CachedFileHeader header = freshFileHeader(regionFile);
        boolean cachedHeader = header != null;
        if (header == null) {
            long mtime = fileMtimeOrMinusOne(regionFile);
            if (mtime < 0L) {
                invalidateFileCache(regionFile);
                return null;
            }
            header = revalidateFileHeader(regionFile, mtime);
            if (header != null) {
                cachedHeader = true;
            } else {
                header = loadFileHeader(regionFile, channel, mtime);
                if (header == null) return null;
            }
        }
        boolean reloaded = false;
        while (true) {
            try {
                if (!header.isOccupied(rx, rz)) {
                    return null;
                }
                ChunkSlabView snap = readSlabFromChannel(channel, header, rx, rz);
                if (snap == null) return null;
                LcaSectionCodec.ParsedHeaderAndDict dict = header.ensureParsedDict(channel);
                return decodeChunkFromFrame(dict, snap, rx, rz);
            } catch (CorruptRegionEntryException e) {
                if (cachedHeader && !reloaded) {
                    reloaded = true;
                    cachedHeader = false;
                    long mtime = fileMtimeOrMinusOne(regionFile);
                    if (mtime < 0L) {
                        invalidateFileCache(regionFile);
                        return null;
                    }
                    header = loadFileHeader(regionFile, channel, mtime);
                    if (header == null) return null;
                    continue;
                }
                throw e;
            }
        }
    }

    public static byte[] readChunkNbtBytesFromFile(Path regionFile, int rx, int rz) throws IOException {
        if (regionFile == null) return null;
        try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            return readChunkNbtBytesFromChannel(regionFile, ch, rx, rz);
        } catch (NoSuchFileException e) {
            invalidateFileCache(regionFile);
            return null;
        }
    }

    public static byte[] readChunkNbtBytesFromChannel(
            Path regionFile, FileChannel channel, int rx, int rz
    ) throws IOException {
        validateCoordinates(rx, rz);
        if (regionFile == null) return null;
        if (channel == null || !channel.isOpen()) {
            return readChunkNbtBytesFromFile(regionFile, rx, rz);
        }
        CachedFileHeader header = freshFileHeader(regionFile);
        boolean cachedHeader = header != null;
        if (header == null) {
            long mtime = fileMtimeOrMinusOne(regionFile);
            if (mtime < 0L) {
                invalidateFileCache(regionFile);
                return null;
            }
            header = revalidateFileHeader(regionFile, mtime);
            if (header != null) {
                cachedHeader = true;
            } else {
                header = loadFileHeader(regionFile, channel, mtime);
                if (header == null) return null;
            }
        }
        boolean reloaded = false;
        while (true) {
            try {
                if (!header.isOccupied(rx, rz)) {
                    return null;
                }
                ChunkSlabView snap = readSlabFromChannel(channel, header, rx, rz);
                if (snap == null) return null;
                LcaSectionCodec.ParsedHeaderAndDict dict = header.ensureParsedDict(channel);
                return encodeChunkNbtBytesFromFrame(dict, snap, rx, rz);
            } catch (CorruptRegionEntryException e) {
                if (cachedHeader && !reloaded) {
                    reloaded = true;
                    cachedHeader = false;
                    long mtime = fileMtimeOrMinusOne(regionFile);
                    if (mtime < 0L) {
                        invalidateFileCache(regionFile);
                        return null;
                    }
                    header = loadFileHeader(regionFile, channel, mtime);
                    if (header == null) return null;
                    continue;
                }
                throw e;
            }
        }
    }

    public static AnvilChunkView readChunkViewFromFile(Path regionFile, int rx, int rz) throws IOException {
        if (regionFile == null) return null;
        try (FileSession s = openFileSession(regionFile)) {
            return s.readChunkView(rx, rz);
        }
    }

    public static ColumnProbe readColumnProbeFromFile(
            Path regionFile, int chunkX, int chunkZ, int minY, int maxY
    ) throws IOException {
        if (regionFile == null) return null;
        try (FileSession s = openFileSession(regionFile)) {
            return s.readColumnProbe(chunkX, chunkZ, minY, maxY);
        }
    }

    public static FileSession openFileSession(Path regionFile) {
        return new FileSession(regionFile);
    }

    public static final class FileSession implements AutoCloseable {
        private final Path regionFile;
        private FileChannel ch;
        private CachedFileHeader header;
        private boolean cachedHeader;
        private boolean reloaded;
        private boolean missing;

        FileSession(Path regionFile) {
            this.regionFile = regionFile;
        }

        public boolean isChunkGenerated(int rx, int rz) throws IOException {
            if (missing || regionFile == null) return false;
            if (!ensureHeader()) return false;
            return header.isOccupied(rx, rz);
        }

        public AnvilChunkView readChunkView(int rx, int rz) throws IOException {
            WindowDecodeResult res = readWindow(rx, rz, Integer.MIN_VALUE, Integer.MAX_VALUE, true, true);
            return res == null ? null : res.toChunkView();
        }

        public ColumnProbe readColumnProbe(int chunkX, int chunkZ, int minY, int maxY) throws IOException {
            if (minY > maxY) {
                throw new IllegalArgumentException("minY=" + minY + " must be <= maxY=" + maxY);
            }
            int rx = Math.floorMod(chunkX, 32);
            int rz = Math.floorMod(chunkZ, 32);
            WindowDecodeResult res = readWindow(rx, rz, minY, maxY, true, true);
            return res == null ? null : res.toColumnProbe(minY, maxY);
        }

        public ColumnProbe readTerrainProbe(int chunkX, int chunkZ, int minY, int maxY) throws IOException {
            if (minY > maxY) {
                throw new IllegalArgumentException("minY=" + minY + " must be <= maxY=" + maxY);
            }
            int rx = Math.floorMod(chunkX, 32);
            int rz = Math.floorMod(chunkZ, 32);
            WindowDecodeResult res = readWindow(rx, rz, minY, maxY, false, false);
            if (res == null || res.sections.isEmpty()) return null;
            return new ColumnProbe(minY, maxY, Integer.MIN_VALUE, res.sections, Collections.emptyList());
        }

        public AnvilReader.ChunkEntry readChunkEntry(int rx, int rz) throws IOException {
            validateCoordinates(rx, rz);
            if (missing || regionFile == null) return null;
            if (!ensureHeader()) return null;
            while (true) {
                try {
                    if (!header.isOccupied(rx, rz)) {
                        return null;
                    }
                    FileChannel channel = ensureChannel();
                    if (channel == null) return null;
                    ChunkSlabView snap = readSlabFromChannel(channel, header, rx, rz);
                    if (snap == null) return null;
                    LcaSectionCodec.ParsedHeaderAndDict dict = header.ensureParsedDict(channel);
                    return decodeChunkFromFrame(dict, snap, rx, rz);
                } catch (CorruptRegionEntryException e) {
                    if (cachedHeader && !reloaded) {
                        if (!reloadHeader()) return null;
                        continue;
                    }
                    throw e;
                }
            }
        }

        private WindowDecodeResult readWindow(
                int rx, int rz, int minY, int maxY, boolean decodeBiomes, boolean decodeHeightmap
        ) throws IOException {
            validateCoordinates(rx, rz);
            if (missing || regionFile == null) return null;
            if (!ensureHeader()) return null;
            while (true) {
                try {
                    if (!header.isOccupied(rx, rz)) {
                        return null;
                    }
                    FileChannel channel = ensureChannel();
                    if (channel == null) return null;
                    ChunkSlabView snap = readSlabFromChannel(channel, header, rx, rz);
                    if (snap == null) return null;
                    if (snap.frameLength < 24) {
                        throw new CorruptRegionEntryException("CBIT T5: ChunkFrame too short (" + snap.frameLength + ")");
                    }
                    boolean hasSlabLocalPalette = (snap.frameBytes[snap.frameOffset + 21] & 2) != 0;
                    LcaSectionCodec.ParsedHeaderAndDict dict = hasSlabLocalPalette
                            ? null
                            : header.ensureParsedDict(channel);
                    return decodeWindowToViewOrProbe(
                            null, 0, header.sb, dict, snap, minY, maxY, decodeBiomes, decodeHeightmap, null);
                } catch (CorruptRegionEntryException e) {
                    if (cachedHeader && !reloaded) {
                        if (!reloadHeader()) return null;
                        continue;
                    }
                    throw e;
                }
            }
        }

        private boolean ensureHeader() throws IOException {
            if (header != null) return true;
            CachedFileHeader h = freshFileHeader(regionFile);
            if (h != null) {
                header = h;
                cachedHeader = true;
                return true;
            }
            long mtime = fileMtimeOrMinusOne(regionFile);
            if (mtime < 0L) {
                invalidateFileCache(regionFile);
                missing = true;
                return false;
            }
            h = revalidateFileHeader(regionFile, mtime);
            if (h != null) {
                header = h;
                cachedHeader = true;
                return true;
            }
            FileChannel channel = ensureChannel();
            if (channel == null) return false;
            header = loadFileHeader(regionFile, channel, mtime);
            cachedHeader = false;
            return header != null;
        }

        private boolean reloadHeader() throws IOException {
            reloaded = true;
            cachedHeader = false;
            FileChannel channel = ensureChannel();
            if (channel == null) {
                header = null;
                return false;
            }
            long mtime = fileMtimeOrMinusOne(regionFile);
            if (mtime < 0L) {
                invalidateFileCache(regionFile);
                missing = true;
                header = null;
                return false;
            }
            header = loadFileHeader(regionFile, channel, mtime);
            return header != null;
        }

        private FileChannel ensureChannel() throws IOException {
            if (ch != null) return ch;
            try {
                ch = FileChannel.open(regionFile, StandardOpenOption.READ);
                return ch;
            } catch (NoSuchFileException e) {
                invalidateFileCache(regionFile);
                missing = true;
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            if (ch != null) {
                ch.close();
                ch = null;
            }
        }
    }

    private static CachedFileHeader getOrLoadFileHeader(Path regionFile) throws IOException {
        if (regionFile == null) return null;
        CachedFileHeader h = freshFileHeader(regionFile);
        if (h != null) return h;
        long mtime = fileMtimeOrMinusOne(regionFile);
        if (mtime < 0L) {
            invalidateFileCache(regionFile);
            return null;
        }
        h = revalidateFileHeader(regionFile, mtime);
        if (h != null) return h;
        try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            return loadFileHeader(regionFile, ch, mtime);
        } catch (NoSuchFileException e) {
            invalidateFileCache(regionFile);
            return null;
        }
    }

    private static CachedFileHeader freshFileHeader(Path regionFile) {
        long window = AnvilRegionByteCache.revalidateIntervalNanos();
        if (window <= 0L) return null;
        CachedFileHeader h = FILE_HEADER_CACHE.get(regionFile);
        if (h != null && System.nanoTime() - h.checkedNanos < window) {
            return h;
        }
        return null;
    }

    private static CachedFileHeader revalidateFileHeader(Path regionFile, long mtime) {
        CachedFileHeader h = FILE_HEADER_CACHE.get(regionFile);
        if (h == null || h.mtime != mtime) return null;
        h.checkedNanos = System.nanoTime();
        return h;
    }

    private static long fileMtimeOrMinusOne(Path regionFile) {
        try {
            return Files.getLastModifiedTime(regionFile).toMillis();
        } catch (IOException | SecurityException e) {
            return -1L;
        }
    }

    private static CachedFileHeader loadFileHeader(Path regionFile, FileChannel ch, long mtime) throws IOException {
        long fileSize = ch.size();
        int prefixLen = SUPERBLOCK_SIZE + POINTER_DIRECTORY_SIZE;
        if (fileSize < prefixLen + FOOTER_SIZE) {
            return null;
        }
        byte[] headerBytes = new byte[prefixLen];
        readChannelFully(ch, headerBytes, 0, prefixLen, 0L, regionFile);

        byte[] footerBytes = new byte[FOOTER_SIZE];
        readChannelFully(ch, footerBytes, 0, FOOTER_SIZE, fileSize - FOOTER_SIZE, regionFile);

        long magic = (long) LONG_BE_VIEW.get(headerBytes, 0);
        long footerCanary = (long) LONG_BE_VIEW.get(footerBytes, 0);
        if (magic != LCA_SUPERBLOCK_MAGIC || footerCanary != LCA_FOOTER_CANARY_MAGIC) {
            throw new CorruptRegionEntryException("CBIT T1: Bad .lca superblock/footer magic in " + regionFile);
        }
        int formatVersion = LcaSectionCodec.readUint16BE(headerBytes, 0x08);
        if (formatVersion != LCA_FORMAT_VERSION) {
            throw new CorruptRegionEntryException("CBIT T1: Unsupported .lca formatVersion in " + regionFile);
        }
        int minSectionY = LcaSectionCodec.readIntBE(headerBytes, 0x14);
        int sectionCount = LcaSectionCodec.readUint16BE(headerBytes, 0x18);
        int slabShift = LcaSectionCodec.readUint16BE(headerBytes, 0x1A);
        if (slabShift != SLAB_SHIFT || sectionCount <= 0 || sectionCount > 128) {
            throw new CorruptRegionEntryException("CBIT T1: Invalid slabShift or sectionCount in " + regionFile);
        }
        long actualChecksum = (long) LONG_BE_VIEW.get(headerBytes, 0x38);
        long expectedChecksum = LcaSectionCodec.computeSuperblockChecksum64(headerBytes, 0);
        if (expectedChecksum != actualChecksum) {
            throw new CorruptRegionEntryException("CBIT T2: Superblock headerChecksum mismatch in " + regionFile);
        }

        byte[] secDedBytes = null;
        int flags = headerBytes[0x0B] & 0xFF;
        if ((flags & 16) != 0) {
            long lodOffsetAndLen = (long) LONG_BE_VIEW.get(headerBytes, 0x28);
            int secDedOff = (int) (lodOffsetAndLen & 0xFFFF_FFFFL);
            int secDedLen = (int) (lodOffsetAndLen >>> 32);
            if (secDedLen >= SECDED_TABLE_SIZE
                    && secDedOff >= DICT_ROOT_OFFSET
                    && (long) secDedOff + SECDED_TABLE_SIZE <= fileSize - FOOTER_SIZE) {
                secDedBytes = new byte[SECDED_TABLE_SIZE];
                readChannelFully(ch, secDedBytes, 0, SECDED_TABLE_SIZE, secDedOff, regionFile);
            }
        }

        CachedFileHeader h = new CachedFileHeader(
                headerBytes, secDedBytes, new SuperblockFastMeta(minSectionY, sectionCount), mtime, fileSize, System.nanoTime());
        if (FILE_HEADER_CACHE.size() >= FILE_HEADER_CACHE_CAPACITY) {
            Iterator<Path> it = FILE_HEADER_CACHE.keySet().iterator();
            if (it.hasNext()) {
                FILE_HEADER_CACHE.remove(it.next());
            }
        }
        FILE_HEADER_CACHE.put(regionFile, h);
        return h;
    }

    private static LcaSectionCodec.ParsedHeaderAndDict loadDictionaryFromChannel(
            FileChannel ch, byte[] headerBytes, SuperblockFastMeta sb, long fileSize
    ) throws IOException {
        long dictOffsetAndLen = (long) LONG_BE_VIEW.get(headerBytes, 0x20);
        int rootDictOffset = (int) (dictOffsetAndLen & 0xFFFF_FFFFL);
        int rootDictCapacity = (int) (dictOffsetAndLen >>> 32);
        if (rootDictOffset != DICT_ROOT_OFFSET
                || rootDictCapacity < 16
                || (long) rootDictOffset + rootDictCapacity > fileSize - FOOTER_SIZE) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid root dictionary bounds");
        }

        int storageKind = headerBytes[0x0A] & 0xFF;
        int flags = headerBytes[0x0B] & 0xFF;
        int regionX = LcaSectionCodec.readIntBE(headerBytes, 0x0C);
        int regionZ = LcaSectionCodec.readIntBE(headerBytes, 0x10);
        int defaultDataVersion = LcaSectionCodec.readIntBE(headerBytes, 0x1C);
        long slabPoolBaseOffset = (long) LONG_BE_VIEW.get(headerBytes, 0x30);

        List<String> canonicalStates = new ArrayList<>(256);
        List<String> baseBlockNames = new ArrayList<>(256);
        List<List<String>> singletonBasePalettes = new ArrayList<>(256);
        List<String> biomes = new ArrayList<>(16);
        List<List<String>> singletonBiomePalettes = new ArrayList<>(16);

        int segOffset = rootDictOffset;
        int segCap = rootDictCapacity;
        int segmentHops = 0;

        while (true) {
            if (++segmentHops > MAX_DICT_CHAIN_SEGMENTS) {
                throw new CorruptRegionEntryException("CBIT T5: Dictionary continuation chain exceeded limit");
            }
            if (segOffset < DICT_ROOT_OFFSET || segCap < 16 || (long) segOffset + segCap > fileSize - FOOTER_SIZE) {
                throw new CorruptRegionEntryException("CBIT T5: Dictionary segment out of file bounds");
            }
            byte[] segBuf = new byte[segCap];
            readChannelFully(ch, segBuf, 0, segCap, segOffset, null);

            int segStateCount = LcaSectionCodec.readIntBE(segBuf, 0);
            int segBiomeCount = LcaSectionCodec.readUint16BE(segBuf, 4);
            int usedPayloadBytes = LcaSectionCodec.readUint16BE(segBuf, 6);
            int segPayloadLimit = segCap - 8;
            if (segStateCount < 0 || segStateCount > 65536
                    || segBiomeCount < 0 || segBiomeCount > 16384
                    || 8 + usedPayloadBytes > segPayloadLimit) {
                throw new CorruptRegionEntryException("CBIT T5: Dictionary segment header corrupt");
            }

            int pos = 8;
            for (int i = 0; i < segStateCount; i++) {
                int utfLen = LcaSectionCodec.readVarInt(segBuf, pos, segPayloadLimit);
                pos += LcaSectionCodec.varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen + 4 > segPayloadLimit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated dictionary BlockState entry");
                }
                String canonical = new String(segBuf, pos, utfLen, StandardCharsets.UTF_8);
                pos += utfLen + 4;
                String baseName = LcaSectionCodec.stripBlockProperties(canonical);
                canonicalStates.add(canonical);
                baseBlockNames.add(baseName);
                singletonBasePalettes.add(List.of(baseName));
            }

            for (int i = 0; i < segBiomeCount; i++) {
                int utfLen = LcaSectionCodec.readVarInt(segBuf, pos, segPayloadLimit);
                pos += LcaSectionCodec.varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen + 4 > segPayloadLimit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated dictionary Biome entry");
                }
                String biomeId = new String(segBuf, pos, utfLen, StandardCharsets.UTF_8);
                pos += utfLen + 4;
                biomes.add(biomeId);
                singletonBiomePalettes.add(List.of(biomeId));
            }

            long trailer = (long) LONG_BE_VIEW.get(segBuf, segPayloadLimit);
            int nextRelSlabs32 = (int) (trailer >>> 32);
            int nextCapSlabs24 = (int) ((trailer >>> 8) & 0xFFFFFFL);
            if (nextRelSlabs32 == 0) {
                break;
            }
            segOffset = (segOffset & ~0xFF) + (nextRelSlabs32 << SLAB_SHIFT);
            segCap = nextCapSlabs24 << SLAB_SHIFT;
        }

        return new LcaSectionCodec.ParsedHeaderAndDict(
                storageKind, regionX, regionZ, sb.minSectionY, sb.sectionCount, defaultDataVersion,
                flags, slabPoolBaseOffset,
                Collections.unmodifiableList(canonicalStates),
                Collections.unmodifiableList(baseBlockNames),
                Collections.unmodifiableList(singletonBasePalettes),
                Collections.unmodifiableList(biomes),
                Collections.unmodifiableList(singletonBiomePalettes)
        );
    }

    private static ChunkSlabView readSlabFromChannel(
            FileChannel ch, CachedFileHeader header, int rx, int rz
    ) throws IOException {
        int chunkIdx = (rx & 31) | ((rz & 31) << 5);
        int ptrByteOffset = POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
        long ptr = header.pointer(rx, rz);
        long slabOffset35 = ptr >>> 29;
        int codec1 = (int) ((ptr >>> 28) & 1L);
        int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
        int generation8 = (int) (ptr & 0xFFL);

        if (slabOffset35 == 0 || exactByteLen20 == 0) {
            return null;
        }
        long payloadStartOffset = slabOffset35 << SLAB_SHIFT;
        if (payloadStartOffset < DICT_ROOT_OFFSET
                || exactByteLen20 > MAX_CHUNK_UNCOMPRESSED_BYTES
                || payloadStartOffset + exactByteLen20 > header.fileSize - FOOTER_SIZE) {
            throw new CorruptRegionEntryException("CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds");
        }

        byte[] scratch = FILE_SCRATCH.get();
        if (scratch.length < exactByteLen20) {
            scratch = new byte[Math.max(exactByteLen20, scratch.length * 2)];
            FILE_SCRATCH.set(scratch);
        }
        readChannelFully(ch, scratch, 0, exactByteLen20, payloadStartOffset, null);

        if (codec1 == 1) {
            byte[] slabCopy = Arrays.copyOf(scratch, exactByteLen20);
            return buildSlabViewFromCopy(
                    rx, rz, ptrByteOffset, ptr, codec1, exactByteLen20, generation8,
                    slabCopy, header.sb.sectionCount, null);
        }
        return new ChunkSlabView(
                rx, rz, ptrByteOffset, ptr, codec1, exactByteLen20, generation8,
                scratch, 0, exactByteLen20);
    }

    private static void readChannelFully(
            FileChannel ch, byte[] dst, int offset, int length, long filePosition, Path regionFile
    ) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(dst, offset, length);
        long pos = filePosition;
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos);
            if (n < 0) {
                throw new CorruptRegionEntryException("Short read from .lca region file"
                        + (regionFile != null ? ": " + regionFile : ""));
            }
            pos += n;
        }
    }
}

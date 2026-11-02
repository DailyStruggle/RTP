package io.github.dailystruggle.anvil;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * LeafCraft Anvil (`r.X.Z.lca`) region-file encoder, `.mca`/`.linear` transcoder,
 * and Copy-on-Write (`CoW`) live-save slab allocator (`anvil-api-ADR-003` Sections 2.3, 3.1, 4, 5).
 *
 * <p>Manages the `256-byte` sub-slab pool via a size-indexed {@link TreeMap} free-list and a
 * dynamically shape-able shared `BlockState` and `Biome` dictionary (`nextSegmentLinkTrailer`),
 * guaranteeing tear-free concurrent reads (`CBIT T8`) and `95%+` slab reuse across incremental saves.</p>
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaRegionWriter {

    /** Default root dictionary slack arena capacity (`1,024 B` = 4 sub-slabs of `256 B`, dynamically chained via `nextSegmentLinkTrailer`). */
    public static final int DEFAULT_ROOT_DICT_CAPACITY_BYTES = 1024;

    /** Minimum continuation dictionary segment capacity (`1,024 B` = 4 sub-slabs of `256 B`). */
    public static final int MIN_CONTINUATION_DICT_CAPACITY_BYTES = 1024;

    private static final java.util.concurrent.atomic.AtomicBoolean MODE_B_FALLBACK_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private LcaRegionWriter() {}

    static boolean resolveEffectiveModeB(boolean requestedModeB) {
        if (!requestedModeB) {
            return false;
        }
        if (LcaSectionCodec.isZstdCompressionAvailable()) {
            return true;
        }
        if (MODE_B_FALLBACK_LOGGED.compareAndSet(false, true)) {
            java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName()).log(
                    java.util.logging.Level.WARNING,
                    "[LeafAnvil] Mode B (Zstd) was requested, but no Zstd runtime is available ({0}); gracefully falling back to Mode A (0-LZ).",
                    DynamicZstdBinding.pbitResult().diagnosticMessage());
        }
        return false;
    }

    static int normalizeZstdLevel(int zstdLevel) {
        return zstdLevel <= 0 ? 0 : Math.min(22, zstdLevel);
    }

    static byte[] compressZstd1(byte[] raw) throws IOException {
        return LcaSectionCodec.compressZstd1(raw);
    }

    static byte[] compressZstd(byte[] raw, int zstdLevel) throws IOException {
        return LcaSectionCodec.compressZstd(raw, zstdLevel);
    }

    /**
     * Transcodes a vanilla `.mca` region buffer into `.lca` (`Mode A: Pure 0-LZ` when
     * `modeBZstd == false`, or `Mode B: + Zstd-1` when `modeBZstd == true`).
     */
    public static byte[] transcodeFromMca(byte[] mcaBytes, int regionX, int regionZ, boolean modeBZstd) throws IOException {
        int level = modeBZstd ? 1 : 0;
        return transcodeFromReader(AnvilReader.INSTANCE, mcaBytes, regionX, regionZ, level);
    }

    /**
     * Transcodes a vanilla `.mca` region buffer into `.lca` at the specified `zstdLevel` (`0` for Mode A `0-LZ`, `1..22` for Mode B; `ADR-115`, `S-005` off-thread).
     */
    public static byte[] transcodeFromMca(byte[] mcaBytes, int regionX, int regionZ, int zstdLevel) throws IOException {
        return transcodeFromReader(AnvilReader.INSTANCE, mcaBytes, regionX, regionZ, zstdLevel);
    }

    /**
     * Transcodes all generated chunks from any {@link RegionFileReader} (`.mca`, `.linear`) into a
     * compact `.lca` region byte array.
     */
    public static byte[] transcodeFromReader(
            RegionFileReader reader, byte[] srcBytes, int regionX, int regionZ, boolean modeBZstd
    ) throws IOException {
        return transcodeFromReader(
                reader, srcBytes, regionX, regionZ, modeBZstd ? 1 : 0, LcaRegionReader.STORAGE_KIND_REGION_VOXEL);
    }

    /**
     * Transcodes all generated chunks from any {@link RegionFileReader} into `.lca` at `zstdLevel` (`ADR-115`, `S-005` off-thread).
     */
    public static byte[] transcodeFromReader(
            RegionFileReader reader, byte[] srcBytes, int regionX, int regionZ, int zstdLevel
    ) throws IOException {
        return transcodeFromReader(
                reader, srcBytes, regionX, regionZ, zstdLevel, LcaRegionReader.STORAGE_KIND_REGION_VOXEL);
    }

    /**
     * Transcodes all generated chunks from any {@link RegionFileReader} (`.mca`, `.linear`) into a
     * compact `.lca` region byte array with an explicit superblock `storageKind`
     * (`0 = REGION_VOXEL`, `1 = ENTITIES`, `2 = POI`).
     */
    public static byte[] transcodeFromReader(
            RegionFileReader reader,
            byte[] srcBytes,
            int regionX,
            int regionZ,
            boolean modeBZstd,
            int storageKind
    ) throws IOException {
        return transcodeFromReader(reader, srcBytes, regionX, regionZ, modeBZstd ? 1 : 0, storageKind);
    }

    public static byte[] transcodeFromReader(
            RegionFileReader reader,
            byte[] srcBytes,
            int regionX,
            int regionZ,
            int zstdLevel,
            int storageKind
    ) throws IOException {
        int minSectionY = -4;
        int maxSectionY = 19;
        int defaultDataVersion = 3953;
        boolean foundAny = false;

        @SuppressWarnings({"unchecked", "rawtypes"})
        LinkedHashMap<String, Object>[] roots = new LinkedHashMap[1024];

        for (int rz = 0; rz < 32; rz++) {
            for (int rx = 0; rx < 32; rx++) {
                if (!reader.isChunkGenerated(srcBytes, rx, rz)) continue;
                AnvilReader.ChunkEntry entry = reader.readChunk(srcBytes, rx, rz);
                if (entry == null || entry.root == null) continue;
                int idx = rx | (rz << 5);
                roots[idx] = entry.root;
                if (!foundAny) {
                    foundAny = true;
                    int dv = AnvilReader.getDataVersion(entry.root);
                    if (dv > 0) defaultDataVersion = dv;
                }
                Nbt.NbtList sections = AnvilReader.getSections(entry.root);
                if (sections != null) {
                    for (Object rawSec : sections.items) {
                        if (rawSec instanceof Map<?, ?> sec) {
                            Object bs = sec.get("block_states");
                            Object yObj = sec.get("Y");
                            if (bs instanceof Map<?, ?> && yObj instanceof Number yNum) {
                                int sy = yNum.intValue();
                                if (sy < minSectionY) minSectionY = sy;
                                if (sy > maxSectionY) maxSectionY = sy;
                            }
                        }
                    }
                }
            }
        }

        int sectionCount = Math.max(1, maxSectionY - minSectionY + 1);
        LcaLiveArchive archive = createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, zstdLevel,
                storageKind, DEFAULT_ROOT_DICT_CAPACITY_BYTES);
        for (int rz = 0; rz < 32; rz++) {
            for (int rx = 0; rx < 32; rx++) {
                LinkedHashMap<String, Object> root = roots[rx | (rz << 5)];
                if (root != null) {
                    archive.saveChunkInitial(rx, rz, root);
                }
            }
        }
        if (archive.dictionarySegmentCount() > 1) {
            archive.compact();
        }
        return archive.toByteArray();
    }

    /**
     * Reverse transcoder (`.lca` -> vanilla `.mca` `4 KiB` Zlib sectors), guaranteeing full
     * format reversibility for worlds, `entities/`, and `poi/` archives.
     */
    public static byte[] transcodeToMca(byte[] lcaBytes) throws IOException {
        byte[][] compressedPayloads = new byte[1024][];
        int totalSectors = 2; // First 2 sectors (8 KiB) are locations + timestamps
        for (int idx = 0; idx < 1024; idx++) {
            int rx = idx & 31;
            int rz = (idx >>> 5) & 31;
            if (!LcaRegionReader.INSTANCE.isChunkGenerated(lcaBytes, rx, rz)) {
                continue;
            }
            byte[] rawNbt = LcaRegionReader.INSTANCE.readChunkNbtBytes(lcaBytes, rx, rz);
            if (rawNbt == null) {
                continue;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(512, rawNbt.length >>> 1));
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
                dos.write(rawNbt);
            } finally {
                deflater.end();
            }
            byte[] deflated = baos.toByteArray();
            compressedPayloads[idx] = deflated;
            int bytesWithHeader = 5 + deflated.length;
            totalSectors += (bytesWithHeader + 4095) >>> 12;
        }
        if (totalSectors == 2) {
            return new byte[8192];
        }
        byte[] mca = new byte[totalSectors << 12];
        ByteBuffer header = ByteBuffer.wrap(mca);
        int currentSector = 2;
        int timestampSec = (int) (System.currentTimeMillis() / 1000L);
        for (int idx = 0; idx < 1024; idx++) {
            byte[] deflated = compressedPayloads[idx];
            if (deflated == null) continue;
            int bytesWithHeader = 5 + deflated.length;
            int sectors = (bytesWithHeader + 4095) >>> 12;
            header.putInt(idx << 2, ((currentSector & 0xFFFFFF) << 8) | (sectors & 0xFF));
            header.putInt(4096 + (idx << 2), timestampSec);
            int byteOffset = currentSector << 12;
            header.putInt(byteOffset, deflated.length + 1);
            mca[byteOffset + 4] = 2; // Zlib compression scheme
            System.arraycopy(deflated, 0, mca, byteOffset + 5, deflated.length);
            currentSector += sectors;
        }
        return mca;
    }

    /**
     * Fast direct transcoder from a `.lca` `Mode A` (`codec1 = 0`, `0-LZ`) byte array into a
     * `.lca` `Mode B` (`codec1 = 1`, `+ Zstd-1`) byte array without re-parsing NBT or re-encoding
     * voxel sections. Flattens any chained dictionary segments into the root dictionary arena,
     * sets `DEFAULT_ZSTD1` (`flags |= 8`), and wraps each chunk's body in `Zstd-1` with uncompressed
     * `ChunkFrame` header + SEC-DED parity (`anvil-api-ADR-003` Section 7.1 `CBIT T3c`).
     */
    public static byte[] convertModeAToModeB(byte[] modeABytes) throws IOException {
        return convertModeAToModeB(modeABytes, 1);
    }

    public static byte[] convertModeAToModeB(byte[] modeABytes, int zstdLevel) throws IOException {
        int effectiveLevel = Math.max(1, Math.min(22, zstdLevel));
        LcaLiveArchive src = openLiveArchive(modeABytes);
        int secCountVal = LcaSectionCodec.readUint16BE(modeABytes, 0x18);
        int origRootDictCap = (int) ((long) LcaRegionReader.LONG_BE_VIEW.get(modeABytes, 0x20) >>> 32);
        long lodOffsetAndLen = (long) LcaRegionReader.LONG_BE_VIEW.get(modeABytes, 0x28);
        int lodLen = (int) (lodOffsetAndLen >>> 32);
        int secDedTableOff = (lodLen >= LcaRegionReader.SECDED_TABLE_SIZE)
                ? (int) (lodOffsetAndLen & 0xFFFF_FFFFL)
                : ((LcaRegionReader.DICT_ROOT_OFFSET + origRootDictCap + 255) & ~255);
        int slabPoolBaseOffset = (int) ((long) LcaRegionReader.LONG_BE_VIEW.get(modeABytes, 0x30));
        int slabPoolBaseSlabVal = slabPoolBaseOffset >>> LcaRegionReader.SLAB_SHIFT;
        int headerLen = 24 + (secCountVal << 1);

        int segCount;
        int[] oldSegOffsets;
        int[] dstSegOffsets;
        int[] segCaps;
        int[] newSegBaseSlabs;
        int cursorSlab = slabPoolBaseSlabVal;
        synchronized (src) {
            segCount = src.dictSegments.size();
            oldSegOffsets = new int[segCount];
            dstSegOffsets = new int[segCount];
            segCaps = new int[segCount];
            newSegBaseSlabs = new int[segCount];
            for (int s = 0; s < segCount; s++) {
                LcaLiveArchive.DictSegmentMeta seg = src.dictSegments.get(s);
                oldSegOffsets[s] = seg.byteOffset();
                segCaps[s] = seg.capacityBytes();
                if (s == 0) {
                    newSegBaseSlabs[0] = seg.baseSlab();
                    dstSegOffsets[0] = seg.byteOffset();
                } else {
                    newSegBaseSlabs[s] = cursorSlab;
                    dstSegOffsets[s] = cursorSlab << LcaRegionReader.SLAB_SHIFT;
                    cursorSlab += (seg.capacityBytes() + 255) >>> LcaRegionReader.SLAB_SHIFT;
                }
            }
        }

        byte[][] modeBFrames = new byte[1024][];
        int[] gens = new int[1024];
        int currentSlab = cursorSlab;
        for (int i = 0; i < 1024; i++) {
            int ptrPos = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3);
            long ptr = (long) LcaRegionReader.LONG_BE_VIEW.get(modeABytes, ptrPos);
            if (ptr == 0L) {
                continue;
            }
            int slabStart = (int) (ptr >>> 29);
            int codec1 = (int) ((ptr >>> 28) & 1L);
            int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
            int gen8 = (int) (ptr & 0xFFL);
            if (slabStart <= 0 || byteLen <= 0) {
                continue;
            }
            int byteOff = slabStart << LcaRegionReader.SLAB_SHIFT;
            byte[] slice = Arrays.copyOfRange(modeABytes, byteOff, byteOff + byteLen);
            byte[] encoded = (codec1 == 1)
                    ? slice
                    : LcaSectionCodec.encodeModeBChunkFrameWithUncompressedHeader(slice, headerLen, effectiveLevel);
            modeBFrames[i] = encoded;
            gens[i] = gen8;
            currentSlab += (encoded.length + 255) >>> LcaRegionReader.SLAB_SHIFT;
        }

        int totalBytes = (currentSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE;
        byte[] out = new byte[totalBytes];
        System.arraycopy(modeABytes, 0, out, 0, LcaRegionReader.SUPERBLOCK_SIZE);
        out[0x0B] = (byte) ((out[0x0B] & 0xFF) | 8); // DEFAULT_ZSTD1
        LcaRegionReader.setVolatileLongBE(out, 0x38, LcaSectionCodec.computeSuperblockChecksum64(out, 0));

        // Copy root dictionary and any continuation dictionary segments with compacted link trailers
        for (int s = 0; s < segCount; s++) {
            int dstOff = dstSegOffsets[s];
            int cap = segCaps[s];
            System.arraycopy(modeABytes, oldSegOffsets[s], out, dstOff, cap);
            long linkTrailer = 0L;
            if (s + 1 < segCount) {
                int relSlabs32 = newSegBaseSlabs[s + 1] - newSegBaseSlabs[s];
                int contSlabs = segCaps[s + 1] >>> LcaRegionReader.SLAB_SHIFT;
                linkTrailer = ((long) relSlabs32 << 32)
                        | ((contSlabs & 0xFFFFFFL) << 8)
                        | 1L;
            }
            LcaRegionReader.setVolatileLongBE(out, dstOff + cap - 8, linkTrailer);
        }

        int writeSlab = cursorSlab;
        for (int i = 0; i < 1024; i++) {
            int ptrPos = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3);
            byte[] frame = modeBFrames[i];
            if (frame == null) {
                LcaRegionReader.setVolatileLongBE(out, ptrPos, 0L);
                out[secDedTableOff + i] = (byte) LcaSectionCodec.computeHammingSecDed8(0L);
                continue;
            }
            int dstOff = writeSlab << LcaRegionReader.SLAB_SHIFT;
            System.arraycopy(frame, 0, out, dstOff, frame.length);
            long newPtr = packChunkPointer64(writeSlab, true, frame.length, gens[i]);
            LcaRegionReader.setVolatileLongBE(out, ptrPos, newPtr);
            out[secDedTableOff + i] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
            writeSlab += (frame.length + 255) >>> LcaRegionReader.SLAB_SHIFT;
        }
        LcaRegionReader.setVolatileLongBE(out, writeSlab << LcaRegionReader.SLAB_SHIFT, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
        return out;
    }

    /**
     * Creates a new empty mutable `.lca` live archive with a pre-allocated root dictionary slack
     * arena (`1,024 B`) and `TreeMap` `256B` sub-slab allocator.
     */
    public static LcaLiveArchive createEmptyLiveArchive(
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBZstd
    ) {
        return createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, modeBZstd,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL, DEFAULT_ROOT_DICT_CAPACITY_BYTES);
    }

    /**
     * Creates a new empty mutable `.lca` live archive with a configurable root dictionary capacity
     * (must be a multiple of `256 B`, minimum `256 B`).
     */
    public static LcaLiveArchive createEmptyLiveArchive(
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBZstd,
            int rootDictCapacityBytes
    ) {
        return createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, modeBZstd,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL, rootDictCapacityBytes);
    }

    /**
     * Creates a new empty mutable `.lca` live archive with explicit `storageKind`
     * (`0 = REGION_VOXEL`, `1 = ENTITIES`, `2 = POI`) and root dictionary slack capacity.
     */
    public static LcaLiveArchive createEmptyLiveArchive(
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBZstd,
            int storageKind,
            int rootDictCapacityBytes
    ) {
        return createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                modeBZstd ? 1 : 0, storageKind, rootDictCapacityBytes);
    }

    public static LcaLiveArchive createEmptyLiveArchive(
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int zstdLevel,
            int storageKind,
            int rootDictCapacityBytes
    ) {
        int alignedDictCap = Math.max(256, (rootDictCapacityBytes + 255) & ~255);
        return new LcaLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, zstdLevel, storageKind, alignedDictCap);
    }

    /**
     * Creates a new disk-backed `.lca` live archive at `lcaFile`, immediately writing the
     * superblock, pointer directory, root dictionary, SEC-DED table, and footer canary via
     * positional {@link FileChannel} I/O.
     */
    public static LcaLiveArchive createEmptyLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBZstd
    ) throws IOException {
        return createEmptyLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, modeBZstd ? 1 : 0,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL, DEFAULT_ROOT_DICT_CAPACITY_BYTES);
    }

    public static LcaLiveArchive createEmptyLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBZstd,
            int storageKind,
            int rootDictCapacityBytes
    ) throws IOException {
        return createEmptyLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                modeBZstd ? 1 : 0, storageKind, rootDictCapacityBytes);
    }

    public static LcaLiveArchive createEmptyLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int zstdLevel,
            int storageKind,
            int rootDictCapacityBytes
    ) throws IOException {
        LcaLiveArchive archive = createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, zstdLevel,
                storageKind, rootDictCapacityBytes);
        if (lcaFile != null) {
            archive.bindBackingFile(lcaFile, true);
        }
        return archive;
    }

    /**
     * Opens an existing `.lca` byte array as a mutable live archive, scanning the `1,024` pointer
     * directory words and chained dictionary segments in {@code < 15 us} to populate the `TreeMap` `256B`
     * free-slab allocator (`anvil-api-ADR-003` Section 2.3).
     */
    public static LcaLiveArchive openLiveArchive(byte[] existingLcaBytes) throws CorruptRegionEntryException {
        return new LcaLiveArchive(existingLcaBytes);
    }

    /**
     * Opens an existing `.lca` region file on disk as a mutable live archive backed by an active
     * {@link FileChannel} for incremental positional `256B` Copy-on-Write (`CoW`) slab writes.
     *
     * <p>Reads only the `~25.6 KiB` region header (`Superblock`, `Pointer Directory`, `Shared Dictionary`,
     * and `SEC-DED` table) plus the 8-byte footer canary into heap memory, serving chunk reads and
     * CoW slab writes positionally over the open {@link FileChannel} without retaining the multi-megabyte
     * slab pool in heap.</p>
     */
    public static LcaLiveArchive openLiveArchive(Path lcaFile) throws IOException {
        return openLiveArchive(lcaFile, -1);
    }

    public static LcaLiveArchive openLiveArchive(Path lcaFile, int preferredZstdLevel) throws IOException {
        FileChannel ch = FileChannel.open(lcaFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
        boolean ok = false;
        try {
            long size = ch.size();
            if (size < LcaRegionReader.SUPERBLOCK_SIZE
                    + LcaRegionReader.POINTER_DIRECTORY_SIZE
                    + LcaRegionReader.FOOTER_SIZE
                    || size > Integer.MAX_VALUE - 262144) {
                throw new CorruptRegionEntryException("Invalid .lca file size: " + size + " in " + lcaFile);
            }
            int validLen = (int) size;
            LcaLiveArchive archive = new LcaLiveArchive(lcaFile, ch, validLen, preferredZstdLevel);
            ok = true;
            return archive;
        } finally {
            if (!ok) {
                ch.close();
            }
        }
    }

    /**
     * Opens an existing `.lca` file at `lcaFile` if present and non-empty, or creates and binds
     * a new disk-backed `.lca` archive at `lcaFile`.
     */
    public static LcaLiveArchive openOrCreateLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBZstd,
            int storageKind
    ) throws IOException {
        return openOrCreateLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                modeBZstd ? 1 : 0, storageKind);
    }

    public static LcaLiveArchive openOrCreateLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int zstdLevel,
            int storageKind
    ) throws IOException {
        if (Files.exists(lcaFile) && Files.size(lcaFile) >= LcaRegionReader.SUPERBLOCK_SIZE
                + LcaRegionReader.POINTER_DIRECTORY_SIZE + LcaRegionReader.FOOTER_SIZE) {
            return openLiveArchive(lcaFile, zstdLevel);
        }
        return createEmptyLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, zstdLevel,
                storageKind, DEFAULT_ROOT_DICT_CAPACITY_BYTES);
    }

    /**
     * Live mutable `.lca` region archive supporting Copy-on-Write (`CoW`) `256B` sub-slab
     * rotation (`Section 2.3`), dynamically chained dictionary continuation segments (`Section 3.1`),
     * and concurrent lock-free reader access (`CBIT T8`).
     */
    public static final class LcaLiveArchive implements Closeable, AutoCloseable {
        private final int regionX;
        private final int regionZ;
        private final int minSectionY;
        private final int sectionCount;
        private final int defaultDataVersion;
        private final boolean modeBZstd;
        private final boolean writeModeBZstd;
        private final int zstdLevel;
        private final int storageKind;
        private final int rootDictCapacityBytes;
        private final int secDedTableOffset;
        private int slabPoolBaseSlab;

        private volatile byte[] buffer;
        private volatile int activeEndSlab; // Exclusive end slab index before the 8-byte footer canary

        private Path backingFile;
        private FileChannel channel;
        private volatile boolean headerOnlyChannelMode = false;
        private volatile boolean dictModifiedSinceSync = false;
        private volatile LcaSectionCodec.ParsedHeaderAndDict cachedParsedDict;
        private int lastSyncedFooterOffset = -1;
        private long diskChannelWriteCount = 0;
        private long diskChannelBytesWritten = 0;
        private volatile boolean dirty = false;

        private final Map<String, Integer> blockStateToId = new ConcurrentHashMap<>();
        private final List<String> idToBlockState = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Integer> biomeToId = new ConcurrentHashMap<>();
        private final List<String> idToBiome = Collections.synchronizedList(new ArrayList<>());

        // Per-chunk cached residual NBT bytes keyed by Heightmaps identity to avoid redundant NBT serialization on tick saves
        private final Object[] cachedHeightmapRef = new Object[1024];
        private final byte[][] cachedResidualBytes = new byte[1024][];
        // Per-chunk previous CoW pointer table for instant O(1) rollback if the latest slab is corrupted
        private final long[] prevChunkPointers = new long[1024];

        private static final class DictSegmentMeta {
            int byteOffset;
            final int capacityBytes;
            final List<byte[]> stateEntries = new ArrayList<>();
            final List<byte[]> biomeEntries = new ArrayList<>();
            int usedPayloadBytes = 0;
            long linkTrailer = 0L;

            DictSegmentMeta(int byteOffset, int capacityBytes) {
                this.byteOffset = byteOffset;
                this.capacityBytes = capacityBytes;
            }

            int baseSlab() {
                return byteOffset >>> LcaRegionReader.SLAB_SHIFT;
            }

            int byteOffset() {
                return byteOffset;
            }

            int capacityBytes() {
                return capacityBytes;
            }

            int availableSlackBytes() {
                return (capacityBytes - 16) - usedPayloadBytes;
            }
        }

        private final List<DictSegmentMeta> dictSegments = new ArrayList<>();

        private final TreeMap<Integer, Integer> freeByStart = new TreeMap<>();
        private final TreeMap<Integer, TreeSet<Integer>> freeBySize = new TreeMap<>();

        // 4-epoch CoW grace ring + Epoch-Based Reclamation (EBR) retirement gate (anvil-api-ADR-003 Section 2.3 & T8)
        private static final int COW_GRACE_EPOCHS = 4;
        private final int[] graceSlabStarts = new int[COW_GRACE_EPOCHS];
        private final int[] graceSlabCounts = new int[COW_GRACE_EPOCHS];
        private final long[] graceRetireEpochs = new long[COW_GRACE_EPOCHS];
        private int graceCursor = 0;
        private final List<long[]> deferredRetireSlabs = new ArrayList<>();

        private long totalSaves = 0;
        private long reusedSlabSaves = 0;
        private long eofAppendSaves = 0;
        private long liveSecDedHealedPointers = 0;
        private long liveChunkHeaderHealedWords = 0;
        private long liveSlabScanReconstructions = 0;
        private long liveCoWRollbacks = 0;
        private long crashFooterRepairs = 0;
        private final List<String> selfHealingDiagnostics = Collections.synchronizedList(new ArrayList<>());

        LcaLiveArchive(
                int regionX,
                int regionZ,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                boolean modeBZstd,
                int storageKind,
                int rootDictCapacityBytes
        ) {
            this(regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                    modeBZstd ? 1 : 0, storageKind, rootDictCapacityBytes);
        }

        LcaLiveArchive(
                int regionX,
                int regionZ,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                int zstdLevel,
                int storageKind,
                int rootDictCapacityBytes
        ) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.minSectionY = minSectionY;
            this.sectionCount = sectionCount;
            this.defaultDataVersion = defaultDataVersion;
            int normLevel = normalizeZstdLevel(zstdLevel);
            this.modeBZstd = resolveEffectiveModeB(normLevel > 0);
            this.writeModeBZstd = this.modeBZstd;
            this.zstdLevel = this.writeModeBZstd ? normLevel : 0;
            this.storageKind = storageKind & 0xFF;
            this.rootDictCapacityBytes = rootDictCapacityBytes;
            this.secDedTableOffset = (LcaRegionReader.DICT_ROOT_OFFSET + rootDictCapacityBytes + 255) & ~255;

            int slabPoolBaseOffset = this.secDedTableOffset + LcaRegionReader.SECDED_TABLE_SIZE;
            this.slabPoolBaseSlab = slabPoolBaseOffset >>> LcaRegionReader.SLAB_SHIFT;
            this.activeEndSlab = this.slabPoolBaseSlab;

            this.buffer = new byte[slabPoolBaseOffset + LcaRegionReader.FOOTER_SIZE + 65536];
            DictSegmentMeta rootSeg = new DictSegmentMeta(
                    LcaRegionReader.DICT_ROOT_OFFSET,
                    rootDictCapacityBytes);
            this.dictSegments.add(rootSeg);

            registerOrGetBlockStateId("minecraft:air");
            registerOrGetBiomeId("minecraft:plains");
            writeSuperblockAndFooter();
            this.dirty = true;
        }

        LcaLiveArchive(byte[] existingBytes) throws CorruptRegionEntryException {
            this(existingBytes, existingBytes.length);
        }

        LcaLiveArchive(byte[] existingBytes, int validByteLength) throws CorruptRegionEntryException {
            LcaSectionCodec.ParsedHeaderAndDict parsed =
                    LcaRegionReader.INSTANCE.parseAndValidateSuperblockAndDict(existingBytes, validByteLength);
            this.regionX = parsed.regionX;
            this.regionZ = parsed.regionZ;
            this.minSectionY = parsed.minSectionY;
            this.sectionCount = parsed.sectionCount;
            this.defaultDataVersion = parsed.defaultDataVersion;
            this.modeBZstd = (parsed.flags & 8) != 0;
            this.writeModeBZstd = resolveEffectiveModeB(this.modeBZstd);
            this.zstdLevel = this.writeModeBZstd ? 1 : 0;
            this.storageKind = parsed.storageKind;

            long dictOffsetAndLen = (long) LcaRegionReader.LONG_BE_VIEW.get(existingBytes, 0x20);
            this.rootDictCapacityBytes = (int) (dictOffsetAndLen >>> 32);
            long lodOffsetAndLen = (long) LcaRegionReader.LONG_BE_VIEW.get(existingBytes, 0x28);
            int lodLen = (int) (lodOffsetAndLen >>> 32);
            this.secDedTableOffset = (lodLen >= LcaRegionReader.SECDED_TABLE_SIZE)
                    ? (int) (lodOffsetAndLen & 0xFFFF_FFFFL)
                    : ((LcaRegionReader.DICT_ROOT_OFFSET + rootDictCapacityBytes + 255) & ~255);
            this.slabPoolBaseSlab = (int) (parsed.slabPoolBaseOffset >>> LcaRegionReader.SLAB_SHIFT);
            int payloadBytesNoFooter = validByteLength - LcaRegionReader.FOOTER_SIZE;
            this.activeEndSlab = Math.max(this.slabPoolBaseSlab,
                    (payloadBytesNoFooter + LcaRegionReader.SLAB_BYTES - 1) >>> LcaRegionReader.SLAB_SHIFT);

            int minRequiredCap = (activeEndSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE;
            this.buffer = existingBytes.length >= minRequiredCap
                    ? existingBytes
                    : Arrays.copyOf(existingBytes, minRequiredCap + 131072);

            List<int[]> allocatedIntervals = new ArrayList<>(1032);
            int segOffset = LcaRegionReader.DICT_ROOT_OFFSET;
            int segCap = this.rootDictCapacityBytes;
            while (true) {
                int startSlab = segOffset >>> LcaRegionReader.SLAB_SHIFT;
                int capSlabs = (segCap + 255) >>> LcaRegionReader.SLAB_SHIFT;
                DictSegmentMeta segMeta = new DictSegmentMeta(segOffset, segCap);
                if (segOffset >= (this.slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT)) {
                    allocatedIntervals.add(new int[] {startSlab, startSlab + capSlabs});
                }

                int segStateCount = LcaSectionCodec.readIntBE(existingBytes, segOffset);
                int segBiomeCount = LcaSectionCodec.readUint16BE(existingBytes, segOffset + 4);
                int segPayloadLimit = segOffset + segCap - 8;
                int pos = segOffset + 8;

                for (int i = 0; i < segStateCount; i++) {
                    int entryStart = pos;
                    int utfLen = LcaSectionCodec.readVarInt(existingBytes, pos, segPayloadLimit);
                    pos += LcaSectionCodec.varIntLen(utfLen);
                    String canonical = new String(existingBytes, pos, utfLen, StandardCharsets.UTF_8);
                    pos += utfLen + 4;
                    byte[] rawEntry = Arrays.copyOfRange(existingBytes, entryStart, pos);
                    segMeta.stateEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    blockStateToId.put(canonical, idToBlockState.size());
                    idToBlockState.add(canonical);
                }

                for (int i = 0; i < segBiomeCount; i++) {
                    int entryStart = pos;
                    int utfLen = LcaSectionCodec.readVarInt(existingBytes, pos, segPayloadLimit);
                    pos += LcaSectionCodec.varIntLen(utfLen);
                    String biome = new String(existingBytes, pos, utfLen, StandardCharsets.UTF_8);
                    pos += utfLen + 4;
                    byte[] rawEntry = Arrays.copyOfRange(existingBytes, entryStart, pos);
                    segMeta.biomeEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    biomeToId.put(biome, idToBiome.size());
                    idToBiome.add(biome);
                }

                this.dictSegments.add(segMeta);
                long trailer = (long) LcaRegionReader.LONG_BE_VIEW.get(existingBytes, segPayloadLimit);
                segMeta.linkTrailer = trailer;
                int nextRelSlabs32 = (int) (trailer >>> 32);
                int nextCapSlabs24 = (int) ((trailer >>> 8) & 0xFFFFFFL);
                if (nextRelSlabs32 == 0) break;
                segOffset = (segOffset & ~0xFF) + (nextRelSlabs32 << LcaRegionReader.SLAB_SHIFT);
                segCap = nextCapSlabs24 << LcaRegionReader.SLAB_SHIFT;
            }

            // Scan 1,024 chunk pointers in < 15 us to populate TreeMap free-slab pool (Section 2.3)
            for (int i = 0; i < 1024; i++) {
                long ptr = (long) LcaRegionReader.LONG_BE_VIEW.get(
                        existingBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3));
                int slabStart = (int) (ptr >>> 29);
                int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                if (slabStart > 0 && byteLen > 0) {
                    int slabs = (byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT;
                    allocatedIntervals.add(new int[] {slabStart, slabStart + slabs});
                }
            }
            allocatedIntervals.sort((a, b) -> Integer.compare(a[0], b[0]));
            int cursorSlab = this.slabPoolBaseSlab;
            for (int[] iv : allocatedIntervals) {
                if (iv[0] > cursorSlab) {
                    returnFreeSlabs(cursorSlab, iv[0] - cursorSlab);
                }
                if (iv[1] > cursorSlab) {
                    cursorSlab = iv[1];
                }
            }
            this.activeEndSlab = Math.max(this.slabPoolBaseSlab, cursorSlab);
            writeFooterCanary();
        }

        LcaLiveArchive(Path lcaFile, FileChannel ch, int validByteLength) throws IOException {
            this(lcaFile, ch, validByteLength, -1);
        }

        LcaLiveArchive(Path lcaFile, FileChannel ch, int validByteLength, int preferredZstdLevel) throws IOException {
            int initRead = Math.min(validByteLength, 32768);
            byte[] initBuf = new byte[initRead];
            readChannelRangeStatic(ch, 0L, initBuf, 0, initRead, lcaFile);

            long magic = (long) LcaRegionReader.LONG_BE_VIEW.get(initBuf, 0);
            if (magic != LcaRegionReader.LCA_SUPERBLOCK_MAGIC) {
                throw new CorruptRegionEntryException("CBIT T1: Bad .lca superblock magic in " + lcaFile);
            }
            byte[] footerBuf = new byte[LcaRegionReader.FOOTER_SIZE];
            if (validByteLength <= initRead) {
                System.arraycopy(initBuf, validByteLength - LcaRegionReader.FOOTER_SIZE, footerBuf, 0, LcaRegionReader.FOOTER_SIZE);
            } else {
                readChannelRangeStatic(ch, validByteLength - LcaRegionReader.FOOTER_SIZE, footerBuf, 0, LcaRegionReader.FOOTER_SIZE, lcaFile);
            }
            long footerCanary = (long) LcaRegionReader.LONG_BE_VIEW.get(footerBuf, 0);
            boolean crashFooterRecovered = (footerCanary != LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
            int formatVersion = LcaSectionCodec.readUint16BE(initBuf, 0x08);
            if (formatVersion != LcaRegionReader.LCA_FORMAT_VERSION) {
                throw new CorruptRegionEntryException("CBIT T1: Unsupported .lca formatVersion in " + lcaFile);
            }
            this.storageKind = initBuf[0x0A] & 0xFF;
            int flags = initBuf[0x0B] & 0xFF;
            boolean fileFlagModeB = (flags & 8) != 0;
            int normPref = preferredZstdLevel < 0
                    ? (fileFlagModeB ? 1 : 0)
                    : normalizeZstdLevel(preferredZstdLevel);
            this.modeBZstd = fileFlagModeB;
            this.writeModeBZstd = resolveEffectiveModeB(normPref > 0);
            this.zstdLevel = this.writeModeBZstd ? normPref : 0;
            this.regionX = LcaSectionCodec.readIntBE(initBuf, 0x0C);
            this.regionZ = LcaSectionCodec.readIntBE(initBuf, 0x10);
            this.minSectionY = LcaSectionCodec.readIntBE(initBuf, 0x14);
            this.sectionCount = LcaSectionCodec.readUint16BE(initBuf, 0x18);
            int slabShift = LcaSectionCodec.readUint16BE(initBuf, 0x1A);
            if (slabShift != LcaRegionReader.SLAB_SHIFT || sectionCount <= 0 || sectionCount > 128) {
                throw new CorruptRegionEntryException("CBIT T1: Invalid slabShift or sectionCount in " + lcaFile);
            }
            this.defaultDataVersion = LcaSectionCodec.readIntBE(initBuf, 0x1C);
            long dictOffsetAndLen = (long) LcaRegionReader.LONG_BE_VIEW.get(initBuf, 0x20);
            int rootDictOffset = (int) (dictOffsetAndLen & 0xFFFF_FFFFL);
            this.rootDictCapacityBytes = (int) (dictOffsetAndLen >>> 32);
            long lodOffsetAndLen = (long) LcaRegionReader.LONG_BE_VIEW.get(initBuf, 0x28);
            int lodLen = (int) (lodOffsetAndLen >>> 32);
            this.secDedTableOffset = (lodLen >= LcaRegionReader.SECDED_TABLE_SIZE)
                    ? (int) (lodOffsetAndLen & 0xFFFF_FFFFL)
                    : ((LcaRegionReader.DICT_ROOT_OFFSET + rootDictCapacityBytes + 255) & ~255);
            long slabPoolBaseOffset = (long) LcaRegionReader.LONG_BE_VIEW.get(initBuf, 0x30);
            long actualChecksum = (long) LcaRegionReader.LONG_BE_VIEW.get(initBuf, 0x38);
            long expectedChecksum = LcaSectionCodec.computeSuperblockChecksum64(initBuf, 0);
            if (expectedChecksum != actualChecksum) {
                throw new CorruptRegionEntryException("CBIT T2: Superblock headerChecksum mismatch in " + lcaFile);
            }
            int effectivePayloadLimit = crashFooterRecovered
                    ? validByteLength
                    : (validByteLength - LcaRegionReader.FOOTER_SIZE);
            if (rootDictOffset != LcaRegionReader.DICT_ROOT_OFFSET
                    || rootDictCapacityBytes < 16
                    || slabPoolBaseOffset < (long) secDedTableOffset + LcaRegionReader.SECDED_TABLE_SIZE
                    || slabPoolBaseOffset > effectivePayloadLimit) {
                throw new CorruptRegionEntryException("CBIT T5: Invalid root dictionary / slabPoolBaseOffset in " + lcaFile);
            }
            this.slabPoolBaseSlab = (int) (slabPoolBaseOffset >>> LcaRegionReader.SLAB_SHIFT);
            int headerPrefixBytes = (int) slabPoolBaseOffset;
            if (headerPrefixBytes <= initRead) {
                this.buffer = Arrays.copyOf(initBuf, headerPrefixBytes);
            } else {
                this.buffer = new byte[headerPrefixBytes];
                System.arraycopy(initBuf, 0, this.buffer, 0, initRead);
                readChannelRangeStatic(ch, initRead, this.buffer, initRead, headerPrefixBytes - initRead, lcaFile);
            }

            List<int[]> allocatedIntervals = new ArrayList<>(1032);
            int segOffset = LcaRegionReader.DICT_ROOT_OFFSET;
            int segCap = this.rootDictCapacityBytes;
            int segmentHops = 0;
            while (true) {
                if (++segmentHops > LcaRegionReader.MAX_DICT_CHAIN_SEGMENTS) {
                    throw new CorruptRegionEntryException("CBIT T5: Dictionary continuation chain exceeded limit in " + lcaFile);
                }
                if (segOffset < LcaRegionReader.DICT_ROOT_OFFSET
                        || segCap < 16
                        || (long) segOffset + segCap > effectivePayloadLimit) {
                    throw new CorruptRegionEntryException("CBIT T5: Dictionary segment out of bounds in " + lcaFile);
                }
                int startSlab = segOffset >>> LcaRegionReader.SLAB_SHIFT;
                int capSlabs = (segCap + 255) >>> LcaRegionReader.SLAB_SHIFT;
                DictSegmentMeta segMeta = new DictSegmentMeta(segOffset, segCap);
                if (segOffset >= headerPrefixBytes) {
                    allocatedIntervals.add(new int[] {startSlab, startSlab + capSlabs});
                }

                byte[] segBuf;
                int segBaseOff;
                if (segOffset + segCap <= this.buffer.length) {
                    segBuf = this.buffer;
                    segBaseOff = segOffset;
                } else {
                    segBuf = new byte[segCap];
                    readChannelRangeStatic(ch, segOffset, segBuf, 0, segCap, lcaFile);
                    segBaseOff = 0;
                }

                int segStateCount = LcaSectionCodec.readIntBE(segBuf, segBaseOff);
                int segBiomeCount = LcaSectionCodec.readUint16BE(segBuf, segBaseOff + 4);
                int segPayloadLimit = segBaseOff + segCap - 8;
                int pos = segBaseOff + 8;

                for (int i = 0; i < segStateCount; i++) {
                    int entryStart = pos;
                    int utfLen = LcaSectionCodec.readVarInt(segBuf, pos, segPayloadLimit);
                    pos += LcaSectionCodec.varIntLen(utfLen);
                    String canonical = new String(segBuf, pos, utfLen, StandardCharsets.UTF_8);
                    pos += utfLen + 4;
                    byte[] rawEntry = Arrays.copyOfRange(segBuf, entryStart, pos);
                    segMeta.stateEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    blockStateToId.put(canonical, idToBlockState.size());
                    idToBlockState.add(canonical);
                }

                for (int i = 0; i < segBiomeCount; i++) {
                    int entryStart = pos;
                    int utfLen = LcaSectionCodec.readVarInt(segBuf, pos, segPayloadLimit);
                    pos += LcaSectionCodec.varIntLen(utfLen);
                    String biome = new String(segBuf, pos, utfLen, StandardCharsets.UTF_8);
                    pos += utfLen + 4;
                    byte[] rawEntry = Arrays.copyOfRange(segBuf, entryStart, pos);
                    segMeta.biomeEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    biomeToId.put(biome, idToBiome.size());
                    idToBiome.add(biome);
                }

                this.dictSegments.add(segMeta);
                long trailer = (long) LcaRegionReader.LONG_BE_VIEW.get(segBuf, segPayloadLimit);
                segMeta.linkTrailer = trailer;
                int nextRelSlabs32 = (int) (trailer >>> 32);
                int nextCapSlabs24 = (int) ((trailer >>> 8) & 0xFFFFFFL);
                if (nextRelSlabs32 == 0) break;
                segOffset = (segOffset & ~0xFF) + (nextRelSlabs32 << LcaRegionReader.SLAB_SHIFT);
                segCap = nextCapSlabs24 << LcaRegionReader.SLAB_SHIFT;
            }

            this.backingFile = lcaFile;
            this.channel = ch;
            this.headerOnlyChannelMode = true;

            boolean hasSecDed = (flags & 16) != 0;
            boolean[] needsOpenScan = null;
            for (int i = 0; i < 1024; i++) {
                int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3);
                long ptr = (long) LcaRegionReader.LONG_BE_VIEW.get(this.buffer, ptrByteOffset);
                int syn8 = hasSecDed ? (this.buffer[this.secDedTableOffset + i] & 0xFF) : 0;
                if (hasSecDed && (ptr != 0L || syn8 != 0)) {
                    int expectedSyn = LcaSectionCodec.computeHammingSecDed8(ptr);
                    if (expectedSyn != syn8) {
                        if (ptr != 0L && isSlabValidForChunkOnChannel(ch, ptr, i, effectivePayloadLimit)) {
                            // Torn crash write where SEC-DED byte updated before 8-byte pointer (or 1-bit flip in syn8)
                            this.buffer[this.secDedTableOffset + i] = (byte) expectedSyn;
                            writeChannelRange(this.secDedTableOffset + i, this.buffer, this.secDedTableOffset + i, 1);
                            this.dirty = true;
                            this.selfHealingDiagnostics.add("Reconciled torn SEC-DED syndrome byte on open for chunkIdx=" + i);
                        } else {
                            long healed = LcaSectionCodec.healSingleBitFlip64(ptr, syn8);
                            if (healed != Long.MIN_VALUE && (healed == 0L || isSlabValidForChunkOnChannel(ch, healed, i, effectivePayloadLimit))) {
                                ptr = healed;
                                LcaRegionReader.setVolatileLongBE(this.buffer, ptrByteOffset, ptr);
                                this.buffer[this.secDedTableOffset + i] = (byte) LcaSectionCodec.computeHammingSecDed8(ptr);
                                persistPointerAndSecDed(i);
                                this.dirty = true;
                                this.liveSecDedHealedPointers++;
                                this.selfHealingDiagnostics.add("Tier-1 SEC-DED healed 1-bit pointer flip on open for chunkIdx=" + i);
                            } else {
                                if (needsOpenScan == null) needsOpenScan = new boolean[1024];
                                needsOpenScan[i] = true;
                                continue;
                            }
                        }
                    }
                }
                if (ptr != 0L) {
                    long slabStartLong = ptr >>> 29;
                    int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                    long byteEnd = (slabStartLong << LcaRegionReader.SLAB_SHIFT) + byteLen;
                    if (slabStartLong < this.slabPoolBaseSlab
                            || slabStartLong > Integer.MAX_VALUE
                            || byteLen < 24
                            || byteEnd > effectivePayloadLimit
                            || !isSlabValidForChunkOnChannel(ch, ptr, i, effectivePayloadLimit)) {
                        if (needsOpenScan == null) needsOpenScan = new boolean[1024];
                        needsOpenScan[i] = true;
                        continue;
                    }
                    int slabStart = (int) slabStartLong;
                    int slabs = (byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT;
                    allocatedIntervals.add(new int[] {slabStart, slabStart + slabs});
                }
            }

            if (needsOpenScan != null) {
                reconstructCorruptPointersOnOpen(ch, needsOpenScan, effectivePayloadLimit, allocatedIntervals);
            }

            allocatedIntervals.sort((a, b) -> Integer.compare(a[0], b[0]));
            int cursorSlab = this.slabPoolBaseSlab;
            for (int[] iv : allocatedIntervals) {
                if (iv[0] > cursorSlab) {
                    returnFreeSlabs(cursorSlab, iv[0] - cursorSlab);
                }
                if (iv[1] > cursorSlab) {
                    cursorSlab = iv[1];
                }
            }
            if (!crashFooterRecovered) {
                int fileEndSlab = (validByteLength - LcaRegionReader.FOOTER_SIZE) >>> LcaRegionReader.SLAB_SHIFT;
                if (fileEndSlab > cursorSlab) {
                    returnFreeSlabs(cursorSlab, fileEndSlab - cursorSlab);
                    cursorSlab = fileEndSlab;
                }
                this.activeEndSlab = Math.max(this.slabPoolBaseSlab, cursorSlab);
                this.lastSyncedFooterOffset = validByteLength - LcaRegionReader.FOOTER_SIZE;
                writeFooterCanary();
            } else {
                this.activeEndSlab = Math.max(this.slabPoolBaseSlab, cursorSlab);
                this.lastSyncedFooterOffset = -1;
                this.dirty = true;
                this.crashFooterRepairs++;
                this.selfHealingDiagnostics.add("Recovered missing/torn LCA1_END footer canary on open in " + lcaFile);
                writeFooterCanary();
                long targetLen = activeByteLength();
                if (ch.size() != targetLen) {
                    ch.truncate(targetLen);
                }
                if (lcaFile != null) {
                    LcaRegionReader.invalidateFileCache(lcaFile);
                }
            }
        }

        private boolean isSlabValidForChunkOnChannel(
                FileChannel ch, long ptr, int expectedChunkIdx, int effectivePayloadLimit
        ) throws IOException {
            long slabStart = ptr >>> 29;
            int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
            if (slabStart < this.slabPoolBaseSlab || byteLen < 24) {
                return false;
            }
            long byteOffset = slabStart << LcaRegionReader.SLAB_SHIFT;
            if (byteOffset + byteLen > effectivePayloadLimit) {
                return false;
            }
            int headerLen = 24 + (this.sectionCount << 1);
            int headerWords = (headerLen + 7) >>> 3;
            int readLen = Math.min(byteLen, headerLen + headerWords);
            if (byteOffset + readLen > ch.size()) {
                return false;
            }
            byte[] hdr = new byte[readLen];
            readChannelRangeStatic(ch, byteOffset, hdr, 0, readLen, this.backingFile);
            int dvWord = LcaSectionCodec.readIntBE(hdr, 0);
            if ((dvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) != LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC
                    && ((ptr >>> 28) & 1L) != 0
                    && readLen >= headerLen + headerWords) {
                int healed = LcaSectionCodec.verifyAndHealChunkHeaderSecDed(hdr, 0, headerLen, headerLen);
                if (healed > 0) {
                    dvWord = LcaSectionCodec.readIntBE(hdr, 0);
                }
            }
            return (dvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) == LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC
                    && ((dvWord >>> 16) & 0x03FF) == expectedChunkIdx;
        }

        private void reconstructCorruptPointersOnOpen(
                FileChannel ch, boolean[] needsOpenScan, int effectivePayloadLimit, List<int[]> allocatedIntervals
        ) throws IOException {
            int headerLen = 24 + (this.sectionCount << 1);
            int headerWords = (headerLen + 7) >>> 3;
            int maxSlab = effectivePayloadLimit >>> LcaRegionReader.SLAB_SHIFT;
            long[] bestPtrByChunk = new long[1024];
            int[] bestGenByChunk = new int[1024];
            Arrays.fill(bestGenByChunk, -1);

            byte[] probe = new byte[headerLen + headerWords];
            for (int slab = this.slabPoolBaseSlab; slab < maxSlab; slab++) {
                long bytePos = (long) slab << LcaRegionReader.SLAB_SHIFT;
                if (bytePos + headerLen > effectivePayloadLimit) {
                    break;
                }
                int toRead = (int) Math.min(probe.length, effectivePayloadLimit - bytePos);
                readChannelRangeStatic(ch, bytePos, probe, 0, toRead, this.backingFile);
                int dvWord = LcaSectionCodec.readIntBE(probe, 0);
                if ((dvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) != LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC
                        && this.modeBZstd
                        && toRead >= headerLen + headerWords) {
                    int healed = LcaSectionCodec.verifyAndHealChunkHeaderSecDed(probe, 0, headerLen, headerLen);
                    if (healed > 0) {
                        writeChannelRange(bytePos, probe, 0, headerLen);
                        this.liveChunkHeaderHealedWords += healed;
                        dvWord = LcaSectionCodec.readIntBE(probe, 0);
                    }
                }
                if ((dvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) != LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC) {
                    continue;
                }
                int chunkIdx = (dvWord >>> 16) & 0x03FF;
                if (!needsOpenScan[chunkIdx]) {
                    continue;
                }
                int estLen = estimateSlabLengthOnChannel(ch, bytePos, effectivePayloadLimit);
                if (estLen >= headerLen && bytePos + estLen <= effectivePayloadLimit) {
                    bestPtrByChunk[chunkIdx] = packChunkPointer64(slab, this.modeBZstd, estLen, 1);
                    bestGenByChunk[chunkIdx] = slab;
                }
            }

            for (int i = 0; i < 1024; i++) {
                if (!needsOpenScan[i]) continue;
                int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3);
                long recoveredPtr = bestPtrByChunk[i];
                this.buffer[this.secDedTableOffset + i] = (byte) LcaSectionCodec.computeHammingSecDed8(recoveredPtr);
                LcaRegionReader.setVolatileLongBE(this.buffer, ptrByteOffset, recoveredPtr);
                persistPointerAndSecDed(i);
                this.dirty = true;
                if (recoveredPtr != 0L) {
                    int slabStart = (int) (recoveredPtr >>> 29);
                    int byteLen = (int) ((recoveredPtr >>> 8) & 0xFFFFFL);
                    int slabs = (byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT;
                    allocatedIntervals.add(new int[] {slabStart, slabStart + slabs});
                    this.liveSlabScanReconstructions++;
                    this.selfHealingDiagnostics.add("Tier-2 Slab Reverse-Scan reconstructed pointer on open for chunkIdx=" + i);
                } else {
                    this.selfHealingDiagnostics.add("Cleared unreferenced/dangling crash pointer on open for chunkIdx=" + i);
                }
            }
        }

        private int estimateSlabLengthOnChannel(FileChannel ch, long bytePos, int effectivePayloadLimit)
                throws IOException {
            long availLimit = Math.min((long) effectivePayloadLimit, ch.size());
            int maxRem = (int) Math.min(65536L, availLimit - bytePos);
            if (maxRem < 24) return 0;
            byte[] buf = new byte[maxRem];
            readChannelRangeStatic(ch, bytePos, buf, 0, maxRem, this.backingFile);
            int exact = LcaRegionReader.estimateSelfDescribingChunkLengthStatic(
                    buf, 0, this.sectionCount, this.modeBZstd, maxRem);
            int headerLen = 24 + (this.sectionCount << 1);
            if (exact > headerLen && exact <= maxRem) {
                return exact;
            }
            if (this.modeBZstd) {
                int headerWords = (headerLen + 7) >>> 3;
                int compStart = headerLen + headerWords;
                int maxSlabs = maxRem >>> LcaRegionReader.SLAB_SHIFT;
                for (int s = 1; s <= maxSlabs; s++) {
                    int candEnd = s << LcaRegionReader.SLAB_SHIFT;
                    if (candEnd < maxRem) {
                        long maybeCanary = (long) LcaRegionReader.LONG_BE_VIEW.get(buf, candEnd);
                        int maybeDv = LcaSectionCodec.readIntBE(buf, candEnd);
                        if (maybeCanary == LcaRegionReader.LCA_FOOTER_CANARY_MAGIC
                                || (maybeDv & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) == LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC) {
                            return trimTrailingSlabZeros(buf, compStart, candEnd);
                        }
                    }
                }
                return trimTrailingSlabZeros(buf, compStart, maxRem);
            }
            return exact;
        }

        private static int trimTrailingSlabZeros(byte[] buf, int minLen, int paddedEnd) {
            int end = paddedEnd;
            while (end > minLen && buf[end - 1] == 0) {
                end--;
            }
            return Math.max(minLen, end);
        }

        private static void readChannelRangeStatic(
                FileChannel ch, long fileOffset, byte[] dst, int dstOffset, int length, Path file
        ) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(dst, dstOffset, length);
            long pos = fileOffset;
            while (bb.hasRemaining()) {
                int n = ch.read(bb, pos);
                if (n < 0) {
                    throw new CorruptRegionEntryException("Short read from .lca archive"
                            + (file != null ? ": " + file : "") + " at offset " + pos);
                }
                pos += n;
            }
        }

        public synchronized byte[] liveBuffer() {
            if (headerOnlyChannelMode) {
                materializeFullBufferFromChannel();
            }
            return buffer;
        }

        private void materializeFullBufferFromChannel() {
            if (!headerOnlyChannelMode) {
                return;
            }
            int fullLen = activeByteLength();
            byte[] full = new byte[fullLen + 65536];
            if (channel != null && channel.isOpen()) {
                try {
                    int diskLen = (int) Math.min(channel.size(), (long) fullLen);
                    if (diskLen > 0) {
                        readChannelRangeStatic(channel, 0L, full, 0, diskLen, backingFile);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            int copyPrefix = Math.min(buffer.length, full.length);
            System.arraycopy(buffer, 0, full, 0, copyPrefix);
            LcaRegionReader.setVolatileLongBE(
                    full, activeEndSlab << LcaRegionReader.SLAB_SHIFT, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
            this.buffer = full;
            this.headerOnlyChannelMode = false;
        }

        public int activeByteLength() {
            return (activeEndSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE;
        }

        public int regionX() {
            return regionX;
        }

        public int regionZ() {
            return regionZ;
        }

        public int storageKind() {
            return storageKind;
        }

        public Path backingFile() {
            return backingFile;
        }

        public boolean isChannelOpen() {
            return channel != null && channel.isOpen();
        }

        public long diskChannelWriteCount() {
            return diskChannelWriteCount;
        }

        public long diskChannelBytesWritten() {
            return diskChannelBytesWritten;
        }

        synchronized void attachOpenChannel(Path file, FileChannel openChannel) {
            this.backingFile = file;
            this.channel = openChannel;
            this.lastSyncedFooterOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
        }

        synchronized void bindBackingFile(Path file, boolean writeInitialState) throws IOException {
            this.backingFile = file;
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (writeInitialState) {
                this.channel = FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                writeFooterCanary();
                int len = activeByteLength();
                writeChannelRange(0L, buffer, 0, len);
                this.lastSyncedFooterOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                int prefixBytes = slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT;
                if (activeEndSlab == slabPoolBaseSlab && buffer.length > prefixBytes) {
                    this.buffer = Arrays.copyOf(buffer, prefixBytes);
                    this.headerOnlyChannelMode = true;
                }
            } else {
                this.channel = FileChannel.open(
                        file,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
                this.lastSyncedFooterOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
            }
        }

        private void writeChannelRange(long fileOffset, byte[] src, int srcOffset, int length) throws IOException {
            if (channel == null || !channel.isOpen() || length <= 0) {
                return;
            }
            ByteBuffer bb = ByteBuffer.wrap(src, srcOffset, length);
            long pos = fileOffset;
            while (bb.hasRemaining()) {
                int written = channel.write(bb, pos);
                if (written <= 0) {
                    throw new IOException("FileChannel positional write made no progress at offset " + pos);
                }
                pos += written;
            }
            diskChannelWriteCount++;
            diskChannelBytesWritten += length;
        }

        private void writeChannelRangeUnchecked(long fileOffset, byte[] src, int srcOffset, int length) {
            try {
                writeChannelRange(fileOffset, src, srcOffset, length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void persistPointerAndSecDed(int chunkIdx) {
            if (channel == null || !channel.isOpen()) {
                return;
            }
            int secDedPos = secDedTableOffset + chunkIdx;
            writeChannelRangeUnchecked(secDedPos, buffer, secDedPos, 1);
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            writeChannelRangeUnchecked(ptrByteOffset, buffer, ptrByteOffset, 8);
            if (backingFile != null) {
                long newPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOffset);
                boolean dictMod = this.dictModifiedSinceSync;
                this.dictModifiedSinceSync = false;
                LcaRegionReader.updateCachedPointer(backingFile, chunkIdx, newPtr, activeByteLength(), dictMod);
            }
        }

        public synchronized byte[] toByteArray() {
            writeFooterCanary();
            if (headerOnlyChannelMode) {
                int fullLen = activeByteLength();
                byte[] out = new byte[fullLen];
                if (channel != null && channel.isOpen()) {
                    try {
                        int diskLen = (int) Math.min(channel.size(), (long) fullLen);
                        if (diskLen > 0) {
                            readChannelRangeStatic(channel, 0L, out, 0, diskLen, backingFile);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                int copyPrefix = Math.min(buffer.length, out.length);
                System.arraycopy(buffer, 0, out, 0, copyPrefix);
                LcaRegionReader.setVolatileLongBE(
                        out, activeEndSlab << LcaRegionReader.SLAB_SHIFT, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                return out;
            }
            return Arrays.copyOf(buffer, activeByteLength());
        }

        public long totalSaves() {
            return totalSaves;
        }

        public long reusedSlabSaves() {
            return reusedSlabSaves;
        }

        public long eofAppendSaves() {
            return eofAppendSaves;
        }

        public long liveSecDedHealedPointers() {
            return liveSecDedHealedPointers;
        }

        public long liveChunkHeaderHealedWords() {
            return liveChunkHeaderHealedWords;
        }

        public long liveSlabScanReconstructions() {
            return liveSlabScanReconstructions;
        }

        public long liveCoWRollbacks() {
            return liveCoWRollbacks;
        }

        public long crashFooterRepairs() {
            return crashFooterRepairs;
        }

        public List<String> selfHealingDiagnostics() {
            synchronized (selfHealingDiagnostics) {
                return List.copyOf(selfHealingDiagnostics);
            }
        }

        public double slabReuseRatio() {
            return totalSaves == 0 ? 1.0 : (double) reusedSlabSaves / (double) totalSaves;
        }

        public int dictionarySegmentCount() {
            return dictSegments.size();
        }

        public int totalDictionaryStates() {
            return idToBlockState.size();
        }

        /**
         * Returns the total number of `256B` sub-slabs currently sitting in the free-slab pool.
         */
        public synchronized int freeSlabCount() {
            int free = 0;
            for (int count : freeByStart.values()) {
                free += count;
            }
            for (int i = 0; i < COW_GRACE_EPOCHS; i++) {
                if (graceSlabStarts[i] > 0 && graceSlabCounts[i] > 0) {
                    free += graceSlabCounts[i];
                }
            }
            for (long[] item : deferredRetireSlabs) {
                free += (int) item[1];
            }
            return free;
        }

        /**
         * Returns the ratio of free `256B` sub-slabs to total allocated sub-slab pool capacity (`0.0 .. 1.0`).
         */
        public synchronized double fragmentationRatio() {
            int totalPoolSlabs = activeEndSlab - slabPoolBaseSlab;
            if (totalPoolSlabs <= 0) {
                return 0.0;
            }
            return (double) freeSlabCount() / (double) totalPoolSlabs;
        }

        /**
         * $O(1)$ volatile check for whether chunk `(rx, rz)` is allocated (`RegionFile#doesChunkExist`
         * / `RegionFile#hasChunk` equivalent).
         */
        public boolean hasChunk(int rx, int rz) {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                return false;
            }
            if (headerOnlyChannelMode) {
                int chunkIdx = (rx & 31) | ((rz & 31) << 5);
                long ptr = resolveAndHealLivePointer(chunkIdx);
                long byteOffset = (ptr >>> 29) << LcaRegionReader.SLAB_SHIFT;
                int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                long maxEnd = (long) activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                return byteOffset >= LcaRegionReader.DICT_ROOT_OFFSET
                        && exactByteLen20 > 0
                        && byteOffset + exactByteLen20 <= maxEnd;
            }
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            resolveAndHealLivePointer(chunkIdx);
            byte[] buf = this.buffer;
            int len = Math.min(buf.length, (this.activeEndSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE);
            return LcaRegionReader.INSTANCE.isChunkGenerated(buf, len, rx, rz);
        }

        private long resolveAndHealLivePointer(int chunkIdx) {
            byte[] buf = this.buffer;
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long ptr = LcaRegionReader.getVolatileLongBE(buf, ptrByteOffset);
            if (secDedTableOffset + chunkIdx >= buf.length) {
                return ptr;
            }
            int syn8 = buf[secDedTableOffset + chunkIdx] & 0xFF;
            if ((ptr != 0L || syn8 != 0) && LcaSectionCodec.computeHammingSecDed8(ptr) != syn8) {
                synchronized (this) {
                    ptr = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                    syn8 = this.buffer[secDedTableOffset + chunkIdx] & 0xFF;
                    if (LcaSectionCodec.computeHammingSecDed8(ptr) != syn8) {
                        long healed = LcaSectionCodec.healSingleBitFlip64(ptr, syn8);
                        if (healed != Long.MIN_VALUE) {
                            if (healed != ptr) {
                                ptr = healed;
                                LcaRegionReader.setVolatileLongBE(this.buffer, ptrByteOffset, ptr);
                                this.liveSecDedHealedPointers++;
                                this.selfHealingDiagnostics.add("Tier-1 SEC-DED healed 1-bit pointer flip on hot read for chunkIdx=" + chunkIdx);
                            }
                            this.buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(ptr);
                            this.dirty = true;
                            try {
                                persistPointerAndSecDed(chunkIdx);
                            } catch (RuntimeException e) {
                                this.selfHealingDiagnostics.add("Deferred persisting healed pointer for chunkIdx="
                                        + chunkIdx + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
            return ptr;
        }

        private synchronized boolean tryRecoverLiveChunkPointer(int rx, int rz, int chunkIdx, FileChannel ch)
                throws IOException {
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            // 1. Check if on-disk pointer/syndrome was modified externally and can be healed by Tier 1
            if (ch != null && ch.isOpen() && ch.size() > secDedTableOffset + chunkIdx) {
                byte[] diskPtrBuf = new byte[8];
                byte[] diskSynBuf = new byte[1];
                readChannelRangeStatic(ch, ptrByteOffset, diskPtrBuf, 0, 8, backingFile);
                readChannelRangeStatic(ch, secDedTableOffset + chunkIdx, diskSynBuf, 0, 1, backingFile);
                long diskPtr = (long) LcaRegionReader.LONG_BE_VIEW.get(diskPtrBuf, 0);
                int diskSyn = diskSynBuf[0] & 0xFF;
                long healed = LcaSectionCodec.healSingleBitFlip64(diskPtr, diskSyn);
                int effLimit = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                if (healed != Long.MIN_VALUE && healed != 0L
                        && isSlabValidForChunkOnChannel(ch, healed, chunkIdx, effLimit)) {
                    LcaRegionReader.setVolatileLongBE(this.buffer, ptrByteOffset, healed);
                    this.buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(healed);
                    persistPointerAndSecDed(chunkIdx);
                    this.dirty = true;
                    this.liveSecDedHealedPointers++;
                    this.selfHealingDiagnostics.add("Tier-1 SEC-DED healed on-disk pointer flip for chunk (" + rx + "," + rz + ")");
                    return true;
                }
            }
            // 2. Tier 4: CoW rollback to previous verified generation pointer if present and valid
            long prevPtr = prevChunkPointers[chunkIdx];
            if ((prevPtr >>> 29) > 0 && ((prevPtr >>> 8) & 0xFFFFFL) > 0) {
                int effLimit = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                if (ch == null || !ch.isOpen() || isSlabValidForChunkOnChannel(ch, prevPtr, chunkIdx, effLimit)) {
                    if (rollbackCorruptChunkToPriorCoW(rx, rz)) {
                        this.liveCoWRollbacks++;
                        this.selfHealingDiagnostics.add("Tier-4 CoW rollback recovered chunk (" + rx + "," + rz + ")");
                        return true;
                    }
                }
            }
            // 3. Tier 2: Reverse-scan 256B slab pool for self-describing 0x5800_0000 | (chunkIdx << 16)
            if (ch != null && ch.isOpen()) {
                boolean[] scanMask = new boolean[1024];
                scanMask[chunkIdx] = true;
                List<int[]> dummyIntervals = new ArrayList<>(1);
                reconstructCorruptPointersOnOpen(ch, scanMask, activeEndSlab << LcaRegionReader.SLAB_SHIFT, dummyIntervals);
                long recPtr = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                if ((recPtr >>> 29) > 0 && ((recPtr >>> 8) & 0xFFFFFL) > 0) {
                    return true;
                }
            } else if (!headerOnlyChannelMode) {
                int len = Math.min(buffer.length, activeByteLength());
                byte[] copy = Arrays.copyOf(buffer, len);
                LcaRegionReader.RecoveryReport rep = LcaRegionReader.healRegionBitRot(copy);
                if (rep.secDedHealedPointers() + rep.slabScanReconstructedPointers() + rep.chunkHeaderSecDedHealedWords() > 0) {
                    System.arraycopy(copy, 0, buffer, 0, len);
                    this.liveSecDedHealedPointers += rep.secDedHealedPointers();
                    this.liveSlabScanReconstructions += rep.slabScanReconstructedPointers();
                    this.liveChunkHeaderHealedWords += rep.chunkHeaderSecDedHealedWords();
                    this.selfHealingDiagnostics.addAll(rep.diagnostics());
                    return true;
                }
            }
            return false;
        }

        private byte[] readAndHealChannelSlab(
                FileChannel ch, Path bf, int rx, int rz, long payloadStartOffset, int codec1, int exactByteLen20
        ) throws IOException {
            byte[] rawSlab = new byte[exactByteLen20];
            readChannelRangeStatic(ch, payloadStartOffset, rawSlab, 0, exactByteLen20, bf);
            if (codec1 == 1) {
                int headerLen = 24 + (this.sectionCount << 1);
                int headerWords = (headerLen + 7) >>> 3;
                if (rawSlab.length >= headerLen + headerWords) {
                    int healedWords = LcaSectionCodec.verifyAndHealChunkHeaderSecDed(rawSlab, 0, headerLen, headerLen);
                    if (healedWords > 0) {
                        synchronized (this) {
                            this.liveChunkHeaderHealedWords += healedWords;
                            this.dirty = true;
                            this.selfHealingDiagnostics.add("Tier-1 ChunkFrame header SEC-DED healed "
                                    + healedWords + " word(s) on hot read in (" + rx + "," + rz + ")");
                            writeChannelRange(payloadStartOffset, rawSlab, 0, headerLen);
                        }
                    }
                }
            }
            return rawSlab;
        }

        public boolean doesChunkExist(int rx, int rz) {
            return hasChunk(rx, rz);
        }

        public boolean isDirty() {
            return dirty;
        }

        private LcaSectionCodec.ParsedHeaderAndDict getOrBuildParsedHeaderAndDict() {
            LcaSectionCodec.ParsedHeaderAndDict d = this.cachedParsedDict;
            if (d != null) {
                return d;
            }
            List<String> statesSnapshot;
            List<String> biomesSnapshot;
            synchronized (this) {
                d = this.cachedParsedDict;
                if (d != null) {
                    return d;
                }
                statesSnapshot = new ArrayList<>(idToBlockState);
                biomesSnapshot = new ArrayList<>(idToBiome);
            }
            List<String> canonicalStates = new ArrayList<>(statesSnapshot.size());
            List<String> baseBlockNames = new ArrayList<>(statesSnapshot.size());
            List<List<String>> singletonBasePalettes = new ArrayList<>(statesSnapshot.size());
            for (String canonical : statesSnapshot) {
                String baseName = LcaSectionCodec.stripBlockProperties(canonical);
                canonicalStates.add(canonical);
                baseBlockNames.add(baseName);
                singletonBasePalettes.add(List.of(baseName));
            }
            List<String> biomes = new ArrayList<>(biomesSnapshot.size());
            List<List<String>> singletonBiomePalettes = new ArrayList<>(biomesSnapshot.size());
            for (String biomeId : biomesSnapshot) {
                biomes.add(biomeId);
                singletonBiomePalettes.add(List.of(biomeId));
            }
            int flags = 1 | (modeBZstd ? 8 : 0) | 16;
            long slabPoolBaseOffset = (long) slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT;
            d = new LcaSectionCodec.ParsedHeaderAndDict(
                    storageKind, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                    flags, slabPoolBaseOffset,
                    Collections.unmodifiableList(canonicalStates),
                    Collections.unmodifiableList(baseBlockNames),
                    Collections.unmodifiableList(singletonBasePalettes),
                    Collections.unmodifiableList(biomes),
                    Collections.unmodifiableList(singletonBiomePalettes)
            );
            this.cachedParsedDict = d;
            return d;
        }

        /**
         * Reads and decodes the chunk at `(rx, rz)` directly from this live archive's buffer or
         * open {@link FileChannel}, or returns `null` if unallocated.
         */
        public AnvilReader.ChunkEntry readChunkEntry(int rx, int rz) throws IOException {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
            }
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            if (headerOnlyChannelMode) {
                FileChannel ch = this.channel;
                if (ch == null || !ch.isOpen()) {
                    if (backingFile != null) {
                        return LcaRegionReader.readChunkFromFile(backingFile, rx, rz);
                    }
                    return null;
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    long ptr = resolveAndHealLivePointer(chunkIdx);
                    long slabOffset35 = ptr >>> 29;
                    int codec1 = (int) ((ptr >>> 28) & 1L);
                    int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                    int generation8 = (int) (ptr & 0xFFL);
                    if (slabOffset35 == 0 || exactByteLen20 == 0) {
                        return null;
                    }
                    long payloadStartOffset = slabOffset35 << LcaRegionReader.SLAB_SHIFT;
                    long maxEnd = (long) activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                    try {
                        if (payloadStartOffset < LcaRegionReader.DICT_ROOT_OFFSET
                                || exactByteLen20 > LcaRegionReader.MAX_CHUNK_UNCOMPRESSED_BYTES
                                || payloadStartOffset + exactByteLen20 > maxEnd) {
                            throw new CorruptRegionEntryException("CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds");
                        }
                        byte[] rawSlab = readAndHealChannelSlab(
                                ch, backingFile, rx, rz, payloadStartOffset, codec1, exactByteLen20);
                        return LcaRegionReader.decodeChunkFromRawSlab(
                                getOrBuildParsedHeaderAndDict(),
                                rx, rz, ptrByteOffset, ptr, codec1, exactByteLen20, generation8, rawSlab);
                    } catch (CorruptRegionEntryException e) {
                        if (attempt == 0 && tryRecoverLiveChunkPointer(rx, rz, chunkIdx, ch)) {
                            continue;
                        }
                        throw e;
                    }
                }
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                resolveAndHealLivePointer(chunkIdx);
                byte[] buf = this.buffer;
                int len = Math.min(buf.length, (this.activeEndSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE);
                try {
                    return LcaRegionReader.INSTANCE.readChunk(buf, len, rx, rz);
                } catch (CorruptRegionEntryException e) {
                    if (attempt == 0 && tryRecoverLiveChunkPointer(rx, rz, chunkIdx, null)) {
                        continue;
                    }
                    throw e;
                }
            }
            return null;
        }

        /**
         * Reads and serializes the chunk at `(rx, rz)` into an uncompressed Mojang binary NBT
         * root `TAG_Compound` byte array, or returns `null` if unallocated.
         */
        public byte[] readChunkNbtBytes(int rx, int rz) throws IOException {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
            }
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            FileChannel ch;
            Path bf;
            byte[] buf;
            int endSlab;
            synchronized (this) {
                ch = this.channel;
                bf = this.backingFile;
                buf = this.buffer;
                endSlab = this.activeEndSlab;
            }
            if (headerOnlyChannelMode) {
                if (ch == null || !ch.isOpen()) {
                    if (bf != null) {
                        return LcaRegionReader.readChunkNbtBytesFromFile(bf, rx, rz);
                    }
                    return null;
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    long ptr = resolveAndHealLivePointer(chunkIdx);
                    long slabOffset35 = ptr >>> 29;
                    int codec1 = (int) ((ptr >>> 28) & 1L);
                    int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                    int generation8 = (int) (ptr & 0xFFL);
                    if (slabOffset35 == 0 || exactByteLen20 == 0) {
                        return null;
                    }
                    long payloadStartOffset = slabOffset35 << LcaRegionReader.SLAB_SHIFT;
                    long maxEnd = (long) this.activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                    try {
                        if (payloadStartOffset < LcaRegionReader.DICT_ROOT_OFFSET
                                || exactByteLen20 > LcaRegionReader.MAX_CHUNK_UNCOMPRESSED_BYTES
                                || payloadStartOffset + exactByteLen20 > maxEnd) {
                            throw new CorruptRegionEntryException("CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds");
                        }
                        byte[] rawSlab = readAndHealChannelSlab(
                                ch, bf, rx, rz, payloadStartOffset, codec1, exactByteLen20);
                        return LcaRegionReader.decodeChunkNbtBytesFromRawSlab(
                                getOrBuildParsedHeaderAndDict(),
                                rx, rz, ptrByteOffset, ptr, codec1, exactByteLen20, generation8, rawSlab);
                    } catch (CorruptRegionEntryException e) {
                        if (attempt == 0 && tryRecoverLiveChunkPointer(rx, rz, chunkIdx, ch)) {
                            continue;
                        }
                        throw e;
                    }
                }
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                resolveAndHealLivePointer(chunkIdx);
                buf = this.buffer;
                endSlab = this.activeEndSlab;
                int len = Math.min(buf.length, (endSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE);
                try {
                    return LcaRegionReader.INSTANCE.readChunkNbtBytes(buf, len, rx, rz);
                } catch (CorruptRegionEntryException e) {
                    if (attempt == 0 && tryRecoverLiveChunkPointer(rx, rz, chunkIdx, null)) {
                        continue;
                    }
                    throw e;
                }
            }
            return null;
        }

        /**
         * `RegionFile#getChunkDataInputStream(ChunkPos)` equivalent: returns a {@link DataInputStream}
         * over the uncompressed Mojang binary NBT root `TAG_Compound` for `(rx, rz)`, or `null` if
         * the chunk is unallocated. Decoding is deferred until the first stream read so callers such
         * as Paper Moonrise and `C2ME` decode on their parallel worker pools rather than the single
         * I/O coordinator thread.
         */
        public DataInputStream getChunkDataInputStream(int rx, int rz) throws IOException {
            if (!hasChunk(rx, rz)) {
                return null;
            }
            return new DataInputStream(new LazyChunkNbtInputStream(() -> readChunkNbtBytes(rx, rz)));
        }

        /**
         * `RegionFile#getChunkDataOutputStream(ChunkPos)` equivalent: returns a {@link DataOutputStream}
         * backed by a commit-on-close buffer that parses the written uncompressed Mojang binary NBT
         * `TAG_Compound` and commits it via {@link #saveChunkCoW(int, int, Map)} when closed.
         */
        public DataOutputStream getChunkDataOutputStream(int rx, int rz) {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
            }
            ByteArrayOutputStream commitOut = new ByteArrayOutputStream(8192) {
                private boolean closed = false;

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    super.close();
                    byte[] nbtBytes = toByteArray();
                    if (nbtBytes.length > 0) {
                        try {
                            saveChunkNbtBytes(rx, rz, nbtBytes);
                        } catch (UncheckedIOException e) {
                            throw e.getCause() != null ? e.getCause() : new IOException(e);
                        }
                    }
                }
            };
            return new DataOutputStream(commitOut);
        }

        /**
         * Parses an uncompressed Mojang binary NBT root `TAG_Compound` byte array and saves it via
         * {@link #saveChunkCoW(int, int, Map)}.
         */
        public synchronized int saveChunkNbtBytes(int rx, int rz, byte[] uncompressedNbtBytes) throws IOException {
            LinkedHashMap<String, Object> root = Nbt.readRootCompound(uncompressedNbtBytes);
            int written = saveChunkCoW(rx, rz, root);
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            cachedHeightmapRef[chunkIdx] = null;
            cachedResidualBytes[chunkIdx] = null;
            return written;
        }

        /**
         * Clears the chunk pointer for `(rx, rz)` and returns its slabs to the CoW grace ring.
         */
        public synchronized boolean removeChunk(int rx, int rz) {
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long oldPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOffset);
            int oldSlabStart = (int) (oldPtr >>> 29);
            int oldByteLen = (int) ((oldPtr >>> 8) & 0xFFFFFL);
            if (oldSlabStart == 0 || oldByteLen == 0) {
                return false;
            }
            dirty = true;
            prevChunkPointers[chunkIdx] = oldPtr;
            buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(0L);
            LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, 0L);
            persistPointerAndSecDed(chunkIdx);

            int oldSlabs = (oldByteLen + 255) >>> LcaRegionReader.SLAB_SHIFT;
            returnFreeSlabs(oldSlabStart, oldSlabs);
            cachedHeightmapRef[chunkIdx] = null;
            cachedResidualBytes[chunkIdx] = null;
            return true;
        }

        /**
         * Compacts this archive in-place if the free-slab fragmentation ratio is `>= maxFreeRatio`
         * (`0.0 .. 1.0`). Returns `true` if compaction was performed.
         */
        public synchronized boolean compactIfFragmented(double maxFreeRatio) throws IOException {
            if (fragmentationRatio() < maxFreeRatio) {
                return false;
            }
            compact();
            return true;
        }

        /**
         * Repacks all live `256B` chunk slabs contiguously without re-encoding voxels ({@code < 0.2 ms}),
         * eliminating free-list holes and truncating the backing file to the compacted EOF.
         */
        public synchronized void compact() throws IOException {
            dirty = true;
            // Reclaim all grace-ring and unpinned deferred slabs first
            Arrays.fill(graceSlabStarts, 0);
            Arrays.fill(graceSlabCounts, 0);
            Arrays.fill(graceRetireEpochs, 0L);
            graceCursor = 0;
            deferredRetireSlabs.clear();
            freeByStart.clear();
            freeBySize.clear();

            // Collect all live chunk slabs in index order
            byte[][] liveChunkPayloads = new byte[1024][];
            int[] liveChunkGens = new int[1024];
            boolean[] liveChunkModeB = new boolean[1024];
            int totalLiveSlabCount = 0;
            for (int i = 0; i < 1024; i++) {
                long ptr = LcaRegionReader.getVolatileLongBE(
                        buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3));
                int slabStart = (int) (ptr >>> 29);
                int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                if (slabStart > 0 && byteLen > 0) {
                    int byteOff = slabStart << LcaRegionReader.SLAB_SHIFT;
                    if (headerOnlyChannelMode && channel != null && channel.isOpen()) {
                        byte[] pay = new byte[byteLen];
                        readChannelRangeStatic(channel, byteOff, pay, 0, byteLen, backingFile);
                        liveChunkPayloads[i] = pay;
                    } else {
                        liveChunkPayloads[i] = Arrays.copyOfRange(buffer, byteOff, byteOff + byteLen);
                    }
                    liveChunkGens[i] = (int) (ptr & 0xFFL);
                    liveChunkModeB[i] = ((ptr >>> 28) & 1L) != 0;
                    totalLiveSlabCount += (byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT;
                }
            }

            // Relocate any continuation dictionary segments contiguously to the start of the slab pool
            int cursorSlab = slabPoolBaseSlab;
            for (int s = 1; s < dictSegments.size(); s++) {
                DictSegmentMeta seg = dictSegments.get(s);
                int segSlabs = (seg.capacityBytes() + 255) >>> LcaRegionReader.SLAB_SHIFT;
                seg.byteOffset = cursorSlab << LcaRegionReader.SLAB_SHIFT;
                cursorSlab += segSlabs;
            }
            for (int s = 0; s < dictSegments.size(); s++) {
                DictSegmentMeta seg = dictSegments.get(s);
                if (s + 1 < dictSegments.size()) {
                    DictSegmentMeta nextSeg = dictSegments.get(s + 1);
                    int relSlabs32 = nextSeg.baseSlab() - seg.baseSlab();
                    int contSlabs = (nextSeg.capacityBytes() + 255) >>> LcaRegionReader.SLAB_SHIFT;
                    seg.linkTrailer = ((long) relSlabs32 << 32)
                            | ((contSlabs & 0xFFFFFFL) << 8)
                            | 1L;
                } else {
                    seg.linkTrailer = 0L;
                }
            }

            if (headerOnlyChannelMode && channel != null && channel.isOpen()) {
                for (int s = 0; s < dictSegments.size(); s++) {
                    flushDictSegment(dictSegments.get(s));
                }
                for (int i = 0; i < 1024; i++) {
                    byte[] payload = liveChunkPayloads[i];
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3);
                    if (payload == null) {
                        buffer[secDedTableOffset + i] = (byte) LcaSectionCodec.computeHammingSecDed8(0L);
                        LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, 0L);
                        continue;
                    }
                    int slabs = (payload.length + 255) >>> LcaRegionReader.SLAB_SHIFT;
                    int targetSlab = cursorSlab;
                    cursorSlab += slabs;
                    writeSlabBytes(targetSlab, payload);
                    long newPtr = packChunkPointer64(targetSlab, liveChunkModeB[i], payload.length, liveChunkGens[i]);
                    buffer[secDedTableOffset + i] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
                    LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, newPtr);
                }
                activeEndSlab = cursorSlab;
                lastSyncedFooterOffset = -1;
                writeChannelRange(
                        LcaRegionReader.POINTER_DIRECTORY_OFFSET,
                        buffer,
                        LcaRegionReader.POINTER_DIRECTORY_OFFSET,
                        LcaRegionReader.POINTER_DIRECTORY_SIZE);
                writeChannelRange(
                        secDedTableOffset,
                        buffer,
                        secDedTableOffset,
                        LcaRegionReader.SECDED_TABLE_SIZE);
                writeFooterCanary();
                channel.truncate(activeByteLength());
                if (backingFile != null) {
                    LcaRegionReader.invalidateFileCache(backingFile);
                }
                return;
            }

            ensureBufferCapacity(((cursorSlab + totalLiveSlabCount) << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE + 4096);
            for (int s = 0; s < dictSegments.size(); s++) {
                flushDictSegment(dictSegments.get(s));
            }
            for (int i = 0; i < 1024; i++) {
                byte[] payload = liveChunkPayloads[i];
                int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3);
                if (payload == null) {
                    buffer[secDedTableOffset + i] = (byte) LcaSectionCodec.computeHammingSecDed8(0L);
                    LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, 0L);
                    continue;
                }
                int slabs = (payload.length + 255) >>> LcaRegionReader.SLAB_SHIFT;
                int targetSlab = cursorSlab;
                cursorSlab += slabs;
                int dstOff = targetSlab << LcaRegionReader.SLAB_SHIFT;
                int padded = slabs << LcaRegionReader.SLAB_SHIFT;
                System.arraycopy(payload, 0, buffer, dstOff, payload.length);
                if (padded > payload.length) {
                    Arrays.fill(buffer, dstOff + payload.length, dstOff + padded, (byte) 0);
                }
                long newPtr = packChunkPointer64(targetSlab, liveChunkModeB[i], payload.length, liveChunkGens[i]);
                buffer[secDedTableOffset + i] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, newPtr);
            }

            activeEndSlab = cursorSlab;
            lastSyncedFooterOffset = -1;
            writeFooterCanary();
            if (channel != null && channel.isOpen()) {
                int newLen = activeByteLength();
                writeChannelRange(0L, buffer, 0, newLen);
                channel.truncate(newLen);
            }
            if (backingFile != null) {
                LcaRegionReader.invalidateFileCache(backingFile);
            }
        }

        /**
         * Flushes any pending writes to the underlying {@link FileChannel} (and issues a hardware
         * `channel.force(false)` barrier when `-Dleafanvil.fsync=true` is enabled).
         */
        public synchronized void flush() throws IOException {
            if (!dirty) {
                return;
            }
            writeFooterCanary();
            if (channel != null && channel.isOpen()
                    && (Boolean.getBoolean("leafanvil.fsync") || Boolean.getBoolean("leafarchive.fsync"))) {
                channel.force(false);
            }
            if (backingFile != null) {
                LcaRegionReader.invalidateFileCache(backingFile);
            }
            dirty = false;
        }

        /**
         * Writes the trailing footer canary if dirty and closes the backing {@link FileChannel}
         * without issuing a synchronous hardware `channel.force(false)` (`fsync`) syscall.
         * Used on LRU cache eviction where slab and pointer updates are already committed to the
         * OS page cache via positional `FileChannel#write`.
         */
        public synchronized void closeWithoutForce() throws IOException {
            try {
                if (dirty) {
                    writeFooterCanary();
                    if (backingFile != null) {
                        LcaRegionReader.invalidateFileCache(backingFile);
                    }
                    dirty = false;
                }
            } finally {
                if (channel != null) {
                    try {
                        channel.close();
                    } finally {
                        channel = null;
                    }
                }
            }
        }

        /**
         * Flushes and closes the backing {@link FileChannel} if open.
         */
        @Override
        public synchronized void close() throws IOException {
            try {
                if (dirty) {
                    flush();
                }
            } finally {
                if (channel != null) {
                    try {
                        channel.close();
                    } finally {
                        channel = null;
                    }
                }
            }
        }

        /**
         * Pre-allocates `headroomSlabs` (`256B` units) into the {@link TreeMap} free-slab pool
         * before the trailing `"LCA1_END"` footer canary so Copy-on-Write (`CoW`) live saves rotate
         * through the grace ring without shifting `activeByteLength()` or the EOF footer canary.
         */
        public synchronized void reserveCoWHeadroomSlabs(int headroomSlabs) {
            if (headroomSlabs <= 0) return;
            dirty = true;
            int startSlab = activeEndSlab;
            int nextEndSlab = startSlab + headroomSlabs;
            if (!headerOnlyChannelMode) {
                ensureBufferCapacity((nextEndSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE);
            }
            returnFreeSlabs(startSlab, headroomSlabs);
            activeEndSlab = nextEndSlab;
            writeFooterCanary();
        }

        void saveChunkInitial(int rx, int rz, Map<String, Object> chunkRoot) throws IOException {
            boolean prevDirty = this.dirty;
            int stateBookmark = idToBlockState.size();
            int biomeBookmark = idToBiome.size();
            int segBookmark = dictSegments.size();
            dirty = true;
            byte[] payload;
            try {
                payload = encodeChunkPayload(rx, rz, chunkRoot);
            } catch (RuntimeException | Error | IOException e) {
                rollbackDictionaryToBookmark(stateBookmark, biomeBookmark, segBookmark);
                this.dirty = prevDirty;
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }
            int slabsNeeded = (payload.length + 255) >>> LcaRegionReader.SLAB_SHIFT;
            int prevActiveEndSlab = this.activeEndSlab;
            int prevLastSyncedFooter = this.lastSyncedFooterOffset;
            int targetSlab = allocateSlabs(slabsNeeded, false);
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long oldPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOffset);
            byte oldSyn = buffer[secDedTableOffset + chunkIdx];
            try {
                writeSlabBytes(targetSlab, payload);
                writeFooterCanary();
                long ptr = packChunkPointer64(targetSlab, writeModeBZstd, payload.length, 1);
                buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(ptr);
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, ptr);
                persistPointerAndSecDed(chunkIdx);
            } catch (RuntimeException | Error e) {
                buffer[secDedTableOffset + chunkIdx] = oldSyn;
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, oldPtr);
                rollbackAllocatedSlabs(targetSlab, slabsNeeded, prevActiveEndSlab, prevLastSyncedFooter, false);
                rollbackDictionaryToBookmark(stateBookmark, biomeBookmark, segBookmark);
                this.dirty = prevDirty;
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }
            cachedHeightmapRef[chunkIdx] = null;
            cachedResidualBytes[chunkIdx] = null;
        }

        /**
         * Rolls back a corrupted chunk pointer for `(rx, rz)` to its previous verified Copy-on-Write
         * (`CoW`) slab pointer if available (`anvil-api-ADR-003` Section 7.2).
         */
        public synchronized boolean rollbackCorruptChunkToPriorCoW(int rx, int rz) {
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            long prevPtr = prevChunkPointers[chunkIdx];
            if ((prevPtr >>> 29) == 0 || ((prevPtr >>> 8) & 0xFFFFFL) == 0) {
                return false;
            }
            dirty = true;
            buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(prevPtr);
            LcaRegionReader.setVolatileLongBE(
                    buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3), prevPtr);
            persistPointerAndSecDed(chunkIdx);
            return true;
        }

        /**
         * Performs a PaperMC-style incremental chunk save using Copy-on-Write (`CoW`) `256B` sub-slab
         * rotation (`anvil-api-ADR-003` Section 2.3).
         */
        public synchronized int saveChunkCoW(int rx, int rz, Map<String, Object> chunkRoot) throws IOException {
            int stateBookmark = idToBlockState.size();
            int biomeBookmark = idToBiome.size();
            int segBookmark = dictSegments.size();
            byte[] payload;
            try {
                payload = encodeChunkPayload(rx, rz, chunkRoot);
                return commitPreEncodedChunkCoW(rx, rz, payload);
            } catch (RuntimeException | Error | IOException e) {
                rollbackDictionaryToBookmark(stateBookmark, biomeBookmark, segBookmark);
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }
        }

        /**
         * Encodes a chunk NBT compound into a `.lca` `ChunkFrame` slab payload without mutating
         * the region pointer directory or slab allocator. Safe to execute concurrently on caller
         * worker pools (such as `C2ME`'s `GlobalExecutors.prioritizedScheduler` via
         * `RegionFileVersion$StreamWrapper`).
         */
        public byte[] encodeChunkPayloadForSlab(int rx, int rz, Map<String, Object> chunkRoot) throws IOException {
            try {
                return encodeChunkPayload(rx, rz, chunkRoot);
            } catch (UncheckedIOException e) {
                throw e.getCause() != null ? e.getCause() : new IOException(e);
            }
        }

        /**
         * Commits a pre-encoded `.lca` `ChunkFrame` slab payload for `(rx, rz)` using Copy-on-Write
         * (`CoW`) `256B` sub-slab rotation, patching the self-describing `chunkIdx` header tag (and
         * `Mode B` word-0 `SEC-DED` syndrome) to match `(rx, rz)`.
         */
        public synchronized int commitPreEncodedChunkCoW(int rx, int rz, byte[] payload) throws IOException {
            if (payload == null || payload.length < 24) {
                return 0;
            }
            boolean prevDirty = this.dirty;
            dirty = true;
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            patchChunkFrameSelfTag(payload, chunkIdx);

            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long oldPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOffset);
            byte oldSyn = buffer[secDedTableOffset + chunkIdx];
            int oldSlabStart = (int) (oldPtr >>> 29);
            int oldByteLen = (int) ((oldPtr >>> 8) & 0xFFFFFL);
            int oldGen = (int) (oldPtr & 0xFFL);

            int newSlabs = Math.max(1, (payload.length + 255) >>> LcaRegionReader.SLAB_SHIFT);

            // Reclaim any unpinned deferred slabs and the oldest 4-epoch grace slot BEFORE allocating
            // so 4-epoch CoW rotation needs only 4x chunk slabs of headroom with 0 EOF appends.
            if (!deferredRetireSlabs.isEmpty()) {
                for (int i = deferredRetireSlabs.size() - 1; i >= 0; i--) {
                    long[] item = deferredRetireSlabs.get(i);
                    int defStart = (int) item[0];
                    int defCount = (int) item[1];
                    if (!LcaRegionReader.isSlabRangePinned(defStart, defCount)) {
                        returnFreeSlabs(defStart, defCount);
                        deferredRetireSlabs.remove(i);
                    }
                }
            }
            if (oldSlabStart > 0 && oldByteLen > 0) {
                int evictedStart = graceSlabStarts[graceCursor];
                int evictedCount = graceSlabCounts[graceCursor];
                if (evictedStart > 0 && evictedCount > 0) {
                    graceSlabStarts[graceCursor] = 0;
                    graceSlabCounts[graceCursor] = 0;
                    if (!LcaRegionReader.isSlabRangePinned(evictedStart, evictedCount)) {
                        returnFreeSlabs(evictedStart, evictedCount);
                    } else {
                        deferredRetireSlabs.add(new long[] {evictedStart, evictedCount, 0L});
                    }
                }
            }

            int prevActiveEndSlab = this.activeEndSlab;
            int prevLastSyncedFooter = this.lastSyncedFooterOffset;
            int newSlabStart = allocateSlabs(newSlabs, true);
            try {
                writeSlabBytes(newSlabStart, payload);
                writeFooterCanary();

                VarHandle.releaseFence();
                int nextGen = (oldGen + 1) & 0xFF;
                long newPtr = packChunkPointer64(newSlabStart, writeModeBZstd, payload.length, nextGen);
                buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, newPtr);
                persistPointerAndSecDed(chunkIdx);
            } catch (RuntimeException | Error e) {
                buffer[secDedTableOffset + chunkIdx] = oldSyn;
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, oldPtr);
                rollbackAllocatedSlabs(newSlabStart, newSlabs, prevActiveEndSlab, prevLastSyncedFooter, true);
                this.dirty = prevDirty;
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }

            if (oldSlabStart > 0 && oldByteLen > 0) {
                prevChunkPointers[chunkIdx] = oldPtr;
                int oldSlabs = (oldByteLen + 255) >>> LcaRegionReader.SLAB_SHIFT;
                graceSlabStarts[graceCursor] = oldSlabStart;
                graceSlabCounts[graceCursor] = oldSlabs;
                graceCursor = (graceCursor + 1) & (COW_GRACE_EPOCHS - 1);
            }
            return payload.length;
        }

        synchronized byte[] readUncompressedChunkFrame(int rx, int rz) throws IOException {
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            long ptr = LcaRegionReader.getVolatileLongBE(
                    buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3));
            int slabStart = (int) (ptr >>> 29);
            int codec1 = (int) ((ptr >>> 28) & 1L);
            int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
            if (slabStart <= 0 || byteLen <= 0) {
                return null;
            }
            int byteOff = slabStart << LcaRegionReader.SLAB_SHIFT;
            byte[] rawSlab;
            if (headerOnlyChannelMode && channel != null && channel.isOpen()) {
                rawSlab = new byte[byteLen];
                readChannelRangeStatic(channel, byteOff, rawSlab, 0, byteLen, backingFile);
            } else {
                rawSlab = Arrays.copyOfRange(buffer, byteOff, byteOff + byteLen);
            }
            if (codec1 == 0) {
                return rawSlab;
            }
            int headerLen = 24 + (sectionCount << 1);
            int headerWords = (headerLen + 7) >>> 3;
            int compOff = headerLen + headerWords;
            byte[] body = DynamicZstdBinding.decompress(
                    rawSlab, compOff, rawSlab.length - compOff, LcaRegionReader.MAX_CHUNK_UNCOMPRESSED_BYTES);
            byte[] full = new byte[headerLen + body.length];
            System.arraycopy(rawSlab, 0, full, 0, headerLen);
            System.arraycopy(body, 0, full, headerLen, body.length);
            return full;
        }

        /**
         * Appends or replaces a `HAS_CHUNK_PATCH_TAIL (0x4000)` delta record (`LCA-ICD-1.0` Section 8.3)
         * on chunk `(rx, rz)` using Copy-on-Write (`CoW`) slab rotation without re-encoding any
         * untouched `SectionFrame`.
         */
        synchronized int applyChunkPatchCoW(
                int rx,
                int rz,
                List<String> appendedStates,
                List<LcaSectionCodec.SectionPatch> sectionPatches
        ) throws IOException {
            byte[] frame = readUncompressedChunkFrame(rx, rz);
            if (frame == null) {
                throw new IOException("Cannot patch ungenerated chunk (" + rx + "," + rz + ")");
            }
            int sectionDirEnd = 24 + (sectionCount << 1);
            int chunkFlags = frame[21] & 0xFF;
            boolean hasResidualNbt = (chunkFlags & 1) != 0;
            boolean hasSlabLocalPalette = (chunkFlags & 2) != 0;
            boolean scale4B = (chunkFlags & 4) != 0;
            int unitShift = scale4B ? 2 : 0;
            int residualRelStart = hasResidualNbt
                    ? sectionDirEnd + (LcaSectionCodec.readUint16BE(frame, 22) << unitShift)
                    : frame.length;

            int[] relStarts = new int[sectionCount];
            for (int i = 0; i < sectionCount; i++) {
                int entry16 = LcaSectionCodec.readUint16BE(frame, 24 + (i << 1));
                relStarts[i] = (entry16 != 0 && (entry16 & 0x8000) == 0)
                        ? sectionDirEnd + ((entry16 - 1) << unitShift)
                        : -1;
            }
            int baseLocalStateCount = 0;
            if (hasSlabLocalPalette) {
                int paletteLimitRel = LcaSectionCodec.findFirstSectionRelStart(relStarts, sectionCount, residualRelStart);
                LcaSectionCodec.SlabLocalPalette localPal = LcaSectionCodec.decodeSlabLocalPalette(
                        frame, 0, sectionDirEnd, paletteLimitRel, false, false, idToBlockState);
                baseLocalStateCount = localPal.baseBlockNames.size();
            }

            List<String> appStates = appendedStates == null ? Collections.emptyList() : appendedStates;
            int[] appGlobalIds = new int[appStates.size()];
            for (int i = 0; i < appStates.size(); i++) {
                String st = appStates.get(i);
                int bracket = st.indexOf('[');
                if (bracket >= 0 && LcaSectionCodec.STATIC_CORE_PROP_TO_ID.get(st.substring(bracket)) == null) {
                    appGlobalIds[i] = registerOrGetBlockStateId(st);
                } else {
                    appGlobalIds[i] = -1;
                }
            }

            byte[] patchTailBytes = LcaSectionCodec.encodeChunkPatchTailBytes(
                    baseLocalStateCount, appStates, appGlobalIds, sectionPatches);
            byte[] patchedFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                    frame, sectionCount, patchTailBytes);

            byte[] finalPayload = writeModeBZstd
                    ? LcaSectionCodec.encodeModeBChunkFrameWithUncompressedHeader(
                            patchedFrame, sectionDirEnd, Math.max(1, zstdLevel))
                    : patchedFrame;
            return commitPreEncodedChunkCoW(rx, rz, finalPayload);
        }

        /**
         * Re-encodes a single modified $16 \times 16 \times 16$ section (`sectionY`) and appends its
         * `SectionFrame` out-of-order into the chunk's `256-byte` sub-slab slack (either immediately
         * before `residualRelStart` when `placeAfterResidualTail == false`, or after the Residual Tail
         * when `placeAfterResidualTail == true`), without re-encoding the remaining 23 sections
         * (`LCA-ICD-1.0` Section 6.4 & Section 8.3).
         */
        synchronized int spliceOutOfOrderSectionCoW(
                int rx,
                int rz,
                int sectionY,
                Map<String, Object> replacementSectionNbt,
                boolean placeAfterResidualTail,
                boolean usePatchTailReplacementOffset
        ) throws IOException {
            int secIdx = sectionY - minSectionY;
            if (secIdx < 0 || secIdx >= sectionCount) {
                throw new IllegalArgumentException("sectionY out of range: " + sectionY);
            }
            byte[] frame = readUncompressedChunkFrame(rx, rz);
            if (frame == null) {
                throw new IOException("Cannot splice section into ungenerated chunk (" + rx + "," + rz + ")");
            }
            int sectionDirEnd = 24 + (sectionCount << 1);
            int chunkFlags = frame[21] & 0xFF;
            boolean hasResidualNbt = (chunkFlags & 1) != 0;
            boolean hasSlabLocalPalette = (chunkFlags & 2) != 0;
            boolean scale4B = (chunkFlags & 4) != 0;
            int unitShift = scale4B ? 2 : 0;
            int unitAlign = 1 << unitShift;
            int defaultBiomeCode = (chunkFlags >>> 3) & 0x1F;
            int defaultBiomeDictId = defaultBiomeCode > 0 ? (defaultBiomeCode - 1) : -1;
            int residualRelStart = hasResidualNbt
                    ? sectionDirEnd + (LcaSectionCodec.readUint16BE(frame, 22) << unitShift)
                    : frame.length;

            int[] relStarts = new int[sectionCount];
            for (int i = 0; i < sectionCount; i++) {
                int entry16 = LcaSectionCodec.readUint16BE(frame, 24 + (i << 1));
                relStarts[i] = (entry16 != 0 && (entry16 & 0x8000) == 0)
                        ? sectionDirEnd + ((entry16 - 1) << unitShift)
                        : -1;
            }

            Map<String, Integer> localStateMap = new LinkedHashMap<>();
            List<String> localStateList = new ArrayList<>();
            List<Integer> localStateGlobalIds = new ArrayList<>();
            Map<String, Integer> localBiomeMap = new LinkedHashMap<>();
            List<String> localBiomeList = new ArrayList<>();
            int baseLocalStateCount = 0;

            if (hasSlabLocalPalette) {
                int paletteLimitRel = LcaSectionCodec.findFirstSectionRelStart(relStarts, sectionCount, residualRelStart);
                LcaSectionCodec.SlabLocalPalette localPal = LcaSectionCodec.decodeSlabLocalPalette(
                        frame, 0, sectionDirEnd, paletteLimitRel, true, true, idToBlockState);
                baseLocalStateCount = localPal.canonicalStates.size();
                for (int i = 0; i < baseLocalStateCount; i++) {
                    String s = localPal.canonicalStates.get(i);
                    localStateMap.put(s, i);
                    localStateList.add(s);
                    localStateGlobalIds.add(-1);
                }
                for (int i = 0; i < localPal.biomes.size(); i++) {
                    String b = localPal.biomes.get(i);
                    localBiomeMap.put(b, i);
                    localBiomeList.add(b);
                }
            }

            int[] blockDictIds = extractBlockPaletteIds(
                    replacementSectionNbt,
                    hasSlabLocalPalette ? localStateMap : null,
                    hasSlabLocalPalette ? localStateList : null,
                    hasSlabLocalPalette ? localStateGlobalIds : null);
            long[] blockData = extractBlockDataLongs(replacementSectionNbt);
            int[] biomeDictIds = extractBiomeIds(
                    replacementSectionNbt,
                    hasSlabLocalPalette ? localBiomeMap : null,
                    hasSlabLocalPalette ? localBiomeList : null);
            long[] biomeData = extractBiomeDataLongs(replacementSectionNbt);
            byte[] skyLight = replacementSectionNbt.get("SkyLight") instanceof byte[] b && b.length == 2048 ? b : null;
            byte[] blkLight = replacementSectionNbt.get("BlockLight") instanceof byte[] b && b.length == 2048 ? b : null;

            LcaSectionCodec.EncodedSection enc = LcaSectionCodec.encodeSection(
                    blockDictIds, blockData, biomeDictIds, biomeData,
                    skyLight, blkLight, defaultBiomeDictId, -1);
            // Force a non-uniform SectionFrame body when testing out-of-order section frame placement
            byte[] secFrameBytes = enc.frameBytes;
            if (secFrameBytes == null || secFrameBytes.length == 0) {
                LcaSectionCodec.EncodedSection forced = LcaSectionCodec.encodeSection(
                        blockDictIds, blockData, biomeDictIds, biomeData,
                        skyLight, blkLight, -2, -1);
                secFrameBytes = forced.frameBytes;
            }

            List<String> appendedStates = (hasSlabLocalPalette && localStateList.size() > baseLocalStateCount)
                    ? new ArrayList<>(localStateList.subList(baseLocalStateCount, localStateList.size()))
                    : Collections.emptyList();
            int[] appendedGlobalIds = new int[appendedStates.size()];
            for (int i = 0; i < appendedStates.size(); i++) {
                appendedGlobalIds[i] = localStateGlobalIds.get(baseLocalStateCount + i);
            }

            // If we have appended states or usePatchTailReplacementOffset, inject ChunkPatchTail first if placing after residual
            byte[] workingFrame = frame;
            if (placeAfterResidualTail) {
                if (!appendedStates.isEmpty() && !usePatchTailReplacementOffset) {
                    byte[] patchPayload = LcaSectionCodec.encodeChunkPatchTailBytes(
                            baseLocalStateCount, appendedStates, appendedGlobalIds, Collections.emptyList());
                    workingFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                            workingFrame, sectionCount, patchPayload);
                } else if (usePatchTailReplacementOffset) {
                    // First inject a placeholder with 2-byte replacementSectionEntry16 to stabilize residual tail length
                    List<LcaSectionCodec.SectionPatch> placeholder = List.of(
                            new LcaSectionCodec.SectionPatch(
                                    secIdx, LcaSectionCodec.PATCH_FLAG_REPLACEMENT_OFFSET, 1, null, null, null, null));
                    byte[] placeholderBytes = LcaSectionCodec.encodeChunkPatchTailBytes(
                            baseLocalStateCount, appendedStates, appendedGlobalIds, placeholder);
                    workingFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                            workingFrame, sectionCount, placeholderBytes);
                }
                int alignedStart = (workingFrame.length + (unitAlign - 1)) & ~(unitAlign - 1);
                int newEntry16 = ((alignedStart - sectionDirEnd) >>> unitShift) + 1;
                byte[] withAppendedSec = new byte[alignedStart + secFrameBytes.length];
                System.arraycopy(workingFrame, 0, withAppendedSec, 0, workingFrame.length);
                System.arraycopy(secFrameBytes, 0, withAppendedSec, alignedStart, secFrameBytes.length);
                if (usePatchTailReplacementOffset) {
                    List<LcaSectionCodec.SectionPatch> realPatch = List.of(
                            new LcaSectionCodec.SectionPatch(
                                    secIdx, LcaSectionCodec.PATCH_FLAG_REPLACEMENT_OFFSET, newEntry16, null, null, null, null));
                    byte[] realPatchBytes = LcaSectionCodec.encodeChunkPatchTailBytes(
                            baseLocalStateCount, appendedStates, appendedGlobalIds, realPatch);
                    workingFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                            withAppendedSec, sectionCount, realPatchBytes);
                } else {
                    withAppendedSec[24 + (secIdx << 1)] = (byte) ((newEntry16 >>> 8) & 0xFF);
                    withAppendedSec[24 + (secIdx << 1) + 1] = (byte) (newEntry16 & 0xFF);
                    workingFrame = withAppendedSec;
                }
            } else {
                // Place out-of-order immediately before residualRelStart
                int insertPos = (residualRelStart + (unitAlign - 1)) & ~(unitAlign - 1);
                int newEntry16 = ((insertPos - sectionDirEnd) >>> unitShift) + 1;
                int afterSecPos = (insertPos + secFrameBytes.length + (unitAlign - 1)) & ~(unitAlign - 1);
                int residualLen = workingFrame.length - residualRelStart;
                byte[] spliced = new byte[afterSecPos + residualLen];
                System.arraycopy(workingFrame, 0, spliced, 0, residualRelStart);
                System.arraycopy(secFrameBytes, 0, spliced, insertPos, secFrameBytes.length);
                if (residualLen > 0) {
                    System.arraycopy(workingFrame, residualRelStart, spliced, afterSecPos, residualLen);
                }
                int newResidualRelOffset = (afterSecPos - sectionDirEnd) >>> unitShift;
                spliced[22] = (byte) ((newResidualRelOffset >>> 8) & 0xFF);
                spliced[23] = (byte) (newResidualRelOffset & 0xFF);
                if (usePatchTailReplacementOffset || !appendedStates.isEmpty()) {
                    List<LcaSectionCodec.SectionPatch> patches = usePatchTailReplacementOffset
                            ? List.of(new LcaSectionCodec.SectionPatch(
                                    secIdx, LcaSectionCodec.PATCH_FLAG_REPLACEMENT_OFFSET, newEntry16, null, null, null, null))
                            : Collections.emptyList();
                    if (!usePatchTailReplacementOffset) {
                        spliced[24 + (secIdx << 1)] = (byte) ((newEntry16 >>> 8) & 0xFF);
                        spliced[24 + (secIdx << 1) + 1] = (byte) (newEntry16 & 0xFF);
                    }
                    byte[] patchPayload = LcaSectionCodec.encodeChunkPatchTailBytes(
                            baseLocalStateCount, appendedStates, appendedGlobalIds, patches);
                    workingFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                            spliced, sectionCount, patchPayload);
                } else {
                    spliced[24 + (secIdx << 1)] = (byte) ((newEntry16 >>> 8) & 0xFF);
                    spliced[24 + (secIdx << 1) + 1] = (byte) (newEntry16 & 0xFF);
                    workingFrame = spliced;
                }
            }

            byte[] finalPayload = writeModeBZstd
                    ? LcaSectionCodec.encodeModeBChunkFrameWithUncompressedHeader(
                            workingFrame, sectionDirEnd, Math.max(1, zstdLevel))
                    : workingFrame;
            return commitPreEncodedChunkCoW(rx, rz, finalPayload);
        }

        private void patchChunkFrameSelfTag(byte[] payload, int chunkIdx) {
            int rawDvWord = LcaSectionCodec.readIntBE(payload, 0);
            if ((rawDvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) != LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC) {
                return;
            }
            int currentIdx = (rawDvWord >>> 16) & 0x03FF;
            if (currentIdx == (chunkIdx & 0x03FF)) {
                return;
            }
            int patchedDvWord = (rawDvWord & 0xFC00_FFFF) | ((chunkIdx & 0x03FF) << 16);
            payload[0] = (byte) ((patchedDvWord >>> 24) & 0xFF);
            payload[1] = (byte) ((patchedDvWord >>> 16) & 0xFF);
            payload[2] = (byte) ((patchedDvWord >>> 8) & 0xFF);
            payload[3] = (byte) (patchedDvWord & 0xFF);
            if (writeModeBZstd) {
                int headerLen = 24 + (sectionCount << 1);
                if (payload.length > headerLen && payload.length >= 8) {
                    long word0 = (long) LcaRegionReader.LONG_BE_VIEW.get(payload, 0);
                    payload[headerLen] = (byte) LcaSectionCodec.computeHammingSecDed8(word0);
                }
            }
        }

        private byte[] encodeChunkPayload(int rx, int rz, Map<String, Object> chunkRoot) throws IOException {
            int chunkDataVersion = chunkRoot.get("DataVersion") instanceof Number n ? n.intValue() : defaultDataVersion;
            if (chunkDataVersion <= 0) chunkDataVersion = defaultDataVersion;

            long lastUpdate = chunkRoot.get("LastUpdate") instanceof Number n ? n.longValue() : 0L;
            long inhabitedTime = chunkRoot.get("InhabitedTime") instanceof Number n ? n.longValue() : 0L;
            int statusOrdinal = LcaRegionReader.encodeStatusOrdinal(chunkRoot.get("Status"));

            Object rawSectionsObj = chunkRoot.get("sections");
            if (rawSectionsObj == null) rawSectionsObj = chunkRoot.get("Sections");
            Nbt.NbtList sectionsList = (storageKind == LcaRegionReader.STORAGE_KIND_REGION_VOXEL
                    && rawSectionsObj instanceof Nbt.NbtList nl) ? nl : null;
            boolean hasVoxelSections = sectionsList != null;
            List<Object> lowBoundarySections = null;
            List<Object> highBoundarySections = null;

            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<String, Object>[] sectionByIdx = new Map[sectionCount];
            int[][] sectionBiomeIds = new int[sectionCount][];
            long[][] sectionBiomeData = new long[sectionCount][];
            boolean useSlabLocalPalette = hasVoxelSections && idToBlockState.size() < 8192;
            Map<String, Integer> localStateMap = useSlabLocalPalette ? new LinkedHashMap<>(32) : null;
            List<String> localStateList = useSlabLocalPalette ? new ArrayList<>(32) : null;
            List<Integer> localStateGlobalIds = useSlabLocalPalette ? new ArrayList<>(32) : null;
            Map<String, Integer> localBiomeMap = useSlabLocalPalette ? new LinkedHashMap<>(8) : null;
            List<String> localBiomeList = useSlabLocalPalette ? new ArrayList<>(8) : null;
            if (useSlabLocalPalette) {
                localStateMap.put("minecraft:air", 0);
                localStateList.add("minecraft:air");
                localStateGlobalIds.add(-1);
                localBiomeMap.put("minecraft:plains", 0);
                localBiomeList.add("minecraft:plains");
            }
            int chunkDefaultBiomeDictId = -1;
            int bestBiomeCount = 0;
            int[] biomeCandidateIds = new int[8];
            int[] biomeCandidateCounts = new int[8];
            int biomeCandidatesUsed = 0;

            List<LcaSectionCodec.BiomeTemplate> rawTemplates = useSlabLocalPalette ? new ArrayList<>(8) : Collections.emptyList();
            int[] rawTemplateCounts = useSlabLocalPalette ? new int[24] : null;
            int[] sectionRawTemplateIdx = new int[sectionCount];
            Arrays.fill(sectionRawTemplateIdx, -1);

            if (sectionsList != null) {
                for (Object rawSec : sectionsList.items) {
                    if (!(rawSec instanceof Map<?, ?> rawMap)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sec = (Map<String, Object>) rawMap;
                    Object yObj = sec.get("Y");
                    if (!(yObj instanceof Number yNum)) continue;
                    int sy = yNum.intValue();
                    int idx = sy - minSectionY;
                    Object bsObj = sec.get("block_states");
                    if (bsObj instanceof Map<?, ?> && idx >= 0 && idx < sectionCount) {
                        sectionByIdx[idx] = sec;
                        int[] bIds = extractBiomeIds(sec, localBiomeMap, localBiomeList);
                        long[] bData = extractBiomeDataLongs(sec);
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
                                rawTemplates.add(new LcaSectionCodec.BiomeTemplate(bIds, bData, Collections.emptyList(), null));
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
                    } else if (sy < minSectionY) {
                        if (lowBoundarySections == null) lowBoundarySections = new ArrayList<>(2);
                        lowBoundarySections.add(sec);
                    } else {
                        if (highBoundarySections == null) highBoundarySections = new ArrayList<>(2);
                        highBoundarySections.add(sec);
                    }
                }
            }

            List<LcaSectionCodec.BiomeTemplate> sortedBiomeTemplates = Collections.emptyList();
            int[] oldToSortedTemplate = null;
            if (useSlabLocalPalette && !rawTemplates.isEmpty()) {
                int tSize = rawTemplates.size();
                Integer[] tOrder = new Integer[tSize];
                for (int t = 0; t < tSize; t++) tOrder[t] = t;
                Arrays.sort(tOrder, (a, b) -> Integer.compare(rawTemplateCounts[b], rawTemplateCounts[a]));
                sortedBiomeTemplates = new ArrayList<>(tSize);
                oldToSortedTemplate = new int[tSize];
                for (int newIdx = 0; newIdx < tSize; newIdx++) {
                    int oldIdx = tOrder[newIdx];
                    oldToSortedTemplate[oldIdx] = newIdx;
                    sortedBiomeTemplates.add(rawTemplates.get(oldIdx));
                }
            }

            LcaSectionCodec.EncodedSection[] encodedSections = new LcaSectionCodec.EncodedSection[sectionCount];
            int totalSectionPayloadBytes = 0;

            for (int i = 0; i < sectionCount; i++) {
                Map<String, Object> sec = sectionByIdx[i];
                if (sec == null) continue;
                int[] blockDictIds = extractBlockPaletteIds(sec, localStateMap, localStateList, localStateGlobalIds);
                if (blockDictIds == null || blockDictIds.length == 0) continue;
                long[] blockData = extractBlockDataLongs(sec);
                int[] biomeDictIds = sectionBiomeIds[i];
                long[] biomeData = sectionBiomeData[i];
                byte[] skyLight = sec.get("SkyLight") instanceof byte[] b && b.length == 2048 ? b : null;
                byte[] blkLight = sec.get("BlockLight") instanceof byte[] b && b.length == 2048 ? b : null;
                int secTmplIdx = (oldToSortedTemplate != null && sectionRawTemplateIdx[i] >= 0)
                        ? oldToSortedTemplate[sectionRawTemplateIdx[i]]
                        : -1;

                LcaSectionCodec.EncodedSection enc = LcaSectionCodec.encodeSection(
                        blockDictIds, blockData, biomeDictIds, biomeData,
                        skyLight, blkLight, chunkDefaultBiomeDictId, secTmplIdx);
                encodedSections[i] = enc;
                if (enc.inlineEntry16 == 0 && enc.frameBytes.length > 0) {
                    totalSectionPayloadBytes += enc.frameBytes.length;
                }
            }

            int[] localStateGlobalIdArr = null;
            if (useSlabLocalPalette && localStateGlobalIds != null) {
                localStateGlobalIdArr = new int[localStateGlobalIds.size()];
                for (int k = 0; k < localStateGlobalIdArr.length; k++) {
                    localStateGlobalIdArr[k] = localStateGlobalIds.get(k);
                }
            }
            byte[] slabLocalPaletteBytes = useSlabLocalPalette
                    ? LcaSectionCodec.encodeSlabLocalPalette(
                            localStateList, localStateGlobalIdArr, localBiomeList, sortedBiomeTemplates)
                    : new byte[0];
            totalSectionPayloadBytes += slabLocalPaletteBytes.length;

            boolean scale4B = totalSectionPayloadBytes > 0x7FFF;
            int unitShift = scale4B ? 2 : 0;
            int unitAlign = 1 << unitShift;

            ByteArrayOutputStream sectionsBody = new ByteArrayOutputStream(totalSectionPayloadBytes + 16);
            if (useSlabLocalPalette) {
                sectionsBody.writeBytes(slabLocalPaletteBytes);
            }
            int[] sectionOffsetEntries = new int[sectionCount];

            for (int i = 0; i < sectionCount; i++) {
                LcaSectionCodec.EncodedSection enc = encodedSections[i];
                if (enc == null) {
                    sectionOffsetEntries[i] = 0x0000;
                } else if (enc.inlineEntry16 != 0) {
                    sectionOffsetEntries[i] = enc.inlineEntry16;
                } else {
                    while ((sectionsBody.size() & (unitAlign - 1)) != 0) {
                        sectionsBody.write(0);
                    }
                    int startRel = sectionsBody.size();
                    int entry16 = (startRel >>> unitShift) + 1;
                    if (entry16 > 0x7FFF) {
                        throw new IOException("Section offset exceeded 15-bit range even with scale4B=" + scale4B);
                    }
                    sectionOffsetEntries[i] = entry16;
                    sectionsBody.writeBytes(enc.frameBytes);
                }
            }

            if (scale4B) {
                while ((sectionsBody.size() & 3) != 0) {
                    sectionsBody.write(0);
                }
            }
            int residualNbtRelOffset = sectionsBody.size() >>> unitShift;
            if (residualNbtRelOffset > 0xFFFF) {
                throw new IOException("Residual offset exceeded 16-bit range: " + residualNbtRelOffset);
            }

            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            Object hmObj = chunkRoot.get("Heightmaps");
            boolean canCacheResidual = hasVoxelSections
                    && isEmptyNbtList(chunkRoot.get("block_entities"))
                    && isEmptyNbtList(chunkRoot.get("block_ticks"))
                    && isEmptyNbtList(chunkRoot.get("fluid_ticks"));
            boolean effectiveModeB = writeModeBZstd;
            byte[] residualNbtBytes;
            if (canCacheResidual && hmObj != null && hmObj == cachedHeightmapRef[chunkIdx] && cachedResidualBytes[chunkIdx] != null) {
                residualNbtBytes = cachedResidualBytes[chunkIdx];
            } else {
                residualNbtBytes = serializeCompactResidualNbt(
                        chunkRoot, lowBoundarySections, highBoundarySections,
                        minSectionY, sectionCount, hasVoxelSections, !effectiveModeB);
                if (canCacheResidual) {
                    cachedHeightmapRef[chunkIdx] = hmObj;
                    cachedResidualBytes[chunkIdx] = residualNbtBytes;
                } else {
                    cachedHeightmapRef[chunkIdx] = null;
                    cachedResidualBytes[chunkIdx] = null;
                }
            }
            boolean hasResidualNbt = residualNbtBytes.length > 0;

            int defaultBiomeCode = (chunkDefaultBiomeDictId >= 0 && chunkDefaultBiomeDictId < 30)
                    ? (chunkDefaultBiomeDictId + 1)
                    : 0;
            int chunkFlags = (hasResidualNbt ? 1 : 0)
                    | (useSlabLocalPalette ? 2 : 0)
                    | (scale4B ? 4 : 0)
                    | ((defaultBiomeCode & 0x1F) << 3);

            int headerLen = 24 + (sectionCount << 1);
            byte[] secBytes = sectionsBody.toByteArray();
            byte[] chunkFrame = new byte[headerLen + secBytes.length + residualNbtBytes.length];
            ByteBuffer bb = ByteBuffer.wrap(chunkFrame);
            int selfDescribingDvWord = LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC
                    | ((chunkIdx & 0x03FF) << 16)
                    | (chunkDataVersion & 0xFFFF);
            bb.putInt(0, selfDescribingDvWord);
            bb.putLong(4, lastUpdate);
            bb.putLong(12, inhabitedTime);
            chunkFrame[20] = (byte) statusOrdinal;
            chunkFrame[21] = (byte) chunkFlags;
            bb.putShort(22, (short) residualNbtRelOffset);
            for (int i = 0; i < sectionCount; i++) {
                bb.putShort(24 + (i << 1), (short) sectionOffsetEntries[i]);
            }
            System.arraycopy(secBytes, 0, chunkFrame, headerLen, secBytes.length);
            if (hasResidualNbt) {
                System.arraycopy(residualNbtBytes, 0, chunkFrame, headerLen + secBytes.length, residualNbtBytes.length);
            }

            if (effectiveModeB) {
                return LcaSectionCodec.encodeModeBChunkFrameWithUncompressedHeader(
                        chunkFrame, headerLen, Math.max(1, zstdLevel));
            }
            return chunkFrame;
        }

        private static byte[] serializeCompactResidualNbt(
                Map<String, Object> chunkRoot,
                List<Object> lowBoundarySections,
                List<Object> highBoundarySections,
                int minSectionY,
                int sectionCount,
                boolean hasVoxelSections,
                boolean allowDeflateResidual
        ) throws IOException {
            byte[] mbnlPrefix = null;
            List<String> secHmKeys = new ArrayList<>(6);
            List<byte[]> secHmPayloads = new ArrayList<>(6);
            int bpFlags = hasVoxelSections ? 0 : 2048;
            int ppEmptyCount = 0;
            int starlightVer = 0;
            int lowBoundaryFlags = -1;
            int highBoundaryFlags = -1;

            LinkedHashMap<String, Object> residualMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : chunkRoot.entrySet()) {
                String k = e.getKey();
                if (!hasVoxelSections) {
                    if ("DataVersion".equals(k)) {
                        continue;
                    }
                    residualMap.put(k, e.getValue());
                    continue;
                }
                if ("sections".equals(k) || "Sections".equals(k)
                        || "DataVersion".equals(k)
                        || "LastUpdate".equals(k)
                        || "InhabitedTime".equals(k)
                        || "xPos".equals(k)
                        || "zPos".equals(k)
                        || "yPos".equals(k)
                        || "Status".equals(k)) {
                    continue;
                }
                Object val = e.getValue();
                if ("block_entities".equals(k) && val instanceof Nbt.NbtList nl && nl.items.isEmpty()) {
                    bpFlags |= 1;
                    continue;
                }
                if ("block_ticks".equals(k) && val instanceof Nbt.NbtList nl && nl.items.isEmpty()) {
                    bpFlags |= 2;
                    continue;
                }
                if ("fluid_ticks".equals(k) && val instanceof Nbt.NbtList nl && nl.items.isEmpty()) {
                    bpFlags |= 4;
                    continue;
                }
                if ("isLightOn".equals(k) && val instanceof Byte b && b == (byte) 1) {
                    bpFlags |= 8;
                    continue;
                }
                if ("structures".equals(k) && isEmptyVanillaStructures(val)) {
                    bpFlags |= 16;
                    continue;
                }
                if ("PostProcessing".equals(k) && val instanceof Nbt.NbtList ppList && isAllEmptyShortLists(ppList)) {
                    bpFlags |= 32;
                    ppEmptyCount = ppList.items.size();
                    continue;
                }
                if ("starlight.light_version".equals(k) && val instanceof Integer iv && iv >= 0 && iv <= 1024) {
                    bpFlags |= 64;
                    starlightVer = iv;
                    continue;
                }
                if ("Heightmaps".equals(k) && val instanceof Map<?, ?> hmMap) {
                    List<long[]> seenLongs = new ArrayList<>(hmMap.size());
                    Object mbnlFirst = hmMap.get("MOTION_BLOCKING_NO_LEAVES");
                    if (mbnlFirst instanceof long[] mbnlLongs) {
                        seenLongs.add(mbnlLongs);
                        mbnlPrefix = encodeDeltaHeightmapLongs(mbnlLongs);
                    }
                    for (Map.Entry<?, ?> hmEntry : hmMap.entrySet()) {
                        if (hmEntry.getKey() instanceof String hmKey && hmEntry.getValue() instanceof long[] longs) {
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
                                        byte[] cross = encodeCrossDeltaHeightmapLongs(candBase, refIdx, longs);
                                        if (bestCross == null || cross.length < bestCross.length) {
                                            bestCross = cross;
                                        }
                                    }
                                }
                                hmPayload = (bestCross != null) ? bestCross : encodeDeltaHeightmapLongs(longs);
                            } else {
                                hmPayload = encodeDeltaHeightmapLongs(longs);
                            }
                            seenLongs.add(longs);
                            secHmKeys.add(hmKey);
                            secHmPayloads.add(hmPayload);
                        }
                    }
                    if (!secHmKeys.isEmpty()) {
                        bpFlags |= 512;
                    }
                    continue;
                }
                residualMap.put(k, val);
            }

            if (lowBoundarySections != null && !lowBoundarySections.isEmpty()) {
                lowBoundaryFlags = tryEncodeCompactBoundarySection(lowBoundarySections, minSectionY - 1);
                if (lowBoundaryFlags >= 0) {
                    bpFlags |= 128;
                } else {
                    residualMap.put("_lca_boundary_low", new Nbt.NbtList(Nbt.TAG_COMPOUND, lowBoundarySections));
                }
            }
            if (highBoundarySections != null && !highBoundarySections.isEmpty()) {
                highBoundaryFlags = tryEncodeCompactBoundarySection(highBoundarySections, minSectionY + sectionCount);
                if (highBoundaryFlags >= 0) {
                    bpFlags |= 256;
                } else {
                    residualMap.put("_lca_boundary_high", new Nbt.NbtList(Nbt.TAG_COMPOUND, highBoundarySections));
                }
            }

            byte[] nbtBytes = residualMap.isEmpty() ? new byte[0] : Nbt.writeNamedRoot("", residualMap);
            if (allowDeflateResidual && nbtBytes.length >= 256) {
                ByteArrayOutputStream defOut = new ByteArrayOutputStream(Math.max(128, nbtBytes.length >>> 2));
                Deflater deflater = new Deflater(Deflater.BEST_SPEED);
                try (DeflaterOutputStream dos = new DeflaterOutputStream(defOut, deflater)) {
                    dos.write(nbtBytes);
                } finally {
                    deflater.end();
                }
                byte[] compressedNbt = defOut.toByteArray();
                if (compressedNbt.length < nbtBytes.length) {
                    nbtBytes = compressedNbt;
                    bpFlags |= 4096;
                }
            }
            if (nbtBytes.length > 0) {
                bpFlags |= 1024;
            }
            int mbnlLen = mbnlPrefix == null ? 0 : mbnlPrefix.length;
            if (mbnlLen > 0) {
                bpFlags |= 8192;
            }
            if (bpFlags == 0) {
                return new byte[0];
            }

            ByteArrayOutputStream tailOut = new ByteArrayOutputStream(64 + mbnlLen + nbtBytes.length);
            tailOut.write((bpFlags >>> 8) & 0xFF);
            tailOut.write(bpFlags & 0xFF);
            if ((bpFlags & 8192) != 0) {
                byte[] vBuf = new byte[5];
                int mLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, mbnlLen);
                tailOut.write(vBuf, 0, mLen);
                tailOut.write(mbnlPrefix, 0, mbnlLen);
            }
            if ((bpFlags & 32) != 0) {
                tailOut.write(ppEmptyCount & 0xFF);
            }
            if ((bpFlags & 64) != 0) {
                byte[] vBuf = new byte[5];
                int vLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, starlightVer);
                tailOut.write(vBuf, 0, vLen);
            }
            if ((bpFlags & 128) != 0) {
                tailOut.write(lowBoundaryFlags & 0xFF);
            }
            if ((bpFlags & 256) != 0) {
                tailOut.write(highBoundaryFlags & 0xFF);
            }
            if ((bpFlags & 512) != 0) {
                tailOut.write(secHmKeys.size() & 0xFF);
                byte[] vBuf = new byte[5];
                for (int h = 0; h < secHmKeys.size(); h++) {
                    String hmKey = secHmKeys.get(h);
                    byte[] pay = secHmPayloads.get(h);
                    int code = LcaSectionCodec.encodeStandardHeightmapCode(hmKey);
                    tailOut.write(code & 0xFF);
                    if (code == 0xFF) {
                        byte[] kUtf = hmKey.getBytes(StandardCharsets.UTF_8);
                        int kLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, kUtf.length);
                        tailOut.write(vBuf, 0, kLen);
                        tailOut.writeBytes(kUtf);
                    }
                    int pLen = LcaSectionCodec.writeVarIntToBuf(vBuf, 0, pay.length);
                    tailOut.write(vBuf, 0, pLen);
                    tailOut.writeBytes(pay);
                }
            }
            if (nbtBytes.length > 0) {
                tailOut.writeBytes(nbtBytes);
            }
            return tailOut.toByteArray();
        }

        private static boolean isEmptyNbtList(Object val) {
            return val == null || (val instanceof Nbt.NbtList nl && nl.items.isEmpty());
        }

        private static boolean isEmptyVanillaStructures(Object val) {
            if (!(val instanceof Map<?, ?> m) || m.size() != 2) return false;
            Object refs = m.get("References");
            Object starts = m.get("Starts");
            return refs instanceof Map<?, ?> rm && rm.isEmpty()
                    && starts instanceof Map<?, ?> sm && sm.isEmpty();
        }

        private static int tryEncodeCompactBoundarySection(List<Object> list, int expectedY) {
            if (list.size() != 1 || !(list.get(0) instanceof Map<?, ?> sec)) return -1;
            Object yObj = sec.get("Y");
            if (!(yObj instanceof Number yn) || yn.intValue() != expectedY || sec.containsKey("block_states")) {
                return -1;
            }
            int skyMode = checkUniformLight0Or15(sec.get("SkyLight"));
            int blkMode = checkUniformLight0Or15(sec.get("BlockLight"));
            return (skyMode < 0 || blkMode < 0) ? -1 : (skyMode | (blkMode << 2));
        }

        private static int checkUniformLight0Or15(Object obj) {
            if (obj == null) return 0;
            if (!(obj instanceof byte[] b2048) || b2048.length != 2048) return -1;
            byte first = b2048[0];
            if (first != 0 && first != (byte) 0xFF) return -1;
            long expectedWord = first == 0 ? 0L : -1L;
            for (int off = 0; off < 2048; off += 8) {
                if ((long) LcaRegionReader.LONG_BE_VIEW.get(b2048, off) != expectedWord) {
                    return -1;
                }
            }
            return first == 0 ? 1 : 2;
        }

        static byte[] encodeDeltaHeightmapLongs(long[] longs) {
            if (longs == null || longs.length == 0) {
                return new byte[0];
            }
            byte[] buf = new byte[1 + 256 * 2];
            buf[0] = (byte) longs.length;
            int pos = 1;
            int prev = 0;
            int bits = 9;
            int entriesPerLong = 64 / bits; // 7
            long mask = (1L << bits) - 1L;
            int maxCols = Math.min(256, longs.length * entriesPerLong);
            int c = 0;
            while (c < maxCols) {
                int w = c / entriesPerLong;
                int s = c % entriesPerLong;
                int val = (int) ((longs[w] >>> (s * bits)) & mask);
                int delta = val - prev;
                prev = val;
                if (delta == 0) {
                    int zeroRun = 1;
                    c++;
                    while (c < maxCols && zeroRun < 128) {
                        int nw = c / entriesPerLong;
                        int ns = c % entriesPerLong;
                        int nval = (int) ((longs[nw] >>> (ns * bits)) & mask);
                        if (nval != prev) break;
                        zeroRun++;
                        c++;
                    }
                    buf[pos++] = (byte) (0x80 | (zeroRun - 1));
                } else {
                    int zz = (delta << 1) ^ (delta >> 31);
                    if (zz < 64) {
                        buf[pos++] = (byte) zz;
                    } else {
                        buf[pos++] = (byte) (0x40 | (zz & 0x3F));
                        pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, zz >>> 6);
                    }
                    c++;
                }
            }
            return Arrays.copyOf(buf, pos);
        }

        static byte[] encodeCrossDeltaHeightmapLongs(long[] baseLongs, int refIdx, long[] targetLongs) {
            byte[] buf = new byte[3 + 256 * 2];
            buf[0] = (byte) 0xFD;
            buf[1] = (byte) refIdx;
            buf[2] = (byte) targetLongs.length;
            int pos = 3;
            int bits = 9;
            int entriesPerLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            int c = 0;
            while (c < 256) {
                int w = c / entriesPerLong;
                int s = c % entriesPerLong;
                int baseVal = (int) ((baseLongs[w] >>> (s * bits)) & mask);
                int targVal = (int) ((targetLongs[w] >>> (s * bits)) & mask);
                int delta = targVal - baseVal;
                if (delta == 0) {
                    int zeroRun = 1;
                    c++;
                    while (c < 256 && zeroRun < 128) {
                        int nw = c / entriesPerLong;
                        int ns = c % entriesPerLong;
                        int nb = (int) ((baseLongs[nw] >>> (ns * bits)) & mask);
                        int nt = (int) ((targetLongs[nw] >>> (ns * bits)) & mask);
                        if (nt != nb) break;
                        zeroRun++;
                        c++;
                    }
                    buf[pos++] = (byte) (0x80 | (zeroRun - 1));
                } else {
                    int zz = (delta << 1) ^ (delta >> 31);
                    if (zz < 64) {
                        buf[pos++] = (byte) zz;
                    } else {
                        buf[pos++] = (byte) (0x40 | (zz & 0x3F));
                        pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, zz >>> 6);
                    }
                    c++;
                }
            }
            return Arrays.copyOf(buf, pos);
        }

        private static boolean isAllEmptyShortLists(Nbt.NbtList ppList) {
            if (ppList.items.isEmpty() || ppList.items.size() > 127) return false;
            for (Object item : ppList.items) {
                if (!(item instanceof Nbt.NbtList sub) || !sub.items.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private int[] extractBlockPaletteIds(
                Map<String, Object> sec,
                Map<String, Integer> localStateMap,
                List<String> localStateList,
                List<Integer> localStateGlobalIds
        ) {
            Object bsObj = sec.get("block_states");
            if (!(bsObj instanceof Map<?, ?> bs)) return null;
            Object palObj = bs.get("palette");
            if (!(palObj instanceof Nbt.NbtList palList) || palList.items.isEmpty()) return null;
            int[] out = new int[palList.items.size()];
            for (int i = 0; i < palList.items.size(); i++) {
                String canonical = formatCanonicalStateString(palList.items.get(i));
                if (localStateMap != null) {
                    Integer locId = localStateMap.get(canonical);
                    if (locId == null) {
                        locId = localStateList.size();
                        localStateMap.put(canonical, locId);
                        localStateList.add(canonical);
                        int bracket = canonical.indexOf('[');
                        int globalId = -1;
                        if (bracket >= 0 && !LcaSectionCodec.STATIC_CORE_PROP_TO_ID.containsKey(canonical.substring(bracket))) {
                            globalId = registerOrGetBlockStateId(canonical);
                        }
                        localStateGlobalIds.add(globalId);
                    }
                    out[i] = locId;
                } else {
                    out[i] = registerOrGetBlockStateId(canonical);
                }
            }
            return out;
        }

        private static long[] extractBlockDataLongs(Map<String, Object> sec) {
            Object bsObj = sec.get("block_states");
            if (!(bsObj instanceof Map<?, ?> bs)) return null;
            Object dataObj = bs.get("data");
            return (dataObj instanceof long[] longs) ? longs : null;
        }

        private int[] extractBiomeIds(
                Map<String, Object> sec,
                Map<String, Integer> localBiomeMap,
                List<String> localBiomeList
        ) {
            Object bObj = sec.get("biomes");
            if (!(bObj instanceof Map<?, ?> biomes)) return null;
            Object palObj = biomes.get("palette");
            if (!(palObj instanceof Nbt.NbtList palList) || palList.items.isEmpty()) return null;
            int[] out = new int[palList.items.size()];
            for (int i = 0; i < palList.items.size(); i++) {
                Object item = palList.items.get(i);
                String biomeId = (item instanceof String s) ? s : "minecraft:plains";
                if (localBiomeMap != null) {
                    Integer locId = localBiomeMap.get(biomeId);
                    if (locId == null) {
                        locId = localBiomeList.size();
                        localBiomeMap.put(biomeId, locId);
                        localBiomeList.add(biomeId);
                    }
                    out[i] = locId;
                } else {
                    out[i] = registerOrGetBiomeId(biomeId);
                }
            }
            return out;
        }

        private static long[] extractBiomeDataLongs(Map<String, Object> sec) {
            Object bObj = sec.get("biomes");
            if (!(bObj instanceof Map<?, ?> biomes)) return null;
            Object dataObj = biomes.get("data");
            return (dataObj instanceof long[] longs) ? longs : null;
        }

        @SuppressWarnings("unchecked")
        static String formatCanonicalStateString(Object entry) {
            if (entry instanceof String s) return s;
            if (!(entry instanceof Map<?, ?> m)) return "minecraft:air";
            Object nameObj = m.get("Name");
            if (nameObj == null) nameObj = m.get("id");
            if (nameObj == null) nameObj = m.get("");
            String name = (nameObj instanceof String s) ? s : "minecraft:air";
            Object propsObj = m.get("Properties");
            if (propsObj == null) propsObj = m.get("properties");
            if (!(propsObj instanceof Map<?, ?> props) || props.isEmpty()) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name.length() + 32);
            sb.append(name).append('[');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) props).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append(']');
            return sb.toString();
        }

        /**
         * Registers a `BlockState` in the dynamically shape-able region dictionary (`Section 3.1`).
         */
        public int registerOrGetBlockStateId(String canonicalState) {
            Integer existing = blockStateToId.get(canonicalState);
            if (existing != null) {
                return existing;
            }
            return registerNewBlockStateId(canonicalState);
        }

        private synchronized int registerNewBlockStateId(String canonicalState) {
            Integer existing = blockStateToId.get(canonicalState);
            if (existing != null) {
                return existing;
            }
            byte[] utfBytes = canonicalState.getBytes(StandardCharsets.UTF_8);
            byte[] rawEntry = new byte[LcaSectionCodec.varIntLen(utfBytes.length) + utfBytes.length + 4];
            int pos = LcaSectionCodec.writeVarIntToBuf(rawEntry, 0, utfBytes.length);
            System.arraycopy(utfBytes, 0, rawEntry, pos, utfBytes.length);
            pos += utfBytes.length;
            int materialPacked = computeLeafMapMaterialPacked(canonicalState);
            rawEntry[pos] = (byte) ((materialPacked >>> 24) & 0xFF);
            rawEntry[pos + 1] = (byte) ((materialPacked >>> 16) & 0xFF);
            rawEntry[pos + 2] = (byte) ((materialPacked >>> 8) & 0xFF);
            rawEntry[pos + 3] = (byte) (materialPacked & 0xFF);

            DictSegmentMeta activeSeg = ensureDictSegmentCapacity(rawEntry.length);
            activeSeg.stateEntries.add(rawEntry);
            activeSeg.usedPayloadBytes += rawEntry.length;
            try {
                flushDictSegment(activeSeg);
            } catch (RuntimeException | Error e) {
                activeSeg.stateEntries.remove(activeSeg.stateEntries.size() - 1);
                activeSeg.usedPayloadBytes -= rawEntry.length;
                throw e;
            }
            int newId = idToBlockState.size();
            idToBlockState.add(canonicalState);
            blockStateToId.put(canonicalState, newId);
            return newId;
        }

        public int registerOrGetBiomeId(String biomeId) {
            Integer existing = biomeToId.get(biomeId);
            if (existing != null) {
                return existing;
            }
            return registerNewBiomeId(biomeId);
        }

        private synchronized int registerNewBiomeId(String biomeId) {
            Integer existing = biomeToId.get(biomeId);
            if (existing != null) {
                return existing;
            }
            byte[] utfBytes = biomeId.getBytes(StandardCharsets.UTF_8);
            byte[] rawEntry = new byte[LcaSectionCodec.varIntLen(utfBytes.length) + utfBytes.length + 4];
            int pos = LcaSectionCodec.writeVarIntToBuf(rawEntry, 0, utfBytes.length);
            System.arraycopy(utfBytes, 0, rawEntry, pos, utfBytes.length);
            pos += utfBytes.length;
            int tint = 0x5E9D34FF;
            rawEntry[pos] = (byte) ((tint >>> 24) & 0xFF);
            rawEntry[pos + 1] = (byte) ((tint >>> 16) & 0xFF);
            rawEntry[pos + 2] = (byte) ((tint >>> 8) & 0xFF);
            rawEntry[pos + 3] = (byte) (tint & 0xFF);

            DictSegmentMeta activeSeg = ensureDictSegmentCapacity(rawEntry.length);
            activeSeg.biomeEntries.add(rawEntry);
            activeSeg.usedPayloadBytes += rawEntry.length;
            try {
                flushDictSegment(activeSeg);
            } catch (RuntimeException | Error e) {
                activeSeg.biomeEntries.remove(activeSeg.biomeEntries.size() - 1);
                activeSeg.usedPayloadBytes -= rawEntry.length;
                throw e;
            }
            int newId = idToBiome.size();
            idToBiome.add(biomeId);
            biomeToId.put(biomeId, newId);
            return newId;
        }

        private DictSegmentMeta ensureDictSegmentCapacity(int entryBytes) {
            DictSegmentMeta current = dictSegments.get(dictSegments.size() - 1);
            if (current.availableSlackBytes() >= entryBytes) {
                return current;
            }
            // Geometric continuation segment sizing (1 KiB .. 16 KiB) so large dictionaries stay well below 64 segments
            int grownCap = Math.min(16384, Math.max(MIN_CONTINUATION_DICT_CAPACITY_BYTES, current.capacityBytes() * 2));
            int contBytes = Math.max(grownCap, (entryBytes + 64 + 255) & ~255);
            int contSlabs = contBytes >>> LcaRegionReader.SLAB_SHIFT;
            int prevActiveEndSlab = this.activeEndSlab;
            int prevLastSyncedFooter = this.lastSyncedFooterOffset;
            long prevTrailer = current.linkTrailer;
            int nextSlabStart = allocateSlabs(contSlabs, false);
            if (!headerOnlyChannelMode) {
                ensureBufferCapacity((nextSlabStart + contSlabs) << LcaRegionReader.SLAB_SHIFT);
            }

            DictSegmentMeta nextSeg = new DictSegmentMeta(
                    nextSlabStart << LcaRegionReader.SLAB_SHIFT,
                    contSlabs << LcaRegionReader.SLAB_SHIFT);
            try {
                flushDictSegment(nextSeg);
                int relSlabs32 = nextSlabStart - current.baseSlab();
                long linkTrailer = ((long) relSlabs32 << 32)
                        | ((contSlabs & 0xFFFFFFL) << 8)
                        | 1L;
                current.linkTrailer = linkTrailer;
                flushDictSegment(current);
                dictSegments.add(nextSeg);
                return nextSeg;
            } catch (RuntimeException | Error e) {
                current.linkTrailer = prevTrailer;
                rollbackAllocatedSlabs(nextSlabStart, contSlabs, prevActiveEndSlab, prevLastSyncedFooter, false);
                throw e;
            }
        }

        private void rollbackDictionaryToBookmark(int stateBookmark, int biomeBookmark, int segBookmark) {
            boolean modified = false;
            while (idToBlockState.size() > stateBookmark) {
                String removed = idToBlockState.remove(idToBlockState.size() - 1);
                blockStateToId.remove(removed);
                for (int s = dictSegments.size() - 1; s >= 0; s--) {
                    DictSegmentMeta seg = dictSegments.get(s);
                    if (!seg.stateEntries.isEmpty()) {
                        byte[] entry = seg.stateEntries.remove(seg.stateEntries.size() - 1);
                        seg.usedPayloadBytes -= entry.length;
                        modified = true;
                        break;
                    }
                }
            }
            while (idToBiome.size() > biomeBookmark) {
                String removed = idToBiome.remove(idToBiome.size() - 1);
                biomeToId.remove(removed);
                for (int s = dictSegments.size() - 1; s >= 0; s--) {
                    DictSegmentMeta seg = dictSegments.get(s);
                    if (!seg.biomeEntries.isEmpty()) {
                        byte[] entry = seg.biomeEntries.remove(seg.biomeEntries.size() - 1);
                        seg.usedPayloadBytes -= entry.length;
                        modified = true;
                        break;
                    }
                }
            }
            while (dictSegments.size() > segBookmark) {
                dictSegments.remove(dictSegments.size() - 1);
                modified = true;
            }
            if (modified && !dictSegments.isEmpty()) {
                DictSegmentMeta tail = dictSegments.get(dictSegments.size() - 1);
                tail.linkTrailer = 0L;
                try {
                    flushDictSegment(tail);
                } catch (RuntimeException ignored) {
                }
            }
        }

        private void flushDictSegment(DictSegmentMeta seg) {
            int offset = seg.byteOffset();
            int capBytes = seg.capacityBytes();
            cachedParsedDict = null;
            dictModifiedSinceSync = true;

            byte[] staged = new byte[capBytes];
            ByteBuffer bb = ByteBuffer.wrap(staged);
            bb.putInt(0, seg.stateEntries.size());
            bb.putShort(4, (short) seg.biomeEntries.size());
            bb.putShort(6, (short) seg.usedPayloadBytes);

            int pos = 8;
            for (byte[] sEntry : seg.stateEntries) {
                System.arraycopy(sEntry, 0, staged, pos, sEntry.length);
                pos += sEntry.length;
            }
            for (byte[] bEntry : seg.biomeEntries) {
                System.arraycopy(bEntry, 0, staged, pos, bEntry.length);
                pos += bEntry.length;
            }
            bb.putLong(capBytes - 8, seg.linkTrailer);
            if (!headerOnlyChannelMode) {
                ensureBufferCapacity(offset + capBytes + LcaRegionReader.FOOTER_SIZE);
                System.arraycopy(staged, 0, buffer, offset, capBytes);
            } else if (offset + capBytes <= buffer.length) {
                System.arraycopy(staged, 0, buffer, offset, capBytes);
            }
            VarHandle.releaseFence();
            writeChannelRangeUnchecked(offset, staged, 0, capBytes);
        }

        private int allocateSlabs(int neededSlabs, boolean trackTelemetry) {
            NavigableMap<Integer, TreeSet<Integer>> tail = freeBySize.tailMap(neededSlabs, true);
            if (!tail.isEmpty()) {
                Map.Entry<Integer, TreeSet<Integer>> bestFitEntry = tail.firstEntry();
                int availSlabs = bestFitEntry.getKey();
                TreeSet<Integer> starts = bestFitEntry.getValue();
                int chosenStart = starts.pollFirst();
                if (starts.isEmpty()) {
                    freeBySize.remove(availSlabs);
                }
                freeByStart.remove(chosenStart);
                int remainder = availSlabs - neededSlabs;
                if (remainder > 0) {
                    insertFreeInterval(chosenStart + neededSlabs, remainder);
                }
                if (trackTelemetry) {
                    totalSaves++;
                    reusedSlabSaves++;
                }
                return chosenStart;
            }

            int chosenStart = activeEndSlab;
            int nextEndSlab = chosenStart + neededSlabs;
            if (!headerOnlyChannelMode) {
                ensureBufferCapacity((nextEndSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE);
                LcaRegionReader.setVolatileLongBE(
                        buffer, nextEndSlab << LcaRegionReader.SLAB_SHIFT, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
            }
            VarHandle.releaseFence();
            activeEndSlab = nextEndSlab;
            if (trackTelemetry) {
                totalSaves++;
                eofAppendSaves++;
            }
            return chosenStart;
        }

        private void rollbackAllocatedSlabs(
                int allocatedStart,
                int allocatedCount,
                int prevActiveEndSlab,
                int prevLastSyncedFooter,
                boolean trackTelemetry
        ) {
            boolean wasEofAppend = (allocatedStart == prevActiveEndSlab
                    && activeEndSlab == prevActiveEndSlab + allocatedCount);
            if (trackTelemetry) {
                totalSaves = Math.max(0L, totalSaves - 1L);
                if (wasEofAppend) {
                    eofAppendSaves = Math.max(0L, eofAppendSaves - 1L);
                } else {
                    reusedSlabSaves = Math.max(0L, reusedSlabSaves - 1L);
                }
            }
            if (wasEofAppend) {
                activeEndSlab = prevActiveEndSlab;
                lastSyncedFooterOffset = prevLastSyncedFooter;
                int prevFooterOffset = prevActiveEndSlab << LcaRegionReader.SLAB_SHIFT;
                if (!headerOnlyChannelMode && prevFooterOffset + LcaRegionReader.FOOTER_SIZE <= buffer.length) {
                    LcaRegionReader.setVolatileLongBE(
                            buffer, prevFooterOffset, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                }
                if (channel != null && channel.isOpen()) {
                    try {
                        long targetSize = activeByteLength();
                        if (channel.size() > targetSize) {
                            channel.truncate(targetSize);
                        }
                        byte[] canary = new byte[LcaRegionReader.FOOTER_SIZE];
                        LcaRegionReader.setVolatileLongBE(canary, 0, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                        writeChannelRange(prevFooterOffset, canary, 0, LcaRegionReader.FOOTER_SIZE);
                        lastSyncedFooterOffset = prevFooterOffset;
                    } catch (IOException ignored) {
                    }
                }
            } else {
                returnFreeSlabs(allocatedStart, allocatedCount);
            }
        }

        private void returnFreeSlabs(int slabStart, int slabCount) {
            if (slabCount <= 0) return;
            int mergedStart = slabStart;
            int mergedCount = slabCount;

            Map.Entry<Integer, Integer> prev = freeByStart.floorEntry(slabStart);
            if (prev != null && prev.getKey() + prev.getValue() == slabStart) {
                removeFreeInterval(prev.getKey(), prev.getValue());
                mergedStart = prev.getKey();
                mergedCount += prev.getValue();
            }

            Map.Entry<Integer, Integer> next = freeByStart.ceilingEntry(slabStart + slabCount);
            if (next != null && mergedStart + mergedCount == next.getKey()) {
                removeFreeInterval(next.getKey(), next.getValue());
                mergedCount += next.getValue();
            }

            insertFreeInterval(mergedStart, mergedCount);
        }

        private void insertFreeInterval(int start, int count) {
            freeByStart.put(start, count);
            freeBySize.computeIfAbsent(count, k -> new TreeSet<>()).add(start);
        }

        private void removeFreeInterval(int start, int count) {
            freeByStart.remove(start);
            TreeSet<Integer> set = freeBySize.get(count);
            if (set != null) {
                set.remove(start);
                if (set.isEmpty()) {
                    freeBySize.remove(count);
                }
            }
        }

        private void writeSlabBytes(int startSlab, byte[] payload) {
            int byteOffset = startSlab << LcaRegionReader.SLAB_SHIFT;
            int paddedBytes = ((payload.length + 255) >>> LcaRegionReader.SLAB_SHIFT) << LcaRegionReader.SLAB_SHIFT;
            int footerOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
            if (headerOnlyChannelMode) {
                if (channel != null
                        && channel.isOpen()
                        && byteOffset + paddedBytes == footerOffset
                        && footerOffset != lastSyncedFooterOffset) {
                    byte[] paddedWithFooter = Arrays.copyOf(payload, paddedBytes + LcaRegionReader.FOOTER_SIZE);
                    LcaRegionReader.setVolatileLongBE(
                            paddedWithFooter, paddedBytes, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                    writeChannelRangeUnchecked(
                            byteOffset, paddedWithFooter, 0, paddedBytes + LcaRegionReader.FOOTER_SIZE);
                    lastSyncedFooterOffset = footerOffset;
                    return;
                }
                if (paddedBytes == payload.length) {
                    writeChannelRangeUnchecked(byteOffset, payload, 0, payload.length);
                } else {
                    byte[] padded = Arrays.copyOf(payload, paddedBytes);
                    writeChannelRangeUnchecked(byteOffset, padded, 0, paddedBytes);
                }
                return;
            }
            ensureBufferCapacity(byteOffset + paddedBytes + LcaRegionReader.FOOTER_SIZE);
            System.arraycopy(payload, 0, buffer, byteOffset, payload.length);
            if (paddedBytes > payload.length) {
                Arrays.fill(buffer, byteOffset + payload.length, byteOffset + paddedBytes, (byte) 0);
            }
            if (channel != null
                    && channel.isOpen()
                    && byteOffset + paddedBytes == footerOffset
                    && footerOffset != lastSyncedFooterOffset) {
                LcaRegionReader.setVolatileLongBE(buffer, footerOffset, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                writeChannelRangeUnchecked(byteOffset, buffer, byteOffset, paddedBytes + LcaRegionReader.FOOTER_SIZE);
                lastSyncedFooterOffset = footerOffset;
                return;
            }
            writeChannelRangeUnchecked(byteOffset, buffer, byteOffset, paddedBytes);
        }

        private void ensureBufferCapacity(int minCapacity) {
            if (minCapacity <= buffer.length) return;
            int step = Math.min(Math.max(buffer.length >>> 2, 16384), 262144);
            int newCap = Math.max(minCapacity, buffer.length + step);
            buffer = Arrays.copyOf(buffer, newCap);
        }

        private void writeSuperblockAndFooter() {
            int slabPoolBaseOffset = slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT;
            ensureBufferCapacity(slabPoolBaseOffset + LcaRegionReader.FOOTER_SIZE);
            ByteBuffer bb = ByteBuffer.wrap(buffer, 0, LcaRegionReader.SUPERBLOCK_SIZE);
            bb.putLong(0x00, LcaRegionReader.LCA_SUPERBLOCK_MAGIC);
            bb.putShort(0x08, (short) LcaRegionReader.LCA_FORMAT_VERSION);
            buffer[0x0A] = (byte) storageKind;
            buffer[0x0B] = (byte) (1 | (modeBZstd ? 8 : 0) | 16); // HAS_SHARED_DICT | optional DEFAULT_ZSTD1 | HAS_SECDED_PARITY
            bb.putInt(0x0C, regionX);
            bb.putInt(0x10, regionZ);
            bb.putInt(0x14, minSectionY);
            bb.putShort(0x18, (short) sectionCount);
            bb.putShort(0x1A, (short) LcaRegionReader.SLAB_SHIFT);
            bb.putInt(0x1C, defaultDataVersion);
            long dictOffsetAndLen = ((long) rootDictCapacityBytes << 32) | (LcaRegionReader.DICT_ROOT_OFFSET & 0xFFFF_FFFFL);
            bb.putLong(0x20, dictOffsetAndLen);
            long lodAndSecDed = ((long) LcaRegionReader.SECDED_TABLE_SIZE << 32) | (secDedTableOffset & 0xFFFF_FFFFL);
            bb.putLong(0x28, lodAndSecDed);
            bb.putLong(0x30, slabPoolBaseOffset);
            long checksum = LcaSectionCodec.computeSuperblockChecksum64(buffer, 0);
            bb.putLong(0x38, checksum);
            writeFooterCanary();
        }

        private void writeFooterCanary() {
            int footerOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
            if (headerOnlyChannelMode) {
                if (channel != null && channel.isOpen() && footerOffset != lastSyncedFooterOffset) {
                    byte[] canary = new byte[LcaRegionReader.FOOTER_SIZE];
                    LcaRegionReader.setVolatileLongBE(canary, 0, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                    writeChannelRangeUnchecked(footerOffset, canary, 0, LcaRegionReader.FOOTER_SIZE);
                    lastSyncedFooterOffset = footerOffset;
                }
                return;
            }
            ensureBufferCapacity(footerOffset + LcaRegionReader.FOOTER_SIZE);
            LcaRegionReader.setVolatileLongBE(buffer, footerOffset, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
            if (channel != null && channel.isOpen() && footerOffset != lastSyncedFooterOffset) {
                writeChannelRangeUnchecked(footerOffset, buffer, footerOffset, LcaRegionReader.FOOTER_SIZE);
                lastSyncedFooterOffset = footerOffset;
            }
        }
    }

    static long packChunkPointer64(long slabOffset35, boolean modeBZstd, int exactByteLen20, int generation8) {
        return (slabOffset35 << 29)
                | ((modeBZstd ? 1L : 0L) << 28)
                | ((exactByteLen20 & 0xFFFFFL) << 8)
                | (generation8 & 0xFFL);
    }

    private static int computeLeafMapMaterialPacked(String canonicalState) {
        if (canonicalState.startsWith("minecraft:air") || canonicalState.endsWith("_air")) return 0x00000000;
        if (canonicalState.contains("water")) return 0x2B65EC01;
        if (canonicalState.contains("lava") || canonicalState.contains("glowstone")) return 0xF57C0004;
        if (canonicalState.contains("leaves") || canonicalState.contains("grass")) return 0x5E9D3402;
        if (canonicalState.contains("deepslate")) return 0x4B4B5208;
        return 0x7D7D7D08;
    }

    @FunctionalInterface
    interface ChunkBytesSupplier {
        byte[] get() throws IOException;
    }

    /**
     * Lazy {@link InputStream} that defers `.lca -> Mojang NBT` decoding until the first
     * {@link #read()} or {@link #read(byte[], int, int)} invocation on the caller's decompression
     * worker thread (Paper Moonrise / `C2ME` `GlobalExecutors`), keeping the single-threaded
     * `RegionFile` I/O coordinator (`C2MEStorageThread` / `RegionFileIOWorker`) $O(1)$ and non-blocking.
     */
    static final class LazyChunkNbtInputStream extends InputStream {
        private final ChunkBytesSupplier supplier;
        private byte[] buf;
        private int pos;

        LazyChunkNbtInputStream(ChunkBytesSupplier supplier) {
            this.supplier = supplier;
        }

        private byte[] ensureDecoded() throws IOException {
            byte[] b = this.buf;
            if (b == null) {
                b = supplier.get();
                if (b == null) {
                    b = new byte[0];
                }
                this.buf = b;
            }
            return b;
        }

        @Override
        public int read() throws IOException {
            byte[] b = ensureDecoded();
            return (pos < b.length) ? (b[pos++] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] dst, int off, int len) throws IOException {
            if (dst == null) {
                throw new NullPointerException();
            }
            if (off < 0 || len < 0 || len > dst.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            byte[] b = ensureDecoded();
            if (pos >= b.length) {
                return -1;
            }
            int avail = Math.min(len, b.length - pos);
            System.arraycopy(b, pos, dst, off, avail);
            pos += avail;
            return avail;
        }

        @Override
        public int available() throws IOException {
            byte[] b = ensureDecoded();
            return Math.max(0, b.length - pos);
        }
    }
}

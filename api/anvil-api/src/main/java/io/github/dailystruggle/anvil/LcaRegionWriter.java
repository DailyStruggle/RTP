package io.github.dailystruggle.anvil;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.VarHandle;
import java.lang.ref.SoftReference;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
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
        return requestedModeB;
    }

    static int normalizeLcd2Level(int lcd2Level) {
        return lcd2Level <= 0 ? 0 : Math.min(22, lcd2Level);
    }

    /**
     * Transcodes a vanilla `.mca` region buffer into `.lca` (`Mode A: Uncompressed direct random access` when
     * `modeBLcd2 == false`, or `Mode B: + LCD2 0x4C44` when `modeBLcd2 == true`).
     */
    public static byte[] transcodeFromMca(byte[] mcaBytes, int regionX, int regionZ, boolean modeBLcd2) throws IOException {
        int level = modeBLcd2 ? 1 : 0;
        return transcodeFromReader(AnvilReader.INSTANCE, mcaBytes, regionX, regionZ, level);
    }

    /**
     * Transcodes a vanilla `.mca` region buffer into `.lca` at the specified `lcd2Level` (`0` for Mode A uncompressed direct random access, `>= 1` for Mode B `LCD2` `0x4C44`; `ADR-115`, `S-005` off-thread).
     */
    public static byte[] transcodeFromMca(byte[] mcaBytes, int regionX, int regionZ, int lcd2Level) throws IOException {
        return transcodeFromReader(AnvilReader.INSTANCE, mcaBytes, regionX, regionZ, lcd2Level);
    }

    static byte[] transcodeFromMcaWithVersion(
            byte[] mcaBytes, int regionX, int regionZ, int lcd2Level, int formatVersion
    ) throws IOException {
        return transcodeFromReaderWithVersion(
                AnvilReader.INSTANCE, mcaBytes, regionX, regionZ, lcd2Level,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL, formatVersion);
    }

    /**
     * Transcodes all generated chunks from any {@link RegionFileReader} (`.mca`, `.linear`) into a
     * compact `.lca` region byte array.
     */
    public static byte[] transcodeFromReader(
            RegionFileReader reader, byte[] srcBytes, int regionX, int regionZ, boolean modeBLcd2
    ) throws IOException {
        return transcodeFromReader(
                reader, srcBytes, regionX, regionZ, modeBLcd2 ? 1 : 0, LcaRegionReader.STORAGE_KIND_REGION_VOXEL);
    }

    /**
     * Transcodes all generated chunks from any {@link RegionFileReader} into `.lca` at `lcd2Level` (`ADR-115`, `S-005` off-thread).
     */
    public static byte[] transcodeFromReader(
            RegionFileReader reader, byte[] srcBytes, int regionX, int regionZ, int lcd2Level
    ) throws IOException {
        return transcodeFromReader(
                reader, srcBytes, regionX, regionZ, lcd2Level, LcaRegionReader.STORAGE_KIND_REGION_VOXEL);
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
            boolean modeBLcd2,
            int storageKind
    ) throws IOException {
        return transcodeFromReader(reader, srcBytes, regionX, regionZ, modeBLcd2 ? 1 : 0, storageKind);
    }

    public static byte[] transcodeFromReader(
            RegionFileReader reader,
            byte[] srcBytes,
            int regionX,
            int regionZ,
            int lcd2Level,
            int storageKind
    ) throws IOException {
        return transcodeFromReaderWithVersion(
                reader, srcBytes, regionX, regionZ, lcd2Level, storageKind, LcaRegionReader.LCA_FORMAT_VERSION);
    }

    static byte[] transcodeFromReaderWithVersion(
            RegionFileReader reader,
            byte[] srcBytes,
            int regionX,
            int regionZ,
            int lcd2Level,
            int storageKind,
            int formatVersion
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
        LcaLiveArchive archive = createEmptyLiveArchiveWithVersion(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, lcd2Level,
                storageKind, DEFAULT_ROOT_DICT_CAPACITY_BYTES, formatVersion);
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
     * Creates a new empty mutable `.lca` live archive with a pre-allocated root dictionary slack
     * arena (`1,024 B`) and `TreeMap` `256B` sub-slab allocator.
     */
    public static LcaLiveArchive createEmptyLiveArchive(
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBLcd2
    ) {
        return createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, modeBLcd2,
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
            boolean modeBLcd2,
            int rootDictCapacityBytes
    ) {
        return createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, modeBLcd2,
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
            boolean modeBLcd2,
            int storageKind,
            int rootDictCapacityBytes
    ) {
        return createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                modeBLcd2 ? 1 : 0, storageKind, rootDictCapacityBytes);
    }

    // Factory method: caller owns and closes the returned LcaLiveArchive
    @SuppressWarnings({"java:S2095", "resource"})
    public static LcaLiveArchive createEmptyLiveArchive(
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int lcd2Level,
            int storageKind,
            int rootDictCapacityBytes
    ) {
        int alignedDictCap = Math.max(256, (rootDictCapacityBytes + 255) & ~255);
        return new LcaLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, lcd2Level, storageKind, alignedDictCap);
    }

    @SuppressWarnings({"java:S2095", "resource"})
    static LcaLiveArchive createEmptyLiveArchiveWithVersion(
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int lcd2Level,
            int storageKind,
            int rootDictCapacityBytes,
            int formatVersion
    ) {
        int alignedDictCap = Math.max(256, (rootDictCapacityBytes + 255) & ~255);
        return new LcaLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                lcd2Level, storageKind, alignedDictCap, formatVersion);
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
            boolean modeBLcd2
    ) throws IOException {
        return createEmptyLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, modeBLcd2 ? 1 : 0,
                LcaRegionReader.STORAGE_KIND_REGION_VOXEL, DEFAULT_ROOT_DICT_CAPACITY_BYTES);
    }

    public static LcaLiveArchive createEmptyLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBLcd2,
            int storageKind,
            int rootDictCapacityBytes
    ) throws IOException {
        return createEmptyLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                modeBLcd2 ? 1 : 0, storageKind, rootDictCapacityBytes);
    }

    // Factory method: caller owns and closes the returned LcaLiveArchive
    @SuppressWarnings({"java:S2095", "resource"})
    public static LcaLiveArchive createEmptyLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int lcd2Level,
            int storageKind,
            int rootDictCapacityBytes
    ) throws IOException {
        LcaLiveArchive archive = createEmptyLiveArchive(
                regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, lcd2Level,
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
    // Factory method: caller owns and closes the returned LcaLiveArchive
    @SuppressWarnings({"java:S2095", "resource"})
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

    /**
     * Off-thread `.mca` region block patcher (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    static boolean patchMcaRegionFile(
            Path regionFile,
            int cx,
            int cz,
            java.util.Collection<RegionFileReader.BlockPatch> patches,
            java.util.function.Predicate<String> isReplaceable) throws IOException {
        if (regionFile == null || !Files.isRegularFile(regionFile)) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(
                regionFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = ch.size();
            if (size < 8192L || size > Integer.MAX_VALUE) {
                return false;
            }
            byte[] regionBytes = new byte[(int) size];
            ByteBuffer readBuf = ByteBuffer.wrap(regionBytes);
            while (readBuf.hasRemaining()) {
                if (ch.read(readBuf) < 0) {
                    break;
                }
            }
            byte[] updated = AnvilReader.patchChunkInMcaBytes(
                    regionBytes, cx, cz, patches, isReplaceable);
            if (updated == null || updated.length == 0) {
                return false;
            }
            if (updated != regionBytes) {
                ch.position(0L);
                ByteBuffer writeBuf = ByteBuffer.wrap(updated);
                while (writeBuf.hasRemaining()) {
                    ch.write(writeBuf);
                }
                ch.truncate(updated.length);
            }
            return true;
        }
    }

    /**
     * Synchronous crash-durable atomic region file writer (`REQ-RTP-S-005`).
     * Writes `bytes` to `tmpFile`, forces `tmpFile` to stable storage, atomically renames over
     * `targetFile`, and syncs the parent directory entry on POSIX filesystems.
     *
     * <p>Callers must already be off any Minecraft primary or Folia region thread, or use
     * {@link #writeBytesAtomicallyDurableAsync(Path, Path, byte[], java.util.concurrent.Executor)}.
     */
    public static void writeBytesAtomicallyDurable(
            Path targetFile, Path tmpFile, byte[] bytes
    ) throws IOException {
        java.util.Objects.requireNonNull(targetFile, "targetFile");
        java.util.Objects.requireNonNull(tmpFile, "tmpFile");
        java.util.Objects.requireNonNull(bytes, "bytes");
        writeBytesAtomicallyDurableOnWorker(targetFile, tmpFile, bytes);
    }

    /**
     * Asynchronous crash-durable atomic region file writer (`REQ-RTP-S-005`). Dispatches
     * {@link #writeBytesAtomicallyDurable(Path, Path, byte[])} onto the caller-supplied
     * lifecycle-managed blocking-I/O {@link java.util.concurrent.Executor} without blocking the
     * calling thread.
     */
    public static java.util.concurrent.CompletableFuture<Void> writeBytesAtomicallyDurableAsync(
            Path targetFile, Path tmpFile, byte[] bytes, java.util.concurrent.Executor executor
    ) {
        java.util.Objects.requireNonNull(targetFile, "targetFile");
        java.util.Objects.requireNonNull(tmpFile, "tmpFile");
        java.util.Objects.requireNonNull(bytes, "bytes");
        java.util.Objects.requireNonNull(executor, "executor");
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                writeBytesAtomicallyDurableOnWorker(targetFile, tmpFile, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);
    }

    private static void writeBytesAtomicallyDurableOnWorker(
            Path targetFile, Path tmpFile, byte[] bytes
    ) throws IOException {
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmpParent = tmpFile.getParent();
        if (tmpParent != null && !tmpParent.equals(parent)) {
            Files.createDirectories(tmpParent);
        }
        try {
            writeAndForceTempChannel(tmpFile, bytes);
            moveAtomicallyWithFallback(tmpFile, targetFile);
        } catch (IOException | RuntimeException | Error t) {
            cleanupTempFileAfterFailure(tmpFile, t);
            throw t;
        }
        syncPosixDirectoryBestEffort(parent);
    }

    private static void writeAndForceTempChannel(Path tmpFile, byte[] bytes) throws IOException {
        try (FileChannel ch = FileChannel.open(
                tmpFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            while (bb.hasRemaining()) {
                int written = ch.write(bb);
                if (written <= 0) {
                    throw new IOException("Temporary region file write made no progress: " + tmpFile);
                }
            }
            ch.force(true);
        }
    }

    private static void moveAtomicallyWithFallback(Path tmpFile, Path targetFile) throws IOException {
        try {
            Files.move(
                    tmpFile,
                    targetFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(
                    tmpFile,
                    targetFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void syncPosixDirectoryBestEffort(Path parent) {
        if (parent != null && parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try (FileChannel dirCh = FileChannel.open(parent, StandardOpenOption.READ)) {
                dirCh.force(true);
            } catch (IOException | UnsupportedOperationException dirSyncEx) {
                java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName()).log(
                        java.util.logging.Level.FINE,
                        "Parent directory fsync unsupported for " + parent + ": " + dirSyncEx.getMessage(),
                        dirSyncEx);
            }
        }
    }

    private static void cleanupTempFileAfterFailure(Path tmpFile, Throwable primary) {
        try {
            Files.deleteIfExists(tmpFile);
        } catch (IOException cleanupEx) {
            primary.addSuppressed(cleanupEx);
        }
    }

    private static final String OPEN_LIVE_ARCHIVES_PROP =
            "leafcraft.anvil.openLiveArchives";

    @SuppressWarnings("unchecked")
    private static Map<Path, Object> openLiveArchivesRegistry() {
        java.util.Properties props = System.getProperties();
        synchronized (props) {
            Object existing = props.get(OPEN_LIVE_ARCHIVES_PROP);
            if (existing instanceof Map<?, ?> map) {
                return (Map<Path, Object>) map;
            }
            Map<Path, Object> created = new ConcurrentHashMap<>();
            props.put(OPEN_LIVE_ARCHIVES_PROP, created);
            return created;
        }
    }

    static void registerOpenLiveArchive(Path lcaFile, Object archive) {
        if (lcaFile == null || archive == null) {
            return;
        }
        openLiveArchivesRegistry().putIfAbsent(lcaFile.toAbsolutePath().normalize(), archive);
    }

    static void unregisterOpenLiveArchive(Path lcaFile, Object archive) {
        if (lcaFile == null || archive == null) {
            return;
        }
        openLiveArchivesRegistry().remove(lcaFile.toAbsolutePath().normalize(), archive);
    }

    /**
     * If {@code regionFile} is currently held open by an active {@link LcaLiveArchive} in this JVM
     * (including across shaded plugin classloaders such as {@code LeafAnvil}), patches chunk
     * {@code (rx, rz)} directly through that synchronized live archive instance and returns
     * {@link Boolean#TRUE} or {@link Boolean#FALSE}. Returns {@code null} when no open live archive
     * owns {@code regionFile}.
     */
    @SuppressWarnings("java:S1168")
    static Boolean tryPatchOpenLiveArchive(
            Path regionFile,
            int rx,
            int rz,
            java.util.Collection<RegionFileReader.BlockPatch> patches,
            java.util.function.Predicate<String> isReplaceable) throws IOException {
        if (regionFile == null) {
            return null;
        }
        Path norm = regionFile.toAbsolutePath().normalize();
        Object openArchive = openLiveArchivesRegistry().get(norm);
        if (openArchive == null) {
            return null;
        }
        synchronized (openArchive) {
            return invokeOpenArchivePatch(norm, openArchive, rx, rz, patches, isReplaceable);
        }
    }

    @SuppressWarnings("java:S1168")
    private static Boolean invokeOpenArchivePatch(
            Path norm,
            Object openArchive,
            int rx,
            int rz,
            java.util.Collection<RegionFileReader.BlockPatch> patches,
            java.util.function.Predicate<String> isReplaceable) throws IOException {
        try {
            Class<?> cls = openArchive.getClass();
            boolean isOpen = (boolean) cls.getMethod("isChannelOpen").invoke(openArchive);
            if (!isOpen) {
                unregisterOpenLiveArchive(norm, openArchive);
                return null;
            }
            boolean has = (boolean) cls.getMethod("hasChunk", int.class, int.class)
                    .invoke(openArchive, rx, rz);
            if (!has) {
                return Boolean.FALSE;
            }
            byte[] rawNbt = (byte[]) cls.getMethod("readChunkNbtBytes", int.class, int.class)
                    .invoke(openArchive, rx, rz);
            return applyAndSaveOpenArchiveNbt(cls, openArchive, norm, rx, rz, rawNbt, patches, isReplaceable);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Failed to patch open live .lca archive " + norm, ite.getCause());
        } catch (ReflectiveOperationException roe) {
            return null;
        }
    }

    private static Boolean applyAndSaveOpenArchiveNbt(
            Class<?> cls,
            Object openArchive,
            Path norm,
            int rx,
            int rz,
            byte[] rawNbt,
            java.util.Collection<RegionFileReader.BlockPatch> patches,
            java.util.function.Predicate<String> isReplaceable)
            throws IOException, ReflectiveOperationException {
        if (rawNbt == null || rawNbt.length == 0) {
            return Boolean.FALSE;
        }
        LinkedHashMap<String, Object> root = Nbt.readRootCompound(rawNbt);
        if (!RegionFileReader.isFullChunkRoot(root)) {
            return Boolean.FALSE;
        }
        if (patches != null && !patches.isEmpty()
                && RegionFileReader.applyPatchesToChunkRoot(root, patches, isReplaceable)) {
            byte[] updatedNbt = Nbt.writeNamedRoot("", root);
            cls.getMethod("saveChunkNbtBytes", int.class, int.class, byte[].class)
                    .invoke(openArchive, rx, rz, updatedNbt);
            LcaRegionReader.invalidateFileCache(norm);
        }
        return Boolean.TRUE;
    }

    /**
     * Reads the 2-byte big-endian {@code formatVersion} from an on-disk `.lca` superblock
     * without mutating the file, or returns {@code -1} if the file does not exist, is too short,
     * or does not start with {@link LcaRegionReader#LCA_SUPERBLOCK_MAGIC}.
     */
    public static int peekFormatVersion(Path lcaFile) throws IOException {
        if (lcaFile == null || !Files.exists(lcaFile)) {
            return -1;
        }
        try (FileChannel ch = FileChannel.open(lcaFile, StandardOpenOption.READ)) {
            if (ch.size() < LcaRegionReader.SUPERBLOCK_SIZE
                    + LcaRegionReader.POINTER_DIRECTORY_SIZE
                    + LcaRegionReader.FOOTER_SIZE) {
                return -1;
            }
            byte[] hdr = new byte[10];
            ByteBuffer bb = ByteBuffer.wrap(hdr);
            while (bb.hasRemaining()) {
                int r = ch.read(bb);
                if (r < 0) {
                    return -1;
                }
            }
            long magic = (long) LcaRegionReader.LONG_BE_VIEW.get(hdr, 0);
            if (magic != LcaRegionReader.LCA_SUPERBLOCK_MAGIC) {
                return -1;
            }
            return LcaSectionCodec.readUint16BE(hdr, 0x08);
        }
    }

    /**
     * Atomically upgrades an on-disk legacy {@code 0x0002} `.lca` archive to {@code 0x0003}
     * ({@code LCA-ICD-3.0} factored Layer 2 dictionary + pure-ID Layer 3 micro-palettes) via
     * sibling {@code .tmp} + {@code fsync} + {@code ATOMIC_MOVE} ({@code ICD.md} Section 10.3,
     * {@code REQ-RTP-S-004}).
     *
     * <p>Returns {@code true} if the file was upgraded from {@code 0x0002} to {@code 0x0003}, or
     * {@code false} if the file is already {@code >= 0x0003} or not an `.lca` archive.
     * Passing {@code preferredLcd2Level < 0} preserves the legacy archive's Mode A / Mode B setting.
     */
    public static boolean upgradeLegacyArchiveAtomically(
            Path lcaFile, int preferredLcd2Level
    ) throws IOException {
        int version = peekFormatVersion(lcaFile);
        if (version < 0 || version >= LcaRegionReader.LCA_FORMAT_VERSION_V3) {
            return false;
        }
        if (!LcaRegionReader.isSupportedFormatVersion(version)) {
            throw new CorruptRegionEntryException(
                    "Unsupported legacy .lca formatVersion 0x" + Integer.toHexString(version) + " in " + lcaFile);
        }
        byte[] oldBytes = Files.readAllBytes(lcaFile);
        LcaSectionCodec.ParsedHeaderAndDict parsed =
                LcaRegionReader.INSTANCE.parseAndValidateSuperblockAndDict(oldBytes, oldBytes.length);
        int effectiveLcd2 = preferredLcd2Level >= 0
                ? preferredLcd2Level
                : ((parsed.flags & 8) != 0 ? 1 : 0);

        LcaLiveArchive upgraded = createEmptyLiveArchiveWithVersion(
                parsed.regionX,
                parsed.regionZ,
                parsed.minSectionY,
                parsed.sectionCount,
                parsed.defaultDataVersion,
                effectiveLcd2,
                parsed.storageKind,
                DEFAULT_ROOT_DICT_CAPACITY_BYTES,
                LcaRegionReader.LCA_FORMAT_VERSION_V3);
        for (int rz = 0; rz < 32; rz++) {
            for (int rx = 0; rx < 32; rx++) {
                if (!LcaRegionReader.INSTANCE.isChunkGenerated(oldBytes, rx, rz)) {
                    continue;
                }
                AnvilReader.ChunkEntry entry = LcaRegionReader.INSTANCE.readChunk(oldBytes, rx, rz);
                if (entry != null && entry.root != null) {
                    upgraded.saveChunkInitial(rx, rz, entry.root);
                }
            }
        }
        if (upgraded.dictionarySegmentCount() > 1) {
            upgraded.compact();
        }
        byte[] upgradedBytes = upgraded.toByteArray();
        Path tmpFile = lcaFile.resolveSibling(
                lcaFile.getFileName().toString() + "." + Thread.currentThread().getId() + ".v3upgrade.tmp");
        writeBytesAtomicallyDurable(lcaFile, tmpFile, upgradedBytes);
        LcaRegionReader.invalidateFileCache(lcaFile);
        return true;
    }

    // Factory method: FileChannel ownership transfers to the returned LcaLiveArchive on success
    @SuppressWarnings({"java:S2095", "java:S2093", "resource"})
    public static LcaLiveArchive openLiveArchive(Path lcaFile, int preferredLcd2Level) throws IOException {
        if (peekFormatVersion(lcaFile) == LcaRegionReader.LCA_FORMAT_VERSION_V2) {
            upgradeLegacyArchiveAtomically(lcaFile, preferredLcd2Level);
        }
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
            LcaLiveArchive archive = new LcaLiveArchive(lcaFile, ch, validLen, preferredLcd2Level);
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
            boolean modeBLcd2,
            int storageKind
    ) throws IOException {
        return openOrCreateLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                modeBLcd2 ? 1 : 0, storageKind);
    }

    public static LcaLiveArchive openOrCreateLiveArchive(
            Path lcaFile,
            int regionX,
            int regionZ,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int lcd2Level,
            int storageKind
    ) throws IOException {
        if (Files.exists(lcaFile) && Files.size(lcaFile) >= LcaRegionReader.SUPERBLOCK_SIZE
                + LcaRegionReader.POINTER_DIRECTORY_SIZE + LcaRegionReader.FOOTER_SIZE) {
            return openLiveArchive(lcaFile, lcd2Level);
        }
        return createEmptyLiveArchive(
                lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion, lcd2Level,
                storageKind, DEFAULT_ROOT_DICT_CAPACITY_BYTES);
    }

    static final byte[] ALL_ZERO_LIGHT_2048 = new byte[2048];

    private static byte[] resolveSectionLightBytes(Map<?, ?> sec, String lightKey, String starlightStateKey) {
        if (sec.get(lightKey) instanceof byte[] b && b.length == 2048) {
            return b;
        }
        if (sec.get(starlightStateKey) instanceof Number n && n.intValue() == 1) {
            return ALL_ZERO_LIGHT_2048;
        }
        return null;
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
        private final boolean modeBLcd2;
        private final boolean writeModeBLcd2;
        private final int lcd2Level;
        private final int storageKind;
        private final int rootDictCapacityBytes;
        private final int secDedTableOffset;
        private final int slabPoolBaseSlab;

        private volatile byte[] buffer;
        private volatile int activeEndSlab; // Exclusive end slab index before the 8-byte footer canary

        private Path backingFile;
        private FileChannel channel;
        /** Default chunk-count threshold before write-back staging flushes a sequential batch. */
        public static final int DEFAULT_WRITE_BACK_MAX_CHUNKS = 64;

        /** Default byte-size threshold (`512 KiB`) before write-back staging flushes a sequential batch. */
        public static final int DEFAULT_WRITE_BACK_MAX_BYTES = 512 * 1024;

        /** Default cold positional reads per archive before triggering sequential slab-pool read-ahead. */
        public static final int DEFAULT_READ_AHEAD_TRIGGER_READS = 4;

        /** Maximum slab-pool byte size (`16 MiB`) eligible for sequential read-ahead intake. */
        public static final int DEFAULT_READ_AHEAD_MAX_BYTES = 16 * 1024 * 1024;

        private static final class SlabReadAheadSnapshot {
            final int compactionEpoch;
            final long baseFileOffset;
            final byte[] bytes;

            SlabReadAheadSnapshot(int compactionEpoch, long baseFileOffset, byte[] bytes) {
                this.compactionEpoch = compactionEpoch;
                this.baseFileOffset = baseFileOffset;
                this.bytes = bytes;
            }
        }

        private static final class PendingChunkSnapshot {
            final byte[] payload;
            final boolean modeB;

            PendingChunkSnapshot(byte[] payload, boolean modeB) {
                this.payload = payload;
                this.modeB = modeB;
            }
        }

        private volatile boolean headerOnlyChannelMode = false;
        private volatile boolean dictModifiedSinceSync = false;
        private volatile LcaSectionCodec.ParsedHeaderAndDict cachedParsedDict;
        private int lastSyncedFooterOffset = -1;
        private long diskChannelWriteCount = 0;
        private long diskChannelBytesWritten = 0;
        private volatile boolean dirty = false;
        private volatile int compactionEpoch = 0;

        // BufferedLinear-inspired write-back staging & adaptive sequential read-ahead state
        private volatile boolean bufferedWritesEnabled = false;
        private int writeBackMaxChunks = DEFAULT_WRITE_BACK_MAX_CHUNKS;
        private int writeBackMaxBytes = DEFAULT_WRITE_BACK_MAX_BYTES;
        private byte[][] pendingPayloads;
        private boolean[] pendingPayloadModeB;
        private volatile int pendingWriteCount = 0;
        private int pendingWriteBytes = 0;
        private long coalescedWriteCount = 0;

        private volatile boolean readAheadEnabled = false;
        private volatile int readAheadTriggerReads = DEFAULT_READ_AHEAD_TRIGGER_READS;
        private volatile int readAheadMaxBytes = DEFAULT_READ_AHEAD_MAX_BYTES;
        private final AtomicInteger coldPositionalReadCount = new AtomicInteger(0);
        private volatile SoftReference<SlabReadAheadSnapshot> readAheadRef;
        private long sequentialReadAheadCount = 0;

        private final Map<String, Integer> blockStateToId = new ConcurrentHashMap<>();
        private final List<String> idToBlockState = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Integer> baseBlockToId = new ConcurrentHashMap<>();
        private final List<String> idToBaseBlock = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Integer> propTupleToId = new ConcurrentHashMap<>();
        private final List<String> idToPropTuple = Collections.synchronizedList(new ArrayList<>());
        private final List<Integer> stateBaseBlockIds = Collections.synchronizedList(new ArrayList<>());
        private final List<Integer> statePropTupleIds = Collections.synchronizedList(new ArrayList<>());
        private final List<Integer> stateMaterialPacked = Collections.synchronizedList(new ArrayList<>());
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
            final boolean factoredV3;
            final List<byte[]> baseBlockEntries = new ArrayList<>();
            final List<byte[]> propTupleEntries = new ArrayList<>();
            final List<byte[]> stateEntries = new ArrayList<>();
            final List<byte[]> biomeEntries = new ArrayList<>();
            byte[] patternBytes = LcaSectionCodec.EMPTY_BYTES;
            int patternLen = 0;
            int usedPayloadBytes;
            long linkTrailer = 0L;
            volatile boolean channelDirty = false;
            byte[] stagedScratch;

            DictSegmentMeta(int byteOffset, int capacityBytes, boolean factoredV3) {
                this.byteOffset = byteOffset;
                this.capacityBytes = capacityBytes;
                this.factoredV3 = factoredV3;
                this.usedPayloadBytes = factoredV3 ? 2 : 0;
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

            int maxUsedPayloadWithSecDed() {
                int limit = capacityBytes - 8;
                int maxActive = (limit * 8) / 9;
                while (maxActive + ((maxActive + 7) >>> 3) > limit) {
                    maxActive--;
                }
                return Math.max(0, maxActive - 8);
            }

            int availableSlackBytes() {
                return Math.max(0, maxUsedPayloadWithSecDed() - usedPayloadBytes);
            }

            void appendPatternSlice(byte[] src, int srcOff, int len) {
                if (len <= 0) {
                    return;
                }
                if (patternLen + len > patternBytes.length) {
                    int nextCap = Math.max(patternLen + len, Math.max(256, patternBytes.length << 1));
                    patternBytes = Arrays.copyOf(patternBytes, nextCap);
                }
                System.arraycopy(src, srcOff, patternBytes, patternLen, len);
                patternLen += len;
                usedPayloadBytes += len;
            }
        }

        private final List<DictSegmentMeta> dictSegments = new ArrayList<>();
        private LcaSectionCodec.AtomicDictState atomicDictState;
        private int syncedPatternBytes = 0;
        private final int formatVersion;

        int formatVersion() {
            return formatVersion;
        }

        boolean isFactoredV3Dict() {
            return formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3;
        }

        boolean writeModeBLcd2() {
            return writeModeBLcd2;
        }

        int[] atomicDictLitLen() {
            return (atomicDictState != null && atomicDictState.huffmanInitialized)
                    ? atomicDictState.litLen
                    : null;
        }

        byte[] buildGlobalStatePackedLightV1() {
            return LcaV3Codec.buildStatePackedLightV1(idToBlockState);
        }

        private LcaSectionCodec.AtomicDictState ensureAtomicDictState() {
            if (this.atomicDictState == null) {
                this.atomicDictState = new LcaSectionCodec.AtomicDictState();
            }
            return this.atomicDictState;
        }
        private final TreeMap<Integer, Integer> freeByStart = new TreeMap<>();
        private final TreeMap<Integer, TreeSet<Integer>> freeBySize = new TreeMap<>();
        private boolean freeSlabPoolInitialized = false;
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

        private java.util.function.Function<String, Integer> cachedRuntimeLookupRef;
        private int[] cachedDictIdToRuntimeId = new int[0];
        private java.util.function.IntFunction<String> cachedCanonicalLookupRef;
        private int[] cachedRuntimeIdToDictId = new int[0];

        LcaLiveArchive(
                int regionX,
                int regionZ,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                boolean modeBLcd2,
                int storageKind,
                int rootDictCapacityBytes
        ) {
            this(regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                    modeBLcd2 ? 1 : 0, storageKind, rootDictCapacityBytes);
        }

        LcaLiveArchive(
                int regionX,
                int regionZ,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                int lcd2Level,
                int storageKind,
                int rootDictCapacityBytes
        ) {
            this(regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                    lcd2Level, storageKind, rootDictCapacityBytes, LcaRegionReader.LCA_FORMAT_VERSION);
        }

        LcaLiveArchive(
                int regionX,
                int regionZ,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                int lcd2Level,
                int storageKind,
                int rootDictCapacityBytes,
                int formatVersion
        ) {
            this.formatVersion = formatVersion;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.minSectionY = minSectionY;
            this.sectionCount = sectionCount;
            this.defaultDataVersion = defaultDataVersion;
            int normLevel = normalizeLcd2Level(lcd2Level);
            this.modeBLcd2 = resolveEffectiveModeB(normLevel > 0);
            this.writeModeBLcd2 = this.modeBLcd2;
            this.lcd2Level = this.writeModeBLcd2 ? normLevel : 0;
            this.storageKind = storageKind & 0xFF;
            this.rootDictCapacityBytes = rootDictCapacityBytes;
            this.secDedTableOffset = (LcaRegionReader.DICT_ROOT_OFFSET + rootDictCapacityBytes + 255) & ~255;

            int slabPoolBaseOffset = this.secDedTableOffset + LcaRegionReader.SECDED_TABLE_SIZE;
            this.slabPoolBaseSlab = slabPoolBaseOffset >>> LcaRegionReader.SLAB_SHIFT;
            this.activeEndSlab = this.slabPoolBaseSlab;

            this.buffer = new byte[slabPoolBaseOffset + LcaRegionReader.FOOTER_SIZE + 65536];
            DictSegmentMeta rootSeg = new DictSegmentMeta(
                    LcaRegionReader.DICT_ROOT_OFFSET,
                    rootDictCapacityBytes,
                    isFactoredV3Dict());
            this.dictSegments.add(rootSeg);

            registerOrGetBlockStateId("minecraft:air");
            registerOrGetBiomeId("minecraft:plains");
            this.freeSlabPoolInitialized = true;
            writeSuperblockAndFooter();
            this.dirty = true;
        }

        LcaLiveArchive(byte[] existingBytes) throws CorruptRegionEntryException {
            this(existingBytes, existingBytes.length);
        }

        LcaLiveArchive(byte[] existingBytes, int validByteLength) throws CorruptRegionEntryException {
            LcaSectionCodec.ParsedHeaderAndDict parsed =
                    LcaRegionReader.INSTANCE.parseAndValidateSuperblockAndDict(existingBytes, validByteLength);
            this.formatVersion = parsed.formatVersion;
            this.regionX = parsed.regionX;
            this.regionZ = parsed.regionZ;
            this.minSectionY = parsed.minSectionY;
            this.sectionCount = parsed.sectionCount;
            this.defaultDataVersion = parsed.defaultDataVersion;
            this.modeBLcd2 = (parsed.flags & 8) != 0;
            this.writeModeBLcd2 = resolveEffectiveModeB(this.modeBLcd2);
            this.lcd2Level = this.writeModeBLcd2 ? 1 : 0;
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

            int maxCursorSlab = this.slabPoolBaseSlab;
            int segOffset = LcaRegionReader.DICT_ROOT_OFFSET;
            int segCap = this.rootDictCapacityBytes;
            while (true) {
                int startSlab = segOffset >>> LcaRegionReader.SLAB_SHIFT;
                int capSlabs = (segCap + 255) >>> LcaRegionReader.SLAB_SHIFT;
                DictSegmentMeta segMeta = new DictSegmentMeta(segOffset, segCap, isFactoredV3Dict());
                if (segOffset >= (this.slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT)) {
                    int segEndSlab = startSlab + capSlabs;
                    if (segEndSlab > maxCursorSlab) {
                        maxCursorSlab = segEndSlab;
                    }
                }

                parseDictSegmentIntoLiveArchive(existingBytes, segOffset, segCap, segMeta);

                this.dictSegments.add(segMeta);
                int segPayloadLimit = segOffset + segCap - 8;
                long trailer = (long) LcaRegionReader.LONG_BE_VIEW.get(existingBytes, segPayloadLimit);
                segMeta.linkTrailer = trailer;
                int nextRelSlabs32 = (int) (trailer >>> 32);
                int nextCapSlabs24 = (int) ((trailer >>> 8) & 0xFFFFFFL);
                if (nextRelSlabs32 == 0) break;
                segOffset = (segOffset & ~0xFF) + (nextRelSlabs32 << LcaRegionReader.SLAB_SHIFT);
                segCap = nextCapSlabs24 << LcaRegionReader.SLAB_SHIFT;
            }
            if (parsed.dictPatternArena != null && parsed.dictPatternArena.length > 0) {
                LcaSectionCodec.AtomicDictState ds = ensureAtomicDictState();
                ds.loadFromExistingArena(parsed.dictPatternArena);
                this.syncedPatternBytes = ds.usedBytes;
            } else {
                this.syncedPatternBytes = 0;
            }

            // Scan 1,024 chunk pointers in < 2 us to find activeEndSlab; TreeMap free-slab pool is built lazily on first write
            for (int i = 0; i < 1024; i++) {
                long ptr = (long) LcaRegionReader.LONG_BE_VIEW.get(
                        existingBytes, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3));
                int slabStart = (int) (ptr >>> 29);
                int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                if (slabStart > 0 && byteLen > 0) {
                    int slabEnd = slabStart + ((byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT);
                    if (slabEnd > maxCursorSlab) {
                        maxCursorSlab = slabEnd;
                    }
                }
            }
            this.activeEndSlab = Math.max(this.slabPoolBaseSlab, maxCursorSlab);
            this.freeSlabPoolInitialized = false;
            writeFooterCanary();
        }

        LcaLiveArchive(Path lcaFile, FileChannel ch, int validByteLength) throws IOException {
            this(lcaFile, ch, validByteLength, -1);
        }

        LcaLiveArchive(Path lcaFile, FileChannel ch, int validByteLength, int preferredLcd2Level) throws IOException {
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
                readChannelRangeStatic(ch, (long) validByteLength - LcaRegionReader.FOOTER_SIZE, footerBuf, 0, LcaRegionReader.FOOTER_SIZE, lcaFile);
            }
            long footerCanary = (long) LcaRegionReader.LONG_BE_VIEW.get(footerBuf, 0);
            boolean crashFooterRecovered = (footerCanary != LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
            int formatVersion = LcaSectionCodec.readUint16BE(initBuf, 0x08);
            if (!LcaRegionReader.isSupportedFormatVersion(formatVersion)) {
                throw new CorruptRegionEntryException("CBIT T1: Unsupported .lca formatVersion in " + lcaFile);
            }
            this.formatVersion = formatVersion;
            this.storageKind = initBuf[0x0A] & 0xFF;
            int flags = initBuf[0x0B] & 0xFF;
            boolean fileFlagModeB = (flags & 8) != 0;
            int normPref;
            if (preferredLcd2Level < 0) {
                normPref = fileFlagModeB ? 1 : 0;
            } else {
                normPref = normalizeLcd2Level(preferredLcd2Level);
            }
            this.modeBLcd2 = fileFlagModeB;
            this.writeModeBLcd2 = resolveEffectiveModeB(normPref > 0);
            this.lcd2Level = this.writeModeBLcd2 ? normPref : 0;
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

            int maxCursorSlab = this.slabPoolBaseSlab;
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
                DictSegmentMeta segMeta = new DictSegmentMeta(segOffset, segCap, isFactoredV3Dict());
                if (segOffset >= headerPrefixBytes) {
                    int segEndSlab = startSlab + capSlabs;
                    if (segEndSlab > maxCursorSlab) {
                        maxCursorSlab = segEndSlab;
                    }
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

                int segPayloadLimit = segBaseOff + segCap - 8;
                long trailer = (long) LcaRegionReader.LONG_BE_VIEW.get(segBuf, segPayloadLimit);
                if ((trailer & 2L) != 0) {
                    int dictHealed = LcaSectionCodec.verifyAndHealDictSegmentSecDed(segBuf, segBaseOff, segCap);
                    if (dictHealed < 0) {
                        throw new CorruptRegionEntryException(
                                "CBIT T5: Unrecoverable double-bit error (DED) in Layer 2 dictionary segment in "
                                        + lcaFile);
                    }
                    if (dictHealed > 0) {
                        this.dirty = true;
                        this.selfHealingDiagnostics.add("Layer-2 Dictionary SEC-DED healed "
                                + dictHealed + " word(s) on open in " + lcaFile);
                    }
                }

                parseDictSegmentIntoLiveArchive(segBuf, segBaseOff, segCap, segMeta);

                this.dictSegments.add(segMeta);
                segMeta.linkTrailer = trailer;
                int nextRelSlabs32 = (int) (trailer >>> 32);
                int nextCapSlabs24 = (int) ((trailer >>> 8) & 0xFFFFFFL);
                if (nextRelSlabs32 == 0) break;
                segOffset = (segOffset & ~0xFF) + (nextRelSlabs32 << LcaRegionReader.SLAB_SHIFT);
                segCap = nextCapSlabs24 << LcaRegionReader.SLAB_SHIFT;
            }
            rebuildAtomicDictFromSegments();

            this.backingFile = lcaFile;
            this.channel = ch;
            this.headerOnlyChannelMode = true;

            boolean hasSecDed = (flags & 16) != 0;
            boolean verifySlabHeadersOnOpen = !hasSecDed || crashFooterRecovered;
            boolean[] needsOpenScan = null;
            long[] openIntervals = new long[1024];
            int[] openSlabEnds = new int[1024];
            int openIntervalCount = 0;
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
                            writeChannelRange((long) this.secDedTableOffset + i, this.buffer, this.secDedTableOffset + i, 1);
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
                            || (verifySlabHeadersOnOpen && !isSlabValidForChunkOnChannel(ch, ptr, i, effectivePayloadLimit))) {
                        if (needsOpenScan == null) needsOpenScan = new boolean[1024];
                        needsOpenScan[i] = true;
                        continue;
                    }
                    int slabStart = (int) slabStartLong;
                    int slabEnd = slabStart + ((byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT);
                    if (slabEnd > maxCursorSlab) {
                        maxCursorSlab = slabEnd;
                    }
                    openIntervals[openIntervalCount++] = ((long) slabStart << 32) | (i & 0xFFFF_FFFFL);
                    openSlabEnds[i] = slabEnd;
                }
            }

            if (openIntervalCount > 1) {
                Arrays.sort(openIntervals, 0, openIntervalCount);
                int prevIdx = (int) openIntervals[0];
                int prevEnd = openSlabEnds[prevIdx];
                for (int k = 1; k < openIntervalCount; k++) {
                    int curStart = (int) (openIntervals[k] >>> 32);
                    int curIdx = (int) openIntervals[k];
                    int curEnd = openSlabEnds[curIdx];
                    if (curStart < prevEnd) {
                        long prevPtr = (long) LcaRegionReader.LONG_BE_VIEW.get(
                                this.buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (prevIdx << 3));
                        long curPtr = (long) LcaRegionReader.LONG_BE_VIEW.get(
                                this.buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (curIdx << 3));
                        boolean prevValid = (needsOpenScan == null || !needsOpenScan[prevIdx])
                                && isSlabValidForChunkOnChannel(ch, prevPtr, prevIdx, effectivePayloadLimit);
                        boolean curValid = (needsOpenScan == null || !needsOpenScan[curIdx])
                                && isSlabValidForChunkOnChannel(ch, curPtr, curIdx, effectivePayloadLimit);
                        if (!prevValid) {
                            if (needsOpenScan == null) needsOpenScan = new boolean[1024];
                            needsOpenScan[prevIdx] = true;
                        }
                        if (!curValid) {
                            if (needsOpenScan == null) needsOpenScan = new boolean[1024];
                            needsOpenScan[curIdx] = true;
                        }
                        if (prevValid && curValid) {
                            if (needsOpenScan == null) needsOpenScan = new boolean[1024];
                            needsOpenScan[prevIdx] = true;
                        }
                    }
                    boolean prevFlagged = needsOpenScan != null && needsOpenScan[prevIdx];
                    boolean curFlagged = needsOpenScan != null && needsOpenScan[curIdx];
                    if (prevFlagged && !curFlagged) {
                        prevIdx = curIdx;
                        prevEnd = curEnd;
                    } else if (!curFlagged && curEnd > prevEnd) {
                        prevIdx = curIdx;
                        prevEnd = curEnd;
                    }
                }
            }

            if (needsOpenScan != null) {
                int recoveredMaxSlab = reconstructCorruptPointersOnOpen(ch, needsOpenScan, effectivePayloadLimit, null);
                if (recoveredMaxSlab > maxCursorSlab) {
                    maxCursorSlab = recoveredMaxSlab;
                }
                if (lcaFile != null) {
                    LcaRegionReader.invalidateFileCache(lcaFile);
                }
            }

            this.freeSlabPoolInitialized = false;
            if (!crashFooterRecovered) {
                int fileEndSlab = (validByteLength - LcaRegionReader.FOOTER_SIZE) >>> LcaRegionReader.SLAB_SHIFT;
                this.activeEndSlab = Math.max(this.slabPoolBaseSlab, Math.max(maxCursorSlab, fileEndSlab));
                this.lastSyncedFooterOffset = validByteLength - LcaRegionReader.FOOTER_SIZE;
                writeFooterCanary();
            } else {
                this.activeEndSlab = Math.max(this.slabPoolBaseSlab, maxCursorSlab);
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
            registerOpenLiveArchive(lcaFile, this);
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
            if (byteOffset + readLen > effectivePayloadLimit) {
                return false;
            }
            byte[] hdr = new byte[readLen];
            readChannelRangeStatic(ch, byteOffset, hdr, 0, readLen, this.backingFile);
            int dvWord = LcaSectionCodec.readIntBE(hdr, 0);
            if (((dvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) != LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC
                    || ((dvWord >>> 16) & 0x03FF) != expectedChunkIdx)
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

        private int reconstructCorruptPointersOnOpen(
                FileChannel ch, boolean[] needsOpenScan, int effectivePayloadLimit, List<int[]> allocatedIntervals
        ) throws IOException {
            int maxRecoveredEndSlab = this.slabPoolBaseSlab;
            int headerLen = 24 + (this.sectionCount << 1);
            int headerWords = (headerLen + 7) >>> 3;
            int maxSlab = effectivePayloadLimit >>> LcaRegionReader.SLAB_SHIFT;
            long[] bestPtrByChunk = new long[1024];
            int[] bestGenByChunk = new int[1024];
            Arrays.fill(bestGenByChunk, -1);

            byte[] probe = new byte[headerLen + headerWords + 4];
            for (int slab = this.slabPoolBaseSlab; slab < maxSlab; slab++) {
                long bytePos = (long) slab << LcaRegionReader.SLAB_SHIFT;
                if (bytePos + headerLen > effectivePayloadLimit) {
                    break;
                }
                int toRead = (int) Math.min(probe.length, effectivePayloadLimit - bytePos);
                readChannelRangeStatic(ch, bytePos, probe, 0, toRead, this.backingFile);
                boolean slabModeB = LcaRegionReader.probeLooksLikeModeB(probe, headerLen, headerWords, toRead);
                int healed = 0;
                int dvWord = LcaSectionCodec.readIntBE(probe, 0);
                if (slabModeB && toRead >= headerLen + headerWords) {
                    byte[] scratch = Arrays.copyOfRange(probe, 0, headerLen + headerWords);
                    int candHealed = LcaSectionCodec.verifyAndHealChunkHeaderSecDed(scratch, 0, headerLen, headerLen);
                    if (candHealed >= 0) {
                        int candDv = LcaSectionCodec.readIntBE(scratch, 0);
                        if ((candDv & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) == LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC) {
                            System.arraycopy(scratch, 0, probe, 0, headerLen + headerWords);
                            dvWord = candDv;
                            healed = candHealed;
                        }
                    }
                }
                if ((dvWord & LcaRegionReader.CHUNK_FRAME_SELF_TAG_MASK) != LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC) {
                    continue;
                }
                int chunkIdx = (dvWord >>> 16) & 0x03FF;
                if (!needsOpenScan[chunkIdx]) {
                    continue;
                }
                if (healed > 0) {
                    writeChannelRange(bytePos, probe, 0, headerLen + headerWords);
                    this.liveChunkHeaderHealedWords += healed;
                }
                int estLen = estimateSlabLengthOnChannel(ch, bytePos, effectivePayloadLimit, slabModeB);
                if (estLen >= headerLen && bytePos + estLen <= effectivePayloadLimit) {
                    bestPtrByChunk[chunkIdx] = packChunkPointer64(slab, slabModeB, estLen, 1);
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
                    int endSlab = slabStart + slabs;
                    if (endSlab > maxRecoveredEndSlab) {
                        maxRecoveredEndSlab = endSlab;
                    }
                    if (allocatedIntervals != null) {
                        allocatedIntervals.add(new int[] {slabStart, endSlab});
                    }
                    this.liveSlabScanReconstructions++;
                    this.selfHealingDiagnostics.add("Tier-2 Slab Reverse-Scan reconstructed pointer on open for chunkIdx=" + i);
                } else {
                    this.selfHealingDiagnostics.add("Cleared unreferenced/dangling crash pointer on open for chunkIdx=" + i);
                }
            }
            return maxRecoveredEndSlab;
        }

        private int estimateSlabLengthOnChannel(
                FileChannel ch, long bytePos, int effectivePayloadLimit, boolean slabModeB
        ) throws IOException {
            long availLimit = Math.min(effectivePayloadLimit, ch.size());
            int maxRem = (int) Math.min(65536L, availLimit - bytePos);
            if (maxRem < 24) return 0;
            byte[] buf = new byte[maxRem];
            readChannelRangeStatic(ch, bytePos, buf, 0, maxRem, this.backingFile);
            int exact = LcaRegionReader.estimateSelfDescribingChunkLengthStatic(
                    buf, 0, this.sectionCount, slabModeB, maxRem);
            int headerLen = 24 + (this.sectionCount << 1);
            if (exact > headerLen && exact <= maxRem) {
                return exact;
            }
            if (slabModeB) {
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

        public byte[] liveBuffer() {
            if (headerOnlyChannelMode) {
                synchronized (this) {
                    if (headerOnlyChannelMode) {
                        materializeFullBufferFromChannel();
                    }
                }
            }
            return buffer;
        }

        private void materializeFullBufferFromChannel() {
            if (!headerOnlyChannelMode) {
                return;
            }
            if (pendingWriteCount > 0) {
                try {
                    flushPendingWrites();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            int fullLen = activeByteLength();
            byte[] full = new byte[fullLen + 65536];
            if (channel != null && channel.isOpen()) {
                try {
                    int diskLen = (int) Math.min(channel.size(), fullLen);
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

        public int minSectionY() {
            return minSectionY;
        }

        public int sectionCount() {
            return sectionCount;
        }

        public int defaultDataVersion() {
            return defaultDataVersion;
        }

        public boolean modeBLcd2() {
            return modeBLcd2;
        }

        public int lcd2Level() {
            return lcd2Level;
        }

        boolean isWriteModeBLcd2() {
            return writeModeBLcd2;
        }

        byte[] encodeModeBWithAtomicDictForStreaming(byte[] chunkFrame, int headerLen) {
            return encodeModeBWithAtomicDict(chunkFrame, headerLen, Math.max(1, lcd2Level));
        }

        /**
         * Returns a cached {@code dictId -> runtimeStateId} lookup table incrementally synchronized
         * with this archive's block-state dictionary.
         */
        public synchronized int[] resolveDictIdToRuntimeId(
                java.util.function.Function<String, Integer> runtimeStateLookup
        ) {
            if (runtimeStateLookup == null) {
                return null;
            }
            int dictSize = idToBlockState.size();
            if (cachedRuntimeLookupRef != runtimeStateLookup) {
                cachedRuntimeLookupRef = runtimeStateLookup;
                cachedDictIdToRuntimeId = new int[dictSize];
                for (int i = 0; i < dictSize; i++) {
                    Integer mapped = runtimeStateLookup.apply(idToBlockState.get(i));
                    cachedDictIdToRuntimeId[i] = mapped != null ? mapped.intValue() : 0;
                }
                return cachedDictIdToRuntimeId;
            }
            if (cachedDictIdToRuntimeId.length < dictSize) {
                int oldLen = cachedDictIdToRuntimeId.length;
                int[] grown = Arrays.copyOf(cachedDictIdToRuntimeId, dictSize);
                for (int i = oldLen; i < dictSize; i++) {
                    Integer mapped = runtimeStateLookup.apply(idToBlockState.get(i));
                    grown[i] = mapped != null ? mapped.intValue() : 0;
                }
                cachedDictIdToRuntimeId = grown;
            }
            return cachedDictIdToRuntimeId;
        }

        /**
         * Translates a host {@code runtimeStateId} into this archive's {@code globalDictId},
         * registering unseen canonical states in the region dictionary and caching the mapping in $O(1)$
         * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        public synchronized int resolveRuntimeIdToDictId(
                int runtimeStateId,
                java.util.function.IntFunction<String> runtimeIdToCanonicalState
        ) {
            java.util.Objects.requireNonNull(runtimeIdToCanonicalState, "runtimeIdToCanonicalState");
            if (runtimeStateId < 0) {
                throw new IllegalArgumentException("runtimeStateId must be >= 0: " + runtimeStateId);
            }
            if (cachedCanonicalLookupRef != runtimeIdToCanonicalState) {
                cachedCanonicalLookupRef = runtimeIdToCanonicalState;
                cachedRuntimeIdToDictId = new int[Math.max(256, runtimeStateId + 64)];
                Arrays.fill(cachedRuntimeIdToDictId, -1);
            } else if (runtimeStateId >= cachedRuntimeIdToDictId.length) {
                int oldLen = cachedRuntimeIdToDictId.length;
                int nextLen = Math.max(runtimeStateId + 64, oldLen << 1);
                cachedRuntimeIdToDictId = Arrays.copyOf(cachedRuntimeIdToDictId, nextLen);
                Arrays.fill(cachedRuntimeIdToDictId, oldLen, nextLen, -1);
            }
            int existing = cachedRuntimeIdToDictId[runtimeStateId];
            if (existing >= 0) {
                return existing;
            }
            String canonical = runtimeIdToCanonicalState.apply(runtimeStateId);
            if (canonical == null || canonical.isBlank()) {
                canonical = "minecraft:air";
            }
            int dictId = registerOrGetBlockStateId(canonical);
            cachedRuntimeIdToDictId[runtimeStateId] = dictId;
            return dictId;
        }

        public Path backingFile() {
            return backingFile;
        }

        public boolean isChannelOpen() {
            return channel != null && channel.isOpen();
        }

        private long diskChannelForceCount = 0;
        private long lastForcedWriteCount = 0;

        public long diskChannelWriteCount() {
            return diskChannelWriteCount;
        }

        public long diskChannelBytesWritten() {
            return diskChannelBytesWritten;
        }

        public long diskChannelForceCount() {
            return diskChannelForceCount;
        }

        private static boolean isFsyncOnCloseEnabled() {
            String explicit = System.getProperty("leafanvil.fsync.onClose");
            if (explicit != null && !explicit.isBlank()) {
                return Boolean.parseBoolean(explicit.trim());
            }
            return true;
        }
        synchronized void attachOpenChannel(Path file, FileChannel openChannel) {
            this.backingFile = file;
            this.channel = openChannel;
            this.lastSyncedFooterOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
            registerOpenLiveArchive(file, this);
        }

        /**
         * Off-thread region archive channel binder (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
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
                int footerOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                ensureBufferCapacity(footerOffset + LcaRegionReader.FOOTER_SIZE);
                LcaRegionReader.setVolatileLongBE(buffer, footerOffset, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                int len = activeByteLength();
                writeChannelRange(0L, buffer, 0, len);
                this.lastSyncedFooterOffset = footerOffset;
                this.dictModifiedSinceSync = false;
                for (int s = 0; s < dictSegments.size(); s++) {
                    dictSegments.get(s).channelDirty = false;
                }
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
            registerOpenLiveArchive(file, this);
        }

        /**
         * Off-thread positional region channel writer (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        private void writeChannelRange(long fileOffset, byte[] src, int srcOffset, int length) throws IOException {
            if (length <= 0) {
                return;
            }
            if (channel == null || !channel.isOpen()) {
                if (headerOnlyChannelMode) {
                    throw new java.nio.channels.ClosedChannelException();
                }
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

        /**
         * Off-thread unchecked positional region channel writer (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        private void writeChannelRangeUnchecked(long fileOffset, byte[] src, int srcOffset, int length) {
            try {
                writeChannelRange(fileOffset, src, srcOffset, length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Off-thread pointer, `SEC-DED` syndrome, and dirty dictionary segment sync (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        private synchronized void persistPointerAndSecDed(int chunkIdx) {
            if (channel == null || !channel.isOpen()) {
                if (headerOnlyChannelMode) {
                    throw new UncheckedIOException(new java.nio.channels.ClosedChannelException());
                }
                return;
            }
            writeFooterCanary();
            boolean dictMod = this.dictModifiedSinceSync;
            if (dictMod) {
                for (int s = 0; s < dictSegments.size(); s++) {
                    DictSegmentMeta seg = dictSegments.get(s);
                    if (seg.channelDirty) {
                        byte[] staged = (seg.stagedScratch != null && seg.stagedScratch.length == seg.capacityBytes())
                                ? seg.stagedScratch
                                : stageDictSegmentInBuffer(seg);
                        writeChannelRangeUnchecked(seg.byteOffset(), staged, 0, seg.capacityBytes());
                        seg.channelDirty = false;
                    }
                }
                this.dictModifiedSinceSync = false;
            }
            int secDedPos = secDedTableOffset + chunkIdx;
            writeChannelRangeUnchecked(secDedPos, buffer, secDedPos, 1);
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            writeChannelRangeUnchecked(ptrByteOffset, buffer, ptrByteOffset, 8);
            if (backingFile != null) {
                long newPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOffset);
                LcaRegionReader.updateCachedPointer(backingFile, chunkIdx, newPtr, activeByteLength(), dictMod);
            }
        }
        /**
         * Off-thread full archive byte snapshot (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        public synchronized byte[] toByteArray() {
            if (pendingWriteCount > 0) {
                try {
                    flushPendingWrites();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            writeFooterCanary();
            if (headerOnlyChannelMode) {
                int fullLen = activeByteLength();
                byte[] out = new byte[fullLen];
                if (channel != null && channel.isOpen()) {
                    try {
                        int diskLen = (int) Math.min(channel.size(), fullLen);
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
            ensureFreeSlabPoolInitialized();
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
        /**
         * Configures coalesced write-back staging for this archive. Disabling while dirty chunks
         * are staged flushes them to disk immediately.
         */
        public synchronized void configureBufferedWriteBack(
                boolean enabled, int maxPendingChunks, int maxPendingBytes
        ) throws IOException {
            if (!enabled && pendingWriteCount > 0) {
                flushPendingWrites();
            }
            this.bufferedWritesEnabled = enabled;
            this.writeBackMaxChunks = Math.max(1, maxPendingChunks);
            this.writeBackMaxBytes = Math.max(4096, maxPendingBytes);
        }

        /**
         * Configures adaptive sequential slab-pool read-ahead intake for cold-cache reads.
         */
        public synchronized void configureSequentialReadAhead(
                boolean enabled, int triggerReads, int maxBytes
        ) {
            this.readAheadEnabled = enabled;
            this.readAheadTriggerReads = Math.max(1, triggerReads);
            this.readAheadMaxBytes = Math.max(65536, maxBytes);
            if (!enabled) {
                this.readAheadRef = null;
            }
        }

        public boolean isBufferedWriteBackEnabled() {
            return bufferedWritesEnabled;
        }

        private volatile boolean directFlatBitPack;
        private String[] boundWorldgenCanonicalPalette;
        private int[] boundWorldgenDictIds;
        private boolean worldgenDictDirectMatch;

        public boolean isSequentialReadAheadEnabled() {
            return readAheadEnabled;
        }

        /**
         * Binds and pre-registers a deterministic worldgen block-state dictionary on this region
         * archive, enabling O(1) GPU-resolved dictionary IDs on {@link #saveFreshChunkDirectModeA}.
         */
        public synchronized int[] bindWorldgenBlockDictionary(String[] canonicalStates) {
            if (canonicalStates == null || canonicalStates.length == 0) {
                this.boundWorldgenCanonicalPalette = null;
                this.boundWorldgenDictIds = null;
                this.worldgenDictDirectMatch = false;
                return new int[0];
            }
            if (this.boundWorldgenCanonicalPalette != null
                    && this.boundWorldgenCanonicalPalette.length == canonicalStates.length
                    && Arrays.equals(this.boundWorldgenCanonicalPalette, canonicalStates)
                    && this.boundWorldgenDictIds != null) {
                return this.boundWorldgenDictIds.clone();
            }
            int[] ids = preRegisterBlockStates(canonicalStates);
            boolean direct = true;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != i) {
                    direct = false;
                    break;
                }
            }
            this.boundWorldgenCanonicalPalette = canonicalStates.clone();
            this.boundWorldgenDictIds = ids;
            this.worldgenDictDirectMatch = direct;
            return ids.clone();
        }

        public synchronized boolean isWorldgenDictDirectMatch() {
            return this.worldgenDictDirectMatch;
        }

        /**
         * Enables or disables direct {@code BLOCK_MODE_FLAT_BITPACK} ({@code mode 10}, YZX) section
         * emission for unified Disk = Memory = GPU parity (zero Hilbert curve search or
         * Delta-Roaring containers).
         */
        public void setDirectFlatBitPack(boolean enabled) {
            this.directFlatBitPack = enabled;
        }

        public boolean isDirectFlatBitPack() {
            return this.directFlatBitPack;
        }

        public int pendingWriteCount() {
            return pendingWriteCount;
        }

        public synchronized long coalescedWriteCount() {
            return coalescedWriteCount;
        }

        public synchronized long sequentialReadAheadCount() {
            return sequentialReadAheadCount;
        }

        private synchronized boolean hasPendingPayload(int chunkIdx) {
            return pendingPayloads != null && pendingPayloads[chunkIdx] != null;
        }

        private synchronized PendingChunkSnapshot getPendingPayload(int chunkIdx) {
            if (pendingPayloads == null) {
                return null;
            }
            byte[] pay = pendingPayloads[chunkIdx];
            if (pay == null) {
                return null;
            }
            return new PendingChunkSnapshot(pay, pendingPayloadModeB[chunkIdx]);
        }

        /**
         * Flushes all staged write-back chunk payloads in one contiguous sequential slab write
         * plus coalesced pointer/SEC-DED header writes (`REQ-RTP-S-004`, `REQ-RTP-S-005`).
         *
         * @return number of staged chunks committed to disk
         */
        public synchronized int flushPendingWrites() throws IOException {
            if (pendingWriteCount == 0 || pendingPayloads == null) {
                return 0;
            }
            if (channel == null || !channel.isOpen()) {
                if (headerOnlyChannelMode) {
                    throw new java.nio.channels.ClosedChannelException();
                }
            }

            int count = pendingWriteCount;
            int[] dirtyIndices = new int[count];
            byte[][] payloads = new byte[count][];
            boolean[] modeBs = new boolean[count];
            int[] slabsNeeded = new int[count];
            long[] oldPtrs = new long[count];
            int[] oldSlabStarts = new int[count];
            int[] oldByteLens = new int[count];
            int[] oldGens = new int[count];

            int cursor = 0;
            int totalBatchSlabs = 0;
            for (int idx = 0; idx < 1024; idx++) {
                byte[] pay = pendingPayloads[idx];
                if (pay != null) {
                    dirtyIndices[cursor] = idx;
                    payloads[cursor] = pay;
                    modeBs[cursor] = pendingPayloadModeB[idx];
                    int s = Math.max(1, (pay.length + 255) >>> LcaRegionReader.SLAB_SHIFT);
                    slabsNeeded[cursor] = s;
                    totalBatchSlabs += s;
                    int ptrByteOff = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idx << 3);
                    long oldPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOff);
                    oldPtrs[cursor] = oldPtr;
                    oldSlabStarts[cursor] = (int) (oldPtr >>> 29);
                    oldByteLens[cursor] = (int) ((oldPtr >>> 8) & 0xFFFFFL);
                    oldGens[cursor] = (int) (oldPtr & 0xFFL);
                    cursor++;
                }
            }
            if (cursor == 0) {
                pendingWriteCount = 0;
                pendingWriteBytes = 0;
                return 0;
            }

            // Reclaim any unpinned deferred slabs before contiguous batch allocation
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
            for (int k = 0; k < cursor; k++) {
                if (oldSlabStarts[k] > 0 && oldByteLens[k] > 0) {
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
            }

            int prevActiveEndSlab = this.activeEndSlab;
            int prevLastSyncedFooter = this.lastSyncedFooterOffset;
            int batchStartSlab = allocateSlabs(totalBatchSlabs, true);
            int batchStartOffset = batchStartSlab << LcaRegionReader.SLAB_SHIFT;
            int batchPaddedBytes = totalBatchSlabs << LcaRegionReader.SLAB_SHIFT;
            int footerOffset = this.activeEndSlab << LcaRegionReader.SLAB_SHIFT;
            boolean coalesceFooter = headerOnlyChannelMode
                    && channel != null
                    && channel.isOpen()
                    && this.activeEndSlab >= slabPoolBaseSlab
                    && slabPoolBaseSlab > 0
                    && batchStartOffset + batchPaddedBytes == footerOffset
                    && footerOffset != lastSyncedFooterOffset;

            byte[] batchBuf = new byte[batchPaddedBytes + (coalesceFooter ? LcaRegionReader.FOOTER_SIZE : 0)];
            int[] assignedStarts = new int[cursor];
            int relSlab = 0;
            for (int k = 0; k < cursor; k++) {
                assignedStarts[k] = batchStartSlab + relSlab;
                System.arraycopy(payloads[k], 0, batchBuf, relSlab << LcaRegionReader.SLAB_SHIFT, payloads[k].length);
                relSlab += slabsNeeded[k];
            }
            if (coalesceFooter) {
                LcaRegionReader.setVolatileLongBE(batchBuf, batchPaddedBytes, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
            }

            try {
                if (headerOnlyChannelMode) {
                    if (!coalesceFooter) {
                        writeFooterCanary();
                    }
                    writeChannelRange(batchStartOffset, batchBuf, 0, batchBuf.length);
                    if (coalesceFooter) {
                        lastSyncedFooterOffset = footerOffset;
                    }
                } else {
                    ensureBufferCapacity(Math.max(batchStartOffset + batchPaddedBytes, footerOffset) + LcaRegionReader.FOOTER_SIZE);
                    System.arraycopy(batchBuf, 0, buffer, batchStartOffset, batchPaddedBytes);
                    if (this.activeEndSlab >= slabPoolBaseSlab && slabPoolBaseSlab > 0) {
                        LcaRegionReader.setVolatileLongBE(buffer, footerOffset, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                    }
                    if (channel != null && channel.isOpen()) {
                        writeChannelRange(batchStartOffset, batchBuf, 0, batchBuf.length);
                        if (coalesceFooter) {
                            lastSyncedFooterOffset = footerOffset;
                        } else {
                            writeFooterCanary();
                        }
                    }
                }
            } catch (IOException | RuntimeException | Error e) {
                rollbackAllocatedSlabs(batchStartSlab, totalBatchSlabs, prevActiveEndSlab, prevLastSyncedFooter, true);
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }

            // Commit pointers & SEC-DED syndromes in memory, then persist contiguously
            long[] newPtrs = new long[cursor];
            for (int k = 0; k < cursor; k++) {
                int idx = dirtyIndices[k];
                int nextGen = (oldGens[k] + 1) & 0xFF;
                long newPtr = packChunkPointer64(assignedStarts[k], modeBs[k], payloads[k].length, nextGen);
                newPtrs[k] = newPtr;
                buffer[secDedTableOffset + idx] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
                LcaRegionReader.setVolatileLongBE(buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (idx << 3), newPtr);
            }
            VarHandle.releaseFence();

            if (channel != null && channel.isOpen()) {
                boolean dictMod = this.dictModifiedSinceSync;
                if (dictMod) {
                    for (int s = 0; s < dictSegments.size(); s++) {
                        DictSegmentMeta seg = dictSegments.get(s);
                        if (seg.channelDirty) {
                            byte[] staged = (seg.stagedScratch != null && seg.stagedScratch.length == seg.capacityBytes())
                                    ? seg.stagedScratch
                                    : stageDictSegmentInBuffer(seg);
                            writeChannelRange(seg.byteOffset(), staged, 0, seg.capacityBytes());
                            seg.channelDirty = false;
                        }
                    }
                    this.dictModifiedSinceSync = false;
                }
                int minIdx = dirtyIndices[0];
                int maxIdx = dirtyIndices[cursor - 1];
                int secStart = secDedTableOffset + minIdx;
                int secLen = (maxIdx - minIdx) + 1;
                writeChannelRange(secStart, buffer, secStart, secLen);
                int ptrStart = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (minIdx << 3);
                int ptrLen = ((maxIdx - minIdx) + 1) << 3;
                writeChannelRange(ptrStart, buffer, ptrStart, ptrLen);
                if (backingFile != null) {
                    int activeLen = activeByteLength();
                    for (int k = 0; k < cursor; k++) {
                        LcaRegionReader.updateCachedPointer(
                                backingFile, dirtyIndices[k], newPtrs[k], activeLen, dictMod && k == 0);
                    }
                }
            }

            for (int k = 0; k < cursor; k++) {
                int idx = dirtyIndices[k];
                if (oldSlabStarts[k] > 0 && oldByteLens[k] > 0) {
                    prevChunkPointers[idx] = oldPtrs[k];
                    int oldSlabs = (oldByteLens[k] + 255) >>> LcaRegionReader.SLAB_SHIFT;
                    graceSlabStarts[graceCursor] = oldSlabStarts[k];
                    graceSlabCounts[graceCursor] = oldSlabs;
                    graceCursor = (graceCursor + 1) & (COW_GRACE_EPOCHS - 1);
                }
                pendingPayloads[idx] = null;
            }
            pendingWriteCount = 0;
            pendingWriteBytes = 0;
            this.readAheadRef = null;
            return cursor;
        }

        private SlabReadAheadSnapshot tryLoadSequentialReadAhead(FileChannel ch, Path bf) throws IOException {
            if (!readAheadEnabled || ch == null || !ch.isOpen()) {
                return null;
            }
            synchronized (this) {
                SoftReference<SlabReadAheadSnapshot> existingRef = this.readAheadRef;
                if (existingRef != null) {
                    SlabReadAheadSnapshot existing = existingRef.get();
                    if (existing != null && existing.compactionEpoch == this.compactionEpoch) {
                        return existing;
                    }
                }
                if (!headerOnlyChannelMode || channel == null || !channel.isOpen() || ch != channel) {
                    return null;
                }
                long poolBase = (long) slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT;
                long poolEnd = (long) activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                long len = poolEnd - poolBase;
                if (len <= 0L || len > readAheadMaxBytes) {
                    return null;
                }
                byte[] poolBytes = new byte[(int) len];
                readChannelRangeStatic(ch, poolBase, poolBytes, 0, (int) len, bf);
                SlabReadAheadSnapshot snap = new SlabReadAheadSnapshot(this.compactionEpoch, poolBase, poolBytes);
                this.readAheadRef = new SoftReference<>(snap);
                this.coldPositionalReadCount.set(0);
                this.sequentialReadAheadCount++;
                return snap;
            }
        }

        public boolean hasChunk(int rx, int rz) {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                return false;
            }
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            if (pendingWriteCount > 0 && hasPendingPayload(chunkIdx)) {
                return true;
            }
            if (headerOnlyChannelMode) {
                long ptr = resolveAndHealLivePointer(chunkIdx);
                long byteOffset = (ptr >>> 29) << LcaRegionReader.SLAB_SHIFT;
                int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                long maxEnd = (long) activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                return byteOffset >= LcaRegionReader.DICT_ROOT_OFFSET
                        && exactByteLen20 > 0
                        && byteOffset + exactByteLen20 <= maxEnd;
            }
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
            if (ch != null && ch.isOpen() && ch.size() > (long) secDedTableOffset + chunkIdx) {
                byte[] diskPtrBuf = new byte[8];
                byte[] diskSynBuf = new byte[1];
                readChannelRangeStatic(ch, ptrByteOffset, diskPtrBuf, 0, 8, backingFile);
                readChannelRangeStatic(ch, (long) secDedTableOffset + chunkIdx, diskSynBuf, 0, 1, backingFile);
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
                if ((ch == null || !ch.isOpen() || isSlabValidForChunkOnChannel(ch, prevPtr, chunkIdx, effLimit))
                        && rollbackCorruptChunkToPriorCoW(rx, rz)) {
                    this.liveCoWRollbacks++;
                    this.selfHealingDiagnostics.add("Tier-4 CoW rollback recovered chunk (" + rx + "," + rz + ")");
                    return true;
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
            byte[] rawSlab = null;
            SoftReference<SlabReadAheadSnapshot> ref = this.readAheadRef;
            if (ref != null) {
                SlabReadAheadSnapshot snap = ref.get();
                if (snap != null
                        && snap.compactionEpoch == this.compactionEpoch
                        && payloadStartOffset >= snap.baseFileOffset
                        && (payloadStartOffset - snap.baseFileOffset) + exactByteLen20 <= snap.bytes.length) {
                    int rel = (int) (payloadStartOffset - snap.baseFileOffset);
                    rawSlab = Arrays.copyOfRange(snap.bytes, rel, rel + exactByteLen20);
                }
            }
            if (rawSlab == null && readAheadEnabled && ch != null && ch.isOpen()) {
                int misses = coldPositionalReadCount.incrementAndGet();
                if (misses >= readAheadTriggerReads) {
                    SlabReadAheadSnapshot snap = tryLoadSequentialReadAhead(ch, bf);
                    if (snap != null
                            && payloadStartOffset >= snap.baseFileOffset
                            && (payloadStartOffset - snap.baseFileOffset) + exactByteLen20 <= snap.bytes.length) {
                        int rel = (int) (payloadStartOffset - snap.baseFileOffset);
                        rawSlab = Arrays.copyOfRange(snap.bytes, rel, rel + exactByteLen20);
                    }
                }
            }
            if (rawSlab == null) {
                rawSlab = new byte[exactByteLen20];
                readChannelRangeStatic(ch, payloadStartOffset, rawSlab, 0, exactByteLen20, bf);
            }
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
            synchronized (this) {
                d = this.cachedParsedDict;
                if (d != null) {
                    return d;
                }
                int stateCount = this.idToBlockState.size();
                List<String> canonicalStates = new ArrayList<>(stateCount);
                List<String> baseBlockNames = new ArrayList<>(stateCount);
                List<List<String>> singletonBasePalettes = new ArrayList<>(stateCount);
                for (String canonical : this.idToBlockState) {
                    String baseName = LcaSectionCodec.stripBlockProperties(canonical);
                    canonicalStates.add(canonical);
                    baseBlockNames.add(baseName);
                    singletonBasePalettes.add(List.of(baseName));
                }
                int biomeCount = this.idToBiome.size();
                List<String> biomes = new ArrayList<>(biomeCount);
                List<List<String>> singletonBiomePalettes = new ArrayList<>(biomeCount);
                for (String biomeId : this.idToBiome) {
                    biomes.add(biomeId);
                    singletonBiomePalettes.add(List.of(biomeId));
                }
                int flags = 1 | (modeBLcd2 ? 8 : 0) | 16;
                long slabPoolBaseOffset = (long) slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT;
                byte[] patternArenaSnap = (atomicDictState != null && atomicDictState.usedBytes > 0)
                        ? Arrays.copyOf(atomicDictState.arena, atomicDictState.usedBytes)
                        : LcaSectionCodec.EMPTY_BYTES;
                if (isFactoredV3Dict()) {
                    String[] baseArr = this.idToBaseBlock.toArray(new String[0]);
                    String[] propArr = this.idToPropTuple.toArray(new String[0]);
                    int[] sBaseIds = new int[stateCount];
                    int[] sPropIds = new int[stateCount];
                    int[] sMat = new int[stateCount];
                    for (int i = 0; i < stateCount; i++) {
                        sBaseIds[i] = this.stateBaseBlockIds.get(i);
                        sPropIds[i] = this.statePropTupleIds.get(i);
                        sMat[i] = this.stateMaterialPacked.get(i);
                    }
                    d = new LcaSectionCodec.ParsedHeaderAndDict(
                            this.formatVersion,
                            storageKind, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                            flags, slabPoolBaseOffset,
                            Collections.unmodifiableList(canonicalStates),
                            Collections.unmodifiableList(baseBlockNames),
                            Collections.unmodifiableList(singletonBasePalettes),
                            Collections.unmodifiableList(biomes),
                            Collections.unmodifiableList(singletonBiomePalettes),
                            patternArenaSnap,
                            baseArr,
                            propArr,
                            sBaseIds,
                            sPropIds,
                            sMat
                    );
                } else {
                    d = new LcaSectionCodec.ParsedHeaderAndDict(
                            this.formatVersion,
                            storageKind, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                            flags, slabPoolBaseOffset,
                            Collections.unmodifiableList(canonicalStates),
                            Collections.unmodifiableList(baseBlockNames),
                            Collections.unmodifiableList(singletonBasePalettes),
                            Collections.unmodifiableList(biomes),
                            Collections.unmodifiableList(singletonBiomePalettes),
                            patternArenaSnap
                    );
                }
                this.cachedParsedDict = d;
                return d;
            }
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
            if (pendingWriteCount > 0) {
                PendingChunkSnapshot pending = getPendingPayload(chunkIdx);
                if (pending != null) {
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    int codec1 = pending.modeB ? 1 : 0;
                    return LcaRegionReader.decodeChunkFromRawSlab(
                            getOrBuildParsedHeaderAndDict(),
                            rx, rz, ptrByteOffset, 0L, codec1, pending.payload.length, 0, pending.payload);
                }
            }
            if (headerOnlyChannelMode) {
                FileChannel ch = this.channel;
                if (ch == null || !ch.isOpen()) {
                    if (backingFile != null) {
                        return LcaRegionReader.readChunkFromFile(backingFile, rx, rz);
                    }
                    return null;
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    int epochBefore = this.compactionEpoch;
                    if ((epochBefore & 1) != 0) {
                        synchronized (this) {
                            epochBefore = this.compactionEpoch;
                        }
                    }
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    long ptr = resolveAndHealLivePointer(chunkIdx);
                    long slabOffset35 = ptr >>> 29;
                    int codec1 = (int) ((ptr >>> 28) & 1L);
                    int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                    int generation8 = (int) (ptr & 0xFFL);
                    if (slabOffset35 == 0 || exactByteLen20 == 0) {
                        if (attempt == 0 && this.compactionEpoch != epochBefore) {
                            synchronized (this) {}
                            continue;
                        }
                        return null;
                    }
                    long payloadStartOffset = slabOffset35 << LcaRegionReader.SLAB_SHIFT;
                    long maxEnd = (long) activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                    int hazardSlot = LcaRegionReader.pinHazardSlab((int) slabOffset35);
                    try {
                        if (payloadStartOffset < LcaRegionReader.DICT_ROOT_OFFSET
                                || exactByteLen20 > LcaRegionReader.MAX_CHUNK_UNCOMPRESSED_BYTES
                                || payloadStartOffset + exactByteLen20 > maxEnd) {
                            throw new CorruptRegionEntryException("CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds");
                        }
                        byte[] rawSlab = readAndHealChannelSlab(
                                ch, backingFile, rx, rz, payloadStartOffset, codec1, exactByteLen20);
                        VarHandle.loadLoadFence();
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        int epochAfter = this.compactionEpoch;
                        if ((ptrAfter != ptr || epochAfter != epochBefore) && attempt == 0) {
                            if (epochAfter != epochBefore) {
                                synchronized (this) {}
                            }
                            continue;
                        }
                        return LcaRegionReader.decodeChunkFromRawSlab(
                                getOrBuildParsedHeaderAndDict(),
                                rx, rz, ptrByteOffset, ptr, codec1, exactByteLen20, generation8, rawSlab);
                    } catch (java.nio.channels.ClosedChannelException e) {
                        if (backingFile != null) {
                            return LcaRegionReader.readChunkFromFile(backingFile, rx, rz);
                        }
                        throw e;
                    } catch (CorruptRegionEntryException e) {
                        if (!ch.isOpen() && backingFile != null) {
                            return LcaRegionReader.readChunkFromFile(backingFile, rx, rz);
                        }
                        int epochNow = this.compactionEpoch;
                        if (attempt == 0 && (epochNow != epochBefore || (epochNow & 1) != 0)) {
                            synchronized (this) {}
                            continue;
                        }
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        if (attempt == 0 && (ptrAfter != ptr || tryRecoverLiveChunkPointer(rx, rz, chunkIdx, ch))) {
                            continue;
                        }
                        throw e;
                    } finally {
                        LcaRegionReader.unpinHazardSlab(hazardSlot);
                    }
                }
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                int epochBefore = this.compactionEpoch;
                if ((epochBefore & 1) != 0) {
                    synchronized (this) {
                        epochBefore = this.compactionEpoch;
                    }
                }
                resolveAndHealLivePointer(chunkIdx);
                byte[] buf = this.buffer;
                int len = Math.min(buf.length, (this.activeEndSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE);
                try {
                    AnvilReader.ChunkEntry decoded = LcaRegionReader.INSTANCE.readChunk(buf, len, rx, rz);
                    VarHandle.loadLoadFence();
                    if (attempt == 0 && this.compactionEpoch != epochBefore) {
                        synchronized (this) {}
                        continue;
                    }
                    return decoded;
                } catch (CorruptRegionEntryException e) {
                    int epochNow = this.compactionEpoch;
                    if (attempt == 0 && (epochNow != epochBefore || (epochNow & 1) != 0)) {
                        synchronized (this) {}
                        continue;
                    }
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
        @SuppressWarnings("java:S1168")
        public byte[] readChunkNbtBytes(int rx, int rz) throws IOException {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
            }
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            if (pendingWriteCount > 0) {
                PendingChunkSnapshot pending = getPendingPayload(chunkIdx);
                if (pending != null) {
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    int codec1 = pending.modeB ? 1 : 0;
                    return LcaRegionReader.decodeChunkNbtBytesFromRawSlab(
                            getOrBuildParsedHeaderAndDict(),
                            rx, rz, ptrByteOffset, 0L, codec1, pending.payload.length, 0, pending.payload);
                }
            }
            FileChannel ch;
            Path bf;
            synchronized (this) {
                ch = this.channel;
                bf = this.backingFile;
            }
            if (headerOnlyChannelMode) {
                if (ch == null || !ch.isOpen()) {
                    if (bf != null) {
                        return LcaRegionReader.readChunkNbtBytesFromFile(bf, rx, rz);
                    }
                    return null;
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    int epochBefore = this.compactionEpoch;
                    if ((epochBefore & 1) != 0) {
                        synchronized (this) {
                            epochBefore = this.compactionEpoch;
                        }
                    }
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    long ptr = resolveAndHealLivePointer(chunkIdx);
                    long slabOffset35 = ptr >>> 29;
                    int codec1 = (int) ((ptr >>> 28) & 1L);
                    int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                    int generation8 = (int) (ptr & 0xFFL);
                    if (slabOffset35 == 0 || exactByteLen20 == 0) {
                        if (attempt == 0 && this.compactionEpoch != epochBefore) {
                            synchronized (this) {}
                            continue;
                        }
                        return null;
                    }
                    long payloadStartOffset = slabOffset35 << LcaRegionReader.SLAB_SHIFT;
                    long maxEnd = (long) this.activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                    int hazardSlot = LcaRegionReader.pinHazardSlab((int) slabOffset35);
                    try {
                        if (payloadStartOffset < LcaRegionReader.DICT_ROOT_OFFSET
                                || exactByteLen20 > LcaRegionReader.MAX_CHUNK_UNCOMPRESSED_BYTES
                                || payloadStartOffset + exactByteLen20 > maxEnd) {
                            throw new CorruptRegionEntryException("CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds");
                        }
                        byte[] rawSlab = readAndHealChannelSlab(
                                ch, bf, rx, rz, payloadStartOffset, codec1, exactByteLen20);
                        VarHandle.loadLoadFence();
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        int epochAfter = this.compactionEpoch;
                        if ((ptrAfter != ptr || epochAfter != epochBefore) && attempt == 0) {
                            if (epochAfter != epochBefore) {
                                synchronized (this) {}
                            }
                            continue;
                        }
                        return LcaRegionReader.decodeChunkNbtBytesFromRawSlab(
                                getOrBuildParsedHeaderAndDict(),
                                rx, rz, ptrByteOffset, ptr, codec1, exactByteLen20, generation8, rawSlab);
                    } catch (java.nio.channels.ClosedChannelException e) {
                        if (bf != null) {
                            return LcaRegionReader.readChunkNbtBytesFromFile(bf, rx, rz);
                        }
                        throw e;
                    } catch (CorruptRegionEntryException e) {
                        if (!ch.isOpen() && bf != null) {
                            return LcaRegionReader.readChunkNbtBytesFromFile(bf, rx, rz);
                        }
                        int epochNow = this.compactionEpoch;
                        if (attempt == 0 && (epochNow != epochBefore || (epochNow & 1) != 0)) {
                            synchronized (this) {}
                            continue;
                        }
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        if (attempt == 0 && (ptrAfter != ptr || tryRecoverLiveChunkPointer(rx, rz, chunkIdx, ch))) {
                            continue;
                        }
                        throw e;
                    } finally {
                        LcaRegionReader.unpinHazardSlab(hazardSlot);
                    }
                }
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                int epochBefore = this.compactionEpoch;
                if ((epochBefore & 1) != 0) {
                    synchronized (this) {
                        epochBefore = this.compactionEpoch;
                    }
                }
                resolveAndHealLivePointer(chunkIdx);
                byte[] buf = this.buffer;
                int endSlab = this.activeEndSlab;
                int len = Math.min(buf.length, (endSlab << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE);
                try {
                    byte[] decoded = LcaRegionReader.INSTANCE.readChunkNbtBytes(buf, len, rx, rz);
                    VarHandle.loadLoadFence();
                    if (attempt == 0 && this.compactionEpoch != epochBefore) {
                        synchronized (this) {}
                        continue;
                    }
                    return decoded;
                } catch (CorruptRegionEntryException e) {
                    int epochNow = this.compactionEpoch;
                    if (attempt == 0 && (epochNow != epochBefore || (epochNow & 1) != 0)) {
                        synchronized (this) {}
                        continue;
                    }
                    if (attempt == 0 && tryRecoverLiveChunkPointer(rx, rz, chunkIdx, null)) {
                        continue;
                    }
                    throw e;
                }
            }
            return null;
        }

        /**
         * Reads the raw uncompressed `.lca` `ChunkFrame` at `(rx, rz)` using positional `FileChannel`
         * reads + `pinHazardSlab` (Mode A zero-copy read, Mode B pure-Java `LCD2` `0x4C44` unpack)
         * without synthesizing Mojang NBT (`leafanvil-ADR-005`, `leafanvil-ADR-006`, `REQ-RTP-S-002`,
         * `REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        @SuppressWarnings("java:S1168")
        public RawChunkFrameSnapshot readRawChunkFrame(
                int rx,
                int rz,
                java.util.function.Function<String, Integer> runtimeStateLookup
        ) throws IOException {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
            }
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            if (pendingWriteCount > 0) {
                PendingChunkSnapshot pending = getPendingPayload(chunkIdx);
                if (pending != null) {
                    LcaSectionCodec.ParsedHeaderAndDict meta = getOrBuildParsedHeaderAndDict();
                    int[] dictIdToRuntime = resolveDictIdToRuntimeId(runtimeStateLookup);
                    byte[] rawSlab = pending.payload;
                    if (!pending.modeB) {
                        int rawDvWord = LcaSectionCodec.readIntBE(rawSlab, 0);
                        int dv = LcaRegionReader.decodeAndVerifySelfDescribingChunkHeader(rawDvWord, rx, rz);
                        int resRel = LcaSectionCodec.readUint16BE(rawSlab, 22);
                        return new RawChunkFrameSnapshot(
                                rawSlab, dv, minSectionY, sectionCount, resRel, dictIdToRuntime, this.formatVersion);
                    }
                    int headerLen = 24 + (sectionCount << 1);
                    int headerWords = (headerLen + 7) >>> 3;
                    int compStart = headerLen + headerWords;
                    int compLen = rawSlab.length - compStart;
                    byte[] fullFrame = compLen <= 0
                            ? Arrays.copyOf(rawSlab, headerLen)
                            : LcaSectionCodec.decodeModeBBodyToFullFrame(rawSlab, 0, headerLen, compStart, compLen, meta);
                    int rawDvWord = LcaSectionCodec.readIntBE(fullFrame, 0);
                    int dv = LcaRegionReader.decodeAndVerifySelfDescribingChunkHeader(rawDvWord, rx, rz);
                    int resRel = LcaSectionCodec.readUint16BE(fullFrame, 22);
                    return new RawChunkFrameSnapshot(
                            fullFrame, dv, minSectionY, sectionCount, resRel, dictIdToRuntime, this.formatVersion);
                }
            }
            FileChannel ch = this.channel;
            Path bf = this.backingFile;
            if (headerOnlyChannelMode) {
                if (ch == null || !ch.isOpen()) {
                    if (bf != null) {
                        return LcaRegionReader.readRawChunkFrameFromFile(bf, rx, rz, runtimeStateLookup);
                    }
                    return null;
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    int epochBefore = this.compactionEpoch;
                    if ((epochBefore & 1) != 0) {
                        synchronized (this) {
                            epochBefore = this.compactionEpoch;
                        }
                    }
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    long ptr = resolveAndHealLivePointer(chunkIdx);
                    long slabOffset35 = ptr >>> 29;
                    int codec1 = (int) ((ptr >>> 28) & 1L);
                    int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                    int generation8 = (int) (ptr & 0xFFL);
                    if (slabOffset35 == 0 || exactByteLen20 == 0) {
                        if (attempt == 0 && this.compactionEpoch != epochBefore) {
                            synchronized (this) {}
                            continue;
                        }
                        return null;
                    }
                    long payloadStartOffset = slabOffset35 << LcaRegionReader.SLAB_SHIFT;
                    long maxEnd = (long) this.activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                    int hazardSlot = LcaRegionReader.pinHazardSlab((int) slabOffset35);
                    try {
                        if (payloadStartOffset < LcaRegionReader.DICT_ROOT_OFFSET
                                || exactByteLen20 > LcaRegionReader.MAX_CHUNK_UNCOMPRESSED_BYTES
                                || payloadStartOffset + exactByteLen20 > maxEnd) {
                            throw new CorruptRegionEntryException("CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds");
                        }
                        byte[] rawSlab = readAndHealChannelSlab(
                                ch, bf, rx, rz, payloadStartOffset, codec1, exactByteLen20);
                        VarHandle.loadLoadFence();
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        int epochAfter = this.compactionEpoch;
                        if ((ptrAfter != ptr || epochAfter != epochBefore) && attempt == 0) {
                            if (epochAfter != epochBefore) {
                                synchronized (this) {}
                            }
                            continue;
                        }
                        LcaSectionCodec.ParsedHeaderAndDict meta = getOrBuildParsedHeaderAndDict();
                        int[] dictIdToRuntime = resolveDictIdToRuntimeId(runtimeStateLookup);
                        if (codec1 == 0) {
                            int rawDvWord = LcaSectionCodec.readIntBE(rawSlab, 0);
                            int dv = LcaRegionReader.decodeAndVerifySelfDescribingChunkHeader(rawDvWord, rx, rz);
                            int resRel = LcaSectionCodec.readUint16BE(rawSlab, 22);
                            return new RawChunkFrameSnapshot(
                                    rawSlab, dv, minSectionY, sectionCount, resRel, dictIdToRuntime, this.formatVersion);
                        }
                        int headerLen = 24 + (sectionCount << 1);
                        int headerWords = (headerLen + 7) >>> 3;
                        int compStart = headerLen + headerWords;
                        int compLen = rawSlab.length - compStart;
                        byte[] fullFrame = compLen <= 0
                                ? Arrays.copyOf(rawSlab, headerLen)
                                : LcaSectionCodec.decodeModeBBodyToFullFrame(rawSlab, 0, headerLen, compStart, compLen, meta);
                        int rawDvWord = LcaSectionCodec.readIntBE(fullFrame, 0);
                        int dv = LcaRegionReader.decodeAndVerifySelfDescribingChunkHeader(rawDvWord, rx, rz);
                        int resRel = LcaSectionCodec.readUint16BE(fullFrame, 22);
                        return new RawChunkFrameSnapshot(
                                fullFrame, dv, minSectionY, sectionCount, resRel, dictIdToRuntime, this.formatVersion);
                    } catch (java.nio.channels.ClosedChannelException e) {
                        if (bf != null) {
                            return LcaRegionReader.readRawChunkFrameFromFile(bf, rx, rz, runtimeStateLookup);
                        }
                        throw e;
                    } catch (CorruptRegionEntryException e) {
                        if (!ch.isOpen() && bf != null) {
                            return LcaRegionReader.readRawChunkFrameFromFile(bf, rx, rz, runtimeStateLookup);
                        }
                        int epochNow = this.compactionEpoch;
                        if (attempt == 0 && (epochNow != epochBefore || (epochNow & 1) != 0)) {
                            synchronized (this) {}
                            continue;
                        }
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        if (attempt == 0 && (ptrAfter != ptr || tryRecoverLiveChunkPointer(rx, rz, chunkIdx, ch))) {
                            continue;
                        }
                        throw e;
                    } finally {
                        LcaRegionReader.unpinHazardSlab(hazardSlot);
                    }
                }
                return null;
            }
            byte[] frame;
            synchronized (this) {
                resolveAndHealLivePointer(chunkIdx);
                frame = readUncompressedChunkFrame(rx, rz);
            }
            if (frame == null) {
                return null;
            }
            int rawDvWord = LcaSectionCodec.readIntBE(frame, 0);
            int dv = LcaRegionReader.decodeAndVerifySelfDescribingChunkHeader(rawDvWord, rx, rz);
            int resRel = LcaSectionCodec.readUint16BE(frame, 22);
            int[] dictIdToRuntime = resolveDictIdToRuntimeId(runtimeStateLookup);
            return new RawChunkFrameSnapshot(
                    frame, dv, minSectionY, sectionCount, resRel, dictIdToRuntime, this.formatVersion);
        }

        /**
         * Reads the raw uncompressed `.lca` `ChunkFrame` at `(rx, rz)` together with lightweight
         * metadata-only Mojang NBT bytes (omitting section {@code block_states}) in a single slab
         * decompression pass (`REQ-RTP-S-005`).
         */
        @SuppressWarnings("java:S1168")
        public LcaRegionReader.RawChunkFrameWithMetadata readRawChunkFrameWithMetadata(
                int rx,
                int rz,
                java.util.function.Function<String, Integer> runtimeStateLookup
        ) throws IOException {
            RawChunkFrameSnapshot frame = readRawChunkFrame(rx, rz, runtimeStateLookup);
            if (frame == null) {
                return null;
            }
            LcaSectionCodec.ParsedHeaderAndDict meta = getOrBuildParsedHeaderAndDict();
            byte[] metadataNbt = LcaRegionReader.encodeChunkMetadataNbtBytesFromSnapshot(meta, frame, rx, rz, true);
            return new LcaRegionReader.RawChunkFrameWithMetadata(frame, metadataNbt);
        }

        /**
         * Reads the raw uncompressed `.lca` `ChunkFrame` at `(rx, rz)` together with zero-NBT
         * decoded chunk metadata ({@link LcaDecodedChunkMeta}) in a single slab decompression pass
         * (`REQ-RTP-S-005`).
         */
        @SuppressWarnings("java:S1168")
        public LcaRegionReader.RawChunkFrameWithDirectMeta readRawChunkFrameWithDirectMeta(
                int rx,
                int rz,
                java.util.function.Function<String, Integer> runtimeStateLookup
        ) throws IOException {
            RawChunkFrameSnapshot frame = readRawChunkFrame(rx, rz, runtimeStateLookup);
            if (frame == null) {
                return null;
            }
            LcaSectionCodec.ParsedHeaderAndDict meta = getOrBuildParsedHeaderAndDict();
            LcaDecodedChunkMeta directMeta =
                    LcaRegionReader.decodeChunkMetadataDirectFromSnapshot(meta, frame, rx, rz);
            return new LcaRegionReader.RawChunkFrameWithDirectMeta(frame, directMeta);
        }

        /**
         * Reads the raw `.lca` chunk slab at `(rx, rz)` with full hazard-pin and compaction-epoch
         * tear-free validation, deferring Mode B decompression and zero-NBT metadata decoding to
         * worker threads (`REQ-RTP-S-005`).
         */
        @SuppressWarnings("java:S1168")
        public LcaRegionReader.DeferredChunkSlab readDeferredChunkSlab(
                int rx,
                int rz,
                java.util.function.Function<String, Integer> runtimeStateLookup
        ) throws IOException {
            if ((rx & ~31) != 0 || (rz & ~31) != 0) {
                throw new IllegalArgumentException("Chunk coordinates out of 0..31 range: (" + rx + "," + rz + ")");
            }
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            if (pendingWriteCount > 0) {
                PendingChunkSnapshot pending = getPendingPayload(chunkIdx);
                if (pending != null) {
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    int codec1 = pending.modeB ? 1 : 0;
                    LcaSectionCodec.ParsedHeaderAndDict meta = getOrBuildParsedHeaderAndDict();
                    int[] dictIdToRuntime = resolveDictIdToRuntimeId(runtimeStateLookup);
                    return new LcaRegionReader.DeferredChunkSlab(
                            rx, rz, ptrByteOffset, 0L, codec1, 0, pending.payload, meta, dictIdToRuntime);
                }
            }
            FileChannel ch = this.channel;
            Path bf = this.backingFile;
            if (headerOnlyChannelMode) {
                if (ch == null || !ch.isOpen()) {
                    if (bf != null) {
                        return LcaRegionReader.readDeferredChunkSlabFromFile(bf, rx, rz, runtimeStateLookup);
                    }
                    return null;
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    int epochBefore = this.compactionEpoch;
                    if ((epochBefore & 1) != 0) {
                        synchronized (this) {
                            epochBefore = this.compactionEpoch;
                        }
                    }
                    int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
                    long ptr = resolveAndHealLivePointer(chunkIdx);
                    long slabOffset35 = ptr >>> 29;
                    int codec1 = (int) ((ptr >>> 28) & 1L);
                    int exactByteLen20 = (int) ((ptr >>> 8) & 0xFFFFFL);
                    int generation8 = (int) (ptr & 0xFFL);
                    if (slabOffset35 == 0 || exactByteLen20 == 0) {
                        if (attempt == 0 && this.compactionEpoch != epochBefore) {
                            synchronized (this) {}
                            continue;
                        }
                        return null;
                    }
                    long payloadStartOffset = slabOffset35 << LcaRegionReader.SLAB_SHIFT;
                    long maxEnd = (long) this.activeEndSlab << LcaRegionReader.SLAB_SHIFT;
                    int hazardSlot = LcaRegionReader.pinHazardSlab((int) slabOffset35);
                    try {
                        if (payloadStartOffset < LcaRegionReader.DICT_ROOT_OFFSET
                                || exactByteLen20 > LcaRegionReader.MAX_CHUNK_UNCOMPRESSED_BYTES
                                || payloadStartOffset + exactByteLen20 > maxEnd) {
                            throw new CorruptRegionEntryException(
                                    "CBIT T3/T4: Chunk pointer (" + rx + "," + rz + ") out of bounds");
                        }
                        byte[] rawSlab = readAndHealChannelSlab(
                                ch, bf, rx, rz, payloadStartOffset, codec1, exactByteLen20);
                        VarHandle.loadLoadFence();
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        int epochAfter = this.compactionEpoch;
                        if ((ptrAfter != ptr || epochAfter != epochBefore) && attempt == 0) {
                            if (epochAfter != epochBefore) {
                                synchronized (this) {}
                            }
                            continue;
                        }
                        LcaSectionCodec.ParsedHeaderAndDict meta = getOrBuildParsedHeaderAndDict();
                        int[] dictIdToRuntime = resolveDictIdToRuntimeId(runtimeStateLookup);
                        return new LcaRegionReader.DeferredChunkSlab(
                                rx, rz, ptrByteOffset, ptr, codec1, generation8, rawSlab, meta, dictIdToRuntime);
                    } catch (java.nio.channels.ClosedChannelException e) {
                        if (bf != null) {
                            return LcaRegionReader.readDeferredChunkSlabFromFile(bf, rx, rz, runtimeStateLookup);
                        }
                        throw e;
                    } catch (CorruptRegionEntryException e) {
                        if (!ch.isOpen() && bf != null) {
                            return LcaRegionReader.readDeferredChunkSlabFromFile(bf, rx, rz, runtimeStateLookup);
                        }
                        int epochNow = this.compactionEpoch;
                        if (attempt == 0 && (epochNow != epochBefore || (epochNow & 1) != 0)) {
                            synchronized (this) {}
                            continue;
                        }
                        long ptrAfter = LcaRegionReader.getVolatileLongBE(this.buffer, ptrByteOffset);
                        if (attempt == 0 && (ptrAfter != ptr || tryRecoverLiveChunkPointer(rx, rz, chunkIdx, ch))) {
                            continue;
                        }
                        throw e;
                    } finally {
                        LcaRegionReader.unpinHazardSlab(hazardSlot);
                    }
                }
                return null;
            }
            byte[] frame;
            synchronized (this) {
                resolveAndHealLivePointer(chunkIdx);
                frame = readUncompressedChunkFrame(rx, rz);
            }
            if (frame == null) {
                return null;
            }
            LcaSectionCodec.ParsedHeaderAndDict meta = getOrBuildParsedHeaderAndDict();
            int[] dictIdToRuntime = resolveDictIdToRuntimeId(runtimeStateLookup);
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            return new LcaRegionReader.DeferredChunkSlab(
                    rx, rz, ptrByteOffset, 0L, 0, 0, frame, meta, dictIdToRuntime);
        }

        /**
         * Splices re-encoded dirty sections (`dirtySectionMask`) with verbatim byte copies of all
         * untouched `SectionFrame`s from `coldChunkFrame`, commits the new slab via Copy-on-Write
         * (`commitPreEncodedChunkCoW`), and returns the new uncompressed `ChunkFrame` buffer
         * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         *
         * <p>{@code dirtySectionVoxels.apply(sectionIdx)} may return either a dense {@code int[4096]}
         * array of archive {@code dictId}s or a sparse array of packed 32-bit
         * {@code ((pos12 + 1) << 19) | dictId19} modifiers.</p>
         */
        public synchronized byte[] spliceAndCommitChunkFrameCoW(
                int rx,
                int rz,
                byte[] coldChunkFrame,
                int dirtySectionMask,
                java.util.function.IntFunction<int[]> dirtySectionVoxels,
                byte[] updatedResidualNbtOrNull
        ) throws IOException {
            return spliceAndCommitChunkFrameCoW(
                    rx, rz, coldChunkFrame, dirtySectionMask, dirtySectionVoxels,
                    null, null, updatedResidualNbtOrNull);
        }

        public synchronized byte[] spliceAndCommitChunkFrameCoW(
                int rx,
                int rz,
                byte[] coldChunkFrame,
                int dirtySectionMask,
                java.util.function.IntFunction<int[]> dirtySectionVoxels,
                java.util.function.IntFunction<byte[]> updatedSectionSkyLight,
                java.util.function.IntFunction<byte[]> updatedSectionBlockLight,
                byte[] updatedResidualNbtOrNull
        ) throws IOException {
            java.util.Objects.requireNonNull(coldChunkFrame, "coldChunkFrame");
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            int sectionDirEnd = 24 + (sectionCount << 1);
            if (coldChunkFrame.length < sectionDirEnd) {
                throw new CorruptRegionEntryException("coldChunkFrame too short: " + coldChunkFrame.length);
            }
            if (dirtySectionMask == 0 && updatedResidualNbtOrNull == null) {
                byte[] cleanFrame = coldChunkFrame.clone();
                patchChunkFrameSelfTag(cleanFrame, chunkIdx);
                if (!hasChunk(rx, rz)) {
                    byte[] payload = writeModeBLcd2
                            ? encodeModeBWithAtomicDict(cleanFrame, sectionDirEnd, Math.max(1, lcd2Level))
                            : cleanFrame;
                    commitPreEncodedChunkCoW(rx, rz, payload);
                }
                return cleanFrame;
            }

            int chunkFlags = coldChunkFrame[21] & 0xFF;
            boolean oldHasResidual = (chunkFlags & 1) != 0;
            boolean hasSlabLocalPalette = (chunkFlags & 2) != 0;
            boolean oldScale4B = (chunkFlags & 4) != 0;
            int oldUnitShift = oldScale4B ? 2 : 0;
            int defaultBiomeCode = (chunkFlags >>> 3) & 0x1F;
            int defaultBiomeDictId = defaultBiomeCode > 0 ? (defaultBiomeCode - 1) : -1;
            int oldResidualRelStart = oldHasResidual
                    ? Math.min(coldChunkFrame.length, sectionDirEnd + (LcaSectionCodec.readUint16BE(coldChunkFrame, 22) << oldUnitShift))
                    : coldChunkFrame.length;
            boolean hasStarlightVersion = oldHasResidual
                    && oldResidualRelStart + 2 <= coldChunkFrame.length
                    && (LcaSectionCodec.readUint16BE(coldChunkFrame, oldResidualRelStart) & 64) != 0;

            int[] oldEntries = new int[sectionCount];
            int[] oldRelStarts = LcaRegionReader.computeSectionRelStarts(
                    coldChunkFrame, 0, coldChunkFrame.length, sectionCount,
                    sectionDirEnd, oldResidualRelStart, oldScale4B, oldEntries);

            if (dirtySectionMask == 0) {
                int oldResidualRelEnd = oldHasResidual
                        ? LcaSectionCodec.findResidualRelEnd(
                                oldRelStarts, sectionCount, oldResidualRelStart, coldChunkFrame.length)
                        : coldChunkFrame.length;
                if (oldResidualRelEnd == coldChunkFrame.length) {
                    if (hasChunk(rx, rz)
                            && oldHasResidual
                            && Arrays.equals(
                                    coldChunkFrame, oldResidualRelStart, coldChunkFrame.length,
                                    updatedResidualNbtOrNull, 0, updatedResidualNbtOrNull.length)) {
                        return coldChunkFrame;
                    }
                    int paddedPrefixLen = oldHasResidual
                            ? oldResidualRelStart
                            : (oldScale4B ? ((oldResidualRelStart + 3) & ~3) : oldResidualRelStart);
                    int newResidualRelOffset = (paddedPrefixLen - sectionDirEnd) >>> oldUnitShift;
                    if (newResidualRelOffset <= 0xFFFF) {
                        boolean newHasResidual = updatedResidualNbtOrNull.length > 0;
                        byte[] newChunkFrame = new byte[paddedPrefixLen + updatedResidualNbtOrNull.length];
                        System.arraycopy(coldChunkFrame, 0, newChunkFrame, 0, oldResidualRelStart);
                        patchChunkFrameSelfTag(newChunkFrame, chunkIdx);
                        ByteBuffer hdrBuf = ByteBuffer.wrap(newChunkFrame, 0, sectionDirEnd);
                        hdrBuf.putLong(4, hdrBuf.getLong(4) + 1L);
                        newChunkFrame[21] = (byte) (newHasResidual ? (chunkFlags | 1) : (chunkFlags & ~1));
                        hdrBuf.putShort(22, (short) newResidualRelOffset);
                        if (newHasResidual) {
                            System.arraycopy(
                                    updatedResidualNbtOrNull, 0,
                                    newChunkFrame, paddedPrefixLen, updatedResidualNbtOrNull.length);
                        }
                        byte[] finalPayload = writeModeBLcd2
                                ? encodeModeBWithAtomicDict(newChunkFrame, sectionDirEnd, Math.max(1, lcd2Level))
                                : newChunkFrame;
                        commitPreEncodedChunkCoW(rx, rz, finalPayload);
                        cachedHeightmapRef[chunkIdx] = null;
                        cachedResidualBytes[chunkIdx] = null;
                        return newChunkFrame;
                    }
                }
            }

            Map<String, Integer> localStateMap = null;
            List<String> localStateList = null;
            List<Integer> localStateGlobalIds = null;
            List<String> localBiomeList = null;
            List<Integer> localBiomeGlobalIds = null;
            List<LcaSectionCodec.BiomeTemplate> biomeTemplates = Collections.emptyList();
            int paletteLimitRel = sectionDirEnd;
            int initialLocalStateCount = 0;
            int initialLocalBiomeCount = 0;
            boolean factoredV3 = isFactoredV3Dict();
            if (hasSlabLocalPalette) {
                paletteLimitRel = LcaSectionCodec.findFirstSectionRelStart(
                        oldRelStarts, sectionCount, oldResidualRelStart);
                LcaSectionCodec.SlabLocalPalette localPal = LcaSectionCodec.decodeSlabLocalPalette(
                        coldChunkFrame, 0, sectionDirEnd, paletteLimitRel, true, true, getOrBuildParsedHeaderAndDict());
                initialLocalStateCount = localPal.canonicalStates.size();
                localStateMap = new LinkedHashMap<>(initialLocalStateCount + 16);
                localStateList = new ArrayList<>(initialLocalStateCount + 16);
                localStateGlobalIds = new ArrayList<>(initialLocalStateCount + 16);
                for (int i = 0; i < initialLocalStateCount; i++) {
                    String canon = localPal.canonicalStates.get(i);
                    localStateMap.put(canon, i);
                    localStateList.add(canon);
                    if (factoredV3) {
                        int gid = (localPal.localStateGlobalIds != null && i < localPal.localStateGlobalIds.length)
                                ? localPal.localStateGlobalIds[i]
                                : registerOrGetBlockStateId(canon);
                        localStateGlobalIds.add(gid);
                    } else {
                        int bracket = canon.indexOf('[');
                        if (bracket >= 0 && LcaV2LegacyCodec.STATIC_CORE_PROP_TO_ID.get(canon.substring(bracket)) == null) {
                            localStateGlobalIds.add(registerOrGetBlockStateId(canon));
                        } else {
                            localStateGlobalIds.add(-1);
                        }
                    }
                }
                localBiomeList = new ArrayList<>(localPal.biomes);
                initialLocalBiomeCount = localBiomeList.size();
                if (factoredV3) {
                    localBiomeGlobalIds = new ArrayList<>(initialLocalBiomeCount + 4);
                    for (int i = 0; i < initialLocalBiomeCount; i++) {
                        int bgid = (localPal.localBiomeGlobalIds != null && i < localPal.localBiomeGlobalIds.length)
                                ? localPal.localBiomeGlobalIds[i]
                                : registerOrGetBiomeId(localBiomeList.get(i));
                        localBiomeGlobalIds.add(bgid);
                    }
                }
                biomeTemplates = localPal.biomeTemplates;
            }

            List<String> activeCanonicalStates = hasSlabLocalPalette ? localStateList : idToBlockState;
            List<String> activeBaseBlockNames = null;
            if (hasSlabLocalPalette) {
                activeBaseBlockNames = new ArrayList<>(localStateList.size());
                for (String cs : localStateList) {
                    int b = cs.indexOf('[');
                    activeBaseBlockNames.add(b >= 0 ? cs.substring(0, b) : cs);
                }
            } else {
                activeBaseBlockNames = getOrBuildParsedHeaderAndDict().baseBlockNames;
            }
            List<String> activeBiomes = hasSlabLocalPalette ? localBiomeList : idToBiome;

            int[] newInlineEntries = new int[sectionCount];
            byte[][] sectionFrames = new byte[sectionCount][];
            int totalBodyBytes = 0;

            int[] scratchDictVoxels = null;
            for (int s = 0; s < sectionCount; s++) {
                boolean isDirty = (dirtySectionMask & (1 << s)) != 0;
                if (!isDirty) {
                    int entry16 = oldEntries[s];
                    if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                        newInlineEntries[s] = entry16;
                    } else {
                        int start = oldRelStarts[s];
                        int end = LcaRegionReader.findSectionRelEnd(
                                oldRelStarts, s, sectionCount, oldResidualRelStart, coldChunkFrame.length);
                        byte[] verbatimSlice = Arrays.copyOfRange(coldChunkFrame, start, end);
                        sectionFrames[s] = verbatimSlice;
                        totalBodyBytes += verbatimSlice.length;
                    }
                    continue;
                }

                int[] supplied = dirtySectionVoxels != null ? dirtySectionVoxels.apply(s) : null;
                byte[] suppliedSky = updatedSectionSkyLight != null ? updatedSectionSkyLight.apply(s) : null;
                byte[] suppliedBlk = updatedSectionBlockLight != null ? updatedSectionBlockLight.apply(s) : null;
                if (supplied == null && suppliedSky == null && suppliedBlk == null) {
                    int entry16 = oldEntries[s];
                    if (entry16 == 0 || (entry16 & 0x8000) != 0) {
                        newInlineEntries[s] = entry16;
                    } else {
                        int start = oldRelStarts[s];
                        int end = LcaRegionReader.findSectionRelEnd(
                                oldRelStarts, s, sectionCount, oldResidualRelStart, coldChunkFrame.length);
                        byte[] verbatimSlice = Arrays.copyOfRange(coldChunkFrame, start, end);
                        sectionFrames[s] = verbatimSlice;
                        totalBodyBytes += verbatimSlice.length;
                    }
                    continue;
                }

                int sectionY = minSectionY + s;
                LcaSectionCodec.DecodedSection dec;
                int oldEntry = oldEntries[s];
                if (oldEntry == 0 || (oldEntry & 0x8000) != 0) {
                    dec = LcaSectionCodec.createUniformDecodedSection(
                            sectionY, oldEntry, hasSlabLocalPalette,
                            activeCanonicalStates, activeBaseBlockNames, activeBiomes,
                            biomeTemplates, defaultBiomeDictId, false, false);
                } else {
                    int start = oldRelStarts[s];
                    int end = LcaRegionReader.findSectionRelEnd(
                            oldRelStarts, s, sectionCount, oldResidualRelStart, coldChunkFrame.length);
                    dec = LcaSectionCodec.decodeSectionFrame(
                            coldChunkFrame, start, end, sectionY,
                            activeCanonicalStates, activeBaseBlockNames, activeBiomes,
                            biomeTemplates, false, false, this.formatVersion);
                }

                if (scratchDictVoxels == null) {
                    scratchDictVoxels = new int[4096];
                }
                boolean isDense4096 = supplied != null && supplied.length == 4096 && (supplied[0] >>> 19) == 0;
                if (isDense4096) {
                    System.arraycopy(supplied, 0, scratchDictVoxels, 0, 4096);
                } else {
                    int palSize = dec.blockStateCanonicalPalette.size();
                    int[] palGlobalIds = new int[Math.max(1, palSize)];
                    for (int p = 0; p < palSize; p++) {
                        palGlobalIds[p] = registerOrGetBlockStateId(dec.blockStateCanonicalPalette.get(p));
                    }
                    if (palSize <= 1 || dec.packedBlockLongs == null || dec.packedBlockLongs.length == 0) {
                        Arrays.fill(scratchDictVoxels, palGlobalIds[0]);
                    } else {
                        int bits = PackedPaletteDecoder.bitsPerEntry(palSize);
                        int entriesPerLong = 64 / bits;
                        long mask = (1L << bits) - 1L;
                        int v = 0;
                        for (int w = 0; w < dec.packedBlockLongs.length && v < 4096; w++) {
                            long word = dec.packedBlockLongs[w];
                            int limit = Math.min(entriesPerLong, 4096 - v);
                            for (int k = 0; k < limit; k++) {
                                int ord = (int) (word & mask);
                                scratchDictVoxels[v++] = palGlobalIds[ord < palSize ? ord : 0];
                                word >>>= bits;
                            }
                        }
                        while (v < 4096) {
                            scratchDictVoxels[v++] = palGlobalIds[0];
                        }
                    }
                    if (supplied != null) {
                        for (int entry32 : supplied) {
                            if (entry32 != 0) {
                                int pos12 = LcaSectionBacking.unpackPos12(entry32) & 4095;
                                int dictId = LcaSectionBacking.unpackStateId19(entry32);
                                scratchDictVoxels[pos12] = dictId;
                            }
                        }
                    }
                }

                // Build compact section palette (either slab-local indices or archive global dictIds) + Mojang packed longs
                Map<Integer, Integer> secPalOrdByGlobalId = new LinkedHashMap<>(16);
                List<Integer> secPalEncodedIds = new ArrayList<>(16);
                int[] voxOrdinals = new int[4096];
                for (int v = 0; v < 4096; v++) {
                    int gId = scratchDictVoxels[v];
                    Integer ord = secPalOrdByGlobalId.get(gId);
                    if (ord == null) {
                        ord = secPalEncodedIds.size();
                        secPalOrdByGlobalId.put(gId, ord);
                        if (hasSlabLocalPalette) {
                            String canon = (gId >= 0 && gId < idToBlockState.size())
                                    ? idToBlockState.get(gId)
                                    : "minecraft:air";
                            Integer localIdx = localStateMap.get(canon);
                            if (localIdx == null) {
                                localIdx = localStateList.size();
                                localStateMap.put(canon, localIdx);
                                localStateList.add(canon);
                                int bracket = canon.indexOf('[');
                                if (factoredV3) {
                                    localStateGlobalIds.add(gId);
                                } else if (bracket >= 0 && LcaV2LegacyCodec.STATIC_CORE_PROP_TO_ID.get(canon.substring(bracket)) == null) {
                                    localStateGlobalIds.add(gId);
                                } else {
                                    localStateGlobalIds.add(-1);
                                }
                                activeBaseBlockNames.add(bracket >= 0 ? canon.substring(0, bracket) : canon);
                            }
                            secPalEncodedIds.add(localIdx);
                        } else {
                            secPalEncodedIds.add(gId);
                        }
                    }
                    voxOrdinals[v] = ord;
                }
                int[] rawBlockPalIds = new int[secPalEncodedIds.size()];
                for (int p = 0; p < rawBlockPalIds.length; p++) {
                    rawBlockPalIds[p] = secPalEncodedIds.get(p);
                }
                long[] packedBlockData = null;
                if (rawBlockPalIds.length > 1) {
                    int bits = PackedPaletteDecoder.bitsPerEntry(rawBlockPalIds.length);
                    int entriesPerLong = 64 / bits;
                    long mask = (1L << bits) - 1L;
                    int words = (4096 + entriesPerLong - 1) / entriesPerLong;
                    packedBlockData = new long[words];
                    int pos = 0;
                    for (int w = 0; w < words; w++) {
                        long word = 0L;
                        int shift = 0;
                        int limit = Math.min(4096, pos + entriesPerLong);
                        while (pos < limit) {
                            word |= ((long) voxOrdinals[pos++] & mask) << shift;
                            shift += bits;
                        }
                        packedBlockData[w] = word;
                    }
                }

                int[] biomeIds = null;
                if (!dec.biomePalette.isEmpty()) {
                    biomeIds = new int[dec.biomePalette.size()];
                    for (int b = 0; b < biomeIds.length; b++) {
                        String bName = dec.biomePalette.get(b);
                        if (hasSlabLocalPalette) {
                            int bIdx = localBiomeList.indexOf(bName);
                            if (bIdx < 0) {
                                bIdx = localBiomeList.size();
                                localBiomeList.add(bName);
                                if (localBiomeGlobalIds != null) {
                                    localBiomeGlobalIds.add(registerOrGetBiomeId(bName));
                                }
                            }
                            biomeIds[b] = bIdx;
                        } else {
                            biomeIds[b] = registerOrGetBiomeId(bName);
                        }
                    }
                }
                int secBiomeTmplIdx = -1;
                if (hasSlabLocalPalette && !biomeTemplates.isEmpty() && biomeIds != null) {
                    for (int t = 0; t < biomeTemplates.size() && t < 4; t++) {
                        LcaSectionCodec.BiomeTemplate tmpl = biomeTemplates.get(t);
                        if (tmpl.palette.equals(dec.biomePalette)
                                && Arrays.equals(tmpl.packedBiomeLongs, dec.packedBiomeLongs)) {
                            secBiomeTmplIdx = t;
                            break;
                        }
                    }
                }

                byte[] effectiveSkyLight = suppliedSky != null ? suppliedSky : dec.skyLight2048;
                byte[] effectiveBlkLight = suppliedBlk != null ? suppliedBlk : dec.blockLight2048;
                if (hasStarlightVersion
                        && rawBlockPalIds.length == 1
                        && oldEntry != 0
                        && (oldEntry & 0x8000) == 0
                        && isNullOrAllZeroLight(effectiveSkyLight)) {
                    // Preserve Starlight underground dark state (state == 1 -> ALL_ZERO_LIGHT_2048)
                    // when a previously non-inline dark section is mined out to uniform air.
                    String singleCanon = hasSlabLocalPalette
                            ? localStateList.get(rawBlockPalIds[0])
                            : idToBlockState.get(rawBlockPalIds[0]);
                    if ("minecraft:air".equals(singleCanon)
                            || "minecraft:cave_air".equals(singleCanon)
                            || "minecraft:void_air".equals(singleCanon)) {
                        effectiveSkyLight = ALL_ZERO_LIGHT_2048;
                    }
                }

                byte[] secLightV1 = (this.formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3
                        && this.storageKind != 3
                        && (effectiveSkyLight != null || effectiveBlkLight != null))
                        ? LcaV3Codec.buildStatePackedLightV1(hasSlabLocalPalette ? localStateList : idToBlockState)
                        : null;
                LcaSectionCodec.EncodedSection enc = LcaSectionCodec.encodeSection(
                        rawBlockPalIds, packedBlockData, biomeIds, dec.packedBiomeLongs,
                        effectiveSkyLight, effectiveBlkLight, defaultBiomeDictId, secBiomeTmplIdx,
                        this.formatVersion, secLightV1, writeModeBLcd2 ? atomicDictLitLen() : null,
                        this.directFlatBitPack);
                if (enc.inlineEntry16 != 0) {
                    newInlineEntries[s] = enc.inlineEntry16;
                } else {
                    sectionFrames[s] = enc.frameBytes;
                    totalBodyBytes += enc.frameBytes.length;
                }
            }

            byte[] slabLocalPaletteBytes = LcaSectionCodec.EMPTY_BYTES;
            if (hasSlabLocalPalette) {
                if (localStateList.size() == initialLocalStateCount
                        && localBiomeList.size() == initialLocalBiomeCount) {
                    slabLocalPaletteBytes = Arrays.copyOfRange(coldChunkFrame, sectionDirEnd, paletteLimitRel);
                } else {
                    int[] globalIdArr = new int[localStateGlobalIds.size()];
                    for (int i = 0; i < globalIdArr.length; i++) {
                        globalIdArr[i] = localStateGlobalIds.get(i);
                    }
                    if (factoredV3) {
                        int[] biomeGlobalIdArr = new int[localBiomeGlobalIds.size()];
                        for (int i = 0; i < biomeGlobalIdArr.length; i++) {
                            biomeGlobalIdArr[i] = localBiomeGlobalIds.get(i);
                        }
                        slabLocalPaletteBytes = LcaSectionCodec.encodePureIdMicroPalette(
                                globalIdArr, globalIdArr.length,
                                biomeGlobalIdArr, biomeGlobalIdArr.length,
                                biomeTemplates);
                    } else {
                        slabLocalPaletteBytes = LcaSectionCodec.encodeSlabLocalPalette(
                                localStateList, globalIdArr, localBiomeList, biomeTemplates);
                    }
                }
                totalBodyBytes += slabLocalPaletteBytes.length;
            }

            byte[] residualTailBytes;
            if (updatedResidualNbtOrNull != null) {
                residualTailBytes = updatedResidualNbtOrNull;
            } else if (oldHasResidual && oldResidualRelStart < coldChunkFrame.length) {
                int oldResidualRelEnd = LcaSectionCodec.findResidualRelEnd(
                        oldRelStarts, sectionCount, oldResidualRelStart, coldChunkFrame.length);
                residualTailBytes = oldResidualRelEnd > oldResidualRelStart
                        ? Arrays.copyOfRange(coldChunkFrame, oldResidualRelStart, oldResidualRelEnd)
                        : LcaSectionCodec.EMPTY_BYTES;
            } else {
                residualTailBytes = LcaSectionCodec.EMPTY_BYTES;
            }

            boolean newScale4B = totalBodyBytes > 0x7FFF;
            int newUnitShift = newScale4B ? 2 : 0;
            int newUnitAlign = 1 << newUnitShift;

            ByteArrayOutputStream sectionsBody = new ByteArrayOutputStream(totalBodyBytes + 16);
            if (hasSlabLocalPalette && slabLocalPaletteBytes.length > 0) {
                sectionsBody.writeBytes(slabLocalPaletteBytes);
            }
            int[] newSectionEntries = new int[sectionCount];
            for (int i = 0; i < sectionCount; i++) {
                byte[] secFrame = sectionFrames[i];
                if (secFrame == null) {
                    newSectionEntries[i] = newInlineEntries[i];
                } else {
                    while ((sectionsBody.size() & (newUnitAlign - 1)) != 0) {
                        sectionsBody.write(0);
                    }
                    int startRel = sectionsBody.size();
                    int entry16 = (startRel >>> newUnitShift) + 1;
                    if (entry16 > 0x7FFF) {
                        throw new IOException("Section offset exceeded 15-bit range: " + entry16);
                    }
                    newSectionEntries[i] = entry16;
                    sectionsBody.writeBytes(secFrame);
                }
            }
            if (newScale4B) {
                while ((sectionsBody.size() & 3) != 0) {
                    sectionsBody.write(0);
                }
            }
            int newResidualRelOffset = sectionsBody.size() >>> newUnitShift;
            if (newResidualRelOffset > 0xFFFF) {
                throw new IOException("Residual offset exceeded 16-bit range: " + newResidualRelOffset);
            }

            boolean newHasResidual = residualTailBytes.length > 0;
            int newChunkFlags = (newHasResidual ? 1 : 0)
                    | (hasSlabLocalPalette ? 2 : 0)
                    | (newScale4B ? 4 : 0)
                    | (defaultBiomeCode << 3);

            byte[] secBodyBytes = sectionsBody.toByteArray();
            byte[] newChunkFrame = new byte[sectionDirEnd + secBodyBytes.length + residualTailBytes.length];
            System.arraycopy(coldChunkFrame, 0, newChunkFrame, 0, 24);
            patchChunkFrameSelfTag(newChunkFrame, chunkIdx);
            ByteBuffer hdrBuf = ByteBuffer.wrap(newChunkFrame, 0, sectionDirEnd);
            long prevTick = hdrBuf.getLong(4);
            hdrBuf.putLong(4, prevTick + 1L);
            newChunkFrame[21] = (byte) newChunkFlags;
            hdrBuf.putShort(22, (short) newResidualRelOffset);
            for (int i = 0; i < sectionCount; i++) {
                hdrBuf.putShort(24 + (i << 1), (short) newSectionEntries[i]);
            }
            System.arraycopy(secBodyBytes, 0, newChunkFrame, sectionDirEnd, secBodyBytes.length);
            if (newHasResidual) {
                System.arraycopy(
                        residualTailBytes, 0,
                        newChunkFrame, sectionDirEnd + secBodyBytes.length, residualTailBytes.length);
            }

            byte[] finalPayload = writeModeBLcd2
                    ? encodeModeBWithAtomicDict(newChunkFrame, sectionDirEnd, Math.max(1, lcd2Level))
                    : newChunkFrame;
            commitPreEncodedChunkCoW(rx, rz, finalPayload);
            cachedHeightmapRef[chunkIdx] = null;
            cachedResidualBytes[chunkIdx] = null;
            return newChunkFrame;
        }

        private static boolean isNullOrAllZeroLight(byte[] light2048) {
            if (light2048 == null || light2048 == ALL_ZERO_LIGHT_2048) {
                return true;
            }
            for (byte b : light2048) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * `RegionFile#getChunkDataInputStream(ChunkPos)` equivalent: returns a {@link DataInputStream}
         * over the uncompressed Mojang binary NBT root `TAG_Compound` for `(rx, rz)`, or `null` if
         * the chunk is unallocated. Decoding is deferred until the first stream read so callers such
         * as Paper Moonrise and `C2ME` decode on their parallel worker pools rather than the single
         * I/O coordinator thread.
         */
        @SuppressWarnings("java:S1130")
        public DataInputStream getChunkDataInputStream(int rx, int rz) throws IOException {
            if (!hasChunk(rx, rz)) {
                return null;
            }
            return new DataInputStream(new java.io.BufferedInputStream(
                    new LazyChunkNbtInputStream(() -> readChunkNbtBytes(rx, rz)),
                    8192));
        }

        /**
         * `RegionFile#getChunkDataOutputStream(ChunkPos)` equivalent: returns a {@link DataOutputStream}
         * backed by a buffered commit-on-close slice stream that parses the written uncompressed Mojang
         * binary NBT `TAG_Compound` outside the region lock and commits it via {@link #saveChunkCoW(int, int, Map)}.
         */
        public DataOutputStream getChunkDataOutputStream(int rx, int rz) {
            if ((rx | rz | (31 - rx) | (31 - rz)) < 0) {
                throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
            }
            OutputStream commitOut = new OutputStream() {
                private byte[] buf = new byte[65536];
                private int count;
                private boolean closed;

                private void ensureCap(int needed) {
                    if (needed > buf.length) {
                        int next = Math.max(needed, buf.length << 1);
                        buf = Arrays.copyOf(buf, next);
                    }
                }

                @Override
                public void write(int b) {
                    ensureCap(count + 1);
                    buf[count++] = (byte) b;
                }

                @Override
                public void write(byte[] src, int off, int len) {
                    java.util.Objects.checkFromIndexSize(off, len, src.length);
                    if (len > 0) {
                        ensureCap(count + len);
                        System.arraycopy(src, off, buf, count, len);
                        count += len;
                    }
                }

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    if (count > 0) {
                        try {
                            saveChunkNbtBytes(rx, rz, buf, 0, count);
                        } catch (UncheckedIOException e) {
                            throw e.getCause() != null ? e.getCause() : new IOException(e);
                        }
                    }
                }
            };
            return new DataOutputStream(new java.io.BufferedOutputStream(commitOut, 8192));
        }

        /**
         * Parses an uncompressed Mojang binary NBT root `TAG_Compound` byte array and saves it via
         * {@link #saveChunkCoW(int, int, Map)}.
         */
        public int saveChunkNbtBytes(int rx, int rz, byte[] uncompressedNbtBytes) throws IOException {
            if (uncompressedNbtBytes == null) {
                throw new IOException("Chunk NBT byte array is null");
            }
            return saveChunkNbtBytes(rx, rz, uncompressedNbtBytes, 0, uncompressedNbtBytes.length);
        }

        /**
         * Parses an uncompressed Mojang binary NBT root `TAG_Compound` slice in a single streaming
         * pass via {@link LcaStreamingNbtEncoder} and commits the encoded chunk via Copy-on-Write
         * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        public int saveChunkNbtBytes(
                int rx, int rz, byte[] uncompressedNbtBytes, int offset, int length
        ) throws IOException {
            if (uncompressedNbtBytes == null || offset < 0 || length <= 0
                    || offset + length > uncompressedNbtBytes.length) {
                throw new IOException("Invalid chunk NBT byte slice: offset=" + offset + ", length=" + length);
            }
            return saveChunkNbtBuffer(rx, rz, ByteBuffer.wrap(uncompressedNbtBytes, offset, length));
        }

        /**
         * Parses an uncompressed Mojang binary NBT root `TAG_Compound` {@link ByteBuffer} in a
         * single streaming pass via {@link LcaStreamingNbtEncoder} and commits the encoded chunk
         * under {@code synchronized (this)} with dictionary rollback safety (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        public synchronized int saveChunkNbtBuffer(
                int rx, int rz, ByteBuffer uncompressedNbt
        ) throws IOException {
            int baseBlockBookmark = idToBaseBlock.size();
            int propTupleBookmark = idToPropTuple.size();
            int stateBookmark = idToBlockState.size();
            int biomeBookmark = idToBiome.size();
            int patternBookmark = syncedPatternBytes;
            int segBookmark = dictSegments.size();
            try {
                byte[] payload = LcaStreamingNbtEncoder.encodeChunkSlabAtLocalCoords(this, rx, rz, uncompressedNbt);
                int written = commitPreEncodedChunkCoW(rx, rz, payload);
                int chunkIdx = (rx & 31) | ((rz & 31) << 5);
                cachedHeightmapRef[chunkIdx] = null;
                cachedResidualBytes[chunkIdx] = null;
                return written;
            } catch (RuntimeException | Error | IOException e) {
                rollbackDictionaryToBookmark(
                        baseBlockBookmark, propTupleBookmark, stateBookmark, biomeBookmark, patternBookmark, segBookmark);
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }
        }

        /**
         * Clears the chunk pointer for `(rx, rz)` and returns its slabs to the CoW grace ring.
         */
        public synchronized boolean removeChunk(int rx, int rz) {
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            boolean removedPending = false;
            if (pendingPayloads != null && pendingPayloads[chunkIdx] != null) {
                pendingWriteBytes -= pendingPayloads[chunkIdx].length;
                pendingPayloads[chunkIdx] = null;
                pendingWriteCount = Math.max(0, pendingWriteCount - 1);
                removedPending = true;
            }
            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long oldPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOffset);
            int oldSlabStart = (int) (oldPtr >>> 29);
            int oldByteLen = (int) ((oldPtr >>> 8) & 0xFFFFFL);
            if (oldSlabStart == 0 || oldByteLen == 0) {
                return removedPending;
            }
            ensureFreeSlabPoolInitialized();
            dirty = true;
            prevChunkPointers[chunkIdx] = oldPtr;
            buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(0L);
            LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, 0L);
            persistPointerAndSecDed(chunkIdx);

            int oldSlabs = (oldByteLen + 255) >>> LcaRegionReader.SLAB_SHIFT;
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
            graceSlabStarts[graceCursor] = oldSlabStart;
            graceSlabCounts[graceCursor] = oldSlabs;
            graceCursor = (graceCursor + 1) & (COW_GRACE_EPOCHS - 1);
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
            if (pendingWriteCount > 0) {
                flushPendingWrites();
            }
            this.readAheadRef = null;
            dirty = true;
            // Reclaim all grace-ring and unpinned deferred slabs first
            Arrays.fill(graceSlabStarts, 0);
            Arrays.fill(graceSlabCounts, 0);
            Arrays.fill(graceRetireEpochs, 0L);
            graceCursor = 0;
            deferredRetireSlabs.clear();
            freeByStart.clear();
            freeBySize.clear();
            freeSlabPoolInitialized = true;

            // Collect all live chunk slabs in index order (single coalesced pread in headerOnlyChannelMode)
            byte[][] liveChunkPayloads = new byte[1024][];
            int[] liveChunkGens = new int[1024];
            boolean[] liveChunkModeB = new boolean[1024];
            int totalLiveSlabCount = 0;
            int poolBaseByteOff = slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT;
            int poolEndByteOff = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
            byte[] coalescedPool = null;
            if (headerOnlyChannelMode && channel != null && channel.isOpen() && poolEndByteOff > poolBaseByteOff) {
                coalescedPool = new byte[poolEndByteOff - poolBaseByteOff];
                readChannelRangeStatic(channel, poolBaseByteOff, coalescedPool, 0, coalescedPool.length, backingFile);
            }
            for (int i = 0; i < 1024; i++) {
                long ptr = LcaRegionReader.getVolatileLongBE(
                        buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3));
                int slabStart = (int) (ptr >>> 29);
                int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                if (slabStart > 0 && byteLen > 0) {
                    int byteOff = slabStart << LcaRegionReader.SLAB_SHIFT;
                    if (coalescedPool != null && byteOff >= poolBaseByteOff
                            && (byteOff - poolBaseByteOff) + byteLen <= coalescedPool.length) {
                        int relOff = byteOff - poolBaseByteOff;
                        liveChunkPayloads[i] = Arrays.copyOfRange(coalescedPool, relOff, relOff + byteLen);
                    } else if (headerOnlyChannelMode && channel != null && channel.isOpen()) {
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

            if (backingFile != null) {
                LcaRegionReader.invalidateFileCache(backingFile);
            }
            compactionEpoch++;
            try {
                if (headerOnlyChannelMode && channel != null && channel.isOpen()) {
                    for (int s = 0; s < dictSegments.size(); s++) {
                        flushDictSegment(dictSegments.get(s));
                    }
                    int chunkStartSlab = cursorSlab;
                    int chunkStartByteOff = chunkStartSlab << LcaRegionReader.SLAB_SHIFT;
                    int totalChunkPoolBytes = (totalLiveSlabCount << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE;
                    byte[] packedChunkPool = new byte[totalChunkPoolBytes];
                    long[] newPtrs = new long[1024];
                    for (int i = 0; i < 1024; i++) {
                        byte[] payload = liveChunkPayloads[i];
                        if (payload == null) {
                            newPtrs[i] = 0L;
                            continue;
                        }
                        int slabs = (payload.length + 255) >>> LcaRegionReader.SLAB_SHIFT;
                        int targetSlab = cursorSlab;
                        cursorSlab += slabs;
                        int relDst = (targetSlab - chunkStartSlab) << LcaRegionReader.SLAB_SHIFT;
                        System.arraycopy(payload, 0, packedChunkPool, relDst, payload.length);
                        newPtrs[i] = packChunkPointer64(targetSlab, liveChunkModeB[i], payload.length, liveChunkGens[i]);
                    }
                    int newEndSlab = cursorSlab;
                    int footerRelOff = (newEndSlab - chunkStartSlab) << LcaRegionReader.SLAB_SHIFT;
                    LcaRegionReader.setVolatileLongBE(packedChunkPool, footerRelOff, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                    // Write compacted slabs to disk BEFORE publishing new pointers to lock-free readers
                    writeChannelRange(chunkStartByteOff, packedChunkPool, 0, packedChunkPool.length);
                    VarHandle.releaseFence();
                    Arrays.fill(prevChunkPointers, 0L);
                    for (int i = 0; i < 1024; i++) {
                        int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3);
                        long newPtr = newPtrs[i];
                        buffer[secDedTableOffset + i] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
                        LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, newPtr);
                    }
                    activeEndSlab = newEndSlab;
                    lastSyncedFooterOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
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
                    channel.truncate(activeByteLength());
                    return;
                }

                ensureBufferCapacity(((cursorSlab + totalLiveSlabCount) << LcaRegionReader.SLAB_SHIFT) + LcaRegionReader.FOOTER_SIZE + 4096);
                for (int s = 0; s < dictSegments.size(); s++) {
                    flushDictSegment(dictSegments.get(s));
                }
                Arrays.fill(prevChunkPointers, 0L);
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
                    VarHandle.releaseFence();
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
            } finally {
                VarHandle.releaseFence();
                compactionEpoch++;
                if (backingFile != null) {
                    LcaRegionReader.invalidateFileCache(backingFile);
                }
            }
        }

        /**
         * Off-thread region archive flush (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         * Flushes any pending writes to the underlying {@link FileChannel} (and issues a hardware
         * `channel.force(false)` barrier when `-Dleafanvil.fsync=true` is enabled).
         */
        public synchronized void flush() throws IOException {
            if (pendingWriteCount > 0) {
                flushPendingWrites();
            }
            if (!dirty) {
                return;
            }
            writeFooterCanary();
            if (channel != null && channel.isOpen()
                    && (Boolean.getBoolean("leafanvil.fsync") || Boolean.getBoolean("leafarchive.fsync"))) {
                channel.force(false);
                diskChannelForceCount++;
                lastForcedWriteCount = diskChannelWriteCount;
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
                if (pendingWriteCount > 0) {
                    flushPendingWrites();
                }
                if (dirty) {
                    writeFooterCanary();
                    if (backingFile != null) {
                        LcaRegionReader.invalidateFileCache(backingFile);
                    }
                    dirty = false;
                }
            } finally {
                this.readAheadRef = null;
                unregisterOpenLiveArchive(backingFile, this);
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
         * Off-thread region archive close (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         * Flushes pending writes, forces dirty/written channels to disk when
         * `-Dleafanvil.fsync.onClose=true` (default `true`), and closes the backing {@link FileChannel}.
         */
        @Override
        public synchronized void close() throws IOException {
            try {
                boolean wasDirty = dirty || pendingWriteCount > 0;
                if (wasDirty) {
                    flush();
                }
                if (channel != null
                        && channel.isOpen()
                        && (wasDirty || diskChannelWriteCount > lastForcedWriteCount)
                        && isFsyncOnCloseEnabled()) {
                    channel.force(false);
                    diskChannelForceCount++;
                    lastForcedWriteCount = diskChannelWriteCount;
                }
            } finally {
                this.readAheadRef = null;
                unregisterOpenLiveArchive(backingFile, this);
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
            int baseBlockBookmark = idToBaseBlock.size();
            int propTupleBookmark = idToPropTuple.size();
            int stateBookmark = idToBlockState.size();
            int biomeBookmark = idToBiome.size();
            int patternBookmark = syncedPatternBytes;
            int segBookmark = dictSegments.size();
            dirty = true;
            byte[] payload;
            try {
                payload = encodeChunkPayload(rx, rz, chunkRoot);
            } catch (RuntimeException | Error | IOException e) {
                rollbackDictionaryToBookmark(
                        baseBlockBookmark, propTupleBookmark, stateBookmark, biomeBookmark, patternBookmark, segBookmark);
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
                long ptr = packChunkPointer64(targetSlab, writeModeBLcd2, payload.length, 1);
                buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(ptr);
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, ptr);
                persistPointerAndSecDed(chunkIdx);
            } catch (RuntimeException | Error e) {
                buffer[secDedTableOffset + chunkIdx] = oldSyn;
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, oldPtr);
                rollbackAllocatedSlabs(targetSlab, slabsNeeded, prevActiveEndSlab, prevLastSyncedFooter, false);
                rollbackDictionaryToBookmark(
                        baseBlockBookmark, propTupleBookmark, stateBookmark, biomeBookmark, patternBookmark, segBookmark);
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
            int prevStart = (int) (prevPtr >>> 29);
            int prevByteLen = (int) ((prevPtr >>> 8) & 0xFFFFFL);
            if (prevStart == 0 || prevByteLen == 0) {
                return false;
            }
            int prevEnd = prevStart + ((prevByteLen + 255) >>> LcaRegionReader.SLAB_SHIFT);
            for (int g = 0; g < COW_GRACE_EPOCHS; g++) {
                int gStart = graceSlabStarts[g];
                int gCount = graceSlabCounts[g];
                if (gStart > 0 && gCount > 0 && gStart < prevEnd && (gStart + gCount) > prevStart) {
                    graceSlabStarts[g] = 0;
                    graceSlabCounts[g] = 0;
                }
            }
            for (int d = deferredRetireSlabs.size() - 1; d >= 0; d--) {
                long[] item = deferredRetireSlabs.get(d);
                int dStart = (int) item[0];
                int dCount = (int) item[1];
                if (dStart > 0 && dCount > 0 && dStart < prevEnd && (dStart + dCount) > prevStart) {
                    deferredRetireSlabs.remove(d);
                }
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
         * rotation (`anvil-api-ADR-003` Section 2.3, `REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        public synchronized int saveChunkCoW(int rx, int rz, Map<String, Object> chunkRoot) throws IOException {
            int baseBlockBookmark = idToBaseBlock.size();
            int propTupleBookmark = idToPropTuple.size();
            int stateBookmark = idToBlockState.size();
            int biomeBookmark = idToBiome.size();
            int patternBookmark = syncedPatternBytes;
            int segBookmark = dictSegments.size();
            byte[] payload;
            try {
                payload = encodeChunkPayload(rx, rz, chunkRoot);
                return commitPreEncodedChunkCoW(rx, rz, payload);
            } catch (RuntimeException | Error | IOException e) {
                rollbackDictionaryToBookmark(
                        baseBlockBookmark, propTupleBookmark, stateBookmark, biomeBookmark, patternBookmark, segBookmark);
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }
        }

        /**
         * Encodes and commits a freshly generated chunk directly from pre-extracted Mojang
         * section palettes, packed {@code SimpleBitStorage} {@code long[]} arrays, light arrays,
         * and a pre-encoded residual metadata tail — bypassing {@code CompoundTag},
         * {@code BlockState.CODEC}, and {@code NbtIo} for all voxel/biome/light sections
         * ({@code anvil-api-ADR-003}, {@code REQ-RTP-S-004}).
         */
        public synchronized int saveFreshChunkDirect(
                int rx,
                int rz,
                int dataVersion,
                long lastUpdate,
                long inhabitedTime,
                String statusName,
                String[][] sectionBlockPalettes,
                long[][] sectionBlockData,
                String[][] sectionBiomePalettes,
                long[][] sectionBiomeData,
                byte[][] sectionSkyLight,
                byte[][] sectionBlockLight,
                byte[] residualMetadataTailBytes
        ) throws IOException {
            int baseBlockBookmark = idToBaseBlock.size();
            int propTupleBookmark = idToPropTuple.size();
            int stateBookmark = idToBlockState.size();
            int biomeBookmark = idToBiome.size();
            int patternBookmark = syncedPatternBytes;
            int segBookmark = dictSegments.size();
            try {
                byte[] payload = encodeFreshChunkPayloadDirect(
                        rx,
                        rz,
                        dataVersion,
                        lastUpdate,
                        inhabitedTime,
                        statusName,
                        sectionBlockPalettes,
                        sectionBlockData,
                        sectionBiomePalettes,
                        sectionBiomeData,
                        sectionSkyLight,
                        sectionBlockLight,
                        residualMetadataTailBytes
                );
                int chunkIdx = (rx & 31) | ((rz & 31) << 5);
                cachedHeightmapRef[chunkIdx] = null;
                cachedResidualBytes[chunkIdx] = null;
                return commitPreEncodedChunkCoW(rx, rz, payload);
            } catch (RuntimeException | Error | IOException e) {
                rollbackDictionaryToBookmark(
                        baseBlockBookmark, propTupleBookmark, stateBookmark, biomeBookmark, patternBookmark, segBookmark);
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }
        }

        /**
         * Pre-registers canonical block-state strings in this region's Layer 2 dictionary and
         * returns their deterministic global dictionary IDs for GPU/DMA dictionary LUT binding.
         */
        public synchronized int[] preRegisterBlockStates(String[] canonicalStates) {
            if (canonicalStates == null || canonicalStates.length == 0) {
                return new int[0];
            }
            int[] ids = new int[canonicalStates.length];
            for (int i = 0; i < canonicalStates.length; i++) {
                String canon = canonicalStates[i] != null ? canonicalStates[i] : "minecraft:air";
                ids[i] = registerOrGetBlockStateId(canon);
            }
            return ids;
        }

        /**
         * Zero-copy slice commit variant writing {@code payload[0..payloadLen)} directly from a
         * caller-owned reusable staging buffer.
         */
        public synchronized int commitPreEncodedChunkCoWSlice(int rx, int rz, byte[] payload, int payloadLen) throws IOException {
            if (payload == null || payloadLen < 24 || payloadLen > payload.length) {
                return 0;
            }
            boolean prevDirty = this.dirty;
            dirty = true;
            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            patchChunkFrameSelfTag(payload, chunkIdx);

            if (bufferedWritesEnabled && headerOnlyChannelMode && channel != null && channel.isOpen()) {
                byte[] ownedPayload = (payloadLen == payload.length) ? payload : Arrays.copyOf(payload, payloadLen);
                int headerLen = 24 + (sectionCount << 1);
                int headerWords = (headerLen + 7) >>> 3;
                boolean payloadModeB = writeModeBLcd2
                        || LcaRegionReader.probeLooksLikeModeB(ownedPayload, headerLen, headerWords, payloadLen);
                if (pendingPayloads == null) {
                    pendingPayloads = new byte[1024][];
                    pendingPayloadModeB = new boolean[1024];
                }
                if (pendingPayloads[chunkIdx] != null) {
                    pendingWriteBytes -= pendingPayloads[chunkIdx].length;
                    coalescedWriteCount++;
                } else {
                    pendingWriteCount++;
                }
                pendingPayloads[chunkIdx] = ownedPayload;
                pendingPayloadModeB[chunkIdx] = payloadModeB;
                pendingWriteBytes += payloadLen;
                if (pendingWriteCount >= writeBackMaxChunks || pendingWriteBytes >= writeBackMaxBytes) {
                    flushPendingWrites();
                }
                return payloadLen;
            }

            int ptrByteOffset = LcaRegionReader.POINTER_DIRECTORY_OFFSET + (chunkIdx << 3);
            long oldPtr = LcaRegionReader.getVolatileLongBE(buffer, ptrByteOffset);
            byte oldSyn = buffer[secDedTableOffset + chunkIdx];
            int oldSlabStart = (int) (oldPtr >>> 29);
            int oldByteLen = (int) ((oldPtr >>> 8) & 0xFFFFFL);
            int oldGen = (int) (oldPtr & 0xFFL);

            int newSlabs = Math.max(1, (payloadLen + 255) >>> LcaRegionReader.SLAB_SHIFT);

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
                writeSlabBytesSlice(newSlabStart, payload, payloadLen);
                writeFooterCanary();

                VarHandle.releaseFence();
                int nextGen = (oldGen + 1) & 0xFF;
                int headerLen = 24 + (sectionCount << 1);
                int headerWords = (headerLen + 7) >>> 3;
                boolean payloadModeB = writeModeBLcd2
                        || LcaRegionReader.probeLooksLikeModeB(payload, headerLen, headerWords, payloadLen);
                long newPtr = packChunkPointer64(newSlabStart, payloadModeB, payloadLen, nextGen);
                buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, newPtr);
                persistPointerAndSecDed(chunkIdx);
                this.readAheadRef = null;
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
            return payloadLen;
        }

        /**
         * Zero-re-encode Unified LCA Mode A direct save path. Streams pre-encoded
         * {@link LcaModeABlockSection} payloads, GPU/in-memory {@code BLOCK_MODE_FLAT_BITPACK}
         * ({@code mode 10}) bytes, and hot {@code SimpleBitStorage} sections directly into
         * {@link LcaSectionCodec.ScratchArena#chunkFrameStaging} in one pass with zero
         * {@code ByteArrayOutputStream}, zero per-section {@code byte[]}, and zero CPU
         * Hilbert/Roaring/Gray-code re-encoding ({@code anvil-api-ADR-003}, {@code REQ-RTP-S-004}).
         */
        public synchronized int saveFreshChunkDirectModeA(
                int rx,
                int rz,
                int dataVersion,
                long lastUpdate,
                long inhabitedTime,
                String statusName,
                String[][] sectionBlockPalettes,
                LcaModeABlockSection[] sectionPreEncodedModeA,
                byte[][] sectionPreEncodedFlatBytes,
                int[] sectionPreEncodedFlatBits,
                long[][] sectionBlockData,
                String[][] sectionBiomePalettes,
                long[][] sectionBiomeData,
                byte[][] sectionSkyLight,
                byte[][] sectionBlockLight,
                byte[] residualMetadataTailBytes
        ) throws IOException {
            return saveFreshChunkDirectModeA(
                    rx, rz, dataVersion, lastUpdate, inhabitedTime, statusName,
                    sectionBlockPalettes, sectionPreEncodedModeA, sectionPreEncodedFlatBytes,
                    sectionPreEncodedFlatBits, null, null, null,
                    sectionBlockData, sectionBiomePalettes, sectionBiomeData,
                    sectionSkyLight, sectionBlockLight, residualMetadataTailBytes);
        }

        public synchronized int saveFreshChunkDirectModeA(
                int rx,
                int rz,
                int dataVersion,
                long lastUpdate,
                long inhabitedTime,
                String statusName,
                String[][] sectionBlockPalettes,
                LcaModeABlockSection[] sectionPreEncodedModeA,
                byte[][] sectionPreEncodedFlatBytes,
                int[] sectionPreEncodedFlatBits,
                int[][] sectionPreResolvedDictIds,
                long[] sectionPrecomputedOctreeMasks,
                boolean[] sectionHasPrecomputedOctreeMask,
                long[][] sectionBlockData,
                String[][] sectionBiomePalettes,
                long[][] sectionBiomeData,
                byte[][] sectionSkyLight,
                byte[][] sectionBlockLight,
                byte[] residualMetadataTailBytes
        ) throws IOException {
            int baseBlockBookmark = idToBaseBlock.size();
            int propTupleBookmark = idToPropTuple.size();
            int stateBookmark = idToBlockState.size();
            int biomeBookmark = idToBiome.size();
            int patternBookmark = syncedPatternBytes;
            int segBookmark = dictSegments.size();
            try {
                int chunkDataVersion = dataVersion > 0 ? dataVersion : defaultDataVersion;
                int statusOrdinal = LcaRegionReader.encodeStatusOrdinal(statusName);

                LcaSectionCodec.ScratchArena arena = LcaSectionCodec.scratch();
                boolean factoredV3 = isFactoredV3Dict();
                boolean useSlabLocalPalette = (storageKind == LcaRegionReader.STORAGE_KIND_REGION_VOXEL)
                        && (factoredV3 || idToBlockState.size() < 8192);
                boolean canUsePreResolvedIds = factoredV3 && worldgenDictDirectMatch
                        && boundWorldgenDictIds != null && sectionPreResolvedDictIds != null;
                int boundDictLen = canUsePreResolvedIds ? boundWorldgenDictIds.length : 0;
                int[] globalToLocal = (useSlabLocalPalette && canUsePreResolvedIds)
                        ? arena.resetGlobalToLocal(boundDictLen)
                        : null;
                Map<String, Integer> localStateMap = useSlabLocalPalette ? new LinkedHashMap<>(32) : null;
                List<String> localStateList = useSlabLocalPalette ? new ArrayList<>(32) : null;
                List<Integer> localStateGlobalIds = useSlabLocalPalette ? new ArrayList<>(32) : null;
                Map<String, Integer> localBiomeMap = useSlabLocalPalette ? new LinkedHashMap<>(8) : null;
                List<String> localBiomeList = useSlabLocalPalette ? new ArrayList<>(8) : null;
                List<Integer> localBiomeGlobalIds = (useSlabLocalPalette && factoredV3) ? new ArrayList<>(8) : null;
                if (useSlabLocalPalette) {
                    localStateMap.put("minecraft:air", 0);
                    localStateList.add("minecraft:air");
                    int airGlobalId = factoredV3 ? registerOrGetBlockStateId("minecraft:air") : -1;
                    localStateGlobalIds.add(airGlobalId);
                    if (globalToLocal != null && airGlobalId >= 0 && airGlobalId < boundDictLen) {
                        globalToLocal[airGlobalId] = 0;
                    }
                    localBiomeMap.put("minecraft:plains", 0);
                    localBiomeList.add("minecraft:plains");
                    if (factoredV3) {
                        localBiomeGlobalIds.add(registerOrGetBiomeId("minecraft:plains"));
                    }
                }

                int[][] sectionBlockDictIds = new int[sectionCount][];
                int[][] sectionBiomeIds = new int[sectionCount][];
                long[][] resolvedBiomeData = new long[sectionCount][];
                int chunkDefaultBiomeDictId = -1;
                int bestBiomeCount = 0;
                int[] biomeCandidateIds = new int[8];
                int[] biomeCandidateCounts = new int[8];
                int biomeCandidatesUsed = 0;

                List<LcaSectionCodec.BiomeTemplate> rawTemplates = useSlabLocalPalette ? new ArrayList<>(8) : Collections.emptyList();
                int[] rawTemplateCounts = useSlabLocalPalette ? new int[24] : null;
                int[] sectionRawTemplateIdx = new int[sectionCount];
                Arrays.fill(sectionRawTemplateIdx, -1);

                int nonEmptySections = 0;
                int extraModeAPayloadBytes = 0;
                for (int i = 0; i < sectionCount; i++) {
                    String[] bp = (sectionBlockPalettes != null && i < sectionBlockPalettes.length) ? sectionBlockPalettes[i] : null;
                    int[] preIds = (canUsePreResolvedIds && i < sectionPreResolvedDictIds.length)
                            ? sectionPreResolvedDictIds[i]
                            : null;
                    if ((bp == null || bp.length == 0) && (preIds == null || preIds.length == 0)) {
                        continue;
                    }
                    nonEmptySections++;
                    boolean useFastIds = false;
                    if (preIds != null && preIds.length > 0 && (bp == null || bp.length == preIds.length)) {
                        useFastIds = true;
                        for (int p = 0; p < preIds.length; p++) {
                            int gid = preIds[p];
                            if (gid < 0 || gid >= boundDictLen) {
                                useFastIds = false;
                                break;
                            }
                        }
                    }
                    int[] blockIds;
                    if (useFastIds) {
                        blockIds = new int[preIds.length];
                        if (useSlabLocalPalette) {
                            for (int p = 0; p < preIds.length; p++) {
                                int gid = preIds[p];
                                int localIdx = globalToLocal[gid];
                                if (localIdx < 0) {
                                    localIdx = localStateGlobalIds.size();
                                    globalToLocal[gid] = localIdx;
                                    localStateGlobalIds.add(gid);
                                }
                                blockIds[p] = localIdx;
                            }
                        } else {
                            System.arraycopy(preIds, 0, blockIds, 0, preIds.length);
                        }
                    } else {
                        blockIds = new int[bp.length];
                        for (int p = 0; p < bp.length; p++) {
                            String canon = bp[p] != null ? bp[p] : "minecraft:air";
                            if (useSlabLocalPalette) {
                                Integer existing = localStateMap.get(canon);
                                if (existing != null) {
                                    blockIds[p] = existing;
                                } else {
                                    int gid = factoredV3 ? registerOrGetBlockStateId(canon) : -1;
                                    if (globalToLocal != null && gid >= 0 && gid < boundDictLen && globalToLocal[gid] >= 0) {
                                        int localIdx = globalToLocal[gid];
                                        localStateMap.put(canon, localIdx);
                                        blockIds[p] = localIdx;
                                    } else {
                                        int localIdx = localStateGlobalIds.size();
                                        localStateMap.put(canon, localIdx);
                                        localStateList.add(canon);
                                        localStateGlobalIds.add(gid);
                                        if (globalToLocal != null && gid >= 0 && gid < boundDictLen) {
                                            globalToLocal[gid] = localIdx;
                                        }
                                        blockIds[p] = localIdx;
                                    }
                                }
                            } else {
                                blockIds[p] = registerOrGetBlockStateId(canon);
                            }
                        }
                    }
                    sectionBlockDictIds[i] = blockIds;
                    if (sectionPreEncodedModeA != null && i < sectionPreEncodedModeA.length && sectionPreEncodedModeA[i] != null) {
                        extraModeAPayloadBytes += sectionPreEncodedModeA[i].payloadByteLength();
                    }

                    String[] biomeNames = (sectionBiomePalettes != null && i < sectionBiomePalettes.length) ? sectionBiomePalettes[i] : null;
                    int[] bIds = null;
                    if (biomeNames != null && biomeNames.length > 0) {
                        bIds = new int[biomeNames.length];
                        for (int b = 0; b < biomeNames.length; b++) {
                            String bName = biomeNames[b] != null ? biomeNames[b] : "minecraft:plains";
                            if (useSlabLocalPalette) {
                                Integer existing = localBiomeMap.get(bName);
                                if (existing != null) {
                                    bIds[b] = existing;
                                } else {
                                    int localIdx = localBiomeList.size();
                                    localBiomeMap.put(bName, localIdx);
                                    localBiomeList.add(bName);
                                    if (localBiomeGlobalIds != null) {
                                        localBiomeGlobalIds.add(registerOrGetBiomeId(bName));
                                    }
                                    bIds[b] = localIdx;
                                }
                            } else {
                                bIds[b] = registerOrGetBiomeId(bName);
                            }
                        }
                    }
                    long[] bData = (sectionBiomeData != null && i < sectionBiomeData.length) ? sectionBiomeData[i] : null;
                    sectionBiomeIds[i] = bIds;
                    resolvedBiomeData[i] = bData;

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
                            sectionRawTemplateIdx[i] = foundT;
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

                byte[] slabLocalPaletteBytes;
                if (!useSlabLocalPalette) {
                    slabLocalPaletteBytes = new byte[0];
                } else if (factoredV3) {
                    int[] localStateGlobalIdArr = new int[localStateGlobalIds.size()];
                    for (int k = 0; k < localStateGlobalIdArr.length; k++) {
                        localStateGlobalIdArr[k] = localStateGlobalIds.get(k);
                    }
                    int[] localBiomeGlobalIdArr = new int[localBiomeGlobalIds.size()];
                    for (int k = 0; k < localBiomeGlobalIdArr.length; k++) {
                        localBiomeGlobalIdArr[k] = localBiomeGlobalIds.get(k);
                    }
                    slabLocalPaletteBytes = LcaSectionCodec.encodePureIdMicroPalette(
                            localStateGlobalIdArr, localStateGlobalIdArr.length,
                            localBiomeGlobalIdArr, localBiomeGlobalIdArr.length,
                            sortedBiomeTemplates);
                } else {
                    int[] localStateGlobalIdArr = new int[localStateGlobalIds.size()];
                    for (int k = 0; k < localStateGlobalIdArr.length; k++) {
                        localStateGlobalIdArr[k] = localStateGlobalIds.get(k);
                    }
                    slabLocalPaletteBytes = LcaSectionCodec.encodeSlabLocalPalette(
                            localStateList, localStateGlobalIdArr, localBiomeList, sortedBiomeTemplates);
                }

                byte[] residualBytes = residualMetadataTailBytes != null ? residualMetadataTailBytes : new byte[0];
                int headerLen = 24 + (sectionCount << 1);
                int worstCaseCap = headerLen
                        + slabLocalPaletteBytes.length
                        + (nonEmptySections * 9216)
                        + extraModeAPayloadBytes
                        + residualBytes.length
                        + 512;
                byte[] frameBuf = arena.ensureChunkFrameCap(worstCaseCap);

                final boolean scale4B = true;
                int bodyPos = headerLen;
                if (useSlabLocalPalette && slabLocalPaletteBytes.length > 0) {
                    System.arraycopy(slabLocalPaletteBytes, 0, frameBuf, bodyPos, slabLocalPaletteBytes.length);
                    bodyPos += slabLocalPaletteBytes.length;
                }

                int[] sectionOffsetEntries = arena.sectionEntries;
                for (int i = 0; i < sectionCount; i++) {
                    int[] blockIds = sectionBlockDictIds[i];
                    if (blockIds == null || blockIds.length == 0) {
                        sectionOffsetEntries[i] = 0x0000;
                        continue;
                    }
                    while (((bodyPos - headerLen) & 3) != 0) {
                        frameBuf[bodyPos++] = 0;
                    }
                    int startRel = bodyPos - headerLen;
                    LcaModeABlockSection preModeA = (sectionPreEncodedModeA != null && i < sectionPreEncodedModeA.length)
                            ? sectionPreEncodedModeA[i] : null;
                    byte[] preFlatBytes = (sectionPreEncodedFlatBytes != null && i < sectionPreEncodedFlatBytes.length)
                            ? sectionPreEncodedFlatBytes[i] : null;
                    int preFlatBits = (sectionPreEncodedFlatBits != null && i < sectionPreEncodedFlatBits.length)
                            ? sectionPreEncodedFlatBits[i] : 0;
                    boolean hasPreOctree = (sectionHasPrecomputedOctreeMask != null
                            && i < sectionHasPrecomputedOctreeMask.length
                            && sectionHasPrecomputedOctreeMask[i]
                            && sectionPrecomputedOctreeMasks != null
                            && i < sectionPrecomputedOctreeMasks.length);
                    long preOctree = hasPreOctree ? sectionPrecomputedOctreeMasks[i] : 0L;
                    long[] blockData = (sectionBlockData != null && i < sectionBlockData.length) ? sectionBlockData[i] : null;
                    byte[] skyLight = (sectionSkyLight != null && i < sectionSkyLight.length) ? sectionSkyLight[i] : null;
                    byte[] blkLight = (sectionBlockLight != null && i < sectionBlockLight.length) ? sectionBlockLight[i] : null;
                    int secTmplIdx = (oldToSortedTemplate != null && sectionRawTemplateIdx[i] >= 0)
                            ? oldToSortedTemplate[sectionRawTemplateIdx[i]]
                            : -1;

                    int res = LcaSectionCodec.encodeSectionModeAPassthroughToBuf(
                            blockIds,
                            preModeA,
                            preFlatBytes,
                            preFlatBits,
                            hasPreOctree,
                            preOctree,
                            blockData,
                            sectionBiomeIds[i],
                            resolvedBiomeData[i],
                            skyLight,
                            blkLight,
                            chunkDefaultBiomeDictId,
                            secTmplIdx,
                            arena,
                            frameBuf,
                            bodyPos
                    );
                    if (res < 0) {
                        sectionOffsetEntries[i] = -res;
                    } else {
                        int entry16 = (startRel >>> 2) + 1;
                        if (entry16 > 0x7FFF) {
                            throw new IOException("Section offset exceeded 15-bit range with scale4B=true: " + entry16);
                        }
                        sectionOffsetEntries[i] = entry16;
                        bodyPos += res;
                    }
                }
                while (((bodyPos - headerLen) & 3) != 0) {
                    frameBuf[bodyPos++] = 0;
                }
                int residualNbtRelOffset = (bodyPos - headerLen) >>> 2;
                if (residualNbtRelOffset > 0xFFFF) {
                    throw new IOException("Residual offset exceeded 16-bit range: " + residualNbtRelOffset);
                }

                boolean hasResidualNbt = residualBytes.length > 0;
                if (hasResidualNbt) {
                    System.arraycopy(residualBytes, 0, frameBuf, bodyPos, residualBytes.length);
                    bodyPos += residualBytes.length;
                }

                int defaultBiomeCode = (chunkDefaultBiomeDictId >= 0 && chunkDefaultBiomeDictId < 30)
                        ? (chunkDefaultBiomeDictId + 1)
                        : 0;
                int chunkFlags = (hasResidualNbt ? 1 : 0)
                        | (useSlabLocalPalette ? 2 : 0)
                        | (scale4B ? 4 : 0)
                        | ((defaultBiomeCode & 0x1F) << 3);

                int chunkIdx = (rx & 31) | ((rz & 31) << 5);
                int selfDescribingDvWord = LcaRegionReader.CHUNK_FRAME_SELF_TAG_MAGIC
                        | ((chunkIdx & 0x03FF) << 16)
                        | (chunkDataVersion & 0xFFFF);
                ByteBuffer bb = ByteBuffer.wrap(frameBuf, 0, headerLen);
                bb.putInt(0, selfDescribingDvWord);
                bb.putLong(4, lastUpdate);
                bb.putLong(12, inhabitedTime);
                frameBuf[20] = (byte) statusOrdinal;
                frameBuf[21] = (byte) chunkFlags;
                bb.putShort(22, (short) residualNbtRelOffset);
                for (int i = 0; i < sectionCount; i++) {
                    bb.putShort(24 + (i << 1), (short) sectionOffsetEntries[i]);
                }

                cachedHeightmapRef[chunkIdx] = null;
                cachedResidualBytes[chunkIdx] = null;
                if (writeModeBLcd2) {
                    byte[] modeBPayload = encodeModeBWithAtomicDict(
                            Arrays.copyOf(frameBuf, bodyPos), headerLen, Math.max(1, lcd2Level));
                    return commitPreEncodedChunkCoWSlice(rx, rz, modeBPayload, modeBPayload.length);
                }
                return commitPreEncodedChunkCoWSlice(rx, rz, frameBuf, bodyPos);
            } catch (RuntimeException | Error | IOException e) {
                rollbackDictionaryToBookmark(
                        baseBlockBookmark, propTupleBookmark, stateBookmark, biomeBookmark, patternBookmark, segBookmark);
                if (e instanceof UncheckedIOException uio) {
                    throw uio.getCause() != null ? uio.getCause() : new IOException(uio);
                }
                throw e;
            }
        }

        private byte[] encodeFreshChunkPayloadDirect(
                int rx,
                int rz,
                int dataVersion,
                long lastUpdate,
                long inhabitedTime,
                String statusName,
                String[][] sectionBlockPalettes,
                long[][] sectionBlockData,
                String[][] sectionBiomePalettes,
                long[][] sectionBiomeData,
                byte[][] sectionSkyLight,
                byte[][] sectionBlockLight,
                byte[] residualMetadataTailBytes
        ) throws IOException {
            int chunkDataVersion = dataVersion > 0 ? dataVersion : defaultDataVersion;
            int statusOrdinal = LcaRegionReader.encodeStatusOrdinal(statusName);

            boolean factoredV3 = isFactoredV3Dict();
            boolean useSlabLocalPalette = (storageKind == LcaRegionReader.STORAGE_KIND_REGION_VOXEL)
                    && (factoredV3 || idToBlockState.size() < 8192);
            Map<String, Integer> localStateMap = useSlabLocalPalette ? new LinkedHashMap<>(32) : null;
            List<String> localStateList = useSlabLocalPalette ? new ArrayList<>(32) : null;
            List<Integer> localStateGlobalIds = useSlabLocalPalette ? new ArrayList<>(32) : null;
            Map<String, Integer> localBiomeMap = useSlabLocalPalette ? new LinkedHashMap<>(8) : null;
            List<String> localBiomeList = useSlabLocalPalette ? new ArrayList<>(8) : null;
            List<Integer> localBiomeGlobalIds = (useSlabLocalPalette && factoredV3) ? new ArrayList<>(8) : null;
            if (useSlabLocalPalette) {
                localStateMap.put("minecraft:air", 0);
                localStateList.add("minecraft:air");
                localStateGlobalIds.add(factoredV3 ? registerOrGetBlockStateId("minecraft:air") : -1);
                localBiomeMap.put("minecraft:plains", 0);
                localBiomeList.add("minecraft:plains");
                if (factoredV3) {
                    localBiomeGlobalIds.add(registerOrGetBiomeId("minecraft:plains"));
                }
            }

            int[][] sectionBiomeIds = new int[sectionCount][];
            long[][] resolvedBiomeData = new long[sectionCount][];
            int chunkDefaultBiomeDictId = -1;
            int bestBiomeCount = 0;
            int[] biomeCandidateIds = new int[8];
            int[] biomeCandidateCounts = new int[8];
            int biomeCandidatesUsed = 0;

            List<LcaSectionCodec.BiomeTemplate> rawTemplates = useSlabLocalPalette ? new ArrayList<>(8) : Collections.emptyList();
            int[] rawTemplateCounts = useSlabLocalPalette ? new int[24] : null;
            int[] sectionRawTemplateIdx = new int[sectionCount];
            Arrays.fill(sectionRawTemplateIdx, -1);

            for (int i = 0; i < sectionCount; i++) {
                String[] bp = (sectionBlockPalettes != null && i < sectionBlockPalettes.length) ? sectionBlockPalettes[i] : null;
                if (bp == null || bp.length == 0) {
                    continue;
                }
                String[] biomeNames = (sectionBiomePalettes != null && i < sectionBiomePalettes.length) ? sectionBiomePalettes[i] : null;
                int[] bIds = null;
                if (biomeNames != null && biomeNames.length > 0) {
                    bIds = new int[biomeNames.length];
                    for (int b = 0; b < biomeNames.length; b++) {
                        String bName = biomeNames[b] != null ? biomeNames[b] : "minecraft:plains";
                        if (useSlabLocalPalette) {
                            Integer existing = localBiomeMap.get(bName);
                            if (existing != null) {
                                bIds[b] = existing;
                            } else {
                                int localIdx = localBiomeList.size();
                                localBiomeMap.put(bName, localIdx);
                                localBiomeList.add(bName);
                                if (localBiomeGlobalIds != null) {
                                    localBiomeGlobalIds.add(registerOrGetBiomeId(bName));
                                }
                                bIds[b] = localIdx;
                            }
                        } else {
                            bIds[b] = registerOrGetBiomeId(bName);
                        }
                    }
                }
                long[] bData = (sectionBiomeData != null && i < sectionBiomeData.length) ? sectionBiomeData[i] : null;
                sectionBiomeIds[i] = bIds;
                resolvedBiomeData[i] = bData;

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
                        sectionRawTemplateIdx[i] = foundT;
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
            boolean allowV3Eikonal = this.formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3
                    && this.storageKind != 3
                    && (this.writeModeBLcd2 || (LcaV3Codec.currentFeatureMask() & LcaV3Codec.FEATURE_EIKONAL_MODE_A) != 0);
            int[] modeBLitLen = writeModeBLcd2 ? atomicDictLitLen() : null;
            byte[] cachedLightV1 = null;
            int cachedLightV1Len = 0;

            for (int i = 0; i < sectionCount; i++) {
                String[] bp = (sectionBlockPalettes != null && i < sectionBlockPalettes.length) ? sectionBlockPalettes[i] : null;
                if (bp == null || bp.length == 0) {
                    continue;
                }
                int[] blockDictIds = new int[bp.length];
                for (int p = 0; p < bp.length; p++) {
                    String canon = bp[p] != null ? bp[p] : "minecraft:air";
                    if (useSlabLocalPalette) {
                        Integer existing = localStateMap.get(canon);
                        if (existing != null) {
                            blockDictIds[p] = existing;
                        } else {
                            int localIdx = localStateList.size();
                            localStateMap.put(canon, localIdx);
                            localStateList.add(canon);
                            localStateGlobalIds.add(factoredV3 ? registerOrGetBlockStateId(canon) : -1);
                            blockDictIds[p] = localIdx;
                        }
                    } else {
                        blockDictIds[p] = registerOrGetBlockStateId(canon);
                    }
                }
                long[] blockData = (sectionBlockData != null && i < sectionBlockData.length) ? sectionBlockData[i] : null;
                int[] biomeDictIds = sectionBiomeIds[i];
                long[] biomeData = resolvedBiomeData[i];
                byte[] skyLight = (sectionSkyLight != null && i < sectionSkyLight.length) ? sectionSkyLight[i] : null;
                byte[] blkLight = (sectionBlockLight != null && i < sectionBlockLight.length) ? sectionBlockLight[i] : null;
                int secTmplIdx = (oldToSortedTemplate != null && sectionRawTemplateIdx[i] >= 0)
                        ? oldToSortedTemplate[sectionRawTemplateIdx[i]]
                        : -1;

                byte[] secLightV1 = null;
                if (allowV3Eikonal && (skyLight != null || blkLight != null)) {
                    List<String> activeStates = useSlabLocalPalette ? localStateList : idToBlockState;
                    int curSize = activeStates.size();
                    if (cachedLightV1 == null || cachedLightV1.length < curSize) {
                        cachedLightV1 = cachedLightV1 == null
                                ? new byte[Math.max(64, curSize)]
                                : Arrays.copyOf(cachedLightV1, Math.max(curSize, cachedLightV1.length << 1));
                    }
                    for (int sIdx = cachedLightV1Len; sIdx < curSize; sIdx++) {
                        cachedLightV1[sIdx] = LcaV3Codec.classifyLightV1(activeStates.get(sIdx));
                    }
                    cachedLightV1Len = curSize;
                    secLightV1 = cachedLightV1;
                }

                LcaSectionCodec.EncodedSection enc = LcaSectionCodec.encodeSection(
                        blockDictIds, blockData, biomeDictIds, biomeData,
                        skyLight, blkLight, chunkDefaultBiomeDictId, secTmplIdx,
                        this.formatVersion, secLightV1, modeBLitLen, this.directFlatBitPack);
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
            byte[] slabLocalPaletteBytes;
            if (!useSlabLocalPalette) {
                slabLocalPaletteBytes = new byte[0];
            } else if (factoredV3) {
                int[] localBiomeGlobalIdArr = new int[localBiomeGlobalIds.size()];
                for (int k = 0; k < localBiomeGlobalIdArr.length; k++) {
                    localBiomeGlobalIdArr[k] = localBiomeGlobalIds.get(k);
                }
                slabLocalPaletteBytes = LcaSectionCodec.encodePureIdMicroPalette(
                        localStateGlobalIdArr, localStateGlobalIdArr.length,
                        localBiomeGlobalIdArr, localBiomeGlobalIdArr.length,
                        sortedBiomeTemplates);
            } else {
                slabLocalPaletteBytes = LcaSectionCodec.encodeSlabLocalPalette(
                        localStateList, localStateGlobalIdArr, localBiomeList, sortedBiomeTemplates);
            }
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

            byte[] residualBytes = residualMetadataTailBytes != null ? residualMetadataTailBytes : new byte[0];
            boolean hasResidualNbt = residualBytes.length > 0;
            int defaultBiomeCode = (chunkDefaultBiomeDictId >= 0 && chunkDefaultBiomeDictId < 30)
                    ? (chunkDefaultBiomeDictId + 1)
                    : 0;
            int chunkFlags = (hasResidualNbt ? 1 : 0)
                    | (useSlabLocalPalette ? 2 : 0)
                    | (scale4B ? 4 : 0)
                    | ((defaultBiomeCode & 0x1F) << 3);

            int chunkIdx = (rx & 31) | ((rz & 31) << 5);
            int headerLen = 24 + (sectionCount << 1);
            byte[] secBytes = sectionsBody.toByteArray();
            byte[] chunkFrame = new byte[headerLen + secBytes.length + residualBytes.length];
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
                System.arraycopy(residualBytes, 0, chunkFrame, headerLen + secBytes.length, residualBytes.length);
            }

            if (writeModeBLcd2) {
                return encodeModeBWithAtomicDict(chunkFrame, headerLen, Math.max(1, lcd2Level));
            }
            return chunkFrame;
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

            if (bufferedWritesEnabled && headerOnlyChannelMode && channel != null && channel.isOpen()) {
                int headerLen = 24 + (sectionCount << 1);
                int headerWords = (headerLen + 7) >>> 3;
                boolean payloadModeB = writeModeBLcd2
                        || LcaRegionReader.probeLooksLikeModeB(payload, headerLen, headerWords, payload.length);
                if (pendingPayloads == null) {
                    pendingPayloads = new byte[1024][];
                    pendingPayloadModeB = new boolean[1024];
                }
                if (pendingPayloads[chunkIdx] != null) {
                    pendingWriteBytes -= pendingPayloads[chunkIdx].length;
                    coalescedWriteCount++;
                } else {
                    pendingWriteCount++;
                }
                pendingPayloads[chunkIdx] = payload;
                pendingPayloadModeB[chunkIdx] = payloadModeB;
                pendingWriteBytes += payload.length;
                if (pendingWriteCount >= writeBackMaxChunks || pendingWriteBytes >= writeBackMaxBytes) {
                    flushPendingWrites();
                }
                return payload.length;
            }

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
                int headerLen = 24 + (sectionCount << 1);
                int headerWords = (headerLen + 7) >>> 3;
                boolean payloadModeB = writeModeBLcd2
                        || LcaRegionReader.probeLooksLikeModeB(payload, headerLen, headerWords, payload.length);
                long newPtr = packChunkPointer64(newSlabStart, payloadModeB, payload.length, nextGen);
                buffer[secDedTableOffset + chunkIdx] = (byte) LcaSectionCodec.computeHammingSecDed8(newPtr);
                LcaRegionReader.setVolatileLongBE(buffer, ptrByteOffset, newPtr);
                persistPointerAndSecDed(chunkIdx);
                this.readAheadRef = null;
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

        @SuppressWarnings("java:S1168")
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
            byte[] body = LcaSectionCodec.decodeModeBBody(
                    rawSlab, compOff, rawSlab.length - compOff, getOrBuildParsedHeaderAndDict());
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
            boolean factoredV3 = isFactoredV3Dict();
            int baseLocalStateCount = 0;
            if (hasSlabLocalPalette) {
                int paletteLimitRel = LcaSectionCodec.findFirstSectionRelStart(relStarts, sectionCount, residualRelStart);
                LcaSectionCodec.SlabLocalPalette localPal = LcaSectionCodec.decodeSlabLocalPalette(
                        frame, 0, sectionDirEnd, paletteLimitRel, false, false, getOrBuildParsedHeaderAndDict());
                baseLocalStateCount = localPal.baseBlockNames.size();
            }

            List<String> appStates = appendedStates == null ? Collections.emptyList() : appendedStates;
            int[] appGlobalIds = new int[appStates.size()];
            for (int i = 0; i < appStates.size(); i++) {
                String st = appStates.get(i);
                if (factoredV3) {
                    appGlobalIds[i] = registerOrGetBlockStateId(st);
                } else {
                    int bracket = st.indexOf('[');
                    if (bracket >= 0 && LcaV2LegacyCodec.STATIC_CORE_PROP_TO_ID.get(st.substring(bracket)) == null) {
                        appGlobalIds[i] = registerOrGetBlockStateId(st);
                    } else {
                        appGlobalIds[i] = -1;
                    }
                }
            }

            byte[] patchTailBytes = factoredV3
                    ? LcaSectionCodec.encodePureIdChunkPatchTailBytes(appGlobalIds, appGlobalIds.length, sectionPatches)
                    : LcaSectionCodec.encodeChunkPatchTailBytes(
                            baseLocalStateCount, appStates, appGlobalIds, sectionPatches);
            byte[] patchedFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                    frame, sectionCount, patchTailBytes);

            byte[] finalPayload = writeModeBLcd2
                    ? encodeModeBWithAtomicDict(patchedFrame, sectionDirEnd, Math.max(1, lcd2Level))
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

            boolean factoredV3 = isFactoredV3Dict();
            Map<String, Integer> localStateMap = new LinkedHashMap<>();
            List<String> localStateList = new ArrayList<>();
            List<Integer> localStateGlobalIds = new ArrayList<>();
            Map<String, Integer> localBiomeMap = new LinkedHashMap<>();
            List<String> localBiomeList = new ArrayList<>();
            List<Integer> localBiomeGlobalIds = factoredV3 ? new ArrayList<>() : null;
            int baseLocalStateCount = 0;

            if (hasSlabLocalPalette) {
                int paletteLimitRel = LcaSectionCodec.findFirstSectionRelStart(relStarts, sectionCount, residualRelStart);
                LcaSectionCodec.SlabLocalPalette localPal = LcaSectionCodec.decodeSlabLocalPalette(
                        frame, 0, sectionDirEnd, paletteLimitRel, true, true, getOrBuildParsedHeaderAndDict());
                baseLocalStateCount = localPal.canonicalStates.size();
                for (int i = 0; i < baseLocalStateCount; i++) {
                    String s = localPal.canonicalStates.get(i);
                    localStateMap.put(s, i);
                    localStateList.add(s);
                    localStateGlobalIds.add((factoredV3 && localPal.localStateGlobalIds != null)
                            ? localPal.localStateGlobalIds[i]
                            : -1);
                }
                for (int i = 0; i < localPal.biomes.size(); i++) {
                    String b = localPal.biomes.get(i);
                    localBiomeMap.put(b, i);
                    localBiomeList.add(b);
                    if (localBiomeGlobalIds != null) {
                        localBiomeGlobalIds.add(localPal.localBiomeGlobalIds != null
                                ? localPal.localBiomeGlobalIds[i]
                                : registerOrGetBiomeId(b));
                    }
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
                    hasSlabLocalPalette ? localBiomeList : null,
                    hasSlabLocalPalette ? localBiomeGlobalIds : null);
            long[] biomeData = extractBiomeDataLongs(replacementSectionNbt);
            byte[] skyLight = resolveSectionLightBytes(
                    replacementSectionNbt, "SkyLight", "starlight.skylight_state");
            byte[] blkLight = resolveSectionLightBytes(
                    replacementSectionNbt, "BlockLight", "starlight.blocklight_state");

            byte[] patchSecLightV1 = (this.formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3
                    && this.storageKind != 3
                    && (skyLight != null || blkLight != null))
                    ? LcaV3Codec.buildStatePackedLightV1(hasSlabLocalPalette ? localStateList : idToBlockState)
                    : null;
            int[] patchLitLen = writeModeBLcd2 ? atomicDictLitLen() : null;
            LcaSectionCodec.EncodedSection enc = LcaSectionCodec.encodeSection(
                    blockDictIds, blockData, biomeDictIds, biomeData,
                    skyLight, blkLight, defaultBiomeDictId, -1,
                    this.formatVersion, patchSecLightV1, patchLitLen, this.directFlatBitPack);
            // Force a non-uniform SectionFrame body when testing out-of-order section frame placement
            byte[] secFrameBytes = enc.frameBytes;
            if (secFrameBytes == null || secFrameBytes.length == 0) {
                LcaSectionCodec.EncodedSection forced = LcaSectionCodec.encodeSection(
                        blockDictIds, blockData, biomeDictIds, biomeData,
                        skyLight, blkLight, -2, -1,
                        this.formatVersion, patchSecLightV1, patchLitLen, this.directFlatBitPack);
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
                    byte[] patchPayload = encodeVersionedChunkPatchTail(
                            factoredV3, baseLocalStateCount, appendedStates, appendedGlobalIds, Collections.emptyList());
                    workingFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                            workingFrame, sectionCount, patchPayload);
                } else if (usePatchTailReplacementOffset) {
                    // First inject a placeholder with 2-byte replacementSectionEntry16 to stabilize residual tail length
                    List<LcaSectionCodec.SectionPatch> placeholder = List.of(
                            new LcaSectionCodec.SectionPatch(
                                    secIdx, LcaSectionCodec.PATCH_FLAG_REPLACEMENT_OFFSET, 1, null, null, null, null));
                    byte[] placeholderBytes = encodeVersionedChunkPatchTail(
                            factoredV3, baseLocalStateCount, appendedStates, appendedGlobalIds, placeholder);
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
                    byte[] realPatchBytes = encodeVersionedChunkPatchTail(
                            factoredV3, baseLocalStateCount, appendedStates, appendedGlobalIds, realPatch);
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
                    byte[] patchPayload = encodeVersionedChunkPatchTail(
                            factoredV3, baseLocalStateCount, appendedStates, appendedGlobalIds, patches);
                    workingFrame = LcaSectionCodec.injectChunkPatchTailIntoUncompressedFrame(
                            spliced, sectionCount, patchPayload);
                } else {
                    spliced[24 + (secIdx << 1)] = (byte) ((newEntry16 >>> 8) & 0xFF);
                    spliced[24 + (secIdx << 1) + 1] = (byte) (newEntry16 & 0xFF);
                    workingFrame = spliced;
                }
            }

            byte[] finalPayload = writeModeBLcd2
                    ? encodeModeBWithAtomicDict(workingFrame, sectionDirEnd, Math.max(1, lcd2Level))
                    : workingFrame;
            return commitPreEncodedChunkCoW(rx, rz, finalPayload);
        }

        private static byte[] encodeVersionedChunkPatchTail(
                boolean factoredV3,
                int baseLocalStateCount,
                List<String> appendedStates,
                int[] appendedGlobalIds,
                List<LcaSectionCodec.SectionPatch> patches
        ) {
            return factoredV3
                    ? LcaSectionCodec.encodePureIdChunkPatchTailBytes(appendedGlobalIds, appendedGlobalIds.length, patches)
                    : LcaSectionCodec.encodeChunkPatchTailBytes(
                            baseLocalStateCount, appendedStates, appendedGlobalIds, patches);
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
            int headerLen = 24 + (sectionCount << 1);
            int headerWords = (headerLen + 7) >>> 3;
            if (writeModeBLcd2 || LcaRegionReader.probeLooksLikeModeB(payload, headerLen, headerWords, payload.length)) {
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
            boolean factoredV3 = isFactoredV3Dict();
            boolean useSlabLocalPalette = hasVoxelSections && (factoredV3 || idToBlockState.size() < 8192);
            Map<String, Integer> localStateMap = useSlabLocalPalette ? new LinkedHashMap<>(32) : null;
            List<String> localStateList = useSlabLocalPalette ? new ArrayList<>(32) : null;
            List<Integer> localStateGlobalIds = useSlabLocalPalette ? new ArrayList<>(32) : null;
            Map<String, Integer> localBiomeMap = useSlabLocalPalette ? new LinkedHashMap<>(8) : null;
            List<String> localBiomeList = useSlabLocalPalette ? new ArrayList<>(8) : null;
            List<Integer> localBiomeGlobalIds = (useSlabLocalPalette && factoredV3) ? new ArrayList<>(8) : null;
            if (useSlabLocalPalette) {
                localStateMap.put("minecraft:air", 0);
                localStateList.add("minecraft:air");
                localStateGlobalIds.add(factoredV3 ? registerOrGetBlockStateId("minecraft:air") : -1);
                localBiomeMap.put("minecraft:plains", 0);
                localBiomeList.add("minecraft:plains");
                if (factoredV3) {
                    localBiomeGlobalIds.add(registerOrGetBiomeId("minecraft:plains"));
                }
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
                        int[] bIds = extractBiomeIds(sec, localBiomeMap, localBiomeList, localBiomeGlobalIds);
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

            boolean allowV3Eikonal = this.formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3
                    && this.storageKind != 3
                    && (this.writeModeBLcd2 || (LcaV3Codec.currentFeatureMask() & LcaV3Codec.FEATURE_EIKONAL_MODE_A) != 0);
            int[] modeBLitLen = writeModeBLcd2 ? atomicDictLitLen() : null;
            byte[] cachedLightV1 = null;
            int cachedLightV1Len = 0;

            for (int i = 0; i < sectionCount; i++) {
                Map<String, Object> sec = sectionByIdx[i];
                if (sec == null) continue;
                int[] blockDictIds = extractBlockPaletteIds(sec, localStateMap, localStateList, localStateGlobalIds);
                if (blockDictIds == null || blockDictIds.length == 0) continue;
                long[] blockData = extractBlockDataLongs(sec);
                int[] biomeDictIds = sectionBiomeIds[i];
                long[] biomeData = sectionBiomeData[i];
                byte[] skyLight = resolveSectionLightBytes(sec, "SkyLight", "starlight.skylight_state");
                byte[] blkLight = resolveSectionLightBytes(sec, "BlockLight", "starlight.blocklight_state");
                int secTmplIdx = (oldToSortedTemplate != null && sectionRawTemplateIdx[i] >= 0)
                        ? oldToSortedTemplate[sectionRawTemplateIdx[i]]
                        : -1;

                byte[] secLightV1 = null;
                if (allowV3Eikonal && (skyLight != null || blkLight != null)) {
                    List<String> activeStates = useSlabLocalPalette ? localStateList : idToBlockState;
                    int curSize = activeStates.size();
                    if (cachedLightV1 == null || cachedLightV1.length < curSize) {
                        cachedLightV1 = cachedLightV1 == null
                                ? new byte[Math.max(64, curSize)]
                                : Arrays.copyOf(cachedLightV1, Math.max(curSize, cachedLightV1.length << 1));
                    }
                    for (int sIdx = cachedLightV1Len; sIdx < curSize; sIdx++) {
                        cachedLightV1[sIdx] = LcaV3Codec.classifyLightV1(activeStates.get(sIdx));
                    }
                    cachedLightV1Len = curSize;
                    secLightV1 = cachedLightV1;
                }

                LcaSectionCodec.EncodedSection enc = LcaSectionCodec.encodeSection(
                        blockDictIds, blockData, biomeDictIds, biomeData,
                        skyLight, blkLight, chunkDefaultBiomeDictId, secTmplIdx,
                        this.formatVersion, secLightV1, modeBLitLen, this.directFlatBitPack);
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
            byte[] slabLocalPaletteBytes;
            if (!useSlabLocalPalette) {
                slabLocalPaletteBytes = new byte[0];
            } else if (factoredV3) {
                int[] localBiomeGlobalIdArr = new int[localBiomeGlobalIds.size()];
                for (int k = 0; k < localBiomeGlobalIdArr.length; k++) {
                    localBiomeGlobalIdArr[k] = localBiomeGlobalIds.get(k);
                }
                slabLocalPaletteBytes = LcaSectionCodec.encodePureIdMicroPalette(
                        localStateGlobalIdArr, localStateGlobalIdArr.length,
                        localBiomeGlobalIdArr, localBiomeGlobalIdArr.length,
                        sortedBiomeTemplates);
            } else {
                slabLocalPaletteBytes = LcaSectionCodec.encodeSlabLocalPalette(
                        localStateList, localStateGlobalIdArr, localBiomeList, sortedBiomeTemplates);
            }
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
                    && statusOrdinal != LcaSectionCodec.STATUS_ORDINAL_RESIDUAL
                    && isEmptyNbtList(chunkRoot.get("block_entities"))
                    && isEmptyNbtList(chunkRoot.get("block_ticks"))
                    && isEmptyNbtList(chunkRoot.get("fluid_ticks"));
            boolean effectiveModeB = writeModeBLcd2;
            byte[] residualNbtBytes;
            if (canCacheResidual && hmObj != null && hmObj == cachedHeightmapRef[chunkIdx] && cachedResidualBytes[chunkIdx] != null) {
                residualNbtBytes = cachedResidualBytes[chunkIdx];
            } else {
                residualNbtBytes = serializeCompactResidualNbt(
                        chunkRoot, lowBoundarySections, highBoundarySections,
                        minSectionY, sectionCount, hasVoxelSections, statusOrdinal, !effectiveModeB,
                        (regionX << 5) + rx, (regionZ << 5) + rz, this.formatVersion);
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
                return encodeModeBWithAtomicDict(chunkFrame, headerLen, Math.max(1, lcd2Level));
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
                int statusOrdinal,
                boolean allowDeflateResidual,
                int chunkX,
                int chunkZ,
                int formatVersion
        ) throws IOException {
            byte[] mbnlPrefix = null;
            List<String> secHmKeys = new ArrayList<>(6);
            List<byte[]> secHmPayloads = new ArrayList<>(6);
            int bpFlags = hasVoxelSections ? 0 : 2048;
            int extFlags = 0;
            byte[] extBlockTicks = null;
            byte[] extFluidTicks = null;
            byte[] extBlockEntities = null;
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
                        || ("Status".equals(k)
                                && statusOrdinal != LcaSectionCodec.STATUS_ORDINAL_RESIDUAL
                                && statusOrdinal != LcaSectionCodec.STATUS_ORDINAL_ABSENT)) {
                    continue;
                }
                Object val = e.getValue();
                if ("block_entities".equals(k) && val instanceof Nbt.NbtList nl) {
                    if (nl.items.isEmpty()) {
                        bpFlags |= 1;
                        continue;
                    }
                    if (formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3) {
                        byte[] encodedBe = LcaV3Codec.tryEncodeColumnarBlockEntitiesFromList(nl, chunkX, chunkZ);
                        if (encodedBe != null) {
                            extBlockEntities = encodedBe;
                            extFlags |= LcaV3Codec.EXT_FLAG_COLUMNAR_BLOCK_ENTITIES;
                            continue;
                        }
                    }
                }
                if ("block_ticks".equals(k) && val instanceof Nbt.NbtList nl) {
                    if (nl.items.isEmpty()) {
                        bpFlags |= 2;
                        continue;
                    }
                    if (formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3) {
                        byte[] encodedBt = LcaV3Codec.tryEncodeColumnarTicksFromList(nl, chunkX, chunkZ);
                        if (encodedBt != null) {
                            extBlockTicks = encodedBt;
                            extFlags |= LcaV3Codec.EXT_FLAG_COLUMNAR_BLOCK_TICKS;
                            continue;
                        }
                    }
                }
                if ("fluid_ticks".equals(k) && val instanceof Nbt.NbtList nl) {
                    if (nl.items.isEmpty()) {
                        bpFlags |= 4;
                        continue;
                    }
                    if (formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3) {
                        byte[] encodedFt = LcaV3Codec.tryEncodeColumnarTicksFromList(nl, chunkX, chunkZ);
                        if (encodedFt != null) {
                            extFluidTicks = encodedFt;
                            extFlags |= LcaV3Codec.EXT_FLAG_COLUMNAR_FLUID_TICKS;
                            continue;
                        }
                    }
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
            if (extFlags != 0) {
                bpFlags |= LcaV3Codec.BP_FLAG_HAS_EXTENDED_FLAGS;
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
            if (extFlags != 0) {
                LcaV3Codec.writeVarInt(tailOut, extFlags);
                if (extBlockTicks != null) {
                    LcaV3Codec.writeVarInt(tailOut, extBlockTicks.length);
                    tailOut.writeBytes(extBlockTicks);
                }
                if (extFluidTicks != null) {
                    LcaV3Codec.writeVarInt(tailOut, extFluidTicks.length);
                    tailOut.writeBytes(extFluidTicks);
                }
                if (extBlockEntities != null) {
                    LcaV3Codec.writeVarInt(tailOut, extBlockEntities.length);
                    tailOut.writeBytes(extBlockEntities);
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
            Object starSky = sec.get("starlight.skylight_state");
            Object starBlk = sec.get("starlight.blocklight_state");
            if ((starSky instanceof Number sn && sn.intValue() != 1)
                    || (starBlk instanceof Number bn && bn.intValue() != 1)) {
                return -1;
            }
            int skyMode = sec.get("SkyLight") == null && starSky instanceof Number sn && sn.intValue() == 1
                    ? 1
                    : checkUniformLight0Or15(sec.get("SkyLight"));
            int blkMode = sec.get("BlockLight") == null && starBlk instanceof Number bn && bn.intValue() == 1
                    ? 1
                    : checkUniformLight0Or15(sec.get("BlockLight"));
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

        @SuppressWarnings("java:S1168")
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
            boolean factoredV3 = isFactoredV3Dict();
            int[] out = new int[palList.items.size()];
            for (int i = 0; i < palList.items.size(); i++) {
                String canonical = formatCanonicalStateString(palList.items.get(i));
                if (localStateMap != null) {
                    Integer locId = localStateMap.get(canonical);
                    if (locId == null) {
                        locId = localStateList.size();
                        localStateMap.put(canonical, locId);
                        localStateList.add(canonical);
                        int globalId;
                        if (factoredV3) {
                            globalId = registerOrGetBlockStateId(canonical);
                        } else {
                            int bracket = canonical.indexOf('[');
                            globalId = -1;
                            if (bracket >= 0 && !LcaV2LegacyCodec.STATIC_CORE_PROP_TO_ID.containsKey(canonical.substring(bracket))) {
                                globalId = registerOrGetBlockStateId(canonical);
                            }
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

        @SuppressWarnings("java:S1168")
        private static long[] extractBlockDataLongs(Map<String, Object> sec) {
            Object bsObj = sec.get("block_states");
            if (!(bsObj instanceof Map<?, ?> bs)) return null;
            Object dataObj = bs.get("data");
            return (dataObj instanceof long[] longs) ? longs : null;
        }

        @SuppressWarnings("java:S1168")
        private int[] extractBiomeIds(
                Map<String, Object> sec,
                Map<String, Integer> localBiomeMap,
                List<String> localBiomeList,
                List<Integer> localBiomeGlobalIds
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
                    Integer existing = localBiomeMap.get(biomeId);
                    if (existing == null) {
                        int id = localBiomeList.size();
                        localBiomeMap.put(biomeId, id);
                        localBiomeList.add(biomeId);
                        if (localBiomeGlobalIds != null) {
                            localBiomeGlobalIds.add(registerOrGetBiomeId(biomeId));
                        }
                        out[i] = id;
                    } else {
                        out[i] = existing;
                    }
                } else {
                    out[i] = registerOrGetBiomeId(biomeId);
                }
            }
            return out;
        }

        @SuppressWarnings("java:S1168")
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

        private void parseDictSegmentIntoLiveArchive(
                byte[] segBuf, int segBaseOff, int segCap, DictSegmentMeta segMeta
        ) throws CorruptRegionEntryException {
            int segPayloadLimit = segBaseOff + segCap - 8;
            if (segMeta.factoredV3) {
                int segStateCount = LcaSectionCodec.readUint16BE(segBuf, segBaseOff);
                int segBiomeCount = LcaSectionCodec.readUint16BE(segBuf, segBaseOff + 2);
                int segBaseBlockCount = LcaSectionCodec.readUint16BE(segBuf, segBaseOff + 4);
                int usedPayloadBytes = LcaSectionCodec.readUint16BE(segBuf, segBaseOff + 6);
                if (segBiomeCount > 16384
                        || (usedPayloadBytes != 0 && usedPayloadBytes < 2)
                        || segBaseOff + 8 + usedPayloadBytes > segPayloadLimit) {
                    throw new CorruptRegionEntryException("CBIT T5: Factored v3 dictionary segment header corrupt");
                }
                if (usedPayloadBytes == 0) {
                    if (segStateCount != 0 || segBiomeCount != 0 || segBaseBlockCount != 0) {
                        throw new CorruptRegionEntryException("CBIT T5: Non-zero counts in zero-payload v3 dict segment");
                    }
                    segMeta.usedPayloadBytes = 2;
                    return;
                }
                int segPropTupleCount = LcaSectionCodec.readUint16BE(segBuf, segBaseOff + 8);
                int activeEnd = segBaseOff + 8 + usedPayloadBytes;
                int pos = segBaseOff + 10;
                segMeta.usedPayloadBytes = 2;

                for (int i = 0; i < segBaseBlockCount; i++) {
                    int entryStart = pos;
                    int utfLen = LcaSectionCodec.readVarInt(segBuf, pos, activeEnd);
                    pos += LcaSectionCodec.varIntLen(utfLen);
                    if (utfLen < 0 || pos + utfLen > activeEnd) {
                        throw new CorruptRegionEntryException("CBIT T5: Truncated v3 BaseBlock entry");
                    }
                    String baseName = new String(segBuf, pos, utfLen, StandardCharsets.UTF_8);
                    pos += utfLen;
                    byte[] rawEntry = Arrays.copyOfRange(segBuf, entryStart, pos);
                    segMeta.baseBlockEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    baseBlockToId.put(baseName, idToBaseBlock.size());
                    idToBaseBlock.add(baseName);
                }

                for (int i = 0; i < segPropTupleCount; i++) {
                    int entryStart = pos;
                    int utfLen = LcaSectionCodec.readVarInt(segBuf, pos, activeEnd);
                    pos += LcaSectionCodec.varIntLen(utfLen);
                    if (utfLen < 2 || pos + utfLen > activeEnd) {
                        throw new CorruptRegionEntryException("CBIT T5: Truncated or invalid v3 PropTuple entry");
                    }
                    String propTuple = new String(segBuf, pos, utfLen, StandardCharsets.UTF_8);
                    if (propTuple.charAt(0) != '[' || propTuple.charAt(propTuple.length() - 1) != ']') {
                        throw new CorruptRegionEntryException("CBIT T5: Malformed v3 PropTuple entry: " + propTuple);
                    }
                    pos += utfLen;
                    byte[] rawEntry = Arrays.copyOfRange(segBuf, entryStart, pos);
                    segMeta.propTupleEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    propTupleToId.put(propTuple, idToPropTuple.size());
                    idToPropTuple.add(propTuple);
                }

                int totalBases = idToBaseBlock.size();
                int totalProps = idToPropTuple.size();
                for (int i = 0; i < segStateCount; i++) {
                    int entryStart = pos;
                    int baseId = LcaSectionCodec.readVarInt(segBuf, pos, activeEnd);
                    pos += LcaSectionCodec.varIntLen(baseId);
                    int propPlusOne = LcaSectionCodec.readVarInt(segBuf, pos, activeEnd);
                    pos += LcaSectionCodec.varIntLen(propPlusOne);
                    if (baseId < 0 || baseId >= totalBases || propPlusOne < 0 || propPlusOne > totalProps || pos + 4 > activeEnd) {
                        throw new CorruptRegionEntryException("CBIT T5: Invalid causal ID or truncated v3 BlockState entry");
                    }
                    int mat32 = LcaSectionCodec.readIntBE(segBuf, pos);
                    pos += 4;
                    byte[] rawEntry = Arrays.copyOfRange(segBuf, entryStart, pos);
                    segMeta.stateEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    String baseName = idToBaseBlock.get(baseId);
                    String canonical = propPlusOne == 0 ? baseName : (baseName + idToPropTuple.get(propPlusOne - 1));
                    blockStateToId.put(canonical, idToBlockState.size());
                    idToBlockState.add(canonical);
                    stateBaseBlockIds.add(baseId);
                    statePropTupleIds.add(propPlusOne - 1);
                    stateMaterialPacked.add(mat32);
                }

                for (int i = 0; i < segBiomeCount; i++) {
                    int entryStart = pos;
                    int utfLen = LcaSectionCodec.readVarInt(segBuf, pos, activeEnd);
                    pos += LcaSectionCodec.varIntLen(utfLen);
                    if (utfLen < 0 || pos + utfLen + 4 > activeEnd) {
                        throw new CorruptRegionEntryException("CBIT T5: Truncated v3 Biome entry");
                    }
                    String biome = new String(segBuf, pos, utfLen, StandardCharsets.UTF_8);
                    pos += utfLen + 4;
                    byte[] rawEntry = Arrays.copyOfRange(segBuf, entryStart, pos);
                    segMeta.biomeEntries.add(rawEntry);
                    segMeta.usedPayloadBytes += rawEntry.length;
                    biomeToId.put(biome, idToBiome.size());
                    idToBiome.add(biome);
                }

                if (pos > activeEnd) {
                    throw new CorruptRegionEntryException("CBIT T5: Factored v3 dict entries exceed usedPayloadBytes");
                }
                if (pos < activeEnd) {
                    segMeta.appendPatternSlice(segBuf, pos, activeEnd - pos);
                }
                return;
            }

            int segStateCount = LcaSectionCodec.readIntBE(segBuf, segBaseOff);
            int segBiomeCount = LcaSectionCodec.readUint16BE(segBuf, segBaseOff + 4);
            int usedPayloadBytes = LcaSectionCodec.readUint16BE(segBuf, segBaseOff + 6);
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

            int activeEnd = segBaseOff + 8 + usedPayloadBytes;
            if (pos < activeEnd && activeEnd <= segPayloadLimit) {
                segMeta.appendPatternSlice(segBuf, pos, activeEnd - pos);
            }
        }

        private synchronized int registerNewBlockStateId(String canonicalState) {
            Integer existing = blockStateToId.get(canonicalState);
            if (existing != null) {
                return existing;
            }
            if (isFactoredV3Dict()) {
                int bracket = canonicalState.indexOf('[');
                boolean hasProps = bracket >= 0 && canonicalState.endsWith("]") && canonicalState.length() - bracket >= 2;
                String baseName = hasProps ? canonicalState.substring(0, bracket) : canonicalState;
                String propSuffix = hasProps ? canonicalState.substring(bracket) : null;

                Integer existingBaseId = baseBlockToId.get(baseName);
                boolean needNewBase = existingBaseId == null;
                int baseId = needNewBase ? idToBaseBlock.size() : existingBaseId;
                byte[] rawBaseEntry = null;
                if (needNewBase) {
                    byte[] baseUtf = baseName.getBytes(StandardCharsets.UTF_8);
                    rawBaseEntry = new byte[LcaSectionCodec.varIntLen(baseUtf.length) + baseUtf.length];
                    int bp = LcaSectionCodec.writeVarIntToBuf(rawBaseEntry, 0, baseUtf.length);
                    System.arraycopy(baseUtf, 0, rawBaseEntry, bp, baseUtf.length);
                }

                boolean needNewProp = false;
                int propId = -1;
                byte[] rawPropEntry = null;
                if (propSuffix != null) {
                    Integer existingPropId = propTupleToId.get(propSuffix);
                    if (existingPropId == null) {
                        needNewProp = true;
                        propId = idToPropTuple.size();
                        byte[] propUtf = propSuffix.getBytes(StandardCharsets.UTF_8);
                        rawPropEntry = new byte[LcaSectionCodec.varIntLen(propUtf.length) + propUtf.length];
                        int pp = LcaSectionCodec.writeVarIntToBuf(rawPropEntry, 0, propUtf.length);
                        System.arraycopy(propUtf, 0, rawPropEntry, pp, propUtf.length);
                    } else {
                        propId = existingPropId;
                    }
                }

                int propIdPlusOne = propId + 1;
                int materialPacked = computeLeafMapMaterialPacked(canonicalState);
                byte[] rawStateEntry = new byte[
                        LcaSectionCodec.varIntLen(baseId) + LcaSectionCodec.varIntLen(propIdPlusOne) + 4];
                int sp = LcaSectionCodec.writeVarIntToBuf(rawStateEntry, 0, baseId);
                sp = LcaSectionCodec.writeVarIntToBuf(rawStateEntry, sp, propIdPlusOne);
                rawStateEntry[sp] = (byte) ((materialPacked >>> 24) & 0xFF);
                rawStateEntry[sp + 1] = (byte) ((materialPacked >>> 16) & 0xFF);
                rawStateEntry[sp + 2] = (byte) ((materialPacked >>> 8) & 0xFF);
                rawStateEntry[sp + 3] = (byte) (materialPacked & 0xFF);

                int totalNewBytes = rawStateEntry.length
                        + (needNewBase ? rawBaseEntry.length : 0)
                        + (needNewProp ? rawPropEntry.length : 0);
                DictSegmentMeta activeSeg = ensureDictSegmentCapacity(totalNewBytes);
                if (needNewBase) {
                    activeSeg.baseBlockEntries.add(rawBaseEntry);
                }
                if (needNewProp) {
                    activeSeg.propTupleEntries.add(rawPropEntry);
                }
                activeSeg.stateEntries.add(rawStateEntry);
                activeSeg.usedPayloadBytes += totalNewBytes;
                try {
                    flushDictSegment(activeSeg);
                } catch (RuntimeException | Error e) {
                    activeSeg.stateEntries.remove(activeSeg.stateEntries.size() - 1);
                    if (needNewProp) {
                        activeSeg.propTupleEntries.remove(activeSeg.propTupleEntries.size() - 1);
                    }
                    if (needNewBase) {
                        activeSeg.baseBlockEntries.remove(activeSeg.baseBlockEntries.size() - 1);
                    }
                    activeSeg.usedPayloadBytes -= totalNewBytes;
                    throw e;
                }
                if (needNewBase) {
                    idToBaseBlock.add(baseName);
                    baseBlockToId.put(baseName, baseId);
                }
                if (needNewProp) {
                    idToPropTuple.add(propSuffix);
                    propTupleToId.put(propSuffix, propId);
                }
                int newId = idToBlockState.size();
                idToBlockState.add(canonicalState);
                stateBaseBlockIds.add(baseId);
                statePropTupleIds.add(propId);
                stateMaterialPacked.add(materialPacked);
                cachedParsedDict = null;
                blockStateToId.put(canonicalState, newId);
                return newId;
            }

            byte[] utfBytes = canonicalState.getBytes(StandardCharsets.UTF_8);
            byte[] rawEntry = new byte[LcaSectionCodec.varIntLen(utfBytes.length) + utfBytes.length + 4];
            int pos = LcaSectionCodec.writeVarIntToBuf(rawEntry, 0, utfBytes.length);
            System.arraycopy(utfBytes, 0, rawEntry, pos, utfBytes.length);
            pos += utfBytes.length;
            int materialPacked = computeLeafMapMaterialPacked(canonicalState);
            rawEntry[pos] = (byte) ((materialPacked >>> 24) & 0xFF);
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
            cachedParsedDict = null;
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
            cachedParsedDict = null;
            biomeToId.put(biomeId, newId);
            return newId;
        }

        private synchronized DictSegmentMeta ensureDictSegmentCapacity(int entryBytes) {
            DictSegmentMeta current = dictSegments.get(dictSegments.size() - 1);
            boolean countsFit = !current.factoredV3
                    || (current.stateEntries.size() < 65535
                            && current.baseBlockEntries.size() < 65535
                            && current.propTupleEntries.size() < 65535
                            && current.biomeEntries.size() < 16384);
            if (countsFit && current.availableSlackBytes() >= entryBytes) {
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
                    contSlabs << LcaRegionReader.SLAB_SHIFT,
                    isFactoredV3Dict());
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
        private synchronized void rebuildAtomicDictFromSegments() {
            int totalPatternBytes = 0;
            for (int s = 0; s < dictSegments.size(); s++) {
                totalPatternBytes += dictSegments.get(s).patternLen;
            }
            if (totalPatternBytes <= 0) {
                if (atomicDictState != null) {
                    atomicDictState.loadFromExistingArena(LcaSectionCodec.EMPTY_BYTES);
                }
                syncedPatternBytes = 0;
                return;
            }
            byte[] fullArena = new byte[totalPatternBytes];
            int pos = 0;
            for (int s = 0; s < dictSegments.size(); s++) {
                DictSegmentMeta seg = dictSegments.get(s);
                if (seg.patternLen > 0) {
                    System.arraycopy(seg.patternBytes, 0, fullArena, pos, seg.patternLen);
                    pos += seg.patternLen;
                }
            }
            LcaSectionCodec.AtomicDictState ds = ensureAtomicDictState();
            ds.loadFromExistingArena(fullArena);
            syncedPatternBytes = ds.usedBytes;
        }

        private synchronized void prewarmAtomicDictCandidates(byte[] src, int off, int len) {
            if (!this.writeModeBLcd2 || src == null || len < 16 || off < 0 || off + len > src.length) {
                return;
            }
            LcaSectionCodec.AtomicDictState ds = ensureAtomicDictState();
            int step = 64;
            for (int p = 0; p < len; p += step) {
                int sliceLen = Math.min(step + 8, len - p);
                if (sliceLen >= 6) {
                    ds.recordCandidateSlice(src, off + p, sliceLen);
                }
            }
        }

        /**
         * Off-thread atomic dictionary Mode B frame compressor (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         * Invoked exclusively from asynchronous region I/O worker threads (`RTP-Anvil-IO`, Moonrise I/O
         * pool, or offline batch converter); never called on the server main thread.
         */
        private synchronized byte[] encodeModeBWithAtomicDict(byte[] chunkFrame, int headerLen, int level) {
            LcaSectionCodec.AtomicDictState ds = ensureAtomicDictState();
            boolean allowTrain = (level >= 3)
                    || !ds.huffmanInitialized
                    || (ds.fastTrainedChunks < LcaSectionCodec.DICT_FAST_TRAIN_MAX_CHUNKS
                            && ds.usedBytes < LcaSectionCodec.DICT_FAST_TRAIN_CAP_BYTES);
            if (allowTrain) {
                DictSegmentMeta tail = dictSegments.get(dictSegments.size() - 1);
                ds.currentFreeSlackEnd =
                        syncedPatternBytes + Math.max(0, tail.availableSlackBytes());
            }
            byte[] encoded = LcaSectionCodec.encodeModeBChunkFrameWithAtomicDict(
                    chunkFrame, headerLen, ds, allowTrain, level);
            if (syncedPatternBytes < ds.usedBytes) {
                syncAtomicDictToSegments();
            }
            return encoded;
        }

        private synchronized void syncAtomicDictToSegments() {
            if (atomicDictState == null || syncedPatternBytes >= atomicDictState.usedBytes) {
                return;
            }
            while (syncedPatternBytes < atomicDictState.usedBytes) {
                DictSegmentMeta tail = dictSegments.get(dictSegments.size() - 1);
                int avail = tail.availableSlackBytes();
                if (avail <= 0) {
                    int rem = atomicDictState.usedBytes - syncedPatternBytes;
                    int hdrOverhead = isFactoredV3Dict() ? 10 : 8;
                    int minNeededBytes = (((hdrOverhead + rem + 7) >>> 3) * 9 + 8 + 255) & ~255;
                    int contBytes = Math.max(512, minNeededBytes);
                    int contSlabs = contBytes >>> LcaRegionReader.SLAB_SHIFT;
                    int nextSlabStart = allocateSlabs(contSlabs, false);
                    if (!headerOnlyChannelMode) {
                        ensureBufferCapacity((nextSlabStart + contSlabs) << LcaRegionReader.SLAB_SHIFT);
                    }
                    DictSegmentMeta nextSeg = new DictSegmentMeta(
                            nextSlabStart << LcaRegionReader.SLAB_SHIFT,
                            contSlabs << LcaRegionReader.SLAB_SHIFT,
                            isFactoredV3Dict());
                    int relSlabs32 = nextSlabStart - tail.baseSlab();
                    tail.linkTrailer = ((long) relSlabs32 << 32)
                            | ((contSlabs & 0xFFFFFFL) << 8)
                            | 3L;
                    stageDictSegmentInBuffer(tail);
                    stageDictSegmentInBuffer(nextSeg);
                    dictSegments.add(nextSeg);
                    tail = nextSeg;
                    avail = tail.availableSlackBytes();
                }
                int chunk = Math.min(avail, atomicDictState.usedBytes - syncedPatternBytes);
                tail.appendPatternSlice(atomicDictState.arena, syncedPatternBytes, chunk);
                syncedPatternBytes += chunk;
                stageDictSegmentInBuffer(tail);
            }
            cachedParsedDict = null;
        }

        private synchronized void rollbackDictionaryToBookmark(
                int baseBlockBookmark,
                int propTupleBookmark,
                int stateBookmark,
                int biomeBookmark,
                int patternBookmark,
                int segBookmark
        ) {
            boolean modified = false;
            while (idToBlockState.size() > stateBookmark) {
                int lastIdx = idToBlockState.size() - 1;
                String removed = idToBlockState.remove(lastIdx);
                blockStateToId.remove(removed);
                if (stateBaseBlockIds.size() > lastIdx) {
                    stateBaseBlockIds.remove(lastIdx);
                    statePropTupleIds.remove(lastIdx);
                    stateMaterialPacked.remove(lastIdx);
                }
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
            while (idToPropTuple.size() > propTupleBookmark) {
                String removed = idToPropTuple.remove(idToPropTuple.size() - 1);
                propTupleToId.remove(removed);
                for (int s = dictSegments.size() - 1; s >= 0; s--) {
                    DictSegmentMeta seg = dictSegments.get(s);
                    if (!seg.propTupleEntries.isEmpty()) {
                        byte[] entry = seg.propTupleEntries.remove(seg.propTupleEntries.size() - 1);
                        seg.usedPayloadBytes -= entry.length;
                        modified = true;
                        break;
                    }
                }
            }
            while (idToBaseBlock.size() > baseBlockBookmark) {
                String removed = idToBaseBlock.remove(idToBaseBlock.size() - 1);
                baseBlockToId.remove(removed);
                for (int s = dictSegments.size() - 1; s >= 0; s--) {
                    DictSegmentMeta seg = dictSegments.get(s);
                    if (!seg.baseBlockEntries.isEmpty()) {
                        byte[] entry = seg.baseBlockEntries.remove(seg.baseBlockEntries.size() - 1);
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
            int currentUsedBytes = atomicDictState != null ? atomicDictState.usedBytes : 0;
            if (syncedPatternBytes > patternBookmark || currentUsedBytes > patternBookmark) {
                int toTrim = syncedPatternBytes - patternBookmark;
                for (int s = dictSegments.size() - 1; s >= 0 && toTrim > 0; s--) {
                    DictSegmentMeta seg = dictSegments.get(s);
                    if (seg.patternLen > 0) {
                        int cut = Math.min(seg.patternLen, toTrim);
                        seg.patternLen -= cut;
                        seg.usedPayloadBytes -= cut;
                        toTrim -= cut;
                        modified = true;
                    }
                }
                syncedPatternBytes = patternBookmark;
                if (atomicDictState != null) {
                    atomicDictState.rollbackTo(patternBookmark);
                }
                modified = true;
            }
            while (dictSegments.size() > segBookmark) {
                dictSegments.remove(dictSegments.size() - 1);
                modified = true;
            }
            if (modified) {
                cachedParsedDict = null;
                if (!dictSegments.isEmpty()) {
                    DictSegmentMeta tail = dictSegments.get(dictSegments.size() - 1);
                    tail.linkTrailer = 2L;
                    try {
                        flushDictSegment(tail);
                    } catch (RuntimeException e) {
                        java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName())
                                .log(java.util.logging.Level.WARNING,
                                        "[LcaRegionWriter] Dictionary rollback flush warning: " + e.getMessage(), e);
                    }
                }
            }
        }

        private synchronized byte[] stageDictSegmentInBuffer(DictSegmentMeta seg) {
            int offset = seg.byteOffset();
            int capBytes = seg.capacityBytes();
            cachedParsedDict = null;
            dictModifiedSinceSync = true;
            seg.channelDirty = true;

            byte[] staged = seg.stagedScratch;
            if (staged == null || staged.length != capBytes) {
                staged = new byte[capBytes];
                seg.stagedScratch = staged;
            } else {
                Arrays.fill(staged, (byte) 0);
            }
            ByteBuffer bb = ByteBuffer.wrap(staged);
            int pos;
            if (seg.factoredV3) {
                bb.putShort(0, (short) seg.stateEntries.size());
                bb.putShort(2, (short) seg.biomeEntries.size());
                bb.putShort(4, (short) seg.baseBlockEntries.size());
                bb.putShort(6, (short) seg.usedPayloadBytes);
                bb.putShort(8, (short) seg.propTupleEntries.size());
                pos = 10;
                for (byte[] bbEntry : seg.baseBlockEntries) {
                    System.arraycopy(bbEntry, 0, staged, pos, bbEntry.length);
                    pos += bbEntry.length;
                }
                for (byte[] ptEntry : seg.propTupleEntries) {
                    System.arraycopy(ptEntry, 0, staged, pos, ptEntry.length);
                    pos += ptEntry.length;
                }
                for (byte[] sEntry : seg.stateEntries) {
                    System.arraycopy(sEntry, 0, staged, pos, sEntry.length);
                    pos += sEntry.length;
                }
                for (byte[] bEntry : seg.biomeEntries) {
                    System.arraycopy(bEntry, 0, staged, pos, bEntry.length);
                    pos += bEntry.length;
                }
            } else {
                bb.putInt(0, seg.stateEntries.size());
                bb.putShort(4, (short) seg.biomeEntries.size());
                bb.putShort(6, (short) seg.usedPayloadBytes);
                pos = 8;
                for (byte[] sEntry : seg.stateEntries) {
                    System.arraycopy(sEntry, 0, staged, pos, sEntry.length);
                    pos += sEntry.length;
                }
                for (byte[] bEntry : seg.biomeEntries) {
                    System.arraycopy(bEntry, 0, staged, pos, bEntry.length);
                    pos += bEntry.length;
                }
            }
            if (seg.patternLen > 0) {
                System.arraycopy(seg.patternBytes, 0, staged, pos, seg.patternLen);
                pos += seg.patternLen;
            }
            LcaSectionCodec.writeDictSegmentSecDedSyndromes(staged, capBytes, seg.usedPayloadBytes);
            long trailerWithSecDed = seg.linkTrailer | 2L;
            seg.linkTrailer = trailerWithSecDed;
            bb.putLong(capBytes - 8, trailerWithSecDed);
            if (!headerOnlyChannelMode) {
                ensureBufferCapacity(offset + capBytes + LcaRegionReader.FOOTER_SIZE);
                System.arraycopy(staged, 0, buffer, offset, capBytes);
            } else if (offset + capBytes <= buffer.length) {
                System.arraycopy(staged, 0, buffer, offset, capBytes);
            }
            VarHandle.releaseFence();
            return staged;
        }

        /**
         * Off-thread dictionary segment channel flush (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        private synchronized void flushDictSegment(DictSegmentMeta seg) {
            byte[] staged = stageDictSegmentInBuffer(seg);
            if (bufferedWritesEnabled && headerOnlyChannelMode) {
                return;
            }
            writeFooterCanary();
            writeChannelRangeUnchecked(seg.byteOffset(), staged, 0, seg.capacityBytes());
            if (channel != null && channel.isOpen()) {
                seg.channelDirty = false;
            }
        }

        private synchronized void ensureFreeSlabPoolInitialized() {
            if (freeSlabPoolInitialized) {
                return;
            }
            freeSlabPoolInitialized = true;
            int maxIntervals = 1024 + dictSegments.size() + COW_GRACE_EPOCHS + deferredRetireSlabs.size();
            long[] packedIntervals = new long[maxIntervals];
            int count = 0;
            for (int s = 0; s < dictSegments.size(); s++) {
                DictSegmentMeta seg = dictSegments.get(s);
                int startSlab = seg.byteOffset >>> LcaRegionReader.SLAB_SHIFT;
                if (startSlab >= this.slabPoolBaseSlab) {
                    int endSlab = startSlab + ((seg.capacityBytes + 255) >>> LcaRegionReader.SLAB_SHIFT);
                    packedIntervals[count++] = ((long) startSlab << 32) | (endSlab & 0xFFFF_FFFFL);
                }
            }
            for (int i = 0; i < 1024; i++) {
                long ptr = (long) LcaRegionReader.LONG_BE_VIEW.get(
                        this.buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3));
                if (ptr != 0L) {
                    int slabStart = (int) (ptr >>> 29);
                    int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                    if (slabStart >= this.slabPoolBaseSlab && byteLen > 0) {
                        int slabEnd = slabStart + ((byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT);
                        packedIntervals[count++] = ((long) slabStart << 32) | (slabEnd & 0xFFFF_FFFFL);
                    }
                }
            }
            for (int g = 0; g < COW_GRACE_EPOCHS; g++) {
                int gStart = graceSlabStarts[g];
                int gCount = graceSlabCounts[g];
                if (gStart >= this.slabPoolBaseSlab && gCount > 0) {
                    int gEnd = gStart + gCount;
                    packedIntervals[count++] = ((long) gStart << 32) | (gEnd & 0xFFFF_FFFFL);
                }
            }
            for (int d = 0; d < deferredRetireSlabs.size(); d++) {
                long[] item = deferredRetireSlabs.get(d);
                int dStart = (int) item[0];
                int dCount = (int) item[1];
                if (dStart >= this.slabPoolBaseSlab && dCount > 0) {
                    int dEnd = dStart + dCount;
                    packedIntervals[count++] = ((long) dStart << 32) | (dEnd & 0xFFFF_FFFFL);
                }
            }
            Arrays.sort(packedIntervals, 0, count);
            int cursorSlab = this.slabPoolBaseSlab;
            for (int k = 0; k < count; k++) {
                long iv = packedIntervals[k];
                int start = (int) (iv >>> 32);
                int end = (int) iv;
                if (start > cursorSlab) {
                    insertFreeInterval(cursorSlab, start - cursorSlab);
                }
                if (end > cursorSlab) {
                    cursorSlab = end;
                }
            }
            if (this.activeEndSlab > cursorSlab) {
                insertFreeInterval(cursorSlab, this.activeEndSlab - cursorSlab);
            }
        }

        private synchronized int allocateSlabs(int neededSlabs, boolean trackTelemetry) {
            ensureFreeSlabPoolInitialized();
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

        private synchronized void rollbackAllocatedSlabs(
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
                    } catch (IOException e) {
                        java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName())
                                .log(java.util.logging.Level.WARNING,
                                        "[LcaRegionWriter] Slab rollback truncate/canary warning: " + e.getMessage(), e);
                    }
                }
            } else {
                returnFreeSlabs(allocatedStart, allocatedCount);
            }
        }

        private synchronized void returnFreeSlabs(int slabStart, int slabCount) {
            if (slabCount <= 0 || slabStart < this.slabPoolBaseSlab) return;
            ensureFreeSlabPoolInitialized();
            int slabEnd = slabStart + slabCount;

            for (int s = 0; s < dictSegments.size(); s++) {
                DictSegmentMeta seg = dictSegments.get(s);
                int segStart = seg.byteOffset >>> LcaRegionReader.SLAB_SHIFT;
                if (segStart >= this.slabPoolBaseSlab) {
                    int segEnd = segStart + ((seg.capacityBytes + 255) >>> LcaRegionReader.SLAB_SHIFT);
                    if (segStart < slabEnd && segEnd > slabStart) {
                        java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName())
                                .log(java.util.logging.Level.WARNING,
                                        "[LcaRegionWriter] Refused to free slab range [" + slabStart + ".." + slabEnd
                                                + ") overlapping live dict segment [" + segStart + ".." + segEnd + ")");
                        return;
                    }
                }
            }
            for (int i = 0; i < 1024; i++) {
                long ptr = (long) LcaRegionReader.LONG_BE_VIEW.get(
                        this.buffer, LcaRegionReader.POINTER_DIRECTORY_OFFSET + (i << 3));
                if (ptr != 0L) {
                    int ptrStart = (int) (ptr >>> 29);
                    int byteLen = (int) ((ptr >>> 8) & 0xFFFFFL);
                    if (ptrStart >= this.slabPoolBaseSlab && byteLen > 0) {
                        int ptrEnd = ptrStart + ((byteLen + 255) >>> LcaRegionReader.SLAB_SHIFT);
                        if (ptrStart < slabEnd && ptrEnd > slabStart) {
                            java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName())
                                    .log(java.util.logging.Level.WARNING,
                                            "[LcaRegionWriter] Refused to free slab range [" + slabStart + ".." + slabEnd
                                                    + ") still referenced by live chunkIdx=" + i
                                                    + " [" + ptrStart + ".." + ptrEnd + ")");
                            return;
                        }
                    }
                }
            }

            Map.Entry<Integer, Integer> prev = freeByStart.floorEntry(slabStart);
            if (prev != null && prev.getKey() + prev.getValue() > slabStart) {
                java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "[LcaRegionWriter] Refused duplicate free of slab range [" + slabStart + ".." + slabEnd
                                        + ") overlapping free interval [" + prev.getKey() + ".."
                                        + (prev.getKey() + prev.getValue()) + ")");
                return;
            }
            Map.Entry<Integer, Integer> next = freeByStart.ceilingEntry(slabStart);
            if (next != null && next.getKey() < slabEnd) {
                java.util.logging.Logger.getLogger(LcaRegionWriter.class.getName())
                        .log(java.util.logging.Level.WARNING,
                                "[LcaRegionWriter] Refused duplicate free of slab range [" + slabStart + ".." + slabEnd
                                        + ") overlapping free interval [" + next.getKey() + ".."
                                        + (next.getKey() + next.getValue()) + ")");
                return;
            }

            int mergedStart = slabStart;
            int mergedCount = slabCount;

            if (prev != null && prev.getKey() + prev.getValue() == slabStart) {
                removeFreeInterval(prev.getKey(), prev.getValue());
                mergedStart = prev.getKey();
                mergedCount += prev.getValue();
            }

            if (next != null && mergedStart + mergedCount == next.getKey()) {
                removeFreeInterval(next.getKey(), next.getValue());
                mergedCount += next.getValue();
            }

            insertFreeInterval(mergedStart, mergedCount);
        }

        private synchronized void insertFreeInterval(int start, int count) {
            freeByStart.put(start, count);
            freeBySize.computeIfAbsent(count, k -> new TreeSet<>()).add(start);
        }

        private synchronized void removeFreeInterval(int start, int count) {
            freeByStart.remove(start);
            TreeSet<Integer> set = freeBySize.get(count);
            if (set != null) {
                set.remove(start);
                if (set.isEmpty()) {
                    freeBySize.remove(count);
                }
            }
        }
        /**
         * Off-thread padded slab and coalesced EOF canary writer (`REQ-RTP-S-005`, `@scheduler_dispatch`).
         */
        private synchronized void writeSlabBytes(int startSlab, byte[] payload) {
            writeSlabBytesSlice(startSlab, payload, payload.length);
        }

        private synchronized void writeSlabBytesSlice(int startSlab, byte[] payload, int payloadLen) {
            int byteOffset = startSlab << LcaRegionReader.SLAB_SHIFT;
            int paddedBytes = ((payloadLen + 255) >>> LcaRegionReader.SLAB_SHIFT) << LcaRegionReader.SLAB_SHIFT;
            int footerOffset = activeEndSlab << LcaRegionReader.SLAB_SHIFT;
            boolean isAtActiveEnd = (activeEndSlab >= slabPoolBaseSlab
                    && slabPoolBaseSlab > 0
                    && byteOffset + paddedBytes == footerOffset);
            if (headerOnlyChannelMode) {
                if (isAtActiveEnd && channel != null && channel.isOpen() && footerOffset != lastSyncedFooterOffset) {
                    int totalWrite = paddedBytes + LcaRegionReader.FOOTER_SIZE;
                    if (payload.length >= totalWrite) {
                        if (paddedBytes > payloadLen) {
                            Arrays.fill(payload, payloadLen, paddedBytes, (byte) 0);
                        }
                        LcaRegionReader.setVolatileLongBE(payload, paddedBytes, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                        writeChannelRangeUnchecked(byteOffset, payload, 0, totalWrite);
                    } else {
                        byte[] coalesced = Arrays.copyOf(payload, totalWrite);
                        if (paddedBytes > payloadLen) {
                            Arrays.fill(coalesced, payloadLen, paddedBytes, (byte) 0);
                        }
                        LcaRegionReader.setVolatileLongBE(coalesced, paddedBytes, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                        writeChannelRangeUnchecked(byteOffset, coalesced, 0, totalWrite);
                    }
                    lastSyncedFooterOffset = footerOffset;
                    return;
                }
                writeFooterCanary();
                if (payload.length >= paddedBytes) {
                    if (paddedBytes > payloadLen) {
                        Arrays.fill(payload, payloadLen, paddedBytes, (byte) 0);
                    }
                    writeChannelRangeUnchecked(byteOffset, payload, 0, paddedBytes);
                } else {
                    byte[] padded = Arrays.copyOf(payload, paddedBytes);
                    if (paddedBytes > payloadLen) {
                        Arrays.fill(padded, payloadLen, paddedBytes, (byte) 0);
                    }
                    writeChannelRangeUnchecked(byteOffset, padded, 0, paddedBytes);
                }
                return;
            }
            ensureBufferCapacity(Math.max(byteOffset + paddedBytes, footerOffset) + LcaRegionReader.FOOTER_SIZE);
            System.arraycopy(payload, 0, buffer, byteOffset, payloadLen);
            if (paddedBytes > payloadLen) {
                Arrays.fill(buffer, byteOffset + payloadLen, byteOffset + paddedBytes, (byte) 0);
            }
            if (isAtActiveEnd) {
                LcaRegionReader.setVolatileLongBE(buffer, footerOffset, LcaRegionReader.LCA_FOOTER_CANARY_MAGIC);
                if (channel != null && channel.isOpen() && footerOffset != lastSyncedFooterOffset) {
                    writeChannelRangeUnchecked(byteOffset, buffer, byteOffset, paddedBytes + LcaRegionReader.FOOTER_SIZE);
                    lastSyncedFooterOffset = footerOffset;
                    return;
                }
            }
            writeFooterCanary();
            writeChannelRangeUnchecked(byteOffset, buffer, byteOffset, paddedBytes);
        }

        private synchronized void ensureBufferCapacity(int minCapacity) {
            if (minCapacity <= buffer.length) return;
            int step = Math.min(Math.max(buffer.length >>> 2, 16384), 262144);
            int newCap = Math.max(minCapacity, buffer.length + step);
            buffer = Arrays.copyOf(buffer, newCap);
        }

        private synchronized void writeSuperblockAndFooter() {
            int slabPoolBaseOffset = slabPoolBaseSlab << LcaRegionReader.SLAB_SHIFT;
            ensureBufferCapacity(slabPoolBaseOffset + LcaRegionReader.FOOTER_SIZE);
            ByteBuffer bb = ByteBuffer.wrap(buffer, 0, LcaRegionReader.SUPERBLOCK_SIZE);
            bb.putLong(0x00, LcaRegionReader.LCA_SUPERBLOCK_MAGIC);
            bb.putShort(0x08, (short) this.formatVersion);
            buffer[0x0A] = (byte) storageKind;
            buffer[0x0B] = (byte) (1 | (modeBLcd2 ? 8 : 0) | 16); // HAS_SHARED_DICT | optional DEFAULT_MODE_B_LCD2 | HAS_SECDED_PARITY
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

        private synchronized void writeFooterCanary() {
            if (activeEndSlab < slabPoolBaseSlab || slabPoolBaseSlab <= 0) {
                return;
            }
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

    static long packChunkPointer64(long slabOffset35, boolean modeBLcd2, int exactByteLen20, int generation8) {
        return (slabOffset35 << 29)
                | ((modeBLcd2 ? 1L : 0L) << 28)
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
            java.util.Objects.requireNonNull(dst, "dst");
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

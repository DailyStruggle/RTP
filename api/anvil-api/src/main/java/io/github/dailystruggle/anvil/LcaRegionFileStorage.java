package io.github.dailystruggle.anvil;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk-backed `.lca` region-folder storage manager and `RegionFile` stream hook
 * (`anvil-api-ADR-003` Sections 2.1, 2.3, 4; `anvil-api-ADR-002`).
 *
 * <p>Mirrors Mojang's `net.minecraft.world.level.chunk.storage.RegionFileStorage` and `RegionFile`
 * stream contract (`getChunkDataInputStream`, `getChunkDataOutputStream`, `doesChunkExist`, `flush`,
 * `close`) over a bounded LRU cache of open {@link LcaRegionWriter.LcaLiveArchive} handles,
 * supporting `region/` (`STORAGE_KIND_REGION_VOXEL`), `entities/` (`STORAGE_KIND_ENTITIES`), and
 * `poi/` (`STORAGE_KIND_POI`) folders as well as lazy on-open `.mca` / `.linear` -> `.lca`
 * atomic region migration.</p>
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaRegionFileStorage implements Closeable, AutoCloseable {

    /** Default maximum open `.lca` region handles per storage folder (512, `~12.8 MiB` header-only heap). */
    public static final int DEFAULT_MAX_OPEN_REGIONS = 512;

    /** Maximum mutable `LcaLiveArchive` header-only handles (`~25.6 KiB` each) retained in heap per storage folder. */
    private static final int DEFAULT_MAX_OPEN_WRITE_ARCHIVES = 512;

    /** Default free-slab fragmentation ratio threshold (`35%`) triggering compaction on region eviction/close. */
    public static final double DEFAULT_COMPACT_FRAGMENTATION_THRESHOLD = 0.35;

    private final Path storageDirectory;
    private final int storageKind;
    private final int minSectionY;
    private final int sectionCount;
    private final int defaultDataVersion;
    private final int zstdLevel;
    private final boolean autoMigrateLegacy;
    private final boolean backupMigratedLegacyFiles;
    private final int maxOpenRegions;
    private final int maxOpenWriteArchives;
    private final double compactFragmentationThreshold;

    private final ConcurrentHashMap<Long, LcaRegionWriter.LcaLiveArchive> regionCache =
            new ConcurrentHashMap<>(64);
    private final ConcurrentLinkedQueue<Long> regionEvictionQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, FileChannel> readChannelCache =
            new ConcurrentHashMap<>(64);
    private final ConcurrentLinkedQueue<Long> readChannelEvictionQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, CompletableFuture<LcaRegionWriter.LcaLiveArchive>> openingRegions =
            new ConcurrentHashMap<>();
    private final Set<Long> knownMissingRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong migratedRegionCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LcaRegionFileStorage(Path storageDirectory) {
        this(
                storageDirectory,
                inferStorageKindFromPath(storageDirectory),
                -4,
                24,
                4671,
                1,
                true,
                true,
                DEFAULT_MAX_OPEN_REGIONS,
                DEFAULT_COMPACT_FRAGMENTATION_THRESHOLD);
    }

    public LcaRegionFileStorage(
            Path storageDirectory,
            int storageKind,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            boolean modeBZstd,
            boolean autoMigrateLegacy,
            boolean backupMigratedLegacyFiles,
            int maxOpenRegions,
            double compactFragmentationThreshold
    ) {
        this(
                storageDirectory,
                storageKind,
                minSectionY,
                sectionCount,
                defaultDataVersion,
                modeBZstd ? 1 : 0,
                autoMigrateLegacy,
                backupMigratedLegacyFiles,
                maxOpenRegions,
                compactFragmentationThreshold);
    }

    public LcaRegionFileStorage(
            Path storageDirectory,
            int storageKind,
            int minSectionY,
            int sectionCount,
            int defaultDataVersion,
            int zstdLevel,
            boolean autoMigrateLegacy,
            boolean backupMigratedLegacyFiles,
            int maxOpenRegions,
            double compactFragmentationThreshold
    ) {
        if (storageDirectory == null) {
            throw new IllegalArgumentException("storageDirectory must not be null");
        }
        this.storageDirectory = storageDirectory;
        this.storageKind = storageKind & 0xFF;
        this.minSectionY = minSectionY;
        this.sectionCount = Math.max(1, sectionCount);
        this.defaultDataVersion = defaultDataVersion;
        this.zstdLevel = LcaRegionWriter.normalizeZstdLevel(zstdLevel);
        this.autoMigrateLegacy = autoMigrateLegacy;
        this.backupMigratedLegacyFiles = backupMigratedLegacyFiles;
        this.maxOpenRegions = Math.max(1, maxOpenRegions);
        this.maxOpenWriteArchives = Math.min(this.maxOpenRegions, DEFAULT_MAX_OPEN_WRITE_ARCHIVES);
        this.compactFragmentationThreshold = compactFragmentationThreshold;
    }

    /**
     * Infers the `.lca` superblock `storageKind` from a region folder name
     * (`"entities"` -> `1`, `"poi"` -> `2`, otherwise `0 = REGION_VOXEL`).
     */
    public static int inferStorageKindFromPath(Path directory) {
        if (directory == null || directory.getFileName() == null) {
            return LcaRegionReader.STORAGE_KIND_REGION_VOXEL;
        }
        String folderName = directory.getFileName().toString().toLowerCase(Locale.ROOT);
        if ("entities".equals(folderName)) {
            return LcaRegionReader.STORAGE_KIND_ENTITIES;
        }
        if ("poi".equals(folderName)) {
            return LcaRegionReader.STORAGE_KIND_POI;
        }
        return LcaRegionReader.STORAGE_KIND_REGION_VOXEL;
    }

    public static long packRegionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFF_FFFFL);
    }

    public Path storageDirectory() {
        return storageDirectory;
    }

    public int storageKind() {
        return storageKind;
    }

    public int openRegionCount() {
        return regionCache.size();
    }

    public long migratedRegionCount() {
        return migratedRegionCount.get();
    }

    private LcaRegionWriter.LcaLiveArchive getOpenRegionIfCached(int regionX, int regionZ) {
        return regionCache.get(packRegionKey(regionX, regionZ));
    }

    private FileChannel getOrOpenReadChannel(int regionX, int regionZ, Path lcaFile) throws IOException {
        long key = packRegionKey(regionX, regionZ);
        FileChannel cached = readChannelCache.get(key);
        if (cached != null) {
            if (cached.isOpen()) {
                return cached;
            }
            readChannelCache.remove(key, cached);
        }
        if (!Files.exists(lcaFile)) {
            return null;
        }
        FileChannel opened = FileChannel.open(lcaFile, StandardOpenOption.READ);
        FileChannel existing = readChannelCache.putIfAbsent(key, opened);
        if (existing != null) {
            if (existing.isOpen()) {
                opened.close();
                return existing;
            }
            readChannelCache.put(key, opened);
        }
        readChannelEvictionQueue.offer(key);
        while (readChannelCache.size() > maxOpenRegions) {
            Long eldestKey = readChannelEvictionQueue.poll();
            if (eldestKey == null || eldestKey.longValue() == key) {
                break;
            }
            FileChannel evicted = readChannelCache.remove(eldestKey);
            if (evicted != null) {
                evicted.close();
            }
        }
        return opened;
    }

    private void closeReadChannel(long key) throws IOException {
        FileChannel ch = readChannelCache.remove(key);
        if (ch != null) {
            ch.close();
        }
    }

    /**
     * Returns whether chunk `(chunkX, chunkZ)` exists and is generated.
     */
    public boolean doesChunkExist(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        if (knownMissingRegions.contains(key)) {
            return false;
        }
        int rx = chunkX & 31;
        int rz = chunkZ & 31;
        LcaRegionWriter.LcaLiveArchive cached = getOpenRegionIfCached(regionX, regionZ);
        if (cached != null) {
            return cached.hasChunk(rx, rz);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        if (LcaRegionReader.isChunkGeneratedInFile(lcaFile, rx, rz)) {
            return true;
        }
        if (LcaRegionReader.hasValidFileHeader(lcaFile)) {
            return false;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return false;
        }
        return archive.hasChunk(rx, rz);
    }

    /**
     * `RegionFile#getChunkDataInputStream(ChunkPos)` equivalent at world chunk coordinates `(chunkX, chunkZ)`.
     */
    public DataInputStream getChunkDataInputStream(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        if (knownMissingRegions.contains(key)) {
            return null;
        }
        int rx = chunkX & 31;
        int rz = chunkZ & 31;
        LcaRegionWriter.LcaLiveArchive cached = getOpenRegionIfCached(regionX, regionZ);
        if (cached != null) {
            return cached.getChunkDataInputStream(rx, rz);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        if (LcaRegionReader.isChunkGeneratedInFile(lcaFile, rx, rz)) {
            return new DataInputStream(new LcaRegionWriter.LazyChunkNbtInputStream(() -> {
                LcaRegionWriter.LcaLiveArchive live = getOpenRegionIfCached(regionX, regionZ);
                if (live != null) {
                    return live.readChunkNbtBytes(rx, rz);
                }
                FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                return LcaRegionReader.readChunkNbtBytesFromChannel(lcaFile, ch, rx, rz);
            }));
        }
        if (LcaRegionReader.hasValidFileHeader(lcaFile)) {
            return null;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        return archive.getChunkDataInputStream(rx, rz);
    }

    /**
     * `RegionFile#getChunkDataOutputStream(ChunkPos)` equivalent at world chunk coordinates `(chunkX, chunkZ)`.
     * Closing the returned {@link DataOutputStream} parses the written Mojang binary NBT compound and
     * commits it to the backing `.lca` region file via Copy-on-Write (`CoW`) `256B` slab rotation.
     */
    public DataOutputStream getChunkDataOutputStream(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.getChunkDataOutputStream(chunkX & 31, chunkZ & 31);
    }

    /**
     * Reads and decodes the chunk NBT compound at world chunk coordinates `(chunkX, chunkZ)`, or `null` if absent.
     */
    @SuppressWarnings({"java:S1168", "java:S1319"})
    public LinkedHashMap<String, Object> readChunkCompound(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        int rx = chunkX & 31;
        int rz = chunkZ & 31;
        LcaRegionWriter.LcaLiveArchive cached = getOpenRegionIfCached(regionX, regionZ);
        if (cached != null) {
            AnvilReader.ChunkEntry entry = cached.readChunkEntry(rx, rz);
            return entry == null ? null : entry.root;
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        if (LcaRegionReader.hasValidFileHeader(lcaFile)) {
            if (!LcaRegionReader.isChunkGeneratedInFile(lcaFile, rx, rz)) {
                return null;
            }
            FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
            AnvilReader.ChunkEntry entry = LcaRegionReader.readChunkFromChannel(lcaFile, ch, rx, rz);
            return entry == null ? null : entry.root;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        AnvilReader.ChunkEntry entry = archive.readChunkEntry(rx, rz);
        return entry == null ? null : entry.root;
    }

    /**
     * Reads and serializes the chunk at world chunk coordinates `(chunkX, chunkZ)` into uncompressed
     * Mojang binary NBT bytes, or returns `null` if absent.
     */
    @SuppressWarnings("java:S1168")
    public byte[] readChunkNbtBytes(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        int rx = chunkX & 31;
        int rz = chunkZ & 31;
        LcaRegionWriter.LcaLiveArchive cached = getOpenRegionIfCached(regionX, regionZ);
        if (cached != null) {
            return cached.readChunkNbtBytes(rx, rz);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        if (LcaRegionReader.hasValidFileHeader(lcaFile)) {
            if (!LcaRegionReader.isChunkGeneratedInFile(lcaFile, rx, rz)) {
                return null;
            }
            FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
            return LcaRegionReader.readChunkNbtBytesFromChannel(lcaFile, ch, rx, rz);
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        return archive.readChunkNbtBytes(rx, rz);
    }

    /**
     * Writes a decoded chunk NBT compound map at world chunk coordinates `(chunkX, chunkZ)` using
     * Copy-on-Write (`CoW`) `256B` sub-slab persistence.
     */
    public int writeChunkCompound(int chunkX, int chunkZ, Map<String, Object> chunkRoot) throws IOException {
        if (chunkRoot == null) {
            removeChunk(chunkX, chunkZ);
            return 0;
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.saveChunkCoW(chunkX & 31, chunkZ & 31, chunkRoot);
    }

    /**
     * Writes an uncompressed Mojang binary NBT root `TAG_Compound` byte array at world chunk coordinates `(chunkX, chunkZ)`.
     */
    public int writeChunkNbtBytes(int chunkX, int chunkZ, byte[] uncompressedNbtBytes) throws IOException {
        if (uncompressedNbtBytes == null || uncompressedNbtBytes.length == 0) {
            removeChunk(chunkX, chunkZ);
            return 0;
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.saveChunkNbtBytes(chunkX & 31, chunkZ & 31, uncompressedNbtBytes);
    }

    /**
     * Parses `uncompressedNbtBytes` and encodes the `.lca` `ChunkFrame` slab for region `(regionX, regionZ)`
     * without mutating the region pointer directory or slab allocator. Designed to run inside
     * `RegionFileVersion$StreamWrapper` on `C2ME`'s `GlobalExecutors.prioritizedScheduler` worker pool.
     */
    public byte[] encodeChunkNbtForRegionSlab(
            int regionX, int regionZ, byte[] uncompressedNbtBytes
    ) throws IOException {
        if (uncompressedNbtBytes == null || uncompressedNbtBytes.length == 0) {
            return new byte[0];
        }
        LinkedHashMap<String, Object> root = Nbt.readRootCompound(uncompressedNbtBytes);
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        int rx = extractLocalChunkCoord(root, true, regionX);
        int rz = extractLocalChunkCoord(root, false, regionZ);
        return archive.encodeChunkPayloadForSlab(rx, rz, root);
    }

    private static int extractLocalChunkCoord(Map<String, Object> root, boolean isX, int fallbackRegionCoord) {
        if (root != null) {
            Object direct = root.get(isX ? "xPos" : "zPos");
            if (direct instanceof Number n) {
                return n.intValue() & 31;
            }
            Object posObj = root.get("Position");
            if (posObj instanceof int[] posArr && posArr.length >= 2) {
                return posArr[isX ? 0 : 1] & 31;
            }
            Object levelObj = root.get("Level");
            if (levelObj instanceof Map<?, ?> levelMap) {
                Object nested = levelMap.get(isX ? "xPos" : "zPos");
                if (nested instanceof Number n) {
                    return n.intValue() & 31;
                }
            }
        }
        return (fallbackRegionCoord << 5) & 31;
    }

    /**
     * Commits a pre-encoded `.lca` `ChunkFrame` slab payload at world chunk coordinates `(chunkX, chunkZ)`
     * using Copy-on-Write (`CoW`) `256B` sub-slab persistence.
     */
    public int commitPreEncodedChunkSlab(
            int chunkX, int chunkZ, byte[] preEncodedSlabBytes
    ) throws IOException {
        if (preEncodedSlabBytes == null || preEncodedSlabBytes.length == 0) {
            removeChunk(chunkX, chunkZ);
            return 0;
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.commitPreEncodedChunkCoW(chunkX & 31, chunkZ & 31, preEncodedSlabBytes);
    }

    /**
     * Removes the chunk at `(chunkX, chunkZ)` if present.
     */
    public boolean removeChunk(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return false;
        }
        return archive.removeChunk(chunkX & 31, chunkZ & 31);
    }

    /**
     * Retrieves or opens the disk-backed {@link LcaRegionWriter.LcaLiveArchive} for `(regionX, regionZ)`.
     * Ownership is retained in {@link #regionCache} and closed on eviction or {@link #close()}.
     */
    @SuppressWarnings({"java:S2095", "resource"})
    public LcaRegionWriter.LcaLiveArchive getOrOpenRegion(
            int regionX, int regionZ, boolean createIfAbsent
    ) throws IOException {
        long key = packRegionKey(regionX, regionZ);
        closed.set(false);
        LcaRegionWriter.LcaLiveArchive cached = regionCache.get(key);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<LcaRegionWriter.LcaLiveArchive> inflight = new CompletableFuture<>();
        CompletableFuture<LcaRegionWriter.LcaLiveArchive> existingFuture = openingRegions.putIfAbsent(key, inflight);
        if (existingFuture != null) {
            try {
                LcaRegionWriter.LcaLiveArchive joined = existingFuture.join();
                if (joined != null || !createIfAbsent) {
                    return joined;
                }
            } catch (CompletionException e) {
                if (e.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw e;
            }
        }

        try {
            LcaRegionWriter.LcaLiveArchive recheck = regionCache.get(key);
            if (recheck != null) {
                inflight.complete(recheck);
                return recheck;
            }

            closeReadChannel(key);
            LcaRegionWriter.LcaLiveArchive result = null;
            Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
            if (Files.exists(lcaFile) && Files.size(lcaFile) >= LcaRegionReader.SUPERBLOCK_SIZE
                    + LcaRegionReader.POINTER_DIRECTORY_SIZE + LcaRegionReader.FOOTER_SIZE) {
                result = LcaRegionWriter.openLiveArchive(lcaFile, zstdLevel);
            } else if (autoMigrateLegacy) {
                LcaRegionWriter.LcaLiveArchive migrated = tryMigrateLegacyRegion(regionX, regionZ, lcaFile);
                if (migrated != null) {
                    migratedRegionCount.incrementAndGet();
                    result = migrated;
                }
            }

            if (result == null) {
                if (!createIfAbsent) {
                    knownMissingRegions.add(key);
                    inflight.complete(null);
                    return null;
                }
                result = LcaRegionWriter.createEmptyLiveArchive(
                        lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                        zstdLevel, storageKind, LcaRegionWriter.DEFAULT_ROOT_DICT_CAPACITY_BYTES);
            }
            knownMissingRegions.remove(key);

            closed.set(false);
            regionCache.put(key, result);
            regionEvictionQueue.offer(key);
            while (regionCache.size() > maxOpenWriteArchives) {
                Long eldestKey = regionEvictionQueue.poll();
                if (eldestKey == null || eldestKey.longValue() == key) {
                    break;
                }
                LcaRegionWriter.LcaLiveArchive evicted = regionCache.remove(eldestKey);
                if (evicted != null) {
                    closeArchiveQuietlyWithCompaction(evicted);
                }
            }
            inflight.complete(result);
            return result;
        } catch (IOException | RuntimeException | Error e) {
            inflight.completeExceptionally(e);
            throw e;
        } finally {
            openingRegions.remove(key, inflight);
        }
    }

    private LcaRegionWriter.LcaLiveArchive tryMigrateLegacyRegion(
            int regionX, int regionZ, Path targetLcaFile
    ) throws IOException {
        Path mcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
        Path legacyFile = null;
        RegionFileReader legacyReader = null;
        if (Files.exists(mcaFile) && Files.size(mcaFile) >= 8192) {
            legacyFile = mcaFile;
            legacyReader = AnvilReader.INSTANCE;
        } else {
            for (String ext : new String[] {".linear", ".b_linear"}) {
                Path linearFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ext);
                if (Files.exists(linearFile) && Files.size(linearFile) > 32) {
                    LinearRegionReader.registerIfAvailable();
                    if (RegionFormatRegistry.isRegistered(ext)) {
                        legacyFile = linearFile;
                        legacyReader = RegionFormatRegistry.getReader(ext);
                        break;
                    }
                }
            }
        }
        if (legacyFile == null || legacyReader == null) {
            return null;
        }

        byte[] srcBytes = Files.readAllBytes(legacyFile);
        byte[] lcaBytes = LcaRegionWriter.transcodeFromReader(
                legacyReader, srcBytes, regionX, regionZ, zstdLevel, storageKind);

        Files.createDirectories(storageDirectory);
        Path tmpFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca.tmp");
        try {
            Files.write(tmpFile, lcaBytes);
            Files.move(tmpFile, targetLcaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try {
                Files.deleteIfExists(tmpFile);
            } catch (IOException ignored) {
            }
        }

        if (backupMigratedLegacyFiles) {
            Path bakFile = storageDirectory.resolve(legacyFile.getFileName().toString() + ".bak");
            if (!Files.exists(bakFile)) {
                Files.move(legacyFile, bakFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return LcaRegionWriter.openLiveArchive(targetLcaFile, zstdLevel);
    }

    private void closeArchiveQuietlyWithCompaction(LcaRegionWriter.LcaLiveArchive archive) throws IOException {
        if (archive == null) {
            return;
        }
        try {
            if (compactFragmentationThreshold > 0.0 && compactFragmentationThreshold < 1.0) {
                archive.compactIfFragmented(compactFragmentationThreshold);
            }
        } finally {
            archive.closeWithoutForce();
        }
    }

    /**
     * Clears any negative missing-region cache entry for `(regionX, regionZ)` after external file creation.
     */
    public void invalidateRegionCacheState(int regionX, int regionZ) {
        long key = packRegionKey(regionX, regionZ);
        knownMissingRegions.remove(key);
        try {
            closeReadChannel(key);
        } catch (IOException ignored) {
        }
    }

    /**
     * Flushes a single open `.lca` region `(regionX, regionZ)` if currently cached.
     */
    public void flushRegion(int regionX, int regionZ) throws IOException {
        LcaRegionWriter.LcaLiveArchive archive = regionCache.get(packRegionKey(regionX, regionZ));
        if (archive != null) {
            archive.flush();
        }
    }

    /**
     * Compacts (when above threshold), flushes, and closes a single open `.lca` region `(regionX, regionZ)`.
     */
    public void closeRegion(int regionX, int regionZ) throws IOException {
        long key = packRegionKey(regionX, regionZ);
        closeReadChannel(key);
        LcaRegionWriter.LcaLiveArchive archive = regionCache.remove(key);
        if (archive != null) {
            closeArchiveQuietlyWithCompaction(archive);
        }
    }

    /**
     * Compacts all currently open `.lca` region handles whose free-slab ratio exceeds `maxFreeRatio`.
     *
     * @return number of open regions that were compacted
     */
    public int compactOpenRegions(double maxFreeRatio) throws IOException {
        List<LcaRegionWriter.LcaLiveArchive> archives = new ArrayList<>(regionCache.values());
        int compacted = 0;
        for (LcaRegionWriter.LcaLiveArchive archive : archives) {
            if (archive.compactIfFragmented(maxFreeRatio)) {
                compacted++;
            }
        }
        return compacted;
    }

    /**
     * Flushes all open `.lca` region files in this storage directory to disk.
     */
    public void flush() throws IOException {
        List<LcaRegionWriter.LcaLiveArchive> archives = new ArrayList<>(regionCache.values());
        IOException firstEx = null;
        for (LcaRegionWriter.LcaLiveArchive archive : archives) {
            try {
                archive.flush();
            } catch (IOException e) {
                if (firstEx == null) firstEx = e;
            }
        }
        if (firstEx != null) {
            throw firstEx;
        }
    }

    /**
     * Compacts (when above the fragmentation threshold), flushes, and closes all open `.lca`
     * region handles in this storage directory.
     */
    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<LcaRegionWriter.LcaLiveArchive> archives = new ArrayList<>(regionCache.values());
        regionCache.clear();
        regionEvictionQueue.clear();
        List<FileChannel> readChannels = new ArrayList<>(readChannelCache.values());
        readChannelCache.clear();
        readChannelEvictionQueue.clear();
        IOException firstEx = null;
        for (FileChannel ch : readChannels) {
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException e) {
                    if (firstEx == null) firstEx = e;
                }
            }
        }
        for (LcaRegionWriter.LcaLiveArchive archive : archives) {
            try {
                closeArchiveQuietlyWithCompaction(archive);
            } catch (IOException e) {
                if (firstEx == null) firstEx = e;
            }
        }
        if (firstEx != null) {
            throw firstEx;
        }
    }

    /**
     * Dynamic proxy / reflective stream adapter for `IRegionFile` or custom region interfaces that
     * expose `getChunkDataInputStream`, `getChunkDataOutputStream`, `doesChunkExist` / `hasChunk`,
     * `flush`, and `close`.
     */
    public static <T> T createRegionFileInterfaceProxy(
            Class<T> regionFileInterface, LcaRegionWriter.LcaLiveArchive archive
    ) {
        if (regionFileInterface == null || !regionFileInterface.isInterface()) {
            throw new IllegalArgumentException("regionFileInterface must be a valid interface");
        }
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            String name = method.getName();
            if ("flush".equals(name) || "sync".equals(name)) {
                archive.flush();
                return null;
            }
            if ("close".equals(name)) {
                archive.close();
                return null;
            }
            if ("getPath".equals(name) || "getRegionFile".equals(name)) {
                return archive.backingFile();
            }
            if (args != null && args.length >= 1) {
                int rx;
                int rz;
                if (args.length >= 2 && args[0] instanceof Number nx && args[1] instanceof Number nz) {
                    rx = nx.intValue() & 31;
                    rz = nz.intValue() & 31;
                } else {
                    int[] pos = extractChunkPosCoords(args[0]);
                    rx = pos[0] & 31;
                    rz = pos[1] & 31;
                }
                if ("getChunkDataInputStream".equals(name)) {
                    return archive.getChunkDataInputStream(rx, rz);
                }
                if ("getChunkDataOutputStream".equals(name)) {
                    return archive.getChunkDataOutputStream(rx, rz);
                }
                if ("doesChunkExist".equals(name) || "hasChunk".equals(name) || "isChunkGenerated".equals(name)) {
                    return archive.hasChunk(rx, rz);
                }
                if ("clear".equals(name) || "removeChunk".equals(name)) {
                    return archive.removeChunk(rx, rz);
                }
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            return null;
        };
        return regionFileInterface.cast(Proxy.newProxyInstance(
                regionFileInterface.getClassLoader(),
                new Class<?>[] {regionFileInterface},
                handler));
    }

    public static int[] extractChunkPosCoords(Object chunkPosObj) {
        if (chunkPosObj == null) {
            throw new IllegalArgumentException("ChunkPos argument must not be null");
        }
        Class<?> cls = chunkPosObj.getClass();
        try {
            java.lang.reflect.Field xField = cls.getField("x");
            java.lang.reflect.Field zField = cls.getField("z");
            return new int[] {xField.getInt(chunkPosObj), zField.getInt(chunkPosObj)};
        } catch (ReflectiveOperationException ignored) {
            try {
                Method xMethod = cls.getMethod("x");
                Method zMethod = cls.getMethod("z");
                return new int[] {((Number) xMethod.invoke(chunkPosObj)).intValue(),
                        ((Number) zMethod.invoke(chunkPosObj)).intValue()};
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Cannot extract (x, z) from ChunkPos object: " + cls.getName(), e);
            }
        }
    }

    /**
     * Multi-folder world storage bundle managing all three dimension persistence folders:
     * `region/` (`STORAGE_KIND_REGION_VOXEL`), `entities/` (`STORAGE_KIND_ENTITIES`), and
     * `poi/` (`STORAGE_KIND_POI`).
     */
    public static final class LcaWorldStorageSession implements Closeable, AutoCloseable {
        private final Path dimensionDirectory;
        private final LcaRegionFileStorage voxelStorage;
        private final LcaRegionFileStorage entityStorage;
        private final LcaRegionFileStorage poiStorage;

        public LcaWorldStorageSession(
                Path dimensionDirectory,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                boolean modeBZstd,
                boolean autoMigrateLegacy
        ) {
            this(
                    dimensionDirectory,
                    minSectionY,
                    sectionCount,
                    defaultDataVersion,
                    modeBZstd ? 1 : 0,
                    autoMigrateLegacy);
        }

        public LcaWorldStorageSession(
                Path dimensionDirectory,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                int zstdLevel,
                boolean autoMigrateLegacy
        ) {
            this.dimensionDirectory = dimensionDirectory;
            this.voxelStorage = new LcaRegionFileStorage(
                    dimensionDirectory.resolve("region"),
                    LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                    minSectionY,
                    sectionCount,
                    defaultDataVersion,
                    zstdLevel,
                    autoMigrateLegacy,
                    true,
                    DEFAULT_MAX_OPEN_REGIONS,
                    DEFAULT_COMPACT_FRAGMENTATION_THRESHOLD);
            this.entityStorage = new LcaRegionFileStorage(
                    dimensionDirectory.resolve("entities"),
                    LcaRegionReader.STORAGE_KIND_ENTITIES,
                    minSectionY,
                    sectionCount,
                    defaultDataVersion,
                    zstdLevel,
                    autoMigrateLegacy,
                    true,
                    DEFAULT_MAX_OPEN_REGIONS,
                    DEFAULT_COMPACT_FRAGMENTATION_THRESHOLD);
            this.poiStorage = new LcaRegionFileStorage(
                    dimensionDirectory.resolve("poi"),
                    LcaRegionReader.STORAGE_KIND_POI,
                    minSectionY,
                    sectionCount,
                    defaultDataVersion,
                    zstdLevel,
                    autoMigrateLegacy,
                    true,
                    DEFAULT_MAX_OPEN_REGIONS,
                    DEFAULT_COMPACT_FRAGMENTATION_THRESHOLD);
        }

        public Path dimensionDirectory() {
            return dimensionDirectory;
        }

        public LcaRegionFileStorage voxelStorage() {
            return voxelStorage;
        }

        public LcaRegionFileStorage entityStorage() {
            return entityStorage;
        }

        public LcaRegionFileStorage poiStorage() {
            return poiStorage;
        }

        /**
         * Resolves the {@link LcaRegionFileStorage} (`region/`, `entities/`, or `poi/`) matching the
         * given NMS `RegionFileStorage` folder path.
         */
        public LcaRegionFileStorage storageForFolder(Path folderPath) {
            int kind = inferStorageKindFromPath(folderPath);
            if (kind == LcaRegionReader.STORAGE_KIND_ENTITIES) {
                return entityStorage;
            }
            if (kind == LcaRegionReader.STORAGE_KIND_POI) {
                return poiStorage;
            }
            return voxelStorage;
        }

        public int totalOpenRegionCount() {
            return voxelStorage.openRegionCount()
                    + entityStorage.openRegionCount()
                    + poiStorage.openRegionCount();
        }

        public long totalMigratedRegionCount() {
            return voxelStorage.migratedRegionCount()
                    + entityStorage.migratedRegionCount()
                    + poiStorage.migratedRegionCount();
        }

        public int compactOpenRegions(double maxFreeRatio) throws IOException {
            return voxelStorage.compactOpenRegions(maxFreeRatio)
                    + entityStorage.compactOpenRegions(maxFreeRatio)
                    + poiStorage.compactOpenRegions(maxFreeRatio);
        }

        public void flush() throws IOException {
            voxelStorage.flush();
            entityStorage.flush();
            poiStorage.flush();
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (LcaRegionFileStorage s : List.of(voxelStorage, entityStorage, poiStorage)) {
                try {
                    s.close();
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}

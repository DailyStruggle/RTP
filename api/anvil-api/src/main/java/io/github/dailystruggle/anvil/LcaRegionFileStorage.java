package io.github.dailystruggle.anvil;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * @governedBy leafanvil-ADR-005
 * @governedBy leafanvil-ADR-006
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaRegionFileStorage implements Closeable, AutoCloseable {

    /** Default maximum open `.lca` region handles per storage folder (128, `~3.2 MiB` header-only heap). */
    public static final int DEFAULT_MAX_OPEN_REGIONS =
            Math.max(16, Integer.getInteger("leafanvil.storage.maxOpenRegions", 128));

    /** Upper bound (`16 MiB`) on decompressed chunk NBT bytes accepted by {@link #decodeAnvilChunkPayload}. */
    public static final int MAX_DECODED_CHUNK_NBT_BYTES = 16 * 1024 * 1024;

    /**
     * Sentinel root byte-tag name emitted by `MixinSerializableChunkData` / `-javaagent` direct CoW
     * chunk save hooks after committing the spliced `.lca` `ChunkFrame` directly so downstream
     * `RegionFileStorage` stream wrappers treat the metadata envelope as an already-persisted no-op.
     */
    public static final String DIRECT_COW_COMMITTED_TAG = "__leafanvil_cow_committed";
    private static final byte[] DIRECT_COW_MARKER_UTF8 =
            DIRECT_COW_COMMITTED_TAG.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public static boolean containsDirectCoWCommittedMarker(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= DIRECT_COW_MARKER_UTF8.length || length > 512) {
            return false;
        }
        int end = offset + length - DIRECT_COW_MARKER_UTF8.length;
        outer:
        for (int i = offset; i <= end; i++) {
            for (int j = 0; j < DIRECT_COW_MARKER_UTF8.length; j++) {
                if (bytes[i + j] != DIRECT_COW_MARKER_UTF8[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    public static boolean containsDirectCoWCommittedMarker(ByteBuffer buf) {
        if (buf == null) {
            return false;
        }
        int rem = buf.remaining();
        if (rem <= DIRECT_COW_MARKER_UTF8.length || rem > 512) {
            return false;
        }
        if (buf.hasArray()) {
            return containsDirectCoWCommittedMarker(buf.array(), buf.arrayOffset() + buf.position(), rem);
        }
        byte[] tmp = new byte[rem];
        buf.duplicate().get(tmp);
        return containsDirectCoWCommittedMarker(tmp, 0, rem);
    }

    /**
     * Decodes a compressed Anvil region stream payload (`1` = GZIP, `2` = Zlib/Deflate,
     * `3` = Uncompressed, `4` = Mojang `LZ4Block`) into uncompressed Mojang binary NBT bytes,
     * failing closed with {@link IOException} on unknown compression IDs or oversized streams
     * (`anvil-api-ADR-001` Section 15, `REQ-RTP-S-004`).
     */
    public static byte[] decodeAnvilChunkPayload(
            int compressionId, byte[] payload, int offset, int length
    ) throws IOException {
        if (payload == null || offset < 0 || length < 0 || offset > payload.length - length) {
            throw new IOException("Invalid Anvil chunk payload slice (offset=" + offset + ", length=" + length + ")");
        }
        if (compressionId == 3) {
            if (length > MAX_DECODED_CHUNK_NBT_BYTES) {
                throw new IOException("Uncompressed chunk payload exceeds " + MAX_DECODED_CHUNK_NBT_BYTES + " bytes");
            }
            return java.util.Arrays.copyOfRange(payload, offset, offset + length);
        }
        if (compressionId == 4) {
            return Lz4BlockDecoder.decode(payload, offset, length, MAX_DECODED_CHUNK_NBT_BYTES);
        }
        if (compressionId == 2) {
            try (java.io.InputStream in = new java.util.zip.InflaterInputStream(
                    new java.io.ByteArrayInputStream(payload, offset, length))) {
                return readBoundedStreamBytes(in, MAX_DECODED_CHUNK_NBT_BYTES);
            }
        }
        if (compressionId == 1) {
            try (java.io.InputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(payload, offset, length))) {
                return readBoundedStreamBytes(in, MAX_DECODED_CHUNK_NBT_BYTES);
            }
        }
        throw new IOException("Unsupported Anvil chunk compression ID: " + compressionId);
    }

    private static byte[] readBoundedStreamBytes(java.io.InputStream in, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Decompressed chunk stream exceeds " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Maximum mutable `LcaLiveArchive` header-only handles (`~25.6 KiB` each) retained in heap per storage folder. */
    private static final int DEFAULT_MAX_OPEN_WRITE_ARCHIVES = DEFAULT_MAX_OPEN_REGIONS;

    /** Default free-slab fragmentation ratio threshold (`35%`) triggering compaction on region eviction/close. */
    public static final double DEFAULT_COMPACT_FRAGMENTATION_THRESHOLD = 0.35;

    /** Maximum number of read-only regions whose sequential read-ahead snapshots are tracked concurrently. */
    private static final int MAX_READ_AHEAD_REGIONS = 2;

    private static boolean isDefaultBufferedIoEnabled() {
        String prop = System.getProperty("leafanvil.io.buffered");
        return prop == null || Boolean.parseBoolean(prop);
    }

    private final Path storageDirectory;
    private final int storageKind;
    private final int minSectionY;
    private final int sectionCount;
    private final int defaultDataVersion;
    private final int lcd2Level;
    private final boolean autoMigrateLegacy;
    private final boolean backupMigratedLegacyFiles;
    private final int maxOpenRegions;
    private final int maxOpenWriteArchives;
    private final double compactFragmentationThreshold;

    private volatile boolean bufferedWriteBackEnabled;
    private volatile int writeBackMaxChunks = LcaRegionWriter.LcaLiveArchive.DEFAULT_WRITE_BACK_MAX_CHUNKS;
    private volatile int writeBackMaxBytes = LcaRegionWriter.LcaLiveArchive.DEFAULT_WRITE_BACK_MAX_BYTES;
    private volatile boolean sequentialReadAheadEnabled;
    private volatile int readAheadTriggerReads = LcaRegionWriter.LcaLiveArchive.DEFAULT_READ_AHEAD_TRIGGER_READS;
    private volatile int readAheadMaxBytes = LcaRegionWriter.LcaLiveArchive.DEFAULT_READ_AHEAD_MAX_BYTES;

    private final ConcurrentHashMap<Long, LcaRegionWriter.LcaLiveArchive> regionCache =
            new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<Long, Object> regionLocks =
            new ConcurrentHashMap<>(64);
    private final Set<Long> recentRegionKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> regionEvictionQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, FileChannel> readChannelCache =
            new ConcurrentHashMap<>(64);
    private final Set<Long> recentReadChannelKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> readChannelEvictionQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, AtomicInteger> readChannelColdMisses =
            new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<Long, SoftReference<byte[]>> readAheadRegionBytes =
            new ConcurrentHashMap<>(16);
    private final ConcurrentLinkedQueue<Long> readAheadEvictionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong readOnlySequentialReadAheadCount = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<LcaRegionWriter.LcaLiveArchive>> openingRegions =
            new ConcurrentHashMap<>();
    private final Set<Long> knownMissingRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong migratedRegionCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LcaRegionFileStorage(Path storageDirectory) {
        this(storageDirectory, true);
    }

    public LcaRegionFileStorage(Path storageDirectory, boolean modeBLcd2) {
        this(
                storageDirectory,
                inferStorageKindFromPath(storageDirectory),
                -4,
                24,
                4671,
                modeBLcd2 ? 1 : 0,
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
            boolean modeBLcd2,
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
                modeBLcd2 ? 1 : 0,
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
            int lcd2Level,
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
        this.lcd2Level = LcaRegionWriter.normalizeLcd2Level(lcd2Level);
        this.autoMigrateLegacy = autoMigrateLegacy;
        this.backupMigratedLegacyFiles = backupMigratedLegacyFiles;
        this.maxOpenRegions = Math.max(1, maxOpenRegions);
        this.maxOpenWriteArchives = Math.min(this.maxOpenRegions, DEFAULT_MAX_OPEN_WRITE_ARCHIVES);
        this.compactFragmentationThreshold = compactFragmentationThreshold;
        boolean defaultBuffered = isDefaultBufferedIoEnabled();
        this.bufferedWriteBackEnabled = defaultBuffered;
        this.sequentialReadAheadEnabled = defaultBuffered;
    }

    /** Returns {@code true} if either buffered write-back or adaptive sequential read-ahead is enabled. */
    public boolean isBufferedIoEnabled() {
        return bufferedWriteBackEnabled || sequentialReadAheadEnabled;
    }

    /**
     * Enables or disables both coalesced write-back staging and adaptive sequential read-ahead
     * across this storage folder and its currently open archives.
     */
    public void setBufferedIoEnabled(boolean enabled) throws IOException {
        configureBufferedIo(
                enabled,
                writeBackMaxChunks,
                writeBackMaxBytes,
                enabled,
                readAheadTriggerReads);
    }

    /**
     * Configures coalesced write-back staging and adaptive sequential read-ahead thresholds.
     */
    public void configureBufferedIo(
            boolean writeBackEnabled,
            int maxPendingChunks,
            int maxPendingBytes,
            boolean readAheadEnabled,
            int readAheadTrigger
    ) throws IOException {
        this.bufferedWriteBackEnabled = writeBackEnabled;
        this.writeBackMaxChunks = Math.max(1, maxPendingChunks);
        this.writeBackMaxBytes = Math.max(4096, maxPendingBytes);
        this.sequentialReadAheadEnabled = readAheadEnabled;
        this.readAheadTriggerReads = Math.max(1, readAheadTrigger);
        if (!readAheadEnabled) {
            readAheadRegionBytes.clear();
            readAheadEvictionQueue.clear();
            readChannelColdMisses.clear();
        }
        for (LcaRegionWriter.LcaLiveArchive archive : regionCache.values()) {
            archive.configureBufferedWriteBack(this.bufferedWriteBackEnabled, this.writeBackMaxChunks, this.writeBackMaxBytes);
            archive.configureSequentialReadAhead(this.sequentialReadAheadEnabled, this.readAheadTriggerReads, this.readAheadMaxBytes);
        }
    }

    private volatile boolean directFlatBitPack;
    private volatile String[] worldgenBlockDictionary;

    /**
     * Registers a world-level canonical block-state palette (matching the GPU worldgen palette)
     * so every newly opened or created `.lca` region pre-registers those states deterministically
     * for O(1) GPU DMA dictionary-ID saves.
     */
    public void setWorldgenBlockDictionary(String[] canonicalBlockStates) {
        String[] copy = (canonicalBlockStates != null && canonicalBlockStates.length > 0)
                ? canonicalBlockStates.clone()
                : null;
        this.worldgenBlockDictionary = copy;
        if (copy != null && storageKind == LcaRegionReader.STORAGE_KIND_REGION_VOXEL) {
            for (LcaRegionWriter.LcaLiveArchive archive : regionCache.values()) {
                archive.bindWorldgenBlockDictionary(copy);
            }
        }
    }

    public String[] worldgenBlockDictionary() {
        String[] dict = this.worldgenBlockDictionary;
        return dict != null ? dict.clone() : null;
    }

    /**
     * Enables or disables direct {@code BLOCK_MODE_FLAT_BITPACK} ({@code mode 10}, YZX) section
     * emission across this storage folder and all open/future archives for unified
     * Disk = Memory = GPU parity.
     */
    public void setDirectFlatBitPack(boolean enabled) {
        this.directFlatBitPack = enabled;
        for (LcaRegionWriter.LcaLiveArchive archive : regionCache.values()) {
            archive.setDirectFlatBitPack(enabled);
        }
    }

    public boolean isDirectFlatBitPack() {
        return this.directFlatBitPack;
    }

    private volatile boolean forceSyncOnClose = true;

    /**
     * Controls whether {@link #close()} and {@link #closeRegion(int, int)} invoke
     * {@code channel.force(false)} ({@code fdatasync}) on dirty archives. Defaults to {@code true}
     * for standalone durability; server integrations may set {@code false} to match vanilla
     * {@code .mca} {@code sync-chunk-writes=false} while still flushing all pending writes to the
     * OS page cache.
     */
    public void setForceSyncOnClose(boolean enabled) {
        this.forceSyncOnClose = enabled;
    }

    public boolean isForceSyncOnClose() {
        return this.forceSyncOnClose;
    }

    /** Number of read-only regions promoted to sequential full-region read-ahead intake. */
    public long readOnlySequentialReadAheadCount() {
        return readOnlySequentialReadAheadCount.get();
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

    public int lcd2Level() {
        return lcd2Level;
    }

    public boolean modeBLcd2() {
        return lcd2Level > 0;
    }

    public int openRegionCount() {
        return regionCache.size();
    }

    public long migratedRegionCount() {
        return migratedRegionCount.get();
    }

    private LcaRegionWriter.LcaLiveArchive getOpenRegionIfCached(int regionX, int regionZ) {
        long key = packRegionKey(regionX, regionZ);
        LcaRegionWriter.LcaLiveArchive cached = regionCache.get(key);
        if (cached != null) {
            recentRegionKeys.add(key);
        }
        return cached;
    }

    private FileChannel getOrOpenReadChannel(int regionX, int regionZ, Path lcaFile) throws IOException {
        long key = packRegionKey(regionX, regionZ);
        FileChannel cached = readChannelCache.get(key);
        if (cached != null) {
            if (cached.isOpen()) {
                recentReadChannelKeys.add(key);
                return cached;
            }
            readChannelCache.remove(key, cached);
        }
        FileChannel opened;
        try {
            opened = FileChannel.open(lcaFile, StandardOpenOption.READ);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
        FileChannel existing = readChannelCache.putIfAbsent(key, opened);
        if (existing != null) {
            if (existing.isOpen()) {
                opened.close();
                recentReadChannelKeys.add(key);
                return existing;
            }
            readChannelCache.put(key, opened);
        }
        readChannelEvictionQueue.offer(key);
        int maxScans = (maxOpenRegions << 1) + 8;
        int scans = 0;
        while (readChannelCache.size() > maxOpenRegions && scans++ < maxScans) {
            Long eldestKey = readChannelEvictionQueue.poll();
            if (eldestKey == null || eldestKey.longValue() == key) {
                if (eldestKey != null) {
                    readChannelEvictionQueue.offer(eldestKey);
                }
                break;
            }
            if (!readChannelCache.containsKey(eldestKey)) {
                recentReadChannelKeys.remove(eldestKey);
                continue;
            }
            if (recentReadChannelKeys.remove(eldestKey)) {
                readChannelEvictionQueue.offer(eldestKey);
                continue;
            }
            FileChannel evicted = readChannelCache.remove(eldestKey);
            if (evicted != null) {
                evicted.close();
            }
        }
        return opened;
    }

    private void closeReadChannel(long key) throws IOException {
        recentReadChannelKeys.remove(key);
        readChannelColdMisses.remove(key);
        readAheadRegionBytes.remove(key);
        FileChannel ch = readChannelCache.remove(key);
        if (ch != null) {
            ch.close();
        }
    }

    private byte[] getOrPromoteReadAheadRegionBytes(long key, FileChannel ch) throws IOException {
        if (!sequentialReadAheadEnabled || ch == null || !ch.isOpen()) {
            return null;
        }
        SoftReference<byte[]> ref = readAheadRegionBytes.get(key);
        if (ref != null) {
            byte[] cached = ref.get();
            if (cached != null) {
                return cached;
            }
            readAheadRegionBytes.remove(key, ref);
        }
        AtomicInteger misses = readChannelColdMisses.computeIfAbsent(key, k -> new AtomicInteger());
        if (misses.incrementAndGet() < readAheadTriggerReads) {
            return null;
        }
        synchronized (regionLock(key)) {
            if (regionCache.containsKey(key) || !ch.isOpen()) {
                return null;
            }
            SoftReference<byte[]> recheck = readAheadRegionBytes.get(key);
            if (recheck != null) {
                byte[] cached = recheck.get();
                if (cached != null) {
                    return cached;
                }
            }
            long fileSize = ch.size();
            if (fileSize <= 0L || fileSize > readAheadMaxBytes) {
                misses.set(0);
                return null;
            }
            byte[] fullBytes = new byte[(int) fileSize];
            ByteBuffer bb = ByteBuffer.wrap(fullBytes);
            long pos = 0L;
            while (bb.hasRemaining()) {
                int n = ch.read(bb, pos);
                if (n < 0) {
                    break;
                }
                pos += n;
            }
            if (pos < fileSize) {
                return null;
            }
            readAheadRegionBytes.put(key, new SoftReference<>(fullBytes));
            readAheadEvictionQueue.offer(key);
            while (readAheadRegionBytes.size() > MAX_READ_AHEAD_REGIONS) {
                Long eldest = readAheadEvictionQueue.poll();
                if (eldest == null || eldest.longValue() == key) {
                    if (eldest != null) {
                        readAheadEvictionQueue.offer(eldest);
                    }
                    break;
                }
                readAheadRegionBytes.remove(eldest);
            }
            misses.set(0);
            readOnlySequentialReadAheadCount.incrementAndGet();
            return fullBytes;
        }
    }

    private LcaRegionReader.CachedFileHeader resolveReadHeader(
            int regionX, int regionZ, Path lcaFile
    ) throws IOException {
        long key = packRegionKey(regionX, regionZ);
        if (knownMissingRegions.contains(key)) {
            return null;
        }
        LcaRegionReader.CachedFileHeader fast = LcaRegionReader.peekCachedFileHeader(lcaFile);
        if (fast != null) {
            return fast;
        }
        FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
        if (ch != null) {
            try {
                return LcaRegionReader.getOrLoadFileHeader(lcaFile, ch);
            } catch (java.nio.channels.ClosedChannelException race) {
                synchronized (regionLock(key)) {
                    closeReadChannel(key);
                    FileChannel fresh = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                    return fresh != null ? LcaRegionReader.getOrLoadFileHeader(lcaFile, fresh) : null;
                }
            }
        }
        return null;
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
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            return header.isOccupied(rx, rz);
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
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
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            if (!header.isOccupied(rx, rz)) {
                return null;
            }
            return new DataInputStream(new java.io.BufferedInputStream(
                    new LcaRegionWriter.LazyChunkNbtInputStream(() -> {
                        LcaRegionWriter.LcaLiveArchive live = getOpenRegionIfCached(regionX, regionZ);
                        if (live != null) {
                            return live.readChunkNbtBytes(rx, rz);
                        }
                        try {
                            FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                            byte[] prefetched = getOrPromoteReadAheadRegionBytes(key, ch);
                            if (prefetched != null) {
                                return LcaRegionReader.INSTANCE.readChunkNbtBytes(prefetched, rx, rz);
                            }
                            return LcaRegionReader.readChunkNbtBytesFromChannel(lcaFile, ch, rx, rz);
                        } catch (CorruptRegionEntryException | java.nio.channels.ClosedChannelException race) {
                            synchronized (regionLock(key)) {
                                LcaRegionWriter.LcaLiveArchive lockedLive = getOpenRegionIfCached(regionX, regionZ);
                                if (lockedLive != null) {
                                    return lockedLive.readChunkNbtBytes(rx, rz);
                                }
                                LcaRegionReader.invalidateFileCache(lcaFile);
                                closeReadChannel(key);
                                FileChannel fresh = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                                return LcaRegionReader.readChunkNbtBytesFromChannel(lcaFile, fresh, rx, rz);
                            }
                        }
                    }),
                    8192));
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
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
        java.io.OutputStream commitOut = new java.io.OutputStream() {
            private byte[] buf = new byte[65536];
            private int count;
            private boolean streamClosed;

            private void ensureCap(int needed) {
                if (needed > buf.length) {
                    int next = Math.max(needed, buf.length << 1);
                    buf = java.util.Arrays.copyOf(buf, next);
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
                if (streamClosed) {
                    return;
                }
                streamClosed = true;
                if (count > 0) {
                    writeChunkNbtBytes(chunkX, chunkZ, buf, 0, count);
                }
            }
        };
        return new DataOutputStream(new java.io.BufferedOutputStream(commitOut, 8192));
    }

    /**
     * Reads and decodes the chunk NBT compound at world chunk coordinates `(chunkX, chunkZ)`, or `null` if absent.
     */
    @SuppressWarnings({"java:S1168", "java:S1319"})
    public LinkedHashMap<String, Object> readChunkCompound(int chunkX, int chunkZ) throws IOException {
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
            AnvilReader.ChunkEntry entry = cached.readChunkEntry(rx, rz);
            return entry == null ? null : entry.root;
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            if (!header.isOccupied(rx, rz)) {
                return null;
            }
            FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
            AnvilReader.ChunkEntry entry = LcaRegionReader.readChunkFromChannel(lcaFile, ch, rx, rz);
            return entry == null ? null : entry.root;
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
            return null;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        AnvilReader.ChunkEntry entry = archive.readChunkEntry(rx, rz);
        return entry == null ? null : entry.root;
    }

    /**
     * Reads the canonical uncompressed `.lca` `ChunkFrame` at world chunk coordinates `(chunkX, chunkZ)`
     * using positional `FileChannel` reads + `pinHazardSlab` (Mode A zero-copy read, Mode B pure-Java
     * `LCD2` `0x4C44` unpack) without synthesizing Mojang NBT (`leafanvil-ADR-005`, `leafanvil-ADR-006`,
     * `REQ-RTP-S-002`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    @SuppressWarnings("java:S1168")
    public RawChunkFrameSnapshot readRawChunkFrame(
            int chunkX,
            int chunkZ,
            java.util.function.Function<String, Integer> runtimeStateLookup
    ) throws IOException {
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
            return cached.readRawChunkFrame(rx, rz, runtimeStateLookup);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            if (!header.isOccupied(rx, rz)) {
                return null;
            }
            try {
                FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                return LcaRegionReader.readRawChunkFrameFromChannel(lcaFile, ch, rx, rz, runtimeStateLookup);
            } catch (CorruptRegionEntryException | java.nio.channels.ClosedChannelException race) {
                synchronized (regionLock(key)) {
                    LcaRegionWriter.LcaLiveArchive lockedLive = getOpenRegionIfCached(regionX, regionZ);
                    if (lockedLive != null) {
                        return lockedLive.readRawChunkFrame(rx, rz, runtimeStateLookup);
                    }
                    LcaRegionReader.invalidateFileCache(lcaFile);
                    closeReadChannel(key);
                    FileChannel fresh = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                    return LcaRegionReader.readRawChunkFrameFromChannel(lcaFile, fresh, rx, rz, runtimeStateLookup);
                }
            }
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
            return null;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        return archive.readRawChunkFrame(rx, rz, runtimeStateLookup);
    }

    /**
     * Reads both the immutable uncompressed {@link RawChunkFrameSnapshot} and lightweight Mojang
     * metadata NBT bytes (with section {@code block_states} omitted when fast-path eligible) in a
     * single slab read and decompression pass (`REQ-RTP-S-005`).
     */
    @SuppressWarnings("java:S1168")
    public LcaRegionReader.RawChunkFrameWithMetadata readRawChunkFrameWithMetadata(
            int chunkX,
            int chunkZ,
            java.util.function.Function<String, Integer> runtimeStateLookup
    ) throws IOException {
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
            return cached.readRawChunkFrameWithMetadata(rx, rz, runtimeStateLookup);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            if (!header.isOccupied(rx, rz)) {
                return null;
            }
            try {
                FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                byte[] prefetched = getOrPromoteReadAheadRegionBytes(key, ch);
                if (prefetched != null) {
                    return LcaRegionReader.readRawChunkFrameWithMetadataFromBytes(
                            header, ch, prefetched, rx, rz, runtimeStateLookup);
                }
                return LcaRegionReader.readRawChunkFrameWithMetadataFromChannel(
                        lcaFile, ch, rx, rz, runtimeStateLookup);
            } catch (CorruptRegionEntryException | java.nio.channels.ClosedChannelException race) {
                synchronized (regionLock(key)) {
                    LcaRegionWriter.LcaLiveArchive lockedLive = getOpenRegionIfCached(regionX, regionZ);
                    if (lockedLive != null) {
                        return lockedLive.readRawChunkFrameWithMetadata(rx, rz, runtimeStateLookup);
                    }
                    LcaRegionReader.invalidateFileCache(lcaFile);
                    closeReadChannel(key);
                    FileChannel fresh = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                    return LcaRegionReader.readRawChunkFrameWithMetadataFromChannel(
                            lcaFile, fresh, rx, rz, runtimeStateLookup);
                }
            }
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
            return null;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        return archive.readRawChunkFrameWithMetadata(rx, rz, runtimeStateLookup);
    }

    /**
     * Reads both the immutable uncompressed {@link RawChunkFrameSnapshot} and zero-NBT decoded
     * {@link LcaDecodedChunkMeta} in a single slab read and decompression pass (`REQ-RTP-S-005`).
     */
    @SuppressWarnings("java:S1168")
    public LcaRegionReader.RawChunkFrameWithDirectMeta readRawChunkFrameWithDirectMeta(
            int chunkX,
            int chunkZ,
            java.util.function.Function<String, Integer> runtimeStateLookup
    ) throws IOException {
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
            return cached.readRawChunkFrameWithDirectMeta(rx, rz, runtimeStateLookup);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            if (!header.isOccupied(rx, rz)) {
                return null;
            }
            try {
                FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                byte[] prefetched = getOrPromoteReadAheadRegionBytes(key, ch);
                if (prefetched != null) {
                    return LcaRegionReader.readRawChunkFrameWithDirectMetaFromBytes(
                            header, ch, prefetched, rx, rz, runtimeStateLookup);
                }
                return LcaRegionReader.readRawChunkFrameWithDirectMetaFromChannel(
                        lcaFile, ch, rx, rz, runtimeStateLookup);
            } catch (CorruptRegionEntryException | java.nio.channels.ClosedChannelException race) {
                synchronized (regionLock(key)) {
                    LcaRegionWriter.LcaLiveArchive lockedLive = getOpenRegionIfCached(regionX, regionZ);
                    if (lockedLive != null) {
                        return lockedLive.readRawChunkFrameWithDirectMeta(rx, rz, runtimeStateLookup);
                    }
                    LcaRegionReader.invalidateFileCache(lcaFile);
                    closeReadChannel(key);
                    FileChannel fresh = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                    return LcaRegionReader.readRawChunkFrameWithDirectMetaFromChannel(
                            lcaFile, fresh, rx, rz, runtimeStateLookup);
                }
            }
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
            return null;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        return archive.readRawChunkFrameWithDirectMeta(rx, rz, runtimeStateLookup);
    }

    /**
     * Reads only the raw `.lca` chunk slab and cached dictionary metadata on the calling I/O thread,
     * returning a thread-safe {@link LcaRegionReader.DeferredChunkSlab} whose Mode B decompression
     * and zero-NBT metadata decoding run in parallel on worker threads (`REQ-RTP-S-005`).
     */
    @SuppressWarnings("java:S1168")
    public LcaRegionReader.DeferredChunkSlab readDeferredChunkSlab(
            int chunkX,
            int chunkZ,
            java.util.function.Function<String, Integer> runtimeStateLookup
    ) throws IOException {
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
            return cached.readDeferredChunkSlab(rx, rz, runtimeStateLookup);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            if (!header.isOccupied(rx, rz)) {
                return null;
            }
            try {
                FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                byte[] prefetched = getOrPromoteReadAheadRegionBytes(key, ch);
                if (prefetched != null) {
                    return LcaRegionReader.readDeferredChunkSlabFromBytes(
                            header, ch, prefetched, rx, rz, runtimeStateLookup);
                }
                return LcaRegionReader.readDeferredChunkSlabFromChannel(
                        lcaFile, ch, rx, rz, runtimeStateLookup);
            } catch (CorruptRegionEntryException | java.nio.channels.ClosedChannelException race) {
                synchronized (regionLock(key)) {
                    LcaRegionWriter.LcaLiveArchive lockedLive = getOpenRegionIfCached(regionX, regionZ);
                    if (lockedLive != null) {
                        return lockedLive.readDeferredChunkSlab(rx, rz, runtimeStateLookup);
                    }
                    LcaRegionReader.invalidateFileCache(lcaFile);
                    closeReadChannel(key);
                    FileChannel fresh = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                    return LcaRegionReader.readDeferredChunkSlabFromChannel(
                            lcaFile, fresh, rx, rz, runtimeStateLookup);
                }
            }
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
            return null;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        return archive.readDeferredChunkSlab(rx, rz, runtimeStateLookup);
    }

    /**
     * Translates a host {@code runtimeStateId} into the open archive's {@code globalDictId} for
     * region `(regionX, regionZ)`, caching the mapping in $O(1)$ (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public int resolveRuntimeIdToDictId(
            int regionX,
            int regionZ,
            int runtimeStateId,
            java.util.function.IntFunction<String> runtimeIdToCanonicalState
    ) throws IOException {
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.resolveRuntimeIdToDictId(runtimeStateId, runtimeIdToCanonicalState);
    }

    /**
     * Registers or looks up a canonical `BlockState` string in region `(regionX, regionZ)`'s dictionary
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public int registerBlockStateInRegion(int regionX, int regionZ, String canonicalBlockState) throws IOException {
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.registerOrGetBlockStateId(canonicalBlockState);
    }

    /**
     * Registers or looks up a canonical `Biome` identifier in region `(regionX, regionZ)`'s dictionary
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public int registerBiomeInRegion(int regionX, int regionZ, String canonicalBiome) throws IOException {
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.registerOrGetBiomeId(canonicalBiome);
    }

    /**
     * Splices re-encoded dirty sections (`dirtySectionMask`) with verbatim byte copies of untouched
     * `SectionFrame`s from `coldChunkFrame` and commits the new slab via `.lca` `256B` Copy-on-Write
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public void commitSplicedChunkFrameCoW(
            int chunkX,
            int chunkZ,
            byte[] coldChunkFrame,
            int dirtySectionMask,
            java.util.function.IntFunction<int[]> dirtySectionVoxels,
            byte[] updatedResidualNbtOrNull
    ) throws IOException {
        commitAndGetSplicedChunkFrameCoW(
                chunkX, chunkZ, coldChunkFrame, dirtySectionMask, dirtySectionVoxels, updatedResidualNbtOrNull);
    }

    /**
     * Splices re-encoded dirty sections (`dirtySectionMask`) with verbatim byte copies of untouched
     * `SectionFrame`s from `coldChunkFrame`, commits via `.lca` `256B` Copy-on-Write, and returns
     * the newly committed uncompressed `ChunkFrame` buffer for atomic in-memory baseline swap
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public byte[] commitAndGetSplicedChunkFrameCoW(
            int chunkX,
            int chunkZ,
            byte[] coldChunkFrame,
            int dirtySectionMask,
            java.util.function.IntFunction<int[]> dirtySectionVoxels,
            byte[] updatedResidualNbtOrNull
    ) throws IOException {
        return commitAndGetSplicedChunkFrameCoW(
                chunkX,
                chunkZ,
                coldChunkFrame,
                dirtySectionMask,
                dirtySectionVoxels,
                null,
                null,
                updatedResidualNbtOrNull
        );
    }

    public byte[] commitAndGetSplicedChunkFrameCoW(
            int chunkX,
            int chunkZ,
            byte[] coldChunkFrame,
            int dirtySectionMask,
            java.util.function.IntFunction<int[]> dirtySectionVoxels,
            java.util.function.IntFunction<byte[]> updatedSectionSkyLight,
            java.util.function.IntFunction<byte[]> updatedSectionBlockLight,
            byte[] updatedResidualNbtOrNull
    ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return archive.spliceAndCommitChunkFrameCoW(
                chunkX & 31,
                chunkZ & 31,
                coldChunkFrame,
                dirtySectionMask,
                dirtySectionVoxels,
                updatedSectionSkyLight,
                updatedSectionBlockLight,
                updatedResidualNbtOrNull
        );
    }

    /**
     * Reads and serializes the chunk at world chunk coordinates `(chunkX, chunkZ)` into uncompressed
     * Mojang binary NBT bytes, or returns `null` if absent.
     */
    @SuppressWarnings("java:S1168")
    public byte[] readChunkNbtBytes(int chunkX, int chunkZ) throws IOException {
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
            return cached.readChunkNbtBytes(rx, rz);
        }
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        LcaRegionReader.CachedFileHeader header = resolveReadHeader(regionX, regionZ, lcaFile);
        if (header != null) {
            if (!header.isOccupied(rx, rz)) {
                return null;
            }
            try {
                FileChannel ch = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                byte[] prefetched = getOrPromoteReadAheadRegionBytes(key, ch);
                if (prefetched != null) {
                    return LcaRegionReader.INSTANCE.readChunkNbtBytes(prefetched, rx, rz);
                }
                return LcaRegionReader.readChunkNbtBytesFromChannel(lcaFile, ch, rx, rz);
            } catch (CorruptRegionEntryException | java.nio.channels.ClosedChannelException race) {
                synchronized (regionLock(key)) {
                    LcaRegionWriter.LcaLiveArchive lockedLive = getOpenRegionIfCached(regionX, regionZ);
                    if (lockedLive != null) {
                        return lockedLive.readChunkNbtBytes(rx, rz);
                    }
                    LcaRegionReader.invalidateFileCache(lcaFile);
                    closeReadChannel(key);
                    FileChannel fresh = getOrOpenReadChannel(regionX, regionZ, lcaFile);
                    return LcaRegionReader.readChunkNbtBytesFromChannel(lcaFile, fresh, rx, rz);
                }
            }
        }
        if (!autoMigrateLegacy) {
            knownMissingRegions.add(key);
            return null;
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, false);
        if (archive == null) {
            return null;
        }
        return archive.readChunkNbtBytes(rx, rz);
    }

    private Object regionLock(long key) {
        return regionLocks.computeIfAbsent(key, k -> new Object());
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
        long key = packRegionKey(regionX, regionZ);
        int written;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, true);
            written = archive.saveChunkCoW(chunkX & 31, chunkZ & 31, chunkRoot);
        }
        evictExcessRegions(key);
        return written;
    }

    /**
     * Returns the shared 2048-byte all-zero light array sentinel used to preserve explicit
     * Starlight {@code state == 1} light sections in {@link #writeFreshChunkDirect}.
     */
    public static byte[] allZeroLight2048() {
        return LcaRegionWriter.ALL_ZERO_LIGHT_2048;
    }

    /**
     * Writes a freshly generated chunk directly into `.lca` CoW storage from pre-extracted
     * section palettes, packed {@code SimpleBitStorage} {@code long[]} arrays, light arrays,
     * and a pre-encoded residual metadata tail — skipping Mojang NBT section allocation and
     * {@code NbtIo} round-tripping ({@code anvil-api-ADR-003}, {@code REQ-RTP-S-004}).
     */
    public int writeFreshChunkDirect(
            int chunkX,
            int chunkZ,
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
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        int written;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, true);
            written = archive.saveFreshChunkDirect(
                    chunkX & 31,
                    chunkZ & 31,
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
        }
        evictExcessRegions(key);
        return written;
    }

    /**
     * Writes a chunk directly into `.lca` CoW storage using Unified LCA Mode A passthrough —
     * splicing pre-encoded {@link LcaModeABlockSection} payloads and GPU/in-memory
     * {@code BLOCK_MODE_FLAT_BITPACK} ({@code mode 10}) byte arrays directly into a single pooled
     * staging buffer with zero CPU Hilbert/Roaring/Gray-code re-encoding and zero
     * {@code ByteArrayOutputStream} allocation ({@code anvil-api-ADR-003}, {@code REQ-RTP-S-004}).
     */
    public int writeFreshChunkDirectModeA(
            int chunkX,
            int chunkZ,
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
        return writeFreshChunkDirectModeA(
                chunkX, chunkZ, dataVersion, lastUpdate, inhabitedTime, statusName,
                sectionBlockPalettes, sectionPreEncodedModeA, sectionPreEncodedFlatBytes,
                sectionPreEncodedFlatBits, null, null, null,
                sectionBlockData, sectionBiomePalettes, sectionBiomeData,
                sectionSkyLight, sectionBlockLight, residualMetadataTailBytes);
    }

    public int writeFreshChunkDirectModeA(
            int chunkX,
            int chunkZ,
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
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        int written;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, true);
            written = archive.saveFreshChunkDirectModeA(
                    chunkX & 31,
                    chunkZ & 31,
                    dataVersion,
                    lastUpdate,
                    inhabitedTime,
                    statusName,
                    sectionBlockPalettes,
                    sectionPreEncodedModeA,
                    sectionPreEncodedFlatBytes,
                    sectionPreEncodedFlatBits,
                    sectionPreResolvedDictIds,
                    sectionPrecomputedOctreeMasks,
                    sectionHasPrecomputedOctreeMask,
                    sectionBlockData,
                    sectionBiomePalettes,
                    sectionBiomeData,
                    sectionSkyLight,
                    sectionBlockLight,
                    residualMetadataTailBytes
            );
        }
        evictExcessRegions(key);
        return written;
    }

    /**
     * Pre-registers a palette of canonical block-state strings in the target region's Layer 2
     * dictionary and returns their deterministic global dictionary IDs for GPU/DMA LUT binding.
     */
    public int[] preRegisterBlockStates(int chunkX, int chunkZ, String[] canonicalBlockStates) throws IOException {
        if (canonicalBlockStates == null || canonicalBlockStates.length == 0) {
            return new int[0];
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        int[] ids;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, true);
            ids = archive.preRegisterBlockStates(canonicalBlockStates);
        }
        evictExcessRegions(key);
        return ids;
    }

    /**
     * Writes an uncompressed Mojang binary NBT root `TAG_Compound` byte array at world chunk coordinates `(chunkX, chunkZ)`.
     */
    public int writeChunkNbtBytes(int chunkX, int chunkZ, byte[] uncompressedNbtBytes) throws IOException {
        if (uncompressedNbtBytes == null || uncompressedNbtBytes.length == 0) {
            removeChunk(chunkX, chunkZ);
            return 0;
        }
        return writeChunkNbtBytes(chunkX, chunkZ, uncompressedNbtBytes, 0, uncompressedNbtBytes.length);
    }

    /**
     * Writes an uncompressed Mojang binary NBT root `TAG_Compound` byte slice at world chunk coordinates `(chunkX, chunkZ)`.
     */
    public int writeChunkNbtBytes(
            int chunkX, int chunkZ, byte[] uncompressedNbtBytes, int offset, int length
    ) throws IOException {
        if (uncompressedNbtBytes == null || length <= 0) {
            removeChunk(chunkX, chunkZ);
            return 0;
        }
        if (containsDirectCoWCommittedMarker(uncompressedNbtBytes, offset, length)) {
            return 0;
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        int written;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, true);
            written = archive.saveChunkNbtBytes(chunkX & 31, chunkZ & 31, uncompressedNbtBytes, offset, length);
        }
        evictExcessRegions(key);
        return written;
    }

    /**
     * Writes an uncompressed Mojang binary NBT root `TAG_Compound` {@link ByteBuffer} slice at world
     * chunk coordinates `(chunkX, chunkZ)` without mutating the caller's buffer position/limit
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public int writeChunkNbtBuffer(int chunkX, int chunkZ, ByteBuffer uncompressedNbt) throws IOException {
        if (uncompressedNbt == null || !uncompressedNbt.hasRemaining()) {
            removeChunk(chunkX, chunkZ);
            return 0;
        }
        if (containsDirectCoWCommittedMarker(uncompressedNbt)) {
            return 0;
        }
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        int written;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, true);
            written = archive.saveChunkNbtBuffer(chunkX & 31, chunkZ & 31, uncompressedNbt.duplicate());
        }
        evictExcessRegions(key);
        return written;
    }

    /**
     * Parses `uncompressedNbtBytes` in a single streaming pass ({@link LcaStreamingNbtEncoder}) and
     * encodes the `.lca` `ChunkFrame` slab for region `(regionX, regionZ)` without mutating the
     * region pointer directory or slab allocator. Designed to run inside `RegionFileVersion$StreamWrapper`
     * on `C2ME`'s `GlobalExecutors.prioritizedScheduler` worker pool.
     */
    public byte[] encodeChunkNbtForRegionSlab(
            int regionX, int regionZ, byte[] uncompressedNbtBytes
    ) throws IOException {
        if (uncompressedNbtBytes == null || uncompressedNbtBytes.length == 0) {
            return new byte[0];
        }
        return encodeChunkNbtForRegionSlab(regionX, regionZ, uncompressedNbtBytes, 0, uncompressedNbtBytes.length);
    }

    /**
     * Slice overload of {@link #encodeChunkNbtForRegionSlab(int, int, byte[])} avoiding buffer copies
     * and intermediate {@code Map<String, Object>} trees via {@link LcaStreamingNbtEncoder}.
     */
    public byte[] encodeChunkNbtForRegionSlab(
            int regionX, int regionZ, byte[] uncompressedNbtBytes, int offset, int length
    ) throws IOException {
        if (uncompressedNbtBytes == null || length <= 0) {
            return new byte[0];
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return LcaStreamingNbtEncoder.encodeChunkSlab(archive, regionX, regionZ, uncompressedNbtBytes, offset, length);
    }

    /**
     * Zero-copy {@link ByteBuffer} overload of {@link #encodeChunkNbtForRegionSlab(int, int, byte[])}
     * visiting uncompressed Mojang binary NBT directly via {@link LcaStreamingNbtEncoder}
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public byte[] encodeChunkNbtForRegionSlab(
            int regionX, int regionZ, ByteBuffer uncompressedNbt
    ) throws IOException {
        if (uncompressedNbt == null || !uncompressedNbt.hasRemaining()) {
            return new byte[0];
        }
        LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegion(regionX, regionZ, true);
        return LcaStreamingNbtEncoder.encodeChunkSlab(archive, regionX, regionZ, uncompressedNbt);
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
        long key = packRegionKey(regionX, regionZ);
        int written;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, true);
            written = archive.commitPreEncodedChunkCoW(chunkX & 31, chunkZ & 31, preEncodedSlabBytes);
        }
        evictExcessRegions(key);
        return written;
    }

    /**
     * {@link ByteBuffer} overload of {@link #commitPreEncodedChunkSlab(int, int, byte[])} that reads
     * without mutating the caller's buffer position/limit (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public int commitPreEncodedChunkSlab(
            int chunkX, int chunkZ, ByteBuffer preEncodedSlab
    ) throws IOException {
        if (preEncodedSlab == null || !preEncodedSlab.hasRemaining()) {
            removeChunk(chunkX, chunkZ);
            return 0;
        }
        if (preEncodedSlab.hasArray()
                && preEncodedSlab.arrayOffset() == 0
                && preEncodedSlab.position() == 0
                && preEncodedSlab.remaining() == preEncodedSlab.array().length) {
            return commitPreEncodedChunkSlab(chunkX, chunkZ, preEncodedSlab.array());
        }
        ByteBuffer dup = preEncodedSlab.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return commitPreEncodedChunkSlab(chunkX, chunkZ, bytes);
    }

    /**
     * Removes the chunk at `(chunkX, chunkZ)` if present.
     */
    public boolean removeChunk(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = packRegionKey(regionX, regionZ);
        boolean removed;
        synchronized (regionLock(key)) {
            LcaRegionWriter.LcaLiveArchive archive = getOrOpenRegionLocked(regionX, regionZ, false);
            if (archive == null) {
                return false;
            }
            removed = archive.removeChunk(chunkX & 31, chunkZ & 31);
        }
        evictExcessRegions(key);
        return removed;
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
        while (true) {
            LcaRegionWriter.LcaLiveArchive cached = regionCache.get(key);
            if (cached != null && cached.isChannelOpen()) {
                recentRegionKeys.add(key);
                return cached;
            }

            CompletableFuture<LcaRegionWriter.LcaLiveArchive> inflight = new CompletableFuture<>();
            CompletableFuture<LcaRegionWriter.LcaLiveArchive> existingFuture = openingRegions.putIfAbsent(key, inflight);
            if (existingFuture != null) {
                try {
                    LcaRegionWriter.LcaLiveArchive joined = existingFuture.join();
                    if (joined != null && joined.isChannelOpen()) {
                        recentRegionKeys.add(key);
                        return joined;
                    }
                    if (!createIfAbsent && joined == null) {
                        return null;
                    }
                    continue;
                } catch (CompletionException e) {
                    if (e.getCause() instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw e;
                }
            }

            LcaRegionWriter.LcaLiveArchive result;
            try {
                synchronized (regionLock(key)) {
                    result = getOrOpenRegionLocked(regionX, regionZ, createIfAbsent);
                }
                openingRegions.remove(key, inflight);
                inflight.complete(result);
            } catch (IOException | RuntimeException | Error e) {
                openingRegions.remove(key, inflight);
                inflight.completeExceptionally(e);
                throw e;
            } finally {
                openingRegions.remove(key, inflight);
            }
            if (result != null) {
                evictExcessRegions(key);
            }
            return result;
        }
    }

    @SuppressWarnings({"java:S2095", "resource"})
    private LcaRegionWriter.LcaLiveArchive getOrOpenRegionLocked(
            int regionX, int regionZ, boolean createIfAbsent
    ) throws IOException {
        long key = packRegionKey(regionX, regionZ);
        LcaRegionWriter.LcaLiveArchive recheck = regionCache.get(key);
        if (recheck != null) {
            if (recheck.isChannelOpen()) {
                recentRegionKeys.add(key);
                return recheck;
            }
            regionCache.remove(key, recheck);
        }

        closeReadChannel(key);
        LcaRegionWriter.LcaLiveArchive result = null;
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        if (Files.exists(lcaFile) && Files.size(lcaFile) >= LcaRegionReader.SUPERBLOCK_SIZE
                + LcaRegionReader.POINTER_DIRECTORY_SIZE + LcaRegionReader.FOOTER_SIZE) {
            boolean upgraded = LcaRegionWriter.upgradeLegacyArchiveAtomically(lcaFile, lcd2Level);
            if (upgraded) {
                invalidateRegionCacheState(regionX, regionZ);
            }
            result = LcaRegionWriter.openLiveArchive(lcaFile, lcd2Level);
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
                return null;
            }
            result = LcaRegionWriter.createEmptyLiveArchive(
                    lcaFile, regionX, regionZ, minSectionY, sectionCount, defaultDataVersion,
                    lcd2Level, storageKind, LcaRegionWriter.DEFAULT_ROOT_DICT_CAPACITY_BYTES);
        }
        knownMissingRegions.remove(key);
        closed.set(false);
        result.configureBufferedWriteBack(bufferedWriteBackEnabled, writeBackMaxChunks, writeBackMaxBytes);
        result.configureSequentialReadAhead(sequentialReadAheadEnabled, readAheadTriggerReads, readAheadMaxBytes);
        result.setDirectFlatBitPack(directFlatBitPack);
        String[] wgDict = this.worldgenBlockDictionary;
        if (wgDict != null && storageKind == LcaRegionReader.STORAGE_KIND_REGION_VOXEL) {
            result.bindWorldgenBlockDictionary(wgDict);
        }
        regionCache.put(key, result);
        // Close any read channel opened concurrently while openLiveArchive was running
        closeReadChannel(key);
        regionEvictionQueue.offer(key);
        return result;
    }

    private void evictExcessRegions(long keepKey) throws IOException {
        int maxScans = (maxOpenWriteArchives << 1) + 8;
        int scans = 0;
        while (regionCache.size() > maxOpenWriteArchives && scans++ < maxScans) {
            Long eldestKey = regionEvictionQueue.poll();
            if (eldestKey == null || eldestKey.longValue() == keepKey) {
                if (eldestKey != null) {
                    regionEvictionQueue.offer(eldestKey);
                }
                break;
            }
            if (!regionCache.containsKey(eldestKey)) {
                recentRegionKeys.remove(eldestKey);
                continue;
            }
            if (recentRegionKeys.remove(eldestKey)) {
                regionEvictionQueue.offer(eldestKey);
                continue;
            }
            synchronized (regionLock(eldestKey)) {
                LcaRegionWriter.LcaLiveArchive evicted = regionCache.get(eldestKey);
                if (evicted != null) {
                    try {
                        closeArchiveQuietlyWithCompaction(evicted);
                    } finally {
                        regionCache.remove(eldestKey, evicted);
                    }
                }
            }
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
                legacyReader, srcBytes, regionX, regionZ, lcd2Level, storageKind);

        Files.createDirectories(storageDirectory);
        Path tmpFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca.tmp");
        LcaRegionWriter.writeBytesAtomicallyDurable(targetLcaFile, tmpFile, lcaBytes);

        if (backupMigratedLegacyFiles) {
            Path bakFile = storageDirectory.resolve(legacyFile.getFileName().toString() + ".bak");
            if (!Files.exists(bakFile)) {
                Files.move(legacyFile, bakFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return LcaRegionWriter.openLiveArchive(targetLcaFile, lcd2Level);
    }

    private void closeArchiveQuietlyWithCompaction(LcaRegionWriter.LcaLiveArchive archive) throws IOException {
        closeArchiveWithCompaction(archive, false);
    }

    private void closeArchiveWithCompaction(
            LcaRegionWriter.LcaLiveArchive archive, boolean forceDiskSync
    ) throws IOException {
        if (archive == null) {
            return;
        }
        try {
            if (compactFragmentationThreshold > 0.0
                    && compactFragmentationThreshold < 1.0) {
                archive.compactIfFragmented(compactFragmentationThreshold);
            }
        } finally {
            if (forceDiskSync) {
                archive.close();
            } else {
                archive.closeWithoutForce();
            }
        }
    }

    /**
     * Clears any negative missing-region cache entry and cached header state for `(regionX, regionZ)`
     * after external file creation or replacement.
     */
    public void invalidateRegionCacheState(int regionX, int regionZ) {
        long key = packRegionKey(regionX, regionZ);
        knownMissingRegions.remove(key);
        Path lcaFile = storageDirectory.resolve("r." + regionX + "." + regionZ + ".lca");
        LcaRegionReader.invalidateFileCache(lcaFile);
        try {
            closeReadChannel(key);
        } catch (IOException closeEx) {
            java.util.logging.Logger.getLogger(LcaRegionFileStorage.class.getName()).log(
                    java.util.logging.Level.FINE,
                    "Failed to close cached read channel during region invalidation (" + regionX + "," + regionZ + ")",
                    closeEx);
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
        synchronized (regionLock(key)) {
            closeReadChannel(key);
            recentRegionKeys.remove(key);
            LcaRegionWriter.LcaLiveArchive archive = regionCache.get(key);
            if (archive != null) {
                try {
                    closeArchiveWithCompaction(archive, forceSyncOnClose);
                } finally {
                    regionCache.remove(key, archive);
                }
            }
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
        List<FileChannel> readChannels = new ArrayList<>(readChannelCache.values());
        readChannelCache.clear();
        recentReadChannelKeys.clear();
        readChannelEvictionQueue.clear();
        readChannelColdMisses.clear();
        readAheadRegionBytes.clear();
        readAheadEvictionQueue.clear();
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
        List<Long> keys = new ArrayList<>(regionCache.keySet());
        for (Long key : keys) {
            synchronized (regionLock(key)) {
                recentRegionKeys.remove(key);
                LcaRegionWriter.LcaLiveArchive archive = regionCache.get(key);
                if (archive != null) {
                    try {
                        closeArchiveWithCompaction(archive, forceSyncOnClose);
                    } catch (IOException e) {
                        if (firstEx == null) firstEx = e;
                    } finally {
                        regionCache.remove(key, archive);
                    }
                }
            }
        }
        regionEvictionQueue.clear();
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

    private record ChunkPosExtractor(
            java.lang.reflect.Field xField,
            java.lang.reflect.Field zField,
            Method xMethod,
            Method zMethod
    ) {
        int[] extract(Object chunkPosObj, Class<?> cls) {
            try {
                if (xField != null && zField != null) {
                    return new int[] {xField.getInt(chunkPosObj), zField.getInt(chunkPosObj)};
                }
                if (xMethod != null && zMethod != null) {
                    return new int[] {
                            ((Number) xMethod.invoke(chunkPosObj)).intValue(),
                            ((Number) zMethod.invoke(chunkPosObj)).intValue()
                    };
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(
                        "Cannot extract (x, z) from ChunkPos object: " + cls.getName(), e);
            }
            throw new IllegalArgumentException(
                    "Cannot extract (x, z) from ChunkPos object: " + cls.getName());
        }
    }

    private static final ClassValue<ChunkPosExtractor> CHUNK_POS_EXTRACTORS = new ClassValue<>() {
        @Override
        protected ChunkPosExtractor computeValue(Class<?> cls) {
            java.lang.reflect.Field xField = null;
            java.lang.reflect.Field zField = null;
            for (java.lang.reflect.Field f : cls.getFields()) {
                String name = f.getName();
                if ("x".equals(name)) {
                    f.setAccessible(true);
                    xField = f;
                } else if ("z".equals(name)) {
                    f.setAccessible(true);
                    zField = f;
                }
            }
            if (xField != null && zField != null) {
                return new ChunkPosExtractor(xField, zField, null, null);
            }
            Method xMethod = null;
            Method zMethod = null;
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                String name = m.getName();
                if ("x".equals(name)) {
                    m.setAccessible(true);
                    xMethod = m;
                } else if ("z".equals(name)) {
                    m.setAccessible(true);
                    zMethod = m;
                }
            }
            return new ChunkPosExtractor(null, null, xMethod, zMethod);
        }
    };

    public static int[] extractChunkPosCoords(Object chunkPosObj) {
        if (chunkPosObj == null) {
            throw new IllegalArgumentException("ChunkPos argument must not be null");
        }
        Class<?> cls = chunkPosObj.getClass();
        return CHUNK_POS_EXTRACTORS.get(cls).extract(chunkPosObj, cls);
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
                boolean modeBLcd2,
                boolean autoMigrateLegacy
        ) {
            this(
                    dimensionDirectory,
                    minSectionY,
                    sectionCount,
                    defaultDataVersion,
                    modeBLcd2 ? 1 : 0,
                    autoMigrateLegacy);
        }

        public LcaWorldStorageSession(
                Path dimensionDirectory,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                int lcd2Level,
                boolean autoMigrateLegacy
        ) {
            this.dimensionDirectory = dimensionDirectory;
            this.voxelStorage = new LcaRegionFileStorage(
                    dimensionDirectory.resolve("region"),
                    LcaRegionReader.STORAGE_KIND_REGION_VOXEL,
                    minSectionY,
                    sectionCount,
                    defaultDataVersion,
                    lcd2Level,
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
                    lcd2Level,
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
                    lcd2Level,
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

package io.github.dailystruggle.anvil.stream;

import io.github.dailystruggle.anvil.AnvilChunkView;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.BatchChunkEntry;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.ChunkDigestEntry;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.ChunkDigestPayload;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryCleanAck;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryFullChunk;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.EntryPatchDelta;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.LcaRegionDictSyncFrame;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.SectionWireSlice;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.StreamChunkBatchPayload;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.StreamChunkDeltaEntry;
import io.github.dailystruggle.anvil.stream.LcaRemoteStreamCodec.StreamDeltaUpdatePayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-tier byte-budgeted LRU slab pool (`L1` in-memory canonical `.lca` chunk slabs + optional
 * `L2` disk `.lca` archive ring) for off-host `LeafMap` and `LeafAnvil` stream receivers
 * (`leafmap-ADR-004`, `leafanvil-ADR-003`, `anvil-api-ADR-003`).
 *
 * <p>Namespaces all pooled chunks, `LOD-3` (`10,990 B`) horizon headers, and per-region
 * `BlockState`/`Biome` palette dictionaries by `(serverId, world, regionX, regionZ)` so vanilla and
 * modded servers can stream concurrently into the same collector without palette collisions.</p>
 *
 * @governedBy leafmap-ADR-004
 * @governedBy leafanvil-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-MAP-STREAM-001
 * @trace REQ-MAP-STREAM-002
 * @trace REQ-MAP-LRU-001
 * @trace REQ-MAP-LRU-002
 * @trace REQ-MAP-MULTI-001
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class LcaBoundedSlabPool implements AutoCloseable {

    public static final long DEFAULT_MAX_MEMORY_BYTES = 256L * 1024L * 1024L;
    public static final long DEFAULT_MAX_DISK_BYTES = 1024L * 1024L * 1024L;
    public static final int DEFAULT_LOD3_HEADER_BYTES = 10_990;
    private static final int DISK_POOL_MAGIC = 0x4C434150; // "LCAP"

    /**
     * Configuration for the two-tier bounded `.lca` LRU slab pool.
     */
    public record PoolConfig(
            long maxMemoryCacheBytes,
            long maxDiskCacheBytes,
            Path diskCacheRoot,
            boolean pinSpawnRegions,
            boolean pinLod3Headers
    ) {
        public PoolConfig {
            if (maxMemoryCacheBytes <= 0) {
                throw new IllegalArgumentException("maxMemoryCacheBytes must be > 0: " + maxMemoryCacheBytes);
            }
            if (maxDiskCacheBytes < 0) {
                throw new IllegalArgumentException("maxDiskCacheBytes must be >= 0: " + maxDiskCacheBytes);
            }
        }

        public static PoolConfig defaultInMemory(long maxMemoryBytes) {
            return new PoolConfig(maxMemoryBytes, 0L, null, true, true);
        }
    }

    /**
     * Region coordinate key namespaced by `(serverId, world, regionX, regionZ)`.
     */
    public record RegionCoordKey(
            String serverId,
            String world,
            int regionX,
            int regionZ
    ) {
        public RegionCoordKey {
            serverId = normalizeServerId(serverId);
            world = normalizeWorldName(world);
        }

        public String compositeWorldKey() {
            return "local".equalsIgnoreCase(serverId) ? world : serverId + ":" + world;
        }
    }

    /**
     * Chunk coordinate key namespaced by `(serverId, world, chunkX, chunkZ)`.
     */
    public record ChunkCoordKey(
            String serverId,
            String world,
            int chunkX,
            int chunkZ
    ) {
        public ChunkCoordKey {
            serverId = normalizeServerId(serverId);
            world = normalizeWorldName(world);
        }

        public RegionCoordKey toRegionKey() {
            return new RegionCoordKey(serverId, world, chunkX >> 5, chunkZ >> 5);
        }

        public int localChunkIndex10() {
            return LcaRemoteStreamCodec.toLocalChunkIndex10(chunkX, chunkZ);
        }
    }

    /**
     * Cached canonical `.lca` chunk slab entry with `lastUpdateTick`, `secDedSyndrome64`, and
     * pinning status.
     */
    public record PooledChunkSlab(
            String serverId,
            String world,
            int chunkX,
            int chunkZ,
            long lastUpdateTick,
            long secDedSyndrome64,
            byte[] slabBytes,
            boolean pinned
    ) {
        public PooledChunkSlab {
            serverId = normalizeServerId(serverId);
            world = normalizeWorldName(world);
            Objects.requireNonNull(slabBytes, "slabBytes");
            slabBytes = slabBytes.clone();
        }

        public int byteSize() {
            return slabBytes.length;
        }
    }

    /**
     * Outcome status for a single streamed chunk entry.
     */
    public enum StreamIngestStatus {
        APPLIED_CLEAN_ACK,
        APPLIED_PATCH_DELTA,
        APPLIED_FULL_CHUNK,
        NEEDS_FULL_CHUNK_REFILL,
        SYNDROME_MISMATCH
    }

    /**
     * Aggregated result of ingesting a `StreamChunkBatchPayload` or `StreamDeltaUpdatePayload`.
     */
    public record IngestBatchResult(
            RegionCoordKey regionKey,
            int appliedCleanAcks,
            int appliedPatchDeltas,
            int appliedFullChunks,
            List<Integer> refillLocalChunkIndices10,
            long memoryBytesUsed,
            long diskBytesUsed
    ) {
        public IngestBatchResult {
            Objects.requireNonNull(regionKey, "regionKey");
            refillLocalChunkIndices10 = refillLocalChunkIndices10 == null
                    ? List.of()
                    : List.copyOf(refillLocalChunkIndices10);
        }

        public boolean requiresRefill() {
            return !refillLocalChunkIndices10.isEmpty();
        }
    }

    /**
     * Snapshot of pool telemetry for `/leafmap pool` and HTTP diagnostics.
     */
    public record PoolTelemetrySnapshot(
            long memoryBytesUsed,
            long maxMemoryBytes,
            long diskBytesUsed,
            long maxDiskBytes,
            int l1ChunkCount,
            int l2ChunkCount,
            int pinnedChunkCount,
            int pinnedLod3HeaderCount,
            long l1HitCount,
            long l2HitCount,
            long missCount,
            long l1EvictionCount,
            long l2EvictionCount,
            long syndromeMismatchCount,
            Set<String> activeServers,
            Set<String> activeCompositeWorlds
    ) {
    }

    private final PoolConfig config;
    private final LinkedHashMap<ChunkCoordKey, PooledChunkSlab> l1Map =
            new LinkedHashMap<>(256, 0.75f, true);
    private final LinkedHashMap<ChunkCoordKey, Long> l2IndexBytes =
            new LinkedHashMap<>(256, 0.75f, true);
    private final Map<RegionCoordKey, LcaRegionDictSyncFrame> regionDictionaries =
            new ConcurrentHashMap<>();
    private final Map<RegionCoordKey, byte[]> pinnedLod3Headers =
            new ConcurrentHashMap<>();
    private final Set<RegionCoordKey> pinnedRegions =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private long memoryBytesUsed = 0L;
    private long diskBytesUsed = 0L;
    private final AtomicLong l1HitCount = new AtomicLong();
    private final AtomicLong l2HitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong l1EvictionCount = new AtomicLong();
    private final AtomicLong l2EvictionCount = new AtomicLong();
    private final AtomicLong syndromeMismatchCount = new AtomicLong();

    public LcaBoundedSlabPool(PoolConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public PoolConfig config() {
        return config;
    }

    public static String normalizeServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return "local";
        }
        return serverId.trim();
    }

    public static String normalizeWorldName(String world) {
        if (world == null || world.isBlank()) {
            return "world";
        }
        String trimmed = world.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon + 1 < trimmed.length()) {
            String prefix = trimmed.substring(0, colon);
            if (!"minecraft".equalsIgnoreCase(prefix)) {
                return trimmed.substring(colon + 1).trim();
            }
        }
        return trimmed;
    }

    public static String[] splitCompositeWorldKey(String defaultServerId, String rawWorldOrComposite) {
        String defSrv = normalizeServerId(defaultServerId);
        if (rawWorldOrComposite == null || rawWorldOrComposite.isBlank()) {
            return new String[]{defSrv, "world"};
        }
        String trimmed = rawWorldOrComposite.trim();
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon + 1 < trimmed.length()) {
            String prefix = trimmed.substring(0, colon).trim();
            String rest = trimmed.substring(colon + 1).trim();
            if (!"minecraft".equalsIgnoreCase(prefix)) {
                return new String[]{normalizeServerId(prefix), rest.isEmpty() ? "world" : rest};
            }
        }
        return new String[]{defSrv, trimmed};
    }

    /**
     * Registers or updates the `BlockState` and `Biome` palette dictionary for a region.
     */
    public void putRegionDictionary(LcaRegionDictSyncFrame dictFrame) {
        Objects.requireNonNull(dictFrame, "dictFrame");
        RegionCoordKey key = new RegionCoordKey(
                dictFrame.serverId(), dictFrame.world(), dictFrame.regionX(), dictFrame.regionZ());
        regionDictionaries.put(key, dictFrame);
    }

    public Optional<LcaRegionDictSyncFrame> getRegionDictionary(
            String serverId, String world, int regionX, int regionZ) {
        return Optional.ofNullable(regionDictionaries.get(
                new RegionCoordKey(serverId, world, regionX, regionZ)));
    }

    /**
     * Pins or unpins a region (e.g. spawn region `(0, 0)`) and optionally caches its `LOD-3`
     * (`10,990 B`) horizon header in memory (`REQ-MAP-LRU-002`).
     */
    public synchronized void pinRegion(
            String serverId,
            String world,
            int regionX,
            int regionZ,
            byte[] lod3HeaderBytes
    ) {
        RegionCoordKey regionKey = new RegionCoordKey(serverId, world, regionX, regionZ);
        pinnedRegions.add(regionKey);
        if (config.pinLod3Headers()) {
            byte[] header = (lod3HeaderBytes != null && lod3HeaderBytes.length > 0)
                    ? lod3HeaderBytes.clone()
                    : buildDeterministicLod3HorizonHeader(regionKey);
            byte[] prev = pinnedLod3Headers.put(regionKey, header);
            if (prev != null) {
                memoryBytesUsed = Math.max(0L, memoryBytesUsed - prev.length);
            }
            memoryBytesUsed += header.length;
            evictL1IfNeeded(0L);
        }
    }

    public Optional<byte[]> getPinnedLod3Header(
            String serverId, String world, int regionX, int regionZ) {
        RegionCoordKey key = new RegionCoordKey(serverId, world, regionX, regionZ);
        byte[] header = pinnedLod3Headers.get(key);
        return header == null ? Optional.empty() : Optional.of(header.clone());
    }

    /**
     * Ingests a `StreamChunkBatchPayload` (`LCAB`) into the bounded LRU pool.
     */
    public synchronized IngestBatchResult ingestStreamBatch(
            String serverId,
            StreamChunkBatchPayload batch,
            boolean pinnedRegion
    ) {
        Objects.requireNonNull(batch, "batch");
        List<StreamChunkDeltaEntry> entries = new ArrayList<>(batch.entries().size());
        for (BatchChunkEntry entry : batch.entries()) {
            int localIdx = entry.localChunkIndex10();
            int cx = (batch.regionX() << 5) | (localIdx & 31);
            int cz = (batch.regionZ() << 5) | ((localIdx >>> 5) & 31);
            entries.add(new StreamChunkDeltaEntry(cx, cz, 0L, entry));
        }
        StreamDeltaUpdatePayload update = new StreamDeltaUpdatePayload(
                normalizeServerId(serverId),
                batch.world(),
                batch.regionX(),
                batch.regionZ(),
                1L,
                entries
        );
        return ingestDeltaUpdate(update, pinnedRegion);
    }

    /**
     * Ingests a `StreamDeltaUpdatePayload` (`LCAS` or normalized `LCAB`) into the bounded LRU pool,
     * verifying `SEC-DED-64` syndromes and reporting any chunks that require a full-chunk refill
     * (`REQ-MAP-STREAM-001`, `REQ-MAP-STREAM-002`, `S-004`).
     */
    public synchronized IngestBatchResult ingestDeltaUpdate(
            StreamDeltaUpdatePayload update,
            boolean pinnedRegion
    ) {
        Objects.requireNonNull(update, "update");
        String srv = normalizeServerId(update.sourceServerId());
        String world = normalizeWorldName(update.dimension());
        RegionCoordKey regionKey = new RegionCoordKey(srv, world, update.regionX(), update.regionZ());

        boolean autoPinSpawn = config.pinSpawnRegions()
                && (update.regionX() == 0 && update.regionZ() == 0);
        boolean effectivePin = pinnedRegion || autoPinSpawn || pinnedRegions.contains(regionKey);
        if (effectivePin) {
            pinnedRegions.add(regionKey);
            if (config.pinLod3Headers() && !pinnedLod3Headers.containsKey(regionKey)) {
                byte[] hdr = buildDeterministicLod3HorizonHeader(regionKey);
                pinnedLod3Headers.put(regionKey, hdr);
                memoryBytesUsed += hdr.length;
            }
        }

        int cleanAcks = 0;
        int patchDeltas = 0;
        int fullChunks = 0;
        List<Integer> refills = new ArrayList<>();

        for (StreamChunkDeltaEntry delta : update.entries()) {
            StreamIngestStatus status = applySingleEntryInternal(
                    srv, world, update.regionX(), update.regionZ(), delta, effectivePin);
            switch (status) {
                case APPLIED_CLEAN_ACK -> cleanAcks++;
                case APPLIED_PATCH_DELTA -> patchDeltas++;
                case APPLIED_FULL_CHUNK -> fullChunks++;
                case NEEDS_FULL_CHUNK_REFILL, SYNDROME_MISMATCH ->
                        refills.add(delta.localChunkIndex10());
            }
        }

        return new IngestBatchResult(
                regionKey,
                cleanAcks,
                patchDeltas,
                fullChunks,
                refills,
                memoryBytesUsed,
                diskBytesUsed
        );
    }

    private StreamIngestStatus applySingleEntryInternal(
            String serverId,
            String world,
            int regionX,
            int regionZ,
            StreamChunkDeltaEntry deltaEntry,
            boolean pinned
    ) {
        BatchChunkEntry chunkEntry = deltaEntry.chunkEntry();
        if (chunkEntry instanceof EntryFullChunk fullChunk) {
            byte[] canonicalSlab = fullChunk.toCanonicalSlabBytes();
            if (!LcaRemoteStreamCodec.verifySecDedSyndrome64(
                    fullChunk.lastUpdateTick(), canonicalSlab, fullChunk.secDedSyndrome64())) {
                syndromeMismatchCount.incrementAndGet();
                return StreamIngestStatus.SYNDROME_MISMATCH;
            }
            putSlabInternal(new PooledChunkSlab(
                    serverId,
                    world,
                    fullChunk.chunkX(),
                    fullChunk.chunkZ(),
                    fullChunk.lastUpdateTick(),
                    fullChunk.secDedSyndrome64(),
                    canonicalSlab,
                    pinned
            ));
            return StreamIngestStatus.APPLIED_FULL_CHUNK;
        }

        int localIdx = chunkEntry.localChunkIndex10();
        int chunkX = (regionX << 5) | (localIdx & 31);
        int chunkZ = (regionZ << 5) | ((localIdx >>> 5) & 31);
        Optional<PooledChunkSlab> existingOpt = getChunkSlabEntry(serverId, world, chunkX, chunkZ);
        if (existingOpt.isEmpty()) {
            return StreamIngestStatus.NEEDS_FULL_CHUNK_REFILL;
        }

        PooledChunkSlab existing = existingOpt.get();
        if (!LcaRemoteStreamCodec.verifySecDedSyndrome64(
                existing.lastUpdateTick(), existing.slabBytes(), existing.secDedSyndrome64())) {
            evictChunk(serverId, world, chunkX, chunkZ);
            syndromeMismatchCount.incrementAndGet();
            return StreamIngestStatus.SYNDROME_MISMATCH;
        }

        if (deltaEntry.previousBaselineSecDed64() != 0L
                && deltaEntry.previousBaselineSecDed64() != existing.secDedSyndrome64()) {
            syndromeMismatchCount.incrementAndGet();
            return StreamIngestStatus.SYNDROME_MISMATCH;
        }

        if (chunkEntry instanceof EntryCleanAck) {
            return StreamIngestStatus.APPLIED_CLEAN_ACK;
        }

        if (chunkEntry instanceof EntryPatchDelta patch) {
            EntryFullChunk baseChunk = LcaRemoteStreamCodec.deserializeCanonicalChunkSlab(
                    existing.slabBytes(),
                    existing.lastUpdateTick(),
                    existing.secDedSyndrome64()
            );
            List<SectionWireSlice> mergedSections = new ArrayList<>(baseChunk.sections());
            int mask = patch.dirtySectionBitmask32();
            int modCursor = 0;
            while (mask != 0) {
                int sectionIdx = Integer.numberOfTrailingZeros(mask);
                mask &= (mask - 1);
                if (sectionIdx >= mergedSections.size() || modCursor >= patch.modifiedSections().size()) {
                    return StreamIngestStatus.NEEDS_FULL_CHUNK_REFILL;
                }
                mergedSections.set(sectionIdx, patch.modifiedSections().get(modCursor++));
            }
            byte[] mergedSlab = LcaRemoteStreamCodec.serializeCanonicalChunkSlab(
                    baseChunk.chunkX(),
                    baseChunk.chunkZ(),
                    mergedSections,
                    baseChunk.heightmapDeltaBytes()
            );
            if (!LcaRemoteStreamCodec.verifySecDedSyndrome64(
                    patch.newLastUpdateTick(), mergedSlab, patch.newSecDedSyndrome64())) {
                syndromeMismatchCount.incrementAndGet();
                return StreamIngestStatus.SYNDROME_MISMATCH;
            }
            putSlabInternal(new PooledChunkSlab(
                    serverId,
                    world,
                    baseChunk.chunkX(),
                    baseChunk.chunkZ(),
                    patch.newLastUpdateTick(),
                    patch.newSecDedSyndrome64(),
                    mergedSlab,
                    existing.pinned() || pinned
            ));
            return StreamIngestStatus.APPLIED_PATCH_DELTA;
        }

        return StreamIngestStatus.NEEDS_FULL_CHUNK_REFILL;
    }

    /**
     * Inserts a `EntryFullChunk` directly into the L1 slab cache.
     */
    public synchronized void putFullChunk(
            String serverId,
            String world,
            EntryFullChunk fullChunk,
            boolean pinned
    ) {
        Objects.requireNonNull(fullChunk, "fullChunk");
        byte[] slab = fullChunk.toCanonicalSlabBytes();
        long syn = LcaRemoteStreamCodec.computeSecDedSyndrome64(fullChunk.lastUpdateTick(), slab);
        putSlabInternal(new PooledChunkSlab(
                serverId, world, fullChunk.chunkX(), fullChunk.chunkZ(),
                fullChunk.lastUpdateTick(), syn, slab, pinned
        ));
    }

    private void putSlabInternal(PooledChunkSlab slab) {
        int entryBytes = slab.byteSize();
        if (entryBytes > config.maxMemoryCacheBytes()) {
            if (config.maxDiskCacheBytes() >= entryBytes) {
                spillToL2Disk(slab);
            }
            return;
        }
        ChunkCoordKey key = new ChunkCoordKey(slab.serverId(), slab.world(), slab.chunkX(), slab.chunkZ());
        PooledChunkSlab prev = l1Map.remove(key);
        if (prev != null) {
            memoryBytesUsed = Math.max(0L, memoryBytesUsed - prev.byteSize());
        }
        removeL2DiskEntry(key);
        evictL1IfNeeded(entryBytes);
        l1Map.put(key, slab);
        memoryBytesUsed += entryBytes;
    }

    private void evictL1IfNeeded(long incomingBytes) {
        if (memoryBytesUsed + incomingBytes <= config.maxMemoryCacheBytes()) {
            return;
        }
        Iterator<Map.Entry<ChunkCoordKey, PooledChunkSlab>> it = l1Map.entrySet().iterator();
        while (memoryBytesUsed + incomingBytes > config.maxMemoryCacheBytes() && it.hasNext()) {
            Map.Entry<ChunkCoordKey, PooledChunkSlab> eldest = it.next();
            PooledChunkSlab val = eldest.getValue();
            if (val.pinned() || pinnedRegions.contains(eldest.getKey().toRegionKey())) {
                continue;
            }
            memoryBytesUsed = Math.max(0L, memoryBytesUsed - val.byteSize());
            it.remove();
            l1EvictionCount.incrementAndGet();
            if (config.maxDiskCacheBytes() > 0 && config.diskCacheRoot() != null) {
                spillToL2Disk(val);
            }
        }
        // If still over budget because all entries are pinned, evict oldest non-spawn entry as safety valve
        if (memoryBytesUsed + incomingBytes > config.maxMemoryCacheBytes()) {
            Iterator<Map.Entry<ChunkCoordKey, PooledChunkSlab>> forceIt = l1Map.entrySet().iterator();
            while (memoryBytesUsed + incomingBytes > config.maxMemoryCacheBytes() && forceIt.hasNext()) {
                Map.Entry<ChunkCoordKey, PooledChunkSlab> eldest = forceIt.next();
                PooledChunkSlab val = eldest.getValue();
                memoryBytesUsed = Math.max(0L, memoryBytesUsed - val.byteSize());
                forceIt.remove();
                l1EvictionCount.incrementAndGet();
                if (config.maxDiskCacheBytes() > 0 && config.diskCacheRoot() != null) {
                    spillToL2Disk(val);
                }
            }
        }
    }

    private void spillToL2Disk(PooledChunkSlab slab) {
        if (config.maxDiskCacheBytes() <= 0 || config.diskCacheRoot() == null) {
            return;
        }
        ChunkCoordKey key = new ChunkCoordKey(slab.serverId(), slab.world(), slab.chunkX(), slab.chunkZ());
        Path file = resolveL2SlabFile(key);
        try {
            Files.createDirectories(file.getParent());
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32 + slab.byteSize());
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(DISK_POOL_MAGIC);
                out.writeUTF(slab.serverId());
                out.writeUTF(slab.world());
                out.writeInt(slab.chunkX());
                out.writeInt(slab.chunkZ());
                out.writeLong(slab.lastUpdateTick());
                out.writeLong(slab.secDedSyndrome64());
                out.writeBoolean(slab.pinned());
                out.writeInt(slab.slabBytes().length);
                out.write(slab.slabBytes());
            }
            byte[] diskBytes = baos.toByteArray();
            if (diskBytes.length > config.maxDiskCacheBytes()) {
                return;
            }
            evictL2IfNeeded(diskBytes.length);
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, diskBytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            Long prevLen = l2IndexBytes.remove(key);
            if (prevLen != null) {
                diskBytesUsed = Math.max(0L, diskBytesUsed - prevLen);
            }
            l2IndexBytes.put(key, (long) diskBytes.length);
            diskBytesUsed += diskBytes.length;
        } catch (IOException ignored) {
        }
    }

    private void evictL2IfNeeded(long incomingDiskBytes) {
        Iterator<Map.Entry<ChunkCoordKey, Long>> it = l2IndexBytes.entrySet().iterator();
        while (diskBytesUsed + incomingDiskBytes > config.maxDiskCacheBytes() && it.hasNext()) {
            Map.Entry<ChunkCoordKey, Long> eldest = it.next();
            ChunkCoordKey key = eldest.getKey();
            long size = eldest.getValue();
            it.remove();
            diskBytesUsed = Math.max(0L, diskBytesUsed - size);
            l2EvictionCount.incrementAndGet();
            try {
                Files.deleteIfExists(resolveL2SlabFile(key));
            } catch (IOException ignored) {
            }
        }
    }

    private void removeL2DiskEntry(ChunkCoordKey key) {
        Long removed = l2IndexBytes.remove(key);
        if (removed != null) {
            diskBytesUsed = Math.max(0L, diskBytesUsed - removed);
            try {
                Files.deleteIfExists(resolveL2SlabFile(key));
            } catch (IOException ignored) {
            }
        }
    }

    private Path resolveL2SlabFile(ChunkCoordKey key) {
        String safeServer = sanitizePathSegment(key.serverId());
        String safeWorld = sanitizePathSegment(key.world());
        int rx = key.chunkX() >> 5;
        int rz = key.chunkZ() >> 5;
        return config.diskCacheRoot()
                .resolve(safeServer)
                .resolve(safeWorld)
                .resolve("r." + rx + "." + rz)
                .resolve("c." + key.chunkX() + "." + key.chunkZ() + ".lca");
    }

    private static String sanitizePathSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "default";
        }
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Resolves a pooled chunk slab from L1 RAM or L2 Disk `.lca` spillover, promoting L2 hits
     * back into L1 RAM.
     */
    public synchronized Optional<PooledChunkSlab> getChunkSlabEntry(
            String serverId, String world, int chunkX, int chunkZ) {
        ChunkCoordKey key = new ChunkCoordKey(serverId, world, chunkX, chunkZ);
        PooledChunkSlab l1 = l1Map.get(key);
        if (l1 != null) {
            l1HitCount.incrementAndGet();
            return Optional.of(l1);
        }
        Long l2Bytes = l2IndexBytes.get(key);
        if (l2Bytes != null && config.diskCacheRoot() != null) {
            Path file = resolveL2SlabFile(key);
            if (Files.isRegularFile(file)) {
                try {
                    byte[] raw = Files.readAllBytes(file);
                    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
                        int magic = in.readInt();
                        if (magic == DISK_POOL_MAGIC) {
                            String srv = in.readUTF();
                            String w = in.readUTF();
                            int cx = in.readInt();
                            int cz = in.readInt();
                            long tick = in.readLong();
                            long syn = in.readLong();
                            boolean pinned = in.readBoolean();
                            int len = in.readInt();
                            byte[] slab = new byte[len];
                            in.readFully(slab);
                            if (LcaRemoteStreamCodec.verifySecDedSyndrome64(tick, slab, syn)) {
                                PooledChunkSlab reloaded = new PooledChunkSlab(
                                        srv, w, cx, cz, tick, syn, slab, pinned);
                                l2HitCount.incrementAndGet();
                                putSlabInternal(reloaded);
                                return Optional.of(reloaded);
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
            removeL2DiskEntry(key);
        }
        missCount.incrementAndGet();
        return Optional.empty();
    }

    public synchronized Optional<byte[]> getChunkSlabBytes(
            String serverId, String world, int chunkX, int chunkZ) {
        return getChunkSlabEntry(serverId, world, chunkX, chunkZ)
                .map(PooledChunkSlab::slabBytes);
    }

    /**
     * Returns a compact binary snapshot of all pooled chunks in `(serverId, world, regionX, regionZ)`
     * or {@link Optional#empty()} if no chunks in that region are pooled.
     */
    public synchronized Optional<byte[]> getRegionLcaBytes(
            String serverId, String world, int regionX, int regionZ) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        if (!hasAnyChunkInRegion(srv, w, regionX, regionZ)) {
            return Optional.empty();
        }
        int baseCx = regionX << 5;
        int baseCz = regionZ << 5;
        List<BatchChunkEntry> entries = new ArrayList<>();
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                ChunkCoordKey cKey = new ChunkCoordKey(srv, w, baseCx + cx, baseCz + cz);
                if (!l1Map.containsKey(cKey) && !l2IndexBytes.containsKey(cKey)) {
                    continue;
                }
                getFullChunk(srv, w, baseCx + cx, baseCz + cz).ifPresent(entries::add);
            }
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        List<BatchChunkEntry> capped = entries.size() > LcaRemoteStreamCodec.MAX_BATCH_CHUNKS
                ? entries.subList(0, LcaRemoteStreamCodec.MAX_BATCH_CHUNKS)
                : entries;
        return Optional.of(LcaRemoteStreamCodec.serializeRawChunkBatch(regionX, regionZ, capped, true));
    }

    public synchronized Optional<EntryFullChunk> getFullChunk(
            String serverId, String world, int chunkX, int chunkZ) {
        return getChunkSlabEntry(serverId, world, chunkX, chunkZ)
                .map(entry -> LcaRemoteStreamCodec.deserializeCanonicalChunkSlab(
                        entry.slabBytes(), entry.lastUpdateTick(), entry.secDedSyndrome64()));
    }

    /**
     * Resolves a pooled chunk as an {@link AnvilChunkView} using the region's `BlockState` and
     * `Biome` dictionary for `(serverId, world, chunkX >> 5, chunkZ >> 5)`.
     */
    public synchronized Optional<AnvilChunkView> getChunkView(
            String serverId, String world, int chunkX, int chunkZ, int minSectionY) {
        Optional<EntryFullChunk> fullOpt = getFullChunk(serverId, world, chunkX, chunkZ);
        if (fullOpt.isEmpty()) {
            return Optional.empty();
        }
        EntryFullChunk full = fullOpt.get();
        RegionCoordKey rKey = new RegionCoordKey(serverId, world, chunkX >> 5, chunkZ >> 5);
        LcaRegionDictSyncFrame dict = regionDictionaries.get(rKey);
        List<String> blockNames = dict != null ? dict.dictIdToBlockStateName() : List.of();
        List<String> biomeNames = dict != null ? dict.dictIdToBiomeName() : List.of();
        return Optional.of(LcaRemoteStreamCodec.toAnvilChunkView(
                full, minSectionY, blockNames, biomeNames));
    }

    /**
     * Returns all pooled chunks in `(serverId, world, regionX, regionZ)` as a `32x32` grid of
     * {@link AnvilChunkView}s (or `null` entries for unpooled chunks).
     */
    public synchronized AnvilChunkView[][] getRegionChunkViews(
            String serverId, String world, int regionX, int regionZ, int minSectionY) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        RegionCoordKey rKey = new RegionCoordKey(srv, w, regionX, regionZ);
        LcaRegionDictSyncFrame dict = regionDictionaries.get(rKey);
        List<String> blockNames = dict != null ? dict.dictIdToBlockStateName() : List.of();
        List<String> biomeNames = dict != null ? dict.dictIdToBiomeName() : List.of();

        AnvilChunkView[][] grid = new AnvilChunkView[32][32];
        int baseCx = regionX << 5;
        int baseCz = regionZ << 5;
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                ChunkCoordKey cKey = new ChunkCoordKey(srv, w, baseCx + cx, baseCz + cz);
                if (!l1Map.containsKey(cKey) && !l2IndexBytes.containsKey(cKey)) {
                    continue;
                }
                Optional<EntryFullChunk> fullOpt = getFullChunk(srv, w, baseCx + cx, baseCz + cz);
                if (fullOpt.isPresent()) {
                    grid[cz][cx] = LcaRemoteStreamCodec.toAnvilChunkView(
                            fullOpt.get(), minSectionY, blockNames, biomeNames);
                }
            }
        }
        return grid;
    }

    public synchronized boolean hasAnyChunkInRegion(
            String serverId, String world, int regionX, int regionZ) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        for (ChunkCoordKey k : l1Map.keySet()) {
            if (k.serverId().equals(srv) && k.world().equals(w)
                    && (k.chunkX() >> 5) == regionX && (k.chunkZ() >> 5) == regionZ) {
                return true;
            }
        }
        for (ChunkCoordKey k : l2IndexBytes.keySet()) {
            if (k.serverId().equals(srv) && k.world().equals(w)
                    && (k.chunkX() >> 5) == regionX && (k.chunkZ() >> 5) == regionZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a `ChunkDigestPayload` (`LCAG`) for reconnect delta synchronization.
     */
    public synchronized ChunkDigestPayload buildRegionDigest(
            String serverId, String world, int regionX, int regionZ) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        List<ChunkDigestEntry> entries = new ArrayList<>();
        Set<Integer> seenLocal = new LinkedHashSet<>();

        for (PooledChunkSlab slab : l1Map.values()) {
            if (slab.serverId().equals(srv)
                    && slab.world().equals(w)
                    && (slab.chunkX() >> 5) == regionX
                    && (slab.chunkZ() >> 5) == regionZ) {
                int localIdx = LcaRemoteStreamCodec.toLocalChunkIndex10(slab.chunkX(), slab.chunkZ());
                seenLocal.add(localIdx);
                entries.add(new ChunkDigestEntry(
                        localIdx, slab.lastUpdateTick(), slab.secDedSyndrome64()));
            }
        }
        int baseCx = regionX << 5;
        int baseCz = regionZ << 5;
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                int localIdx = (cz << 5) | cx;
                if (seenLocal.contains(localIdx)) {
                    continue;
                }
                ChunkCoordKey cKey = new ChunkCoordKey(srv, w, baseCx + cx, baseCz + cz);
                if (l2IndexBytes.containsKey(cKey)) {
                    Optional<PooledChunkSlab> reloaded = getChunkSlabEntry(srv, w, baseCx + cx, baseCz + cz);
                    reloaded.ifPresent(slab -> entries.add(new ChunkDigestEntry(
                            localIdx, slab.lastUpdateTick(), slab.secDedSyndrome64())));
                }
            }
        }
        return new ChunkDigestPayload(w, regionX, regionZ, entries);
    }

    public synchronized boolean evictChunk(String serverId, String world, int chunkX, int chunkZ) {
        ChunkCoordKey key = new ChunkCoordKey(serverId, world, chunkX, chunkZ);
        boolean removed = false;
        PooledChunkSlab l1 = l1Map.remove(key);
        if (l1 != null) {
            memoryBytesUsed = Math.max(0L, memoryBytesUsed - l1.byteSize());
            l1EvictionCount.incrementAndGet();
            removed = true;
        }
        if (l2IndexBytes.containsKey(key)) {
            removeL2DiskEntry(key);
            l2EvictionCount.incrementAndGet();
            removed = true;
        }
        return removed;
    }

    /**
     * Evicts all unpinned entries across L1 and L2, returning the number of evicted chunks.
     */
    public synchronized int evictUnpinned() {
        int evicted = 0;
        Iterator<Map.Entry<ChunkCoordKey, PooledChunkSlab>> it = l1Map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkCoordKey, PooledChunkSlab> e = it.next();
            PooledChunkSlab slab = e.getValue();
            if (slab.pinned() || pinnedRegions.contains(e.getKey().toRegionKey())) {
                continue;
            }
            memoryBytesUsed = Math.max(0L, memoryBytesUsed - slab.byteSize());
            it.remove();
            l1EvictionCount.incrementAndGet();
            evicted++;
        }
        List<ChunkCoordKey> l2Keys = new ArrayList<>(l2IndexBytes.keySet());
        for (ChunkCoordKey k : l2Keys) {
            if (!pinnedRegions.contains(k.toRegionKey())) {
                removeL2DiskEntry(k);
                l2EvictionCount.incrementAndGet();
                evicted++;
            }
        }
        return evicted;
    }

    /**
     * Injects a single-bit flip into an L1 cached chunk slab for `SEC-DED-64` fault-injection tests.
     */
    public synchronized void corruptSingleBitInL1(
            String serverId,
            String world,
            int chunkX,
            int chunkZ,
            int byteIndex,
            int bitIndex
    ) {
        ChunkCoordKey key = new ChunkCoordKey(serverId, world, chunkX, chunkZ);
        PooledChunkSlab entry = l1Map.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Chunk not present in L1 cache: " + key);
        }
        byte[] raw = entry.slabBytes();
        if (byteIndex < 0 || byteIndex >= raw.length) {
            throw new IllegalArgumentException("byteIndex out of bounds: " + byteIndex);
        }
        if (bitIndex < 0 || bitIndex > 7) {
            throw new IllegalArgumentException("bitIndex out of bounds [0..7]: " + bitIndex);
        }
        raw[byteIndex] ^= (byte) (1 << bitIndex);
        l1Map.put(key, new PooledChunkSlab(
                entry.serverId(), entry.world(), entry.chunkX(), entry.chunkZ(),
                entry.lastUpdateTick(), entry.secDedSyndrome64(), raw, entry.pinned()));
    }

    public synchronized PoolTelemetrySnapshot snapshotTelemetry() {
        Set<String> servers = new LinkedHashSet<>();
        Set<String> worlds = new LinkedHashSet<>();
        int pinnedChunks = 0;
        for (Map.Entry<ChunkCoordKey, PooledChunkSlab> e : l1Map.entrySet()) {
            ChunkCoordKey k = e.getKey();
            servers.add(k.serverId());
            worlds.add(k.toRegionKey().compositeWorldKey());
            if (e.getValue().pinned() || pinnedRegions.contains(k.toRegionKey())) {
                pinnedChunks++;
            }
        }
        for (ChunkCoordKey k : l2IndexBytes.keySet()) {
            servers.add(k.serverId());
            worlds.add(k.toRegionKey().compositeWorldKey());
        }
        for (RegionCoordKey rk : regionDictionaries.keySet()) {
            servers.add(rk.serverId());
            worlds.add(rk.compositeWorldKey());
        }
        for (RegionCoordKey rk : pinnedRegions) {
            servers.add(rk.serverId());
            worlds.add(rk.compositeWorldKey());
        }
        return new PoolTelemetrySnapshot(
                memoryBytesUsed,
                config.maxMemoryCacheBytes(),
                diskBytesUsed,
                config.maxDiskCacheBytes(),
                l1Map.size(),
                l2IndexBytes.size(),
                pinnedChunks,
                pinnedLod3Headers.size(),
                l1HitCount.get(),
                l2HitCount.get(),
                missCount.get(),
                l1EvictionCount.get(),
                l2EvictionCount.get(),
                syndromeMismatchCount.get(),
                Collections.unmodifiableSet(servers),
                Collections.unmodifiableSet(worlds)
        );
    }

    public synchronized int countChunksForWorld(String serverId, String world) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        Set<ChunkCoordKey> unique = new LinkedHashSet<>();
        for (ChunkCoordKey k : l1Map.keySet()) {
            if (k.serverId().equals(srv) && k.world().equals(w)) {
                unique.add(k);
            }
        }
        for (ChunkCoordKey k : l2IndexBytes.keySet()) {
            if (k.serverId().equals(srv) && k.world().equals(w)) {
                unique.add(k);
            }
        }
        return unique.size();
    }

    public synchronized int countRegionsForWorld(String serverId, String world) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        Set<RegionCoordKey> unique = new LinkedHashSet<>();
        for (ChunkCoordKey k : l1Map.keySet()) {
            if (k.serverId().equals(srv) && k.world().equals(w)) {
                unique.add(k.toRegionKey());
            }
        }
        for (ChunkCoordKey k : l2IndexBytes.keySet()) {
            if (k.serverId().equals(srv) && k.world().equals(w)) {
                unique.add(k.toRegionKey());
            }
        }
        for (RegionCoordKey rk : pinnedRegions) {
            if (rk.serverId().equals(srv) && rk.world().equals(w)) {
                unique.add(rk);
            }
        }
        return unique.size();
    }

    private static byte[] buildDeterministicLod3HorizonHeader(RegionCoordKey regionKey) {
        byte[] header = new byte[DEFAULT_LOD3_HEADER_BYTES];
        int seed = Objects.hash(
                regionKey.serverId().toLowerCase(Locale.ROOT),
                regionKey.world().toLowerCase(Locale.ROOT),
                regionKey.regionX(),
                regionKey.regionZ());
        for (int i = 0; i < header.length; i++) {
            header[i] = (byte) ((i * 31 + seed + regionKey.regionX() * 17 + regionKey.regionZ()) & 0xFF);
        }
        return header;
    }

    @Override
    public synchronized void close() {
        l1Map.clear();
        for (ChunkCoordKey k : new ArrayList<>(l2IndexBytes.keySet())) {
            removeL2DiskEntry(k);
        }
        pinnedLod3Headers.clear();
        pinnedRegions.clear();
        memoryBytesUsed = 0L;
        diskBytesUsed = 0L;
    }
}

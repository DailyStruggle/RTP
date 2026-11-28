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
import java.util.Arrays;
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
 * @governedBy leafanvil-ADR-005
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
    private static final int L2_STRIPE_COUNT = 64;
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(LcaBoundedSlabPool.class.getName());

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

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PooledChunkSlab that)) {
                return false;
            }
            return Arrays.equals(slabBytes, that.slabBytes)
                    && Objects.equals(
                            List.of(serverId, world, chunkX, chunkZ, lastUpdateTick, secDedSyndrome64, pinned),
                            List.of(that.serverId, that.world, that.chunkX, that.chunkZ, that.lastUpdateTick, that.secDedSyndrome64, that.pinned));
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(serverId, world, chunkX, chunkZ, lastUpdateTick, secDedSyndrome64, pinned);
            return 31 * result + Arrays.hashCode(slabBytes);
        }

        @Override
        public String toString() {
            return "PooledChunkSlab[serverId=" + serverId
                    + ", world=" + world
                    + ", chunkX=" + chunkX
                    + ", chunkZ=" + chunkZ
                    + ", lastUpdateTick=" + lastUpdateTick
                    + ", secDedSyndrome64=" + secDedSyndrome64
                    + ", slabBytes=" + Arrays.toString(slabBytes)
                    + ", pinned=" + pinned + "]";
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
    private final Object metadataLock = new Object();
    private final Object[] l2StripeLocks = new Object[L2_STRIPE_COUNT];
    private final LinkedHashMap<ChunkCoordKey, PooledChunkSlab> l1Map =
            new LinkedHashMap<>(256, 0.75f, true);
    private final LinkedHashMap<ChunkCoordKey, Long> l2IndexBytes =
            new LinkedHashMap<>(256, 0.75f, true);
    private final Map<ChunkCoordKey, PooledChunkSlab> inflightL2Spills =
            new ConcurrentHashMap<>();
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
        for (int i = 0; i < L2_STRIPE_COUNT; i++) {
            this.l2StripeLocks[i] = new Object();
        }
    }

    private Object l2StripeLock(int chunkX, int chunkZ) {
        int rx = chunkX >> 5;
        int rz = chunkZ >> 5;
        int h = (rx * 0x9E3779B9) ^ (rz * 0x85EBCA6B);
        h ^= (h >>> 16);
        h ^= (h >>> 8);
        return l2StripeLocks[h & (L2_STRIPE_COUNT - 1)];
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
     * (`10,990 B`) horizon header in memory (`REQ-MAP-LRU-002`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public void pinRegion(
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
            List<PooledChunkSlab> l1Victims = new ArrayList<>();
            synchronized (metadataLock) {
                byte[] prev = pinnedLod3Headers.put(regionKey, header);
                if (prev != null) {
                    memoryBytesUsed = Math.max(0L, memoryBytesUsed - prev.length);
                }
                memoryBytesUsed += header.length;
                evictL1CollectVictimsLocked(0L, l1Victims);
            }
            for (PooledChunkSlab victim : l1Victims) {
                spillToL2Disk(victim, true);
            }
        }
    }

    public Optional<byte[]> getPinnedLod3Header(
            String serverId, String world, int regionX, int regionZ) {
        RegionCoordKey key = new RegionCoordKey(serverId, world, regionX, regionZ);
        byte[] header = pinnedLod3Headers.get(key);
        return header == null ? Optional.empty() : Optional.of(header.clone());
    }

    /**
     * Ingests a `StreamChunkBatchPayload` (`LCAB`) into the bounded LRU pool (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public IngestBatchResult ingestStreamBatch(
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
     * (`REQ-MAP-STREAM-001`, `REQ-MAP-STREAM-002`, `S-004`, `REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public IngestBatchResult ingestDeltaUpdate(
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
            if (config.pinLod3Headers()) {
                synchronized (metadataLock) {
                    if (!pinnedLod3Headers.containsKey(regionKey)) {
                        byte[] hdr = buildDeterministicLod3HorizonHeader(regionKey);
                        pinnedLod3Headers.put(regionKey, hdr);
                        memoryBytesUsed += hdr.length;
                    }
                }
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

        long memSnap;
        long diskSnap;
        synchronized (metadataLock) {
            memSnap = memoryBytesUsed;
            diskSnap = diskBytesUsed;
        }
        return new IngestBatchResult(
                regionKey,
                cleanAcks,
                patchDeltas,
                fullChunks,
                refills,
                memSnap,
                diskSnap
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
     * Inserts a `EntryFullChunk` directly into the L1 slab cache (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public void putFullChunk(
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

    /**
     * Off-thread L1 insertion and deferred L2 spillover helper (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private void putSlabInternal(PooledChunkSlab slab) {
        int entryBytes = slab.byteSize();
        if (entryBytes > config.maxMemoryCacheBytes()) {
            if (config.maxDiskCacheBytes() >= entryBytes) {
                spillToL2Disk(slab, false);
            }
            return;
        }
        ChunkCoordKey key = new ChunkCoordKey(slab.serverId(), slab.world(), slab.chunkX(), slab.chunkZ());
        List<PooledChunkSlab> l1Victims = new ArrayList<>();
        boolean hadL2Entry = false;
        synchronized (metadataLock) {
            inflightL2Spills.remove(key);
            PooledChunkSlab prev = l1Map.remove(key);
            if (prev != null) {
                memoryBytesUsed = Math.max(0L, memoryBytesUsed - prev.byteSize());
            }
            Long prevL2 = l2IndexBytes.remove(key);
            if (prevL2 != null) {
                diskBytesUsed = Math.max(0L, diskBytesUsed - prevL2);
                hadL2Entry = true;
            }
            evictL1CollectVictimsLocked(entryBytes, l1Victims);
            l1Map.put(key, slab);
            memoryBytesUsed += entryBytes;
        }
        if (hadL2Entry) {
            deleteUnindexedL2File(key);
        }
        for (PooledChunkSlab victim : l1Victims) {
            spillToL2Disk(victim, true);
        }
    }

    private void evictL1CollectVictimsLocked(long incomingBytes, List<PooledChunkSlab> outVictims) {
        synchronized (metadataLock) {
            if (memoryBytesUsed + incomingBytes <= config.maxMemoryCacheBytes()) {
                return;
            }
            boolean canSpill = config.maxDiskCacheBytes() > 0 && config.diskCacheRoot() != null;
            Iterator<Map.Entry<ChunkCoordKey, PooledChunkSlab>> it = l1Map.entrySet().iterator();
            while (memoryBytesUsed + incomingBytes > config.maxMemoryCacheBytes() && it.hasNext()) {
                Map.Entry<ChunkCoordKey, PooledChunkSlab> eldest = it.next();
                ChunkCoordKey vKey = eldest.getKey();
                PooledChunkSlab val = eldest.getValue();
                if (val.pinned() || pinnedRegions.contains(vKey.toRegionKey())) {
                    continue;
                }
                memoryBytesUsed = Math.max(0L, memoryBytesUsed - val.byteSize());
                it.remove();
                l1EvictionCount.incrementAndGet();
                if (canSpill) {
                    inflightL2Spills.put(vKey, val);
                    outVictims.add(val);
                }
            }
            // Safety valve when pinned entries exceed L1 budget
            if (memoryBytesUsed + incomingBytes > config.maxMemoryCacheBytes()) {
                Iterator<Map.Entry<ChunkCoordKey, PooledChunkSlab>> forceIt = l1Map.entrySet().iterator();
                while (memoryBytesUsed + incomingBytes > config.maxMemoryCacheBytes() && forceIt.hasNext()) {
                    Map.Entry<ChunkCoordKey, PooledChunkSlab> eldest = forceIt.next();
                    ChunkCoordKey vKey = eldest.getKey();
                    PooledChunkSlab val = eldest.getValue();
                    memoryBytesUsed = Math.max(0L, memoryBytesUsed - val.byteSize());
                    forceIt.remove();
                    l1EvictionCount.incrementAndGet();
                    if (canSpill) {
                        inflightL2Spills.put(vKey, val);
                        outVictims.add(val);
                    }
                }
            }
        }
    }

    /**
     * Off-thread coordinate-striped L2 disk spillover writer (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private void spillToL2Disk(PooledChunkSlab slab, boolean trackedInflight) {
        if (config.maxDiskCacheBytes() <= 0 || config.diskCacheRoot() == null) {
            return;
        }
        ChunkCoordKey key = new ChunkCoordKey(slab.serverId(), slab.world(), slab.chunkX(), slab.chunkZ());
        byte[] diskBytes;
        try {
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
            diskBytes = baos.toByteArray();
        } catch (IOException e) {
            if (trackedInflight) {
                inflightL2Spills.remove(key, slab);
            }
            LOG.log(java.util.logging.Level.WARNING, e,
                    () -> "[LcaBoundedSlabPool] Failed serializing L2 slab for " + key + ": " + e.getMessage());
            return;
        }
        if (diskBytes.length > config.maxDiskCacheBytes()) {
            if (trackedInflight) {
                inflightL2Spills.remove(key, slab);
            }
            return;
        }

        Path file = resolveL2SlabFile(key);
        List<ChunkCoordKey> l2VictimsToDelete = new ArrayList<>();
        synchronized (l2StripeLock(key.chunkX(), key.chunkZ())) {
            synchronized (metadataLock) {
                if (l1Map.containsKey(key) || (trackedInflight && inflightL2Spills.get(key) != slab)) {
                    if (trackedInflight) {
                        inflightL2Spills.remove(key, slab);
                    }
                    return;
                }
            }
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
                Files.write(tmp, diskBytes);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                if (trackedInflight) {
                    inflightL2Spills.remove(key, slab);
                }
                LOG.log(java.util.logging.Level.WARNING, e,
                        () -> "[LcaBoundedSlabPool] Failed writing L2 spill file " + file + ": " + e.getMessage());
                return;
            }
            boolean superseded = false;
            synchronized (metadataLock) {
                if (l1Map.containsKey(key) || (trackedInflight && inflightL2Spills.get(key) != slab)) {
                    superseded = true;
                } else {
                    Long prevLen = l2IndexBytes.remove(key);
                    if (prevLen != null) {
                        diskBytesUsed = Math.max(0L, diskBytesUsed - prevLen);
                    }
                    evictL2CollectVictimsLocked(diskBytes.length, l2VictimsToDelete);
                    l2IndexBytes.put(key, (long) diskBytes.length);
                    diskBytesUsed += diskBytes.length;
                }
                if (trackedInflight) {
                    inflightL2Spills.remove(key, slab);
                }
            }
            if (superseded) {
                deleteQuietly(file);
                return;
            }
        }

        for (ChunkCoordKey evictedL2Key : l2VictimsToDelete) {
            deleteUnindexedL2File(evictedL2Key);
        }
    }

    private void evictL2CollectVictimsLocked(long incomingDiskBytes, List<ChunkCoordKey> outEvictedKeys) {
        synchronized (metadataLock) {
            Iterator<Map.Entry<ChunkCoordKey, Long>> it = l2IndexBytes.entrySet().iterator();
            while (diskBytesUsed + incomingDiskBytes > config.maxDiskCacheBytes() && it.hasNext()) {
                Map.Entry<ChunkCoordKey, Long> eldest = it.next();
                ChunkCoordKey key = eldest.getKey();
                long size = eldest.getValue();
                it.remove();
                diskBytesUsed = Math.max(0L, diskBytesUsed - size);
                l2EvictionCount.incrementAndGet();
                outEvictedKeys.add(key);
            }
        }
    }

    /**
     * Off-thread unindexed L2 spillover file remover (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    private void deleteUnindexedL2File(ChunkCoordKey key) {
        if (config.diskCacheRoot() == null) {
            return;
        }
        synchronized (l2StripeLock(key.chunkX(), key.chunkZ())) {
            boolean stillIndexed;
            synchronized (metadataLock) {
                stillIndexed = l2IndexBytes.containsKey(key);
            }
            if (!stillIndexed) {
                deleteQuietly(resolveL2SlabFile(key));
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.log(java.util.logging.Level.WARNING, e,
                    () -> "[LcaBoundedSlabPool] Failed deleting L2 slab file " + path + ": " + e.getMessage());
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
        int len = raw.length();
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-';
            if (!valid) {
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(raw, 0, i);
                }
                sb.append('_');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? raw : sb.toString();
    }

    /**
     * Resolves a pooled chunk slab from L1 RAM or L2 Disk `.lca` spillover, promoting L2 hits
     * back into L1 RAM without blocking concurrent L1 readers or disjoint region stripes
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public Optional<PooledChunkSlab> getChunkSlabEntry(
            String serverId, String world, int chunkX, int chunkZ) {
        ChunkCoordKey key = new ChunkCoordKey(serverId, world, chunkX, chunkZ);
        synchronized (metadataLock) {
            PooledChunkSlab l1 = l1Map.get(key);
            if (l1 != null) {
                l1HitCount.incrementAndGet();
                return Optional.of(l1);
            }
            PooledChunkSlab spilling = inflightL2Spills.get(key);
            if (spilling != null) {
                l1HitCount.incrementAndGet();
                return Optional.of(spilling);
            }
            if (!l2IndexBytes.containsKey(key) || config.diskCacheRoot() == null) {
                missCount.incrementAndGet();
                return Optional.empty();
            }
        }

        PooledChunkSlab reloaded = null;
        synchronized (l2StripeLock(key.chunkX(), key.chunkZ())) {
            synchronized (metadataLock) {
                PooledChunkSlab l1 = l1Map.get(key);
                if (l1 != null) {
                    l1HitCount.incrementAndGet();
                    return Optional.of(l1);
                }
                PooledChunkSlab spilling = inflightL2Spills.get(key);
                if (spilling != null) {
                    l1HitCount.incrementAndGet();
                    return Optional.of(spilling);
                }
                if (!l2IndexBytes.containsKey(key)) {
                    missCount.incrementAndGet();
                    return Optional.empty();
                }
            }

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
                            if (len >= 0 && len <= raw.length) {
                                byte[] slab = new byte[len];
                                in.readFully(slab);
                                if (LcaRemoteStreamCodec.verifySecDedSyndrome64(tick, slab, syn)) {
                                    reloaded = new PooledChunkSlab(
                                            srv, w, cx, cz, tick, syn, slab, pinned);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    LOG.log(java.util.logging.Level.WARNING, e,
                            () -> "[LcaBoundedSlabPool] Failed reading L2 slab file " + file + ": " + e.getMessage());
                }
            }

            if (reloaded == null) {
                synchronized (metadataLock) {
                    Long removed = l2IndexBytes.remove(key);
                    if (removed != null) {
                        diskBytesUsed = Math.max(0L, diskBytesUsed - removed);
                    }
                }
                deleteQuietly(file);
                missCount.incrementAndGet();
                return Optional.empty();
            }

            boolean stillValidInL2;
            synchronized (metadataLock) {
                Long removed = l2IndexBytes.remove(key);
                if (removed != null) {
                    diskBytesUsed = Math.max(0L, diskBytesUsed - removed);
                    stillValidInL2 = true;
                } else {
                    stillValidInL2 = false;
                }
            }
            if (!stillValidInL2) {
                missCount.incrementAndGet();
                return Optional.empty();
            }
            deleteQuietly(file);
        }

        l2HitCount.incrementAndGet();
        putSlabInternal(reloaded);
        return Optional.of(reloaded);
    }

    public Optional<byte[]> getChunkSlabBytes(
            String serverId, String world, int chunkX, int chunkZ) {
        return getChunkSlabEntry(serverId, world, chunkX, chunkZ)
                .map(PooledChunkSlab::slabBytes);
    }

    /**
     * Returns a compact binary snapshot of all pooled chunks in `(serverId, world, regionX, regionZ)`
     * or {@link Optional#empty()} if no chunks in that region are pooled.
     */
    public Optional<byte[]> getRegionLcaBytes(
            String serverId, String world, int regionX, int regionZ) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        List<ChunkCoordKey> candidateKeys = collectRegionCandidateKeys(srv, w, regionX, regionZ);
        if (candidateKeys.isEmpty()) {
            return Optional.empty();
        }
        List<BatchChunkEntry> entries = new ArrayList<>(candidateKeys.size());
        for (ChunkCoordKey cKey : candidateKeys) {
            getFullChunk(srv, w, cKey.chunkX(), cKey.chunkZ()).ifPresent(entries::add);
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        List<BatchChunkEntry> capped = entries.size() > LcaRemoteStreamCodec.MAX_BATCH_CHUNKS
                ? entries.subList(0, LcaRemoteStreamCodec.MAX_BATCH_CHUNKS)
                : entries;
        return Optional.of(LcaRemoteStreamCodec.serializeRawChunkBatch(regionX, regionZ, capped, true));
    }

    private List<ChunkCoordKey> collectRegionCandidateKeys(
            String srv, String w, int regionX, int regionZ) {
        int baseCx = regionX << 5;
        int baseCz = regionZ << 5;
        List<ChunkCoordKey> keys = new ArrayList<>();
        synchronized (metadataLock) {
            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    ChunkCoordKey cKey = new ChunkCoordKey(srv, w, baseCx + cx, baseCz + cz);
                    if (l1Map.containsKey(cKey)
                            || inflightL2Spills.containsKey(cKey)
                            || l2IndexBytes.containsKey(cKey)) {
                        keys.add(cKey);
                    }
                }
            }
        }
        return keys;
    }

    public Optional<EntryFullChunk> getFullChunk(
            String serverId, String world, int chunkX, int chunkZ) {
        return getChunkSlabEntry(serverId, world, chunkX, chunkZ)
                .map(entry -> LcaRemoteStreamCodec.deserializeCanonicalChunkSlab(
                        entry.slabBytes(), entry.lastUpdateTick(), entry.secDedSyndrome64()));
    }

    /**
     * Resolves a pooled chunk as an {@link AnvilChunkView} using the region's `BlockState` and
     * `Biome` dictionary for `(serverId, world, chunkX >> 5, chunkZ >> 5)`.
     */
    public Optional<AnvilChunkView> getChunkView(
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
    public AnvilChunkView[][] getRegionChunkViews(
            String serverId, String world, int regionX, int regionZ, int minSectionY) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        RegionCoordKey rKey = new RegionCoordKey(srv, w, regionX, regionZ);
        LcaRegionDictSyncFrame dict = regionDictionaries.get(rKey);
        List<String> blockNames = dict != null ? dict.dictIdToBlockStateName() : List.of();
        List<String> biomeNames = dict != null ? dict.dictIdToBiomeName() : List.of();

        AnvilChunkView[][] grid = new AnvilChunkView[32][32];
        for (ChunkCoordKey cKey : collectRegionCandidateKeys(srv, w, regionX, regionZ)) {
            Optional<EntryFullChunk> fullOpt = getFullChunk(srv, w, cKey.chunkX(), cKey.chunkZ());
            if (fullOpt.isPresent()) {
                int localCx = cKey.chunkX() & 31;
                int localCz = cKey.chunkZ() & 31;
                grid[localCz][localCx] = LcaRemoteStreamCodec.toAnvilChunkView(
                        fullOpt.get(), minSectionY, blockNames, biomeNames);
            }
        }
        return grid;
    }

    public boolean hasAnyChunkInRegion(
            String serverId, String world, int regionX, int regionZ) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        synchronized (metadataLock) {
            for (ChunkCoordKey k : l1Map.keySet()) {
                if (k.serverId().equals(srv) && k.world().equals(w)
                        && (k.chunkX() >> 5) == regionX && (k.chunkZ() >> 5) == regionZ) {
                    return true;
                }
            }
            for (ChunkCoordKey k : inflightL2Spills.keySet()) {
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
        }
        return false;
    }

    /**
     * Builds a `ChunkDigestPayload` (`LCAG`) for reconnect delta synchronization.
     */
    public ChunkDigestPayload buildRegionDigest(
            String serverId, String world, int regionX, int regionZ) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        List<ChunkDigestEntry> entries = new ArrayList<>();
        Set<Integer> seenLocal = new LinkedHashSet<>();
        List<ChunkCoordKey> l2ReloadKeys = new ArrayList<>();

        synchronized (metadataLock) {
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
            for (PooledChunkSlab slab : inflightL2Spills.values()) {
                if (slab.serverId().equals(srv)
                        && slab.world().equals(w)
                        && (slab.chunkX() >> 5) == regionX
                        && (slab.chunkZ() >> 5) == regionZ) {
                    int localIdx = LcaRemoteStreamCodec.toLocalChunkIndex10(slab.chunkX(), slab.chunkZ());
                    if (seenLocal.add(localIdx)) {
                        entries.add(new ChunkDigestEntry(
                                localIdx, slab.lastUpdateTick(), slab.secDedSyndrome64()));
                    }
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
                        l2ReloadKeys.add(cKey);
                    }
                }
            }
        }
        for (ChunkCoordKey cKey : l2ReloadKeys) {
            int localIdx = cKey.localChunkIndex10();
            Optional<PooledChunkSlab> reloaded = getChunkSlabEntry(srv, w, cKey.chunkX(), cKey.chunkZ());
            reloaded.ifPresent(slab -> entries.add(new ChunkDigestEntry(
                    localIdx, slab.lastUpdateTick(), slab.secDedSyndrome64())));
        }
        return new ChunkDigestPayload(w, regionX, regionZ, entries);
    }

    /**
     * Evicts `(serverId, world, chunkX, chunkZ)` from L1/L2 (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public boolean evictChunk(String serverId, String world, int chunkX, int chunkZ) {
        ChunkCoordKey key = new ChunkCoordKey(serverId, world, chunkX, chunkZ);
        boolean removed = false;
        boolean deleteL2 = false;
        synchronized (metadataLock) {
            if (inflightL2Spills.remove(key) != null) {
                removed = true;
            }
            PooledChunkSlab l1 = l1Map.remove(key);
            if (l1 != null) {
                memoryBytesUsed = Math.max(0L, memoryBytesUsed - l1.byteSize());
                l1EvictionCount.incrementAndGet();
                removed = true;
            }
            Long prevL2 = l2IndexBytes.remove(key);
            if (prevL2 != null) {
                diskBytesUsed = Math.max(0L, diskBytesUsed - prevL2);
                l2EvictionCount.incrementAndGet();
                removed = true;
                deleteL2 = true;
            }
        }
        if (deleteL2) {
            deleteUnindexedL2File(key);
        }
        return removed;
    }

    /**
     * Evicts all unpinned entries across L1 and L2, returning the number of evicted chunks
     * (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    public int evictUnpinned() {
        int evicted = 0;
        List<ChunkCoordKey> l2ToDelete = new ArrayList<>();
        synchronized (metadataLock) {
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
            for (ChunkCoordKey k : new ArrayList<>(inflightL2Spills.keySet())) {
                if (!pinnedRegions.contains(k.toRegionKey()) && inflightL2Spills.remove(k) != null) {
                    evicted++;
                }
            }
            Iterator<Map.Entry<ChunkCoordKey, Long>> l2It = l2IndexBytes.entrySet().iterator();
            while (l2It.hasNext()) {
                Map.Entry<ChunkCoordKey, Long> e = l2It.next();
                ChunkCoordKey k = e.getKey();
                if (!pinnedRegions.contains(k.toRegionKey())) {
                    long sz = e.getValue();
                    l2It.remove();
                    diskBytesUsed = Math.max(0L, diskBytesUsed - sz);
                    l2EvictionCount.incrementAndGet();
                    l2ToDelete.add(k);
                    evicted++;
                }
            }
        }
        for (ChunkCoordKey k : l2ToDelete) {
            deleteUnindexedL2File(k);
        }
        return evicted;
    }

    /**
     * Injects a single-bit flip into an L1 cached chunk slab for `SEC-DED-64` fault-injection tests.
     */
    public void corruptSingleBitInL1(
            String serverId,
            String world,
            int chunkX,
            int chunkZ,
            int byteIndex,
            int bitIndex
    ) {
        ChunkCoordKey key = new ChunkCoordKey(serverId, world, chunkX, chunkZ);
        synchronized (metadataLock) {
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
    }

    public PoolTelemetrySnapshot snapshotTelemetry() {
        Set<String> servers = new LinkedHashSet<>();
        Set<String> worlds = new LinkedHashSet<>();
        int pinnedChunks = 0;
        long memSnap;
        long diskSnap;
        int l1Size;
        int l2Size;
        int lod3Size;
        synchronized (metadataLock) {
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
            memSnap = memoryBytesUsed;
            diskSnap = diskBytesUsed;
            l1Size = l1Map.size();
            l2Size = l2IndexBytes.size();
            lod3Size = pinnedLod3Headers.size();
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
                memSnap,
                config.maxMemoryCacheBytes(),
                diskSnap,
                config.maxDiskCacheBytes(),
                l1Size,
                l2Size,
                pinnedChunks,
                lod3Size,
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

    public int countChunksForWorld(String serverId, String world) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        Set<ChunkCoordKey> unique = new LinkedHashSet<>();
        synchronized (metadataLock) {
            for (ChunkCoordKey k : l1Map.keySet()) {
                if (k.serverId().equals(srv) && k.world().equals(w)) {
                    unique.add(k);
                }
            }
            for (ChunkCoordKey k : inflightL2Spills.keySet()) {
                if (k.serverId().equals(srv) && k.world().equals(w)) {
                    unique.add(k);
                }
            }
            for (ChunkCoordKey k : l2IndexBytes.keySet()) {
                if (k.serverId().equals(srv) && k.world().equals(w)) {
                    unique.add(k);
                }
            }
        }
        return unique.size();
    }

    public int countRegionsForWorld(String serverId, String world) {
        String srv = normalizeServerId(serverId);
        String w = normalizeWorldName(world);
        Set<RegionCoordKey> unique = new LinkedHashSet<>();
        synchronized (metadataLock) {
            for (ChunkCoordKey k : l1Map.keySet()) {
                if (k.serverId().equals(srv) && k.world().equals(w)) {
                    unique.add(k.toRegionKey());
                }
            }
            for (ChunkCoordKey k : inflightL2Spills.keySet()) {
                if (k.serverId().equals(srv) && k.world().equals(w)) {
                    unique.add(k.toRegionKey());
                }
            }
            for (ChunkCoordKey k : l2IndexBytes.keySet()) {
                if (k.serverId().equals(srv) && k.world().equals(w)) {
                    unique.add(k.toRegionKey());
                }
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

    /**
     * Closes the pool and purges L1/L2 state (`REQ-RTP-S-005`, `@scheduler_dispatch`).
     */
    @Override
    public void close() {
        List<ChunkCoordKey> l2Keys;
        synchronized (metadataLock) {
            l1Map.clear();
            inflightL2Spills.clear();
            l2Keys = new ArrayList<>(l2IndexBytes.keySet());
            l2IndexBytes.clear();
            pinnedLod3Headers.clear();
            pinnedRegions.clear();
            memoryBytesUsed = 0L;
            diskBytesUsed = 0L;
        }
        for (ChunkCoordKey k : l2Keys) {
            deleteUnindexedL2File(k);
        }
    }
}

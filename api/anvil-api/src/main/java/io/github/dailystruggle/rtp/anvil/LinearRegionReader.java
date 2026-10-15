package io.github.dailystruggle.rtp.anvil;

import io.github.dailystruggle.rtp.anvil.bit.BitHealth;
import io.github.dailystruggle.rtp.anvil.bit.BuiltInTestable;
import io.github.dailystruggle.rtp.anvil.bit.PbitResult;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only Linear (.linear) region-file parser for the pre-filter (ADR-077).
 *
 * <p>Conforms to the canonical Linear v1/v2 specification established by LinearRegionFileFormatTools
 * (LeavesMC / LinearPaper). Packs 1024 chunks into a continuous Zstandard stream without 4 KiB sector
 * padding. Decodes raw uncompressed NBT chunk payloads using {@link DynamicZstdBinding} with
 * continuous Built-In Testing (CBIT) tripwires.</p>
 *
 * <p>Thread-safe: operates on caller-owned buffers with an internal bounded LRU cache for
 * recently decompressed regions.</p>
 */
public final class LinearRegionReader implements RegionFileReader, BuiltInTestable {

    public static final LinearRegionReader INSTANCE = new LinearRegionReader();

    /** 64-bit Linear signature present in both the 32-byte superblock and the 8-byte footer. */
    public static final long LINEAR_SIGNATURE = 0xc3ff13183cca9d9aL;

    public static final int SUPERBLOCK_SIZE = 32;
    public static final int FOOTER_SIZE = 8;
    public static final int INDEX_TABLE_SIZE = 8192; // 1024 entries * 8 bytes (int size, int timestamp)

    /** Maximum allowed decompressed region size (64 MiB) to guard against decompression bombs. */
    public static final int MAX_DECOMPRESSED_REGION_BYTES = 64 * 1024 * 1024;

    /** Maximum allowed decompressed size for a single chunk (32 MiB). */
    public static final int MAX_DECOMPRESSED_CHUNK_BYTES = 32 * 1024 * 1024;

    /** Bounded LRU cache size for decompressed region entries. */
    private static final int CACHE_CAPACITY = 4;

    private static final class DecompressedRegion {
        final byte[] decompressed;
        final int[] chunkSizes; // 1024 entries
        final long[] chunkOffsets; // 1024 entries

        DecompressedRegion(byte[] decompressed, int[] chunkSizes, long[] chunkOffsets) {
            this.decompressed = decompressed;
            this.chunkSizes = chunkSizes;
            this.chunkOffsets = chunkOffsets;
        }
    }

    private static final class CacheKey {
        final byte[] bufferRef;
        final int length;
        final long timestamp;
        final int completeRegionLength;

        CacheKey(byte[] bufferRef, int length, long timestamp, int completeRegionLength) {
            this.bufferRef = bufferRef;
            this.length = length;
            this.timestamp = timestamp;
            this.completeRegionLength = completeRegionLength;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey other)) return false;
            return bufferRef == other.bufferRef && length == other.length
                    && timestamp == other.timestamp && completeRegionLength == other.completeRegionLength;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(bufferRef) * 31 + length;
        }
    }

    private final Map<CacheKey, DecompressedRegion> regionCache =
            new LinkedHashMap<>(CACHE_CAPACITY * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, DecompressedRegion> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            };

    private LinearRegionReader() {}

    @Override
    public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) throws IOException {
        return readChunk(regionBytes, regionBytes == null ? 0 : regionBytes.length, rx, rz);
    }

    @Override
    public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int regionLength, int rx, int rz) throws IOException {
        validateCoordinates(rx, rz);
        if (regionBytes == null || regionLength < SUPERBLOCK_SIZE + FOOTER_SIZE) {
            throw new CorruptRegionEntryException("Region buffer too short for Linear format: "
                    + (regionBytes == null ? 0 : regionLength));
        }

        DecompressedRegion region = getOrDecompress(regionBytes, regionLength);
        int index = (rx & 31) + ((rz & 31) << 5);
        int chunkSize = region.chunkSizes[index];
        if (chunkSize <= 0) {
            return null; // Chunk unallocated / absent
        }

        long offset = region.chunkOffsets[index];
        // CBIT Tripwire T5: 64-bit Offset Bounds
        if (offset < INDEX_TABLE_SIZE || offset + chunkSize > region.decompressed.length) {
            throw new CorruptRegionEntryException("Chunk entry (" + rx + "," + rz + ") spans past decompressed stream: offset="
                    + offset + " size=" + chunkSize + " streamLen=" + region.decompressed.length);
        }

        // CBIT Tripwire T6: NBT Root Identifier (0x0A = TAG_Compound)
        byte firstByte = region.decompressed[(int) offset];
        if (firstByte != 0x0A) {
            throw new CorruptRegionEntryException("Chunk entry (" + rx + "," + rz
                    + ") does not start with TAG_Compound (0x0A), found: 0x" + Integer.toHexString(firstByte & 0xFF));
        }

        byte[] nbtBytes = new byte[chunkSize];
        System.arraycopy(region.decompressed, (int) offset, nbtBytes, 0, chunkSize);
        LinkedHashMap<String, Object> root = Nbt.readRootCompound(nbtBytes);

        // Mode 2 / 5 is compatible with ChunkEntry consumption
        return new AnvilReader.ChunkEntry(2, chunkSize, root);
    }

    @Override
    public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
        return isChunkGenerated(regionBytes, regionBytes == null ? 0 : regionBytes.length, rx, rz);
    }

    @Override
    public boolean isChunkGenerated(byte[] regionBytes, int regionLength, int rx, int rz) {
        if (regionBytes == null || regionLength < SUPERBLOCK_SIZE + FOOTER_SIZE) {
            return false;
        }
        if (rx < 0 || rx > 31 || rz < 0 || rz > 31) {
            return false;
        }
        try {
            ByteBuffer bb = ByteBuffer.wrap(regionBytes, 0, SUPERBLOCK_SIZE);
            if (bb.getLong(0) != LINEAR_SIGNATURE) {
                return false;
            }
            int chunkCount = Short.toUnsignedInt(bb.getShort(18));
            if (chunkCount == 0) {
                return false;
            }
            DecompressedRegion region = getOrDecompress(regionBytes, regionLength);
            int index = (rx & 31) + ((rz & 31) << 5);
            return region.chunkSizes[index] > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private DecompressedRegion getOrDecompress(byte[] regionBytes, int regionLength) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(regionBytes, 0, SUPERBLOCK_SIZE);

        // CBIT Tripwire T1: Superblock Magic
        long headerSig = header.getLong(0);
        if (headerSig != LINEAR_SIGNATURE) {
            throw new CorruptRegionEntryException("Bad Linear header signature: 0x" + Long.toHexString(headerSig));
        }

        byte version = header.get(8);
        if (version != 1 && version != 2) {
            throw new CorruptRegionEntryException("Unsupported Linear version: " + version);
        }

        long newestTimestamp = header.getLong(9);
        int chunkCount = Short.toUnsignedInt(header.getShort(18));
        int completeRegionLength = header.getInt(20);

        // CBIT Tripwire T3: Container Sizing
        if (completeRegionLength <= 0 || (long) completeRegionLength + SUPERBLOCK_SIZE + FOOTER_SIZE > regionLength) {
            throw new CorruptRegionEntryException("Linear completeRegionLength " + completeRegionLength
                    + " out of bounds for region buffer length " + regionLength);
        }

        // CBIT Tripwire T2: Footer Magic
        int footerOffset = SUPERBLOCK_SIZE + completeRegionLength;
        long footerSig = ByteBuffer.wrap(regionBytes).getLong(footerOffset);
        if (footerSig != LINEAR_SIGNATURE) {
            throw new CorruptRegionEntryException("Bad Linear footer signature: 0x" + Long.toHexString(footerSig));
        }

        CacheKey key = new CacheKey(regionBytes, regionLength, newestTimestamp, completeRegionLength);
        synchronized (regionCache) {
            DecompressedRegion cached = regionCache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        // CBIT Tripwire T4: Allocation Ceiling enforced via MAX_DECOMPRESSED_REGION_BYTES
        byte[] decompressed = DynamicZstdBinding.decompress(
                regionBytes, SUPERBLOCK_SIZE, completeRegionLength, MAX_DECOMPRESSED_REGION_BYTES);

        if (decompressed.length < INDEX_TABLE_SIZE) {
            throw new CorruptRegionEntryException("Decompressed Linear stream too short for index table: "
                    + decompressed.length + " < " + INDEX_TABLE_SIZE);
        }

        ByteBuffer indexBuf = ByteBuffer.wrap(decompressed, 0, INDEX_TABLE_SIZE);
        int[] chunkSizes = new int[1024];
        long[] chunkOffsets = new long[1024];
        long currentOffset = INDEX_TABLE_SIZE;

        for (int i = 0; i < 1024; i++) {
            int size = indexBuf.getInt();
            indexBuf.getInt(); // timestamp (unused in probe)
            if (size < 0) {
                throw new CorruptRegionEntryException("Negative Linear chunk size for index " + i + ": " + size);
            }
            if (size > MAX_DECOMPRESSED_CHUNK_BYTES) {
                throw new CorruptRegionEntryException("Linear chunk size " + size + " exceeds maximum ceiling "
                        + MAX_DECOMPRESSED_CHUNK_BYTES);
            }
            chunkSizes[i] = size;
            if (size > 0) {
                chunkOffsets[i] = currentOffset;
                currentOffset += size;
            }
        }

        if (currentOffset > decompressed.length) {
            throw new CorruptRegionEntryException("Cumulative Linear chunk payloads (" + currentOffset
                    + ") exceed decompressed stream length (" + decompressed.length + ")");
        }

        DecompressedRegion result = new DecompressedRegion(decompressed, chunkSizes, chunkOffsets);
        synchronized (regionCache) {
            regionCache.put(key, result);
        }
        return result;
    }

    private static void validateCoordinates(int rx, int rz) {
        if (rx < 0 || rx > 31 || rz < 0 || rz > 31) {
            throw new IllegalArgumentException("Region-local coordinates out of range: (" + rx + "," + rz + ")");
        }
    }

    @Override
    public PbitResult executePbit() {
        long start = System.nanoTime();
        if (!DynamicZstdBinding.isAvailable()) {
            long duration = System.nanoTime() - start;
            return PbitResult.failed("LinearRegionReader", duration,
                    "DynamicZstdBinding unavailable: " + DynamicZstdBinding.pbitResult().diagnosticMessage(), null);
        }
        long duration = System.nanoTime() - start;
        return PbitResult.ok("LinearRegionReader", duration, "Linear reader operational");
    }

    @Override
    public BitHealth getHealthStatus() {
        return DynamicZstdBinding.isAvailable() ? BitHealth.HEALTHY : BitHealth.DEGRADED;
    }

    /**
     * Registers this reader with {@link RegionFormatRegistry} for {@code ".linear"}
     * if {@link DynamicZstdBinding} is available and passes its PBIT canary.
     *
     * @return true if successfully registered, false if host Zstandard runtime is unavailable
     */
    public static boolean registerIfAvailable() {
        if (DynamicZstdBinding.isAvailable()) {
            RegionFormatRegistry.register(".linear", INSTANCE);
            return true;
        }
        return false;
    }
}

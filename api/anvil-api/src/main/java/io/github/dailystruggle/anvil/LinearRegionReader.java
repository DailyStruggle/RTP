package io.github.dailystruggle.anvil;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BuiltInTestable;
import io.github.dailystruggle.bit.PbitResult;

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

    /** 64-bit Luminol/Leaf BufferedLinear (`.b_linear`) master file superblock signature. */
    public static final long BLINEAR_SIGNATURE = -0x200812250269L;

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
            long sig = bb.getLong(0);
            if (sig != LINEAR_SIGNATURE && sig != BLINEAR_SIGNATURE) {
                return false;
            }
            byte version = bb.get(8);
            if (sig == LINEAR_SIGNATURE && (version == 1 || version == 2)) {
                int chunkCount = Short.toUnsignedInt(bb.getShort(18));
                if (chunkCount == 0) {
                    return false;
                }
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
        if (headerSig == BLINEAR_SIGNATURE) {
            return getOrDecompressBLinear(regionBytes, regionLength, header.get(8));
        }
        if (headerSig != LINEAR_SIGNATURE) {
            throw new CorruptRegionEntryException("Bad Linear header signature: 0x" + Long.toHexString(headerSig));
        }

        byte version = header.get(8);
        if (version == 3) {
            return getOrDecompressV2Bucketed(regionBytes, regionLength, header.getLong(9));
        }
        if (version != 1 && version != 2) {
            throw new CorruptRegionEntryException("Unsupported Linear version: " + version);
        }

        long newestTimestamp = header.getLong(9);
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

    private DecompressedRegion getOrDecompressV2Bucketed(
            byte[] regionBytes, int regionLength, long newestTimestamp
    ) throws IOException {
        CacheKey key = new CacheKey(regionBytes, regionLength, newestTimestamp, regionLength);
        synchronized (regionCache) {
            DecompressedRegion cached = regionCache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        ByteBuffer buf = ByteBuffer.wrap(regionBytes, 0, regionLength);
        buf.position(17); // after superblock (8) + version (1) + newestTimestamp (8)
        int gridSize = buf.get() & 0xFF;
        if (gridSize != 1 && gridSize != 2 && gridSize != 4 && gridSize != 8 && gridSize != 16 && gridSize != 32) {
            throw new CorruptRegionEntryException("Invalid Linear v2 gridSize: " + gridSize);
        }
        int bucketSpan = 32 / gridSize;
        buf.getInt(); // region_x
        buf.getInt(); // region_z
        if (buf.remaining() < 128 + 1 + FOOTER_SIZE) {
            throw new CorruptRegionEntryException("Linear v2 buffer truncated before existence bitmap");
        }
        buf.position(buf.position() + 128); // 1024-bit existence bitmap
        while (true) {
            if (!buf.hasRemaining()) {
                throw new CorruptRegionEntryException("Linear v2 truncated in NBT features");
            }
            int nameLen = buf.get() & 0xFF;
            if (nameLen == 0) {
                break;
            }
            if (buf.remaining() < nameLen + 4) {
                throw new CorruptRegionEntryException("Linear v2 truncated feature entry");
            }
            buf.position(buf.position() + nameLen + 4);
        }

        int bucketCount = gridSize * gridSize;
        if (buf.remaining() < bucketCount * 13 + FOOTER_SIZE) {
            throw new CorruptRegionEntryException("Linear v2 truncated in bucket directory");
        }
        int[] bucketSizes = new int[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            int bSize = buf.getInt();
            buf.get(); // compressionLevel
            buf.getLong(); // xxHash64
            if (bSize < 0 || bSize > regionLength) {
                throw new CorruptRegionEntryException("Invalid Linear v2 bucket size: " + bSize);
            }
            bucketSizes[i] = bSize;
        }

        int[] chunkSizes = new int[1024];
        long[] chunkOffsets = new long[1024];
        java.io.ByteArrayOutputStream payloadOut = new java.io.ByteArrayOutputStream(Math.max(16384, INDEX_TABLE_SIZE * 2));
        payloadOut.write(new byte[INDEX_TABLE_SIZE]);
        long currentOffset = INDEX_TABLE_SIZE;

        for (int bx = 0; bx < gridSize; bx++) {
            for (int bz = 0; bz < gridSize; bz++) {
                int idx = bx * gridSize + bz;
                int bSize = bucketSizes[idx];
                if (bSize == 0) {
                    continue;
                }
                if (buf.remaining() < bSize + FOOTER_SIZE) {
                    throw new CorruptRegionEntryException("Linear v2 bucket " + idx + " overflows buffer");
                }
                int bOffset = buf.position();
                buf.position(bOffset + bSize);
                byte[] bucketRaw = DynamicZstdBinding.decompress(
                        regionBytes, bOffset, bSize, MAX_DECOMPRESSED_REGION_BYTES);
                ByteBuffer bucketBuf = ByteBuffer.wrap(bucketRaw);
                for (int cx = 0; cx < bucketSpan; cx++) {
                    for (int cz = 0; cz < bucketSpan; cz++) {
                        if (bucketBuf.remaining() < 12) {
                            throw new CorruptRegionEntryException("Linear v2 bucket " + idx + " truncated header");
                        }
                        int chunkIndex = (bx * bucketSpan + cx) + (bz * bucketSpan + cz) * 32;
                        int entrySize = bucketBuf.getInt();
                        bucketBuf.getLong(); // timestamp
                        if (entrySize > 0) {
                            int chunkLen = entrySize - 8;
                            if (chunkLen < 0 || chunkLen > MAX_DECOMPRESSED_CHUNK_BYTES || chunkLen > bucketBuf.remaining()) {
                                throw new CorruptRegionEntryException("Invalid Linear v2 chunk length: " + chunkLen);
                            }
                            if (currentOffset + chunkLen > MAX_DECOMPRESSED_REGION_BYTES) {
                                throw new CorruptRegionEntryException("Linear v2 region exceeds max decompressed bytes");
                            }
                            chunkSizes[chunkIndex] = chunkLen;
                            chunkOffsets[chunkIndex] = currentOffset;
                            payloadOut.write(bucketRaw, bucketBuf.position(), chunkLen);
                            bucketBuf.position(bucketBuf.position() + chunkLen);
                            currentOffset += chunkLen;
                        }
                    }
                }
            }
        }

        if (buf.remaining() < FOOTER_SIZE) {
            throw new CorruptRegionEntryException("Missing Linear v2 footer");
        }
        long footerSig = buf.getLong();
        if (footerSig != LINEAR_SIGNATURE) {
            throw new CorruptRegionEntryException("Bad Linear v2 footer signature: 0x" + Long.toHexString(footerSig));
        }

        DecompressedRegion result = new DecompressedRegion(payloadOut.toByteArray(), chunkSizes, chunkOffsets);
        synchronized (regionCache) {
            regionCache.put(key, result);
        }
        return result;
    }

    private DecompressedRegion getOrDecompressBLinear(
            byte[] regionBytes, int regionLength, byte version
    ) throws IOException {
        CacheKey key = new CacheKey(regionBytes, regionLength, version, regionLength);
        synchronized (regionCache) {
            DecompressedRegion cached = regionCache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        int[] chunkSizes = new int[1024];
        long[] chunkOffsets = new long[1024];
        java.io.ByteArrayOutputStream payloadOut = new java.io.ByteArrayOutputStream(Math.max(16384, INDEX_TABLE_SIZE * 2));
        payloadOut.write(new byte[INDEX_TABLE_SIZE]);
        long currentOffset = INDEX_TABLE_SIZE;

        if (version == 3) {
            // Luminol/Leaf BufferedLinear v3: 14-byte header + 16 * 8-byte bucket offset table (142 bytes)
            if (regionLength < 142) {
                throw new CorruptRegionEntryException("BufferedLinear v3 buffer too short: " + regionLength);
            }
            ByteBuffer posBuf = ByteBuffer.wrap(regionBytes, 14, 128);
            for (int bucketIdx = 0; bucketIdx < 16; bucketIdx++) {
                long bucketOffset = posBuf.getLong();
                if (bucketOffset == 0L) {
                    continue;
                }
                if (bucketOffset < 142L || bucketOffset + 8L > regionLength) {
                    throw new CorruptRegionEntryException("BufferedLinear v3 bucket offset out of bounds: " + bucketOffset);
                }
                ByteBuffer lensBuf = ByteBuffer.wrap(regionBytes, (int) bucketOffset, 8);
                int originalLen = lensBuf.getInt();
                int compressedLen = lensBuf.getInt();
                if (originalLen < 0 || originalLen > MAX_DECOMPRESSED_REGION_BYTES
                        || compressedLen <= 0 || bucketOffset + 8L + compressedLen > regionLength) {
                    throw new CorruptRegionEntryException("BufferedLinear v3 invalid bucket lengths: orig="
                            + originalLen + " comp=" + compressedLen);
                }
                // Off-thread in-memory bucket parse (REQ-RTP-S-005: regionBytes is already pre-loaded on the async Anvil I/O pool)
                byte[] bucketRaw = DynamicZstdBinding.decompress(
                        regionBytes, (int) bucketOffset + 8, compressedLen, Math.max(originalLen, 256));
                ByteBuffer bucketBuf = ByteBuffer.wrap(bucketRaw);
                int beginChunkIndex = bucketIdx << 6;
                for (int chunkIndex = beginChunkIndex; chunkIndex < beginChunkIndex + 64; chunkIndex++) {
                    if (bucketBuf.remaining() < 4) {
                        throw new CorruptRegionEntryException("BufferedLinear v3 bucket truncated at chunk " + chunkIndex);
                    }
                    int sectionSize = bucketBuf.getInt();
                    if (sectionSize <= 0) {
                        continue;
                    }
                    int minSectionHeaderBytes = 8;
                    if (sectionSize < minSectionHeaderBytes || sectionSize > bucketBuf.remaining()) {
                        throw new CorruptRegionEntryException("BufferedLinear v3 invalid chunk section size: " + sectionSize);
                    }
                    int sectionStart = bucketBuf.position();
                    int firstWord = bucketBuf.getInt();
                    int chunkLen;
                    if (sectionSize >= 16 && firstWord == sectionSize - 16) {
                        // Direct uncompressed [int chunkLen, long timestamp, int xxHash32, byte[] nbt]
                        chunkLen = firstWord;
                        bucketBuf.getLong(); // timestamp
                        bucketBuf.getInt(); // xxHash32
                        if (chunkLen < 0 || chunkLen > MAX_DECOMPRESSED_CHUNK_BYTES) {
                            throw new CorruptRegionEntryException("BufferedLinear v3 chunk length out of bounds: " + chunkLen);
                        }
                        chunkSizes[chunkIndex] = chunkLen;
                        chunkOffsets[chunkIndex] = currentOffset;
                        payloadOut.write(bucketRaw, bucketBuf.position(), chunkLen);
                        bucketBuf.position(sectionStart + sectionSize);
                        currentOffset += chunkLen;
                    } else {
                        // CompressingOps LZ4 wrapper: [int uncompressedFrameLen (16 + nbtLen), byte[sectionSize - 4] lz4Block]
                        int uncompressedFrameLen = firstWord;
                        int lz4CompLen = sectionSize - 4;
                        if (uncompressedFrameLen < 16 || uncompressedFrameLen > MAX_DECOMPRESSED_CHUNK_BYTES + 16) {
                            throw new CorruptRegionEntryException("BufferedLinear v3 invalid inner LZ4 frame length: "
                                    + uncompressedFrameLen + " (sectionSize=" + sectionSize + ")");
                        }
                        byte[] frameBytes = new byte[uncompressedFrameLen];
                        Lz4BlockDecoder.decodeBlock(bucketRaw, bucketBuf.position(), lz4CompLen, frameBytes, 0, uncompressedFrameLen);
                        bucketBuf.position(sectionStart + sectionSize);
                        ByteBuffer frameBuf = ByteBuffer.wrap(frameBytes);
                        int rawLenField = frameBuf.getInt();
                        frameBuf.getLong(); // timestamp
                        frameBuf.getInt(); // xxHash32
                        int bodyBytes = uncompressedFrameLen - 16;
                        chunkLen = (rawLenField == uncompressedFrameLen) ? bodyBytes : rawLenField;
                        if (chunkLen < 0 || chunkLen != bodyBytes || chunkLen > MAX_DECOMPRESSED_CHUNK_BYTES) {
                            throw new CorruptRegionEntryException("BufferedLinear v3 inner LZ4 chunk length mismatch: "
                                    + rawLenField + " vs " + bodyBytes);
                        }
                        chunkSizes[chunkIndex] = chunkLen;
                        chunkOffsets[chunkIndex] = currentOffset;
                        payloadOut.write(frameBytes, 16, chunkLen);
                        currentOffset += chunkLen;
                    }
                }
            }
        } else if (version == 2) {
            if (regionLength < 18) {
                throw new CorruptRegionEntryException("BufferedLinear v2 buffer too short: " + regionLength);
            }
            byte[] streamRaw = DynamicZstdBinding.decompress(
                    regionBytes, 18, regionLength - 18, MAX_DECOMPRESSED_REGION_BYTES);
            ByteBuffer streamBuf = ByteBuffer.wrap(streamRaw);
            for (int chunkIndex = 0; chunkIndex < 1024; chunkIndex++) {
                if (streamBuf.remaining() < 4) {
                    throw new CorruptRegionEntryException("BufferedLinear v2 stream truncated at chunk " + chunkIndex);
                }
                int sectionSize = streamBuf.getInt();
                if (sectionSize <= 0) {
                    continue;
                }
                if (sectionSize < 8 || sectionSize > streamBuf.remaining()) {
                    throw new CorruptRegionEntryException("BufferedLinear v2 invalid section size: " + sectionSize);
                }
                int sectionStart = streamBuf.position();
                int firstWord = streamBuf.getInt();
                if (sectionSize >= 16 && firstWord == sectionSize - 16) {
                    int chunkLen = firstWord;
                    streamBuf.getLong(); // timestamp
                    streamBuf.getInt(); // xxHash32
                    if (chunkLen < 0 || chunkLen > MAX_DECOMPRESSED_CHUNK_BYTES) {
                        throw new CorruptRegionEntryException("BufferedLinear v2 chunk length out of bounds: " + chunkLen);
                    }
                    chunkSizes[chunkIndex] = chunkLen;
                    chunkOffsets[chunkIndex] = currentOffset;
                    payloadOut.write(streamRaw, streamBuf.position(), chunkLen);
                    streamBuf.position(sectionStart + sectionSize);
                    currentOffset += chunkLen;
                } else {
                    int uncompressedFrameLen = firstWord;
                    int lz4CompLen = sectionSize - 4;
                    if (uncompressedFrameLen < 16 || uncompressedFrameLen > MAX_DECOMPRESSED_CHUNK_BYTES + 16) {
                        throw new CorruptRegionEntryException("BufferedLinear v2 invalid inner LZ4 frame length: "
                                + uncompressedFrameLen + " (sectionSize=" + sectionSize + ")");
                    }
                    byte[] frameBytes = new byte[uncompressedFrameLen];
                    Lz4BlockDecoder.decodeBlock(streamRaw, streamBuf.position(), lz4CompLen, frameBytes, 0, uncompressedFrameLen);
                    streamBuf.position(sectionStart + sectionSize);
                    ByteBuffer frameBuf = ByteBuffer.wrap(frameBytes);
                    int rawLenField = frameBuf.getInt();
                    frameBuf.getLong(); // timestamp
                    frameBuf.getInt(); // xxHash32
                    int bodyBytes = uncompressedFrameLen - 16;
                    int chunkLen = (rawLenField == uncompressedFrameLen) ? bodyBytes : rawLenField;
                    if (chunkLen < 0 || chunkLen != bodyBytes || chunkLen > MAX_DECOMPRESSED_CHUNK_BYTES) {
                        throw new CorruptRegionEntryException("BufferedLinear v2 inner LZ4 chunk length mismatch: "
                                + rawLenField + " vs " + bodyBytes);
                    }
                    chunkSizes[chunkIndex] = chunkLen;
                    chunkOffsets[chunkIndex] = currentOffset;
                    payloadOut.write(frameBytes, 16, chunkLen);
                    currentOffset += chunkLen;
                }
            }
        } else {
            throw new CorruptRegionEntryException("Unsupported BufferedLinear version: " + version);
        }

        DecompressedRegion result = new DecompressedRegion(payloadOut.toByteArray(), chunkSizes, chunkOffsets);
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
     * Registers this reader with {@link RegionFormatRegistry} for {@code ".linear"} and {@code ".b_linear"}
     * if {@link DynamicZstdBinding} is available and passes its PBIT canary.
     *
     * @return true if successfully registered, false if host Zstandard runtime is unavailable
     */
    public static boolean registerIfAvailable() {
        if (DynamicZstdBinding.isAvailable()) {
            RegionFormatRegistry.register(".linear", INSTANCE);
            RegionFormatRegistry.register(".b_linear", INSTANCE);
            return true;
        }
        return false;
    }
}

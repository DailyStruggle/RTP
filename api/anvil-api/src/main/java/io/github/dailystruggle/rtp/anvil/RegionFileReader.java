package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;

/**
 * Common SPI for format-specific region-file chunk decoders (ADR-077).
 *
 * <p>Implementations unpack format-specific layouts (Anvil {@code .mca} 4 KiB sectors built in;
 * other formats such as Linear {@code .linear} come from addons via {@link RegionFormatRegistry})
 * and return the decoded chunk entry containing the uncompressed NBT root compound. Readers receive
 * untrusted on-disk bytes and shall fail closed: bound every length and offset, cap decompressed
 * sizes, and throw {@link CorruptRegionEntryException} rather than read out of range.</p>
 */
public interface RegionFileReader {

    /**
     * Reads and decodes the chunk at region-local coordinates {@code (rx, rz)}.
     *
     * @param regionBytes the raw bytes of the region file
     * @param rx          region-local chunk x coordinate, 0..31
     * @param rz          region-local chunk z coordinate, 0..31
     * @return decoded chunk entry containing the root NBT compound, or {@code null} if the chunk is not present/unallocated
     * @throws CorruptRegionEntryException if the chunk header or payload structure is malformed
     * @throws IOException                 on decompression or I/O failure
     */
    AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) throws IOException;

    /**
     * As {@link #readChunk(byte[], int, int)} over {@code regionBytes[0, regionLength)}. Pooled
     * buffers may be longer than the file; bytes past {@code regionLength} are stale and must not
     * be read. The default copies when the lengths differ; built-in readers override without copying.
     */
    default AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int regionLength, int rx, int rz) throws IOException {
        return readChunk(exact(regionBytes, regionLength), rx, rz);
    }

    /**
     * Fast check to determine whether the chunk at {@code (rx, rz)} is allocated and generated
     * in the region file without performing full decompression or NBT parsing.
     *
     * @param regionBytes the raw bytes of the region file
     * @param rx          region-local chunk x coordinate, 0..31
     * @param rz          region-local chunk z coordinate, 0..31
     * @return true if the chunk entry is present and non-empty in the region file
     */
    boolean isChunkGenerated(byte[] regionBytes, int rx, int rz);

    /** As {@link #isChunkGenerated(byte[], int, int)} over {@code regionBytes[0, regionLength)}. */
    default boolean isChunkGenerated(byte[] regionBytes, int regionLength, int rx, int rz) {
        if (regionBytes == null || regionLength < 0 || regionLength > regionBytes.length) return false;
        return isChunkGenerated(exact(regionBytes, regionLength), rx, rz);
    }

    private static byte[] exact(byte[] regionBytes, int regionLength) {
        if (regionBytes == null || regionLength == regionBytes.length) return regionBytes;
        if (regionLength < 0 || regionLength > regionBytes.length) {
            throw new IllegalArgumentException("regionLength " + regionLength + " outside buffer " + regionBytes.length);
        }
        return java.util.Arrays.copyOf(regionBytes, regionLength);
    }
}

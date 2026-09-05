package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;

/**
 * Common SPI for format-specific region-file chunk decoders (ADR-077).
 *
 * <p>Implementations unpack format-specific layouts (such as Anvil {@code .mca} 4 KiB sectors
 * or Linear {@code .linear} continuous ZStandard streams) and return the decoded chunk entry
 * containing the uncompressed NBT root compound.</p>
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
     * Fast check to determine whether the chunk at {@code (rx, rz)} is allocated and generated
     * in the region file without performing full decompression or NBT parsing.
     *
     * @param regionBytes the raw bytes of the region file
     * @param rx          region-local chunk x coordinate, 0..31
     * @param rz          region-local chunk z coordinate, 0..31
     * @return true if the chunk entry is present and non-empty in the region file
     */
    boolean isChunkGenerated(byte[] regionBytes, int rx, int rz);
}

package io.github.dailystruggle.rtp.anvil;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only Linear region-file decoder (ADR-077).
 *
 * <p>Linear region files ({@code .linear}) replace Mojang's 4 KiB sector-aligned Anvil layout
 * with continuous ZStandard ({@code zstd}) streams. Developed by high-performance server forks
 * (Leaves, Gale) and modded environments to reduce disk footprint by 30-60% and improve I/O.</p>
 *
 * <p>Format Specification:
 * <ul>
 *   <li>Magic header (8 bytes): {@code 0xC370ACDE22013702L} (signed long: {@code -4363842145328286974L}) or {@code "SUPER\0\0\0"}</li>
 *   <li>Version byte (1 byte): 1 or 2</li>
 *   <li>Newest timestamp (8 bytes) / Header metadata</li>
 *   <li>Compression level (1 byte)</li>
 *   <li>Chunk count / size table (1024 entries of 4-byte uncompressed lengths)</li>
 *   <li>Timestamps (1024 entries of 4-byte timestamps in v1, or 8-byte in v2)</li>
 *   <li>Continuous ZSTD-compressed stream containing the sequential chunk NBT payloads</li>
 * </ul>
 */
public final class LinearRegionReader implements RegionFileReader {

    private static final Logger LOG = Logger.getLogger(LinearRegionReader.class.getName());

    public static final LinearRegionReader INSTANCE = new LinearRegionReader();

    public static final long LINEAR_MAGIC_V1 = 0xC370ACDE22013702L;
    public static final long LINEAR_MAGIC_V2 = 0xC370ACDE22013702L;

    /** Total chunks per region file (32 x 32). */
    public static final int CHUNKS_PER_REGION = 1024;

    private static volatile boolean zstdAvailable = true;

    private LinearRegionReader() {}

    /**
     * Checks if native ZStandard decompression via zstd-jni is available in the current runtime.
     */
    public static boolean isZstdAvailable() {
        if (!zstdAvailable) return false;
        try {
            // Trigger class load & native linkage check
            Zstd.isError(0);
            return true;
        } catch (Throwable t) {
            zstdAvailable = false;
            LOG.log(Level.WARNING, "[RTP] zstd-jni native library failed to load; Linear region format decoding disabled.", t);
            return false;
        }
    }

    @Override
    public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
        if (regionBytes == null || regionBytes.length < 32) {
            return false;
        }
        if (rx < 0 || rx > 31 || rz < 0 || rz > 31) {
            return false;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(regionBytes);
            long magic = buf.getLong();
            if (magic != LINEAR_MAGIC_V1) {
                return false;
            }
            byte version = buf.get();
            if (version < 1 || version > 2) {
                return false;
            }

            // Skip newest timestamp (8 bytes) + compression level (1 byte) + data payload length (4 bytes)
            // Header layout: magic(8) + version(1) + newestTimestamp(8) + compressionLevel(1) + dataLen(4) = 22 bytes minimum
            if (regionBytes.length < 22 + CHUNKS_PER_REGION * 4) {
                return false;
            }
            buf.position(22);

            int chunkIndex = (rx & 31) + ((rz & 31) << 5);
            int uncompressedLength = buf.getInt(22 + chunkIndex * 4);
            return uncompressedLength > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) throws IOException {
        if (!isZstdAvailable()) {
            throw new IOException("zstd-jni not available on this platform");
        }
        if (regionBytes == null || regionBytes.length < 32) {
            throw new CorruptRegionEntryException("Linear region buffer too short: " + (regionBytes == null ? 0 : regionBytes.length));
        }
        if (rx < 0 || rx > 31 || rz < 0 || rz > 31) {
            throw new IllegalArgumentException("Region-local (rx,rz) out of range: (" + rx + "," + rz + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(regionBytes);
        long magic = buf.getLong();
        if (magic != LINEAR_MAGIC_V1) {
            throw new CorruptRegionEntryException("Invalid Linear magic header: 0x" + Long.toHexString(magic));
        }

        byte version = buf.get();
        if (version < 1 || version > 2) {
            throw new UnsupportedAnvilFormatException("Unsupported Linear format version: " + version);
        }

        long newestTimestamp = buf.getLong();
        byte compressionLevel = buf.get();
        int dataPayloadLength = buf.getInt();

        int headerSize = 22; // 8 + 1 + 8 + 1 + 4
        int chunkLengthsOffset = headerSize;
        int chunkLengthsSize = CHUNKS_PER_REGION * 4; // 4096 bytes
        int timestampsOffset = chunkLengthsOffset + chunkLengthsSize;
        int timestampsSize = CHUNKS_PER_REGION * (version == 1 ? 4 : 8); // 4096 or 8192 bytes
        int zstdStreamOffset = timestampsOffset + timestampsSize;

        if (regionBytes.length < zstdStreamOffset) {
            throw new CorruptRegionEntryException("Linear region file truncated before ZSTD payload (length: "
                    + regionBytes.length + ", expected >= " + zstdStreamOffset + ")");
        }

        int targetChunkIndex = (rx & 31) + ((rz & 31) << 5);
        int targetUncompressedLength = buf.getInt(chunkLengthsOffset + targetChunkIndex * 4);

        if (targetUncompressedLength <= 0) {
            return null; // Chunk not generated in this region
        }

        // Calculate offset in uncompressed stream to target chunk
        int uncompressedOffsetToTarget = 0;
        for (int i = 0; i < targetChunkIndex; i++) {
            int len = buf.getInt(chunkLengthsOffset + i * 4);
            if (len > 0) {
                uncompressedOffsetToTarget += len;
            }
        }

        // Decompress the target chunk from ZStandard stream
        byte[] nbtBytes = new byte[targetUncompressedLength];
        int zstdLength = Math.min(regionBytes.length - zstdStreamOffset, dataPayloadLength);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(regionBytes, zstdStreamOffset, zstdLength);
             ZstdInputStream zis = new ZstdInputStream(bais);
             DataInputStream dis = new DataInputStream(zis)) {

            // Skip preceding chunk bytes
            long skipped = 0;
            while (skipped < uncompressedOffsetToTarget) {
                long s = zis.skip(uncompressedOffsetToTarget - skipped);
                if (s <= 0) {
                    // Try reading byte if skip returns 0
                    int b = zis.read();
                    if (b == -1) break;
                    skipped++;
                } else {
                    skipped += s;
                }
            }
            if (skipped < uncompressedOffsetToTarget) {
                throw new CorruptRegionEntryException("Truncated ZSTD stream: expected offset "
                        + uncompressedOffsetToTarget + ", reached " + skipped);
            }

            dis.readFully(nbtBytes);
        } catch (Throwable t) {
            if (t instanceof LinkageError || t instanceof NoClassDefFoundError) {
                zstdAvailable = false;
                throw new IOException("zstd-jni linkage failed during Linear decompression", t);
            }
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("Failed to decompress Linear chunk payload at (" + rx + "," + rz + ")", t);
        }

        LinkedHashMap<String, Object> root = Nbt.readRootCompound(nbtBytes);
        // Linear mode: compressionType 255 (custom/ZSTD)
        return new AnvilReader.ChunkEntry(255, targetUncompressedLength, root);
    }
}

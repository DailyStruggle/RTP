package io.github.dailystruggle.rtp.anvil;

import io.airlift.compress.zstd.ZstdInputStream;

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
 * <p>Decompression uses the pure-Java aircompressor ZStandard decoder (no native binaries),
 * so decoding cannot fail to link on any JVM/platform. Built into {@code anvil-api} and
 * registered for {@code .linear} by default alongside vanilla {@code .mca} (ADR-077).</p>
 */
public final class LinearRegionReader implements RegionFileReader {

    private static final Logger LOG = Logger.getLogger(LinearRegionReader.class.getName());

    public static final LinearRegionReader INSTANCE = new LinearRegionReader();

    public static final long LINEAR_MAGIC_V1 = 0xC370ACDE22013702L;
    public static final long LINEAR_MAGIC_V2 = 0xC370ACDE22013702L;

    /** Total chunks per region file (32 x 32). */
    public static final int CHUNKS_PER_REGION = 1024;

    private static volatile boolean zstdAvailable = true;

    LinearRegionReader() {}

    /**
     * Checks if ZStandard decompression is available in the current runtime. The pure-Java
     * aircompressor decoder has no native dependency, so this only fails if the decoder
     * classes are somehow missing from the classpath.
     */
    public static boolean isZstdAvailable() {
        if (!zstdAvailable) return false;
        try {
            // Trigger class load of the pure-Java decoder
            Class.forName(ZstdInputStream.class.getName());
            return true;
        } catch (Throwable t) {
            zstdAvailable = false;
            LOG.log(Level.WARNING, "[RTP] ZStandard decoder classes unavailable; Linear region format decoding disabled.", t);
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
            throw new IOException("ZStandard decoder not available on this classpath");
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

        buf.getLong(); // newestTimestamp
        buf.get(); // compressionLevel
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
                markZstdUnavailable();
                throw new IOException("ZStandard decoder classes failed to link during Linear decompression", t);
            }
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("Failed to decompress Linear chunk payload at (" + rx + "," + rz + ")", t);
        }

        LinkedHashMap<String, Object> root = Nbt.readRootCompound(nbtBytes);
        return new AnvilReader.ChunkEntry(255, targetUncompressedLength, root);
    }

    private static void markZstdUnavailable() {
        zstdAvailable = false;
    }
}

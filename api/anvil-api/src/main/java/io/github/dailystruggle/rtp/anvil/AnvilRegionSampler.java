package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Sampled (partial) biome reads of a single {@code r.X.Z.mca} for the coarse-first world
 * biome survey (ADR-104 section 4.6).
 *
 * <p>Unlike a whole-region decode, {@link #sampleBiomes} reads the 8 KiB header with a
 * positional read, then only the sector run of each requested chunk
 * ({@code offset * 4096}, {@code sectorCount * 4096}, clamped to EOF for an unpadded final
 * sector), and decodes just those chunks via
 * {@link AnvilReader#readChunkViewFromSectors}. When {@link AnvilRegionByteCache} already
 * holds a fresh copy of the file, the cached bytes are used and no disk read happens.
 *
 * <p>Blocking file I/O: call off the tick thread only (S-005). Spawns no threads.
 * Stateless and thread-safe.
 */
public final class AnvilRegionSampler {

  private static final int SECTOR_SIZE = 4096;
  private static final int HEADER_BYTES = SECTOR_SIZE * 2;

  private AnvilRegionSampler() {}

  /**
   * Samples the biome at chunk-local {@code (8, y, 8)} for each requested chunk of region
   * {@code (rcx, rcz)}.
   *
   * <p>{@code localIndices[i] = lz * 32 + lx} with {@code lx = cx & 31}, {@code lz = cz & 31};
   * out-of-range indices are ignored. Result keys are
   * {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)} with absolute {@code cx = (rcx << 5) | lx},
   * {@code cz = (rcz << 5) | lz}; values are {@code canonicaliser} applied to the raw biome id
   * (entries whose canonical form is null/empty are dropped).
   *
   * <p>Absent or undecodable chunks are skipped per chunk. File-level I/O failures (missing
   * file, unreadable header) yield an empty map; callers own logging (S-004).
   *
   * <p>Blocking: off tick thread only (S-005).
   */
  public static Map<Long, String> sampleBiomes(
      Path regionFile, int rcx, int rcz, int y, int[] localIndices,
      UnaryOperator<String> canonicaliser) {
    try {
      return sampleBiomesOrThrow(regionFile, rcx, rcz, y, localIndices, canonicaliser, null);
    } catch (IOException e) {
      return Collections.emptyMap();
    }
  }

  /**
   * As {@link #sampleBiomes} but surfaces file-level I/O failures so adapters can log them
   * (S-004). Missing file or empty request still return an empty map.
   *
   * @throws IOException when the file exists but its header cannot be read
   */
  public static Map<Long, String> sampleBiomesOrThrow(
      Path regionFile, int rcx, int rcz, int y, int[] localIndices,
      UnaryOperator<String> canonicaliser) throws IOException {
    return sampleBiomesOrThrow(regionFile, rcx, rcz, y, localIndices, canonicaliser, null);
  }

  /**
   * Implementation; {@code bytesRead[0]} (optional) accumulates bytes read from disk.
   * Package-private test hook.
   */
  static Map<Long, String> sampleBiomesOrThrow(
      Path regionFile, int rcx, int rcz, int y, int[] localIndices,
      UnaryOperator<String> canonicaliser, long[] bytesRead) throws IOException {
    if (regionFile == null || localIndices == null || localIndices.length == 0) {
      return Collections.emptyMap();
    }
    UnaryOperator<String> canon = canonicaliser != null ? canonicaliser : UnaryOperator.identity();
    long mtime = lastModifiedMillis(regionFile);
    if (mtime < 0L) return Collections.emptyMap();

    try (AnvilRegionByteCache.Lease cached = AnvilRegionByteCache.acquireIfCached(regionFile, mtime)) {
      if (cached != null) {
        return sampleFromRegionBytes(cached.buffer(), cached.length(), rcx, rcz, y, localIndices, canon);
      }
    }

    HashMap<Long, String> out = new HashMap<>(Math.max(16, localIndices.length * 2));
    try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
      long fileSize = channel.size();
      if (fileSize < HEADER_BYTES) return Collections.emptyMap();
      // Location table only (first 4 KiB); timestamps are not consumed.
      byte[] header = new byte[SECTOR_SIZE];
      if (!readFully(channel, ByteBuffer.wrap(header), 0L)) {
        throw new IOException("Short read of region header: " + regionFile);
      }
      if (bytesRead != null) bytesRead[0] += header.length;
      for (int idx : localIndices) {
        if (idx < 0 || idx >= 1024) continue;
        int lx = idx & 31;
        int lz = idx >>> 5;
        int entry = idx * 4;
        int sectorOffset = ((header[entry] & 0xFF) << 16)
            | ((header[entry + 1] & 0xFF) << 8)
            | (header[entry + 2] & 0xFF);
        int sectorCount = header[entry + 3] & 0xFF;
        if (sectorOffset < 2 || sectorCount == 0) continue;
        long start = (long) sectorOffset * SECTOR_SIZE;
        if (start + 5 > fileSize) continue;
        // Final sector is padded only on region close; decodeSectorPayload bounds the length prefix.
        int len = (int) Math.min((long) sectorCount * SECTOR_SIZE, fileSize - start);
        try {
          byte[] sectors = new byte[len];
          if (!readFully(channel, ByteBuffer.wrap(sectors), start)) continue;
          if (bytesRead != null) bytesRead[0] += len;
          AnvilChunkView view = AnvilReader.readChunkViewFromSectors(sectors, lx, lz);
          put(out, view, rcx, rcz, lx, lz, y, canon);
        } catch (Exception ignored) {
          // chunk unreadable or undecodable; skip per chunk.
        }
      }
    }
    return out;
  }

  /**
   * Biome at chunk-local {@code (8, y, 8)} for every chunk of region {@code (rcx, rcz)}: one
   * whole-file read under an {@link AnvilRegionByteCache} lease (pooled buffer, explicit length),
   * shared by every platform adapter's full-region sweep. Keys and canonicalisation as
   * {@link #sampleBiomes}; absent or undecodable chunks are skipped. Empty map when the file is
   * missing or unreadable. Blocking: off tick thread only (S-005).
   */
  public static Map<Long, String> readAllBiomes(
      Path regionFile, int rcx, int rcz, int y, UnaryOperator<String> canonicaliser) {
    if (regionFile == null) return Collections.emptyMap();
    UnaryOperator<String> canon = canonicaliser != null ? canonicaliser : UnaryOperator.identity();
    try (AnvilRegionByteCache.Lease lease = AnvilRegionByteCache.acquire(regionFile)) {
      if (lease == null) return Collections.emptyMap();
      return sampleFromRegionBytes(lease.buffer(), lease.length(), rcx, rcz, y, ALL_INDICES, canon);
    }
  }

  private static final int[] ALL_INDICES = new int[1024];

  static {
    for (int i = 0; i < ALL_INDICES.length; i++) ALL_INDICES[i] = i;
  }

  /**
   * Last-modified time of {@code regionFile} in epoch millis, or {@code -1} when the file is
   * missing or its attributes cannot be read. Blocking stat: off tick thread only (S-005).
   */
  public static long lastModifiedMillis(Path regionFile) {
    if (regionFile == null) return -1L;
    try {
      if (!Files.isRegularFile(regionFile)) return -1L;
      return Files.getLastModifiedTime(regionFile).toMillis();
    } catch (IOException | SecurityException e) {
      return -1L;
    }
  }

  private static Map<Long, String> sampleFromRegionBytes(
      byte[] regionBytes, int regionLength, int rcx, int rcz, int y, int[] localIndices,
      UnaryOperator<String> canon) {
    HashMap<Long, String> out = new HashMap<>(Math.max(16, localIndices.length * 2));
    for (int idx : localIndices) {
      if (idx < 0 || idx >= 1024) continue;
      int lx = idx & 31;
      int lz = idx >>> 5;
      try {
        put(out, AnvilReader.readChunkView(regionBytes, regionLength, lx, lz), rcx, rcz, lx, lz, y, canon);
      } catch (Exception ignored) {
        // chunk not present in region file or unreadable; skip per chunk.
      }
    }
    return out;
  }

  private static void put(
      Map<Long, String> out, AnvilChunkView view, int rcx, int rcz, int lx, int lz, int y,
      UnaryOperator<String> canon) {
    if (view == null) return;
    String raw = view.getBiomeAt(8, y, 8);
    if (raw == null) return;
    String canonical = canon.apply(raw);
    if (canonical == null || canonical.isEmpty()) return;
    int cx = (rcx << 5) | lx;
    int cz = (rcz << 5) | lz;
    out.put(((long) cx << 32) | (cz & 0xFFFFFFFFL), canonical);
  }

  private static boolean readFully(FileChannel channel, ByteBuffer dst, long position)
      throws IOException {
    long pos = position;
    while (dst.hasRemaining()) {
      int n = channel.read(dst, pos);
      if (n < 0) return false;
      pos += n;
    }
    return true;
  }
}

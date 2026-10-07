package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-chunk {@code .mca} reads that touch only the chunk's own bytes: the location entry
 * (via {@link AnvilRegionHeaderCache}) and one positioned read of its sector run - typically
 * 10-40 KiB instead of a multi-megabyte whole-file read (ADR-016).
 *
 * <p>Sector bytes land in a per-thread scratch buffer that grows to the largest run seen (capped
 * at 255 sectors, the location-table maximum), so steady-state probes allocate no I/O buffers.
 * Decoded results hold no reference to the scratch buffer.</p>
 *
 * <p>Staleness: a cached table may predate a re-save that moved the chunk. Sector runs are
 * verified by the decoded {@code xPos}/{@code zPos}; a corrupt or mismatched run read through a
 * cached table drops the table and retries once with a fresh one. Failures after that surface as
 * {@link CorruptRegionEntryException} so callers fail closed to UNKNOWN (S-004).</p>
 *
 * <p>Blocking file I/O: call off the tick thread only (S-005). Spawns no threads.</p>
 */
public final class AnvilSectorReader {

  private static final int SECTOR_SIZE = 4096;
  private static final int MAX_RUN_BYTES = 255 * SECTOR_SIZE;
  private static final int INITIAL_SCRATCH = 64 * 1024;

  private static final ThreadLocal<byte[]> SCRATCH =
      ThreadLocal.withInitial(() -> new byte[INITIAL_SCRATCH]);

  private static final AtomicLong SECTOR_BYTES_READ = new AtomicLong();
  private static final AtomicLong CHUNK_READS = new AtomicLong();
  private static final AtomicLong STALE_RETRIES = new AtomicLong();

  private AnvilSectorReader() {}

  @FunctionalInterface
  private interface Decoder<T> {
    T decode(byte[] buf, int len, int chunkX, int chunkZ) throws IOException;
  }

  /**
   * Decodes absolute chunk {@code (chunkX, chunkZ)} from {@code regionFile}. Returns {@code null}
   * when the file is missing or the chunk slot is empty.
   *
   * @throws IOException on unreadable files or corrupt/unsupported payloads
   */
  public static AnvilReader.ChunkEntry readChunkEntry(Path regionFile, int chunkX, int chunkZ)
      throws IOException {
    return read(regionFile, chunkX, chunkZ, AnvilReader::readChunkEntryFromSectors);
  }

  /**
   * {@link AnvilReader#readColumnProbe} for absolute chunk {@code (chunkX, chunkZ)}, reading only
   * the chunk's own sectors. Returns {@code null} when the file is missing or the slot is empty.
   *
   * @throws IOException on unreadable files or corrupt/unsupported payloads
   */
  public static ColumnProbe readColumnProbe(Path regionFile, int chunkX, int chunkZ, int minY, int maxY)
      throws IOException {
    if (minY > maxY) {
      throw new IllegalArgumentException("minY=" + minY + " must be <= maxY=" + maxY);
    }
    return read(regionFile, chunkX, chunkZ,
        (buf, len, x, z) -> AnvilReader.readColumnProbeFromSectors(buf, len, x, z, minY, maxY));
  }

  private static <T> T read(Path regionFile, int chunkX, int chunkZ, Decoder<T> decoder)
      throws IOException {
    if (regionFile == null) return null;
    int index = (chunkX & 31) + ((chunkZ & 31) << 5);
    for (int attempt = 0; ; attempt++) {
      AnvilRegionHeaderCache.Header header = AnvilRegionHeaderCache.fresh(regionFile);
      long mtime;
      if (header == null) {
        mtime = AnvilRegionHeaderCache.mtimeOrMinusOne(regionFile);
        if (mtime < 0L) {
          AnvilRegionHeaderCache.invalidate(regionFile);
          return null;
        }
        header = AnvilRegionHeaderCache.revalidate(regionFile, mtime);
      } else {
        mtime = header.mtime();
      }
      boolean cachedTable = header != null;
      try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
        if (header == null) {
          header = AnvilRegionHeaderCache.load(regionFile, ch, mtime);
          if (header == null) {
            throw new CorruptRegionEntryException("Region file shorter than its location table: " + regionFile);
          }
        }
        int sectorOffset = header.sectorOffset(index);
        int sectorCount = header.sectorCount(index);
        if (sectorOffset == 0 && sectorCount == 0) return null;
        if (sectorOffset < 2) {
          throw new CorruptRegionEntryException("Chunk entry (" + chunkX + "," + chunkZ + ") sector offset "
              + sectorOffset + " overlaps 8 KiB region header (< 2 sectors)");
        }
        if (sectorCount == 0) {
          throw new CorruptRegionEntryException("Chunk entry (" + chunkX + "," + chunkZ + ") has sector offset "
              + sectorOffset + " but zero sector count");
        }
        long start = (long) sectorOffset * SECTOR_SIZE;
        long fileSize = ch.size();
        if (start + 5 > fileSize) {
          throw new CorruptRegionEntryException("Chunk entry (" + chunkX + "," + chunkZ
              + ") spans past end of file: start=" + start + " fileLen=" + fileSize);
        }
        // Final sector is padded only on region close; the decoder bounds the length prefix.
        int len = (int) Math.min((long) sectorCount * SECTOR_SIZE, fileSize - start);
        byte[] buf = scratch(len);
        int got = readFully(ch, buf, len, start);
        SECTOR_BYTES_READ.addAndGet(got);
        CHUNK_READS.incrementAndGet();
        if (got < len) {
          throw new CorruptRegionEntryException("Short read of chunk (" + chunkX + "," + chunkZ + ") sectors: "
              + got + " of " + len);
        }
        return decoder.decode(buf, len, chunkX, chunkZ);
      } catch (NoSuchFileException e) {
        AnvilRegionHeaderCache.invalidate(regionFile);
        return null;
      } catch (CorruptRegionEntryException e) {
        if (!cachedTable || attempt > 0) throw e;
        // The cached table may predate a re-save: re-read it once.
        AnvilRegionHeaderCache.invalidate(regionFile);
        STALE_RETRIES.incrementAndGet();
      }
    }
  }

  private static byte[] scratch(int len) {
    byte[] buf = SCRATCH.get();
    if (buf.length >= len) return buf;
    int cap = buf.length;
    while (cap < len) cap = Math.min(MAX_RUN_BYTES, cap << 1);
    buf = new byte[Math.max(cap, len)];
    SCRATCH.set(buf);
    return buf;
  }

  private static int readFully(FileChannel ch, byte[] buf, int len, long position) throws IOException {
    ByteBuffer dst = ByteBuffer.wrap(buf, 0, len);
    long pos = position;
    while (dst.hasRemaining()) {
      int n = ch.read(dst, pos);
      if (n < 0) break;
      pos += n;
    }
    return dst.position();
  }

  /** Cumulative sector bytes read from disk (location tables excluded). Diagnostic hook. */
  public static long sectorBytesRead() {
    return SECTOR_BYTES_READ.get();
  }

  /** Cumulative positioned chunk reads. Diagnostic hook. */
  public static long chunkReads() {
    return CHUNK_READS.get();
  }

  /** Cumulative retries after a cached table produced a corrupt or mismatched run. */
  public static long staleRetries() {
    return STALE_RETRIES.get();
  }

  /** Cleans up the calling thread's scratch buffer. */
  public static void clearScratch() {
    SCRATCH.remove();
  }

  /** Zeros the counters and clears the calling thread's scratch buffer. Test hook. */
  public static void resetStats() {
    SECTOR_BYTES_READ.set(0);
    CHUNK_READS.set(0);
    STALE_RETRIES.set(0);
    SCRATCH.remove();
  }
}

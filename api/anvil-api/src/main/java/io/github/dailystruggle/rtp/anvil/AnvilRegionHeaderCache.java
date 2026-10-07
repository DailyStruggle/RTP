package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LRU of {@code .mca} location tables (the first 4 KiB of each region file) keyed by path.
 *
 * <p>A single-chunk probe needs only its 4-byte location entry plus the chunk's own sectors, so
 * caching tables instead of whole files keeps ~1,000 region files resident in 4 MiB - enough for
 * random selection over a 16k-block radius to hit on repeat visits.</p>
 *
 * <p>Staleness: same bounded window as {@link AnvilRegionByteCache} - an entry is trusted without
 * a syscall for {@link AnvilRegionByteCache#revalidateIntervalMillis()}, then re-stat'ed and
 * re-read when the mtime advanced. A table read before a re-save may point at reused sectors;
 * {@link AnvilSectorReader} detects that via the decoded chunk position and retries with a fresh
 * table, so staleness never yields another chunk's data.</p>
 *
 * <p>Tables are immutable once published. Thread-safe; O(1) work under the monitor.</p>
 */
public final class AnvilRegionHeaderCache {

  /** Location table size: 1024 entries x 4 bytes. */
  public static final int TABLE_BYTES = 4096;

  /** ~4 MiB of retained tables. */
  private static final int CAPACITY = 1024;

  private static final LinkedHashMap<Path, Header> CACHE =
      new LinkedHashMap<>(CAPACITY * 2, 0.75f, /* accessOrder = */ true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Header> eldest) {
          return size() > CAPACITY;
        }
      };

  private static final AtomicLong HITS = new AtomicLong();
  private static final AtomicLong MISSES = new AtomicLong();

  private AnvilRegionHeaderCache() {}

  /** Immutable location table of one region file at {@link #mtime()}. */
  public static final class Header {
    private final byte[] table;
    private final long mtime;
    private volatile long checkedNanos;

    Header(byte[] table, long mtime, long checkedNanos) {
      this.table = table;
      this.mtime = mtime;
      this.checkedNanos = checkedNanos;
    }

    /** File mtime (epoch millis) the table was read at. */
    public long mtime() {
      return mtime;
    }

    /** Sector offset of slot {@code index = lx + lz * 32}; 0 for an empty slot. */
    public int sectorOffset(int index) {
      int off = index * 4;
      return ((table[off] & 0xFF) << 16) | ((table[off + 1] & 0xFF) << 8) | (table[off + 2] & 0xFF);
    }

    /** Sector count of slot {@code index = lx + lz * 32}. */
    public int sectorCount(int index) {
      return table[index * 4 + 3] & 0xFF;
    }

    /** True when the slot is allocated (offset and count both non-zero). */
    public boolean isOccupied(int index) {
      return sectorOffset(index) != 0 && sectorCount(index) != 0;
    }
  }

  /**
   * Location table of {@code regionFile}: cached when validated within the window, else at most one
   * stat plus, on mtime change or miss, one 4 KiB positioned read. {@code null} when the file is
   * missing or shorter than a table. Blocking I/O: off the tick thread only (S-005).
   *
   * @throws IOException when the file exists but its table cannot be read
   */
  public static Header get(Path regionFile) throws IOException {
    if (regionFile == null) return null;
    Header h = fresh(regionFile);
    if (h != null) return h;
    long mtime = mtimeOrMinusOne(regionFile);
    if (mtime < 0L) {
      invalidate(regionFile);
      return null;
    }
    h = revalidate(regionFile, mtime);
    if (h != null) return h;
    try (FileChannel ch = FileChannel.open(regionFile, StandardOpenOption.READ)) {
      return load(regionFile, ch, mtime);
    } catch (java.nio.file.NoSuchFileException e) {
      invalidate(regionFile);
      return null;
    }
  }

  /** Entry validated within the window; never performs a syscall. */
  static Header fresh(Path regionFile) {
    long window = AnvilRegionByteCache.revalidateIntervalNanos();
    if (window <= 0L) return null;
    Header h;
    synchronized (CACHE) {
      h = CACHE.get(regionFile);
    }
    if (h != null && System.nanoTime() - h.checkedNanos < window) {
      HITS.incrementAndGet();
      return h;
    }
    return null;
  }

  /** Entry whose mtime equals {@code mtime}, re-armed for another window; else {@code null}. */
  static Header revalidate(Path regionFile, long mtime) {
    synchronized (CACHE) {
      Header h = CACHE.get(regionFile);
      if (h == null || h.mtime != mtime) return null;
      h.checkedNanos = System.nanoTime();
      HITS.incrementAndGet();
      return h;
    }
  }

  /**
   * Reads and publishes the table through an already-open channel. {@code null} when the file is
   * shorter than a table (a pre-allocated stub).
   */
  static Header load(Path regionFile, FileChannel ch, long mtime) throws IOException {
    MISSES.incrementAndGet();
    if (ch.size() < TABLE_BYTES) return null;
    byte[] table = new byte[TABLE_BYTES];
    ByteBuffer bb = ByteBuffer.wrap(table);
    long pos = 0;
    while (bb.hasRemaining()) {
      int n = ch.read(bb, pos);
      if (n < 0) throw new CorruptRegionEntryException("Short read of region location table: " + regionFile);
      pos += n;
    }
    Header h = new Header(table, mtime, System.nanoTime());
    synchronized (CACHE) {
      CACHE.put(regionFile, h);
    }
    return h;
  }

  /** Last-modified millis, or {@code -1} when missing/unreadable. One stat syscall. */
  static long mtimeOrMinusOne(Path regionFile) {
    try {
      return Files.getLastModifiedTime(regionFile).toMillis();
    } catch (IOException | SecurityException e) {
      return -1L;
    }
  }

  /** Drops the table for {@code regionFile}. */
  public static void invalidate(Path regionFile) {
    if (regionFile == null) return;
    synchronized (CACHE) {
      CACHE.remove(regionFile);
    }
  }

  /** Clears every table. Test hook. */
  public static void invalidateAll() {
    synchronized (CACHE) {
      CACHE.clear();
    }
  }

  /** Current entry count. Diagnostic/test hook. */
  public static int size() {
    synchronized (CACHE) {
      return CACHE.size();
    }
  }

  /** Cumulative table hits (window or mtime-confirmed). Diagnostic hook. */
  public static long hits() {
    return HITS.get();
  }

  /** Cumulative table reads from disk. Diagnostic hook. */
  public static long misses() {
    return MISSES.get();
  }

  /** Zeros the counters. Test hook. */
  public static void resetStats() {
    HITS.set(0);
    MISSES.set(0);
  }
}

package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-region-file 1024-bit occupancy bitmap derived from the Anvil location table
 * (first 4 KiB of each {@code r.X.Z.mca}). One bit per chunk slot in the 32x32 tile;
 * {@code true} means {@code sectorOffset != 0 && sectorCount != 0} (chunk present
 * on disk).
 *
 * <p>Rationale (binning fast path for GENSCAN): {@code isChunkGenerated} is called
 * once per candidate chunk (193k+ on a 256-radius region). Re-deriving the bit
 * from {@link AnvilRegionByteCache} on every call costs a {@code getLastModifiedTime}
 * syscall + a {@code synchronized} hop into the byte LRU. Binning the answer per
 * 32x32 tile collapses 1024 sibling-chunk queries into a single bitmap build
 * (one {@code long[16]} per region file, derived once at first touch and reused
 * until the {@code .mca} mtime advances).</p>
 *
 * <p>Source: {@link AnvilRegionHeaderCache} (4 KiB location table), never the whole file.
 * Staleness follows its revalidation window: at most one stat per window per file.</p>
 *
 * <p>Thread-safety: LinkedHashMap LRU with {@code synchronized} access.</p>
 */
public final class AnvilRegionOccupancyCache {

  private AnvilRegionOccupancyCache() {}

  /** Same working-set rationale as {@link AnvilRegionByteCache}. */
  private static final int CAPACITY = 32;

  private record Entry(long[] bitmap, long mtime) {
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Entry(long[] otherBitmap, long otherMtime))) return false;
      return mtime == otherMtime && java.util.Arrays.equals(bitmap, otherBitmap);
    }

    @Override
    public int hashCode() {
      return 31 * Long.hashCode(mtime) + java.util.Arrays.hashCode(bitmap);
    }

    @Override
    public String toString() {
      return "Entry[bitmap=" + java.util.Arrays.toString(bitmap) + ", mtime=" + mtime + "]";
    }
  }

  private static final LinkedHashMap<Path, Entry> CACHE =
      new LinkedHashMap<>(CAPACITY * 2, 0.75f, /* accessOrder = */ true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Entry> eldest) {
          return size() > CAPACITY;
        }
      };

  /**
   * Returns {@code true} iff the chunk slot at {@code (cx, cz)} in {@code regionFile}'s
   * 32x32 tile is occupied (location-table sectorOffset and sectorCount both non-zero).
   *
   * <p>Returns {@code false} if the file is missing, unreadable, or the slot is empty.
   * Callers that need a "treat as generated on error" semantic must apply that policy
   * themselves.</p>
   */
  public static boolean isOccupied(Path regionFile, int cx, int cz) {
    if (regionFile == null) return false;
    AnvilRegionHeaderCache.Header header;
    try {
      header = AnvilRegionHeaderCache.get(regionFile);
    } catch (IOException e) {
      return false;
    }
    if (header == null) return false;
    long[] bitmap;
    synchronized (CACHE) {
      Entry hit = CACHE.get(regionFile);
      bitmap = (hit != null && hit.mtime == header.mtime()) ? hit.bitmap : null;
    }
    if (bitmap == null) {
      bitmap = buildBitmap(header);
      synchronized (CACHE) {
        CACHE.put(regionFile, new Entry(bitmap, header.mtime()));
      }
    }
    int rx = Math.floorMod(cx, 32);
    int rz = Math.floorMod(cz, 32);
    int index = rx + (rz << 5);
    return (bitmap[index >>> 6] & (1L << (index & 63))) != 0L;
  }

  /**
   * Diagnostic / test hook returning whether {@code CACHE} currently holds an entry for {@code regionFile}.
   */
  public static boolean isCached(Path regionFile) {
    if (regionFile == null) return false;
    synchronized (CACHE) {
      return CACHE.containsKey(regionFile);
    }
  }

  /** Test/diagnostic hook. */
  public static void invalidateAll() {
    synchronized (CACHE) {
      CACHE.clear();
    }
  }

  private static long[] buildBitmap(AnvilRegionHeaderCache.Header header) {
    long[] bitmap = new long[16]; // 1024 bits
    for (int index = 0; index < 1024; index++) {
      if (header.isOccupied(index)) {
        bitmap[index >>> 6] |= (1L << (index & 63));
      }
    }
    return bitmap;
  }
}

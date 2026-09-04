package io.github.dailystruggle.rtp.common.selection.region.util;

import io.github.dailystruggle.rtp.common.selection.region.util.DataSizeParser.ParsedDataSize;

/**
 * Memory footprint estimation and capacity resolution for region caches.
 *
 * <p>Estimated memory costs per entry:
 * <ul>
 *   <li>Hot queue (active chunk ticket): ~1 MiB (1,048,576 bytes) per chunk retained in memory.</li>
 *   <li>Cold queue (pre-verified coordinate): ~128 bytes per {@code RTPLocation} POJO.</li>
 *   <li>Backlog queue (candidate coordinate): ~128 bytes per {@code BacklogEntry} POJO.</li>
 * </ul>
 *
 * <p>When a cache capacity setting specifies an explicit data size unit (e.g., "64MB", "256KB", "1GiB"),
 * capacity is derived from {@code floor(memoryBytes / bytesPerEntry)}. If a raw number or unit-less string
 * is supplied, it falls back to the basic count value.
 */
public final class CacheMemoryCost {

  /** Hot queue estimated memory footprint per entry: 1 MiB (loaded chunk ticket). */
  public static final long HOT_CACHE_BYTES_PER_ENTRY = 1024L * 1024L;

  /** Cold queue estimated memory footprint per entry: 128 bytes (pre-verified RTPLocation POJO). */
  public static final long COLD_CACHE_BYTES_PER_ENTRY = 128L;

  /** Backlog queue estimated memory footprint per entry: 128 bytes (BacklogEntry candidate POJO). */
  public static final long BACKLOG_CACHE_BYTES_PER_ENTRY = 128L;

  private CacheMemoryCost() {}

  /**
   * Resolves cache capacity from a raw configuration object.
   *
   * <p>If {@code rawValue} specifies an explicit data size unit (e.g. "64MB", "1GiB"),
   * the capacity is calculated as {@code max(0, totalBytes / bytesPerEntry)}.
   * Otherwise, if it is a number or standard numeric string, it falls back to the parsed count.
   *
   * @param rawValue the configured value (Number or String)
   * @param fallbackCount the fallback count if value is missing or unparseable
   * @param bytesPerEntry estimated memory cost in bytes per entry for this cache tier
   * @return resolved item capacity
   */
  public static long resolveCapacity(Object rawValue, long fallbackCount, long bytesPerEntry) {
    if (rawValue == null) {
      return fallbackCount;
    }

    if (rawValue instanceof Number num) {
      return num.longValue();
    }

    if (rawValue instanceof Boolean bool) {
      return bool ? 1L : 0L;
    }

    String s = rawValue.toString().trim();
    if (s.isEmpty()) {
      return fallbackCount;
    }

    if ("true".equalsIgnoreCase(s)) {
      return 1L;
    }
    if ("false".equalsIgnoreCase(s)) {
      return 0L;
    }

    ParsedDataSize parsed = DataSizeParser.parse(s, null);
    if (parsed != null && parsed.explicitUnit()) {
      double totalBytes = parsed.toBytes();
      if (totalBytes < 0) {
        return -1L;
      }
      if (bytesPerEntry <= 0) {
        return (long) totalBytes;
      }
      return (long) (totalBytes / bytesPerEntry);
    }

    try {
      return (long) Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /**
   * Resolves integer-bounded cache capacity (such as for activeChunkCap).
   *
   * @param rawValue the configured value (Number or String)
   * @param fallbackCount the fallback count if value is missing or unparseable
   * @param bytesPerEntry estimated memory cost in bytes per entry for this cache tier
   * @return resolved integer item capacity clamped to [0, Integer.MAX_VALUE]
   */
  public static int resolveCapacityInt(Object rawValue, int fallbackCount, long bytesPerEntry) {
    long resolved = resolveCapacity(rawValue, fallbackCount, bytesPerEntry);
    if (resolved < 0) return 0;
    if (resolved > Integer.MAX_VALUE) return Integer.MAX_VALUE;
    return (int) resolved;
  }
}

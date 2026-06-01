package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BIOME_LOOKUP_PERF_PLAN.md PR-10 — tiny LRU cache of raw {@code r.X.Z.mca} region-file
 * bytes keyed by absolute file path.
 *
 * <p>Rationale: ScanTask's in-flight probes (up to {@code Semaphore(50)} per region) all
 * hit the same {@code r.X.Z.mca} sequentially. Without this cache, each probe calls
 * {@link Files#readAllBytes(Path)} independently — 50 × 2–8 MB allocations + syscalls
 * for a file that changes at most on chunk-save. Caching the bytes collapses 1024
 * sibling-chunk probes into a single disk read.</p>
 *
 * <p>Staleness: each entry records {@code lastModified} at read time; {@link #get(Path)}
 * re-reads when the on-disk mtime advances. No listener — the server's chunk-save cadence
 * (~every 30s by default) is coarse enough that stat-on-every-get is cheap relative to
 * the read it saves.</p>
 *
 * <p>Thread-safety: all operations go through a {@code synchronized} block on the
 * underlying {@link LinkedHashMap}. Contention on a 4-entry map is negligible compared
 * to the ~1–2ms of downstream inflate+parse each caller does. Two concurrent misses for
 * the same region each read the file once (first-write-wins); acceptable waste vs the
 * complexity of a per-key loading future.</p>
 */
public final class AnvilRegionByteCache {

  /**
   * Max distinct region files retained simultaneously. PR-11 measurement showed a 4-entry cache
   * hit rate of only ~0.31 under ScanTask's 50-in-flight workload — the scan frontier routinely
   * spans 6–10 region files concurrently, thrashing a 4-entry LRU. Bumped to 16 in PR-12 to cover
   * the working set with headroom; steady-state memory ~64 MB (16 × ~4 MB avg per {@code .mca}).
   */
  private static final int CAPACITY = 16;

  private AnvilRegionByteCache() {}

  private static final LinkedHashMap<Path, Entry> CACHE =
      new LinkedHashMap<>(CAPACITY * 2, 0.75f, /* accessOrder = */ true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Entry> eldest) {
          return size() > CAPACITY;
        }
      };

  private record Entry(byte[] bytes, long mtime) {}

  /** In-flight miss dedup: concurrent miss-storms on the same region file wait on one read. */
  private static final HashMap<Path, CompletableFuture<byte[]>> INFLIGHT = new HashMap<>();

  private static final AtomicLong HITS = new AtomicLong();
  private static final AtomicLong MISSES = new AtomicLong();
  private static final AtomicLong STALE = new AtomicLong();
  private static final AtomicLong COALESCED = new AtomicLong();
  /** PR-16 diagnostic: cumulative {@code Files.readAllBytes} wall time for cold misses. */
  private static final AtomicLong COLD_READ_NANOS = new AtomicLong();

  /**
   * Returns the raw bytes of {@code regionFile}, reading from disk on first access or when
   * the file's mtime has advanced since the cached read. Returns {@code null} if the file
   * does not exist or cannot be read.
   *
   * <p>The returned array is shared with other callers and must not be mutated.</p>
   *
   * <p>PR-15: concurrent misses for the same region file are coalesced — the first miss
   * reads disk, all concurrent followers await the same {@code CompletableFuture} and
   * receive the same {@code byte[]}. Prior behavior let 50 concurrent ScanTask probes each
   * {@code Files.readAllBytes} the same 4 MB file in parallel, making the LRU's hit rate
   * meaningless on fresh bins.</p>
   */
  public static byte[] get(Path regionFile) {
    if (regionFile == null) return null;
    long mtime;
    try {
      if (!Files.isRegularFile(regionFile)) return null;
      mtime = Files.getLastModifiedTime(regionFile).toMillis();
    } catch (IOException e) {
      return null;
    }
    CompletableFuture<byte[]> myFuture;
    boolean owner;
    synchronized (CACHE) {
      Entry hit = CACHE.get(regionFile);
      if (hit != null && hit.mtime == mtime) {
        HITS.incrementAndGet();
        return hit.bytes;
      }
      if (hit != null) STALE.incrementAndGet();
      CompletableFuture<byte[]> existing = INFLIGHT.get(regionFile);
      if (existing != null) {
        COALESCED.incrementAndGet();
        myFuture = existing;
        owner = false;
      } else {
        myFuture = new CompletableFuture<>();
        INFLIGHT.put(regionFile, myFuture);
        owner = true;
      }
    }
    if (!owner) {
      try {
        return myFuture.join();
      } catch (Exception e) {
        return null;
      }
    }
    MISSES.incrementAndGet();
    byte[] bytes;
    long readStart = System.nanoTime();
    try {
      bytes = Files.readAllBytes(regionFile);
    } catch (IOException e) {
      bytes = null;
    }
    COLD_READ_NANOS.addAndGet(System.nanoTime() - readStart);
    synchronized (CACHE) {
      if (bytes != null) {
        CACHE.put(regionFile, new Entry(bytes, mtime));
      }
      INFLIGHT.remove(regionFile);
    }
    myFuture.complete(bytes);
    return bytes;
  }

  /** Clears the cache. Test hook. */
  public static void invalidateAll() {
    synchronized (CACHE) {
      CACHE.clear();
    }
  }

  /** Snapshot of hit/miss/stale/coalesced/coldReadNanos counters. PR-11 + PR-16 diagnostic. */
  public record Stats(long hits, long misses, long stale, long coalesced, long coldReadNanos) {
    public long total() { return hits + misses + coalesced; }
    public double hitRate() { return total() == 0 ? 0.0 : (double) (hits + coalesced) / (double) total(); }
    /** Average cold-read wall time in milliseconds; zero when no misses were recorded. */
    public double avgColdMissMs() { return misses == 0 ? 0.0 : (double) coldReadNanos / (double) misses / 1_000_000.0; }
  }

  /** Returns a snapshot of cumulative counters since JVM start or last {@link #resetStats()}. */
  public static Stats stats() {
    return new Stats(HITS.get(), MISSES.get(), STALE.get(), COALESCED.get(), COLD_READ_NANOS.get());
  }

  /** Zeros the counters. Used by ScanTask to report per-window rates. */
  public static void resetStats() {
    HITS.set(0);
    MISSES.set(0);
    STALE.set(0);
    COALESCED.set(0);
    COLD_READ_NANOS.set(0);
  }

  /** Current entry count. Test hook. */
  public static int size() {
    synchronized (CACHE) {
      return CACHE.size();
    }
  }
}

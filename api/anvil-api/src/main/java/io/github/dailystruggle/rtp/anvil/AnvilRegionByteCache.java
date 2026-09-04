package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny LRU cache of raw {@code r.X.Z.mca} region-file bytes keyed by absolute file path.
 *
 * <p>Rationale: ScanTask's in-flight probes (up to {@code Semaphore(50)} per region) all
 * hit the same {@code r.X.Z.mca} sequentially. Without this cache, each probe calls
 * {@link Files#readAllBytes(Path)} independently - 50 × 2-8 MB allocations + syscalls
 * for a file that changes at most on chunk-save. Caching the bytes collapses 1024
 * sibling-chunk probes into a single disk read.</p>
 *
 * <p>Staleness: each entry records {@code lastModified} at read time and the timestamp of
 * its last mtime check; {@link #get(Path)} re-stats an entry at most once per
 * {@link #revalidateIntervalMillis()} window and re-reads when the on-disk mtime advanced.
 * Stat-on-every-get is not viable: a 1024-sibling-probe sweep over one {@code r.X.Z.mca}
 * paid ~67 us per probe on Windows - the entire cost of a warm hit - so the check, not the
 * read, became the throughput ceiling for L3 backlog screening. The server's chunk-save
 * cadence (~30s by default) is far coarser than the default 1s revalidation window, so
 * throttling cannot hide a save for a meaningful period. Set the window to {@code 0} to
 * restore stat-on-every-get.</p>
 *
 * <p>Thread-safety: all operations go through a {@code synchronized} block on the
 * underlying {@link LinkedHashMap}. Contention on a 4-entry map is negligible compared
 * to the ~1-2ms of downstream inflate+parse each caller does. Two concurrent misses for
 * the same region each read the file once (first-write-wins); acceptable waste vs the
 * complexity of a per-key loading future.</p>
 */
public final class AnvilRegionByteCache {

  /**
   * Max distinct region files retained simultaneously. Scan frontier routinely spans
   * 6-10 region files concurrently; capacity 16 covers the working set with headroom;
   * steady-state memory ~64 MB (16 × ~4 MB avg per {@code .mca}).
   */
  private static final int CAPACITY = 16;

  /**
   * Reusable buffer pool avoiding byte-array reallocations on every cache miss or eviction.
   * Reuse is exact-length only: a returned array's {@code length} is the authoritative region
   * file size, since downstream bound checks (AnvilReader's payload guard) treat it as such.
   * An oversized buffer would both weaken that guard and expose stale tail bytes belonging to
   * a previously-pooled larger region file.
   */
  private static final ConcurrentLinkedQueue<byte[]> BUFFER_POOL = new ConcurrentLinkedQueue<>();

  private AnvilRegionByteCache() {}

  private static final LinkedHashMap<Path, Entry> CACHE =
      new LinkedHashMap<>(CAPACITY * 2, 0.75f, /* accessOrder = */ true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Entry> eldest) {
          if (size() > CAPACITY) {
            recycleBuffer(eldest.getValue().bytes);
            return true;
          }
          return false;
        }
      };

  private static void recycleBuffer(byte[] bytes) {
    if (bytes != null && BUFFER_POOL.size() < CAPACITY) {
      BUFFER_POOL.offer(bytes);
    }
  }

  private static byte[] pollOrAllocateBuffer(int len) {
    // Bounded rotation: inspect at most one full pass so a pool of mismatched sizes cannot spin.
    for (int scanned = BUFFER_POOL.size(); scanned > 0; scanned--) {
      byte[] candidate = BUFFER_POOL.poll();
      if (candidate == null) break;
      if (candidate.length == len) return candidate;
      BUFFER_POOL.offer(candidate);
    }
    return new byte[len];
  }

  /**
   * Default mtime re-check window. Bounded staleness: an entry may serve pre-save bytes for
   * at most this long, which is ~1/30th of the default chunk-save cadence.
   */
  private static final long DEFAULT_REVALIDATE_INTERVAL_MILLIS = 1_000L;

  private static volatile long revalidateIntervalNanos =
      DEFAULT_REVALIDATE_INTERVAL_MILLIS * 1_000_000L;

  /**
   * {@code length} is the on-disk file size and always equals {@code bytes.length}.
   * {@code checkedNanos} is the {@link System#nanoTime()} of the last mtime stat.
   */
  private static final class Entry {
    final byte[] bytes;
    final int length;
    final long mtime;
    volatile long checkedNanos;

    Entry(byte[] bytes, int length, long mtime, long checkedNanos) {
      this.bytes = bytes;
      this.length = length;
      this.mtime = mtime;
      this.checkedNanos = checkedNanos;
    }
  }

  /** In-flight miss dedup: concurrent miss-storms on the same region file wait on one read. */
  private static final HashMap<Path, CompletableFuture<byte[]>> INFLIGHT = new HashMap<>();

  private static final AtomicLong HITS = new AtomicLong();
  private static final AtomicLong MISSES = new AtomicLong();
  private static final AtomicLong STALE = new AtomicLong();
  private static final AtomicLong COALESCED = new AtomicLong();
  /** Diagnostic: hits served without any stat syscall thanks to the revalidation window. */
  private static final AtomicLong STAT_SKIPS = new AtomicLong();
  /** Diagnostic: cumulative {@code Files.readAllBytes} wall time for cold misses. */
  private static final AtomicLong COLD_READ_NANOS = new AtomicLong();

  /**
   * Returns the raw bytes of {@code regionFile}, reading from disk on first access or when
   * the file's mtime has advanced since the cached read. Returns {@code null} if the file
   * does not exist or cannot be read.
   *
   * <p>The returned array is shared with other callers and must not be mutated.</p>
   *
   * <p>Concurrent misses for the same region file are coalesced: the first miss
   * reads disk, all concurrent followers await the same {@code CompletableFuture} and
   * receive the same {@code byte[]}.</p>
   */
  public static byte[] get(Path regionFile) {
    if (regionFile == null) return null;
    long now = System.nanoTime();
    long window = revalidateIntervalNanos;
    Entry cached;
    synchronized (CACHE) {
      cached = CACHE.get(regionFile);
    }
    // Fast path: recently validated entry needs no syscall at all.
    if (cached != null && window > 0L && (now - cached.checkedNanos) < window) {
      HITS.incrementAndGet();
      STAT_SKIPS.incrementAndGet();
      return cached.bytes;
    }
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
        hit.checkedNanos = System.nanoTime();
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
    try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
      long fileSize = channel.size();
      if (fileSize > Integer.MAX_VALUE || fileSize < 0) {
        bytes = null;
      } else {
        int len = (int) fileSize;
        bytes = pollOrAllocateBuffer(len);
        ByteBuffer bb = ByteBuffer.wrap(bytes, 0, len);
        while (bb.hasRemaining()) {
          if (channel.read(bb) < 0) break;
        }
      }
    } catch (IOException e) {
      bytes = null;
    }
    COLD_READ_NANOS.addAndGet(System.nanoTime() - readStart);
    synchronized (CACHE) {
      if (bytes != null) {
        CACHE.put(regionFile, new Entry(bytes, bytes.length, mtime, System.nanoTime()));
      }
      INFLIGHT.remove(regionFile);
    }
    myFuture.complete(bytes);
    return bytes;
  }

  /**
   * Current mtime re-check window in milliseconds; {@code 0} means stat on every
   * {@link #get(Path)}.
   */
  public static long revalidateIntervalMillis() {
    return revalidateIntervalNanos / 1_000_000L;
  }

  /**
   * Sets the mtime re-check window. {@code 0} restores stat-on-every-get; negative values
   * are clamped to {@code 0}. Configuration/test hook.
   */
  public static void setRevalidateIntervalMillis(long millis) {
    revalidateIntervalNanos = Math.max(0L, millis) * 1_000_000L;
  }

  /** Clears the cache. Test hook. */
  public static void invalidateAll() {
    synchronized (CACHE) {
      for (Entry e : CACHE.values()) {
        recycleBuffer(e.bytes);
      }
      CACHE.clear();
    }
  }

  /** Clears both the cache and the reusable buffer pool. Test hook. */
  public static void resetAll() {
    synchronized (CACHE) {
      CACHE.clear();
      BUFFER_POOL.clear();
    }
  }

  /**
   * Authoritative byte length of the cached region file, or {@code -1} when not cached.
   * Diagnostic hook; equals {@code get(regionFile).length} for a live entry.
   */
  public static int cachedLength(Path regionFile) {
    if (regionFile == null) return -1;
    synchronized (CACHE) {
      Entry e = CACHE.get(regionFile);
      return e == null ? -1 : e.length;
    }
  }

  /** Current reusable buffer pool size. Diagnostic/test hook. */
  public static int bufferPoolSize() {
    return BUFFER_POOL.size();
  }

  /** Snapshot of hit/miss/stale/coalesced/coldReadNanos counters. Diagnostic metrics. */
  public record Stats(long hits, long misses, long stale, long coalesced, long coldReadNanos,
                      long statSkips) {
    public long total() { return hits + misses + coalesced; }
    public double hitRate() { return total() == 0 ? 0.0 : (double) (hits + coalesced) / (double) total(); }
    /** Average cold-read wall time in milliseconds; zero when no misses were recorded. */
    public double avgColdMissMs() { return misses == 0 ? 0.0 : (double) coldReadNanos / (double) misses / 1_000_000.0; }
  }

  /** Returns a snapshot of cumulative counters since JVM start or last {@link #resetStats()}. */
  public static Stats stats() {
    return new Stats(HITS.get(), MISSES.get(), STALE.get(), COALESCED.get(), COLD_READ_NANOS.get(),
        STAT_SKIPS.get());
  }

  /** Zeros the counters. Used by ScanTask to report per-window rates. */
  public static void resetStats() {
    HITS.set(0);
    MISSES.set(0);
    STALE.set(0);
    COALESCED.set(0);
    COLD_READ_NANOS.set(0);
    STAT_SKIPS.set(0);
  }

  /** Current entry count. Test hook. */
  public static int size() {
    synchronized (CACHE) {
      return CACHE.size();
    }
  }
}

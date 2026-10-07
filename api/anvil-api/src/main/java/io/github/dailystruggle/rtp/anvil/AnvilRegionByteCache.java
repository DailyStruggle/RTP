package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny LRU cache of raw whole-region-file bytes keyed by absolute file path.
 *
 * <p>Scope: whole-file consumers only - addon-registered region formats (decoded from the whole
 * file) and full-region sweeps such as biome extraction. Single-chunk {@code .mca} probes read just the
 * location table and the chunk's sectors via {@link AnvilSectorReader} and never populate this
 * cache; under random selection a whole-file cache misses on almost every probe.</p>
 *
 * <p>Staleness: each entry records {@code lastModified} at read time and the timestamp of
 * its last mtime check; lookups re-stat an entry at most once per
 * {@link #revalidateIntervalMillis()} window and re-read when the on-disk mtime advanced.
 * Stat-on-every-get paid ~67 us per probe on Windows - the entire cost of a warm hit. The
 * server's chunk-save cadence (~30s) is far coarser than the default 1s window. Set the window
 * to {@code 0} to restore stat-on-every-get.</p>
 *
 * <p>Buffer reuse: buffers are pooled and reused when at least as large as the file (bounded
 * slack), so a buffer's {@code length} is NOT the file size. {@link #acquire} returns a
 * {@link Lease} carrying the authoritative {@link Lease#length()}; readers bound every access by
 * it (length-aware {@link RegionFileReader} overloads), so stale tail bytes are never read. A
 * buffer returns to the pool only once its entry is evicted AND every lease is closed, so a reader
 * can never observe a buffer being overwritten by another file. Arrays handed out through the
 * legacy {@link #get}/{@link #peek} have no release signal and are never recycled.</p>
 *
 * <p>Thread-safety: map, ref counts and pool mutate under one monitor; only O(1) work runs under
 * it. Concurrent misses for one file coalesce onto a single read.</p>
 */
public final class AnvilRegionByteCache {

  /** Max distinct region files retained simultaneously. */
  private static final int CAPACITY = 16;

  /** Reuse slack: a pooled buffer serves a file when {@code len <= buf <= len + slack(len)}. */
  private static final int MIN_SLACK_BYTES = 1 << 20;

  /** New buffers at or above this size are rounded up to it, so near-sized files share buffers. */
  private static final int ALLOC_GRANULE = 1 << 20;

  /** Pool bounds: idle buffers retained for reuse. */
  private static final int POOL_MAX_BUFFERS = CAPACITY;
  private static final long POOL_MAX_BYTES = 64L << 20;

  /** Maximum permitted region file size (64 MiB) to guard against unbounded allocations. */
  public static final long MAX_REGION_FILE_BYTES = 64L << 20;

  private static final Object LOCK = new Object();

  private static long currentCachedBytes;
  private static long pooledBytes;

  private static final ArrayDeque<byte[]> BUFFER_POOL = new ArrayDeque<>();

  private AnvilRegionByteCache() {}

  private static final LinkedHashMap<Path, Entry> CACHE =
      new LinkedHashMap<>(CAPACITY * 2, 0.75f, /* accessOrder = */ true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Entry> eldest) {
          if (size() > CAPACITY) {
            retireLocked(eldest.getValue());
            return true;
          }
          return false;
        }
      };

  /**
   * Default mtime re-check window. Bounded staleness: an entry may serve pre-save bytes for
   * at most this long, which is ~1/30th of the default chunk-save cadence.
   */
  private static final long DEFAULT_REVALIDATE_INTERVAL_MILLIS = 1_000L;

  private static volatile long revalidateIntervalNanos =
      DEFAULT_REVALIDATE_INTERVAL_MILLIS * 1_000_000L;

  /**
   * {@code length} is the on-disk file size; {@code buffer.length >= length}. {@code refs},
   * {@code retired}, {@code recycled} and {@code escaped} are guarded by {@link #LOCK}.
   */
  private static final class Entry {
    final byte[] buffer;
    final int length;
    final long mtime;
    volatile long checkedNanos;
    int refs;
    boolean retired;
    boolean recycled;
    /** Handed out without a lease (legacy API); must never return to the pool. */
    boolean escaped;

    Entry(byte[] buffer, int length, long mtime, long checkedNanos) {
      this.buffer = buffer;
      this.length = length;
      this.mtime = mtime;
      this.checkedNanos = checkedNanos;
    }
  }

  /**
   * Read lease over a cached region file. {@link #buffer()} may be longer than the file: only
   * {@code buffer()[0, length())} is valid. Must be closed; the buffer must not be used or
   * retained after {@link #close()} and must never be mutated.
   */
  public static final class Lease implements AutoCloseable {
    private final Entry entry;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(Entry entry) {
      this.entry = entry;
    }

    /** Shared backing array; valid bytes are {@code [0, length())}. */
    public byte[] buffer() {
      return entry.buffer;
    }

    /** Authoritative on-disk file length. */
    public int length() {
      return entry.length;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (LOCK) {
        entry.refs--;
        if (entry.refs == 0 && entry.retired) recycleLocked(entry);
      }
    }
  }

  /** In-flight miss dedup: concurrent miss-storms on the same region file wait on one read. */
  private static final HashMap<Path, CompletableFuture<Entry>> INFLIGHT = new HashMap<>();

  private static final AtomicLong HITS = new AtomicLong();
  private static final AtomicLong MISSES = new AtomicLong();
  private static final AtomicLong STALE = new AtomicLong();
  private static final AtomicLong COALESCED = new AtomicLong();
  /** Diagnostic: hits served without any stat syscall thanks to the revalidation window. */
  private static final AtomicLong STAT_SKIPS = new AtomicLong();
  /** Diagnostic: cumulative cold-read wall time. */
  private static final AtomicLong COLD_READ_NANOS = new AtomicLong();
  /** Diagnostic: cold misses served on a recycled buffer instead of a fresh allocation. */
  private static final AtomicLong POOL_REUSES = new AtomicLong();

  /**
   * Leases the bytes of {@code regionFile}, reading from disk on first access or when the
   * file's mtime has advanced. Returns {@code null} if the file does not exist or cannot be
   * read. Blocking I/O: off the tick thread only (S-005).
   */
  public static Lease acquire(Path regionFile) {
    if (regionFile == null) return null;
    for (int attempt = 0; attempt < 4; attempt++) {
      Entry e = lookupOrLoad(regionFile);
      if (e == null) return null;
      synchronized (LOCK) {
        // A coalesced follower can lose its entry to eviction between load and retain.
        if (e.recycled) continue;
        e.refs++;
      }
      return new Lease(e);
    }
    return null;
  }

  /**
   * Non-loading lease: an entry already resident whose recorded mtime equals
   * {@code currentMtimeMillis}, otherwise {@code null}. Never reads the file, never populates
   * the cache, does not touch hit/miss counters.
   */
  public static Lease acquireIfCached(Path regionFile, long currentMtimeMillis) {
    if (regionFile == null) return null;
    synchronized (LOCK) {
      Entry e = CACHE.get(regionFile);
      if (e == null || e.mtime != currentMtimeMillis || e.recycled) return null;
      e.refs++;
      return new Lease(e);
    }
  }

  /**
   * Legacy whole-file accessor: returns an exact-length shared array, or {@code null} if the file
   * does not exist or cannot be read. The array must not be mutated. Prefer {@link #acquire},
   * which permits buffer reuse; arrays returned here are never recycled.
   */
  public static byte[] get(Path regionFile) {
    if (regionFile == null) return null;
    Entry e = lookupOrLoad(regionFile);
    if (e == null) return null;
    return escapeExact(regionFile, e);
  }

  /**
   * Legacy non-loading lookup: the cached bytes when resident with a matching mtime, otherwise
   * {@code null}. Prefer {@link #acquireIfCached}. The returned array is shared, exact-length,
   * never recycled, and must not be mutated.
   */
  public static byte[] peek(Path regionFile, long currentMtimeMillis) {
    if (regionFile == null) return null;
    Entry e;
    synchronized (LOCK) {
      e = CACHE.get(regionFile);
      if (e == null || e.mtime != currentMtimeMillis || e.recycled) return null;
    }
    return escapeExact(regionFile, e);
  }

  /**
   * Marks {@code e} escaped and returns an exact-length array for it. An oversized pooled buffer
   * is replaced in the map by an exact copy so repeat legacy calls return the same array.
   */
  private static byte[] escapeExact(Path regionFile, Entry e) {
    synchronized (LOCK) {
      if (!e.recycled && e.buffer.length == e.length) {
        e.escaped = true;
        return e.buffer;
      }
      Entry current = CACHE.get(regionFile);
      if (current != null && current.escaped && current.mtime == e.mtime) {
        return current.buffer;
      }
      if (e.recycled) return null;
      Entry exact = new Entry(Arrays.copyOf(e.buffer, e.length), e.length, e.mtime, e.checkedNanos);
      exact.escaped = true;
      if (current == e) {
        CACHE.put(regionFile, exact);
        currentCachedBytes += exact.length - (long) e.length;
        retireLocked(e);
      }
      return exact.buffer;
    }
  }

  private static Entry lookupOrLoad(Path regionFile) {
    long now = System.nanoTime();
    long window = revalidateIntervalNanos;
    Entry cached;
    synchronized (LOCK) {
      cached = CACHE.get(regionFile);
    }
    // Fast path: recently validated entry needs no syscall at all.
    if (cached != null && window > 0L && (now - cached.checkedNanos) < window) {
      HITS.incrementAndGet();
      STAT_SKIPS.incrementAndGet();
      return cached;
    }
    long mtime;
    try {
      // One stat: a missing file throws; a directory fails at FileChannel.open below.
      mtime = Files.getLastModifiedTime(regionFile).toMillis();
    } catch (IOException | SecurityException e) {
      return null;
    }
    CompletableFuture<Entry> myFuture;
    boolean owner;
    synchronized (LOCK) {
      Entry hit = CACHE.get(regionFile);
      if (hit != null && hit.mtime == mtime) {
        hit.checkedNanos = System.nanoTime();
        HITS.incrementAndGet();
        return hit;
      }
      if (hit != null) STALE.incrementAndGet();
      CompletableFuture<Entry> existing = INFLIGHT.get(regionFile);
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
    Entry loaded = null;
    long readStart = System.nanoTime();
    try {
      try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
        long fileSize = channel.size();
        if (fileSize >= 0 && fileSize <= MAX_REGION_FILE_BYTES) {
          int len = (int) fileSize;
          byte[] buf = pollOrAllocateBuffer(len);
          ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
          while (bb.hasRemaining()) {
            if (channel.read(bb) < 0) break;
          }
          // A file truncated mid-read keeps only the bytes actually present.
          loaded = new Entry(buf, bb.position(), mtime, System.nanoTime());
        }
      } catch (IOException | SecurityException e) {
        loaded = null;
      }
      long readNanos = System.nanoTime() - readStart;
      COLD_READ_NANOS.addAndGet(readNanos);
      // Only cold misses reach here, so this is the one place in the read path that observes the
      // device rather than the cache. Feeds the memory-versus-storage cost model; adds no I/O.
      StorageLatencyProbe.record(readNanos, loaded == null ? 0L : loaded.length);
    } finally {
      synchronized (LOCK) {
        if (loaded != null) {
          Entry old = CACHE.put(regionFile, loaded);
          if (old != null) {
            currentCachedBytes -= old.length;
            retireLocked(old);
          }
          currentCachedBytes += loaded.length;
          long budget = AnvilIoPool.getMemoryBudgetBytes();
          while (CACHE.size() > 1 && currentCachedBytes > budget) {
            var it = CACHE.entrySet().iterator();
            if (!it.hasNext()) break;
            Entry eldest = it.next().getValue();
            it.remove();
            currentCachedBytes -= eldest.length;
            retireLocked(eldest);
          }
        }
        INFLIGHT.remove(regionFile);
      }
      myFuture.complete(loaded);
    }
    return loaded;
  }

  private static int slack(int len) {
    return Math.max(MIN_SLACK_BYTES, len >>> 2);
  }

  private static byte[] pollOrAllocateBuffer(int len) {
    synchronized (LOCK) {
      long limit = (long) len + slack(len);
      byte[] best = null;
      for (byte[] candidate : BUFFER_POOL) {
        if (candidate.length >= len && candidate.length <= limit
            && (best == null || candidate.length < best.length)) {
          best = candidate;
        }
      }
      if (best != null) {
        BUFFER_POOL.remove(best);
        pooledBytes -= best.length;
        POOL_REUSES.incrementAndGet();
        return best;
      }
    }
    int alloc = len >= ALLOC_GRANULE
        ? (int) (((long) len + ALLOC_GRANULE - 1) / ALLOC_GRANULE * ALLOC_GRANULE)
        : len;
    return new byte[alloc];
  }

  /** Entry left the map: recycle now if unreferenced, else on the last lease close. */
  private static void retireLocked(Entry e) {
    e.retired = true;
    if (e.refs == 0) recycleLocked(e);
  }

  private static void recycleLocked(Entry e) {
    if (e.recycled) return;
    e.recycled = true;
    if (e.escaped) return;
    byte[] buf = e.buffer;
    if (BUFFER_POOL.size() >= POOL_MAX_BUFFERS || pooledBytes + buf.length > POOL_MAX_BYTES) return;
    BUFFER_POOL.offer(buf);
    pooledBytes += buf.length;
  }

  /**
   * Current mtime re-check window in milliseconds; {@code 0} means stat on every lookup.
   * Shared by {@link AnvilRegionHeaderCache} and {@link RegionFileResolver#resolveExisting}.
   */
  public static long revalidateIntervalMillis() {
    return revalidateIntervalNanos / 1_000_000L;
  }

  /** Current mtime re-check window in nanoseconds. */
  static long revalidateIntervalNanos() {
    return revalidateIntervalNanos;
  }

  /**
   * Sets the mtime re-check window. {@code 0} restores stat-on-every-get; negative values
   * are clamped to {@code 0}. Configuration/test hook.
   */
  public static void setRevalidateIntervalMillis(long millis) {
    revalidateIntervalNanos = Math.max(0L, millis) * 1_000_000L;
  }

  /** Clears the cache; unreferenced lease-only buffers return to the pool. Test hook. */
  public static void invalidateAll() {
    synchronized (LOCK) {
      for (Entry e : CACHE.values()) {
        retireLocked(e);
      }
      CACHE.clear();
      currentCachedBytes = 0;
    }
  }

  /** Clears both the cache and the reusable buffer pool. Test hook. */
  public static void resetAll() {
    synchronized (LOCK) {
      for (Entry e : CACHE.values()) {
        e.retired = true;
        e.recycled = true;
      }
      CACHE.clear();
      BUFFER_POOL.clear();
      pooledBytes = 0;
      currentCachedBytes = 0;
    }
  }

  /** Total file bytes currently retained in cache. Diagnostic hook. */
  public static long cachedBytes() {
    synchronized (LOCK) {
      return currentCachedBytes;
    }
  }

  /** Authoritative byte length of the cached region file, or {@code -1} when not cached. */
  public static int cachedLength(Path regionFile) {
    if (regionFile == null) return -1;
    synchronized (LOCK) {
      Entry e = CACHE.get(regionFile);
      return e == null ? -1 : e.length;
    }
  }

  /** Current reusable buffer pool size. Diagnostic/test hook. */
  public static int bufferPoolSize() {
    synchronized (LOCK) {
      return BUFFER_POOL.size();
    }
  }

  /** Snapshot of hit/miss/stale/coalesced/coldReadNanos counters. Diagnostic metrics. */
  public record Stats(long hits, long misses, long stale, long coalesced, long coldReadNanos,
                      long statSkips, long poolReuses) {
    /** Pre-reuse-counter shape; {@code poolReuses} is zero. */
    public Stats(long hits, long misses, long stale, long coalesced, long coldReadNanos, long statSkips) {
      this(hits, misses, stale, coalesced, coldReadNanos, statSkips, 0L);
    }

    public long total() { return hits + misses + coalesced; }
    public double hitRate() { return total() == 0 ? 0.0 : (double) (hits + coalesced) / (double) total(); }
    /** Average cold-read wall time in milliseconds; zero when no misses were recorded. */
    public double avgColdMissMs() { return misses == 0 ? 0.0 : (double) coldReadNanos / (double) misses / 1_000_000.0; }
  }

  /** Returns a snapshot of cumulative counters since JVM start or last {@link #resetStats()}. */
  public static Stats stats() {
    return new Stats(HITS.get(), MISSES.get(), STALE.get(), COALESCED.get(), COLD_READ_NANOS.get(),
        STAT_SKIPS.get(), POOL_REUSES.get());
  }

  /** Zeros the counters. Used by ScanTask to report per-window rates. */
  public static void resetStats() {
    HITS.set(0);
    MISSES.set(0);
    STALE.set(0);
    COALESCED.set(0);
    COLD_READ_NANOS.set(0);
    STAT_SKIPS.set(0);
    POOL_REUSES.set(0);
  }

  /** Current entry count. Test hook. */
  public static int size() {
    synchronized (LOCK) {
      return CACHE.size();
    }
  }
}

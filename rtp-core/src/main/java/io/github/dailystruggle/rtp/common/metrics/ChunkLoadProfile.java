package io.github.dailystruggle.rtp.common.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-global, always-on accumulator of the wall-clock cost of loading a
 * single chunk through the server's chunk API, split by whether the chunk was
 * already <em>generated</em> on disk (loaded from the region file) or had to be
 * <em>generated</em> by the server (the expensive path).
 *
 * <p>Loading a chunk is not free, but the plugin never observes the exact
 * instant the platform <em>begins</em> the load after the request is issued -
 * only the moment the returned future completes. Any single observed duration
 * is therefore {@code dispatch_wait + actual_load}. The smallest duration ever
 * observed is the sample least polluted by queue contention, so it is taken as
 * the assumed shortest time to start and complete loading a chunk on this
 * server (the "floor"). The running minimum is the headline figure; the total
 * and count are retained so a mean (per-RTP cost) can be derived.
 *
 * <p>The generated / ungenerated split matters because RTP-ing into ungenerated
 * terrain forces the server to generate the chunk - far more expensive than
 * reading one that already exists. Tracking the two separately lets operators
 * see how much of the plugin's cost comes from generation (which pre-generating
 * the world removes) versus loading.
 *
 * <p>Only genuine live chunk loads feed this profile: the platform adapters
 * record into it from their live-load path ({@code loadChunkFuture} /
 * {@code loadLiveChunk}), <em>not</em> from the ADR-016 anvil pre-filter
 * short-circuit (which republishes a cached view without a real load) nor from
 * an already-resident chunk. Counting those near-zero non-loads would peg the
 * floor at ~0 and destroy its meaning.
 *
 * <p>Wait-free: the totals are {@link LongAdder} and the minimum is an
 * {@link AtomicLong} updated by CAS. No locking, no tick-thread blocking. Reads
 * are eventually-consistent snapshots.
 *
 * <p>Lifecycle: a single {@link #GLOBAL} instance lives for the process.
 * {@link #reset()} exists for tests; it is not called on {@code /reload}.
 */
public final class ChunkLoadProfile {

  /** Shared process-wide instance fed by the platform live-load paths. */
  public static final ChunkLoadProfile GLOBAL = new ChunkLoadProfile();

  /**
   * One generated/ungenerated sub-bucket: cumulative total/count plus a
   * CAS-updated running minimum. Wait-free.
   */
  private static final class Bucket {
    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder loadCount = new LongAdder();
    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);

    void record(long nanos) {
      totalNanos.add(nanos);
      loadCount.increment();
      long prev = minNanos.get();
      while (nanos < prev && !minNanos.compareAndSet(prev, nanos)) {
        prev = minNanos.get();
      }
    }

    long min() {
      long m = minNanos.get();
      return (m == Long.MAX_VALUE) ? -1L : m;
    }

    long total() {
      return totalNanos.sum();
    }

    long count() {
      return loadCount.sum();
    }

    void reset() {
      totalNanos.reset();
      loadCount.reset();
      minNanos.set(Long.MAX_VALUE);
    }
  }

  private final Bucket generated = new Bucket();
  private final Bucket ungenerated = new Bucket();

  /**
   * Records one successful single-chunk live-load of {@code nanos} wall-clock
   * duration, updating the matching ({@code generated}) running minimum.
   * Non-positive input is ignored (a zero/negative reading is a clock artefact,
   * not a meaningful load floor).
   *
   * @param generated {@code true} when the chunk was already generated on disk
   *                  (loaded from the region file); {@code false} when the load
   *                  triggered generation of a previously-ungenerated chunk.
   */
  public void record(boolean generated, long nanos) {
    if (nanos <= 0L) return;
    (generated ? this.generated : this.ungenerated).record(nanos);
  }

  /**
   * Smallest single-chunk live-load duration observed for the given
   * generated/ungenerated class since process start (or last {@link #reset()}),
   * in nanoseconds; {@code -1L} when no such load has been recorded yet.
   */
  public long minNanos(boolean generated) {
    return (generated ? this.generated : this.ungenerated).min();
  }

  /** Cumulative nanoseconds spent in recorded live chunk loads of the given class. */
  public long totalNanos(boolean generated) {
    return (generated ? this.generated : this.ungenerated).total();
  }

  /** Cumulative count of recorded live chunk loads of the given class. */
  public long loadCount(boolean generated) {
    return (generated ? this.generated : this.ungenerated).count();
  }

  /**
   * Smallest single-chunk live-load duration across both classes, in
   * nanoseconds; {@code -1L} when no load has been recorded yet.
   */
  public long minNanos() {
    long g = generated.min();
    long u = ungenerated.min();
    if (g < 0L) return u;
    if (u < 0L) return g;
    return Math.min(g, u);
  }

  /** Cumulative nanoseconds across both classes. */
  public long totalNanos() {
    return generated.total() + ungenerated.total();
  }

  /** Cumulative count across both classes. */
  public long loadCount() {
    return generated.count() + ungenerated.count();
  }

  /** Resets all counters to their empty state. For tests only. */
  public void reset() {
    generated.reset();
    ungenerated.reset();
  }
}

package io.github.dailystruggle.rtp.common.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-global accumulator for chunk load wall-clock duration, tracking
 * generated vs ungenerated chunk load costs and running minimums.
 */
public final class ChunkLoadProfile {

  /** Shared process-wide instance fed by the platform live-load paths. */
  public static final ChunkLoadProfile GLOBAL = new ChunkLoadProfile();

  /** Constructs an empty profile. Use {@link #GLOBAL} for the shared instance. */
  public ChunkLoadProfile() {}

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
   * Records a chunk load duration in nanoseconds.
   *
   * @param generated true if chunk was already on disk, false if generated on demand
   * @param nanos wall-clock load duration in nanoseconds (non-positive values ignored)
   */
  public void record(boolean generated, long nanos) {
    if (nanos <= 0L) return;
    (generated ? this.generated : this.ungenerated).record(nanos);
  }

  /**
   * Smallest single-chunk live-load duration observed for the given
   * generated/ungenerated class since process start (or last {@link #reset()}),
   * in nanoseconds; {@code -1L} when no such load has been recorded yet.
   *
   * @param generated {@code true} for the pre-generated bucket; {@code false} for the ungenerated bucket
   * @return minimum observed load duration in nanoseconds, or {@code -1L} if no load recorded
   */
  public long minNanos(boolean generated) {
    return (generated ? this.generated : this.ungenerated).min();
  }

  /**
   * Returns cumulative nanoseconds spent in recorded live chunk loads of the given class.
   *
   * @param generated {@code true} for the pre-generated bucket; {@code false} for the ungenerated bucket
   * @return cumulative load time in nanoseconds
   */
  public long totalNanos(boolean generated) {
    return (generated ? this.generated : this.ungenerated).total();
  }

  /**
   * Returns the cumulative count of recorded live chunk loads of the given class.
   *
   * @param generated {@code true} for the pre-generated bucket; {@code false} for the ungenerated bucket
   * @return cumulative load count
   */
  public long loadCount(boolean generated) {
    return (generated ? this.generated : this.ungenerated).count();
  }

  /**
   * Smallest single-chunk live-load duration across both classes, in
   * nanoseconds; {@code -1L} when no load has been recorded yet.
   *
   * @return minimum observed load duration in nanoseconds across both buckets, or {@code -1L}
   */
  public long minNanos() {
    long g = generated.min();
    long u = ungenerated.min();
    if (g < 0L) return u;
    if (u < 0L) return g;
    return Math.min(g, u);
  }

  /**
   * Returns cumulative nanoseconds across both classes.
   *
   * @return total load time in nanoseconds
   */
  public long totalNanos() {
    return generated.total() + ungenerated.total();
  }

  /**
   * Returns cumulative count across both classes.
   *
   * @return total load count
   */
  public long loadCount() {
    return generated.count() + ungenerated.count();
  }

  /** Resets all counters to their empty state. For tests only. */
  public void reset() {
    generated.reset();
    ungenerated.reset();
  }
}

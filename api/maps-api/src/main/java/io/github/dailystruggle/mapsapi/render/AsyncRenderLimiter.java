package io.github.dailystruggle.mapsapi.render;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bulkhead concurrency limiter for web map raster generation (ADR-086).
 *
 * <p>Restricts concurrent off-tick tile render tasks to avoid JVM heap exhaustion and GC churn
 * from transient image buffer allocations under rapid browser panning or scraper traffic.
 */
public final class AsyncRenderLimiter {

  /** Default permit derivation: max(1, min(4, availableProcessors / 4)). */
  public static final int DEFAULT_PERMITS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));

  private final Semaphore semaphore;
  private final int maxPermits;

  public AsyncRenderLimiter() {
    this(DEFAULT_PERMITS);
  }

  public AsyncRenderLimiter(int permits) {
    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be positive: " + permits);
    }
    this.maxPermits = permits;
    this.semaphore = new Semaphore(permits, true);
  }

  /**
   * Attempts to acquire an execution permit within {@code timeoutMs}.
   *
   * @param timeoutMs maximum wait time in milliseconds
   * @return true if permit was acquired, false if timed out
   */
  public boolean tryAcquire(long timeoutMs) {
    if (timeoutMs <= 0) {
      return semaphore.tryAcquire();
    }
    try {
      return semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Releases a previously acquired permit.
   */
  public void release() {
    semaphore.release();
  }

  /**
   * Returns current available permits.
   *
   * @return available permit count
   */
  public int availablePermits() {
    return semaphore.availablePermits();
  }

  /**
   * Returns maximum configured permits.
   *
   * @return max permits
   */
  public int maxPermits() {
    return maxPermits;
  }
}

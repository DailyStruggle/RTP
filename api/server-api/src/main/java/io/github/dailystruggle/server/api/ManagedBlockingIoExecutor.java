package io.github.dailystruggle.server.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle-managed blocking-I/O {@link Executor} owned by a {@link ServerAccessor} runtime.
 *
 * <p>Lazily allocates a bounded daemon fixed thread pool on first submission so lightweight or
 * non-I/O runtimes spawn zero threads. Calling {@link #shutdownAndDrain(Duration)} atomically
 * transitions the executor into the closed state, rejects subsequent submissions with
 * {@link RejectedExecutionException} (never silently executing blocking work inline on the caller
 * thread, {@code S-004}/{@code S-005}), and awaits completion of in-flight tasks up to the caller's
 * timeout so disk I/O cannot race with world unload or server teardown ({@code S-002}).
 *
 * @governedBy ADR-115
 * @governedBy ADR-116
 * @trace REQ-RTP-S-002
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class ManagedBlockingIoExecutor implements Executor, AutoCloseable {

  private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

  private final String threadNamePrefix;
  private final int threadCount;
  private final int threadPriority;
  private final AtomicInteger threadSequence = new AtomicInteger(1);
  private final Object lifecycleLock = new Object();

  private volatile boolean closed;
  private volatile ExecutorService pool;

  /**
   * Creates a managed blocking-I/O executor with default bounded parallelism
   * ({@code clamp(2 * availableProcessors, 4, 32)}) and {@code Thread.NORM_PRIORITY - 1}.
   *
   * @param threadNamePrefix non-blank prefix for worker thread names
   */
  public ManagedBlockingIoExecutor(String threadNamePrefix) {
    this(
        threadNamePrefix,
        Math.max(4, Math.min(32, 2 * Runtime.getRuntime().availableProcessors())),
        Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
  }

  /**
   * Creates a managed blocking-I/O executor with explicit thread count and priority.
   *
   * @param threadNamePrefix non-blank prefix for worker thread names
   * @param threadCount number of worker threads (clamped to {@code >= 1})
   * @param threadPriority worker thread priority (clamped to
   *     {@code [Thread.MIN_PRIORITY, Thread.MAX_PRIORITY]})
   */
  public ManagedBlockingIoExecutor(
      String threadNamePrefix, int threadCount, int threadPriority) {
    Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
    if (threadNamePrefix.isBlank()) {
      throw new IllegalArgumentException("threadNamePrefix must not be blank");
    }
    this.threadNamePrefix = threadNamePrefix;
    this.threadCount = Math.max(1, threadCount);
    this.threadPriority =
        Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, threadPriority));
  }

  @Override
  public void execute(Runnable command) {
    Objects.requireNonNull(command, "command");
    ExecutorService active = ensurePool();
    active.execute(command);
  }

  /** Returns {@code true} once {@link #shutdownAndDrain(Duration)} or {@link #close()} ran. */
  public boolean isShutdown() {
    return closed;
  }

  /**
   * Rejects new submissions and awaits completion of in-flight tasks up to {@code timeout}. If
   * tasks do not finish in time, interrupts remaining workers via {@link
   * ExecutorService#shutdownNow()} and waits briefly for cancellation.
   *
   * @param timeout maximum duration to wait; {@code null} or non-positive skips graceful wait
   * @return {@code true} if all tasks terminated cleanly, {@code false} if timed out or interrupted
   */
  public boolean shutdownAndDrain(Duration timeout) {
    ExecutorService toDrain;
    synchronized (lifecycleLock) {
      closed = true;
      toDrain = pool;
      pool = null;
    }
    if (toDrain == null) {
      return true;
    }
    toDrain.shutdown();
    long timeoutNanos =
        (timeout == null || timeout.isNegative() || timeout.isZero()) ? 0L : timeout.toNanos();
    try {
      if (timeoutNanos > 0L && toDrain.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS)) {
        return true;
      }
      toDrain.shutdownNow();
      return toDrain.awaitTermination(500L, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ie) {
      toDrain.shutdownNow();
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Re-opens the executor after a prior {@link #shutdownAndDrain(Duration)} so server/plugin
   * reloads can resume blocking I/O without leaking old worker threads.
   */
  public void reopen() {
    synchronized (lifecycleLock) {
      closed = false;
    }
  }

  @Override
  public void close() {
    shutdownAndDrain(DEFAULT_CLOSE_TIMEOUT);
  }

  private ExecutorService ensurePool() {
    if (closed) {
      throw new RejectedExecutionException(threadNamePrefix + " blocking I/O executor is shut down");
    }
    ExecutorService current = pool;
    if (current != null && !current.isShutdown()) {
      return current;
    }
    synchronized (lifecycleLock) {
      if (closed) {
        throw new RejectedExecutionException(
            threadNamePrefix + " blocking I/O executor is shut down");
      }
      if (pool == null || pool.isShutdown()) {
        ThreadFactory factory =
            runnable -> {
              Thread t = new Thread(runnable, threadNamePrefix + "-IO-" + threadSequence.getAndIncrement());
              t.setDaemon(true);
              t.setPriority(threadPriority);
              return t;
            };
        pool = Executors.newFixedThreadPool(threadCount, factory);
      }
      return pool;
    }
  }
}

package io.github.dailystruggle.rtp.common.tasks;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract class RTPTaskPipe {
  protected final ConcurrentLinkedQueue<Runnable> runnables = new ConcurrentLinkedQueue<>();
  protected final Semaphore accessGuard = new Semaphore(1);
  protected long avgTime = TimeUnit.MILLISECONDS.toNanos(50);
  //    protected long avgTime = Long.MAX_VALUE;
  protected boolean stop = false;

  /**
   * @return true if the pipeline completed all tasks in the queue.
   * Returns false if the pipeline yielded because it hit its time/count limit.
   */
  public abstract boolean execute();

  /**
   * @param availableTime maximum time allowed for execution in nanoseconds
   * @return true if the pipeline completed all tasks in the queue.
   * Returns false if the pipeline yielded because it hit its time/count limit.
   */
  public abstract boolean execute(long availableTime);

  /**
   * Executes due tasks against {@code availableTime} sharing a pulse/tick-wide {@code sharedRanAny}
   * progress flag across background stages (ADR-087 Section 4.3 items 3 & 4, Invariants I2 & I5).
   *
   * @param availableTime remaining nanoseconds before the shared deadline
   * @param sharedRanAny  pulse/tick-wide progress flag (if {@code null}, behaves like {@link #execute(long)})
   * @return true if the pipeline completed all tasks in the queue
   */
  public boolean execute(long availableTime, java.util.concurrent.atomic.AtomicBoolean sharedRanAny) {
    if (sharedRanAny == null) {
      return execute(availableTime);
    }
    if (availableTime <= 0L) {
      return runnables.isEmpty();
    }
    boolean hadTasks = !runnables.isEmpty();
    boolean res = execute(availableTime);
    if (hadTasks) {
      sharedRanAny.set(true);
    }
    return res;
  }

  public long size() {
    return runnables.size();
  }

  public void add(Runnable runnable) {
    runnables.add(runnable);
  }

  public void clear() {
    runnables.clear();
    // Semantically "clear" is a full reset: also unset the stop flag so the pipe
    // is reusable after a prior stop()/clear() sequence (e.g. in tests that
    // exercise the shutdown path and then re-use the shared RTP.getInstance()
    // singleton pipes).
    stop = false;
  }

  public void start() {
    stop = false;
  }

  public void stop() {
    runnables.forEach(
            runnable -> {
              if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).setCancelled(true);
            });
    stop = true;
    runnables.clear(); // Add this line to drop the strong references
  }
}

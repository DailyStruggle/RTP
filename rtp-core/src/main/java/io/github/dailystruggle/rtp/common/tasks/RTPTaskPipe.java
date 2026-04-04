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

  public long size() {
    return runnables.size();
  }

  public long avgTime() {
    return avgTime;
  }

  public void add(Runnable runnable) {
    runnables.add(runnable);
  }

  public void clear() {
    runnables.clear();
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

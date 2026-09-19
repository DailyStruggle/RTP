package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Task for processing asynchronous tasks during server ticks */
public final class AsyncTaskProcessing extends RTPRunnable {
  private static final AtomicLong step = new AtomicLong();
  private static final AtomicLong betweenStep = new AtomicLong();
  private static final Semaphore stepSemaphore = new Semaphore(1);
  private static final Semaphore futuresSemaphore = new Semaphore(1);
  private final long availableTime;

  /**
   * Constructor for AsyncTaskProcessing
   *
   * @param availableTime the time available for processing tasks in nanoseconds
   */
  public AsyncTaskProcessing(long availableTime) {
    this.availableTime = availableTime;
  }

  /** Spark-profiler frame tag (diagrams 01/02 async worker drain). See {@link RTPRunnable#sparkFrameName()}. */
  @Override
  protected String sparkFrameName() { return "rtp_async_task_drain"; }

  @Override
  public void run() {
//    System.out.println("[RTP-DEBUG] AsyncTaskProcessing: Tick started.");

    if (trackingId != null) {
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
    }

    // Only allocate a scratch list when there is actually something to drain.
    // This pulse runs every period; allocating an ArrayList on every idle tick
    // (the common case, when no futures are pending) is pure GC churn.
    if (!RTP.futures.isEmpty()) {
      try {
        futuresSemaphore.acquire();
        List<CompletableFuture<?>> futures = new ArrayList<>(RTP.futures.size());
        while (!RTP.futures.isEmpty()) {
          CompletableFuture<?> future = RTP.futures.poll();
          if (future != null && !future.isDone()) futures.add(future);
        }

        RTP.futures.addAll(futures);
      } catch (InterruptedException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      } finally {
        futuresSemaphore.release();
      }
    }

    if (isCancelled()) return;
    long start = System.nanoTime();

    long currentAvailableTime = availableTime; // fallback
    long period = 0;

    if (RTP.configs != null) {
      ConfigParser<PerformanceKeys> perf =
              (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
      if (perf != null) {
        period = perf.getNumber(PerformanceKeys.period, 0).longValue();

        long configMs = perf.getNumber(PerformanceKeys.asyncAllottedTime, 10).longValue();
        // Cap async time to prevent saturating the background thread pool
        configMs = Math.min(configMs, 25);
        currentAvailableTime = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(configMs);
      }
    }

    RTP.getInstance().cancelTasks.execute(Long.MAX_VALUE);
    if (isCancelled()) return;
    RTP.getInstance().miscAsyncTasks.execute(currentAvailableTime - (System.nanoTime() - start));
    if (isCancelled()) return;

    // Diagram 02 (PulseTrigger): drain SelectionAPI urgent + standard pipelines.
//    RTP.log(Level.FINER, "[Pulse] selectionAPI.compute() invoked");
    RTP.selectionAPI.compute();

    // Avoid copying the whole region collection into a fresh ArrayList on every
    // pulse: only one region (at index `step`) is ever used, and only when
    // betweenStep == 0. Read the size directly and fetch the nth region by
    // iteration when needed (below) so an idle pulse allocates nothing here.
    int size = RTP.selectionAPI.permRegionLookup.size();

    if (size == 0) {
      RTP.log(Level.FINER, "[Pulse] no regions configured; skipping budget loop");
      return;
    }
    if (period < size) period = size;

    long betweenTime = Math.max((period / size) - 1, 0);
    long betweenStep;
    long step;
    try {
      stepSemaphore.acquire();
      if (betweenTime <= 0) betweenStep = 0;
      else betweenStep = AsyncTaskProcessing.betweenStep.incrementAndGet() % betweenTime;

      step = AsyncTaskProcessing.step.get();
      if (betweenStep == 0) step = (step + 1) % size;

      AsyncTaskProcessing.betweenStep.set(betweenStep);
      AsyncTaskProcessing.step.set(step);
    } catch (InterruptedException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
      return;
    } finally {
      stepSemaphore.release();
    }

    // step computation according to period
    if (betweenStep == 0) {
      if (isCancelled()) return;
      if (step >= size) return;

      Region region = null;
      long idx = 0;
      for (Region r : RTP.selectionAPI.permRegionLookup.values()) {
        if (idx++ == step) {
          region = r;
          break;
        }
      }
      if (region == null) return;
      // Diagram 02 (CheckPeriod -> ExecuteRegion): this region's turn has come.
//      RTP.log(Level.FINE,
//          "[Pulse] executing region '" + region.name + "' (step=" + step
//              + "/" + size + ", betweenStep=" + betweenStep + ")");
      try {
        region.execute(currentAvailableTime - (System.nanoTime() - start));
      } catch (Exception e) {
        RTP.log(Level.SEVERE, "Error executing processing for region: " + region.name, e);
      }
    }
  }
}

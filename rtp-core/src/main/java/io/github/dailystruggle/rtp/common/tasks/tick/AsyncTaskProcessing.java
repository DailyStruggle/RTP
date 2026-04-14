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

  @Override
  public void run() {
//    System.out.println("[RTP-DEBUG] AsyncTaskProcessing: Tick started.");

    if (trackingId != null) {
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
    }

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

    RTP.selectionAPI.compute();

    List<Region> regions = new ArrayList<>(RTP.selectionAPI.permRegionLookup.values());
    int size = regions.size();

    if (size == 0) return;
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

//      System.out.println("[RTP-DEBUG] AsyncTaskProcessing: Math calculated -> betweenTime: " + betweenTime + " | betweenStep: " + betweenStep + " | step: " + step);

    } catch (InterruptedException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
      return;
    } finally {
      stepSemaphore.release();
    }

    // step computation according to period
    if (betweenStep == 0) {
      if (isCancelled()) return;
      if (step >= regions.size()) return;

      Region region = regions.get((int) step);
      try {
        region.execute(currentAvailableTime - (System.nanoTime() - start));
      } catch (Exception e) {
        RTP.log(Level.SEVERE, "Error executing processing for region: " + region.name, e);
      }
    }
  }
}

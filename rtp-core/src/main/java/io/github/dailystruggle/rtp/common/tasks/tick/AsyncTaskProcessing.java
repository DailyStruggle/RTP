package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.governor.ComputeBudget;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Task for processing asynchronous tasks during server ticks */
public final class AsyncTaskProcessing extends RTPRunnable {
  private static final AtomicLong step = new AtomicLong();
  private static final AtomicLong betweenStep = new AtomicLong();
  private static final Semaphore stepSemaphore = new Semaphore(1);
  private static final Semaphore futuresSemaphore = new Semaphore(1);

  /** Pulse runs longer than this are reported once as a stall (REQ-RTP-S-004 visibility). */
  static final long STALL_WARN_NANOS = TimeUnit.SECONDS.toNanos(5);

  // Non-overlap guard: the platform fires this pulse every tick regardless of whether the
  // previous one finished, so a slow pulse would otherwise stack one worker per tick.
  private static final AtomicBoolean pulseActive = new AtomicBoolean();
  private static final AtomicLong pulseStartNanos = new AtomicLong();
  private static final AtomicBoolean stallReported = new AtomicBoolean();

  static {
    if (ComputeGovernor.active().signalSource() == null) {
      ComputeGovernor.active().setSignalSource(RuntimeSignalSource.instance());
    }
  }

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
    if (!pulseActive.compareAndSet(false, true)) {
      long started = pulseStartNanos.get();
      long elapsed = System.nanoTime() - started;
      // started == 0: the owner won the CAS but has not stamped its start yet.
      if (started != 0L && elapsed > STALL_WARN_NANOS && stallReported.compareAndSet(false, true)) {
        RTPRuntime.log(Level.WARNING, "[RTP] async pulse has not completed after "
            + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms; skipping ticks until it finishes");
      }
      return;
    }
    pulseStartNanos.set(System.nanoTime());
    stallReported.set(false);
    try {
      runPulse();
    } finally {
      pulseActive.set(false);
    }
  }

  /** @return true while a pulse is executing; visible for tests. */
  static boolean isPulseActive() {
    return pulseActive.get();
  }

  /** Restarts the region rotation so the next pulse takes a turn; visible for tests. */
  static void resetRotationForTesting() {
    step.set(0L);
    betweenStep.set(0L);
  }

  private void runPulse() {
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
        Thread.currentThread().interrupt();
        RTPRuntime.log(Level.WARNING, e.getMessage(), e);
      } finally {
        futuresSemaphore.release();
      }
    }

    if (isCancelled()) return;

    // Before configs load, the constructor budget applies (clamped like the configured one).
    long currentAvailableTime = (ActiveConfigs.get() == null && availableTime > 0L)
        ? Math.min(availableTime, TimeUnit.MILLISECONDS.toNanos(TickBudget.MAX_ALLOTTED_MS))
        : TickBudget.asyncNanos();

    if (TickBudget.autoTune() || ComputeGovernor.isObservePropertyEnabled()) {
      if (ComputeGovernor.active().signalSource() == null) {
        ComputeGovernor.active().setSignalSource(RuntimeSignalSource.instance());
      }
      ComputeGovernor.active().maybeEvaluate(System.nanoTime());
    }

    // Cancellations run first and are excluded from the pulse budget and carry-forward debt (ADR-087 I2).
    MiscTasks.cancel().execute(Long.MAX_VALUE);
    if (isCancelled()) return;

    long start = System.nanoTime();
    MiscTasks.async().execute(currentAvailableTime - (System.nanoTime() - start));
    if (isCancelled()) return;

    ComputeBudget govBudget = ComputeGovernor.currentBudget();
    boolean actuating = govBudget != null && govBudget.isActuating();

    if (!actuating) {
      long period = TickBudget.periodTicks();
      // Diagram 02 (PulseTrigger): drain SelectionAPI urgent + standard pipelines.
      ActiveSelection.get().compute(currentAvailableTime - (System.nanoTime() - start));

      // Avoid copying the whole region collection into a fresh ArrayList on every
      // pulse: only one region (at index `step`) is ever used, and only when
      // betweenStep == 0. Read the size directly and fetch the nth region by
      // iteration when needed (below) so an idle pulse allocates nothing here.
      int size = ActiveSelection.get().permRegionLookup.size();

      if (size == 0) {
        RTPRuntime.log(Level.FINER, "[Pulse] no regions configured; skipping budget loop");
        return;
      }
      if (period < size) period = size;

      // One region turn every `interval` pulses, so each region refills once per `period` ticks.
      long interval = Math.max(period / size, 1L);
      long turn;
      long regionIndex;
      try {
        stepSemaphore.acquire();
        // Post-increment: the first pulse takes a turn, then one turn every `interval` pulses.
        turn = betweenStep.get() % interval;
        betweenStep.set((turn + 1) % interval);

        regionIndex = step.get();
        if (turn == 0) regionIndex = (regionIndex + 1) % size;

        step.set(regionIndex);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        RTPRuntime.log(Level.WARNING, e.getMessage(), e);
        return;
      } finally {
        stepSemaphore.release();
      }

      // step computation according to period
      if (turn == 0) {
        if (isCancelled()) return;
        if (regionIndex >= size) return;

        Region region = null;
        long idx = 0;
        for (Region r : ActiveSelection.get().permRegionLookup.values()) {
          if (idx++ == regionIndex) {
            region = r;
            break;
          }
        }
        if (region == null) return;
        try {
          region.execute(currentAvailableTime - (System.nanoTime() - start));
        } catch (Exception e) {
          RTPRuntime.log(Level.SEVERE, "Error executing processing for region: " + region.name, e);
        }
      }
      return;
    }

    // Governed pulse (ADR-087 Section 4.3 & 7.3):
    // 1. Drain urgent/player-facing selection, region miscPipeline, and waiting playerQueue first (Invariant I4).
    ActiveSelection.get().computeUrgent(currentAvailableTime - (System.nanoTime() - start));
    if (isCancelled()) return;

    for (Region region : ActiveSelection.get().permRegionLookup.values()) {
      if (isCancelled()) return;
      if (region == null) continue;
      try {
        region.executePlayerFacing(currentAvailableTime - (System.nanoTime() - start));
      } catch (Exception e) {
        RTPRuntime.log(Level.SEVERE, "Error executing player-facing work for region: " + region.name, e);
      }
    }
    if (isCancelled()) return;

    long cpuStart = RuntimeSignalSource.currentThreadCpuTimeNanos();
    try {
      long remainder = currentAvailableTime - (System.nanoTime() - start);
      long bgAvailable = ComputeGovernor.active().beginBackgroundAsyncPhase(remainder);
      long bgDeadline = System.nanoTime() + bgAvailable;
      AtomicBoolean ranAnyBg = new AtomicBoolean(false);

      if (bgAvailable > 0L) {
        ActiveSelection.get().computeStandard(bgAvailable, false, ranAnyBg);
      }
      if (isCancelled()) return;

      int size = ActiveSelection.get().permRegionLookup.size();
      if (size == 0) {
        RTPRuntime.log(Level.FINER, "[Pulse] no regions configured; skipping budget loop");
        return;
      }

      List<String> creditOrdered = ComputeGovernor.active()
          .orderRegionsByCredit(ActiveSelection.get().permRegionLookup.keySet());
      if (!creditOrdered.isEmpty()) {
        for (String regionName : creditOrdered) {
          if (isCancelled()) break;
          long remBg = bgDeadline - System.nanoTime();
          if (bgAvailable <= 0L || (ranAnyBg.get() && remBg <= 0L)) break;
          Region region = ActiveSelection.get().permRegionLookup.get(regionName);
          if (region == null) continue;
          try {
            long regionBudget = (!ranAnyBg.get() && bgAvailable > 0L) ? Math.max(1L, remBg) : Math.max(0L, remBg);
            region.execute(regionBudget, ranAnyBg);
          } catch (Exception e) {
            RTPRuntime.log(Level.SEVERE, "Error executing processing for region: " + region.name, e);
          }
        }
      } else {
        long period = Math.max(TickBudget.backgroundPeriodTicks(), (long) size);
        long interval = Math.max(period / size, 1L);
        long turn;
        long regionIndex;
        try {
          stepSemaphore.acquire();
          turn = betweenStep.get() % interval;
          betweenStep.set((turn + 1) % interval);
          regionIndex = step.get();
          if (turn == 0) regionIndex = (regionIndex + 1) % size;
          step.set(regionIndex);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          RTPRuntime.log(Level.WARNING, e.getMessage(), e);
          return;
        } finally {
          stepSemaphore.release();
        }
        if (turn == 0 && !isCancelled() && regionIndex < size) {
          long remBg = bgDeadline - System.nanoTime();
          if (bgAvailable > 0L && (!ranAnyBg.get() || remBg > 0L)) {
            Region region = null;
            long idx = 0;
            for (Region r : ActiveSelection.get().permRegionLookup.values()) {
              if (idx++ == regionIndex) {
                region = r;
                break;
              }
            }
            if (region != null) {
              try {
                long regionBudget = (!ranAnyBg.get() && bgAvailable > 0L) ? Math.max(1L, remBg) : Math.max(0L, remBg);
                region.execute(regionBudget, ranAnyBg);
              } catch (Exception e) {
                RTPRuntime.log(Level.SEVERE, "Error executing processing for region: " + region.name, e);
              }
            }
          }
        }
      }
    } finally {
      long cpuEnd = RuntimeSignalSource.currentThreadCpuTimeNanos();
      if (cpuStart >= 0L && cpuEnd > cpuStart) {
        RuntimeSignalSource.instance().recordRtpBackgroundCpuNanos(cpuEnd - cpuStart);
      }
      ComputeGovernor.active().endAsyncPulse(System.nanoTime() - start, currentAvailableTime);
    }
  }
}

package io.github.dailystruggle.rtp.neoforge.commands.test;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaScheduler;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * NeoForge implementation of {@link TestUmbrellaScheduler}. Delegates
 * deferred dispatch to {@link RTP#scheduler} (e.g.
 * {@link io.github.dailystruggle.rtp.neoforge.scheduling.NeoForgeScheduler}).
 *
 * <p>When the platform async scheduler is unavailable (headless tests),
 * the task is executed inline on the calling thread after the delay so
 * S-004 / S-006 contracts are still honored - no silent drop.
 *
 * <p>Installed onto {@code RTP.testUmbrellaContext} during NeoForge server setup.
 */
public final class NeoForgeTestUmbrellaScheduler implements TestUmbrellaScheduler {

  @Override
  public void runLater(long delayMillis, Runnable task) {
    if (task == null) {
      throw new IllegalArgumentException("task");
    }
    final long delay = Math.max(0L, delayMillis);
    final Runnable safe = () -> {
      try {
        task.run();
      } catch (Throwable t) {
        RTP.log(Level.WARNING,
            "[NeoForgeTestUmbrellaScheduler] deferred test-umbrella task threw", t);
      }
    };
    try {
      if (RTP.scheduler != null) {
        long delayTicks = Math.max(1L, (delay + 49L) / 50L);
        final Object[] handle = new Object[1];
        handle[0] = RTP.scheduler.runTaskTimerAsynchronously(
            () -> {
              try {
                if (handle[0] != null) {
                  RTP.scheduler.cancelTask(handle[0]);
                  handle[0] = null;
                }
              } catch (Throwable ignored) {
                // best-effort cancel; still run the task below
              }
              safe.run();
            },
            delayTicks,
            delayTicks);
        if (handle[0] != null) return;
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[NeoForgeTestUmbrellaScheduler] RTP.scheduler.runTaskTimerAsynchronously failed; falling back to inline", t);
    }
    // Headless / scheduler-down fallback.
    try {
      if (delay > 0L) TimeUnit.MILLISECONDS.sleep(delay);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    safe.run();
  }
}

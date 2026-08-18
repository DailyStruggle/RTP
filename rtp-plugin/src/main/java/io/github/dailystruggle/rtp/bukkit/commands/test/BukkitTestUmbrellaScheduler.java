package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaScheduler;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Bukkit-family implementation of {@link TestUmbrellaScheduler}. Delegates
 * deferred dispatch to {@link RTP#scheduler}: the same
 * {@code RTPScheduler} abstraction already used by
 * {@code TestFullCmd#runStep} (async on Spigot/Paper, async-timer on
 * Folia per existing platform pattern).
 *
 * <p>When the platform async scheduler is unavailable (headless tests),
 * the task is executed inline on the calling thread after the delay so
 * S-004 / S-006 contracts are still honored &mdash; no silent drop.
 *
 * <p>Installed onto {@code RTP.testUmbrellaContext} from
 * {@code RTPBukkitPlugin} during {@code onEnable}.
 */
public final class BukkitTestUmbrellaScheduler implements TestUmbrellaScheduler {

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
            "[BukkitTestUmbrellaScheduler] deferred test-umbrella task threw", t);
      }
    };
    try {
      if (RTP.scheduler != null) {
        // Single-shot via the async timer scheduler: schedule a
        // self-cancelling timer that fires once after `delay` ms. The
        // delay is converted to server ticks (20 Hz) per
        // RTPScheduler#runTaskTimerAsynchronously contract. A holder
        // array carries the platform task token into the runnable so it
        // can self-cancel on first fire (mirrors TestFullCmd watchdog
        // pattern).
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
          "[BukkitTestUmbrellaScheduler] RTP.scheduler.runTaskTimerAsynchronously failed; falling back to inline", t);
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

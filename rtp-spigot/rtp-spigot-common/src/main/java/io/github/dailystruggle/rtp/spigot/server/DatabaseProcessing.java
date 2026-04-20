package io.github.dailystruggle.rtp.spigot.server;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.java.JavaPlugin;

public class DatabaseProcessing {
  private static final AtomicBoolean killed = new AtomicBoolean(false);
  private static final AtomicBoolean processing = new AtomicBoolean(false);
  private static final AtomicReference<Object> task = new AtomicReference<>(null);

  public static void clear() {
    // getAndSet makes the cancel-and-null sequence atomic against concurrent
    // clear() callers: only the caller that wins the swap will see the prior
    // task reference, so the scheduled task is cancelled exactly once.
    // (start() vs. start() and start() vs. kill() races are unchanged — those
    // still need an external lifecycle guard if invoked from multiple threads.)
    Object scheduledTask = task.getAndSet(null);
    if (scheduledTask != null) {
        RTP.scheduler.cancelTask(scheduledTask);
    }
  }

  public static void start(JavaPlugin plugin) {
    killed.set(false);
    clear();
    Object scheduledTask = RTP.scheduler.runTaskTimerAsynchronously(
        () -> {
            if (killed.get()) return;
            if (processing.getAndSet(true)) return;
            try {
                if (RTP.getInstance().databaseAccessor != null) {
                    RTP.getInstance().databaseAccessor.processQueries(Long.MAX_VALUE);
                }
            } finally {
                processing.set(false);
            }
        }, 100L, 100L);
    task.set(scheduledTask);
  }

  public static void kill() {
    clear();
    killed.set(true);
  }
}

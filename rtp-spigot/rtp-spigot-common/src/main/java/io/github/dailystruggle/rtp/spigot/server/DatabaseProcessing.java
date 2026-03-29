package io.github.dailystruggle.rtp.spigot.server;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class DatabaseProcessing extends BukkitRunnable {
  private static final AtomicBoolean killed = new AtomicBoolean(false);
  private static final AtomicReference<BukkitTask> asyncTask = new AtomicReference<>(null);
  private final JavaPlugin plugin;

  public DatabaseProcessing(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public static void clear() {
    if (asyncTask.get() != null) asyncTask.get().cancel();
    asyncTask.set(null);
  }

  public static void kill() {
    FillTask.kill();
    clear();
    killed.set(true);
  }

  @Override
  public void run() {
    if (killed.get()) return;
    if (asyncTask.get() != null) return;

    CompletableFuture<Boolean> future = new CompletableFuture<>();
    BukkitTask task =
        Bukkit.getScheduler()
            .runTaskAsynchronously(
                plugin,
                () -> {
                  if (RTP.getInstance().databaseAccessor != null)
                    RTP.getInstance().databaseAccessor.processQueries(Long.MAX_VALUE);
                  future.complete(true);
                });
    asyncTask.set(task);
    future.thenAccept(aBoolean -> asyncTask.set(null));
  }

  @Override
  public void cancel() {
    kill();
    super.cancel();
  }
}

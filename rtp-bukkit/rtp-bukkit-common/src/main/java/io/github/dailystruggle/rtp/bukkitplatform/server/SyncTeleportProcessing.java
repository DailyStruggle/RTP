package io.github.dailystruggle.rtp.bukkitplatform.server;

import io.github.dailystruggle.rtp.common.tasks.TPS;
import io.github.dailystruggle.rtp.common.tasks.tick.SyncTaskProcessing;
import java.util.concurrent.TimeUnit;
import org.bukkit.scheduler.BukkitRunnable;

public class SyncTeleportProcessing extends BukkitRunnable {
  private static SyncTaskProcessing syncTaskProcessing = null;
  private static boolean killed = false;

  public SyncTeleportProcessing() {
    if (syncTaskProcessing != null) return;
    long avgTime = TPS.timeSinceTick(20) / 20;
    long currTime = TPS.timeSinceTick(1);

    long availableTime = avgTime - currTime - 30;
    availableTime = TimeUnit.MILLISECONDS.toNanos(availableTime) / 2;

    syncTaskProcessing = new SyncTaskProcessing(availableTime);
  }

  public static void kill() {
    if (syncTaskProcessing != null) syncTaskProcessing.setCancelled(true);
    syncTaskProcessing = null;
    killed = true;
  }

  @Override
  public void run() {
    if (killed) return;
    if (syncTaskProcessing != null) syncTaskProcessing.run();
  }

  @Override
  public void cancel() {
    kill();
    super.cancel();
  }
}

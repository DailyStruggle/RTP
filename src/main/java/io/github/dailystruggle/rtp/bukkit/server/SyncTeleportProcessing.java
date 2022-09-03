package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.rtp.common.tasks.SyncTaskProcessing;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.TimeUnit;

public class SyncTeleportProcessing extends BukkitRunnable {
    private static SyncTaskProcessing syncTaskProcessing = null;
    private static boolean killed = false;

    public SyncTeleportProcessing() {
        if(syncTaskProcessing != null) return;
        long avgTime = TPS.timeSinceTick(20) / 20;
        long currTime = TPS.timeSinceTick(1);

        long availableTime = avgTime - currTime;
        availableTime = TimeUnit.MICROSECONDS.toNanos(availableTime);

        syncTaskProcessing = new SyncTaskProcessing(availableTime);
    }

    @Override
    public void run() {
        if(killed) return;
        syncTaskProcessing.run();
    }

    @Override
    public void cancel() {
        kill();
        super.cancel();
    }

    public static void kill() {
        if(syncTaskProcessing!=null) syncTaskProcessing.setCancelled(true);
        syncTaskProcessing = null;
        killed = true;
    }
}

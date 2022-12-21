package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.rtp.common.tasks.tick.AsyncTaskProcessing;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.TimeUnit;

public class AsyncTeleportProcessing extends BukkitRunnable {
    private static AsyncTaskProcessing asyncTaskProcessing = null;
    private static boolean killed = false;

    public AsyncTeleportProcessing() {
        if(killed) return;
        if(asyncTaskProcessing != null) return;
        long avgTime = TPS.timeSinceTick(20) / 20;
        long currTime = TPS.timeSinceTick(1);

        long availableTime = avgTime - currTime;
        availableTime = TimeUnit.MILLISECONDS.toNanos(availableTime);

        asyncTaskProcessing = new AsyncTaskProcessing(availableTime);
    }

    @Override
    public void run() {
        if(killed) return;
        asyncTaskProcessing.run();
    }

    @Override
    public void cancel() {
        kill();
        super.cancel();
    }

    public static void clear() {
        asyncTaskProcessing = null;
    }

    public static void kill() {
        if(asyncTaskProcessing!=null && !asyncTaskProcessing.isCancelled()) asyncTaskProcessing.setCancelled(true);
        killed = true;
    }
}

package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.AsyncTaskProcessing;
import io.github.dailystruggle.rtp.common.tasks.SyncTaskProcessing;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class AsyncTeleportProcessing extends BukkitRunnable {
    private static AsyncTaskProcessing asyncTaskProcessing = null;
    private static boolean killed = false;

    public AsyncTeleportProcessing() {
        if(killed) return;
        if(asyncTaskProcessing != null) return;
        long avgTime = TPS.timeSinceTick(20) / 20;
        long currTime = TPS.timeSinceTick(1);

        long availableTime = avgTime - currTime;
        availableTime = TimeUnit.MICROSECONDS.toNanos(availableTime);

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

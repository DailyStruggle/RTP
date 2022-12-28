package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.tick.AsyncTaskProcessing;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class AsyncTeleportProcessing extends BukkitRunnable {
    private static final AtomicReference<AsyncTaskProcessing> asyncTaskProcessing = new AtomicReference<>();
    private static final AtomicBoolean killed = new AtomicBoolean(false);

    public AsyncTeleportProcessing() {
        if(killed.get()) return;
        if(asyncTaskProcessing.get() != null) return;
        long avgTime = TPS.timeSinceTick(20) / 20;
        long currTime = TPS.timeSinceTick(1);

        long availableTime = avgTime - currTime;
        availableTime = TimeUnit.MILLISECONDS.toNanos(availableTime);

        asyncTaskProcessing.set(new AsyncTaskProcessing(availableTime));
    }


    @Override
    public void run() {
        if(killed.get()) return;
        if(asyncTaskProcessing.get() == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
            AsyncTaskProcessing asyncTaskProcessing2 = AsyncTeleportProcessing.asyncTaskProcessing.get();
            if(asyncTaskProcessing2 == null) return;
            asyncTaskProcessing2.run();
            AsyncTeleportProcessing.asyncTaskProcessing.set(null);
        });
    }

    @Override
    public void cancel() {
        kill();
        super.cancel();
    }

    public static void clear() {
        if(asyncTaskProcessing.get()!=null && !asyncTaskProcessing.get().isCancelled()) asyncTaskProcessing.get().setCancelled(true);
        asyncTaskProcessing.set(null);
    }

    public static void kill() {
        if(asyncTaskProcessing.get()!=null && !asyncTaskProcessing.get().isCancelled()) asyncTaskProcessing.get().setCancelled(true);
        asyncTaskProcessing.set(null);
        killed.set(true);
    }
}

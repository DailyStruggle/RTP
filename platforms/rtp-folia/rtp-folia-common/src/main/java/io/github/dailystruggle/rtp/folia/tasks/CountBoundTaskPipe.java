package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.bukkit.plugin.Plugin;

public class CountBoundTaskPipe extends RTPTaskPipe {
    private final Plugin plugin;
    private final int maxTasksPerTick;

    public CountBoundTaskPipe(Plugin plugin, int maxTasksPerTick) {
        this.plugin = plugin;
        this.maxTasksPerTick = maxTasksPerTick;
    }

    @Override
    public boolean execute() {
        return execute(Long.MAX_VALUE);
    }

    @Override
    public boolean execute(long availableTime) {
        if (stop) return true;
        long start = System.nanoTime();
        long dt = 0;
        for (int i = 0; i < maxTasksPerTick && dt < availableTime; i++) {
            Runnable runnable = runnables.poll();
            if (runnable == null) break;

            RTPLocation rtpLocation = null;
            if (runnable instanceof RTPRunnable) {
                rtpLocation = ((RTPRunnable) runnable).getLocation();
            }

            if (rtpLocation == null) {
                if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).runWithTracking();
                else runnable.run();
                dt = System.nanoTime() - start;
            } else {
                RTP.serverAccessor.getScheduler().runTask(rtpLocation, () -> {
                    if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).runWithTracking();
                    else runnable.run();
                });
            }
        }
        return runnables.isEmpty();
    }
}

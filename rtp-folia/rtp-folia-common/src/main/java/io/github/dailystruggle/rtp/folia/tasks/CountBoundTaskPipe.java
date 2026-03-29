package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
    public boolean execute(long ignoredTime) {
        if (stop) return true;
        for (int i = 0; i < maxTasksPerTick; i++) {
            Runnable runnable = runnables.poll();
            if (runnable == null) break;

            RTPLocation rtpLocation = null;
            if (runnable instanceof RTPRunnable) {
                rtpLocation = ((RTPRunnable) runnable).getLocation();
            }

            if (rtpLocation == null) {
                Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
                    if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).runWithTracking();
                    else runnable.run();
                });
            } else {
                if (rtpLocation.world() instanceof FoliaRTPWorld) {
                    World world = ((FoliaRTPWorld) rtpLocation.world()).world();
                    Location loc = new Location(world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z());
                    Bukkit.getRegionScheduler().run(plugin, loc, scheduledTask -> {
                        if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).runWithTracking();
                        else runnable.run();
                    });
                } else {
                    Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> {
                        if (runnable instanceof RTPRunnable) ((RTPRunnable) runnable).runWithTracking();
                        else runnable.run();
                    });
                }
            }
        }
        return runnables.isEmpty();
    }
}

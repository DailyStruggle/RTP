package io.github.dailystruggle.rtp.folia_v1_21_R1.scheduling;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class FoliaSchedulerImpl implements RTPScheduler {
    private final JavaPlugin plugin;

    public FoliaSchedulerImpl(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runTaskAsynchronously(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    @Override
    public void runTask(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), Math.max(1, delay));
    }

    @Override
    public Object runTaskTimer(Runnable task, long delay, long period) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), Math.max(1, delay), Math.max(1, period));
    }

    @Override
    public void cancelTask(Object task) {
        if (task instanceof ScheduledTask) {
            ((ScheduledTask) task).cancel();
        }
    }
}

package io.github.dailystruggle.rtp.paper_v1_20_R1.scheduling;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.Bukkit;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

@Deprecated
public 
class FoliaSchedulerImpl implements RTPScheduler {
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
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delay);
    }

    @Override

public Object runTaskTimer(Runnable task, long delay, long period) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delay, period);
    }

    @Override

public void cancelTask(Object task) {
        if (task instanceof ScheduledTask) {
            ((ScheduledTask) task).cancel();
        }
    }
}


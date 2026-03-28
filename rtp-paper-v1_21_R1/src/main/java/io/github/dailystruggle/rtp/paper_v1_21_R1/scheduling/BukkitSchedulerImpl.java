package io.github.dailystruggle.rtp.paper_v1_21_R1.scheduling;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;

import org.bukkit.Bukkit;

import org.bukkit.plugin.java.JavaPlugin;

public 
class BukkitSchedulerImpl implements RTPScheduler {
    private final JavaPlugin plugin;

public BukkitSchedulerImpl(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override

public void runTaskAsynchronously(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override

public void runTask(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override

public void runTaskLater(Runnable task, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    @Override

public Object runTaskTimer(Runnable task, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }

    @Override

public void cancelTask(Object task) {
        if (task instanceof org.bukkit.scheduler.BukkitTask) {
            ((org.bukkit.scheduler.BukkitTask) task).cancel();
        } else if (task instanceof Integer) {
            Bukkit.getScheduler().cancelTask((Integer) task);
        }
    }
}


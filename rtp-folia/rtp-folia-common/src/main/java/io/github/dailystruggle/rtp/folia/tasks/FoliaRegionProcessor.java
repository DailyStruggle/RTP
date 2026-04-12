package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class FoliaRegionProcessor implements Runnable {
    private final Plugin plugin;
    private final RegionKey key;
    private final ConcurrentLinkedQueue<RTPRunnable> queue;
    private final Set<RegionKey> activeProcessors;
    private final int maxTasksPerTick;

    public FoliaRegionProcessor(Plugin plugin, RegionKey key, ConcurrentLinkedQueue<RTPRunnable> queue, Set<RegionKey> activeProcessors) {
        this(plugin, key, queue, activeProcessors, 20);
    }

    public FoliaRegionProcessor(Plugin plugin, RegionKey key, ConcurrentLinkedQueue<RTPRunnable> queue, Set<RegionKey> activeProcessors, int maxTasksPerTick) {
        this.plugin = plugin;
        this.key = key;
        this.queue = queue;
        this.activeProcessors = activeProcessors;
        this.maxTasksPerTick = maxTasksPerTick;
    }

    @Override
    public void run() {
        for (int i = 0; i < maxTasksPerTick; i++) {
            RTPRunnable task = queue.poll();
            if (task == null) break;

            // Wrap task execution to prevent pipeline crashes from unhandled exceptions
            try {
                task.runWithTracking();
            } catch (Exception e) {
                SendMessage.log(Level.SEVERE, "Error executing task in region processor: " + task, e);
            }
        }

        if (queue.isEmpty()) {
            activeProcessors.remove(key);
            // Double-check in case a task was injected during removal
            if (!queue.isEmpty() && activeProcessors.add(key)) {
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(key.worldId());
                if (world != null) {
                    org.bukkit.Location loc = new org.bukkit.Location(world, key.regionX() << 9, 0, key.regionZ() << 9);
                    org.bukkit.Bukkit.getRegionScheduler().runDelayed(plugin, loc, scheduledTask -> this.run(), 1L);
                }
            }
        } else {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(key.worldId());
            if (world != null) {
                org.bukkit.Location loc = new org.bukkit.Location(world, key.regionX() << 9, 0, key.regionZ() << 9);
                org.bukkit.Bukkit.getRegionScheduler().runDelayed(plugin, loc, scheduledTask -> this.run(), 1L);
            } else {
                activeProcessors.remove(key);
            }
        }
    }
}

package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FoliaRegionProcessor implements Runnable {
    private final Plugin plugin;
    private final RegionKey key;
    private final ConcurrentLinkedQueue<RTPRunnable> queue;
    private final Set<RegionKey> activeProcessors;
    private final long availableTime; // Default: 5000000L for 5ms

    public FoliaRegionProcessor(Plugin plugin, RegionKey key, ConcurrentLinkedQueue<RTPRunnable> queue, Set<RegionKey> activeProcessors) {
        this(plugin, key, queue, activeProcessors, 5000000L);
    }

    public FoliaRegionProcessor(Plugin plugin, RegionKey key, ConcurrentLinkedQueue<RTPRunnable> queue, Set<RegionKey> activeProcessors, long availableTime) {
        this.plugin = plugin;
        this.key = key;
        this.queue = queue;
        this.activeProcessors = activeProcessors;
        this.availableTime = availableTime;
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        while (System.nanoTime() - start < availableTime) {
            RTPRunnable task = queue.poll();
            if (task == null) break;
            task.runWithTracking();
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

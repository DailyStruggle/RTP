package io.github.dailystruggle.rtp.folia.tasks;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
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
                RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(key.worldId());
                if (world != null) {
                    RTP.serverAccessor.getScheduler().runTaskLater(world, key.regionX() << 5, key.regionZ() << 5, this, 1L);
                }
            }
        } else {
            RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(key.worldId());
            if (world != null) {
                RTP.serverAccessor.getScheduler().runTaskLater(world, key.regionX() << 5, key.regionZ() << 5, this, 1L);
            } else {
                activeProcessors.remove(key);
            }
        }
    }
}

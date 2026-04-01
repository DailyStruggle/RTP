package io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class MockFoliaScheduler implements RTPScheduler {
    private final List<MockTask> tasks = new ArrayList<>();
    private final AtomicLong currentTime = new AtomicLong(0);
    private final Plugin plugin;

    public MockFoliaScheduler() {
        this.plugin = null;
    }

    public MockFoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    private static class MockTask {
        final Runnable runnable;
        long nextRun;
        final long period;
        boolean cancelled = false;

        MockTask(Runnable runnable, long nextRun, long period) {
            this.runnable = runnable;
            this.nextRun = nextRun;
            this.period = period;
        }
    }

    private boolean isMockBukkit() {
        try {
            return Bukkit.getScheduler().getClass().getName().contains("Mock");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void runTaskAsynchronously(Runnable task) {
        if (isMockBukkit()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    @Override
    public void runTask(Runnable task) {
        if (isMockBukkit()) {
            Bukkit.getScheduler().runTask(plugin, task);
        } else {
            task.run();
        }
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        if (isMockBukkit()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        } else {
            tasks.add(new MockTask(task, currentTime.get() + delay, -1));
        }
    }

    @Override
    public Object runTaskTimer(Runnable task, long delay, long period) {
        if (isMockBukkit()) {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        } else {
            MockTask mockTask = new MockTask(task, currentTime.get() + delay, period);
            tasks.add(mockTask);
            return mockTask;
        }
    }

    @Override
    public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
        return runTaskTimer(task, delay, period);
    }

    @Override
    public void cancelTask(Object task) {
        if (isMockBukkit()) {
            if (task instanceof Integer) {
                Bukkit.getScheduler().cancelTask((Integer) task);
            } else if (task instanceof BukkitTask) {
                ((BukkitTask) task).cancel();
            }
        }

        if (task instanceof MockTask) {
            ((MockTask) task).cancelled = true;
        }
    }

    @Override
    public void scheduleTeleport(RTPPlayer player, RTPRunnable task, long delayTicks) {
        String taskId = java.util.UUID.randomUUID().toString();
        io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask trackedTask = new io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask(task, taskId);
        if (io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor != null) {
            io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor.registerAction(trackedTask);
        }
        runTaskLater(trackedTask, delayTicks);
    }

    @Override
    public void runTask(RTPLocation location, Runnable task) {
        runTask(task);
    }

    @Override
    public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {
        runTask(task);
    }

    @Override
    public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) {
        return runTaskTimer(task, delay, period);
    }

    public void tick(long ticks) {
        currentTime.addAndGet(ticks);
        List<MockTask> toRun = new ArrayList<>();

        List<MockTask> tasksCopy = new ArrayList<>(tasks);
        for (MockTask task : tasksCopy) {
            if (task.cancelled) {
                tasks.remove(task);
                continue;
            }
            if (task.nextRun <= currentTime.get()) {
                toRun.add(task);
                if (task.period <= 0) {
                    tasks.remove(task);
                } else {
                    task.nextRun += task.period;
                }
            }
        }

        for (MockTask task : toRun) {
            task.runnable.run();
        }
    }
}

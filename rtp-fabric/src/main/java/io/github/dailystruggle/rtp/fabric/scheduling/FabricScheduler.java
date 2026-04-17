package io.github.dailystruggle.rtp.fabric.scheduling;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FabricScheduler implements RTPScheduler {
    private final ScheduledExecutorService asyncExecutor = Executors.newScheduledThreadPool(4);
    private final List<FabricTask> syncTasks = new LinkedList<>();
    private MinecraftServer server;

    public FabricScheduler() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            this.server = server;
            synchronized (syncTasks) {
                Iterator<FabricTask> iterator = syncTasks.iterator();
                while (iterator.hasNext()) {
                    FabricTask task = iterator.next();
                    if (task.tick()) {
                        iterator.remove();
                    }
                }
            }
        });
    }

    @Override
    public TrackedRTPTask runTaskAsynchronously(Runnable task) {
        String taskId = UUID.randomUUID().toString();
        RTPRunnable rtpRunnable = task instanceof RTPRunnable ? (RTPRunnable) task : new RTPRunnable() {
            @Override
            public void run() {
                task.run();
            }
        };
        TrackedRTPTask trackedTask = new TrackedRTPTask(rtpRunnable, taskId);
        if (RTPAPI.serverAccessor != null) {
            RTPAPI.serverAccessor.registerAction(trackedTask);
        }
        asyncExecutor.execute(trackedTask);
        return trackedTask;
    }

    @Override
    public void runTask(Runnable task) {
        if (server != null && server.isOnThread()) {
            task.run();
        } else {
            synchronized (syncTasks) {
                syncTasks.add(new FabricTask(task, 0));
            }
        }
    }

    @Override
    public void runTaskLater(Runnable task, long delay) {
        synchronized (syncTasks) {
            syncTasks.add(new FabricTask(task, delay));
        }
    }

    @Override
    public Object runTaskTimer(Runnable task, long delay, long period) {
        FabricTimer timer = new FabricTimer(task, delay, period);
        synchronized (syncTasks) {
            syncTasks.add(timer);
        }
        return timer;
    }

    @Override
    public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
        return asyncExecutor.scheduleAtFixedRate(task, delay * 50, period * 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancelTask(Object task) {
        if (task instanceof FabricTimer) {
            ((FabricTimer) task).cancel();
        } else if (task instanceof CompletableFuture) {
            ((CompletableFuture<?>) task).cancel(true);
        } else if (task instanceof java.util.concurrent.ScheduledFuture) {
            ((java.util.concurrent.ScheduledFuture<?>) task).cancel(true);
        }
    }

    @Override
    public void scheduleTeleport(io.github.dailystruggle.rtp.api.entity.RTPPlayer player, RTPRunnable task, long delayTicks) {
        runTaskLater(task, delayTicks);
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

    @Override
    public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {
        runTaskLater(task, delay);
    }

    private static class FabricTask {
        protected final Runnable runnable;
        protected long delay;

        public FabricTask(Runnable runnable, long delay) {
            this.runnable = runnable;
            this.delay = delay;
        }

        public boolean tick() {
            if (delay > 0) {
                delay--;
                return false;
            }
            runnable.run();
            return true;
        }
    }

    private static class FabricTimer extends FabricTask {
        private final long period;
        private boolean cancelled = false;

        public FabricTimer(Runnable runnable, long delay, long period) {
            super(runnable, delay);
            this.period = period;
        }

        @Override
        public boolean tick() {
            if (cancelled) return true;
            if (delay > 0) {
                delay--;
                return false;
            }
            runnable.run();
            delay = period - 1;
            return false;
        }

        public void cancel() {
            this.cancelled = true;
        }
    }
}

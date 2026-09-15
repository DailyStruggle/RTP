package io.github.dailystruggle.rtp.fabric.commands.test;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaScheduler;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Fabric implementation of {@link TestUmbrellaScheduler}. Delegates
 * deferred dispatch to {@link RTP#scheduler} (e.g.
 * {@link io.github.dailystruggle.rtp.fabric.scheduling.FabricScheduler}) or
 * the server tick executor per S-005.
 *
 * <p>When the platform async scheduler is unavailable (headless tests),
 * the task is executed inline on the calling thread after the delay so
 * S-004 / S-006 contracts are still honored - no silent drop.
 *
 * <p>Installed onto {@code RTP.testUmbrellaContext} during Fabric server startup.
 */
public final class FabricTestUmbrellaScheduler implements TestUmbrellaScheduler {

    private final RTPScheduler scheduler;
    private final MinecraftServer server;

    public FabricTestUmbrellaScheduler() {
        this(null, null);
    }

    public FabricTestUmbrellaScheduler(RTPScheduler scheduler) {
        this(scheduler, null);
    }

    public FabricTestUmbrellaScheduler(RTPScheduler scheduler, MinecraftServer server) {
        this.scheduler = scheduler;
        this.server = server;
    }

    private RTPScheduler getScheduler() {
        if (this.scheduler != null) return this.scheduler;
        return RTP.scheduler;
    }

    private MinecraftServer getServer() {
        if (this.server != null) return this.server;
        if (RTP.serverAccessor instanceof FabricServerAccessor fsa) {
            return fsa.getServer();
        }
        return null;
    }

    @Override
    public void runLater(long delayMillis, Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task");
        }
        final long delay = Math.max(0L, delayMillis);
        final Runnable safe = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[FabricTestUmbrellaScheduler] deferred test-umbrella task threw", t);
            }
        };
        try {
            RTPScheduler sched = getScheduler();
            if (sched != null) {
                long delayTicks = Math.max(1L, (delay + 49L) / 50L);
                final Object[] handle = new Object[1];
                final java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean(false);
                Object taskObj = sched.runTaskTimerAsynchronously(
                        () -> {
                            ran.set(true);
                            try {
                                Object h = handle[0];
                                if (h != null) {
                                    sched.cancelTask(h);
                                    handle[0] = null;
                                }
                            } catch (Throwable ignored) {
                                // best-effort cancel; still run the task below
                            }
                            safe.run();
                        },
                        delayTicks,
                        delayTicks);
                handle[0] = taskObj;
                if (ran.get() && taskObj != null) {
                    try {
                        sched.cancelTask(taskObj);
                        handle[0] = null;
                    } catch (Throwable ignored) {
                    }
                }
                if (taskObj != null) return;
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[FabricTestUmbrellaScheduler] RTP.scheduler.runTaskTimerAsynchronously failed; falling back", t);
        }

        // Check server tick executor per S-005 if scheduler is unavailable or failed
        try {
            MinecraftServer srv = getServer();
            if (srv != null && delay == 0L) {
                srv.execute(safe);
                return;
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[FabricTestUmbrellaScheduler] server executor dispatch failed; falling back to inline", t);
        }

        // Headless / scheduler-down fallback.
        try {
            if (delay > 0L) TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        safe.run();
    }
}

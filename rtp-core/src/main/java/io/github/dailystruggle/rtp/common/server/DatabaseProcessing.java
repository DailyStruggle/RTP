package io.github.dailystruggle.rtp.common.server;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Platform-agnostic periodic async flush of {@link RTP#databaseAccessor}'s pending query queue.
 * Runs on {@link RTP#scheduler} to drain pending mutations to the database.
 */
public final class DatabaseProcessing {
    private static final AtomicBoolean killed = new AtomicBoolean(false);
    private static final AtomicBoolean processing = new AtomicBoolean(false);
    private static final AtomicReference<Object> task = new AtomicReference<>(null);

    private DatabaseProcessing() {
        // utility - no instances
    }

    /**
     * Atomically cancels the currently scheduled flush task, if any.
     */
    public static void clear() {
        Object scheduledTask = task.getAndSet(null);
        if (scheduledTask != null) {
            RTP.scheduler.cancelTask(scheduledTask);
        }
    }

    /** Schedule the periodic asynchronous flush. */
    public static void start() {
        killed.set(false);
        clear();
        Object scheduledTask = RTP.scheduler.runTaskTimerAsynchronously(
                () -> {
                    if (killed.get()) return;
                    if (processing.getAndSet(true)) return;
                    try {
                        if (RTP.getInstance().databaseAccessor != null) {
                            RTP.getInstance().databaseAccessor.processQueries(Long.MAX_VALUE);
                        }
                    } finally {
                        processing.set(false);
                    }
                }, 100L, 100L);
        task.set(scheduledTask);
    }

    /**
     * Permanently stop the flush task. After this call, {@link #start()}
     * must be invoked to resume processing.
     */
    public static void kill() {
        clear();
        killed.set(true);
    }
}

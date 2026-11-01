package io.github.dailystruggle.rtp.common.server;


import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Platform-agnostic periodic async flush of {@link ActiveDatabase#accessor()}'s pending query queue.
 * Runs on {@link RTPRuntime#scheduler()} to drain pending mutations to the database.
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
            RTPRuntime.scheduler().cancelTask(scheduledTask);
        }
    }

    /** Schedule the periodic asynchronous flush. */
    public static void start() {
        killed.set(false);
        clear();
        Object scheduledTask = RTPRuntime.scheduler().runTaskTimerAsynchronously(
                () -> {
                    if (killed.get()) return;
                    if (processing.getAndSet(true)) return;
                    try {
                        if (ActiveDatabase.accessor() != null) {
                            ActiveDatabase.accessor().processQueries(Long.MAX_VALUE);
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

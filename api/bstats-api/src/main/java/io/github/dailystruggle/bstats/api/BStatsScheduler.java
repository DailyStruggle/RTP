package io.github.dailystruggle.bstats.api;

/**
 * Host scheduler the client runs on, so it never creates its own threads.
 * Delays are in ticks (1 tick = 50 ms); hosts without a tick clock convert.
 */
public interface BStatsScheduler {

    /** Runs {@code task} on the server main / global thread. */
    void runOnMainThread(Runnable task);

    /** Runs {@code task} off the main thread. */
    void runAsync(Runnable task);

    /** Runs {@code task} once after {@code delayTicks}, on any thread. */
    void runLater(Runnable task, long delayTicks);

    /** Repeating off-main-thread task; returns a handle for {@link #cancel(Object)}. */
    Object runAsyncTimer(Runnable task, long delayTicks, long periodTicks);

    void cancel(Object handle);
}

package io.github.dailystruggle.rtp.common.server;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Platform-agnostic periodic async flush of
 * {@link RTP#databaseAccessor}'s pending query queue, scheduled via
 * {@link RTP#scheduler}.
 *
 * <p>This is the cross-process write path that underpins SQL-transport
 * network mode: cooldown rows, reservation tokens (Phase 2+), and
 * player-state rows produced on this backend become visible to peer
 * backends only once {@link io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor#processQueries(long)}
 * drains them onto the shared database. The {@code rtp-core} constructor's
 * 60-tick {@code flush} pass covers prepared-statement flushing only; it
 * does <em>not</em> drain the queued mutation queue serviced here.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Runs every 100 server ticks (~5&nbsp;s) on the async scheduler.</li>
 *   <li>Re-entrant guard via {@code processing} (a long flush cannot
 *       stack on itself).</li>
 *   <li>{@link #kill()} sets a permanent stop flag <em>and</em> cancels
 *       the scheduled task atomically.</li>
 *   <li>{@link #clear()} is safe to call before {@link #start()}
 *       (idempotent).</li>
 *   <li>{@link #start()} is safe to call after a prior {@link #kill()} —
 *       it clears the kill flag, cancels any stale task, and re-arms.</li>
 * </ul>
 *
 * <p>This class is platform-agnostic: every dependency is an
 * {@code rtp-core} surface ({@link RTP#scheduler},
 * {@link RTP#databaseAccessor}). Call from any backend platform entry
 * point (Bukkit/Paper/Folia: {@code RTPBukkitPlugin#onEnable}; Fabric:
 * {@code RTPFabricMod#onInitialize} SERVER_STARTED handler).
 */
public final class DatabaseProcessing {
    private static final AtomicBoolean killed = new AtomicBoolean(false);
    private static final AtomicBoolean processing = new AtomicBoolean(false);
    private static final AtomicReference<Object> task = new AtomicReference<>(null);

    private DatabaseProcessing() {
        // utility — no instances
    }

    /**
     * Cancel the currently-scheduled flush task, if any, atomically.
     *
     * <p>{@code getAndSet} makes the cancel-and-null sequence atomic
     * against concurrent {@code clear()} callers: only the caller that
     * wins the swap sees the prior task reference, so the scheduled task
     * is cancelled exactly once. ({@code start()} vs. {@code start()} and
     * {@code start()} vs. {@code kill()} races still need an external
     * lifecycle guard if invoked from multiple threads.)
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

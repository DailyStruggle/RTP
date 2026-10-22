package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/** Cancels locally tracked non-terminal network queue entries past their configured TTL. */
public final class NetworkQueueEntryReaper {

    private final NetworkRequestQueue queue;
    private final NetworkStatusCache statusCache;
    private final long ttlMs;
    private final LongSupplier clock;
    private final ConcurrentMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    private volatile Object timerTaskHandle;

    public NetworkQueueEntryReaper(
            NetworkRequestQueue queue, NetworkStatusCache statusCache, long ttlMs, LongSupplier clock) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.statusCache = Objects.requireNonNull(statusCache, "statusCache");
        if (ttlMs <= 0L) throw new IllegalArgumentException("ttlMs must be positive");
        this.ttlMs = ttlMs;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Cancels every eligible expired entry not already being cancelled. */
    int sweepOnce() {
        long now = clock.getAsLong();
        int cancelled = 0;
        for (Map.Entry<UUID, NetworkStatusCache.QueueStatus> entry : statusCache.snapshot().entrySet()) {
            NetworkStatusCache.QueueStatus status = entry.getValue();
            if (status == null || !status.nonTerminal() || status.updatedAtMs() <= 0L
                    || now < status.updatedAtMs() || now - status.updatedAtMs() < ttlMs) {
                continue;
            }
            UUID playerId = entry.getKey();
            if (inFlight.putIfAbsent(playerId, Boolean.TRUE) != null) continue;
            cancelled++;
            cancelExpired(playerId);
        }
        return cancelled;
    }

    private void cancelExpired(UUID playerId) {
        try {
            CompletableFuture<Void> cancellation = queue.cancel(playerId, CancelReason.TTL_EXPIRED);
            if (cancellation == null) {
                throw new IllegalStateException("queue.cancel returned a null future");
            }
            cancellation.whenComplete((ignored, failure) -> {
                try {
                    if (failure != null) logFailure(playerId, failure);
                } finally {
                    inFlight.remove(playerId);
                }
            });
        } catch (Throwable failure) {
            inFlight.remove(playerId);
            logFailure(playerId, failure);
        }
    }

    private void logFailure(UUID playerId, Throwable failure) {
        RTPRuntime.log(Level.WARNING,
                "[RTP] failed to cancel expired network queue entry for " + playerId
                        + ": " + failure.getMessage() + " (S-004).", failure);
    }

    /** Starts an asynchronous, idempotent periodic sweep. */
    public void start(long periodTicks) {
        synchronized (this) {
            if (timerTaskHandle != null) return;
            if (RTPRuntime.scheduler() == null) {
                RTPRuntime.log(Level.WARNING,
                        "[RTP] NetworkQueueEntryReaper.start called before scheduler available; "
                                + "queue TTL sweep not started.");
                return;
            }
            long ticks = Math.max(1L, periodTicks);
            timerTaskHandle = RTPRuntime.scheduler().runTaskTimerAsynchronously(
                    () -> sweepOnce(), ticks, ticks);
        }
    }

    /** Stops the timer and forgets pending cancellation markers. Idempotent. */
    public void shutdown() {
        synchronized (this) {
            if (timerTaskHandle != null) {
                if (RTPRuntime.scheduler() == null) {
                    RTPRuntime.log(Level.WARNING,
                            "[RTP] NetworkQueueEntryReaper could not cancel its timer; scheduler is unavailable.");
                } else {
                    try {
                        RTPRuntime.scheduler().cancelTask(timerTaskHandle);
                    } catch (Throwable failure) {
                        RTPRuntime.log(Level.WARNING,
                                "[RTP] NetworkQueueEntryReaper failed to cancel its timer: "
                                        + failure.getMessage(), failure);
                    }
                }
            }
            timerTaskHandle = null;
            inFlight.clear();
        }
    }
}

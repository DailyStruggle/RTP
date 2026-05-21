package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.common.RTP;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Read-side cache for per-player cross-server queue status. Slice C row C6.
 *
 * <p>A single async timer polls the injected {@code statusSupplier} on a
 * fixed interval (default {@code network.queuePollIntervalMs = 1000ms}) and
 * replaces this cache's contents wholesale. The {@code /rtp} command path
 * and any UI / status emitter reads {@link #get(UUID)} and never blocks on
 * the transport. Message emission per {@code REQ-RTP-F-013} is the caller's
 * responsibility - this cache is data only.</p>
 *
 * <p>The {@link QueueStatus} record is the slice-C-local shape; Slice D's
 * {@code NetworkRequestQueue.pollStatus} returns the same fields and a
 * thin adapter feeds them in here.</p>
 */
public final class NetworkStatusCache {

    /** Per-player queue snapshot. */
    public record QueueStatus(
            UUID playerId,
            State state,
            int positionInQueue,
            Optional<String> serverId,
            Optional<String> regionKey,
            long updatedAtMs
    ) {
        public QueueStatus {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(state, "state");
            if (serverId == null) serverId = Optional.empty();
            if (regionKey == null) regionKey = Optional.empty();
            if (positionInQueue < 0) positionInQueue = 0;
        }

        /** Terminal states map to a configurable {@code messages.yml} key (G4). */
        public enum State {
            QUEUED, ROUTING, RESERVED, TRANSFERRING, COMPLETED, FAILED, CANCELLED, UNKNOWN
        }
    }

    private final Supplier<Collection<QueueStatus>> statusSupplier;
    private final ConcurrentHashMap<UUID, QueueStatus> byPlayer = new ConcurrentHashMap<>();
    private volatile Object timerTaskHandle;

    public NetworkStatusCache(Supplier<Collection<QueueStatus>> statusSupplier) {
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
    }

    /** Read entry. Lock-free. */
    public Optional<QueueStatus> get(UUID playerId) {
        if (playerId == null) return Optional.empty();
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    /** Visible for tests / metrics. */
    public int size() { return byPlayer.size(); }

    /** Unmodifiable read-only view, mostly for tests. */
    public java.util.Map<UUID, QueueStatus> snapshot() {
        return Collections.unmodifiableMap(byPlayer);
    }

    /**
     * Start the periodic poll timer on {@link RTP#scheduler}'s async tier.
     * Idempotent.
     */
    public synchronized void start(long periodTicks) {
        if (timerTaskHandle != null) return;
        if (RTP.scheduler == null) {
            RTP.log(Level.WARNING,
                    "[NETWORK] NetworkStatusCache.start called before scheduler available; "
                            + "poll timer not started.");
            return;
        }
        timerTaskHandle = RTP.scheduler.runTaskTimerAsynchronously(this::pollOnce, periodTicks, periodTicks);
    }

    /**
     * Manual poll. Visible for tests; also called by the timer. Pulls the
     * full status set from the supplier and replaces our map's contents.
     * Players absent from the returned collection are evicted (their token
     * either completed, was cancelled, or aged out of the proxy view).
     */
    public void pollOnce() {
        Collection<QueueStatus> latest;
        try {
            latest = statusSupplier.get();
        } catch (Throwable t) {
            // S-004: never silently swallow. We keep the previous cache so
            // readers still see the last-known state until the next pulse.
            RTP.log(Level.WARNING,
                    "[NETWORK] status poll failed: " + t.getMessage(), t);
            return;
        }
        if (latest == null) {
            byPlayer.clear();
            return;
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>(latest.size());
        for (QueueStatus s : latest) {
            if (s == null) continue;
            byPlayer.put(s.playerId(), s);
            seen.add(s.playerId());
        }
        byPlayer.keySet().removeIf(uuid -> !seen.contains(uuid));
    }

    /** Idempotent. */
    public synchronized void shutdown() {
        if (timerTaskHandle != null && RTP.scheduler != null) {
            try { RTP.scheduler.cancelTask(timerTaskHandle); } catch (Throwable ignored) { /* best-effort */ }
        }
        timerTaskHandle = null;
        byPlayer.clear();
    }
}

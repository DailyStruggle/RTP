package io.github.dailystruggle.rtp.common.network;

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
 * Read-side cache for per-player cross-server queue status.
 *
 * <p>A single async timer polls the injected {@code statusSupplier} on a
 * fixed interval (default {@code network.queuePollIntervalMs = 1000ms}) and
 * replaces this cache's contents wholesale. The {@code /rtp} command path
 * and any UI / status emitter reads {@link #get(UUID)} and never blocks on
 * the transport. Message emission per {@code REQ-RTP-F-013} is the caller's
 * responsibility - this cache is data only.</p>
 *
 * <p>The {@link QueueStatus} record is the local shape; the
 * {@code NetworkRequestQueue.pollStatus} returns the same fields and a
 * thin adapter feeds them in here.</p>
 */
public final class NetworkStatusCache {

    /**
     * Per-player queue snapshot.
     *
     * @param playerId        UUID of the player
     * @param state           current queue state
     * @param positionInQueue zero-based position in the queue; 0 when not queued
     * @param serverId        optional backend server id the player is being routed to
     * @param regionKey       optional region key associated with this enrolment
     * @param updatedAtMs     wall-clock time of the last status update in milliseconds
     */
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
            QUEUED,
            /**
             * ADR-015 / REQ-RTP-NET-015: the request
             * is parked on the shared cross-proxy {@code NetworkWaitlist}
             * because no backend qualified at enrolment time. The player has
             * not been routed; they are awaiting the {@code NetworkWaitlistDrainer}.
             * Non-terminal: command-lock applies, periodic notify applies,
             * quit-event removal applies.
             */
            WAITLISTED,
            ROUTING, RESERVED, TRANSFERRING, COMPLETED, FAILED, CANCELLED, UNKNOWN
        }

        /**
         * @return {@code true} when this state is non-terminal (the player
         *     still has an active cross-server enrolment and a new
         *     {@code /rtp} should be rejected with {@code msgAlreadyQueued}).
         *     {@code WAITLISTED} is included here per REQ-RTP-NET-015.
         */
        public boolean nonTerminal() {
            return state == State.QUEUED
                    || state == State.WAITLISTED
                    || state == State.ROUTING
                    || state == State.RESERVED
                    || state == State.TRANSFERRING;
        }
    }

    private final Supplier<Collection<QueueStatus>> statusSupplier;
    private final ConcurrentHashMap<UUID, QueueStatus> byPlayer = new ConcurrentHashMap<>();
    private volatile Object timerTaskHandle;
    /**
     * Terminal-transition observer. Fires when a player's queue entry
     * moves non-terminal -> (terminal state OR eviction). Used by the
     * lobby to release the local {@code processingPlayers} lock once
     * the proxy reports COMPLETED / FAILED / CANCELLED, or once the
     * proxy stops reporting them entirely (eviction = the proxy
     * dropped the row, which is itself terminal from our POV).
     * Default no-op; bootstrap installs the real listener.
     */
    private volatile java.util.function.BiConsumer<UUID, QueueStatus.State> terminalListener = (u, s) -> {};

    /**
     * Locally-seeded enrolments that the supplier has not yet confirmed.
     * A lobby cross-server enrolment is recorded here at the moment we
     * hand the envelope to the enrolment buffer (well before the proxy
     * acks the row into the status supplier's view). Entries are sticky:
     * pollOnce will NOT evict them while they remain in this set, even
     * if the supplier returns an empty snapshot. Once the supplier
     * starts reporting the row, pollOnce removes it from here. If a
     * locally-seeded entry exceeds {@link #seededTimeoutMs} without
     * being confirmed by the supplier, pollOnce drops it AND fires the
     * terminal listener with {@code FAILED} so the lobby releases the
     * processingPlayers lock and the player can retry.
     */
    private final ConcurrentHashMap<UUID, Long> seededAtMs = new ConcurrentHashMap<>();
    /** TTL for {@link #seededAtMs}; default 20s. Settable for tests. */
    private volatile long seededTimeoutMs = 20_000L;

    public NetworkStatusCache(Supplier<Collection<QueueStatus>> statusSupplier) {
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
    }

    /** Override the locally-seeded TTL (default 20s). Visible for tests. */
    public void setSeededTimeoutMs(long ms) { this.seededTimeoutMs = Math.max(0L, ms); }

    /**
     * Record a locally-seeded cross-server enrolment that the supplier
     * has not yet acked. Idempotent: re-seeding refreshes the timestamp.
     * Also installs a synthetic {@code QUEUED} row in {@link #byPlayer}
     * so {@code get(uuid).nonTerminal()} reports true to anti-spam guards
     * even before the first supplier roundtrip.
     */
    public void seedLocal(UUID playerId) {
        if (playerId == null) return;
        long now = System.currentTimeMillis();
        boolean firstSeed = seededAtMs.put(playerId, now) == null;
        // Only install a synthetic QUEUED row if the supplier has not
        // already reported something authoritative. Don't clobber a
        // RESERVED / TRANSFERRING state with a fresh QUEUED.
        byPlayer.compute(playerId, (k, prev) -> {
            if (prev != null) return prev;
            return new QueueStatus(playerId, QueueStatus.State.QUEUED, 0,
                    Optional.empty(), Optional.empty(), now);
        });
        // State-transition log: emitted at FINE (operator log volume).
        // The non-state-transition INFO surface for network mode is the
        // bootstrap banner in NetworkModeBootstrap; per-player traces
        // live here at FINE.
        RTP.log(Level.FINE,
                "[RTP][state] " + (firstSeed ? "seeded" : "re-seeded")
                        + " local QUEUED row: playerId=" + playerId
                        + " ttlMs=" + seededTimeoutMs);
    }

    /**
     * Install a terminal-transition listener. Called from pollOnce when a
     * UUID that was previously in the cache with {@link QueueStatus#nonTerminal()}
     * either appears in a terminal state OR vanishes from the supplier's
     * snapshot (proxy-side eviction). The {@code state} arg is the new
     * terminal state, or {@code null} when the row was evicted entirely.
     * Listener exceptions are caught and logged (S-004).
     */
    public void setTerminalListener(java.util.function.BiConsumer<UUID, QueueStatus.State> listener) {
        this.terminalListener = listener == null ? (u, s) -> {} : listener;
    }

    /** Read entry. Lock-free. */
    public Optional<QueueStatus> get(UUID playerId) {
        if (playerId == null) return Optional.empty();
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    /**
     * Locally evict any cached row for {@code playerId} (and clear any
     * sticky local seed). Used by {@code JoinTriggerSource} on a successful
     * cross-server redeem: once the proxy token has transitioned to
     * {@code CONSUMED}, the lobby-seeded / proxy-poller-seeded non-terminal
     * row on this backend is stale and would cause
     * {@link NetworkWaitlistGuard} to short-circuit the post-arrival
     * {@code /rtp} dispatch with {@code msgAlreadyQueued}, leaving the
     * arrival without a teleport (REQ-RTP-S-004, REQ-RTP-NET-008/-015).
     *
     * <p>This is a deliberate local-state correction, not a proxy state
     * change, so the terminal listener is intentionally NOT fired: the
     * authoritative terminal transition will be observed (and fired) by
     * {@link #pollOnce} on the next supplier roundtrip. Idempotent; null
     * tolerant.</p>
     */
    public void evictLocal(UUID playerId) {
        if (playerId == null) return;
        seededAtMs.remove(playerId);
        QueueStatus prev = byPlayer.remove(playerId);
        if (prev != null) {
            RTP.log(Level.FINE,
                    "[RTP][state] evictLocal: playerId=" + playerId
                            + " priorState=" + prev.state().name());
        }
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
                    "[RTP] NetworkStatusCache.start called before scheduler available; "
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
                    "[RTP] status poll failed: " + t.getMessage(), t);
            return;
        }
        // Snapshot prior non-terminal entries before we mutate the map so
        // we can detect non-terminal -> (terminal or eviction) transitions
        // and fire the listener exactly once per transition.
        java.util.Map<UUID, QueueStatus> priorNonTerminal = new java.util.HashMap<>();
        for (java.util.Map.Entry<UUID, QueueStatus> e : byPlayer.entrySet()) {
            if (e.getValue() != null && e.getValue().nonTerminal()) {
                priorNonTerminal.put(e.getKey(), e.getValue());
            }
        }
        if (latest == null) {
            // Supplier returned null (defensive). Treat the same way as
            // "supplier returned an empty collection": preserve sticky-
            // seeded rows within TTL, drop everything else.
            latest = java.util.Collections.emptyList();
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>(latest.size());
        for (QueueStatus s : latest) {
            if (s == null) continue;
            QueueStatus prev = byPlayer.put(s.playerId(), s);
            seen.add(s.playerId());
            // Supplier has now confirmed this UUID -> drop any local seed.
            seededAtMs.remove(s.playerId());
            // State-transition log for supplier-confirmed non-terminal
            // progress (QUEUED -> RESERVED -> TRANSFERRING, position
            // changes, etc.). Terminal transitions are logged by
            // fireTerminal; skip here to avoid double-logging.
            if (s.nonTerminal()) {
                QueueStatus.State prevState = (prev == null) ? null : prev.state();
                int prevPos = (prev == null) ? -1 : prev.positionInQueue();
                if (prevState != s.state() || prevPos != s.positionInQueue()) {
                    RTP.log(Level.FINE,
                            "[RTP][state] supplier: playerId=" + s.playerId()
                                    + " " + (prevState == null ? "NEW" : prevState.name())
                                    + "(pos=" + prevPos + ") -> "
                                    + s.state().name() + "(pos=" + s.positionInQueue() + ")");
                }
            }
        }
        // Evict supplier-absent rows EXCEPT those still sticky-seeded
        // within TTL. Seeded rows past TTL get evicted AND fire the
        // terminal listener so the lobby releases its lock.
        long now = System.currentTimeMillis();
        java.util.List<UUID> seedTimedOut = new java.util.ArrayList<>();
        byPlayer.keySet().removeIf(uuid -> {
            if (seen.contains(uuid)) return false;
            Long seededAt = seededAtMs.get(uuid);
            if (seededAt != null && (now - seededAt) <= seededTimeoutMs) {
                // Still sticky: keep the row.
                return false;
            }
            if (seededAt != null) {
                // Was sticky, TTL exceeded: drop seed + fire terminal.
                seededAtMs.remove(uuid);
                seedTimedOut.add(uuid);
            }
            return true;
        });
        // Fire terminal listener for: (a) prior non-terminal rows now absent
        // from the supplier AND not still sticky-seeded AND not a TTL-expired
        // seed (those are handled below with the FAILED state); (b) prior
        // non-terminal rows now in a terminal state in the new snapshot;
        // (c) sticky-seeded rows whose TTL just expired (fired as FAILED).
        java.util.Set<UUID> seedTimedOutSet = new java.util.HashSet<>(seedTimedOut);
        for (java.util.Map.Entry<UUID, QueueStatus> e : priorNonTerminal.entrySet()) {
            UUID u = e.getKey();
            if (!seen.contains(u)) {
                if (seedTimedOutSet.contains(u)) {
                    continue; // handled below as FAILED, not as null-eviction.
                }
                Long seededAt = seededAtMs.get(u);
                if (seededAt == null) {
                    // No longer seeded -> evicted by the proxy -> terminal.
                    fireTerminal(u, null);
                }
                // else: still sticky within TTL, no transition yet.
            } else {
                QueueStatus nowEntry = byPlayer.get(u);
                if (nowEntry != null && !nowEntry.nonTerminal()) {
                    fireTerminal(u, nowEntry.state());
                }
            }
        }
        for (UUID u : seedTimedOut) {
            fireTerminal(u, QueueStatus.State.FAILED);
        }
    }

    private void fireTerminal(UUID playerId, QueueStatus.State newState) {
        // State-transition log: non-terminal -> terminal (or supplier
        // eviction when newState==null, sticky-TTL FAILED, etc.). One
        // line per UUID per transition; bounded by exactly-once firing.
        RTP.log(Level.FINE,
                "[RTP][state] terminal: playerId=" + playerId
                        + " newState=" + (newState == null ? "EVICTED" : newState.name()));
        try {
            terminalListener.accept(playerId, newState);
        } catch (Throwable t) {
            // S-004: log, never swallow.
            RTP.log(Level.WARNING,
                    "[RTP] terminal listener threw for " + playerId
                            + " (state=" + newState + "): " + t.getMessage(), t);
        }
    }

    /** Idempotent. */
    public synchronized void shutdown() {
        if (timerTaskHandle != null && RTP.scheduler != null) {
            try { RTP.scheduler.cancelTask(timerTaskHandle); } catch (Throwable ignored) { /* best-effort */ }
        }
        timerTaskHandle = null;
        byPlayer.clear();
        seededAtMs.clear();
    }
}

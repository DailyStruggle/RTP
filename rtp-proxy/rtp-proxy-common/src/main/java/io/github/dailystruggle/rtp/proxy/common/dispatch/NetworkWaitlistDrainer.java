package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.TriggerType;
import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxy-side daemon body that drains the shared cross-proxy
 * {@link NetworkWaitlist} into the configured {@link RtpDispatcher}. Slice 2
 * of `CHECKLIST-network-waitlist.md`; design in
 * `rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md`.
 *
 * <p><strong>Pure pulse logic.</strong> This class does <em>not</em> own a
 * scheduler. The proxy adapter (Velocity, BungeeCord, future) owns the
 * {@code ScheduledExecutorService} that calls {@link #runPulse()} on its
 * own cadence. Reasons:</p>
 * <ul>
 *   <li>Proxy JVMs cannot reach {@code RTP.scheduler} (it lives in the
 *       backend plugin), so a "scheduler" carve-out is legitimate per
 *       AGENTS.md "Scheduler Usage" section but kept at the adapter layer
 *       rather than common.</li>
 *   <li>Testability: a single {@code runPulse()} call is deterministic
 *       (`CompletableFuture` join in tests) without time-faking.</li>
 * </ul>
 *
 * <p><strong>Pulse algorithm</strong> (per ADR-015 Section "Drain loop"):</p>
 * <ol>
 *   <li>If currently inside a pause window (set by a previous total-failure
 *       pulse), resolve with {@link PulseResult#SKIPPED_PAUSED}.</li>
 *   <li>{@link WaitlistLeaderLease#tryAcquire(Duration) tryAcquire} the
 *       leadership lease. Losers resolve with
 *       {@link PulseResult#SKIPPED_NOT_LEADER}.</li>
 *   <li>{@link NetworkWaitlist#size()} short-circuit: zero -> {@link
 *       PulseResult#EMPTY}.</li>
 *   <li>{@link NetworkTransport#readSnapshot()}; compute per-backend
 *       capacity from heartbeats. A backend qualifies when:
 *       <ul>
 *         <li>{@code acceptingRequests == true}</li>
 *         <li>{@code killSwitch == false}</li>
 *         <li>{@code pluginState == READY}</li>
 *       </ul>
 *       Qualifying capacity = {@code max(0, sum(regionKeptCounts) -
 *       networkReservedCount)}. Equivalent to "how many cross-server
 *       envelopes can this backend serve right now from its
 *       {@code networkKeptLocations} pool".</li>
 *   <li>If the global cap is zero, resolve with
 *       {@link PulseResult#NO_CAPACITY}.</li>
 *   <li>{@link NetworkWaitlist#drainBatch(Map, int)} with the per-backend
 *       caps. The waitlist preserves FIFO across the drain.</li>
 *   <li>Re-dispatch each drained envelope via the configured
 *       {@link RtpDispatcher}. The dispatcher's normal pipeline applies
 *       (selector -> claim -> transfer -> status sink).</li>
 *   <li>If <em>every</em> drained envelope failed (no
 *       {@link DispatchOutcome.Routed} and no {@link DispatchOutcome.Queued}
 *       returned), the network is unable to serve right now: call
 *       {@link NetworkWaitlist#refreshAllTtl()} so live entries do not age
 *       out due to the partition, then set a pause window. The window
 *       skips the next {@link #pauseFor} of wall-clock so the drainer does
 *       not hammer a dead network.</li>
 * </ol>
 *
 * <p><strong>S-004 contract.</strong> Every exceptional path is logged and
 * surfaced through the returned {@code CompletableFuture<PulseResult>} -
 * never swallowed. Per-envelope dispatch failures are <em>not</em>
 * propagated up (each envelope's dispatcher already accounts for its own
 * status sink); the drainer reports an aggregate count.</p>
 */
public final class NetworkWaitlistDrainer {

    private static final Logger LOG = Logger.getLogger(NetworkWaitlistDrainer.class.getName());

    /** Default lease hold; one drain cycle should fit comfortably inside this. */
    public static final Duration DEFAULT_LEASE_HOLD = Duration.ofSeconds(2);

    /** Default backoff after an all-failed pulse. */
    public static final Duration DEFAULT_PAUSE_AFTER_TOTAL_FAILURE = Duration.ofSeconds(2);

    private final NetworkWaitlist waitlist;
    private final NetworkTransport transport;
    private final RtpDispatcher dispatcher;
    private final WaitlistLeaderLease lease;
    private final Duration leaseHold;
    private final Duration pauseFor;

    /** Wall-clock millis until which {@link #runPulse()} short-circuits to {@link PulseResult#SKIPPED_PAUSED}. */
    private final AtomicLong pausedUntilEpochMs = new AtomicLong(0L);

    public NetworkWaitlistDrainer(NetworkWaitlist waitlist,
                                  NetworkTransport transport,
                                  RtpDispatcher dispatcher,
                                  WaitlistLeaderLease lease) {
        this(waitlist, transport, dispatcher, lease, DEFAULT_LEASE_HOLD,
                DEFAULT_PAUSE_AFTER_TOTAL_FAILURE);
    }

    public NetworkWaitlistDrainer(NetworkWaitlist waitlist,
                                  NetworkTransport transport,
                                  RtpDispatcher dispatcher,
                                  WaitlistLeaderLease lease,
                                  Duration leaseHold,
                                  Duration pauseFor) {
        this.waitlist = Objects.requireNonNull(waitlist, "waitlist");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.leaseHold = Objects.requireNonNull(leaseHold, "leaseHold");
        this.pauseFor = Objects.requireNonNull(pauseFor, "pauseFor");
        if (leaseHold.isZero() || leaseHold.isNegative()) {
            throw new IllegalArgumentException("leaseHold must be positive");
        }
        if (pauseFor.isNegative()) {
            throw new IllegalArgumentException("pauseFor must be non-negative");
        }
    }

    /** Aggregate outcome of one pulse. Surfaces enough state for operator metrics and tests. */
    public enum PulseResult {
        /** Pulse fired inside a pause window from a prior total-failure pulse; no work done. */
        SKIPPED_PAUSED,
        /** Another proxy holds the leadership lease this tick. */
        SKIPPED_NOT_LEADER,
        /** Waitlist is empty; trivially nothing to do. */
        EMPTY,
        /** Waitlist is non-empty but no backend has capacity right now; deferred to next pulse. */
        NO_CAPACITY,
        /** At least one drained envelope was routed or queued by the dispatcher. */
        PROGRESS,
        /** Every drained envelope failed; waitlist TTLs refreshed and drainer paused. */
        TOTAL_FAILURE
    }

    /** Run one pulse. Resolves on the future returned by {@link RtpDispatcher#dispatch}'s downstream executor. */
    public CompletableFuture<PulseResult> runPulse() {
        long now = System.currentTimeMillis();
        if (now < pausedUntilEpochMs.get()) {
            return CompletableFuture.completedFuture(PulseResult.SKIPPED_PAUSED);
        }
        return lease.tryAcquire(leaseHold)
                .thenCompose(isLeader -> {
                    if (!Boolean.TRUE.equals(isLeader)) {
                        return CompletableFuture.completedFuture(PulseResult.SKIPPED_NOT_LEADER);
                    }
                    return waitlist.size().thenCompose(size -> {
                        if (size == null || size <= 0) {
                            return CompletableFuture.completedFuture(PulseResult.EMPTY);
                        }
                        return transport.readSnapshot().thenCompose(snapshot -> drainAgainst(snapshot));
                    });
                })
                .exceptionally(err -> {
                    Throwable cause = unwrap(err);
                    LOG.log(Level.WARNING,
                            "NetworkWaitlistDrainer pulse threw " + cause.getClass().getSimpleName()
                                    + ": " + cause.getMessage(), cause);
                    // S-004: surface as a definite (non-progress) outcome rather than a hung future.
                    return PulseResult.NO_CAPACITY;
                });
    }

    private CompletableFuture<PulseResult> drainAgainst(NetworkSnapshot snapshot) {
        Map<String, Integer> perBackendCaps = computePerBackendCaps(snapshot);
        int globalCap = 0;
        for (int v : perBackendCaps.values()) globalCap += v;
        if (globalCap <= 0) {
            return CompletableFuture.completedFuture(PulseResult.NO_CAPACITY);
        }
        final int finalGlobalCap = globalCap;
        return waitlist.drainBatch(perBackendCaps, finalGlobalCap).thenCompose(this::dispatchDrained);
    }

    /**
     * Build per-backend capacity from the heartbeat snapshot. A backend
     * contributes only when ready, accepting requests, and not
     * kill-switched (priority filter); its cap is
     * {@code max(0, sum(regionKeptCounts) - networkReservedCount)}.
     */
    static Map<String, Integer> computePerBackendCaps(NetworkSnapshot snapshot) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (BackendHeartbeat hb : snapshot.all()) {
            if (!hb.acceptingRequests()) continue;
            if (hb.killSwitch()) continue;
            if (hb.pluginState() != BackendHeartbeat.PluginState.READY) continue;
            int kept = 0;
            for (Integer v : hb.regionKeptCounts().values()) {
                if (v != null && v > 0) kept += v;
            }
            int available = kept - hb.networkReservedCount();
            if (available <= 0) continue;
            out.put(hb.serverId(), available);
        }
        return out;
    }

    private CompletableFuture<PulseResult> dispatchDrained(Map<String, List<NetworkWaitlist.WaitEnvelope>> byBackend) {
        if (byBackend == null || byBackend.isEmpty()) {
            // Waitlist was non-empty but no envelope matched any qualifying
            // backend (head-blocking on a region nobody serves). Defer.
            return CompletableFuture.completedFuture(PulseResult.NO_CAPACITY);
        }
        // Collect per-envelope dispatch futures. We don't ferry the chosen
        // serverId into RtpRequest - the dispatcher's selector decides
        // again; the drained-by-backend map is the waitlist's allocation
        // hint, not a binding directive.
        @SuppressWarnings("unchecked")
        CompletableFuture<DispatchOutcome>[] futures =
                byBackend.values().stream()
                        .flatMap(List::stream)
                        .map(this::redispatch)
                        .toArray(CompletableFuture[]::new);
        if (futures.length == 0) {
            return CompletableFuture.completedFuture(PulseResult.NO_CAPACITY);
        }
        return CompletableFuture.allOf(futures).thenCompose(v -> {
            int progressed = 0;
            int failed = 0;
            for (CompletableFuture<DispatchOutcome> f : futures) {
                DispatchOutcome outcome = f.getNow(null);
                if (outcome instanceof DispatchOutcome.Routed
                        || outcome instanceof DispatchOutcome.Queued) {
                    progressed++;
                } else {
                    failed++;
                }
            }
            if (progressed > 0) {
                return CompletableFuture.completedFuture(PulseResult.PROGRESS);
            }
            // Every drained envelope failed - refresh TTLs across the whole
            // waitlist and pause the drainer briefly. Per ADR-015, this is
            // the "the network can't serve anyone right now" backoff path.
            LOG.log(Level.WARNING,
                    "NetworkWaitlistDrainer: all {0} drained envelope(s) failed dispatch; "
                            + "refreshing waitlist TTLs and pausing for {1} ms.",
                    new Object[]{failed, pauseFor.toMillis()});
            pausedUntilEpochMs.set(System.currentTimeMillis() + pauseFor.toMillis());
            return waitlist.refreshAllTtl()
                    .thenApply(n -> PulseResult.TOTAL_FAILURE)
                    .exceptionally(err -> {
                        LOG.log(Level.WARNING,
                                "NetworkWaitlistDrainer: refreshAllTtl threw " + err.getMessage(),
                                err);
                        return PulseResult.TOTAL_FAILURE;
                    });
        });
    }

    private CompletableFuture<DispatchOutcome> redispatch(NetworkWaitlist.WaitEnvelope env) {
        // Preserve the upstream serverHint across the waitlist round trip
        // (2026-05-23 fix): an envelope parked here because the pinned
        // backend was momentarily ineligible MUST still arrive at the
        // dispatcher with its hint, otherwise the drainer silently
        // load-balances cross-server /rtp that the player had explicitly
        // pinned. originServerId stays as the genuine origin (it is a
        // proxy-side metric, distinct from the player-supplied hint).
        RtpRequest request = new RtpRequest(
                env.playerId(),
                TriggerType.COMMAND,
                env.regionKey(),
                /*worldKey=*/Optional.empty(),
                /*serverHint=*/env.serverHint(),
                /*originServerId=*/Optional.of(env.originServerId()),
                env.correlationId());
        try {
            return dispatcher.dispatch(request);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING,
                    "NetworkWaitlistDrainer: dispatcher.dispatch threw for player "
                            + env.playerId() + " (correlationId=" + env.correlationId() + ")",
                    ex);
            return CompletableFuture.completedFuture(
                    new DispatchOutcome.Failed(DispatchOutcome.Failed.Reason.INTERNAL,
                            "rtp.network.internal-error"));
        }
    }

    /** Test/operator helper: clear any active pause window so the next pulse runs immediately. */
    public void resumeNow() {
        pausedUntilEpochMs.set(0L);
    }

    /** Test helper: epoch millis until which the drainer is paused, or 0 if not paused. */
    public long pausedUntilEpochMs() {
        return pausedUntilEpochMs.get();
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    /** Resolve the player's effective UUID; null-guarded for S-006 sanity. */
    @SuppressWarnings("unused")
    private static UUID safeId(UUID id) {
        return Objects.requireNonNull(id, "playerId");
    }
}

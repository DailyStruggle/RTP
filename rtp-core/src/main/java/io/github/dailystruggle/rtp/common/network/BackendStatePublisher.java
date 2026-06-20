package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend-side heartbeat loop: ticks at the configured interval, asks the
 * registered {@link BackendStateSampler} for a fresh
 * {@link BackendHeartbeat}, and hands it to
 * {@link NetworkTransport#publishBackendHeartbeat(BackendHeartbeat)}.
 *
 * <p>Pinned by rtp-proxy-ADR-011 §Backend Wiring. Mirrors the proxy-side
 * {@code ProxyStatePublisher} in {@code rtp-proxy-common} but ships in
 * {@code rtp-core} because every backend platform inherits the same loop.</p>
 *
 * <p>Lifecycle is owned by the host adapter: it constructs an instance
 * during {@code onEnable} (after {@code RTP.serverAccessor.start} and the
 * network transport is open), calls {@link #start()} once, and calls
 * {@link #stop()} during {@code onDisable} BEFORE the transport is closed.
 * Re-entrant {@code start} is a no-op; {@code stop} is idempotent.</p>
 *
 * <p>Scheduling is delegated to {@link RTP#scheduler} via
 * {@code runTaskTimerAsynchronously}. This is the only Folia-safe
 * scheduling path; do NOT replace it with a raw
 * {@link java.util.concurrent.ScheduledExecutorService} or
 * {@code new Thread(...)} - see {@code .junie/AGENTS.md} "Scheduler Usage".</p>
 *
 * <p>Failure isolation: a thrown exception from the sampler or transport is
 * logged once via {@link io.github.dailystruggle.rtp.common.RTP#log} and the
 * loop continues; one bad tick must not kill heartbeat publication.</p>
 */
public final class BackendStatePublisher {

    private final NetworkTransport transport;
    private final BackendStateSampler sampler;
    private final String serverId;
    private final long intervalMs;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Platform-scheduler task handle for the periodic tick.
     * Stored so {@link #stop()} can cancel via {@code RTP.scheduler.cancelTask}.
     */
    private volatile Object task;

    /**
     * Constructs a publisher that will tick at the given interval.
     *
     * @param transport  the network transport to publish heartbeats to; never {@code null}
     * @param sampler    the sampler that produces each {@link BackendHeartbeat}; never {@code null}
     * @param serverId   this backend's server id; never {@code null}
     * @param intervalMs publish interval in milliseconds; must be &gt; 0
     */
    public BackendStatePublisher(NetworkTransport transport,
                                 BackendStateSampler sampler,
                                 String serverId,
                                 long intervalMs) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be > 0, got " + intervalMs);
        }
        this.intervalMs = intervalMs;
    }

    /** Start the periodic tick. Idempotent. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        if (RTP.scheduler == null) {
            // No scheduler installed (e.g. early boot path on an adapter that
            // wires the publisher before RTP.scheduler is assigned). Caller
            // can drive tick() manually; do not silently no-op forever.
            RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] BackendStatePublisher.start(): RTP.scheduler is null; "
                            + "periodic tick disabled for server=" + serverId);
            return;
        }
        // Convert intervalMs to server ticks (1 tick == 50 ms), clamped >= 1.
        long periodTicks = Math.max(1L, intervalMs / 50L);
        this.task = RTP.scheduler.runTaskTimerAsynchronously(this::tick, periodTicks, periodTicks);
    }

    /** Stop the periodic tick and release the scheduler handle. Idempotent. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        Object t = this.task;
        this.task = null;
        if (t != null && RTP.scheduler != null) {
            try { RTP.scheduler.cancelTask(t); }
            catch (Throwable ignored) { /* best-effort */ }
        }
    }

    /**
     * Force an immediate, off-cadence heartbeat publish. Used by the
     * lobby-mode no-arg {@code /rtp} hook after a cross-server dispatch so
     * the transport's snapshot reflects the local optimistic decrement (a
     * lower {@code keptCount} for the chosen peer) without waiting for the
     * next scheduled {@link #tick()}. Safe to call from any thread; the
     * transport binding handles its own thread affinity. Failure-isolated
     * the same way {@link #tick()} is.
     */
    public void publishNow() {
        tick();
    }

    /** Visible for tests. Publishes one heartbeat now; never throws. */
    void tick() {
        try {
            BackendHeartbeat row = sampler.sample(serverId);
            if (row == null) return;
            transport.publishBackendHeartbeat(row);
        } catch (RuntimeException e) {
            // RTP.log routes through the canonical accessor; safe to call here.
            io.github.dailystruggle.rtp.common.RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] BackendStatePublisher tick failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns this backend's server id.
     *
     * @return the server id
     */
    public String serverId() { return serverId; }

    /**
     * Returns the publish interval in milliseconds.
     *
     * @return the interval in milliseconds
     */
    public long intervalMs() { return intervalMs; }
}

package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * <p>Failure isolation: a thrown exception from the sampler or transport is
 * logged once via {@link io.github.dailystruggle.rtp.common.RTP#log} and the
 * loop continues; one bad tick must not kill heartbeat publication.</p>
 */
public final class BackendStatePublisher {

    private final NetworkTransport transport;
    private final BackendStateSampler sampler;
    private final String serverId;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

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
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rtp-backend-heartbeat-" + serverId);
            t.setDaemon(true);
            return t;
        });
    }

    /** Start the periodic tick. Idempotent. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        scheduler.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Stop the periodic tick and release the scheduler. Idempotent. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
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
                    "[NETWORK] BackendStatePublisher tick failed: " + e.getMessage(), e);
        }
    }

    public String serverId() { return serverId; }
    public long intervalMs() { return intervalMs; }
}

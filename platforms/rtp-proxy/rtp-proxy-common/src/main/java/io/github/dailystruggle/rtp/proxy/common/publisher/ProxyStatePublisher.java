package io.github.dailystruggle.rtp.proxy.common.publisher;

import io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor;
import io.github.dailystruggle.rtp.proxy.common.RtpProxy;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically builds a {@link ProxyHeartbeat} and hands it to the bound
 * {@link NetworkTransport}. Skeleton form (REQ-RTP-PROXY-VELOCITY-006): the
 * publisher knows how to fire on a cadence and how to attribute the proxy
 * via the registered {@link RTPProxyAccessor}; the transport is responsible
 * for whether the row reaches a peer.
 *
 * <p>Phase 2b ships the cadence + payload assembly. Phase 2e wires this to
 * the real Redis/SQL transports. With {@link NetworkConfig#enabled()} false
 * the publisher is never constructed at all, so REQ-RTP-NET-002 parity is
 * upheld by the adapter, not by an internal no-op flag.</p>
 *
 * <p>Threading: callers supply a {@link ScheduledExecutorService}. The
 * publisher submits its own task and does not own the executor's lifecycle.
 * {@link #stop()} cancels the scheduled task but does not shut the executor
 * down; the adapter owns that.</p>
 */
public final class ProxyStatePublisher {

    private final NetworkTransport transport;
    private final NetworkConfig config;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger inFlightRequests = new AtomicInteger();

    private volatile ScheduledFuture<?> task;

    public ProxyStatePublisher(NetworkTransport transport,
                               NetworkConfig config,
                               ScheduledExecutorService scheduler) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Begin publishing on the configured cadence. Idempotent: a second call
     * before {@link #stop()} is a no-op.
     */
    public synchronized void start() {
        if (task != null) {
            return;
        }
        long periodMs = Math.max(1L, config.heartbeatIntervalMs());
        task = scheduler.scheduleAtFixedRate(this::publishOnce, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Cancel the publishing task. Idempotent. */
    public synchronized void stop() {
        ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(false);
            task = null;
        }
    }

    /** Whether the publisher has an active scheduled task. */
    public boolean isRunning() {
        return task != null;
    }

    /**
     * Increment / decrement the in-flight RTP-request gauge reflected in
     * every published heartbeat. Bound to the dispatcher in Phase 2e; for
     * now exposed so callers can wire it manually in tests.
     */
    public AtomicInteger inFlightRequests() {
        return inFlightRequests;
    }

    /**
     * Build and publish exactly one heartbeat. Returns the transport's future
     * so synchronous callers (tests, dispatch fast-paths) may join; the
     * scheduled cadence consumes this future fire-and-forget.
     */
    public java.util.concurrent.CompletableFuture<Void> publishOnce() {
        RTPProxyAccessor accessor = RtpProxy.proxyAccessor();
        ProxyHeartbeat row = new ProxyHeartbeat(
                accessor.proxyId(),
                config.schemaVersion(),
                System.currentTimeMillis(),
                0,                          // playerCount: wired in Phase 2c
                inFlightRequests.get(),
                false                       // killSwitch: operator-driven, default false (ADR-010)
        );
        return transport.publishProxyHeartbeat(row);
    }
}

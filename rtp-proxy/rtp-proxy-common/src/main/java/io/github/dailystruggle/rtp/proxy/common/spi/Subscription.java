package io.github.dailystruggle.rtp.proxy.common.spi;

/**
 * Handle returned by {@link NetworkTransport#subscribeBackendHeartbeats}.
 * Closing the subscription cancels delivery; idempotent and safe to call
 * from any thread.
 *
 * <p>rtp-proxy-ADR-001 §3 chose a minimal {@code Subscription} type over
 * Reactive Streams to avoid adding {@code org.reactivestreams} to the
 * vendor-neutral surface.</p>
 */
public interface Subscription extends AutoCloseable {
    @Override
    void close();

    /** Whether this subscription has been closed. */
    boolean isClosed();
}

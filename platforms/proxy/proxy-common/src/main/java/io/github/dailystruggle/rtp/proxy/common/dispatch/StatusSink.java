package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;

import java.util.Optional;
import java.util.UUID;

/**
 * Optional callback fired by {@link DefaultRtpDispatcher} at every terminal
 * outcome of a dispatch attempt. Lets the proxy push the player's queue
 * state into the cross-server {@link NetworkRequestQueue} (or any other
 * audit sink) without coupling the dispatcher to the queue SPI.
 *
 * <p>Default
 * implementation is the {@link #NO_OP} singleton so adapters that have not
 * yet wired a queue (e.g. the in-memory test path) keep working
 * unchanged.</p>
 *
 * <p><strong>S-004 contract:</strong> the dispatcher invokes the sink on
 * its own executor and catches any {@link Throwable} the sink throws,
 * logging at {@link java.util.logging.Level#WARNING} - it never swallows a
 * dispatch outcome on sink failure. Implementations must therefore be
 * effectively non-throwing and never block the dispatcher executor.</p>
 */
@FunctionalInterface
public interface StatusSink {

    /** No-op singleton. Used as the default when callers do not wire one. */
    StatusSink NO_OP = (playerId, state, reason) -> { /* no-op */ };

    /**
     * Emit a terminal queue-state transition for the named player.
     *
     * @param playerId the player whose dispatch attempt just terminated
     * @param state    one of {@link NetworkRequestQueue.QueueState#COMPLETED},
     *                 {@link NetworkRequestQueue.QueueState#FAILED},
     *                 {@link NetworkRequestQueue.QueueState#CANCELLED},
     *                 {@link NetworkRequestQueue.QueueState#ROUTING}, or
     *                 {@link NetworkRequestQueue.QueueState#RESERVED}
     * @param reason   short machine-readable reason tag (e.g. the dispatcher
     *                 {@code MessageKey} suffix); empty for the
     *                 {@link NetworkRequestQueue.QueueState#COMPLETED} path
     */
    void emit(UUID playerId, NetworkRequestQueue.QueueState state, Optional<String> reason);
}

package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link StatusSink} that propagates dispatcher-emitted state transitions
 * into the shared {@link NetworkRequestQueue} via its single-writer
 * {@link NetworkRequestQueue#transition(UUID, QueueState, Optional)}
 * op. ADR-015 Slice 4 wiring: the backend-side {@code NetworkStatusCache}
 * polls {@code NetworkRequestQueue.pollStatus(...)}, so persisting the
 * dispatcher's transitions through the same queue is how the lobby learns
 * that an envelope was parked on the cross-proxy waitlist (and later
 * dispatched, routed, or completed).
 *
 * <p>S-004: the {@code transition} future's exception path is logged at
 * WARNING; a transport hiccup must never break the dispatcher's outcome
 * future. Callers that need to observe the resulting status row should
 * call {@code transition} directly instead of going through this sink.</p>
 */
public final class RequestQueueStatusSink implements StatusSink {

    private static final Logger LOG = Logger.getLogger(RequestQueueStatusSink.class.getName());

    private final NetworkRequestQueue queue;

    public RequestQueueStatusSink(NetworkRequestQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    @Override
    public void emit(UUID playerId, QueueState state, Optional<String> reason) {
        try {
            queue.transition(playerId, state, reason)
                    .whenComplete((row, err) -> {
                        if (err != null) {
                            LOG.log(Level.WARNING,
                                    "RequestQueueStatusSink: transition(" + state + ") failed for "
                                            + playerId + ": " + err.getMessage(), err);
                        }
                    });
        } catch (Throwable t) {
            // Defensive: even the call itself must not propagate to the dispatcher.
            LOG.log(Level.WARNING,
                    "RequestQueueStatusSink: transition(" + state + ") threw synchronously for "
                            + playerId + ": " + t.getMessage(), t);
        }
    }
}

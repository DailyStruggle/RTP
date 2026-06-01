package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;

/**
 * Result of an {@link RtpDispatcher#dispatch(RtpRequest)} attempt. Sealed so
 * adapter code switch-exhaustively handles the three terminal cases pinned
 * by rtp-proxy-ADR-001 §1.
 *
 * <p>Implementations must <strong>never</strong> swallow a failure
 * silently (REQ-RTP-S-004); always surface a {@link Failed} carrying a
 * configurable {@code messageKey} (REQ-RTP-PROXY-006).</p>
 */
public sealed interface DispatchOutcome
        permits DispatchOutcome.Routed, DispatchOutcome.Queued, DispatchOutcome.Failed {

    /** Player will be (or has been) transferred to {@code serverId}. */
    record Routed(String serverId, String tokenId) implements DispatchOutcome {
        public Routed {
            Objects.requireNonNull(serverId, "serverId");
            Objects.requireNonNull(tokenId, "tokenId");
        }
    }

    /**
     * No backend qualified immediately; request was queued on the network
     * wait queue (REQ-RTP-NET-008).
     *
     * @param positionHint best-effort 1-based position in the wait queue;
     *                     {@code 0} when the queue depth is unknown
     */
    record Queued(int positionHint) implements DispatchOutcome { }

    /**
     * Dispatch failed; {@link #messageKey} resolves a configurable message
     * via the proxy adapter's messaging layer.
     */
    record Failed(Reason reason, String messageKey) implements DispatchOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(messageKey, "messageKey");
        }

        /** Top-level failure categories. */
        public enum Reason {
            /** No backend matched the request constraints. */
            NO_BACKEND,
            /** Capped-retry chain exhausted by claim races. */
            CLAIM_RACE,
            /** Transport timed out before producing a snapshot or claim. */
            TIMEOUT,
            /** Player disconnected mid-dispatch. */
            PLAYER_GONE,
            /** Network mode disabled; dispatcher refused to run. */
            DISABLED,
            /** Catch-all for unexpected transport / SPI errors. */
            INTERNAL
        }
    }
}

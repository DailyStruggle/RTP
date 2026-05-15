package io.github.dailystruggle.rtp.proxy.common.spi;

/**
 * Result of {@link ProxySender#sendTo(java.util.UUID, String, ReservationToken)}.
 * Distinguishes the post-dispatch transfer phase (player physically routed to
 * the chosen backend) from earlier dispatch outcomes.
 */
public enum TransferOutcome {
    /** Transfer completed; the backend has the player. */
    SUCCESS,
    /** Player disconnected before the transfer completed. */
    PLAYER_DISCONNECTED,
    /** Target backend refused the connection. */
    BACKEND_REFUSED,
    /** Transport-level timeout. */
    TIMEOUT,
    /** Catch-all for adapter-side errors. */
    INTERNAL_ERROR
}

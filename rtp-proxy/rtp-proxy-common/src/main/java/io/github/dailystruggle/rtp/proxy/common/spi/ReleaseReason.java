package io.github.dailystruggle.rtp.proxy.common.spi;

/**
 * Reasons a {@link ReservationToken} may be released back to the network
 * state. Used by {@link NetworkTransport#release(String, ReleaseReason)} and
 * captured in heartbeat telemetry so operators can distinguish ordinary
 * consumption from anomalous reanimation.
 */
public enum ReleaseReason {
    /** Player successfully teleported; slot consumed normally. */
    CONSUMED,
    /** TTL elapsed before the player connected. */
    TTL_EXPIRED,
    /** Player disconnected before transfer completed. */
    PLAYER_DISCONNECTED,
    /** Capped-retry chain exhausted with no successful claim. */
    CLAIM_RACE_LOST,
    /** Backend rejected the reservation (e.g., shutting down). */
    BACKEND_REJECTED,
    /** Operator-issued release (admin command or shutdown). */
    OPERATOR
}

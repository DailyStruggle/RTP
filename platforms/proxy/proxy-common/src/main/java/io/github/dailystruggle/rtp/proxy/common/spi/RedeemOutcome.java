package io.github.dailystruggle.rtp.proxy.common.spi;

/**
 * Outcome of a backend-side reservation-token redeem attempt
 * ({@link NetworkTransport#redeem(String, java.util.UUID, String)}).
 *
 * <p>Distinct from {@link ReleaseReason} because the redeem path is
 * structurally observable: the backend's {@code JoinTriggerSource} must
 * branch on the precise reason in order to send the right configurable
 * {@code MessageKey} to the joining player (REQ-RTP-F-013) or stay silent
 * when another node legitimately won the race.</p>
 *
 * <p>Pinned by L2 of the cross-server RTP checklist.</p>
 */
public enum RedeemOutcome {
    /** State transitioned CLAIMED -> CONSUMED on this call. Player should be teleported. */
    REDEEMED,
    /** No row found for the token id. Reaped or never persisted. */
    NOT_FOUND,
    /** Token row exists but its {@code serverId} does not match this backend. Silent no-op. */
    WRONG_SERVER,
    /** Row already in a terminal state (CONSUMED or RELEASED). Silent no-op. */
    ALREADY_CONSUMED,
    /** Row was in an unexpected state (not PENDING, CLAIMED, CONSUMED, or RELEASED). Defensive. */
    BAD_STATE,
    /** Token row exists but its logical TTL has elapsed. Player should see the expired-token message. */
    EXPIRED,
    /** HMAC envelope verification failed on the row this call observed. Drop with WARNING (REQ-RTP-S-004). */
    HMAC_INVALID,
    /** Transport-level failure (Redis unreachable, SQL exception). Caller logs WARNING and abandons. */
    TRANSPORT_ERROR
}

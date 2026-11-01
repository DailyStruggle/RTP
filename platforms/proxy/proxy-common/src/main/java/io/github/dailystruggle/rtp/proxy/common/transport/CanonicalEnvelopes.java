package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;

import java.util.Objects;

/**
 * Shared canonical byte sequences for HMAC-signed shared-store records
 * (rtp-proxy-ADR-010): reservation tokens, wait-queue envelopes, and shared
 * waitlist entries. Redis
 * and SQL bindings sign and verify through this class so the same secret
 * yields identical signatures under either transport.
 *
 * <p>Encoding is line-oriented {@code key=value\n...}. Fields containing a
 * delimiter ({@code \n}, {@code \r}, {@code =}, {@code |}, NUL) are rejected
 * at sign time ({@link IllegalArgumentException}) and fail verification, so
 * a crafted field cannot forge an adjacent one.</p>
 *
 * <p>The first line carries a per-record signature version, independent of
 * the heartbeat {@code schemaVersion} that {@link HmacVerifier} range-checks.
 * Bumping it invalidates every previously-signed record of that kind
 * (tokens and queue entries are short-lived, so rejection is the migration).</p>
 */
public final class CanonicalEnvelopes {

    /** Token signature layout version. v2 adds {@code regionKey} to the signed fields. */
    public static final int TOKEN_SIG_VERSION = 2;

    /** Wait-queue envelope signature layout version. */
    public static final int QUEUE_SIG_VERSION = 1;

    /** Shared waitlist entry signature layout version. */
    public static final int WAITLIST_SIG_VERSION = 1;

    private CanonicalEnvelopes() { /* static-only */ }

    /** {@code true} iff {@code value} contains no canonical-encoding delimiter. Null is treated as empty. */
    public static boolean isSafeField(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '=' || c == '|' || c == '\0') return false;
        }
        return true;
    }

    private static String safe(String name, String value) {
        String v = value == null ? "" : value;
        if (!isSafeField(v)) {
            throw new IllegalArgumentException(
                    "field '" + name + "' contains a reserved delimiter character");
        }
        return v;
    }

    /**
     * Canonical token payload. {@code regionKey} null / empty both encode as empty.
     *
     * @throws IllegalArgumentException if any field contains a delimiter
     */
    public static String canonicalToken(String tokenId, String serverId, String playerId,
                                        String expiresAtMs, String createdAtMs, String state,
                                        String regionKey) {
        return "tokenSig=" + TOKEN_SIG_VERSION
                + "\ntokenId=" + safe("tokenId", tokenId)
                + "\nserverId=" + safe("serverId", serverId)
                + "\nplayerId=" + safe("playerId", playerId)
                + "\nexpiresAtMs=" + safe("expiresAtMs", expiresAtMs)
                + "\ncreatedAtMs=" + safe("createdAtMs", createdAtMs)
                + "\nstate=" + safe("state", state)
                + "\nregionKey=" + safe("regionKey", regionKey);
    }

    /**
     * Canonical wait-queue envelope payload. Covers every field that drives
     * dispatch. Optional fields null / empty both encode as empty.
     *
     * @throws IllegalArgumentException if any field contains a delimiter
     */
    public static String canonicalQueueEnvelope(String correlationId, String playerId,
                                                String regionKey, String serverHint,
                                                String createdAtMs) {
        return "queueSig=" + QUEUE_SIG_VERSION
                + "\ncorrelationId=" + safe("correlationId", correlationId)
                + "\nplayerId=" + safe("playerId", playerId)
                + "\nregionKey=" + safe("regionKey", regionKey)
                + "\nserverHint=" + safe("serverHint", serverHint)
                + "\ncreatedAtMs=" + safe("createdAtMs", createdAtMs);
    }

    /**
     * Canonical shared-waitlist entry payload. Covers every field that drives
     * dispatch; {@code enrolledAtMs} is excluded because
     * {@code waitlist_refresh_ttl.lua} rewrites it in place (it only drives
     * reap aging, never routing). Optional fields null / empty both encode as empty.
     *
     * @throws IllegalArgumentException if any field contains a delimiter
     */
    public static String canonicalWaitlistEntry(String correlationId, String playerId,
                                                String regionKey, String serverHint,
                                                String originServerId) {
        return "waitlistSig=" + WAITLIST_SIG_VERSION
                + "\ncorrelationId=" + safe("correlationId", correlationId)
                + "\nplayerId=" + safe("playerId", playerId)
                + "\nregionKey=" + safe("regionKey", regionKey)
                + "\nserverHint=" + safe("serverHint", serverHint)
                + "\noriginServerId=" + safe("originServerId", originServerId);
    }

    /** Sign a token; throws {@link IllegalArgumentException} on delimiter injection. */
    public static String signToken(HmacVerifier verifier, int schemaVersion,
                                   String tokenId, String serverId, String playerId,
                                   String expiresAtMs, String createdAtMs, String state,
                                   String regionKey) {
        Objects.requireNonNull(verifier, "verifier");
        return verifier.sign(schemaVersion,
                canonicalToken(tokenId, serverId, playerId, expiresAtMs, createdAtMs, state, regionKey));
    }

    /** Verify a token; delimiter-bearing fields, missing hmac, or mismatch all return {@code false}. */
    public static boolean verifyToken(HmacVerifier verifier, int schemaVersion,
                                      String tokenId, String serverId, String playerId,
                                      String expiresAtMs, String createdAtMs, String state,
                                      String regionKey, String hmacHex) {
        Objects.requireNonNull(verifier, "verifier");
        String canonical;
        try {
            canonical = canonicalToken(tokenId, serverId, playerId, expiresAtMs, createdAtMs, state, regionKey);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return verifier.verify(schemaVersion, canonical, hmacHex);
    }

    /** Sign a queue envelope; throws {@link IllegalArgumentException} on delimiter injection. */
    public static String signQueueEnvelope(HmacVerifier verifier, int schemaVersion,
                                           String correlationId, String playerId,
                                           String regionKey, String serverHint,
                                           String createdAtMs) {
        Objects.requireNonNull(verifier, "verifier");
        return verifier.sign(schemaVersion,
                canonicalQueueEnvelope(correlationId, playerId, regionKey, serverHint, createdAtMs));
    }

    /** Verify a queue envelope; delimiter-bearing fields, missing hmac, or mismatch all return {@code false}. */
    public static boolean verifyQueueEnvelope(HmacVerifier verifier, int schemaVersion,
                                              String correlationId, String playerId,
                                              String regionKey, String serverHint,
                                              String createdAtMs, String hmacHex) {
        Objects.requireNonNull(verifier, "verifier");
        String canonical;
        try {
            canonical = canonicalQueueEnvelope(correlationId, playerId, regionKey, serverHint, createdAtMs);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return verifier.verify(schemaVersion, canonical, hmacHex);
    }

    /** Sign a waitlist entry; throws {@link IllegalArgumentException} on delimiter injection. */
    public static String signWaitlistEntry(HmacVerifier verifier, int schemaVersion,
                                           String correlationId, String playerId,
                                           String regionKey, String serverHint,
                                           String originServerId) {
        Objects.requireNonNull(verifier, "verifier");
        return verifier.sign(schemaVersion,
                canonicalWaitlistEntry(correlationId, playerId, regionKey, serverHint, originServerId));
    }

    /** Verify a waitlist entry; delimiter-bearing fields, missing hmac, or mismatch all return {@code false}. */
    public static boolean verifyWaitlistEntry(HmacVerifier verifier, int schemaVersion,
                                              String correlationId, String playerId,
                                              String regionKey, String serverHint,
                                              String originServerId, String hmacHex) {
        Objects.requireNonNull(verifier, "verifier");
        String canonical;
        try {
            canonical = canonicalWaitlistEntry(correlationId, playerId, regionKey, serverHint, originServerId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return verifier.verify(schemaVersion, canonical, hmacHex);
    }
}

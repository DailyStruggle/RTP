package io.github.dailystruggle.rtp.proxy.common;

/**
 * Runtime role of the process hosting {@code rtp-proxy-common}. Set by the
 * platform adapter at bootstrap (see {@link RTPProxyAccessor#role()} and
 * {@link RtpProxy#proxyAccessor}); {@code rtp-proxy-common} itself performs
 * no classpath probing to determine the role.
 *
 * <p>{@link #BACKEND} is included for completeness but is not expected to
 * appear at runtime in {@code rtp-proxy-common} - backends do not load this
 * module. It exists so the SPI can describe "this is a backend speaker"
 * payloads symmetrically.</p>
 *
 * <p>See rtp-proxy-ADR-002 §Validation Rules and rtp-proxy-ADR-013.</p>
 */
public enum Role {
    /** Velocity proxy adapter. */
    PROXY_VELOCITY,
    /** BungeeCord proxy adapter (placeholder; not yet implemented). */
    PROXY_BUNGEE,
    /** Backend (Bukkit/Paper/Folia/Fabric) speaker. */
    BACKEND
}

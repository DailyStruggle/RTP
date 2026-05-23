package io.github.dailystruggle.rtp.proxy.common.selector;

import java.util.Objects;

/**
 * Immutable {@code (serverId, regionKey)} pair returned by
 * {@link RegionAwareSelector} and consumed by lobby-side and proxy-side
 * dispatch alike. Both fields are non-null and non-empty by contract.
 *
 * <p>Lives in {@code rtp-proxy-common.selector} so the same value type
 * crosses the lobby ({@code PeerRegionRegistry.pickMostKept}) and proxy
 * ({@code RtpDispatcher} fallback) call sites without duplicating the
 * record. Previously a nested type on {@code PeerRegionRegistry}; promoted
 * to the selector package when the lobby and proxy picks were unified on
 * one scoring path.</p>
 */
public record ServerRegion(String serverId, String regionKey) {
    public ServerRegion {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(regionKey, "regionKey");
        if (serverId.isEmpty()) throw new IllegalArgumentException("serverId must not be empty");
        if (regionKey.isEmpty()) throw new IllegalArgumentException("regionKey must not be empty");
    }
}

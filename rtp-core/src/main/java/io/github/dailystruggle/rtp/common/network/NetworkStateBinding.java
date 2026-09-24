package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;

/**
 * Network-state member of {@link io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor}.
 * Carries optional {@link #transport()} returning live {@link NetworkTransport}.
 * A null binding indicates network mode is disabled (REQ-RTP-NET-002).
 */
public interface NetworkStateBinding {
    /**
     * The live {@link NetworkTransport} this binding wraps, or {@code null}
     * if the binding does not host one. Default returns {@code null}
     * so plain marker implementations remain valid without changes.
     */
    default NetworkTransport transport() {
        return null;
    }

    /**
     * Read the last teleport timestamp (epoch milliseconds) for a player across the fleet.
     * Default delegates to {@link #transport()} if present.
     */
    default long getLastTeleportTime(java.util.UUID playerId) {
        NetworkTransport t = transport();
        if (t == null) return 0L;
        try {
            return t.getLastTeleportTime(playerId).get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Record the last teleport timestamp (epoch milliseconds) for a player across the fleet.
     * Default delegates to {@link #transport()} if present.
     */
    default void setLastTeleportTime(java.util.UUID playerId, long epochMillis) {
        NetworkTransport t = transport();
        if (t != null) {
            t.setLastTeleportTime(playerId, epochMillis);
        }
    }
}

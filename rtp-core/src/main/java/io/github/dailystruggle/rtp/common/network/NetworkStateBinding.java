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
}

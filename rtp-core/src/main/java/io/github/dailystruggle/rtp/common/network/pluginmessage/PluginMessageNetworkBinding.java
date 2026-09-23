package io.github.dailystruggle.rtp.common.network.pluginmessage;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Database-free {@link NetworkTransport} backed by proxy plugin-messaging via {@link NetworkBridge}.
 * Gossips {@link BackendHeartbeat} records across backends without Redis or SQL requirements.
 */
public final class PluginMessageNetworkBinding extends AbstractPluginMessageNetworkBinding {

    private static final Logger LOG = Logger.getLogger(PluginMessageNetworkBinding.class.getName());

    /** Default age beyond which a cached peer heartbeat is dropped from the snapshot. */
    public static final long DEFAULT_STALE_TIMEOUT_MILLIS = 1_500L;

    public PluginMessageNetworkBinding(NetworkBridge bridge) {
        this(bridge, DEFAULT_STALE_TIMEOUT_MILLIS, System::currentTimeMillis);
    }

    public PluginMessageNetworkBinding(NetworkBridge bridge, long staleTimeoutMillis, LongSupplier clock) {
        super(bridge, staleTimeoutMillis, clock);
    }

    // ---- heartbeat gossip ------------------------------------------------

    @Override
    public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("PluginMessageNetworkBinding is closed"));
        }
        try {
            byte[] payload = BackendHeartbeatCodec.encode(row).getBytes(StandardCharsets.UTF_8);
            bridge.broadcastHeartbeat(payload);
        } catch (Throwable t) {
            // S-004: a transmit failure (no carrier player, channel hiccup) is
            // logged, not swallowed silently and not propagated as a fatal
            // error - the next heartbeat tick retries.
            LOG.log(Level.FINE,
                    "[RTP] plugin-message heartbeat broadcast skipped: " + t.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }
}

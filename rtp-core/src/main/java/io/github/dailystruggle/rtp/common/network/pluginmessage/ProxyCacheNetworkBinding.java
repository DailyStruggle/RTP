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
 * Tier-1 (DB-free) {@link NetworkTransport} storing availability in the proxy companion.
 *
 * <p>Pushes heartbeats and requests cached snapshots via {@link NetworkBridge} plugin messages.</p>
 */
public final class ProxyCacheNetworkBinding extends AbstractPluginMessageNetworkBinding {

    private static final Logger LOG = Logger.getLogger(ProxyCacheNetworkBinding.class.getName());

    /** Default age beyond which a cached peer heartbeat is dropped from the snapshot. */
    public static final long DEFAULT_STALE_TIMEOUT_MILLIS = 5_000L;

    public ProxyCacheNetworkBinding(NetworkBridge bridge) {
        this(bridge, DEFAULT_STALE_TIMEOUT_MILLIS, System::currentTimeMillis);
    }

    public ProxyCacheNetworkBinding(NetworkBridge bridge, long staleTimeoutMillis, LongSupplier clock) {
        super(bridge, staleTimeoutMillis, clock);
    }

    // ---- heartbeat publish + snapshot refresh ----------------------------

    @Override
    public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ProxyCacheNetworkBinding is closed"));
        }
        try {
            byte[] payload = BackendHeartbeatCodec.encode(row).getBytes(StandardCharsets.UTF_8);
            bridge.pushHeartbeatToProxy(payload);
        } catch (Throwable t) {
            // S-004: a push failure (no carrier player, channel hiccup) is
            // logged, not swallowed silently and not propagated - the next
            // heartbeat tick retries.
            LOG.log(Level.FINE,
                    "[RTP] proxy-cache heartbeat push skipped: " + t.getMessage());
        }
        try {
            // Refresh this server's view of the network from the companion on
            // the same cadence (the publisher pumps publishBackendHeartbeat).
            bridge.requestSnapshot();
        } catch (Throwable t) {
            LOG.log(Level.FINE,
                    "[RTP] proxy-cache snapshot request skipped: " + t.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }
}

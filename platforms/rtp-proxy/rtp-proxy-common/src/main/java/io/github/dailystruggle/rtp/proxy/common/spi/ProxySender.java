package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Vendor-neutral wrapper over the host proxy's "send player to backend"
 * capability. Implemented by each proxy adapter (Velocity, Bungee) to bridge
 * into its native event surface - {@code ServerPreConnectEvent} on Velocity,
 * {@code ServerConnectEvent} on Bungee.
 *
 * <p>rtp-proxy-ADR-001 §4.</p>
 */
public interface ProxySender {

    /**
     * Route {@code playerId} to {@code serverId}, presenting {@code token} so
     * the destination backend can match the incoming connection to its
     * pre-reserved coordinate.
     */
    CompletableFuture<TransferOutcome> sendTo(UUID playerId, String serverId, ReservationToken token);

    /** Whether the proxy currently has a session for {@code playerId}. */
    boolean isConnected(UUID playerId);

    /**
     * Route a configurable message through the adapter's messaging layer.
     * Implementations must resolve {@code key} via {@code messages.yml} and
     * substitute {@code placeholders}; the SPI never accepts a literal string
     * (REQ-RTP-PROXY-006, REQ-RTP-F-013).
     */
    void sendMessage(UUID playerId, MessageKey key, Map<String, String> placeholders);
}

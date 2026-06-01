package io.github.dailystruggle.rtp.proxy.common;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Vendor-neutral accessor exposing the host proxy's identity and the few
 * proxy-level operations that {@code rtp-proxy-common} cannot perform itself.
 * Mirrors the {@code RTPServerAccessor} pattern in {@code rtp-core}: the
 * adapter ({@code rtp-proxy-velocity}, future {@code rtp-proxy-bungee})
 * registers a concrete implementation into {@link RtpProxy#proxyAccessor}
 * during bootstrap, and {@code rtp-proxy-common} consumes the abstraction
 * without ever importing platform APIs.
 *
 * <p>S-006 contract: any caller that reads {@link RtpProxy#proxyAccessor}
 * before the adapter has registered must receive a deterministic
 * {@link IllegalStateException}, not a silent null no-op.</p>
 *
 * <p>See rtp-proxy-ADR-013.</p>
 */
public interface RTPProxyAccessor {

    /**
     * The hard-coded role of the registering adapter. Used by the network
     * config loader to resolve {@code role: auto} from {@code network.yml}.
     */
    Role role();

    /**
     * Stable per-process proxy id; sourced from {@code network.proxyId} in
     * {@code network.yml} (or operator-assigned). Returned as-supplied,
     * fail-fast validation happens at config-load time per ADR-002.
     */
    String proxyId();

    /**
     * Send a player-visible message via the host proxy's chat / system
     * message channel. {@code message} is already a fully-resolved string;
     * key resolution and placeholder substitution happen upstream in the
     * caller per REQ-RTP-PROXY-006 / REQ-RTP-F-013.
     *
     * <p>No-op if the player is not currently connected to this proxy.</p>
     */
    void sendMessage(UUID playerId, String message);

    /**
     * Route {@code playerId} to {@code backendId}. Returns a future that
     * completes when the proxy has dispatched the transfer request to its
     * native event surface (Velocity: mutated {@code ServerPreConnectEvent}
     * or {@code Player.createConnectionRequest}).
     */
    CompletableFuture<Void> transferPlayer(UUID playerId, String backendId);
}

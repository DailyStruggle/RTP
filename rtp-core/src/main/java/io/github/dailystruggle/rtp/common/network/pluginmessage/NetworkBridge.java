package io.github.dailystruggle.rtp.common.network.pluginmessage;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Platform-neutral seam for the plugin-messaging transport (DB-free network tier).
 * Bridges opaque byte payloads across proxy channels (Forward/Connect).
 * Transmissions require an online carrier player; returns {@link Optional#empty()}
 * when no player is online.
 */
public interface NetworkBridge {

    /**
     * Whether the platform actually wired this bridge (channels registered,
     * proxy forwarding detected). When {@code false} the {@code auto} resolver
     * must keep network mode disabled rather than spinning a dead transport.
     */
    boolean isAvailable();

    /**
     * Pick any currently-online player whose connection can carry a plugin
     * message, or {@link Optional#empty()} when none are online.
     */
    Optional<UUID> anyOnlinePlayer();

    /**
     * Broadcast a heartbeat payload to peer backends (via the proxy's
     * {@code Forward} sub-channel, carried on an online player's connection).
     * Implementations must not throw when no carrier player is available; they
     * log at FINE and drop the broadcast (the next tick retries).
     */
    void broadcastHeartbeat(byte[] payload);

    /**
     * Move {@code player} to {@code targetServerId} via the proxy's
     * {@code Connect} sub-channel.
     */
    void connect(UUID player, String targetServerId);

    /**
     * Register the sink that receives decoded inbound heartbeat payloads
     * forwarded from peer backends. Called once by
     * {@link PluginMessageNetworkBinding} at construction.
     */
    void registerInbound(Consumer<byte[]> heartbeatSink);

    /**
     * Cheap, traffic-free probe of the platform's proxy-forwarding config
     * ({@code spigot.yml bungeecord:true} / {@code paper-global.yml} proxies
     * section). A hint only - the authoritative confirmation is the active
     * {@code GetServer}/{@code GetServers} handshake.
     */
    ProxyProbe passiveProbe();

    /**
     * Result of {@link #passiveProbe()}: whether proxy forwarding appears to
     * be switched on in the platform's server config.
     *
     * @param armed {@code true} when the operator's config indicates the
     *              server runs behind a proxy
     */
    record ProxyProbe(boolean armed) {
        public static final ProxyProbe DISARMED = new ProxyProbe(false);
        public static final ProxyProbe ARMED = new ProxyProbe(true);
    }

    /**
     * Active topology handshake: queries proxy for own server name and peer set.
     * Default no-op for bridges that do not implement active handshake.
     */
    default void requestTopology() {
    }

    /**
     * Register the sink that receives the decoded proxy topology reply
     * (this backend's own {@code serverId} plus the peer name set). Called once
     * by {@link ProxyAutoDetector} at construction. Default no-op for bridges
     * that do not implement the active handshake.
     */
    default void registerTopology(Consumer<Topology> topologySink) {
    }

    /**
     * Result of the active {@code GetServer}/{@code GetServers} handshake.
     *
     * @param ownServerId   this backend's name as the proxy knows it
     *                      (from {@code GetServer})
     * @param peerServerIds every backend name the proxy reported
     *                      (from {@code GetServers}); may include this server
     */
    record Topology(String ownServerId, Set<String> peerServerIds) {
    }

    // Proxy-cache tier verbs: push heartbeats to proxy companion and request snapshots.

    /**
     * Push a heartbeat payload to the proxy companion's availability cache
     * (addressed to the companion, not relayed to peer backends). Default
     * delegates to {@link #broadcastHeartbeat(byte[])} so a bridge without a
     * dedicated companion verb still propagates the heartbeat via gossip.
     */
    default void pushHeartbeatToProxy(byte[] payload) {
        broadcastHeartbeat(payload);
    }

    /**
     * Ask the proxy companion to send back its cached availability snapshot
     * (the companion replays each cached backend heartbeat to this server's
     * carrier player, decoded through the same inbound heartbeat sink). Default
     * no-op for bridges that do not implement the companion query.
     */
    default void requestSnapshot() {
    }
}

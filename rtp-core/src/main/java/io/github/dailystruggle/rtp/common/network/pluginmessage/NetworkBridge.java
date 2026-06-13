package io.github.dailystruggle.rtp.common.network.pluginmessage;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Platform-neutral seam for the raw plugin-messaging primitive used by the
 * tier-1 (DB-free) cross-server transport. This is the <em>only</em> surface
 * a platform adapter must implement to participate in the plugin-message
 * network tier; everything above it ({@link PluginMessageNetworkBinding}, the
 * heartbeat codec, the {@code auto} proxy-detection state machine) lives in
 * {@code rtp-core} and is therefore shared by Bukkit/Paper/Folia and, once
 * their shims land, Fabric/NeoForge running behind a Velocity/BungeeCord proxy.
 *
 * <p>The bridge deals only in opaque byte payloads and the proxy's built-in
 * plugin-messaging vocabulary ({@code Forward} for backend-to-backend gossip,
 * {@code Connect} for moving a player). It does not know about
 * {@link io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat} or any
 * SPI type, keeping the platform shim tiny and testable via an in-JVM loopback
 * double.</p>
 *
 * <p><strong>Carrier-player dependency:</strong> proxy plugin messages ride an
 * online player's server&lt;-&gt;proxy connection, so a backend with no players
 * online cannot transmit. {@link #anyOnlinePlayer()} returning
 * {@link Optional#empty()} is a normal "cannot broadcast yet" state, never an
 * error (S-004: the caller logs at FINE and skips, it does not throw).</p>
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
     * Active topology handshake: send the proxy's {@code GetServer} (this
     * backend's own proxy name) and {@code GetServers} (the discovered peer
     * name set) sub-channel queries on an online player's connection. Like
     * every other send this requires a carrier player; implementations log at
     * FINE and drop the request when none is online (the
     * {@link ProxyAutoDetector} re-probes on the next carrier-available event).
     *
     * <p>Default no-op so a platform shim that has not yet implemented the
     * handshake (or a test double that does not need it) compiles unchanged;
     * such a bridge simply never produces a {@link Topology}, so the
     * {@code auto} resolver ages out to {@code DISABLED} (standalone).</p>
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

    // ---- proxy-cache tier verbs (PROPOSAL-proxy-as-availability-store) ----
    // The proxy-cache tier reuses the proxy companion as the availability
    // store: a backend pushes its heartbeat to the companion (not to peers),
    // and a lobby requests the cached snapshot from the companion. These ride
    // an online player's connection like every other send. Default
    // implementations keep the proxy-cache verbs degrading gracefully on a
    // bridge / test double that has not implemented them: the push falls back
    // to peer gossip, and the snapshot request is a no-op (the binding then
    // sees only whatever gossip delivered).

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

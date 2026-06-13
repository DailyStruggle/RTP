package io.github.dailystruggle.rtp.bukkitplatform.network;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bukkit/Paper/Folia implementation of the tier-1 (DB-free) plugin-message
 * {@link NetworkBridge}. It speaks the proxy's built-in plugin-messaging
 * vocabulary on the {@code bungeecord:main} channel (BungeeCord and Velocity
 * both honour it natively, so no proxy companion plugin is required):
 *
 * <ul>
 *   <li>{@code Forward} (custom sub-channel {@value #FORWARD_SUBCHANNEL}) for
 *       backend-to-backend region-availability heartbeat gossip;</li>
 *   <li>{@code Connect} for moving a player to a target backend;</li>
 *   <li>{@code GetServer}/{@code GetServers} for the active topology
 *       handshake driven by the {@code auto} resolver.</li>
 * </ul>
 *
 * <p>Every outbound message rides an online player's server&lt;-&gt;proxy
 * connection (a proxy plugin-messaging constraint). When no player is online
 * the send is dropped at FINE rather than throwing (S-004); the periodic
 * heartbeat retries on the next tick. Reads of {@code spigot.yml} /
 * {@code paper-global.yml} feed the cheap {@link #passiveProbe()} that arms the
 * {@code auto} resolver before any traffic is exchanged.</p>
 */
public final class BukkitNetworkBridge implements NetworkBridge {

    private static final Logger LOG = Logger.getLogger(BukkitNetworkBridge.class.getName());

    /** The reserved proxy plugin-messaging channel (modern namespaced form). */
    public static final String CHANNEL = "bungeecord:main";
    /** Custom {@code Forward} sub-channel carrying RTP heartbeat payloads. */
    public static final String FORWARD_SUBCHANNEL = "RTPNet";

    /**
     * Dedicated backend&lt;-&gt;proxy-companion channel for the proxy-cache
     * tier (PROPOSAL-proxy-as-availability-store). Distinct from
     * {@link #CHANNEL}, whose built-in {@code Forward}/{@code Connect}/
     * {@code GetServers} vocabulary the proxy itself consumes; messages on
     * this channel are addressed to the RTP proxy companion plugin, which
     * caches pushed heartbeats and answers snapshot requests.
     */
    public static final String PROXY_CHANNEL = "rtp:net";
    /** Companion verb: backend pushes its heartbeat into the proxy cache. */
    private static final byte PROXY_PUSH = 1;
    /** Companion verb: backend requests the cached snapshot. */
    private static final byte PROXY_SNAPSHOT_REQ = 2;
    /** Companion verb: companion replies with one cached heartbeat row. */
    private static final byte PROXY_SNAPSHOT_RSP = 3;

    private final Plugin plugin;
    private final boolean registered;

    private volatile Consumer<byte[]> heartbeatSink;
    private volatile Consumer<Topology> topologySink;
    // Own proxy server name learned from the most recent GetServer reply; pairs
    // with the GetServers peer set to assemble a Topology.
    private volatile String ownServerId;

    public BukkitNetworkBridge(Plugin plugin) {
        this.plugin = plugin;
        boolean ok = false;
        try {
            Listener listener = new Listener();
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            plugin.getServer().getMessenger()
                    .registerIncomingPluginChannel(plugin, CHANNEL, listener);
            // Proxy-cache companion channel (best-effort; the bungeecord:main
            // tier still works if this fails to register).
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PROXY_CHANNEL);
            plugin.getServer().getMessenger()
                    .registerIncomingPluginChannel(plugin, PROXY_CHANNEL, listener);
            ok = true;
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    "[NETWORK] failed to register the '" + CHANNEL + "' plugin-messaging channel; "
                            + "plugin-message network tier unavailable: " + t.getMessage());
        }
        this.registered = ok;
    }

    @Override
    public boolean isAvailable() {
        return registered;
    }

    @Override
    public Optional<UUID> anyOnlinePlayer() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player p : online) {
            if (p != null && p.isOnline()) return Optional.of(p.getUniqueId());
        }
        return Optional.empty();
    }

    @Override
    public void broadcastHeartbeat(byte[] payload) {
        Optional<UUID> carrier = anyOnlinePlayer();
        if (carrier.isEmpty()) {
            LOG.log(Level.FINE, "[NETWORK] no carrier player online; heartbeat gossip skipped this tick.");
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");
        // "ONLINE" forwards to every other backend that currently has players;
        // the sender is excluded by the proxy.
        out.writeUTF("ONLINE");
        out.writeUTF(FORWARD_SUBCHANNEL);
        out.writeShort(payload.length);
        out.write(payload);
        dispatch(carrier.get(), out.toByteArray(), "heartbeat broadcast");
    }

    @Override
    public void connect(UUID player, String targetServerId) {
        if (player == null || targetServerId == null || targetServerId.isEmpty()) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(targetServerId);
        dispatch(player, out.toByteArray(), "connect -> " + targetServerId);
    }

    @Override
    public void registerInbound(Consumer<byte[]> heartbeatSink) {
        this.heartbeatSink = heartbeatSink;
    }

    @Override
    public void pushHeartbeatToProxy(byte[] payload) {
        if (payload == null) return;
        Optional<UUID> carrier = anyOnlinePlayer();
        if (carrier.isEmpty()) {
            LOG.log(Level.FINE, "[NETWORK] no carrier player online; heartbeat push to proxy skipped.");
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PROXY_PUSH);
        out.writeShort(payload.length);
        out.write(payload);
        LOG.log(Level.FINE, "[NETWORK] pushing heartbeat (" + payload.length
                + " bytes) to proxy via carrier " + carrier.get() + ".");
        dispatch(carrier.get(), PROXY_CHANNEL, out.toByteArray(), "proxy-cache push");
    }

    @Override
    public void requestSnapshot() {
        Optional<UUID> carrier = anyOnlinePlayer();
        if (carrier.isEmpty()) {
            LOG.log(Level.FINE, "[NETWORK] no carrier player online; proxy-cache snapshot request skipped.");
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PROXY_SNAPSHOT_REQ);
        LOG.log(Level.FINE, "[NETWORK] requesting proxy-cache snapshot via carrier " + carrier.get() + ".");
        dispatch(carrier.get(), PROXY_CHANNEL, out.toByteArray(), "proxy-cache snapshot request");
    }

    @Override
    public void requestTopology() {
        Optional<UUID> carrier = anyOnlinePlayer();
        if (carrier.isEmpty()) {
            LOG.log(Level.FINE, "[NETWORK] no carrier player online; topology handshake deferred.");
            return;
        }
        ByteArrayDataOutput getServer = ByteStreams.newDataOutput();
        getServer.writeUTF("GetServer");
        dispatch(carrier.get(), getServer.toByteArray(), "GetServer");

        ByteArrayDataOutput getServers = ByteStreams.newDataOutput();
        getServers.writeUTF("GetServers");
        dispatch(carrier.get(), getServers.toByteArray(), "GetServers");
    }

    @Override
    public void registerTopology(Consumer<Topology> topologySink) {
        this.topologySink = topologySink;
    }

    @Override
    public ProxyProbe passiveProbe() {
        try {
            File root = serverRoot();
            if (root == null) return ProxyProbe.DISARMED;
            // spigot.yml: settings.bungeecord: true
            File spigot = new File(root, "spigot.yml");
            if (spigot.isFile()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(spigot);
                if (cfg.getBoolean("settings.bungeecord", false)) return ProxyProbe.ARMED;
            }
            // paper-global.yml: proxies.bungee-cord.enabled / proxies.velocity.enabled
            File paper = new File(new File(root, "config"), "paper-global.yml");
            if (paper.isFile()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(paper);
                if (cfg.getBoolean("proxies.bungee-cord.enabled", false)
                        || cfg.getBoolean("proxies.velocity.enabled", false)) {
                    return ProxyProbe.ARMED;
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[NETWORK] passive proxy probe failed: " + t.getMessage());
        }
        return ProxyProbe.DISARMED;
    }

    /**
     * Server root directory (parent of {@code plugins/}), derived from the
     * plugin data folder ({@code <root>/plugins/RTP}). Returns {@code null}
     * when the layout cannot be resolved.
     */
    private File serverRoot() {
        File dataFolder = plugin.getDataFolder();
        if (dataFolder == null) return null;
        File pluginsDir = dataFolder.getParentFile();
        if (pluginsDir == null) return null;
        return pluginsDir.getParentFile();
    }

    /**
     * Send an already-framed payload on the carrier player's connection.
     * sendPluginMessage queues a packet on the netty channel and is safe to
     * call off the main thread; any failure is logged at FINE (S-004) and the
     * next heartbeat tick retries.
     */
    private void dispatch(UUID carrier, byte[] frame, String what) {
        dispatch(carrier, CHANNEL, frame, what);
    }

    private void dispatch(UUID carrier, String channel, byte[] frame, String what) {
        try {
            Player p = Bukkit.getPlayer(carrier);
            if (p == null || !p.isOnline()) {
                LOG.log(Level.FINE, "[NETWORK] carrier player gone; " + what + " dropped.");
                return;
            }
            p.sendPluginMessage(plugin, channel, frame);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[NETWORK] " + what + " send failed: " + t.getMessage());
        }
    }

    /** Incoming plugin-message handler for {@link #CHANNEL}. */
    private final class Listener implements PluginMessageListener {
        @Override
        public void onPluginMessageReceived(String channel, Player player, byte[] message) {
            if (message == null || message.length == 0) return;
            if (PROXY_CHANNEL.equals(channel)) {
                onProxyChannel(message);
                return;
            }
            if (!CHANNEL.equals(channel)) return;
            try {
                ByteArrayDataInput in = ByteStreams.newDataInput(message);
                String sub = in.readUTF();
                switch (sub) {
                    case FORWARD_SUBCHANNEL -> {
                        short len = in.readShort();
                        byte[] payload = new byte[Math.max(0, (int) len)];
                        in.readFully(payload);
                        Consumer<byte[]> sink = heartbeatSink;
                        if (sink != null) sink.accept(payload);
                    }
                    case "GetServer" -> ownServerId = in.readUTF();
                    case "GetServers" -> {
                        String csv = in.readUTF();
                        Set<String> peers = new LinkedHashSet<>();
                        for (String name : csv.split(", ")) {
                            String trimmed = name.trim();
                            if (!trimmed.isEmpty()) peers.add(trimmed);
                        }
                        Consumer<Topology> sink = topologySink;
                        if (sink != null) sink.accept(new Topology(ownServerId, peers));
                    }
                    default -> {
                        // Other proxy sub-channels (PlayerCount, etc.) are not
                        // ours; ignore quietly.
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[NETWORK] dropping malformed proxy plugin message: " + t.getMessage());
            }
        }

        /**
         * Handle a message on {@link #PROXY_CHANNEL} from the proxy companion.
         * The only inbound verb a backend cares about is
         * {@link #PROXY_SNAPSHOT_RSP}, which carries one cached heartbeat row
         * that is fed to the same inbound sink the gossip tier uses.
         */
        private void onProxyChannel(byte[] message) {
            try {
                ByteArrayDataInput in = ByteStreams.newDataInput(message);
                byte verb = in.readByte();
                if (verb == PROXY_SNAPSHOT_RSP) {
                    short len = in.readShort();
                    byte[] payload = new byte[Math.max(0, (int) len)];
                    in.readFully(payload);
                    Consumer<byte[]> sink = heartbeatSink;
                    LOG.log(Level.FINE, "[NETWORK] received proxy-cache snapshot row (" + payload.length
                            + " bytes); sink " + (sink != null ? "present" : "absent") + ".");
                    if (sink != null) sink.accept(payload);
                }
                // PROXY_PUSH / PROXY_SNAPSHOT_REQ are companion-bound; a backend
                // never receives them and ignores them if echoed.
            } catch (Throwable t) {
                LOG.log(Level.FINE, "[NETWORK] dropping malformed proxy-cache message: " + t.getMessage());
            }
        }
    }
}

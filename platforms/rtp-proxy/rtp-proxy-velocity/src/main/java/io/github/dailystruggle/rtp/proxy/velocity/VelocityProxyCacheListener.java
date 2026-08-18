package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Objects;

/**
 * Velocity companion for the {@code proxy-cache} transport tier. Registers the
 * dedicated backend&lt;-&gt;proxy {@code rtp:net} channel and answers the two
 * companion verbs backends speak over it:
 *
 * <ul>
 *   <li>{@link #PROXY_PUSH}: a backend pushes its {@code BackendHeartbeat}
 *       (codec payload) into {@link VelocityProxyAvailabilityCache}.</li>
 *   <li>{@link #PROXY_SNAPSHOT_REQ}: a lobby requests the cached snapshot; the
 *       companion replies with one {@link #PROXY_SNAPSHOT_RSP} frame per known
 *       server row (configured + live), sent back on the requesting backend's
 *       connection. The lobby's {@code ProxyCacheNetworkBinding} feeds each row
 *       into the same snapshot {@code PeerRegionRegistry} reads, so
 *       {@code /rtp region=} tab-completion converges even with all backends
 *       player-empty (direction B).</li>
 * </ul>
 *
 * <p>Wire framing mirrors {@code BukkitNetworkBridge} exactly: {@code byte verb}
 * then, for PUSH/RSP, {@code short length} + raw codec payload. S-004: every
 * handler swallows {@link Throwable} so a malformed frame cannot break
 * Velocity's event dispatcher.</p>
 */
public final class VelocityProxyCacheListener {

    /** Mirrors {@code BukkitNetworkBridge.PROXY_CHANNEL}. */
    static final String CHANNEL = "rtp:net";

    private static final byte PROXY_PUSH = 1;
    private static final byte PROXY_SNAPSHOT_REQ = 2;
    private static final byte PROXY_SNAPSHOT_RSP = 3;

    private final ProxyServer proxyServer;
    private final VelocityProxyAvailabilityCache cache;
    private final Logger logger;
    private final ChannelIdentifier channel;

    public VelocityProxyCacheListener(ProxyServer proxyServer,
                                      VelocityProxyAvailabilityCache cache,
                                      Logger logger) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.channel = MinecraftChannelIdentifier.from(CHANNEL);
    }

    /** Register the {@code rtp:net} channel so backend messages reach this companion. */
    public void register() {
        proxyServer.getChannelRegistrar().register(channel);
        logger.info("RTP proxy-cache companion active on channel '{}'; {} declared server row(s) seeded.",
                CHANNEL, cache.snapshot().size());
    }

    /** Deregister the channel on shutdown. */
    public void unregister() {
        try {
            proxyServer.getChannelRegistrar().unregister(channel);
        } catch (Throwable t) {
            logger.warn("RTP proxy-cache channel unregister threw: {}", t.getMessage());
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier())) {
            return;
        }
        // Companion-bound traffic: never forward to the player's client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        // Only backend->proxy messages (source is a ServerConnection) are ours.
        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }
        byte[] data = event.getData();
        if (data == null || data.length == 0) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte verb = in.readByte();
            switch (verb) {
                case PROXY_PUSH -> {
                    int len = in.readUnsignedShort();
                    byte[] payload = new byte[len];
                    in.readFully(payload);
                    cache.onPushPayload(payload);
                    logger.info("RTP proxy-cache: PROXY_PUSH ({} bytes) from backend '{}'; cache now holds {} server row(s).",
                            len, backend.getServerInfo().getName(), cache.snapshot().size());
                }
                case PROXY_SNAPSHOT_REQ -> {
                    logger.info("RTP proxy-cache: PROXY_SNAPSHOT_REQ from '{}'; replying with cached rows.",
                            backend.getServerInfo().getName());
                    replySnapshot(backend);
                }
                default -> {
                    // PROXY_SNAPSHOT_RSP is proxy->backend; ignore if echoed.
                }
            }
        } catch (Throwable t) {
            logger.warn("RTP proxy-cache: dropping malformed companion message: {}", t.getMessage());
        }
    }

    /** Send one {@link #PROXY_SNAPSHOT_RSP} frame per known server row. */
    private void replySnapshot(ServerConnection backend) {
        List<byte[]> rows = cache.snapshotPayloads();
        logger.info("RTP proxy-cache: sending {} snapshot row(s) to '{}'.",
                rows.size(), backend.getServerInfo().getName());
        for (byte[] payload : rows) {
            byte[] frame = frameSnapshotRsp(payload);
            if (frame == null) continue;
            try {
                backend.sendPluginMessage(channel, frame);
            } catch (Throwable t) {
                logger.warn("RTP proxy-cache: snapshot reply send failed: {}", t.getMessage());
            }
        }
    }

    private static byte[] frameSnapshotRsp(byte[] payload) {
        if (payload == null || payload.length > Short.MAX_VALUE) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(payload.length + 3);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(PROXY_SNAPSHOT_RSP);
            out.writeShort(payload.length);
            out.write(payload);
        } catch (Throwable t) {
            return null;
        }
        return bos.toByteArray();
    }
}

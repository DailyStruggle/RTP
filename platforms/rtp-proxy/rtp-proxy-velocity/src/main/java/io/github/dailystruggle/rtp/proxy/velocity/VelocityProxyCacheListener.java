package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.github.dailystruggle.rtp.common.network.pluginmessage.PluginMessageEnvelope;
import io.github.dailystruggle.rtp.common.network.pluginmessage.ThrottledWarning;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
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
 * then, for PUSH/RSP, {@code short length} + {@link PluginMessageEnvelope}
 * payload. S-004: every handler swallows {@link Throwable} so a malformed
 * frame cannot break Velocity's event dispatcher.</p>
 *
 * <p>Authentication: with an {@link HmacVerifier} (same secret as the
 * backends) pushes must carry a valid MAC and every snapshot row is signed so
 * backends can reject forged replies. Rejections log a throttled WARNING.</p>
 */
@SuppressWarnings("java:S1845") // channel field alongside CHANNEL identifier constant
public final class VelocityProxyCacheListener {

    /** Mirrors {@code BukkitNetworkBridge.PROXY_CHANNEL}. */
    static final String CHANNEL = "rtp:net";

    private static final byte PROXY_PUSH = 1;
    private static final byte PROXY_SNAPSHOT_REQ = 2;
    private static final byte PROXY_SNAPSHOT_RSP = 3;

    /** Env var read by the 3-arg constructor; matches the {@code network.secretEnv} default. */
    static final String DEFAULT_SECRET_ENV = "RTP_NET_SECRET";

    private final ProxyServer proxyServer;
    private final VelocityProxyAvailabilityCache cache;
    private final Logger logger;
    private final ChannelIdentifier channel;
    private final HmacVerifier verifier;
    private final ThrottledWarning rejectWarning;

    /** Loads the verifier from {@value #DEFAULT_SECRET_ENV}; unsigned (WARNING) when absent. */
    public VelocityProxyCacheListener(ProxyServer proxyServer,
                                      VelocityProxyAvailabilityCache cache,
                                      Logger logger) {
        this(proxyServer, cache, logger, loadDefaultVerifier(logger));
    }

    /** @param verifier shared-secret verifier; {@code null} runs unsigned */
    public VelocityProxyCacheListener(ProxyServer proxyServer,
                                      VelocityProxyAvailabilityCache cache,
                                      Logger logger,
                                      HmacVerifier verifier) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.channel = MinecraftChannelIdentifier.from(CHANNEL);
        this.verifier = verifier;
        this.rejectWarning = new ThrottledWarning(
                logger::warn, ThrottledWarning.DEFAULT_INTERVAL_MS, System::currentTimeMillis);
    }

    private static HmacVerifier loadDefaultVerifier(Logger logger) {
        try {
            // Schema version is MAC-covered; accept any >= 1 here, rows carry their own.
            return HmacVerifier.loadFromEnv(DEFAULT_SECRET_ENV, 1, Integer.MAX_VALUE);
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.warn("RTP proxy-cache: HMAC secret unavailable ({}); rtp:net heartbeats run UNSIGNED.",
                        ex.getMessage());
            }
            return null;
        }
    }

    /** Register the {@code rtp:net} channel so backend messages reach this companion. */
    public void register() {
        proxyServer.getChannelRegistrar().register(channel);
        logger.info("RTP proxy-cache companion active on channel '{}' ({}); {} declared server row(s) seeded.",
                CHANNEL, verifier != null ? "HMAC-signed" : "unsigned", cache.snapshot().size());
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
        if (data.length > PluginMessageEnvelope.MAX_FRAME_BYTES) {
            rejectWarning.report("RTP proxy-cache: dropped oversized rtp:net frame (" + data.length
                    + " bytes) from backend '" + backend.getServerInfo().getName() + "' (REQ-RTP-S-004).");
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte verb = in.readByte();
            switch (verb) {
                case PROXY_PUSH -> {
                    int len = in.readUnsignedShort();
                    if (len == 0 || len > PluginMessageEnvelope.MAX_PAYLOAD_BYTES || len > in.available()) {
                        rejectWarning.report("RTP proxy-cache: dropped PROXY_PUSH with invalid length " + len
                                + " from backend '" + backend.getServerInfo().getName() + "' (REQ-RTP-S-004).");
                        return;
                    }
                    byte[] payload = new byte[len];
                    in.readFully(payload);
                    PluginMessageEnvelope.Rejection rejected = cache.onPushPayload(payload, verifier);
                    if (rejected != null) {
                        rejectWarning.report("RTP proxy-cache: dropped PROXY_PUSH (" + rejected + ") from backend '"
                                + backend.getServerInfo().getName() + "'"
                                + (verifier != null ? "; HMAC required" : "") + " (REQ-RTP-S-004).");
                        return;
                    }
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
        List<byte[]> rows = cache.snapshotPayloads(verifier);
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

package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.dailystruggle.rtp.proxy.common.spi.MessageKey;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxySender;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.TransferOutcome;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Velocity-side {@link ProxySender} (rtp-proxy-ADR-001 §4).
 *
 * <p>Single chokepoint for player-visible messaging (REQ-RTP-S-007,
 * REQ-RTP-F-013) and {@code Player.createConnectionRequest} transfer
 * (REQ-RTP-PROXY-VELOCITY-002). All inline {@code Component.text("RTP: ...")}
 * literals previously living in {@code RtpVelocityPlugin.onCommandTrigger}
 * are funnelled through here instead.</p>
 *
 * <p>Localized {@code messages.yml} on the Velocity adapter is out of scope
 * for L1 (see {@code PROPOSAL-cross-server-rtp-L1.md} §5). Until that lands,
 * {@link #sendMessage} resolves each {@link MessageKey} via the static
 * baseline-fallback table {@link #BASELINE_FALLBACK}; missing keys log a
 * single WARNING per key (deduplicated via {@link #warnedKeys}) and the
 * player receives the raw key. This is consistent with the
 * "log-once-and-use-baseline" decision approved in the L1 proposal §7.4.</p>
 */
public final class VelocityProxySender implements ProxySender {

    /**
     * English baseline strings for the {@link MessageKey}s emitted by
     * {@code DefaultRtpDispatcher}. Replaced by per-locale lookup once
     * Velocity-side {@code messages.yml} support lands.
     *
     * <p>Values may reference {@code {placeholder}} tokens that
     * {@link #sendMessage} substitutes from the supplied map. Unmatched
     * placeholders are left verbatim (the Bukkit side does the same).</p>
     */
    private static final Map<String, String> BASELINE_FALLBACK = Map.ofEntries(
            Map.entry("rtp.network.routed", "RTP: routing you to {server}."),
            Map.entry("rtp.network.no-backend", "RTP: no servers available right now."),
            Map.entry("rtp.network.claim-failed", "RTP: could not reserve a slot; try again."),
            Map.entry("rtp.network.unknown-target", "RTP: target server is not registered."),
            Map.entry("rtp.network.transfer-failed", "RTP: teleport transfer failed; try again."),
            Map.entry("rtp.network.disabled", "RTP: network mode is disabled on this proxy."),
            Map.entry("rtp.network.player-gone", "RTP: player session ended before teleport."),
            Map.entry("rtp.network.internal-error", "RTP: teleport request failed; try again.")
    );

    private final ProxyServer proxyServer;
    private final Logger logger;
    /** Per-key dedup so a missing localization key logs at most once. */
    private final ConcurrentHashMap<String, Boolean> warnedKeys = new ConcurrentHashMap<>();
    /** Overridable for tests to inject a custom localization lookup. */
    private final Function<String, String> resolver;

    public VelocityProxySender(ProxyServer proxyServer, Logger logger) {
        this(proxyServer, logger, BASELINE_FALLBACK::get);
    }

    /** Test-affordance constructor: callers supply the key -> template resolver. */
    public VelocityProxySender(ProxyServer proxyServer,
                               Logger logger,
                               Function<String, String> resolver) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public CompletableFuture<TransferOutcome> sendTo(UUID playerId,
                                                     String serverId,
                                                     ReservationToken token) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(token, "token");

        // Phase B trace (2026-05-23): adapter entry. The dispatcher has
        // claimed a reservation and is now asking us to actually transfer
        // the player. Each branch (player gone, target unknown, connect
        // result) is logged at INFO so a devstack repro can pinpoint the
        // failure mode. Remove or downgrade once root cause is identified.
        Optional<Player> playerOpt = proxyServer.getPlayer(playerId);
        if (playerOpt.isEmpty()) {
            logger.info("[NETWORK][trace] VelocityProxySender.sendTo: player {} not found on this proxy (PLAYER_DISCONNECTED) targetServerId={}",
                    playerId, serverId);
            return CompletableFuture.completedFuture(TransferOutcome.PLAYER_DISCONNECTED);
        }
        Optional<RegisteredServer> target = proxyServer.getServer(serverId);
        if (target.isEmpty()) {
            logger.warn("RTP transfer: target server '{}' not registered on this proxy.", serverId);
            logger.info("[NETWORK][trace] VelocityProxySender.sendTo: target serverId={} not registered on this proxy (BACKEND_REFUSED) player={}",
                    serverId, playerId);
            return CompletableFuture.completedFuture(TransferOutcome.BACKEND_REFUSED);
        }
        logger.info("[NETWORK][trace] VelocityProxySender.sendTo: invoking createConnectionRequest player={} targetServerId={} currentServer={}",
                playerId, serverId,
                playerOpt.get().getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("<none>"));
        return playerOpt.get().createConnectionRequest(target.get())
                .connect()
                .handle((result, err) -> {
                    if (err != null) {
                        logger.warn("RTP transfer to '{}' threw {}: {}",
                                serverId, err.getClass().getSimpleName(), err.getMessage());
                        logger.info("[NETWORK][trace] VelocityProxySender.sendTo: connect threw for player={} targetServerId={} -> INTERNAL_ERROR",
                                playerId, serverId);
                        return TransferOutcome.INTERNAL_ERROR;
                    }
                    if (result == null) {
                        logger.info("[NETWORK][trace] VelocityProxySender.sendTo: connect returned null result for player={} targetServerId={} -> INTERNAL_ERROR",
                                playerId, serverId);
                        return TransferOutcome.INTERNAL_ERROR;
                    }
                    ConnectionRequestBuilder.Status status = result.getStatus();
                    logger.info("[NETWORK][trace] VelocityProxySender.sendTo: connect returned status={} for player={} targetServerId={}",
                            status, playerId, serverId);
                    if (status == ConnectionRequestBuilder.Status.SUCCESS) {
                        return TransferOutcome.SUCCESS;
                    }
                    logger.warn("RTP transfer to '{}' returned status {}.", serverId, status);
                    return switch (status) {
                        case ALREADY_CONNECTED -> TransferOutcome.SUCCESS;
                        case CONNECTION_IN_PROGRESS -> TransferOutcome.TIMEOUT;
                        case CONNECTION_CANCELLED, SERVER_DISCONNECTED -> TransferOutcome.BACKEND_REFUSED;
                        default -> TransferOutcome.INTERNAL_ERROR;
                    };
                });
    }

    @Override
    public boolean isConnected(UUID playerId) {
        if (playerId == null) return false;
        return proxyServer.getPlayer(playerId).isPresent();
    }

    @Override
    public void sendMessage(UUID playerId, MessageKey key, Map<String, String> placeholders) {
        if (playerId == null) {
            // Defensive guard: dispatcher should never call us with a null
            // playerId, but if it does we log and drop rather than NPE.
            logger.warn("RTP sendMessage: null playerId for key='{}'; dropping.", key.key());
            return;
        }
        Objects.requireNonNull(key, "key");
        Map<String, String> safe = placeholders == null ? Map.of() : placeholders;

        Optional<Player> playerOpt = proxyServer.getPlayer(playerId);
        if (playerOpt.isEmpty()) {
            // Player disconnected before message could be delivered; not an error.
            return;
        }
        String template = resolver.apply(key.key());
        if (template == null) {
            if (warnedKeys.putIfAbsent(key.key(), Boolean.TRUE) == null) {
                logger.warn("RTP messages.yml missing key '{}'; using raw key as fallback. "
                        + "This warning is logged once per key per session.", key.key());
            }
            template = key.key();
        }
        playerOpt.get().sendMessage(Component.text(substitute(template, safe)));
    }

    private static String substitute(String template, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) return template;
        String out = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }
}

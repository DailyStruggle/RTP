package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge;
import io.github.dailystruggle.rtp.proxy.common.selector.ServerRegion;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Handles backend-to-backend direct cross-server dispatch over SQL/Redis (rtp-proxy-ADR-020).
 *
 * <p>Lifecycle flow:</p>
 * <ol>
 *   <li>Select destination backend and region using hint or {@link PeerRegionRegistry}.</li>
 *   <li>Atomically claim a reservation token on the destination backend via {@link NetworkTransport#claim}.</li>
 *   <li>On claim success, send native BungeeCord {@code Connect} plugin message via {@link NetworkBridge#connect}.</li>
 *   <li>On failure, notify player and clean up state safely (S-004: never silently swallow failures).</li>
 * </ol>
 */
public final class DirectDatabaseDispatcher {

    public enum ResultStatus {
        SUCCESS,
        NO_CANDIDATE,
        PLAYER_OFFLINE,
        CLAIM_FAILED,
        BRIDGE_UNAVAILABLE,
        ERROR
    }

    public record DispatchResult(ResultStatus status, String targetServerId, String targetRegionKey, String message) {
        public static DispatchResult success(String serverId, String regionKey) {
            return new DispatchResult(ResultStatus.SUCCESS, serverId, regionKey, null);
        }

        public static DispatchResult failure(ResultStatus status, String message) {
            return new DispatchResult(status, null, null, message);
        }
    }

    private final NetworkTransport transport;
    private final Supplier<NetworkSnapshot> snapshotSupplier;
    private final PeerRegionRegistry peerRegionRegistry;
    private final NetworkBridge bridge;
    private final String localServerId;
    private final Duration tokenTtl;
    private final Consumer<UUID> onTerminalFailure;

    public DirectDatabaseDispatcher(NetworkTransport transport,
                                    Supplier<NetworkSnapshot> snapshotSupplier,
                                    PeerRegionRegistry peerRegionRegistry,
                                    NetworkBridge bridge,
                                    String localServerId,
                                    Duration tokenTtl,
                                    Consumer<UUID> onTerminalFailure) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.peerRegionRegistry = peerRegionRegistry;
        this.bridge = bridge;
        this.localServerId = Objects.requireNonNull(localServerId, "localServerId");
        this.tokenTtl = tokenTtl != null ? tokenTtl : Duration.ofSeconds(60);
        this.onTerminalFailure = onTerminalFailure;
    }

    /**
     * Perform direct database cross-server dispatch for a player.
     *
     * @param playerId   UUID of the requesting player
     * @param regionKey  optional region constraint
     * @param serverHint optional server target pin
     * @return future completing with the dispatch outcome
     */
    public CompletableFuture<DispatchResult> dispatch(UUID playerId, Optional<String> regionKey, Optional<String> serverHint) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(regionKey, "regionKey");
        Objects.requireNonNull(serverHint, "serverHint");

        // Verify player is online before taking any action.
        RTPPlayer player = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPlayer(playerId) : null;
        if (player == null || !player.isOnline()) {
            RTP.log(Level.FINE, "[RTP] Direct DB dispatch aborted: player " + playerId + " is offline.");
            handleFailure(playerId, NetworkMessages.networkRegionUnavailable, "Player offline");
            return CompletableFuture.completedFuture(DispatchResult.failure(ResultStatus.PLAYER_OFFLINE, "Player offline"));
        }

        if (bridge == null) {
            RTP.log(Level.WARNING, "[RTP] Direct DB dispatch failed: NetworkBridge is null (S-004).");
            handleFailure(playerId, NetworkMessages.networkRegionUnavailable, "Bridge unavailable");
            return CompletableFuture.completedFuture(DispatchResult.failure(ResultStatus.BRIDGE_UNAVAILABLE, "Bridge unavailable"));
        }

        // 1. Resolve target server and region.
        String targetServer = serverHint.orElse(null);
        String targetRegion = regionKey.orElse(null);

        if ((targetServer == null || targetServer.isBlank()) && peerRegionRegistry != null) {
            Optional<ServerRegion> picked = peerRegionRegistry.pickMostKept();
            if (picked.isPresent()) {
                targetServer = picked.get().serverId();
                targetRegion = picked.get().regionKey();
            }
        }

        if (targetServer == null || targetServer.isBlank() || targetServer.equals(localServerId)) {
            RTP.log(Level.WARNING, "[RTP] Direct DB dispatch: no candidate peer server found for " + playerId + " (S-004).");
            handleFailure(playerId, NetworkMessages.networkRegionUnavailable, "No destination server available");
            return CompletableFuture.completedFuture(DispatchResult.failure(ResultStatus.NO_CANDIDATE, "No candidate peer server"));
        }

        final String finalTargetServer = targetServer;
        final String finalTargetRegion = (targetRegion != null && !targetRegion.isBlank()) ? targetRegion : "default";

        RTP.log(Level.FINE, "[RTP] Direct DB dispatching " + playerId + " -> server=" + finalTargetServer + " region=" + finalTargetRegion);

        // 2. Claim reservation token in the shared store.
        return transport.claim(finalTargetServer, playerId, tokenTtl, Optional.of(finalTargetRegion))
                .handle((token, err) -> {
                    if (err != null) {
                        RTP.log(Level.WARNING, "[RTP] Direct DB claim failed for " + playerId + " against " + finalTargetServer + ": " + err.getMessage(), err);
                        handleFailure(playerId, NetworkMessages.networkRegionUnavailable, "Claim error: " + err.getMessage());
                        return DispatchResult.failure(ResultStatus.CLAIM_FAILED, err.getMessage());
                    }
                    if (token == null) {
                        RTP.log(Level.WARNING, "[RTP] Direct DB claim returned null token for " + playerId + " against " + finalTargetServer + " (S-004).");
                        handleFailure(playerId, NetworkMessages.networkRegionUnavailable, "Claim returned null");
                        return DispatchResult.failure(ResultStatus.CLAIM_FAILED, "Claim returned null token");
                    }

                    // 3. Record dispatch in peer region registry so local scoring reflects the reservation.
                    if (peerRegionRegistry != null) {
                        try {
                            peerRegionRegistry.recordDispatch(finalTargetServer, finalTargetRegion);
                        } catch (Throwable ignored) {}
                    }

                    // 4. Issue Connect plugin message.
                    try {
                        bridge.connect(playerId, finalTargetServer);
                        RTP.log(Level.FINE, "[RTP] Direct DB sent Connect packet for " + playerId + " -> " + finalTargetServer);
                        return DispatchResult.success(finalTargetServer, finalTargetRegion);
                    } catch (Throwable connectErr) {
                        RTP.log(Level.WARNING, "[RTP] Direct DB connect failed for " + playerId + " -> " + finalTargetServer + ": " + connectErr.getMessage(), connectErr);
                        handleFailure(playerId, NetworkMessages.networkRegionUnavailable, "Connect failed: " + connectErr.getMessage());
                        return DispatchResult.failure(ResultStatus.ERROR, connectErr.getMessage());
                    }
                });
    }

    private void handleFailure(UUID playerId, NetworkMessages msgKey, String reason) {
        if (onTerminalFailure != null) {
            try {
                onTerminalFailure.accept(playerId);
            } catch (Throwable ignored) {}
        }
        if (RTP.serverAccessor != null) {
            try {
                String tmpl = (String) RTP.configs.getConfigValue(msgKey, "&c[RTP] Destination unavailable.");
                if (tmpl != null && !tmpl.isBlank()) {
                    RTP.serverAccessor.sendMessage(playerId, tmpl);
                }
            } catch (Throwable ignored) {}
        }
    }
}

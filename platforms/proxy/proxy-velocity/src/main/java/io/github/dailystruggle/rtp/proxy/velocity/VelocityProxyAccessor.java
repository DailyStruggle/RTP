package io.github.dailystruggle.rtp.proxy.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor;
import io.github.dailystruggle.rtp.proxy.common.Role;
import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Velocity-side {@link RTPProxyAccessor}. Hard-codes {@link Role#PROXY_VELOCITY}
 * per rtp-proxy-ADR-013 (each adapter contributes its own role; no classpath
 * probing in {@code rtp-proxy-common}).
 *
 * <p>{@code proxyId} is supplied at construction from {@code network.yml};
 * the accessor does not read the config itself.</p>
 */
public final class VelocityProxyAccessor implements RTPProxyAccessor {

    private final ProxyServer proxyServer;
    private final String proxyId;

    public VelocityProxyAccessor(ProxyServer proxyServer, String proxyId) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.proxyId = Objects.requireNonNull(proxyId, "proxyId");
    }

    @Override
    public Role role() {
        return Role.PROXY_VELOCITY;
    }

    @Override
    public String proxyId() {
        return proxyId;
    }

    @Override
    public void sendMessage(UUID playerId, String message) {
        Optional<Player> p = proxyServer.getPlayer(playerId);
        p.ifPresent(player -> player.sendMessage(Component.text(message)));
    }

    @Override
    public CompletableFuture<Void> transferPlayer(UUID playerId, String backendId) {
        Optional<Player> player = proxyServer.getPlayer(playerId);
        if (player.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("player not connected to this proxy: " + playerId));
        }
        Optional<RegisteredServer> target = proxyServer.getServer(backendId);
        if (target.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("unknown backend: " + backendId));
        }
        return player.get().createConnectionRequest(target.get())
                .connect()
                .thenApply(result -> null);
    }
}

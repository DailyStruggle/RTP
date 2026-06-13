package io.github.dailystruggle.rtp.neoforge.server;

import io.github.dailystruggle.rtp.api.server.DispatchingPlayerLifecycleHook;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * NeoForge implementation of
 * {@link io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook} backed by
 * {@link DispatchingPlayerLifecycleHook}.
 *
 * <p>NeoForge analogue of {@code FabricPlayerLifecycleHook}. Unlike the Fabric
 * shim, this class is free of reflective UUID extraction: NeoForge ships
 * Mojang-mapped names at runtime, so the join / quit bridge
 * ({@code NeoForgeEventBridge}) hands a typed {@link ServerPlayer} straight
 * through and {@link ServerPlayer#getUUID()} links directly. The
 * {@code NeoForgeEventBridge} invokes {@link #fireJoinFromPlayer(ServerPlayer)}
 * / {@link #fireQuitFromPlayer(ServerPlayer)} after its register / unregister
 * calls so platform-agnostic subscribers (e.g. the network-mode
 * reservation-redeem path, ADR-049) receive the UUID event.
 *
 * @since 3.1.2
 */
public final class NeoForgePlayerLifecycleHook extends DispatchingPlayerLifecycleHook {

    /**
     * Fan a join event out to subscribers using the typed player's UUID.
     *
     * @param player the joining {@link ServerPlayer} (or {@code null}, a no-op)
     */
    public void fireJoinFromPlayer(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        if (uuid != null) fireJoin(uuid);
    }

    /**
     * Fan a quit event out to subscribers using the typed player's UUID.
     *
     * @param player the quitting {@link ServerPlayer} (or {@code null}, a no-op)
     */
    public void fireQuitFromPlayer(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        if (uuid != null) fireQuit(uuid);
    }
}

package io.github.dailystruggle.rtp.fabric.effects;

import io.github.dailystruggle.effectsapi.common.effects.GlideKeepAlive;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** {@link GlideKeepAlive.Glider} over a live {@link ServerPlayer}. Server thread only. */
final class FabricGlider implements GlideKeepAlive.Glider {
    private final ServerPlayer player;

    FabricGlider(@NotNull ServerPlayer player) {
        this.player = player;
    }

    @Override
    public @NotNull UUID id() {
        return player.getUUID();
    }

    @Override
    public boolean isGone() {
        return player.isRemoved() || !player.isAlive() || player.hasDisconnected();
    }

    @Override
    public boolean isLanded() {
        return player.onGround() || player.isInWater() || player.isInLava() || player.isPassenger();
    }

    @Override
    public boolean isGliding() {
        return player.isFallFlying();
    }

    @Override
    public void startGliding() {
        player.startFallFlying();
    }

    @Override
    public void stopGliding() {
        player.stopFallFlying();
    }

    @Override
    public void resetFallDistance() {
        player.resetFallDistance();
    }
}

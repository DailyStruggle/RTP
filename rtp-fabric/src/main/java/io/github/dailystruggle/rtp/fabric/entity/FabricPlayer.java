package io.github.dailystruggle.rtp.fabric.entity;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.fabric.world.FabricWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class FabricPlayer implements RTPPlayer {
    private final ServerPlayerEntity player;

    public FabricPlayer(ServerPlayerEntity player) {
        this.player = player;
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            player.teleport((FabricWorld) to.world().world(), to.x(), to.y(), to.z(), player.getYaw(), player.getPitch());
            future.complete(true);
        } catch (Exception e) {
            future.complete(false);
        }
        return future;
    }

    @Override
    public RTPLocation getLocation() {
        return new RTPLocation(new FabricWorld(player.getServerWorld()), (int) player.getX(), (int) player.getY(), (int) player.getZ());
    }

    @Override
    public boolean isOnline() {
        return player.getServer().getPlayerManager().getPlayer(player.getUuid()) != null;
    }

    @Override
    public UUID uuid() {
        return player.getUuid();
    }

    @Override
    public boolean hasPermission(String permission) {
        // Simple implementation, might need a proper permission API integration
        return player.hasPermissionLevel(2); 
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(Text.literal(message));
    }

    @Override
    public long cooldown() {
        return 0; // Handled by core
    }

    @Override
    public long delay() {
        return 0; // Handled by core
    }

    @Override
    public String name() {
        return player.getName().getString();
    }

    @Override
    public Set<String> getEffectivePermissions() {
        return new HashSet<>(); // Not easily accessible in vanilla/fabric without extra API
    }

    @Override
    public void performCommand(RTPPlayer player, String command) {
        this.player.getServer().getCommandManager().executeWithPrefix(this.player.getCommandSource(), command);
    }

    @Override
    public FabricPlayer clone() {
        return new FabricPlayer(player);
    }
}

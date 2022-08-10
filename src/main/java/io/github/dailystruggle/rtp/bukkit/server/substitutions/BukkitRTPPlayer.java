package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public record BukkitRTPPlayer(Player player) implements RTPPlayer {
    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public void sendMessage(String message) {
        SendMessage.sendMessage(player,message);
    }

    @Override
    public long cooldown() {
        return new BukkitRTPCommandSender(player).cooldown();
    }

    @Override
    public long delay() {
        return new BukkitRTPCommandSender(player).delay();
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        World world = ((BukkitRTPWorld)to.world()).world();
        if(Bukkit.isPrimaryThread()) {
            return PaperLib.teleportAsync(player, new Location(world, to.x(), to.y(), to.z()));
        }

        CompletableFuture<Boolean> res = new CompletableFuture<>();
        Bukkit.getScheduler().callSyncMethod(RTPBukkitPlugin.getInstance(),() -> {
            CompletableFuture<Boolean> pass = PaperLib.teleportAsync(player, new Location(world, to.x(), to.y(), to.z()));
            pass.whenComplete((aBoolean, throwable) -> res.complete(aBoolean));
            return pass;
        });
        return res;
    }

    @Override
    public RTPLocation getLocation() {
        Location location = player.getLocation();
        return new RTPLocation(
                new BukkitRTPWorld(player.getWorld()),
                location.getBlockX(),location.getBlockY(), location.getBlockZ());
    }

    @Override
    public boolean isOnline() {
        return player.isOnline();
    }
}

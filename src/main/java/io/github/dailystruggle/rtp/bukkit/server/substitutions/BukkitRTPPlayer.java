package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.checkerframework.checker.units.qual.C;

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
        double x = to.x() + 0.5;
        double y = to.y();
        double z = to.z() + 0.5;

        CompletableFuture<Boolean> res = new CompletableFuture<>();

        if(Bukkit.isPrimaryThread()) res.complete(player.teleport(new Location(world, x, y, z)));
        else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(),
                () -> res.complete(player.teleport(new Location(world, x, y, z))));
        return res;
    }

    @Override
    public RTPLocation getLocation() {
        Location location = player.getLocation();
        return new RTPLocation(
                RTP.serverAccessor.getRTPWorld(player.getWorld().getUID()),
                location.getBlockX(),location.getBlockY(), location.getBlockZ());
    }

    @Override
    public boolean isOnline() {
        return player.isOnline();
    }
}

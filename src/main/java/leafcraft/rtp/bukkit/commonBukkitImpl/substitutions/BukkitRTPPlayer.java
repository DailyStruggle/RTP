package leafcraft.rtp.bukkit.commonBukkitImpl.substitutions;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.bukkit.tools.SendMessage;
import leafcraft.rtp.common.substitutions.RTPCommandSender;
import leafcraft.rtp.common.substitutions.RTPLocation;
import leafcraft.rtp.common.substitutions.RTPPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public record BukkitRTPPlayer(Player player) implements RTPPlayer {
    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public void sendMessage(String message) {
        SendMessage.sendMessage(player,message);
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
        World world = ((BukkitRTPWorld)to.world()).world();
        return PaperLib.teleportAsync(player,new Location(world,to.x(),to.y(),to.z()));
    }

    @Override
    public RTPLocation getLocation() {
        Location location = player.getLocation();
        return new RTPLocation(
                new BukkitRTPWorld(player.getWorld()),
                location.getBlockX(),location.getBlockY(), location.getBlockZ());
    }
}

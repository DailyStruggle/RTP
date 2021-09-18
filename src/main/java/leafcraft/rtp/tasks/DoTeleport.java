package leafcraft.rtp.tasks;

import leafcraft.rtp.RTP;
import leafcraft.rtp.customEvents.RandomPreTeleportEvent;
import leafcraft.rtp.customEvents.RandomTeleportEvent;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class DoTeleport extends BukkitRunnable {
    private final RTP plugin;
    private final Configs configs;
    private final CommandSender sender;
    private final Player player;
    private final Location location;
    private final Cache cache;

    public DoTeleport(RTP plugin, Configs configs, CommandSender sender, Player player, Location location, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.sender = sender;
        this.player = player;
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        doTeleportNow();
    }

    public void doTeleportNow() {
        cache.playerFromLocations.remove(player.getUniqueId());
        cache.doTeleports.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        cache.lastTP.put(player.getUniqueId(),location);

        Location mutableLocation = location.clone();
        RandomPreTeleportEvent randomPreTeleportEvent = new RandomPreTeleportEvent(sender,player,mutableLocation);
        Bukkit.getPluginManager().callEvent(randomPreTeleportEvent);
        RandomTeleportEvent randomTeleportEvent = new RandomTeleportEvent(sender, player, mutableLocation, cache.numTeleportAttempts.get(location));
        Bukkit.getPluginManager().callEvent(randomTeleportEvent);
        new ChunkCleanup(configs,location,cache).runTask(plugin);
        cache.commandSenderLookup.remove(player.getUniqueId());

        if(sender instanceof Player) {
            cache.currentTeleportCost.remove(((Player)sender).getUniqueId());
        }
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }
}

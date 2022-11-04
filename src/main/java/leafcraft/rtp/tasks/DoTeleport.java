package leafcraft.rtp.tasks;

import leafcraft.rtp.API.customEvents.RandomPreTeleportEvent;
import leafcraft.rtp.API.customEvents.RandomTeleportEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.selection.ChunkSet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

public class DoTeleport extends BukkitRunnable {
    private final RTP plugin;
    private final CommandSender sender;
    private final Player player;
    private final Location location;
    private final Cache cache;
    private final ChunkSet chunkSet;
    private static final Set<UUID> newPlayers = new ConcurrentSkipListSet<>();

    public DoTeleport(CommandSender sender, Player player, Location location, ChunkSet chunkSet) {
        this.chunkSet = chunkSet;
        this.plugin = RTP.getPlugin();
        this.sender = sender;
        this.player = player;
        this.location = location;
        this.cache = RTP.getCache();
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
        new ChunkCleanup(location,cache, chunkSet).runTask(plugin);
        cache.commandSenderLookup.remove(player.getUniqueId());

        if(sender instanceof Player) {
            cache.currentTeleportCost.remove(((Player)sender).getUniqueId());
        }
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }
}

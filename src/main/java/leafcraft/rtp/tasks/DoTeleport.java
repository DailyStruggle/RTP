package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.selection.Translate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
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
    private final RandomSelectParams rsParams;


    public DoTeleport(RTP plugin, Configs configs, CommandSender sender, Player player, Location location, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.sender = sender;
        this.player = player;
        this.location = location;
        this.cache = cache;
        this.rsParams = cache.regionKeys.get(player.getUniqueId());
    }

    @Override
    public void run() {
        //cleanup cache first to avoid cancel() issues
        cache.playerFromLocations.remove(player.getUniqueId());
        cache.doTeleports.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());

        if(!this.isCancelled()) {
            //player.teleport(location);
            PaperLib.teleportAsync(player,location);
            this.player.sendMessage(configs.lang.getLog("teleportMessage", this.cache.numTeleportAttempts.getOrDefault(location,0).toString()));

            //cleanup chunks after teleporting
            int vd = Bukkit.getViewDistance();
            int cx = location.getChunk().getX();
            int cz = location.getChunk().getZ();
            for (int i = -vd; i < vd; i++) {
                for (int j = -vd; j < vd; j++) {
                    Chunk chunk = location.getWorld().getChunkAt(cx+i, cz+j);
                    HashableChunk hashableChunk = new HashableChunk(chunk);
                    if (cache.forceLoadedChunks.containsKey(hashableChunk)
                            || cache.keepChunks.containsKey(hashableChunk)) {
                        chunk.setForceLoaded(false);
                        cache.forceLoadedChunks.remove(hashableChunk);
                        if(cache.keepChunks.containsKey(hashableChunk)) {
                            long intersect = cache.keepChunks.get(hashableChunk) - 1;
                            if (intersect <= 0) cache.keepChunks.remove(hashableChunk);
                        }
                    }
                }
            }

            if(rsParams!=null && cache.permRegions.containsKey(rsParams)) {
                TeleportRegion region = cache.permRegions.get(rsParams);
                region.removeChunks(location);
                QueueLocation queueLocation;
                if(player.hasPermission("rtp.personalQueue"))
                    queueLocation = new QueueLocation(region,player, cache);
                else
                    queueLocation = new QueueLocation(region, cache);
                cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
                queueLocation.runTaskAsynchronously(plugin);
            }
        }
    }

    @Override
    public void cancel() {
        if(cache.permRegions.containsKey(rsParams)) {
            cache.permRegions.get(rsParams).queueLocation(location);
        }
        cache.todoTP.remove(player.getUniqueId());
        super.cancel();
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }
}

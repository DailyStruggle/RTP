package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.selection.RandomSelectParams;
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
            long area = (long)(vd*vd*4+0.5d);
            for (long i = 0; i < area; i++) {
                int[] xz = Translate.squareLocationToXZ(0,cx,cz,area);
                Chunk chunk = location.getWorld().getChunkAt(xz[0],xz[1]);
                HashableChunk hashableChunk = new HashableChunk(chunk);
                if (cache.forceLoadedChunks.containsKey(hashableChunk)) {
                    chunk.setForceLoaded(false);
                    cache.forceLoadedChunks.remove(chunk);
                }
            }

            RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
            if(rsParams!=null && cache.permRegions.containsKey(rsParams))
                cache.permRegions.get(rsParams).removeChunks(location);
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

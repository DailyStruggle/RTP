package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.TimeUnit;

public class DoTeleport extends BukkitRunnable {
    private final RTP plugin;
    private final Configs configs;
    private final CommandSender sender;
    private final Player player;
    private final Location location;
    private final Cache cache;
    private final RandomSelectParams rsParams;
    private boolean cancelled = false;

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
        doTeleportNow();
    }

    public void doTeleportNow() {
        //cleanup cache first to avoid cancel() issues
        cache.playerFromLocations.remove(player.getUniqueId());
        cache.doTeleports.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());

        if(!cancelled) {
//            PaperLib.teleportAsync(player,location);
            player.teleport(location);
            String msg = configs.lang.getLog("teleportMessage", this.cache.numTeleportAttempts.getOrDefault(location,0).toString());
            msg = PAPIChecker.fillPlaceholders(player,msg);

            long diff = System.nanoTime()-cache.lastTeleportTime.get(player.getUniqueId());
            long days = TimeUnit.NANOSECONDS.toDays(diff);
            long hours = TimeUnit.NANOSECONDS.toHours(diff)%24;
            long minutes = TimeUnit.NANOSECONDS.toMinutes(diff)%60;
            long seconds = TimeUnit.NANOSECONDS.toSeconds(diff)%60;
            double millis = ((double)(TimeUnit.NANOSECONDS.toMicros(diff)%1000000))/1000;
            String replacement = "";
            if(days>0) replacement += days + configs.lang.getLog("days") + " ";
            if(hours>0) replacement += hours + configs.lang.getLog("hours") + " ";
            if(minutes>0) replacement += minutes + configs.lang.getLog("minutes") + " ";
            if(seconds>0) replacement += seconds + configs.lang.getLog("seconds") + " ";
            if((millis>0 || seconds<1)&&diff<TimeUnit.SECONDS.toNanos(2)) replacement += millis + configs.lang.getLog("millis");
            msg = msg.replace("[time]",replacement);
            player.sendMessage(msg);
            if(sender.getName()!=player.getName()) sender.sendMessage(msg);

            //cleanup chunks after teleporting
            int vd = configs.config.vd;
            int cx = location.getChunk().getX();
            int cz = location.getChunk().getZ();
            World world = location.getWorld();
            for (int i = -vd; i < vd; i++) {
                for (int j = -vd; j < vd; j++) {
                    if(!world.isChunkForceLoaded(cx+i,cz+j)) continue;
                    HashableChunk hashableChunk = new HashableChunk(world,cx+i,cz+j);
                    if (cache.forceLoadedChunks.containsKey(hashableChunk)) {
                        world.setChunkForceLoaded(cx+i,cz+j, false);
                        cache.forceLoadedChunks.remove(hashableChunk);
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
        this.cache.lastTeleportTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @Override
    public void cancel() {
        if(cache.permRegions.containsKey(rsParams)) {
            cache.permRegions.get(rsParams).queueLocation(location);
        }
        cache.todoTP.remove(player.getUniqueId());
        String msg = configs.lang.getLog("teleportCancel");
        if(player.isOnline()) {
            msg = PAPIChecker.fillPlaceholders(player, msg);
            player.sendMessage(PAPIChecker.fillPlaceholders(player, msg));
        }
        if (!sender.getName().equals(player.getName()))
            sender.sendMessage(msg);
        cancelled = true;
        super.cancel();
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }
}

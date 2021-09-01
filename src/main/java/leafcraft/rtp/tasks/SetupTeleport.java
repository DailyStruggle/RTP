package leafcraft.rtp.tasks;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.TimeUnit;

//prep teleportation
public class SetupTeleport extends BukkitRunnable {
    private final RTP plugin;
    private final CommandSender sender;
    private final Player player;
    private final Configs configs;
    private Cache cache;
    private RandomSelectParams rsParams;
    private Location location = null;
    private boolean cancelled = false;
    private TeleportRegion.ChunkSet chunkSet = null;

    public SetupTeleport(RTP plugin, CommandSender sender, Player player, Configs configs, Cache cache, RandomSelectParams rsParams) {
        this.sender = sender;
        this.plugin = plugin;
        this.player = player;
        this.configs = configs;
        this.cache = cache;
        this.rsParams = rsParams;
    }

    @Override
    public void run() {
        setupTeleportNow(true);
    }

    @Override
    public void cancel() {
        if(cache.permRegions.containsKey(rsParams) && location != null) {
            cache.permRegions.get(rsParams).queueLocation(location);
        }
        cache.todoTP.remove(player.getUniqueId());
        cache.setupTeleports.remove(player.getUniqueId());
        if(cache.loadChunks.containsKey(player.getUniqueId())) {
            cache.loadChunks.get(player.getUniqueId()).cancel();
            cache.loadChunks.remove(player.getUniqueId());
        }
        if(cache.doTeleports.containsKey(player.getUniqueId())) {
            cache.doTeleports.get(player.getUniqueId()).cancel();
            cache.doTeleports.remove(player.getUniqueId());
        }
        cancelled = true;
        player.sendMessage(PAPIChecker.fillPlaceholders(player,configs.lang.getLog("teleportCancel")));
        if(!sender.getName().equals(player.getName()))
            sender.sendMessage(PAPIChecker.fillPlaceholders(player,configs.lang.getLog("teleportCancel")));
        super.cancel();
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }

    public void setupTeleportNow(boolean async) {
        //get a random location according to the parameters
        location = cache.getRandomLocation(rsParams,true,sender, player);
        if(location == null) return;

        //get warmup delay
        int delay = (sender.hasPermission("rtp.noDelay")) ? 0 : configs.config.teleportDelay;

        //let player know if warmup delay > 0
        if(delay>0) {
            int time = delay/20;
            long days = TimeUnit.SECONDS.toDays(time);
            long hours = TimeUnit.SECONDS.toHours(time)%24;
            long minutes = TimeUnit.SECONDS.toMinutes(time)%60;
            long seconds = TimeUnit.SECONDS.toSeconds(time)%60;
            String replacement = "";
            if(days>0) replacement += days + configs.lang.getLog("days") + " ";
            if(hours>0) replacement += hours + configs.lang.getLog("hours") + " ";
            if(minutes>0) replacement += minutes + configs.lang.getLog("minutes") + " ";
            if(seconds>0) replacement += seconds%60 + configs.lang.getLog("seconds");
            String msg = configs.lang.getLog("delayMessage", replacement);
            if(player.isOnline()) {
                msg = PAPIChecker.fillPlaceholders(player, msg);
                player.sendMessage(PAPIChecker.fillPlaceholders(player, msg));
                if (!sender.getName().equals(player.getName()))
                    sender.sendMessage(msg);
            }
        }

        //set up task to load chunks then teleport
        if(!cancelled){
            cache.todoTP.put(player.getUniqueId(),location);
            cache.regionKeys.put(player.getUniqueId(),rsParams);
            LoadChunks loadChunks = new LoadChunks(plugin,configs,sender,player,cache,delay,location);
            if(sender.hasPermission("rtp.noDelay.chunks")
                    || (loadChunks.chunkSet.completed.get()>=loadChunks.chunkSet.expectedSize-1)) {
                DoTeleport doTeleport = new DoTeleport(plugin,configs,sender,player,location,cache);
                cache.doTeleports.put(player.getUniqueId(),doTeleport);
                long diffNanos = System.nanoTime() - cache.lastTeleportTime.getOrDefault(player.getUniqueId(), System.nanoTime());
                long diffMicros = TimeUnit.NANOSECONDS.toMicros(diffNanos);
                long diffTicks = (diffMicros / 50);
                if(async || diffTicks < delay) doTeleport.runTaskLater(plugin, delay+2);
                else doTeleport.doTeleportNow();
            }
            else {
                cache.loadChunks.put(player.getUniqueId(), loadChunks);
                loadChunks.runTaskAsynchronously(plugin);
            }
        }
        cache.setupTeleports.remove(player.getUniqueId());
    }
}

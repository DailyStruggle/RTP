package leafcraft.rtp.tasks;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
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
    private final Cache cache;
    private final RandomSelectParams rsParams;
    private Location location = null;

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
        //if already teleporting, don't do it again
        if(cache.loadChunks.containsKey(player.getUniqueId()) || cache.todoTP.containsKey(player.getUniqueId())) {
            //probably say something
            return;
        }

        //get warmup delay
        int delay = (sender.hasPermission("rtp.noDelay")) ? 0 : (Integer)configs.config.getConfigValue("teleportDelay", 2);

        //let player know if warmup delay > 0
        if(delay>0) {
            long days = TimeUnit.SECONDS.toDays(delay);
            long hours = TimeUnit.SECONDS.toHours(delay)%24;
            long minutes = TimeUnit.SECONDS.toMinutes(delay)%60;
            long seconds = TimeUnit.SECONDS.toSeconds(delay)%60;
            String replacement = "";
            if(days>0) replacement += days + configs.lang.getLog("days") + " ";
            if(hours>0) replacement += hours + configs.lang.getLog("hours") + " ";
            if(minutes>0) replacement += minutes + configs.lang.getLog("minutes") + " ";
            replacement += seconds%60 + configs.lang.getLog("seconds");
            player.sendMessage(configs.lang.getLog("delayMessage", replacement));
            if(!sender.getName().equals(player.getName()))
                sender.sendMessage(configs.lang.getLog("delayMessage", replacement));
        }

        //get a random location according to the parameters
        long start = System.currentTimeMillis();
        location = cache.getRandomLocation(rsParams,true,sender, player);
        long stop = System.currentTimeMillis();
        if(location == null) return;

        //set up task to load chunks then teleport
        if(!this.isCancelled()){
            cache.todoTP.put(player.getUniqueId(),location);
            cache.regionKeys.put(player.getUniqueId(),rsParams);
            LoadChunks loadChunks = new LoadChunks(plugin,configs,sender,player,cache,(int)((20*delay)-((stop-start)/50)),location);
            loadChunks.runTaskLaterAsynchronously(plugin,1);
            cache.loadChunks.put(player.getUniqueId(), loadChunks);
        }
        cache.setupTeleports.remove(player.getUniqueId());
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
        super.cancel();
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }


}

package leafcraft.rtp.tasks;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class SetupTeleport extends BukkitRunnable {
    private final RTP plugin;
    private final CommandSender sender;
    private final Player player;
    private final Config config;
    private final Cache cache;
    private final RandomSelectParams rsParams;

    public SetupTeleport(RTP plugin, CommandSender sender, Player player, Config config, Cache cache, RandomSelectParams rsParams) {
        this.sender = sender;
        this.plugin = plugin;
        this.player = player;
        this.config = config;
        this.cache = cache;
        this.rsParams = rsParams;
    }

    @Override
    public void run() {
        if(this.cache.todoTP.containsKey(player.getName())) {
            //probably say something
            return;
        }
        int delay = (sender.hasPermission("rtp.instant")) ? 0 : (Integer)this.config.getConfigValue("teleportDelay", 2);

        //let player know if they'll have to wait to teleport
        if(delay>0) {
            long days = TimeUnit.SECONDS.toDays(delay);
            long hours = TimeUnit.SECONDS.toHours(delay)%24;
            long minutes = TimeUnit.SECONDS.toMinutes(delay)%60;
            long seconds = TimeUnit.SECONDS.toSeconds(delay)%60;
            String replacement = "";
            if(days>0) replacement += days + this.config.getLog("days") + " ";
            if(hours>0) replacement += hours + this.config.getLog("hours") + " ";
            if(minutes>0) replacement += minutes + this.config.getLog("minutes") + " ";
            replacement += seconds%60 + this.config.getLog("seconds");
            player.sendMessage(config.getLog("delayMessage", replacement));
            if(!sender.getName().equals(player.getName()))
                sender.sendMessage(config.getLog("delayMessage", replacement));
        }

        //get a random location
        Location location = this.cache.getRandomLocation(rsParams,true);

        Integer maxAttempts = (Integer) this.config.getConfigValue("maxAttempts",100);
        if(location == null) {
            player.sendMessage(this.config.getLog("unsafe",maxAttempts.toString()));
            if(!sender.getName().equals(player.getName()))
                sender.sendMessage(this.config.getLog("unsafe",maxAttempts.toString()));
            return;
        }
        this.cache.todoTP.put(player.getName(),location);

        //set up task to load chunks then teleport
        this.cache.loadChunks.put(this.player.getName(), new LoadChunks(this.plugin,this.config,this.player,this.cache,delay*20,location).runTaskLaterAsynchronously(this.plugin,1));
    }
}

package leafcraft.rtp.customEventListeners;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.customEvents.RandomTeleportEvent;
import leafcraft.rtp.tasks.*;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class OnRandomTeleport implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnRandomTeleport(RTP plugin, Configs configs,
                            Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRandomTeleport(RandomTeleportEvent event) {
        Player player = event.getPlayer();
        cache.playerFromLocations.remove(player.getUniqueId());
        cache.doTeleports.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        cache.lastTP.put(player.getUniqueId(),event.getTo());

        if(configs.config.blindnessDuration>0)
            player.addPotionEffect(PotionEffectType.BLINDNESS.createEffect(configs.config.blindnessDuration,100),false);
        player.teleport(event.getTo());
        if(!configs.config.title.equals("")) {
            String title = SendMessage.format(player,configs.config.title);
            String subtitle = SendMessage.format(player,configs.config.subTitle);
            player.sendTitle(title,subtitle,configs.config.fadeIn * 20, configs.config.stay * 20, configs.config.fadeOut * 20);
        }
        String msg = configs.lang.getLog("teleportMessage", this.cache.numTeleportAttempts.getOrDefault(event.getTo(),0).toString());
        msg = PAPIChecker.fillPlaceholders(player,msg);

        long time = System.nanoTime();
        long diff = time-cache.lastTeleportTime.get(player.getUniqueId());
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
        SendMessage.sendMessage(event.getSender(),player,msg);
        cache.lastTeleportTime.put(player.getUniqueId(), time);

        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if(rsParams!=null && cache.permRegions.containsKey(rsParams)) {
            TeleportRegion region = cache.permRegions.get(rsParams);
            region.removeChunks(event.getTo());
            QueueLocation queueLocation = null;
            if(player.hasPermission("rtp.personalQueue"))
                queueLocation = new QueueLocation(region,player, cache);
            else if(configs.config.postTeleportQueueing)
                queueLocation = new QueueLocation(region, cache);
            if(queueLocation!=null) {
                cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
                queueLocation.runTaskAsynchronously(plugin);
            }
        }

        if(!player.isInvulnerable() && configs.config.invulnerabilityTime>0) {
            player.setInvulnerable(true);
            cache.invulnerablePlayers.put(player.getUniqueId(),player);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.setInvulnerable(false);
                cache.invulnerablePlayers.remove(player.getUniqueId());
            },configs.config.invulnerabilityTime*20L);
        }

        runCommands(event.getPlayer());

        if(event.getSender() instanceof Player) {
            cache.currentTeleportCost.remove(((Player)event.getSender()).getUniqueId());
        }
    }

    private void runCommands(Player player) {
        List<String> consoleCommands = configs.config.getConsoleCommands();
        for(String command : consoleCommands) {
            command = PAPIChecker.fillPlaceholders(player,command);
            command = ChatColor.translateAlternateColorCodes('&',command);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command);
        }

        List<String> playerCommands = configs.config.getPlayerCommands();
        for(String command : playerCommands) {
            command = PAPIChecker.fillPlaceholders(player,command);
            command = ChatColor.translateAlternateColorCodes('&',command);
            Bukkit.dispatchCommand(player,command);
        }
    }
}
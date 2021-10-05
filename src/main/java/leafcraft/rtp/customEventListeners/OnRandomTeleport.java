package leafcraft.rtp.customEventListeners;

import leafcraft.rtp.API.customEvents.RandomTeleportEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class OnRandomTeleport implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnRandomTeleport() {
        this.plugin = RTP.getPlugin();
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRandomTeleport(RandomTeleportEvent event) {
        if(configs.config.platformRadius>=0) {
            Bukkit.getScheduler().runTask(plugin, ()->makePlatform(event.getTo()));
        }

        Player player = event.getPlayer();
        player.teleport(event.getTo());
        if(configs.config.blindnessDuration>0)
            player.addPotionEffect(PotionEffectType.BLINDNESS.createEffect(configs.config.blindnessDuration,100));
        if(!configs.config.title.equals("")) {
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                String title = SendMessage.format(player,configs.config.title);
                String subtitle = SendMessage.format(player,configs.config.subTitle);
                player.sendTitle(title,subtitle,configs.config.fadeIn * 20, configs.config.stay * 20, configs.config.fadeOut * 20);
            },2);
        }
        String msg = configs.lang.getLog("teleportMessage", String.valueOf(event.getTries()));

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

    private void makePlatform(Location location) {
        Chunk chunk = location.getChunk();
        if(!chunk.isLoaded()) chunk.load(true);
        Block airBlock = location.getBlock();
        airBlock.breakNaturally();
        Material air = (airBlock.isLiquid()) ? Material.AIR : airBlock.getType();
        Material solid = location.getBlock().getRelative(BlockFace.DOWN).getType();
        if(!solid.isSolid()) solid = configs.config.platformMaterial;

        for(int i = 7-configs.config.platformRadius; i <= 7+configs.config.platformRadius; i++) {
            for(int j = 7-configs.config.platformRadius; j <= 7+configs.config.platformRadius; j++) {
                for(int y = location.getBlockY()-1; y >= location.getBlockY()-configs.config.platformDepth; y--) {
                    Block block = chunk.getBlock(i,y,j);
                    if(!block.getType().isSolid() || configs.config.unsafeBlocks.contains(block.getType()))
                        block.setType(solid,false);
                }
                for(int y = location.getBlockY()+configs.config.platformAirHeight-1; y >= location.getBlockY(); y--) {
                    Block block = chunk.getBlock(i,y,j);
                    block.breakNaturally();
                    block.setType(air,false); //also clear liquids
                }
            }
        }
    }
}
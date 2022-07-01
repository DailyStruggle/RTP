package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import leafcraft.rtp.API.customEvents.TeleportCancelEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.HashMap;
import java.util.Objects;

public final class OnPlayerChangeWorld implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnPlayerChangeWorld() {
        this.plugin = RTP.getPlugin();
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        //if currently teleporting, stop that and clean up
        if (cache.todoTP.containsKey(player.getUniqueId())) {
            stopTeleport(event);
        }

        if(player.hasPermission("rtp.personalQueue")) {
            World fromWorld = event.getFrom();
            String fromWorldName = fromWorld.getName();
            if (!player.hasPermission("rtp.worlds." + fromWorldName) && (Boolean) configs.worlds.getWorldSetting(fromWorldName, "requirePermission", true)) {
                fromWorld = Bukkit.getWorld((String) configs.worlds.getWorldSetting(fromWorldName, "override", "world"));
            }
            RandomSelectParams fromParams = new RandomSelectParams(Objects.requireNonNull(fromWorld), new HashMap<>());
            if (cache.permRegions.containsKey(fromParams)) {
                cache.permRegions.get(fromParams).recyclePlayerLocations(player);
            }

            World toWorld = event.getFrom();
            String toWorldName = toWorld.getName();
            if (!player.hasPermission("rtp.worlds." + toWorldName) && (Boolean) configs.worlds.getWorldSetting(toWorldName, "requirePermission", true)) {
                toWorld = Bukkit.getWorld((String) configs.worlds.getWorldSetting(toWorldName, "override", "world"));
            }
            RandomSelectParams toParams = new RandomSelectParams(Objects.requireNonNull(toWorld), new HashMap<>());
            if (cache.permRegions.containsKey(toParams)) {
                QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(toParams), player, cache);
                cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
                queueLocation.runTaskLaterAsynchronously(plugin, 1);
            }
        }
    }

    private void stopTeleport(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        CommandSender sender = cache.commandSenderLookup.get(player.getUniqueId());
        if(sender == null) return;

        Location to = cache.todoTP.get(player.getUniqueId());
        if(to == null) return;

        TeleportCancelEvent teleportCancelEvent = new TeleportCancelEvent(sender,player,to,event.isAsynchronous());
        Bukkit.getPluginManager().callEvent(teleportCancelEvent);
    }
}

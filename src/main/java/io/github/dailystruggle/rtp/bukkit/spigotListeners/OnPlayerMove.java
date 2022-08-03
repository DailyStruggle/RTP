package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.tasks.RTPTeleportCancel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class OnPlayerMove implements Listener {
    private double cancelDistanceSquared = 2;
    private long lastUpdateTime = 0;

    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        TeleportData data = RTP.getInstance().latestTeleportData.get(id);
        if(data == null || data.completed) return;

        tasks.computeIfPresent(id,(uuid, bukkitTask) -> {
            bukkitTask.cancel();
            return bukkitTask;
        });

        tasks.put(id, Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> check(id)));
    }

    private void check(UUID id) {
        tasks.remove(id);
        Player p = Bukkit.getPlayer(id);
        if(p == null || !p.isOnline()) return;

        RTP instance = RTP.getInstance();
        TeleportData data = instance.latestTeleportData.getOrDefault(id,new TeleportData());

        long t = System.nanoTime();
        if(     t < lastUpdateTime ||
                ((t-lastUpdateTime) > TimeUnit.SECONDS.toNanos(5))) {
            ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) instance.configs.configParserMap.get(ConfigKeys.class);
            cancelDistanceSquared = Math.pow(configParser.getNumber(ConfigKeys.cancelDistance, 2).doubleValue(), 2);
            lastUpdateTime = t;
        }

        //if currently teleporting, stop that and clean up
        if (data.completed) return;

        RTPPlayer player = new BukkitRTPPlayer(p);

        RTPLocation originalLocation = data.originalLocation;
        if(originalLocation == null) {
            originalLocation = player.getLocation();
        }
        if (originalLocation.distanceSquared(player.getLocation()) < cancelDistanceSquared) return;

        RTPCommandSender sender = data.sender;
        if(sender == null) return;

        RTPLocation to = data.selectedLocation;
        if(to == null) return;

        new RTPTeleportCancel(id).run();
    }
}

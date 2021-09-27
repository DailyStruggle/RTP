package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.API.customEvents.TeleportCancelEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.*;

public final class OnPlayerMove implements Listener {
    private final Map<UUID,Double> playerMoveDistances = new HashMap<>();
    private final Configs configs;
    private final Cache cache;

    public OnPlayerMove() {
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if(to == null) return;
        Location from = event.getFrom();
        Player player = event.getPlayer();

        //if currently teleporting, stop that and clean up
        if (this.cache.todoTP.containsKey(player.getUniqueId())) {
            stopTeleport(event);
        }

        playerMoveDistances.putIfAbsent(player.getUniqueId(),0D);
        playerMoveDistances.compute(player.getUniqueId(),(uuid, aDouble) -> aDouble+=from.distance(to));
        Double distance = playerMoveDistances.get(player.getUniqueId());
        if(distance < configs.config.cancelDistance) return;
        playerMoveDistances.put(player.getUniqueId(),0D);

        //if player has this perm, go again
        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasPerm = false;
        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            if(!perm.getPermission().startsWith("rtp.onevent.")) continue;
            if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.move"))
                hasPerm = true;
        }
        if (hasPerm) {
            //skip if already going
            LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
            DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
            if (loadChunks != null && loadChunks.isNoDelay()) return;
            if (doTeleport != null && doTeleport.isNoDelay()) return;

            //run command as console
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "rtp player:" + player.getName() + " world:" +
                            Objects.requireNonNull(
                                    Objects.requireNonNull(to).getWorld()).getName());
        }
    }

    private void stopTeleport(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        Location originalLocation = cache.playerFromLocations.getOrDefault(player.getUniqueId(), player.getLocation());
        if(originalLocation == null) return;
        if (originalLocation.distance(Objects.requireNonNull(event.getTo())) < configs.config.cancelDistance) return;

        CommandSender sender = cache.commandSenderLookup.get(player.getUniqueId());
        if(sender == null) return;

        Location to = cache.todoTP.get(player.getUniqueId());
        if(to == null) return;

        TeleportCancelEvent teleportCancelEvent = new TeleportCancelEvent(sender,player,to,event.isAsynchronous());
        Bukkit.getPluginManager().callEvent(teleportCancelEvent);
    }
}

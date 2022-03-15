package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.API.RTPAPI;
import leafcraft.rtp.API.selection.SelectionAPI;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.API.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;

public class OnEvent implements Listener {
    private final Cache cache;
    private final ConcurrentSkipListSet<UUID> respawningPlayers = new ConcurrentSkipListSet<>();
    private final Map<UUID,Double> playerMoveDistances = new HashMap<>();

    public OnEvent() {
        this.cache = RTP.getCache();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        //if has this perm, go again
        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasPerm = false;
        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            if(!perm.getPermission().startsWith("rtp.onevent.")) continue;
            if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.changeWorld"))
                hasPerm = true;
        }
        if (hasPerm) {
            teleportAction(player, SelectionAPI.SyncState.SYNC);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        
        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasPerm = false;
        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            if(!perm.getPermission().startsWith("rtp.onevent.")) continue;
            if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.respawn"))
                hasPerm = true;
        }
        if (hasPerm) {
            RandomSelectParams rsParams = new RandomSelectParams(event.getEntity().getWorld(), null);
            TeleportRegion region = cache.permRegions.get(rsParams);
            QueueLocation queueLocation = new QueueLocation(region, player, cache);
            cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
            queueLocation.runTaskAsynchronously(RTP.getInstance());
            respawningPlayers.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        
        if (respawningPlayers.contains(player.getUniqueId())) {
            teleportAction(player, SelectionAPI.SyncState.SYNC);
            respawningPlayers.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Configs Configs = RTP.getConfigs();

        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasFirstJoin = false;
        boolean hasJoin = false;

        long start = System.nanoTime();
        long lastTime = cache.lastTeleportTime.getOrDefault((player).getUniqueId(), 0L);
        long cooldownTime = TimeUnit.SECONDS.toNanos(RTP.getConfigs().config.teleportCooldown);

        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            String node = perm.getPermission();
            if(!node.startsWith("rtp.onevent.")) continue;
            if(node.equals("rtp.onevent.*")) {
                hasFirstJoin = true;
                hasJoin = true;
            }
            else if(perm.getPermission().equals("rtp.onevent.firstjoin"))
                hasFirstJoin = true;
            else if(perm.getPermission().equals("rtp.onevent.join"))
                hasJoin = true;
            else if(node.startsWith("rtp.cooldown.")) {
                String[] val = node.split("\\.");
                if(val.length<3 || val[2]==null || val[2].equals("")) continue;
                int number;
                try {
                    number = Integer.parseInt(val[2]);
                } catch (NumberFormatException exception) {
                    Bukkit.getLogger().warning("[rtp] invalid permission: " + node);
                    continue;
                }
                cooldownTime = TimeUnit.SECONDS.toNanos(number);
                break;
            }
        }
        if (hasJoin || (hasFirstJoin && !player.hasPlayedBefore())) {
            if (!player.hasPermission("rtp.nocooldown") && (start - lastTime) < cooldownTime){
                long remaining = (lastTime - start) + cooldownTime;
                long days = TimeUnit.NANOSECONDS.toDays(remaining);
                long hours = TimeUnit.NANOSECONDS.toHours(remaining) % 24;
                long minutes = TimeUnit.NANOSECONDS.toMinutes(remaining) % 60;
                long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining) % 60;
                String replacement = "";
                if (days > 0) replacement += days + Configs.lang.getLog("days") + " ";
                if (days > 0 || hours > 0) replacement += hours + Configs.lang.getLog("hours") + " ";
                if (days > 0 || hours > 0 || minutes > 0) replacement += minutes + Configs.lang.getLog("minutes") + " ";
                replacement += seconds + Configs.lang.getLog("seconds");
                String msg = Configs.lang.getLog("cooldownMessage", replacement);

                SendMessage.sendMessage(player, msg);
                return;
            }
            teleportAction(player, SelectionAPI.SyncState.SYNC);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if(to == null) return;
        Location from = event.getFrom();
        Player player = event.getPlayer();
        

        playerMoveDistances.putIfAbsent(player.getUniqueId(),0D);
        playerMoveDistances.compute(player.getUniqueId(),(uuid, aDouble) -> aDouble+=from.distance(to));
        Double distance = playerMoveDistances.get(player.getUniqueId());
        if(distance < RTP.getConfigs().config.cancelDistance) return;
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
            teleportAction(player, SelectionAPI.SyncState.ASYNC);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        
        //if has this perm, go again
        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasPerm = false;
        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            if(!perm.getPermission().startsWith("rtp.onevent.")) continue;
            if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.teleport"))
                hasPerm = true;
        }
        if (hasPerm) {
            teleportAction(player, SelectionAPI.SyncState.ASYNC);
        }
    }

    private static void teleportAction(Player player, SelectionAPI.SyncState syncState){
        Cache cache = RTP.getCache();

        SetupTeleport setupTeleport = cache.setupTeleports.get(player.getUniqueId());
        LoadChunks loadChunks = cache.loadChunks.get(player.getUniqueId());
        DoTeleport doTeleport = cache.doTeleports.get(player.getUniqueId());
        if (setupTeleport != null && setupTeleport.isNoDelay()) return;
        if (loadChunks != null && loadChunks.isNoDelay()) return;
        if (doTeleport != null && doTeleport.isNoDelay()) return;

        cache.lastTeleportTime.put(player.getUniqueId(),System.nanoTime());
        setupTeleport = new SetupTeleport(player, player, new RandomSelectParams(player.getWorld(),null));

        if(RTPAPI.getServerIntVersion()>8) {
            switch (syncState) {
                case SYNC -> setupTeleport.setupTeleportNow(SelectionAPI.SyncState.SYNC);
                case ASYNC -> setupTeleport.runTaskLaterAsynchronously(RTP.getInstance(), 1);
                case ASYNC_URGENT -> setupTeleport.runTaskAsynchronously(RTP.getInstance());
            }
        }
        else {
            setupTeleport.setupTeleportNow(SelectionAPI.SyncState.SYNC);
        }
    }
}

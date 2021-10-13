package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
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

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

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
            //skip if already going
            SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
            LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
            DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
            if (setupTeleport != null && setupTeleport.isNoDelay()) return;
            if (loadChunks != null && loadChunks.isNoDelay()) return;
            if (doTeleport != null && doTeleport.isNoDelay()) return;

            //run command as console
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "rtp player:" + player.getName() + " world:" + player.getWorld().getName());
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
            queueLocation.runTaskLaterAsynchronously(RTP.getPlugin(), 1);
            respawningPlayers.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        RTP plugin = RTP.getPlugin();
        Configs configs = RTP.getConfigs();
        Player player = event.getPlayer();
        if (respawningPlayers.contains(player.getUniqueId())) {
            RandomSelectParams rsParams = new RandomSelectParams(player.getWorld(), null);
            SetupTeleport setupTeleport = new SetupTeleport(Bukkit.getConsoleSender(), player, rsParams);
            setupTeleport.runTaskAsynchronously(plugin);
            cache.setupTeleports.put(player.getUniqueId(), setupTeleport);
            respawningPlayers.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskAsynchronously(RTP.getPlugin(),()->{
            Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
            boolean hasFirstJoin = false;
            boolean hasJoin = false;
            for(PermissionAttachmentInfo perm : perms) {
                if(!perm.getValue()) continue;
                if(!perm.getPermission().startsWith("rtp.onevent.")) continue;
                if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.respawn"))
                    hasFirstJoin = true;
                if(perm.getPermission().equals("rtp.onevent.*") || perm.getPermission().equals("rtp.onevent.respawn"))
                    hasJoin = true;
            }
            if (hasFirstJoin && !player.hasPlayedBefore()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "rtp player:" + player.getName() + " world:" + player.getWorld().getName());
            } else if (hasJoin) {
                //skip if already going
                SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
                LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
                DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
                if (setupTeleport != null && setupTeleport.isNoDelay()) return;
                if (loadChunks != null && loadChunks.isNoDelay()) return;
                if (doTeleport != null && doTeleport.isNoDelay()) return;

                //run command as console
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "rtp player:" + player.getName() + " world:" + player.getWorld().getName());
            }
        });
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        RTP plugin = RTP.getPlugin();
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
            //skip if already going
            SetupTeleport setupTeleport = this.cache.setupTeleports.get(player.getUniqueId());
            LoadChunks loadChunks = this.cache.loadChunks.get(player.getUniqueId());
            DoTeleport doTeleport = this.cache.doTeleports.get(player.getUniqueId());
            if (setupTeleport != null && setupTeleport.isNoDelay()) return;
            if (loadChunks != null && loadChunks.isNoDelay()) return;
            if (doTeleport != null && doTeleport.isNoDelay()) return;

            //run command
            if (setupTeleport == null && loadChunks == null && doTeleport == null) {
                Bukkit.getScheduler().runTaskLater(plugin,()->player.performCommand("rtp"),1);
            }
        }
    }
}

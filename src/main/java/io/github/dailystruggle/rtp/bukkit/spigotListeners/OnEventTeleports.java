package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
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
import java.util.concurrent.*;
import java.util.logging.Level;

public class OnEventTeleports implements Listener {
    private final ConcurrentSkipListSet<UUID> respawningPlayers = new ConcurrentSkipListSet<>();
    private final Map<UUID,Double> playerMoveDistances = new HashMap<>();

    private static boolean checkPerms(Player player, String... permissions) {
        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasPerm = false;
        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            String s = perm.getPermission().toLowerCase();
            if(!s.startsWith("rtp.onevent.")) continue;
            if(s.equals("rtp.onevent.*")) return true;
            for(String permission : permissions) {
                if(s.equals("rtp.onevent." + permission.toLowerCase())) return true;
            }
        }
        return hasPerm;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (checkPerms(event.getPlayer(),"changeWorld")) {
            teleportAction(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();

        //cancel previous teleport
        new RTPTeleportCancel(id).run();

        if (!checkPerms(player,"respawn")) return;

        respawningPlayers.add(id);

        Region region = RTP.getInstance().selectionAPI.getRegion(new BukkitRTPPlayer(player));

        //I don't know how this can happen, but in case player dies twice, don't reprocess
        if(region.fastLocations.containsKey(id)) return;

        CompletableFuture<RTPLocation> future = new CompletableFuture<>();
        region.fastLocations.put(id, future);

        if(RTP.getInstance().latestTeleportData.containsKey(id)) {
            RTP.getInstance().priorTeleportData.put(id,RTP.getInstance().latestTeleportData.get(id));
        }

        //prep location so it's ready when they respawn or shortly after
        TeleportData data = new TeleportData();
        RTP.getInstance().latestTeleportData.put(id, data);
        region.miscPipeline.add(() -> {
            RTPLocation location = null;
            int i = 0;
            for(; location==null && i<10;i++) {
                location = region.getLocation(
                        new BukkitRTPCommandSender(Bukkit.getConsoleSender()),
                        new BukkitRTPPlayer(player),
                        null);
            }
            if(location == null) {
                RTP.log(Level.WARNING, "[RTP] failed to generate respawn location within 10 attempts. \n region - " + region);
            }
            data.selectedLocation = location;
            future.complete(location);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        
        if (!respawningPlayers.contains(player.getUniqueId())) return;
        respawningPlayers.remove(player.getUniqueId());

        Region region = RTP.getInstance().selectionAPI.getRegion(new BukkitRTPPlayer(player));
        ConcurrentHashMap<UUID, CompletableFuture<RTPLocation>> respawnLocations = region.fastLocations;
        if(!respawnLocations.containsKey(player.getUniqueId())) return;

        CompletableFuture<RTPLocation> future = respawnLocations.get(player.getUniqueId());
        region.fastLocations.remove(player.getUniqueId());

        TeleportData data = RTP.getInstance().latestTeleportData.get(player.getUniqueId());
        data.completed = true;

        if(future.isDone()) {
            RTPLocation rtpLocation;
            try {
                rtpLocation = future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return;
            }

            RTPWorld rtpWorld = rtpLocation.world();
            if(rtpWorld instanceof BukkitRTPWorld bukkitRTPWorld) {
                event.setRespawnLocation(new Location(bukkitRTPWorld.world(),rtpLocation.x(),rtpLocation.y(),rtpLocation.z()));
            }
            else throw new IllegalStateException("expected bukkit world");
            return;
        }

        future.whenComplete((location, throwable) -> {
            SetupTeleport setupTeleport = new SetupTeleport(
                    new BukkitRTPCommandSender(Bukkit.getConsoleSender()),
                    new BukkitRTPPlayer(player),
                    region,
                    null
            );
            data.nextTask = setupTeleport;
            RTP.getInstance().setupTeleportPipeline.add(setupTeleport);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        RTP rtp = RTP.getInstance();

        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();
        boolean hasFirstJoin = false;
        boolean hasJoin = false;

        long start = System.nanoTime();

        TeleportData data = rtp.latestTeleportData.get(player.getUniqueId());
        if(data == null) return;

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) rtp.configs.configParserMap.get(ConfigKeys.class);

        //handle both integer and floating point inputs
        Number cooldownConfig = configParser.getNumber(ConfigKeys.teleportCooldown, 2);
        long cooldownTime = TimeUnit.MILLISECONDS.toNanos((long) (cooldownConfig.doubleValue()*1000));

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
            if (!player.hasPermission("rtp.nocooldown") && (start - data.time) < cooldownTime){
                ConfigParser<LangKeys> langParser = (ConfigParser<LangKeys>) rtp.configs.configParserMap.get(LangKeys.class);

                long remaining = (data.time - start) + cooldownTime;
                long days = TimeUnit.NANOSECONDS.toDays(remaining);
                long hours = TimeUnit.NANOSECONDS.toHours(remaining) % 24;
                long minutes = TimeUnit.NANOSECONDS.toMinutes(remaining) % 60;
                long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining) % 60;
                String replacement = "";
                if (days > 0) replacement += days + langParser.getConfigValue(LangKeys.days,"d").toString() + " ";
                if (days > 0 || hours > 0) replacement += hours + langParser.getConfigValue(LangKeys.hours,"h").toString() + " ";
                if (days > 0 || hours > 0 || minutes > 0) replacement += minutes + langParser.getConfigValue(LangKeys.minutes,"m").toString() + " ";
                replacement += seconds + langParser.getConfigValue(LangKeys.seconds,"s").toString();
                String msg = langParser.getConfigValue(LangKeys.cooldownMessage,"").toString();
                msg = msg.replaceAll("[time]",replacement);
                SendMessage.sendMessage(player, msg);
                return;
            }
            teleportAction(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if(to == null) return;
        Location from = event.getFrom();
        Player player = event.getPlayer();

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.getInstance().configs.configParserMap.get(ConfigKeys.class);

        playerMoveDistances.putIfAbsent(player.getUniqueId(),0D);
        playerMoveDistances.compute(player.getUniqueId(),(uuid, aDouble) -> aDouble+=from.distance(to));
        Double distance = playerMoveDistances.get(player.getUniqueId());
        if(distance < 1.0) return;
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
            teleportAction(player);
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
            teleportAction(player);
        }
    }

    private static void teleportAction(Player player){
        RTP.getInstance().serverAccessor.sendMessage(player.getUniqueId(),"todo: teleportAction");
    }
}

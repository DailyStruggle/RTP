package leafcraft.rtp.tasks;

import leafcraft.rtp.API.selection.SyncState;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

//prep teleportation
public class SetupTeleport extends BukkitRunnable {
    private final RTP plugin;
    private final CommandSender sender;
    private final Player player;
    private final UUID playerId;
    private final Configs configs;
    private final Cache cache;
    private final RandomSelectParams rsParams;
    private boolean cancelled = false;

    public SetupTeleport(RTP plugin, CommandSender sender, Player player, Configs configs, Cache cache, RandomSelectParams rsParams) {
        this.sender = sender;
        this.plugin = plugin;
        this.player = player;
        this.playerId = player.getUniqueId();
        this.configs = configs;
        this.cache = cache;
        this.rsParams = rsParams;
    }

    @Override
    public void run() {
        setupTeleportNow(SyncState.ASYNC_URGENT);
    }

    @Override
    public void cancel() {
        cancelled = true;
        super.cancel();
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }

    public void setupTeleportNow(SyncState state) {
        //get a random location according to the parameters
        Location location;
        location = cache.getRandomLocation(rsParams, state, sender, player);
        if (location == null) {
            cache.setupTeleports.remove(player.getUniqueId());
            return;
        }
        cache.todoTP.put(player.getUniqueId(), location);

        //get warmup delay
        long delay = configs.config.teleportDelay;

        Set<PermissionAttachmentInfo> perms = sender.getEffectivePermissions();

        if (sender.hasPermission("rtp.noDelay")) {
            delay = 0;
        } else {
            for (PermissionAttachmentInfo perm : perms) {
                if(!perm.getValue()) continue;
                String node = perm.getPermission();
                if (node.startsWith("rtp.delay.")) {
                    String[] val = node.split("\\.");
                    if (val.length < 3 || val[2] == null || val[2].equals("")) continue;
                    int number;
                    try {
                        number = Integer.parseInt(val[2]);
                    } catch (NumberFormatException exception) {
                        Bukkit.getLogger().warning("[rtp] invalid permission: " + node);
                        continue;
                    }
                    delay = number*20L;
                    break;
                }
            }
        }

        //let player know if warmup delay > 0
        if(delay>0) {
            long time = delay/20;
            long days = TimeUnit.SECONDS.toDays(time);
            long hours = TimeUnit.SECONDS.toHours(time)%24;
            long minutes = TimeUnit.SECONDS.toMinutes(time)%60;
            long seconds = TimeUnit.SECONDS.toSeconds(time)%60;
            String replacement = "";
            if(days>0) replacement += days + configs.lang.getLog("days") + " ";
            if(hours>0) replacement += hours + configs.lang.getLog("hours") + " ";
            if(minutes>0) replacement += minutes + configs.lang.getLog("minutes") + " ";
            if(seconds>0) replacement += seconds%60 + configs.lang.getLog("seconds");
            String msg = configs.lang.getLog("delayMessage", replacement);
            SendMessage.sendMessage(sender,player,msg);
        }

        //set up task to load chunks then teleport
        if(!cancelled){
            cache.regionKeys.put(player.getUniqueId(),rsParams);
            LoadChunks loadChunks = new LoadChunks(plugin,configs,sender,player,cache,delay, location);
            if(sender.hasPermission("rtp.noDelay.chunks")
                    || (loadChunks.chunkSet.completed.get()>=loadChunks.chunkSet.expectedSize-1)) {
                DoTeleport doTeleport = new DoTeleport(plugin,configs,sender,player, location,cache);

                long diffNanos = System.nanoTime() - cache.lastTeleportTime.getOrDefault(player.getUniqueId(), 0L);
                long diffMicros = TimeUnit.NANOSECONDS.toMicros(diffNanos);
                long diffTicks = (diffMicros / 50);
                if(state.equals(SyncState.SYNC) && diffTicks >= delay) {
                    doTeleport.doTeleportNow();
                }
                else {
                    doTeleport.runTaskLater(plugin, delay+2);
                    cache.doTeleports.put(player.getUniqueId(),doTeleport);
                }

            }
            else {
                loadChunks.runTaskAsynchronously(plugin);
                cache.loadChunks.put(player.getUniqueId(), loadChunks);
            }
        }
        cache.setupTeleports.remove(player.getUniqueId());
    }
}

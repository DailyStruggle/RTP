package leafcraft.rtp.customEventListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.customEvents.RandomTeleportEvent;
import leafcraft.rtp.customEvents.TeleportCancelEvent;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class OnTeleportCancel implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnTeleportCancel(RTP plugin, Configs configs, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void OnTeleportCancel(TeleportCancelEvent event) {
        Player player = event.getPlayer();

        //don't stop teleporting if there isn't supposed to be a delay
        SetupTeleport setupTeleport = cache.setupTeleports.get(player.getUniqueId());
        LoadChunks loadChunks = cache.loadChunks.get(player.getUniqueId());
        DoTeleport doTeleport = cache.doTeleports.get(player.getUniqueId());
        if (setupTeleport != null && setupTeleport.isNoDelay()) return;
        if (loadChunks != null && loadChunks.isNoDelay()) return;
        if (doTeleport != null && doTeleport.isNoDelay()) return;

        if (setupTeleport != null && !setupTeleport.isCancelled()) {
            setupTeleport.cancel();
            cache.loadChunks.remove(player.getUniqueId());
        }
        if (loadChunks != null && !loadChunks.isCancelled()) {
            loadChunks.cancel();
            cache.loadChunks.remove(player.getUniqueId());
        }
        if (doTeleport != null && !doTeleport.isCancelled()) {
            doTeleport.cancel();
            cache.doTeleports.remove(player.getUniqueId());
        }

        RandomSelectParams rsParams = cache.regionKeys.get(player.getUniqueId());
        if (cache.permRegions.containsKey(rsParams)) {
            Location randomLocation = cache.todoTP.get(player.getUniqueId());
            QueueLocation queueLocation = new QueueLocation(cache.permRegions.get(rsParams), randomLocation, cache);
            cache.queueLocationTasks.put(queueLocation.idx,queueLocation);
            queueLocation.runTaskLaterAsynchronously(plugin, 1);
        } else cache.tempRegions.remove(rsParams);

        cache.regionKeys.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        cache.playerFromLocations.remove(player.getUniqueId());

        if(configs.config.refund && event.getSender() instanceof Player) {
            Player sourcePlayer = (Player) event.getSender();
            cache.lastTeleportTime.remove(sourcePlayer.getUniqueId());
            Economy economy = VaultChecker.getEconomy();
            if(economy!=null && economy.isEnabled()) {
                double cost = cache.currentTeleportCost.getOrDefault(sourcePlayer.getUniqueId(),0D);
                economy.depositPlayer(sourcePlayer,cost);
            }
        }

        String msg = configs.lang.getLog("teleportCancel");
        SendMessage.sendMessage(event.getSender(),event.getPlayer(),msg);
    }
}
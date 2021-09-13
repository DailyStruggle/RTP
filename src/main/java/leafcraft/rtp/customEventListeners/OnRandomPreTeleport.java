package leafcraft.rtp.customEventListeners;

import leafcraft.rtp.RTP;
import leafcraft.rtp.customEvents.RandomPreTeleportEvent;
import leafcraft.rtp.customEvents.RandomTeleportEvent;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class OnRandomPreTeleport implements Listener {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    public OnRandomPreTeleport(RTP plugin, Configs configs, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRandomPreTeleport(RandomPreTeleportEvent event) {
        Player player = event.getPlayer();
        CommandSender sender = event.getSender();
        Location to = event.getTo();

        if(configs.config.blindnessDuration>0)
            player.addPotionEffect(PotionEffectType.BLINDNESS.createEffect(configs.config.blindnessDuration,100),false);
        RandomTeleportEvent randomTeleportEvent = new RandomTeleportEvent(sender, player, to, cache.numTeleportAttempts.get(to));
        Bukkit.getPluginManager().callEvent(randomTeleportEvent);
    }
}
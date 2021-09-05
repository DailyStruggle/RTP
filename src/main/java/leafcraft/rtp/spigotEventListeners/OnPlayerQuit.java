package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.customEvents.TeleportCancelEvent;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;
import java.util.UUID;

public final class OnPlayerQuit implements Listener {
    private final Cache cache;

    public OnPlayerQuit(Cache cache) {
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void OnPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if(cache.invulnerablePlayers.containsKey(playerId)) {
            event.getPlayer().setInvulnerable(false);
            cache.invulnerablePlayers.remove(playerId);
        }

        CommandSender sender = cache.commandSenderLookup.get(player.getUniqueId());
        if(sender == null) return;

        Location to = cache.todoTP.get(player.getUniqueId());
        if(to == null) return;

        TeleportCancelEvent teleportCancelEvent = new TeleportCancelEvent(sender,player,to);
        Bukkit.getPluginManager().callEvent(teleportCancelEvent);
    }
}

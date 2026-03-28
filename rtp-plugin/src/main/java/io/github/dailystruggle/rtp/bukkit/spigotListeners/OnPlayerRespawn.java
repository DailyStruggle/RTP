package io.github.dailystruggle.rtp.bukkit.spigotListeners;


import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

//get queued location
public final class OnPlayerRespawn implements Listener {

    public OnPlayerRespawn() {
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("rtp.personalqueue")) {
            Region region = RTP.selectionAPI.getRegion(RTP.serverAccessor.getPlayer(player.getUniqueId()));
            if (region == null) return;
            region.queue(player.getUniqueId());
        }
    }
}



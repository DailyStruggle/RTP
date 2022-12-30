package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

public final class OnPlayerChangeWorld implements Listener {
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        new RTPTeleportCancel(event.getPlayer().getUniqueId()).run();

        Player player = event.getPlayer();

        if(player.hasPermission("rtp.personalQueue")) {
            Region region = RTP.selectionAPI.getRegion(new BukkitRTPPlayer(player));
            if(region == null) return;
            region.queue(player.getUniqueId());
        }
    }
}

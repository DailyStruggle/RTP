package leafcraft.rtp.customEventListeners;

import leafcraft.rtp.API.customEvents.PlayerQueuePopEvent;
import leafcraft.rtp.API.customEvents.PlayerQueuePushEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

public class PlayerQueueListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    void onPlayerQueuePop(PlayerQueuePopEvent event) {
        int pos = 0;
        for(UUID uuid : event.getRegion().getPlayerQueue()) {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null || !player.isOnline()) continue;
            pos++;
            SendMessage.sendMessage(player, RTP.getConfigs().lang.getLog("queueUpdate", String.valueOf(pos)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void onPlayerQueuePush(PlayerQueuePushEvent event) {
        UUID uuid = event.getPlayerId();
        Player player = Bukkit.getPlayer(uuid);
        if(player == null || !player.isOnline()) return;
        SendMessage.sendMessage(player, RTP.getConfigs().lang.getLog("noLocationsQueued"));
        int pos = event.getRegion().getPlayerQueue().size();
        SendMessage.sendMessage(player, RTP.getConfigs().lang.getLog("queueUpdate", String.valueOf(pos)));
    }
}

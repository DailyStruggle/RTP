package leafcraft.rtp.spigotEventListeners;

import leafcraft.rtp.RTP;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public class OnPlayerDamage implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if(!(entity instanceof Player)) return;
        if(!RTP.getCache().invulnerablePlayers.contains(entity.getUniqueId())) return;
        event.setCancelled(true);
    }
}

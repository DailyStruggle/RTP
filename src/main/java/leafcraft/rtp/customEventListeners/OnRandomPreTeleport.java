package leafcraft.rtp.customEventListeners;

import leafcraft.rtp.API.customEvents.RandomPreTeleportEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.configuration.Configs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;

public final class OnRandomPreTeleport implements Listener {
    private final Configs configs;

    public OnRandomPreTeleport() {
        this.configs = RTP.getConfigs();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRandomPreTeleport(RandomPreTeleportEvent event) {
        Player player = event.getPlayer();
        if(configs.config.blindnessDuration>0)
            player.addPotionEffect(PotionEffectType.BLINDNESS.createEffect(configs.config.blindnessDuration,100));
    }
}
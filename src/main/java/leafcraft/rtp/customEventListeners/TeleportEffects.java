package leafcraft.rtp.customEventListeners;

import io.github.dailystruggle.effectsapi.Effect;
import io.github.dailystruggle.effectsapi.EffectBuilder;
import leafcraft.rtp.API.customEvents.RandomTeleportEvent;
import leafcraft.rtp.API.customEvents.TeleportCommandSuccessEvent;
import leafcraft.rtp.RTP;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.*;

public class TeleportEffects implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleportCommand(TeleportCommandSuccessEvent event) {
        List<Effect> effects = EffectBuilder.buildEffects("rtp.command",event.getPlayer().getEffectivePermissions());
        for( Effect e : effects ) e.trigger(event.getPlayer(),RTP.getInstance());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRandomTeleport(RandomTeleportEvent event) {
        List<Effect> effects = EffectBuilder.buildEffects("rtp.teleport",event.getPlayer().getEffectivePermissions());
        for( Effect e : effects ) e.trigger(event.getPlayer(),RTP.getInstance());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRandomPreTeleport(RandomTeleportEvent event) {
        List<Effect> effects = EffectBuilder.buildEffects("rtp.preTeleport",event.getPlayer().getEffectivePermissions());
        for( Effect e : effects ) e.trigger(event.getPlayer(),RTP.getInstance());
    }
}

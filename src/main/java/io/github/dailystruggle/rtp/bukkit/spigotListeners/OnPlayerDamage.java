package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.concurrent.TimeUnit;

public class OnPlayerDamage implements Listener {
    @EventHandler( priority = EventPriority.LOWEST )
    public void onPlayerDamage( EntityDamageEvent event ) {
        Entity entity = event.getEntity();
        if ( !(entity instanceof Player) ) return;
        if ( !RTP.getInstance().invulnerablePlayers.containsKey( entity.getUniqueId()) ) return;
        long lastTeleport = RTP.getInstance().invulnerablePlayers.get( entity.getUniqueId() );
        long current = System.currentTimeMillis();

        ConfigParser<SafetyKeys> safety = ( ConfigParser<SafetyKeys> ) RTP.configs.getParser( SafetyKeys.class );
        long max = safety.getNumber( SafetyKeys.invulnerabilityTime, 0 ).longValue();

        if ( max <= 0 ) {
            RTP.getInstance().invulnerablePlayers.remove( entity.getUniqueId() );
            return;
        }

        long dt = current - lastTeleport;
        if ( dt < 0 ) dt = dt - Long.MIN_VALUE;

        if ( dt < TimeUnit.SECONDS.toMillis( max) ) event.setCancelled( true );
        else RTP.getInstance().invulnerablePlayers.remove( entity.getUniqueId() );
    }
}

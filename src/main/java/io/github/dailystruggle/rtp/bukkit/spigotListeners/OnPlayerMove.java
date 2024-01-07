package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class OnPlayerMove implements Listener {
    private double cancelDistanceSquared = 2;
    private long lastUpdateTime = 0;

    @EventHandler( priority = EventPriority.LOW )
    public void onPlayerMove( PlayerMoveEvent event ) {
        UUID id = event.getPlayer().getUniqueId();

        TeleportData data = RTP.getInstance().latestTeleportData.get( id );
        if ( data == null || data.completed ) return;

        long t = System.nanoTime();
        if ( t < lastUpdateTime ||
                ( (t - lastUpdateTime ) > TimeUnit.SECONDS.toNanos( 5)) ) {
            ConfigParser<?> parser = RTP.configs.configParserMap.get( ConfigKeys.class );
            ConfigParser<ConfigKeys> configParser;
            if( parser.myClass.equals( ConfigKeys.class ) )
                //noinspection unchecked
                configParser = ( ConfigParser<ConfigKeys> ) parser;
            else {
                RTP.log( Level.SEVERE, "", new IllegalStateException( "ConfigParser is not using ConfigKeys") );
                return;
            }

            cancelDistanceSquared = Math.pow( configParser.getNumber( ConfigKeys.cancelDistance, 0 ).doubleValue(), 2 );
            lastUpdateTime = t;
        }

        RTPPlayer player = new BukkitRTPPlayer( event.getPlayer() );

        RTPLocation originalLocation = data.originalLocation;
        if ( originalLocation == null ) {
            return;
        }

        if ( originalLocation.distanceSquared( player.getLocation() ) < cancelDistanceSquared ) return;

        new RTPTeleportCancel( id ).run();
    }
}

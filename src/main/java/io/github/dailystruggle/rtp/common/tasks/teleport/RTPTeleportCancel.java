package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class RTPTeleportCancel extends RTPRunnable {
    public static final List<Consumer<RTPTeleportCancel>> preActions = new ArrayList<>();
    public static final List<Consumer<RTPTeleportCancel>> postActions = new ArrayList<>();
    private final UUID playerId;

    public RTPTeleportCancel( UUID playerId ) {
        this.playerId = playerId;
    }

    public static void refund( UUID playerId ) {
        ConfigParser<EconomyKeys> eco = ( ConfigParser<EconomyKeys> ) RTP.configs.configParserMap.get( EconomyKeys.class );
        Object configValue = eco.getConfigValue( EconomyKeys.refundOnCancel, Boolean.TRUE );
        System.out.println( configValue );

        boolean refund;
        if( configValue instanceof Boolean ) refund = ( Boolean ) configValue;
        else if( configValue instanceof String ) refund = Boolean.parseBoolean( (String ) configValue );
        else refund = Boolean.parseBoolean( configValue.toString() );

        System.out.println( refund );


        //check if teleporting
        TeleportData data = RTP.getInstance().latestTeleportData.get( playerId );
        if ( data == null ) return;
        if ( data.completed ) return;

//        if( data.selectedLocation!=null && data.targetRegion!=null && ( data.biomes==null || data.biomes.size()==0) ) {
//            data.targetRegion.locationQueue.add( new AbstractMap.SimpleEntry<>( data.selectedLocation, data.attempts) );
//        }

        //reset player data
        TeleportData repData = RTP.getInstance().priorTeleportData.get( playerId );
        RTP.getInstance().priorTeleportData.remove( playerId );
        if ( repData != null )
            RTP.getInstance().latestTeleportData.put( playerId, repData );
        else
            RTP.getInstance().latestTeleportData.remove( playerId );

        if ( RTP.economy != null && data.cost != 0.0 ) {
            if ( refund && data.sender instanceof RTPPlayer ) {
                RTP.economy.give( data.sender.uuid(), data.cost );
            }
        }

        RTP.getInstance().processingPlayers.remove( playerId );
    }

    public static void message( UUID playerId ) {
        ConfigParser<MessagesKeys> lang = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
        String msg = lang.getConfigValue( MessagesKeys.teleportCancel, "" ).toString();
        RTP.serverAccessor.sendMessage( playerId, msg );
    }

    @Override
    public void run() {
        preActions.forEach( rtpTeleportCancelConsumer -> rtpTeleportCancelConsumer.accept( this) );

        //check if teleporting
        TeleportData data = RTP.getInstance().latestTeleportData.get( playerId );
        if ( data == null ) return;
        if ( data.completed ) return;
        if ( data.nextTask == null ) return;

        //check no-cancel permission
        RTPPlayer player = RTP.serverAccessor.getPlayer( playerId );
        if ( player != null && player.isOnline() && player.hasPermission( "rtp.noCancel") ) return;

        data.nextTask.setCancelled( true );

        //dump location back onto the pile
//        if( data.selectedLocation!=null ) data.targetRegion.locationQueue.add( new AbstractMap.SimpleEntry<>( data.selectedLocation,data.attempts) );

        refund( playerId );

        message( playerId );

        postActions.forEach( rtpTeleportCancelConsumer -> rtpTeleportCancelConsumer.accept( this) );
    }

    public UUID getPlayerId() {
        return playerId;
    }
}

package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FillStartCmd extends FillSubCmd {
    public FillStartCmd( @Nullable CommandsAPICommand parent ) {
        super( parent );
    }

    @Override
    public String name() {
        return "start";
    }

    @Override
    public String description() {
        return "clear region data and start from 0";
    }

    @Override
    public boolean onCommand( UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand ) {
        if ( nextCommand != null ) return true;

        List<Region> regions = getRegions( callerId, parameterValues.get( "region") );
        for ( Region region : regions ) {
            FillTask fillTask = RTP.getInstance().fillTasks.get( region.name );
            ConfigParser<MessagesKeys> parser = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
            if ( fillTask != null ) {
                if ( parser == null ) continue;
                String msg = String.valueOf( parser.getConfigValue( MessagesKeys.fillRunning, "") );
                if ( msg == null || msg.isEmpty() ) continue;
                msg = msg.replace( "[region]", region.name );
                RTP.serverAccessor.announce( msg, "rtp.fill" );
                continue;
            }

            Shape<?> shapeObj = region.getShape();
            MemoryShape<?> shape;
            if ( shapeObj instanceof MemoryShape ) {
                shape = ( MemoryShape<?> ) shapeObj;
            } else {
                if ( parser == null ) continue;
                String msg = String.valueOf( parser.getConfigValue( MessagesKeys.badArg, "") );
                if ( msg == null || msg.isEmpty() ) continue;
                msg = msg.replace( "[arg]", "region:" + region.name );
                RTP.serverAccessor.sendMessage( callerId, msg );
                continue;
            }

            shape.badLocations.clear();
            shape.biomeLocations.clear();
            shape.badLocationSum.set( 0 );

            RTP.getInstance().fillTasks.put( region.name, new FillTask( region, 0L) );
            if ( parser == null ) continue;
            String msg = String.valueOf( parser.getConfigValue( MessagesKeys.fillStart, "") );
            if ( msg == null || msg.isEmpty() ) continue;
            msg = msg.replace( "[region]", region.name );
            RTP.serverAccessor.announce( msg, "rtp.fill" );
        }

        return true;
    }

    public List<Region> getRegions( UUID callerId, List<String> regionParameter ) {
        List<Region> regions = new ArrayList<>();
        RTPCommandSender sender = RTP.serverAccessor.getSender( callerId );
        if ( regionParameter != null ) {
            for ( String name : regionParameter ) regions.add( RTP.selectionAPI.getRegion( name) );
        } else if ( sender instanceof RTPPlayer ) regions.add( RTP.selectionAPI.getRegion( (RTPPlayer ) sender) );
        else regions.add( RTP.selectionAPI.getRegion( "default") );
        return regions;
    }
}

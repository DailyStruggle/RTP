package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class ReloadCmd extends BaseRTPCmdImpl {

    public ReloadCmd( @Nullable CommandsAPICommand parent ) {
        super( parent );

        addCommands();
    }

    public void addCommands() {
        final Configs configs = RTP.configs;
        if ( configs == null ) RTP.getInstance().miscAsyncTasks.add( new RTPRunnable( this::addCommands, 1) );
        for ( ConfigParser<?> value : configs.configParserMap.values() ) {
            String name = value.name.replace( ".yml", "" );
            if ( getCommandLookup().containsKey( name) ) continue;
            addSubCommand( new SubReloadCmd<>( this, value.name, "rtp.reload", "reload only " + value.name, value.myClass) );
        }

        for ( Map.Entry<Class<?>, MultiConfigParser<?>> e : configs.multiConfigParserMap.entrySet() ) {
            MultiConfigParser<? extends Enum<?>> value = e.getValue();
            if ( getCommandLookup().containsKey( value.name) ) continue;
            addSubCommand( new SubReloadCmd<>( this, value.name, "rtp.reload", "reload only " + value.name, value.myClass) );
        }
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "rtp.reload";
    }

    @Override
    public String description() {
        return "reload config files";
    }

    private static final Pattern filenamePattern = Pattern.compile( "\\[filename]",Pattern.CASE_INSENSITIVE );
    @Override
    public boolean onCommand( UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand ) {
        RTP.reloading.set( true );

        if ( nextCommand == null ) {
            RTP.stop();

            ConfigParser<MessagesKeys> lang = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
            if ( lang != null ) {
                String msg = String.valueOf( lang.getConfigValue( MessagesKeys.reloading, "") );
                if ( msg != null ) msg = filenamePattern.matcher( msg ).replaceAll( "configs" );//msg = msg.replaceAll( "\\[filename]", "configs" );
                RTP.serverAccessor.sendMessage( CommandsAPI.serverId, senderId, msg );
            }

            boolean b = RTP.configs.reload();
            if ( !b ) throw new IllegalStateException( "reload failed" );

            if ( lang != null ) {
                String msg = String.valueOf( lang.getConfigValue( MessagesKeys.reloaded, "") );
                if ( msg != null ) msg = filenamePattern.matcher( msg ).replaceAll( "configs" );//msg.replaceAll( "\\[filename]", "configs" );
                RTP.serverAccessor.sendMessage( senderId, msg );
            }

            RTP.serverAccessor.start();

            RTP.getInstance().miscSyncTasks.add( new RTPRunnable( () -> {
                RTP.reloading.set( false );
            }, 1) );

            RTP.getInstance().miscSyncTasks.start();
            RTP.getInstance().miscAsyncTasks.start();
            RTP.getInstance().setupTeleportPipeline.start();
            RTP.getInstance().loadChunksPipeline.start();
            RTP.getInstance().teleportPipeline.start();
            RTP.getInstance().startupTasks.start();
            RTP.getInstance().getChunkPipeline.start();
        }

        return true;
    }
}

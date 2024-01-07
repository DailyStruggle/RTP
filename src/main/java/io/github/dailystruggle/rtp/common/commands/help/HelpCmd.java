package io.github.dailystruggle.rtp.common.commands.help;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.reload.SubReloadCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HelpCmd extends BaseRTPCmdImpl {

    public HelpCmd( @Nullable CommandsAPICommand parent ) {
        super( parent );

        RTP.getInstance().miscAsyncTasks.add( new RTPRunnable( this::addCommands, 20) );
    }

    public void addCommands() {
        final Configs configs = RTP.configs;
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
        return "help";
    }

    @Override
    public String permission() {
        return "rtp.see";
    }

    @Override
    public String description() {
        return "describe commands";
    }

    @Override
    public boolean onCommand( UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand ) {
        RTPCommandSender sender = RTP.serverAccessor.getSender( callerId );
        ConfigParser<MessagesKeys> lang = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
        if ( !sender.hasPermission( "rtp.see") ) {
            RTP.serverAccessor.sendMessage( callerId, MessagesKeys.noPerms );
            return true;
        }

        String msg = lang.getConfigValue( MessagesKeys.rtp, "" ).toString();
        String hover = "/rtp";
        String click = "/rtp";

        SendMessage.sendMessage( sender, msg, hover, click );

        for ( CommandsAPICommand entry : RTP.baseCommand.getCommandLookup().values() ) {
            if ( sender.hasPermission( entry.permission()) ) {
                String arg = entry.name();

                MessagesKeys key;
                try {
                    key = MessagesKeys.valueOf( entry.name() );
                } catch ( IllegalArgumentException ignored ) {
                    continue;
                }

                msg = lang.getConfigValue( key, "" ).toString();
                hover = "/rtp " + arg;
                click = "/rtp " + arg;

                SendMessage.sendMessage( sender, msg, hover, click );
            }
        }
        return true;
    }
}

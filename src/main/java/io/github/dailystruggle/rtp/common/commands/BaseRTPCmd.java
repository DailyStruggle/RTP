package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;

import java.util.UUID;

public interface BaseRTPCmd extends TreeCommand {
    @Override
    default void msgBadParameter( UUID callerId, String parameterName, String parameterValue ) {
        ConfigParser<MessagesKeys> lang = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );

        String msg = String.valueOf( lang.getConfigValue( MessagesKeys.badArg, "") );
        msg = msg.replace( "[arg]", parameterName + ":" + parameterValue );
        RTP.serverAccessor.sendMessage( callerId, msg );
    }
}

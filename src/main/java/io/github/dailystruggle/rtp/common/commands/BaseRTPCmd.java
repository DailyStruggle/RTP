package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;

import java.util.UUID;

public interface BaseRTPCmd extends TreeCommand {
    @Override
    default void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);

        String msg = String.valueOf(lang.getConfigValue(LangKeys.badArg,""));
        msg = msg.replace("[arg]",parameterName + ":" + parameterValue);
        RTP.getInstance().serverAccessor.sendMessage(callerId,msg);
    }
}

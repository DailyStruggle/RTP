package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.UUID;

/** Base interface for RTP commands */
public interface BaseRTPCmd extends TreeCommand {
  @Override
  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
    msgBadParameter(callerId, parameterName, parameterValue, null);
  }

  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue, String tag) {
    ConfigParser<MessagesKeys> lang =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    String msg = String.valueOf(lang.getConfigValue(MessagesKeys.badArg, ""));
    msg = msg.replace("[arg]", parameterName + ":" + parameterValue);
    RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg, tag);
  }
}

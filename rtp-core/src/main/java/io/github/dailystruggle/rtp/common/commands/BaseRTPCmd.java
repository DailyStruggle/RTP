package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Base interface for RTP commands */
public interface BaseRTPCmd extends TreeCommand {
  @Override
  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
    msgBadParameter(callerId, parameterName, parameterValue, (Consumer<String>) null);
  }

  @Override
  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue, java.util.function.Consumer<String> messageMethod) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    String msg = String.valueOf(lang.getConfigValue(MessagesKeys.badArg, "[P0] bad parameter - [arg]"));
    msg = msg.replace("[arg]", parameterName + ":" + parameterValue);
    if(messageMethod != null) messageMethod.accept(msg);
    else RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg, null);
    RTP.log(Level.WARNING, msg);
  }

  @Override
  default void msgInvalidCommand(UUID callerId, String argument) {
    msgInvalidCommand(callerId, argument, null);
  }

  @Override
  default void msgInvalidCommand(UUID callerId, String argument, java.util.function.Consumer<String> messageMethod) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    String msg = String.valueOf(lang.getConfigValue(MessagesKeys.invalidCommand, "[P0] invalid command - [arg]"));
    msg = msg.replace("[arg]", argument);
    if(messageMethod != null) {
        messageMethod.accept(msg);
    } else {
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg, null);
    }
    RTP.log(Level.WARNING, msg);
  }

  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue, String tag) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    String msg = String.valueOf(lang.getConfigValue(MessagesKeys.badArg, "[P0] bad parameter - [arg]"));
    msg = msg.replace("[arg]", parameterName + ":" + parameterValue);
    RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg, tag);
    RTP.log(Level.WARNING, msg);
  }

  @Override
  default boolean onCommand(
      UUID callerId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand) {
    return onCommand(callerId, parameterValues, nextCommand, null);
  }

  @Override
  default boolean onCommand(
      UUID callerId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand,
      java.util.function.Consumer<String> messageMethod) {
    return onCommand(callerId, parameterValues, nextCommand);
  }
}

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
  /**
   * Get the language configuration parser.
   *
   * @return the language configuration parser
   */
  default ConfigParser<MessagesKeys> lang() {
    return (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
  }

  /**
   * Get a message from the language configuration.
   *
   * @param key the message key
   * @param defaultValue the default value if the key is not found
   * @return the message string
   */
  default String msg(MessagesKeys key, Object defaultValue) {
    Object configValue = lang().getConfigValue(key, defaultValue);
    return configValue != null ? configValue.toString() : String.valueOf(defaultValue);
  }

  @Override
  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
    msgBadParameter(callerId, parameterName, parameterValue, (Consumer<String>) null);
  }

  @Override
  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue, java.util.function.Consumer<String> messageMethod) {
    String msg = msg(MessagesKeys.badArg, "[P0] bad parameter - [arg]");
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
    String msg = msg(MessagesKeys.invalidCommand, "[P0] invalid command - [arg]");
    msg = msg.replace("[arg]", argument);
    if(messageMethod != null) {
        messageMethod.accept(msg);
    } else {
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg, null);
    }
    RTP.log(Level.WARNING, msg);
  }

  default void msgBadParameter(UUID callerId, String parameterName, String parameterValue, String tag) {
    String msg = msg(MessagesKeys.badArg, "[P0] bad parameter - [arg]");
    msg = msg.replace("[arg]", parameterName + ":" + parameterValue);
    RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg, tag);
    RTP.log(Level.WARNING, msg);
  }

  @Override
  boolean onCommand(
      UUID callerId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand);

  @Override
  default boolean onCommand(
      UUID callerId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand,
      java.util.function.Consumer<String> messageMethod) {
    return onCommand(callerId, parameterValues, nextCommand);
  }

  /**
   * Get the command description from the language configuration.
   *
   * @return the command description, or the enum key name if not found in config.
   */
  @Override
  default String description() {
    String name = name();
    if (name == null) return "";
    MessagesKeys key;
    try {
      key = MessagesKeys.valueOf(name.toLowerCase() + "_description");
    } catch (IllegalArgumentException ignored) {
      return "";
    }
    // Use key.name() as fallback to ensure a non-empty description for UI/tests
    // even if messages.yml is missing the specific entry.
    return msg(key, key.name());
  }
}

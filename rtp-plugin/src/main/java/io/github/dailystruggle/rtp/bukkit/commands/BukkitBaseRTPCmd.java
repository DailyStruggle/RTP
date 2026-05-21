package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** Base class for Bukkit RTP commands */
public abstract class BukkitBaseRTPCmd extends BukkitTreeCommand
    implements io.github.dailystruggle.rtp.common.commands.BaseRTPCmd {
  /**
   * Constructor for BukkitBaseRTPCmd
   *
   * @param plugin the plugin instance
   * @param parent the parent command
   */
  public BukkitBaseRTPCmd(Plugin plugin, @Nullable CommandsAPICommand parent) {
    super(plugin, parent);
  }

  @Override
  public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
    msgBadParameter(callerId, parameterName, parameterValue, (java.util.function.Consumer<String>) null);
  }

  @Override
  public void msgBadParameter(UUID callerId, String parameterName, String parameterValue, java.util.function.Consumer<String> messageMethod) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    String msg = String.valueOf(lang.getConfigValue(MessagesKeys.badArg, "[P0] bad parameter - [arg]"));
    msg = msg.replace("[arg]", parameterName + "=" + parameterValue);
    // Format ([P0], &-codes, hex) before invoking the Consumer. The
    // messageMethod handed in by BukkitTreeCommand is sender::sendMessage,
    // which writes the *raw* template directly to the player and (because
    // the dispatch came from a player) is mirrored to the console at INFO
    // by Bukkit -- producing the literal '&c[P0] ...' line observed in the
    // F4 live log. The RTP.log(WARNING, ...) call below routes through
    // SendMessage.log which formats independently.
    String formatted = RTP.serverAccessor.format(callerId, msg);
    if(messageMethod != null) {
      // The Consumer (configured by RTPCmdBukkit's messageMethodFactory) routes
      // through SendMessage.sendMessage, which fires SendMessage.intercept(...) --
      // the REQ-RTP-S-004 auditor's CountingHandler is registered there, so this
      // single dispatch suffices for both player delivery and audit coverage.
      // Calling RTP.log(WARNING, ...) in addition would re-emit the same text to
      // the console via SendMessage.log, producing the duplicate `[RTP] invalid
      // command - <arg>` lines observed under `rtp test full`.
      messageMethod.accept(formatted);
    } else if (!callerId.equals(CommandsAPI.serverId)) {
      CommandSender sender = Bukkit.getPlayer(callerId);
      if (sender != null) SendMessage.sendMessage(sender, msg);
      RTP.log(java.util.logging.Level.WARNING, msg);
    } else {
      // Console caller, no Consumer supplied: route through SendMessage.log so
      // the S-004 interceptor fires.
      RTP.log(java.util.logging.Level.WARNING, msg);
    }
  }

  @Override
  public void msgInvalidCommand(UUID callerId, String argument) {
    msgInvalidCommand(callerId, argument, null);
  }

  @Override
  public void msgInvalidCommand(UUID callerId, String argument, java.util.function.Consumer<String> messageMethod) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    String msg = String.valueOf(lang.getConfigValue(MessagesKeys.invalidCommand, "[P0] invalid command - [arg]"));
    msg = msg.replace("[arg]", argument);
    // See msgBadParameter: format before the Consumer sink so the player and
    // the console-mirror both see substituted [P0] / colors instead of '&c[P0]'.
    String formatted = RTP.serverAccessor.format(callerId, msg);
    if(messageMethod != null) {
      // See msgBadParameter: the Consumer routes through SendMessage.sendMessage
      // which already triggers the S-004 auditor via SendMessage.intercept(...).
      // RTP.log here would duplicate the `[RTP] invalid command - <arg>` line.
      messageMethod.accept(formatted);
    } else if (!callerId.equals(CommandsAPI.serverId)) {
      CommandSender sender = Bukkit.getPlayer(callerId);
      if (sender != null) SendMessage.sendMessage(sender, msg);
      RTP.log(java.util.logging.Level.WARNING, msg);
    } else {
      RTP.log(java.util.logging.Level.WARNING, msg);
    }
  }
}

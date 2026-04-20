package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
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
    msg = msg.replace("[arg]", parameterName + ":" + parameterValue);
    if(messageMethod != null) {
      messageMethod.accept(msg);
    } else if (!callerId.equals(CommandsAPI.serverId)) {
      // Player caller: deliver the player-visible message. Console callers are served
      // by the RTP.log(WARNING, ...) call below (which writes to the console sender
      // via SendMessage.log) -- routing through both paths here would cause the
      // duplicate-line pattern observed under `rtp test full` (REQ-RTP-S-004 auditor).
      CommandSender sender = Bukkit.getPlayer(callerId);
      if (sender != null) SendMessage.sendMessage(sender, msg);
    }
    RTP.log(java.util.logging.Level.WARNING, msg);
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
    if(messageMethod != null) {
      messageMethod.accept(msg);
    } else if (!callerId.equals(CommandsAPI.serverId)) {
      // See msgBadParameter: console callers are served by RTP.log(WARNING, ...)
      // alone to avoid the duplicate-line pattern observed under `rtp test full`.
      CommandSender sender = Bukkit.getPlayer(callerId);
      if (sender != null) SendMessage.sendMessage(sender, msg);
    }
    RTP.log(java.util.logging.Level.WARNING, msg);
  }
}

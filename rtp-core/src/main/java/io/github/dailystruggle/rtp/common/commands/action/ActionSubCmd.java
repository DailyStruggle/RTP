package io.github.dailystruggle.rtp.common.commands.action;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionService;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

/**
 * Root subcommand {@code /rtp action} allowing execution of scripted actions by ID (ADR-093).
 */
public class ActionSubCmd extends BaseRTPCmdImpl {

  public ActionSubCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "action";
  }

  @Override
  public String permission() {
    return "rtp.action";
  }

  @Override
  public String description() {
    return "execute a declarative scripted action";
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(senderId, parameterValues, null);

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    if (!sender.hasPermission("rtp.action") && !sender.hasPermission("rtp.*")) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
      return true;
    }

    RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Usage: /rtp action <actionId> [player=<name>]");
    return true;
  }

  public void syncActions() {
    ActionService service = RTPAPI.actions();
    if (service instanceof ActionManager manager) {
      for (String actionId : service.getActionIds()) {
        Optional<ActionDefinition> defOpt = manager.getAction(actionId);
        if (defOpt.isPresent() && !commandLookup.containsKey(actionId.toUpperCase(Locale.ROOT))) {
          ActionCommand cmd = new ActionCommand(this, defOpt.get());
          addSubCommand(cmd);
        }
      }
    }
  }
}

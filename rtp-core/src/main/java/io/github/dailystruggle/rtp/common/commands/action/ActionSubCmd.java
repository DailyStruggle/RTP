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

  private final ActionCancelCmd cancelCmd;

  public ActionSubCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    this.cancelCmd = new ActionCancelCmd(this);
    commandLookup.put("CANCEL", cancelCmd);
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
    if (nextCommand != null) return true;

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    if (sender == null || (!sender.hasPermission("rtp.action") && !sender.hasPermission("rtp.*"))) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
      return true;
    }

    msgInvalidCommand(senderId, "action");
    return true;
  }

  public void syncActions() {
    synchronized (this) {
      ActionService effectiveService = RTPAPI.actions();
      if (effectiveService == null && RTP.actionManager != null) {
        effectiveService = RTP.actionManager;
      }
      if (effectiveService instanceof ActionManager manager) {
        final ActionService finalService = effectiveService;
        // Remove any previously registered ActionCommands whose actions no longer exist
        commandLookup.entrySet().removeIf(entry -> {
          if (entry.getValue() instanceof ActionCommand ac) {
            return !finalService.getActionIds().contains(ac.definition().id().toLowerCase(Locale.ROOT));
          }
          return false;
        });

        for (String actionId : effectiveService.getActionIds()) {
          Optional<ActionDefinition> defOpt = manager.getAction(actionId);
          if (defOpt.isPresent()) {
            ActionDefinition def = defOpt.get();
            ActionCommand cmd = new ActionCommand(this, def);
            commandLookup.put(def.id().toUpperCase(Locale.ROOT), cmd);
            if (def.alias() != null && !def.alias().isBlank()) {
              commandLookup.put(def.alias().toUpperCase(Locale.ROOT), cmd);
            }
          }
        }
      }
    }
  }
}

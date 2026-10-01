package io.github.dailystruggle.rtp.common.commands.action;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionService;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.ServerAccessorCommandParameters;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Command for cancelling a pending action queue or an active session (ADR-093).
 * Supports:
 * - {@code /rtp action cancel [actionId] [player=<name>]}
 * - {@code /<action> cancel [player=<name>]} (e.g. {@code /challenge cancel})
 */
public class ActionCancelCmd extends BaseRTPCmdImpl {

  public static final String CMD_PERMISSION = "rtp.action.cancel";
  public static final String PERMISSION_OTHER = "rtp.action.cancel.other";

  @Nullable
  private final String fixedActionId;

  public ActionCancelCmd(@Nullable CommandsAPICommand parent) {
    this(parent, null);
  }

  public ActionCancelCmd(@Nullable CommandsAPICommand parent, @Nullable String fixedActionId) {
    super(parent);
    this.fixedActionId = fixedActionId;

    if (fixedActionId == null) {
      addParameter(
          "action",
          new CommandParameter(
              CMD_PERMISSION, "action ID to cancel (omit for all)", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
              ActionService service = RTPAPI.actions();
              if (service == null && RTP.actionManager != null) {
                service = RTP.actionManager;
              }
              return (service != null) ? service.getActionIds() : Collections.emptySet();
            }
          });
    }

    addParameter("player", new ServerAccessorCommandParameters().playerParameter());
  }

  @Override
  public String name() {
    return "cancel";
  }

  @Override
  public String permission() {
    return CMD_PERMISSION;
  }

  @Override
  public String description() {
    return "cancel pending action queue or active session";
  }

  @Nullable
  public String fixedActionId() {
    return fixedActionId;
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);

    // Determine target player
    List<String> playerArgs = parameterValues.get("player");
    String targetPlayerName = null;
    UUID targetPlayerId = null;

    if (playerArgs != null && !playerArgs.isEmpty()) {
      targetPlayerName = playerArgs.get(0).trim();
      if (!targetPlayerName.isEmpty()) {
        RTPPlayer target = RTP.serverAccessor.getPlayer(targetPlayerName);
        if (target != null) {
          targetPlayerId = target.uuid();
        } else {
          RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Player not found: " + targetPlayerName);
          return true;
        }
      }
    }

    if (targetPlayerId == null) {
      if (sender instanceof RTPPlayer player) {
        targetPlayerId = player.uuid();
      } else {
        RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Console must specify player=<name>");
        return true;
      }
    }

    // Permission check for cancelling others
    boolean cancellingSelf = (sender instanceof RTPPlayer player) && player.uuid().equals(targetPlayerId);
    if (!cancellingSelf) {
      if (!sender.hasPermission(PERMISSION_OTHER) && !sender.hasPermission("rtp.*")) {
        RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
        return true;
      }
    }

    // Determine target actionId
    String targetActionId = this.fixedActionId;
    if (targetActionId == null) {
      List<String> actionArgs = parameterValues.get("action");
      if (actionArgs != null && !actionArgs.isEmpty()) {
        String a = actionArgs.get(0).trim();
        if (!a.isEmpty()) {
          targetActionId = a;
        }
      }
    }

    ActionService actionService = RTPAPI.actions();
    if (actionService == null && RTP.actionManager != null) {
      actionService = RTP.actionManager;
    }

    if (actionService == null) {
      RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Action service is unavailable.");
      return true;
    }

    boolean cancelled = actionService.cancelParticipant(targetPlayerId, targetActionId);
    if (cancelled) {
      String actionLabel = (targetActionId != null) ? targetActionId : "action";
      if (cancellingSelf) {
        RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Cancelled " + actionLabel + ".");
      } else {
        RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Cancelled " + actionLabel + " for " + (targetPlayerName != null ? targetPlayerName : targetPlayerId) + ".");
      }
    } else {
      if (actionService.getSessionForParticipant(targetPlayerId).isPresent()) {
        RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Cannot cancel an ongoing match session.");
      } else {
        String actionLabel = (targetActionId != null) ? " for action: " + targetActionId : "";
        RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] No pending queue or active session found" + actionLabel + ".");
      }
    }

    return true;
  }
}

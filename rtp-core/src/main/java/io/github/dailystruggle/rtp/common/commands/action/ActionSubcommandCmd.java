package io.github.dailystruggle.rtp.common.commands.action;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionPlaceholderSanitizer;
import io.github.dailystruggle.rtp.common.action.ActionSessionImpl;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Concrete command executing a declarative subcommand defined on an action (e.g. {@code /challenge leave}).
 */
public class ActionSubcommandCmd extends BaseRTPCmdImpl {

  private final String actionId;
  private final ActionDefinition.SubcommandSpec spec;

  public ActionSubcommandCmd(
      @Nullable CommandsAPICommand parent,
      String actionId,
      ActionDefinition.SubcommandSpec spec) {
    super(parent);
    this.actionId = Objects.requireNonNull(actionId, "actionId must not be null");
    this.spec = Objects.requireNonNull(spec, "spec must not be null");
  }

  @Override
  public String name() {
    return spec.name();
  }

  @Override
  public String permission() {
    return spec.permission();
  }

  @Override
  public String description() {
    return spec.description();
  }

  public ActionDefinition.SubcommandSpec spec() {
    return spec;
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    String perm = permission();
    if (perm != null && !perm.isBlank() && !sender.hasPermission(perm) && !sender.isRtpAdmin()) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
      return true;
    }

    // Subcommands act on the caller's own match (e.g. forfeit kills [player]); refuse outside one.
    if (!(sender instanceof RTPPlayer player)) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.consoleCmdNotAllowed);
      return true;
    }
    ActionSessionImpl session = (RTP.actionManager == null) ? null
        : RTP.actionManager.getSessionForParticipant(player.uuid())
            .filter(s -> s instanceof ActionSessionImpl)
            .map(s -> (ActionSessionImpl) s)
            .filter(s -> actionId.equals(s.actionId()))
            .orElse(null);
    if (session == null) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.notInSession);
      return true;
    }

    // Session metadata first; caller identity last so queue metadata (player = first matched
    // participant, player_name = last merged sender) can never redirect the command to an opponent.
    Map<String, Object> tokens = new HashMap<>();
    if (session.context() != null && session.context().metadata() != null) {
      tokens.putAll(session.context().metadata());
    }
    tokens.put("session_id", session.sessionId().toString());
    tokens.put("action_id", actionId);
    tokens.put("sender_uuid", senderId);
    tokens.put("sender_name", player.name());
    tokens.put("player_name", player.name());
    tokens.put("player_uuid", player.uuid());
    tokens.put("player", player.uuid());

    // Execute declared actions
    for (ActionDefinition.CommandAction cmd : spec.actions()) {
      executeCommandAction(senderId, cmd, tokens);
    }

    return true;
  }

  private void executeCommandAction(
      UUID senderId, ActionDefinition.CommandAction cmd, Map<String, Object> tokens) {
    if (cmd == null) return;
    switch (cmd.type()) {
      case CONSOLE -> {
        String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
        if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
          return;
        }
        UUID serverId = new UUID(0, 0);
        RTP.serverAccessor.executeCommand(serverId, raw);
      }
      case PLAYER -> {
        String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
        if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
          return;
        }
        RTP.serverAccessor.executeCommand(senderId, raw);
      }
      case MESSAGE -> {
        String raw = ActionPlaceholderSanitizer.substitute(cmd.payload(), tokens);
        if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(raw, "target")) {
          return;
        }
        RTP.serverAccessor.sendMessage(senderId, raw);
      }
      case ACTION -> {
        String actionName = cmd.payload().trim();
        if ("DISARM".equalsIgnoreCase(actionName) && RTP.actionManager != null) {
          RTP.actionManager.getSessionForParticipant(senderId).ifPresent(s -> {
            if (s instanceof ActionSessionImpl sessionImpl) {
              sessionImpl.disarm();
            }
          });
        }
      }
      default -> RTP.log(Level.FINE, "[RTP Action] Subcommand unhandled action type: " + cmd.type());
    }
  }
}

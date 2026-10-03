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
    if (perm != null && !perm.isBlank()) {
      if (!sender.hasPermission(perm) && !sender.isRtpAdmin()) {
        RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
        return true;
      }
    }

    // Build context tokens
    Map<String, Object> tokens = new HashMap<>();
    tokens.put("action_id", actionId);
    tokens.put("sender_uuid", senderId);

    if (sender instanceof RTPPlayer player) {
      tokens.put("sender_name", player.name());
      tokens.put("player_name", player.name());
      tokens.put("player_uuid", player.uuid());
      tokens.put("player", player.uuid());
    } else {
      tokens.put("sender_name", "CONSOLE");
      tokens.put("player_name", "CONSOLE");
    }

    // If caller is in an active session for this action, augment with session metadata
    if (RTP.actionManager != null && sender instanceof RTPPlayer player) {
      RTP.actionManager.getSessionForParticipant(player.uuid()).ifPresent(session -> {
        if (session instanceof ActionSessionImpl sessionImpl) {
          tokens.put("session_id", sessionImpl.sessionId().toString());
          if (sessionImpl.context() != null) {
            tokens.putAll(sessionImpl.context().metadata());
          }
        }
      });
    }

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
        if ("DISARM".equalsIgnoreCase(actionName)) {
          if (RTP.actionManager != null) {
            RTP.actionManager.getSessionForParticipant(senderId).ifPresent(s -> {
              if (s instanceof ActionSessionImpl sessionImpl) {
                sessionImpl.disarm();
              }
            });
          }
        }
      }
      default -> RTP.log(Level.FINE, "[RTP Action] Subcommand unhandled action type: " + cmd.type());
    }
  }
}

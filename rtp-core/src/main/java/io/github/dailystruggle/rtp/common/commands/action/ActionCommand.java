package io.github.dailystruggle.rtp.common.commands.action;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.ServerAccessorCommandParameters;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Concrete {@link BaseRTPCmdImpl} executing a declarative scripted action (ADR-093).
 * Supports top-level standalone invocation (e.g. {@code /duel}) and child invocation.
 */
public class ActionCommand extends BaseRTPCmdImpl {

  private final ActionDefinition definition;

  public ActionCommand(ActionDefinition definition) {
    this(null, definition);
  }

  public ActionCommand(@Nullable CommandsAPICommand parent, ActionDefinition definition) {
    super(parent);
    this.definition = Objects.requireNonNull(definition, "definition must not be null");
    addParameter("player", new ServerAccessorCommandParameters().playerParameter());
  }

  @Override
  public String name() {
    if (definition.command().isConfigured()) {
      return definition.command().name();
    }
    return definition.id();
  }

  @Override
  public String permission() {
    if (definition.command().isConfigured() && !definition.command().permission().isBlank()) {
      return definition.command().permission();
    }
    return definition.permission();
  }

  @Override
  public String description() {
    if (definition.command().isConfigured() && !definition.command().description().isBlank()) {
      return definition.command().description();
    }
    return definition.description();
  }

  public ActionDefinition definition() {
    return definition;
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(senderId, parameterValues, null);

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    String requiredPerm = permission();
    if (requiredPerm != null && !requiredPerm.isBlank()) {
      if (!sender.hasPermission(requiredPerm) && !sender.hasPermission("rtp.*")) {
        RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
        return true;
      }
    }

    // Resolve participants
    List<UUID> participants = new ArrayList<>();
    List<List<UUID>> clusters = new ArrayList<>();

    // If caller is a player, add them by default
    if (sender instanceof RTPPlayer player) {
      participants.add(player.uuid());
      clusters.add(new ArrayList<>(List.of(player.uuid())));
    }

    // Ingest player parameter if supplied
    List<String> playerArgs = parameterValues.get("player");
    if (playerArgs != null) {
      for (String pArg : playerArgs) {
        if (pArg == null || pArg.isBlank()) continue;
        // Check for comma-separated players (cluster representation: "Alice,Bob")
        String[] split = pArg.split(",");
        List<UUID> currentCluster = new ArrayList<>();
        for (String pName : split) {
          String trimmed = pName.trim();
          if (trimmed.isEmpty()) continue;
          RTPPlayer target = RTP.serverAccessor.getPlayer(trimmed);
          if (target != null && !participants.contains(target.uuid())) {
            participants.add(target.uuid());
            currentCluster.add(target.uuid());
          }
        }
        if (!currentCluster.isEmpty()) {
          clusters.add(currentCluster);
        }
      }
    }

    if (participants.isEmpty()) {
      RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] No participants specified for action: " + definition.id());
      return true;
    }

    ActionContext context = clusters.isEmpty()
        ? ActionContext.EMPTY
        : ActionContext.ofClusters(clusters);

    io.github.dailystruggle.rtp.api.action.ActionService actionService =
        io.github.dailystruggle.rtp.api.RTPAPI.actions();
    if (actionService != null) {
      actionService.trigger(definition.id(), participants, context).whenComplete((res, ex) -> {
        if (ex != null) {
          RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Action failed: " + ex.getMessage());
        } else if (res != null && !res.success()) {
          RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Action failed: " + res.failureReason());
        }
      });
    }

    return true;
  }
}

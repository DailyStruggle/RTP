package io.github.dailystruggle.rtp.common.commands.action;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

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

    io.github.dailystruggle.rtp.api.action.ParameterSpec targetParam =
        definition.command().firstParameterOfType(io.github.dailystruggle.rtp.api.action.ParameterType.PLAYER);
    String paramPerm = (targetParam != null && targetParam.hasPermission())
        ? targetParam.permission()
        : (targetParam != null ? "" : "rtp.other");

    addParameter(
        "player",
        new io.github.dailystruggle.commandsapi.common.CommandParameter(
            paramPerm,
            "target player",
            (uuid, s) -> {
              RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
              if (sender == null) return false;
              if (paramPerm != null && !paramPerm.isBlank() && !sender.hasPermission(paramPerm) && !sender.isRtpAdmin()) {
                return false;
              }
              RTPPlayer target = RTP.serverAccessor.getPlayer(s);
              if (target == null || !target.name().equalsIgnoreCase(s)) return false;
              RTPCommandSender targetSender = RTP.serverAccessor.getSender(target.uuid());
              if (targetSender == null) return true;
              if (!(sender instanceof RTPPlayer)) return true;
              if (!targetSender.hasPermission("rtp.notme")) return true;
              return sender.hasPermission("rtp.*") || sender.hasPermission("rtp.notme.bypass") || sender.isRtpAdmin();
            }) {
          @Override
          public java.util.Set<String> values() {
            return RTP.serverAccessor != null ? RTP.serverAccessor.getOnlinePlayerNames() : java.util.Collections.emptySet();
          }
        });

    commandLookup.put("CANCEL", new ActionCancelCmd(this, definition.id()));
    registerDeclaredSubcommands();
  }

  private void registerDeclaredSubcommands() {
    for (Map.Entry<String, ActionDefinition.SubcommandSpec> entry : definition.command().subcommands().entrySet()) {
      ActionDefinition.SubcommandSpec subSpec = entry.getValue();
      if (subSpec == null || subSpec.name().isBlank()) continue;
      ActionSubcommandCmd cmd = new ActionSubcommandCmd(this, definition.id(), subSpec);
      commandLookup.put(subSpec.name().toUpperCase(), cmd);
      for (String alias : subSpec.aliases()) {
        if (alias != null && !alias.isBlank()) {
          commandLookup.put(alias.trim().toUpperCase(), cmd);
        }
      }
    }
  }

  @Override
  public String name() {
    if (parent() instanceof ActionSubCmd) {
      return definition.id();
    }
    if (definition.command().isConfigured()) {
      return definition.command().name();
    }
    return definition.id();
  }

  @Override
  public String permission() {
    if (parent() instanceof ActionSubCmd) {
      return definition.permission();
    }
    if (definition.command().isConfigured() && !definition.command().permission().isBlank()) {
      return definition.command().permission();
    }
    return definition.permission();
  }

  @Override
  public String description() {
    if (parent() instanceof ActionSubCmd) {
      return definition.description();
    }
    if (definition.command().isConfigured() && !definition.command().description().isBlank()) {
      return definition.command().description();
    }
    return definition.description();
  }

  public ActionDefinition definition() {
    return definition;
  }

  @Override
  public CompletableFuture<Boolean> onCommand(
      @NotNull UUID callerId,
      @NotNull Predicate<String> permissionCheckMethod,
      @NotNull Consumer<String> messageMethod,
      @NotNull String[] args,
      int i,
      @Nullable Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> tempParameters) {
    if (args != null && i < args.length) {
      String firstArg = args[i];
      if (firstArg != null && !firstArg.contains("=") && !firstArg.endsWith("=")) {
        String upper = firstArg.toUpperCase(java.util.Locale.ROOT);
        if (!getCommandLookup().containsKey(upper) && !"HELP".equalsIgnoreCase(firstArg)) {
          // Positional argument fallback: map unrecognized non-subcommand tokens
          // to the declared 'player' parameter (e.g. /challenge Bob -> player=Bob).
          io.github.dailystruggle.rtp.api.action.ParameterSpec targetParam =
              definition.command().firstParameterOfType(io.github.dailystruggle.rtp.api.action.ParameterType.PLAYER);
          String paramName = (targetParam != null) ? targetParam.name() : (getParameterLookup().containsKey("player") ? "player" : null);
          if (paramName != null) {
            String[] rewrittenArgs = args.clone();
            rewrittenArgs[i] = paramName + "=" + firstArg;
            return super.onCommand(callerId, permissionCheckMethod, messageMethod, rewrittenArgs, i, tempParameters);
          }
        }
      }
    }
    return super.onCommand(callerId, permissionCheckMethod, messageMethod, args, i, tempParameters);
  }

  @Override
  public List<String> onTabComplete(
      @NotNull UUID callerId,
      @NotNull Predicate<String> permissionCheckMethod,
      @NotNull String[] args) {
    List<String> results = new ArrayList<>(super.onTabComplete(callerId, permissionCheckMethod, args));
    if (args != null && args.length == 1 && !args[0].contains("=")) {
      io.github.dailystruggle.rtp.api.action.ParameterSpec targetParam =
          definition.command().firstParameterOfType(io.github.dailystruggle.rtp.api.action.ParameterType.PLAYER);
      if (targetParam != null && (!targetParam.hasPermission() || permissionCheckMethod.test(targetParam.permission()))) {
        if (RTP.serverAccessor != null) {
          String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
          for (String name : RTP.serverAccessor.getOnlinePlayerNames()) {
            if (name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
              results.add(name);
            }
          }
        }
      }
    }
    return results;
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    String requiredPerm = permission();
    if (requiredPerm != null && !requiredPerm.isBlank() && !sender.hasPermission(requiredPerm) && !sender.isRtpAdmin()) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
      return true;
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
    String targetPlayerName = null;
    UUID targetPlayerUuid = null;

    // Declarative PLAYER-parameter resolution (ADR-098). A single decision point governs "who is the
    // target": (1) the caller names a specific player only if permitted; (2) otherwise the declared
    // default applies. Whether target_* metadata is set below is the sole consequence the gate/lifecycle
    // react to (e.g. a target-based reciprocity gate skips when no target is present -> open matchmaking),
    // so config drives behavior rather than an incidental empty placeholder.
    io.github.dailystruggle.rtp.api.action.ParameterSpec targetParam =
        definition.command().firstParameterOfType(io.github.dailystruggle.rtp.api.action.ParameterType.PLAYER);
    boolean callerSuppliedTarget = hasNonBlankArg(playerArgs);

    // Permission gate: naming a specific player (as opposed to relying on the default) requires the
    // parameter's declared permission. This closes the hole where any caller could name -- and thereby
    // teleport or single out -- an arbitrary player. The default path stays unrestricted.
    if (callerSuppliedTarget && targetParam != null && targetParam.hasPermission()
        && !sender.hasPermission(targetParam.permission()) && !sender.isRtpAdmin()) {
      RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.noPerms);
      return true;
    }

    // Check whether this action's gate conditions require multiple participants.
    // When an action requires multiple participants and is invoked by a player, additional player arguments
    // represent invited targets / context metadata rather than agreed participants.
    boolean requiresMultiple = io.github.dailystruggle.rtp.common.action.GateEvaluator.requiresMultipleParticipants(definition.gates());

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
          if (target != null) {
            if (targetPlayerName == null) {
              targetPlayerName = target.name();
              targetPlayerUuid = target.uuid();
            }
            if ((!requiresMultiple || !(sender instanceof RTPPlayer)) && !participants.contains(target.uuid())) {
              participants.add(target.uuid());
              currentCluster.add(target.uuid());
            }
          } else if (targetPlayerName == null) {
            targetPlayerName = trimmed;
          }
        }
        if (!currentCluster.isEmpty()) {
          clusters.add(currentCluster);
        }
      }
    }

    // No explicit target supplied: apply the declared PLAYER default (ADR-098). 'self' resolves the
    // target to the caller (single-player actions teleport the caller); 'any' leaves the target unset
    // (open matchmaking -- a target-based reciprocity gate is skipped); a literal name resolves that
    // player. Absent a declared default the legacy behavior (caller-only participant) is preserved.
    if (!callerSuppliedTarget && targetParam != null
        && targetParam.type() == io.github.dailystruggle.rtp.api.action.ParameterType.PLAYER
        && targetParam.hasDefault()) {
      String defaultValue = targetParam.defaultValue();
      if ("self".equalsIgnoreCase(defaultValue)) {
        if (sender instanceof RTPPlayer selfPlayer) {
          targetPlayerName = selfPlayer.name();
          targetPlayerUuid = selfPlayer.uuid();
        }
      } else if ("any".equalsIgnoreCase(defaultValue)) {
        // Open matchmaking: intentionally leave target_* unset.
        targetPlayerName = null;
        targetPlayerUuid = null;
      } else {
        RTPPlayer literalTarget = RTP.serverAccessor.getPlayer(defaultValue);
        if (literalTarget != null) {
          targetPlayerName = literalTarget.name();
          targetPlayerUuid = literalTarget.uuid();
          if (!participants.contains(literalTarget.uuid())) {
            participants.add(literalTarget.uuid());
          }
        }
      }
    }

    if (participants.isEmpty()) {
      RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] No participants specified for action: " + definition.id());
      return true;
    }

    Map<String, Object> metadata = new java.util.HashMap<>();
    if (!clusters.isEmpty()) {
      metadata.put("clusters", clusters);
    }
    if (sender instanceof RTPPlayer player) {
      metadata.put("sender_name", player.name());
      metadata.put("sender_uuid", player.uuid());
      metadata.put("player_name", player.name());
      metadata.put("player_uuid", player.uuid());
    }
    if (targetPlayerName != null) {
      metadata.put("target_name", targetPlayerName);
      metadata.put("target", targetPlayerName);
      if (targetPlayerUuid != null) {
        metadata.put("target_uuid", targetPlayerUuid);
      }
    }

    ActionContext context = new ActionContext(metadata);
    RTP.log(
        Level.FINE,
        "[action] invoking action '"
            + definition.id()
            + "' with participants="
            + participants
            + ", sender="
            + senderId
            + ", metadata="
            + metadata);

    io.github.dailystruggle.rtp.api.action.ActionService actionService =
        io.github.dailystruggle.rtp.api.RTPAPI.actions();
    if (actionService != null) {
      boolean hasUnqueuedPerm = sender.hasPermission("rtp.unqueued") || sender.isRtpAdmin();
      boolean isCached = false;
      if (actionService instanceof ActionManager am) {
        isCached = am.hasCachedPlacement(definition.id(), participants.size());
      }

      actionService.trigger(definition.id(), participants, context).whenComplete((res, ex) -> {
        if (ex != null) {
          RTP.log(Level.WARNING, "[action] execution threw exception for '" + definition.id() + "'", ex);
          if (RTP.getInstance() != null) {
            io.github.dailystruggle.rtp.common.playerData.TeleportData td =
                RTP.getInstance().latestTeleportData.computeIfAbsent(senderId, k -> new io.github.dailystruggle.rtp.common.playerData.TeleportData());
            td.attempts = (definition.placement() != null) ? definition.placement().retries() : 1;
          }
          RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.unsafe);
        } else if (res != null && !res.success()) {
          String reason = res.failureReason();
          RTP.log(Level.WARNING, "[action] execution failed for '" + definition.id() + "': " + reason);
          if (reason != null && (reason.contains("INSUFFICIENT_SAFE_SLOTS")
              || reason.contains("safe slots")
              || reason.contains("Spatial subspace placement failed")
              || reason.contains("NO_ANCHOR"))) {
            if (RTP.getInstance() != null) {
              io.github.dailystruggle.rtp.common.playerData.TeleportData td =
                  RTP.getInstance().latestTeleportData.computeIfAbsent(senderId, k -> new io.github.dailystruggle.rtp.common.playerData.TeleportData());
              td.attempts = (definition.placement() != null) ? definition.placement().retries() : 1;
            }
            RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.unsafe);
          } else if (reason != null && reason.contains("already in an active session")) {
            String pName = "";
            int idx = reason.indexOf(':');
            if (idx >= 0 && idx < reason.length() - 1) {
              pName = reason.substring(idx + 1).trim();
            }
            if (pName.isBlank()) {
              pName = (sender instanceof RTPPlayer p) ? p.name() : "You";
            }
            // Send localized alreadyInSession message
            Object msgObj = (RTP.configs != null) ? RTP.configs.getConfigValue(PlayerMessages.alreadyInSession, "") : null;
            String msg = (msgObj != null) ? msgObj.toString() : "";
            if (msg.isBlank()) {
              msg = "&c[P0] [player] is already in an active match session.";
            }
            msg = msg.replace("[player]", pName);
            RTP.serverAccessor.sendMessage(senderId, senderId, msg);
          } else {
            RTP.serverAccessor.sendMessage(senderId, senderId, "[RTP] Action failed: " + reason);
          }
        } else if (res != null && res.success()) {
          RTP.log(Level.FINE, "[action] session successfully started: " + res.sessionId());
        }
      });

      // If the action was enqueued into a wait queue (e.g. waiting for participants or gate conditions),
      // send the queueUpdate message so the user knows they are waiting in queue.
      boolean isWaitQueued = (actionService instanceof ActionManager am) && am.isQueued(definition.id(), senderId);
      RTP.log(
          Level.FINE,
          "[action] status after trigger for '"
              + definition.id()
              + "': isWaitQueued="
              + isWaitQueued
              + ", isCached="
              + isCached);
      if (isWaitQueued) {
        RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.queueUpdate);
      } else if (!isCached && definition.placement().enabled()) {
        if (hasUnqueuedPerm) {
          RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.chunkLoading);
        } else {
          RTP.serverAccessor.sendMessage(senderId, senderId, PlayerMessages.queueUpdate);
        }
      }
    }

    return true;
  }

  private static boolean hasNonBlankArg(List<String> args) {
    if (args == null) return false;
    for (String a : args) {
      if (a != null && !a.isBlank()) return true;
    }
    return false;
  }
}

package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;

/**
 * Platform-neutral click handling: submits the teleport intent and reports the result.
 *
 * <p>The renderer's click handler calls {@link #submit(UUID, RtpTarget)} with the
 * resolved target; everything from here is engine work. The teleport goes through
 * {@link RTPAPI#teleport}, which enforces permission / cooldown / cost / safety
 * off-thread and always completes with a result (never a silent no-op, per
 * REQ-RTP-S-004). Player messaging is routed through {@code RTP.serverAccessor} so it
 * lands on the right thread on every platform, and the failure path is always
 * surfaced to the player rather than swallowed.
 */
public final class MenuActions {

  private MenuActions() {}

  /**
   * Checks whether {@code target} is an in-menu navigation or pagination action
   * that transitions between GUI screens rather than executing a command or teleport.
   *
   * @param target destination/action target
   * @return {@code true} if clicking this target navigates between menu pages
   */
  public static boolean isMenuNavigation(RtpTarget target) {
    if (target == null || target.kind() != RtpTarget.Kind.ACTION) {
      return false;
    }
    String name = target.name();
    if (name == null || name.isBlank()) {
      return false;
    }
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    return lower.startsWith("menu:") || "action:operator:reload".equals(lower);
  }

  /**
   * Submits a teleport to {@code target} for {@code playerId} and reports the outcome
   * using the configured messages.
   *
   * @param playerId the player who clicked
   * @param target the destination they selected; if {@code null}, nothing happens
   */
  public static void submit(UUID playerId, RtpTarget target) {
    if (playerId == null || target == null) {
      return;
    }
    GuiMenuConfig config = GuiMenuConfig.INSTANCE;

    // Handle UI navigation actions (sub-menus, pagination)
    if (target.kind() == RtpTarget.Kind.ACTION) {
      String action = target.name();
      if (action == null) return;
      MenuRenderer renderer = GuiRenderers.resolve(config.menuStyle());
      if (renderer == null || !renderer.isAvailable()) return;

      if ("menu:main".equalsIgnoreCase(action) || action.toLowerCase(java.util.Locale.ROOT).startsWith("menu:main:")) {
        int page = 0;
        if (action.toLowerCase(java.util.Locale.ROOT).startsWith("menu:main:")) {
          try {
            page = Integer.parseInt(action.substring("menu:main:".length()));
          } catch (NumberFormatException ignored) {
          }
        }
        renderer.open(playerId, MenuModel.build(playerId, config, page));
        return;
      }
      if ("menu:operator".equalsIgnoreCase(action)) {
        String perm = config.permissionOperatorTools();
        if (hasPerm(playerId, perm)) {
          renderer.open(playerId, MenuModel.buildOperatorMenu(playerId, config));
        } else {
          message(playerId, config.textUnavailable());
        }
        return;
      }
      if (action.toLowerCase(java.util.Locale.ROOT).startsWith("action:operator:")) {
        handleOperatorAction(playerId, action, config, renderer);
        return;
      }
      if (action.toLowerCase(java.util.Locale.ROOT).startsWith("menu:biomes:")) {
        int page = 0;
        try {
          page = Integer.parseInt(action.substring("menu:biomes:".length()));
        } catch (NumberFormatException ignored) {
        }
        renderer.open(playerId, MenuModel.buildBiomeMenu(playerId, config, page));
        return;
      }
      if (action.toLowerCase(java.util.Locale.ROOT).startsWith("menu:actions:")) {
        int page = 0;
        try {
          page = Integer.parseInt(action.substring("menu:actions:".length()));
        } catch (NumberFormatException ignored) {
        }
        renderer.open(playerId, MenuModel.buildActionsMenu(playerId, config, page));
        return;
      }
      if (action.toLowerCase(java.util.Locale.ROOT).startsWith("action:trigger:")) {
        String actionId = action.substring("action:trigger:".length()).trim();
        if (RTPAPI.hasActions()) {
          var actionService = RTPAPI.actions();
          if (actionService != null) {
            // Re-check at click time: the menu may predate a permission revoke or action reload.
            var def = actionService.getAction(actionId).orElse(null);
            if (!MenuModel.canUseAction(playerId, def)) {
              message(playerId, config.textUnavailable());
              return;
            }
            message(playerId, config.textSearching());
            actionService.trigger(
                actionId,
                java.util.List.of(playerId),
                io.github.dailystruggle.rtp.api.action.ActionContext.EMPTY)
                .whenComplete((sessionResult, error) -> {
                  if (error != null) {
                    message(playerId, config.textFailurePrefix() + error.getMessage());
                  } else if (sessionResult != null && !sessionResult.success()) {
                    message(playerId, config.textFailurePrefix() + sessionResult.failureReason());
                  } else {
                    message(playerId, config.textSuccess());
                  }
                });
          }
        }
        return;
      }
      return;
    }

    message(playerId, config.textSearching());

    RTPAPI.teleport(playerId, target)
        .whenComplete(
            (result, error) -> {
              if (error != null) {
                message(playerId, config.textFailurePrefix() + error.getMessage());
              } else if (result != null && result.isQueued()) {
                // Cross-server enrolment accepted: not a failure - the player
                // will be transferred and teleported on the destination backend.
                message(playerId, config.textQueued());
              } else if (result == null || !result.isSuccess()) {
                String reason = (result == null) ? "unknown" : reason(result);
                message(playerId, config.textFailurePrefix() + reason);
              } else {
                message(playerId, config.textSuccess());
              }
            });
  }

  private static String reason(RTPResult result) {
    String msg = result.message();
    return (msg == null || msg.isEmpty()) ? "unknown" : msg;
  }

  /** Sends a message on the correct platform thread; the callback may be off-thread. */
  private static void message(UUID playerId, String text) {
    if (RTP.serverAccessor == null || RTP.serverAccessor.getPlayer(playerId) == null) {
      return;
    }
    RTP.scheduler.runTask(() -> {
      if (RTP.serverAccessor.getPlayer(playerId) != null) {
        RTP.serverAccessor.sendMessage(playerId, text);
      }
    });
  }

  private static void handleOperatorAction(
      UUID playerId, String action, GuiMenuConfig config, MenuRenderer renderer) {
    String lower = action.toLowerCase(java.util.Locale.ROOT);
    switch (lower) {
      case "action:operator:setup" -> {
        if (!hasPerm(playerId, "rtp.admin.setup")) {
          message(playerId, config.textUnavailable());
          return;
        }
        dispatchCommand(playerId, "rtp admin setup");
      }
      case "action:operator:import" -> {
        if (!hasPerm(playerId, "rtp.config")) {
          message(playerId, config.textUnavailable());
          return;
        }
        dispatchCommand(playerId, "rtp config import");
      }
      case "action:operator:config" -> {
        if (!hasPerm(playerId, "rtp.config")) {
          message(playerId, config.textUnavailable());
          return;
        }
        dispatchCommand(playerId, "rtp menu config");
      }
      case "action:operator:visualizations" -> {
        if (!hasPerm(playerId, "rtp.see")) {
          message(playerId, config.textUnavailable());
          return;
        }
        dispatchCommand(playerId, "rtp visualization");
      }
      case "action:operator:status" -> {
        if (!hasPerm(playerId, "rtp.info")) {
          message(playerId, config.textUnavailable());
          return;
        }
        dispatchCommand(playerId, "rtp menu info");
      }
      case "action:operator:adminbook" -> {
        if (!hasPerm(playerId, "rtp.menu.admin")) {
          message(playerId, config.textUnavailable());
          return;
        }
        dispatchCommand(playerId, "rtp menu admin");
      }
      case "action:operator:reload" -> {
        if (!hasPerm(playerId, "rtp.reload")) {
          message(playerId, config.textUnavailable());
          return;
        }
        dispatchCommand(playerId, "rtp reload");
        renderer.open(playerId, MenuModel.buildOperatorMenu(playerId, config));
      }
      default -> message(playerId, config.textUnavailable());
    }
  }

  private static boolean hasPerm(UUID playerId, String perm) {
    return (perm != null && !perm.isBlank() && RTPAPI.checkPermission(playerId, perm))
        || RTPAPI.checkPermission(playerId, "rtp.admin");
  }

  private static void dispatchCommand(UUID playerId, String commandLine) {
    if (RTP.serverAccessor == null || RTP.serverAccessor.getPlayer(playerId) == null) {
      return;
    }
    RTP.scheduler.runTask(() -> {
      if (RTP.serverAccessor != null && RTP.serverAccessor.getPlayer(playerId) != null) {
        RTP.serverAccessor.executeCommand(playerId, commandLine);
      }
    });
  }
}

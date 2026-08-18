package io.github.dailystruggle.rtp.common.commands.gui;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.hooks.RootActionRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The {@code /rtp gui} subcommand: an explicit opener for menu actions bound to /rtp (ADR-056).
 */
public final class GuiCmd extends BaseRTPCmdImpl {

  /** @param parent the {@code /rtp} root command this attaches under */
  public GuiCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "gui";
  }

  @Override
  public String permission() {
    return "rtp.gui";
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) {
      return nextCommand.onCommand(senderId, parameterValues, null);
    }
    if (openBoundMenu(senderId)) {
      return true;
    }
    // No menu was opened (no GUI addon bound an action, it declined, or the caller is
    // not a resolvable player). Defer to RTP's classic teleport via the root command so
    // /rtp gui never silently no-ops.
    CommandsAPICommand root = parent();
    if (root != null) {
      return root.onCommand(senderId, parameterValues, null);
    }
    return true;
  }

  /**
   * Invokes the bound bare-{@code /rtp} root action for {@code senderId}, returning
   * whether it handled (opened a menu). Mirrors the root command's own root-action
   * dispatch; failures and the unbound case are treated as not-handled.
   */
  private boolean openBoundMenu(UUID senderId) {
    if (senderId == null || senderId.equals(new UUID(0, 0))) {
      return false; // console / unidentifiable: no GUI
    }
    RootActionRegistry.Action action;
    try {
      RootActionRegistry registry = RTPAPI.hooks().rootAction();
      action = (registry == null) ? null : registry.current();
    } catch (IllegalStateException coreNotLoaded) {
      return false;
    }
    if (action == null) {
      return false;
    }
    Consumer<String> feedback = m -> RTP.serverAccessor.sendMessage(senderId, m);
    try {
      return action.run(senderId, feedback);
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] /rtp gui bound action threw; unbinding it and deferring to classic teleport", t);
      // A bound action that throws is almost always permanently broken on this runtime
      // (e.g. a GUI menu whose backend cannot load on a too-old modded server). Unbind it
      // so this and every later bare /rtp self-heals to the classic teleport instead of
      // repeatedly paying the same exception. The clear is guarded so it can never escalate.
      try {
        RootActionRegistry registry = RTPAPI.hooks().rootAction();
        if (registry != null) {
          registry.clear();
          RTP.log(Level.INFO,
              "[RTP] unbound failing /rtp root action; bare /rtp reset to the classic teleport");
        }
      } catch (Throwable clearError) {
        RTP.log(Level.FINE, "[RTP] could not unbind the failing /rtp root action", clearError);
      }
      return false;
    }
  }
}

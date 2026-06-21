package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Top-level {@code /rtp clear} verb. Acts purely as a grouping parent for the
 * concrete clear actions:
 *
 * <ul>
 *   <li>{@code /rtp clear cache} - wipe the L1/L2/L3 location caches for every
 *       region (see {@link ClearCacheCmd}).</li>
 *   <li>{@code /rtp clear cooldown} (alias {@code data}) - drop a player's latest
 *       teleport data, lifting their teleport cooldown (see
 *       {@link ClearCooldownCmd}).</li>
 * </ul>
 *
 * <p>Bare {@code /rtp clear} performs no destructive action: with no child
 * selected the command framework emits its built-in help listing of the
 * children, so the parent's own {@link #onCommand} only sends a short usage hint.
 *
 * <p>Permission: {@code rtp.admin}.
 */
public class ClearCmd extends BaseRTPCmdImpl {

  /** Permission key for {@code /rtp clear} and its children. */
  public static final String PERMISSION = "rtp.admin";

  /**
   * Constructs the clear parent verb and registers its children.
   *
   * @param parent the parent command tree (the {@code /rtp} root), or {@code null}
   */
  public ClearCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addSubCommand(new ClearCacheSubCmd(this));
    ClearCooldownCmd cooldown = new ClearCooldownCmd(this);
    addSubCommand(cooldown);
    addSubCommand(new ClearQueueCmd(this));
    addSubCommand(new ClearLimitCmd(this));
    addSubCommand(new ClearInvulnCmd(this));
    // Register the "data" spelling as an alias of the cooldown child, mirroring
    // the VersionCmd.ALIAS lookup pattern used by the /rtp root.
    getCommandLookup().put(ClearCooldownCmd.ALIAS.toUpperCase(), cooldown);
  }

  @Override
  public String name() {
    return "clear";
  }

  @Override
  public String permission() {
    return PERMISSION;
  }

  @Override
  public String description() {
    return "clear RTP state: location caches or player teleport cooldowns";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    String msg = "RTP: usage - /rtp clear <cache|cooldown|queue|limit|invuln>";
    if (callerId != null && RTP.serverAccessor != null) {
      try {
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
      } catch (RuntimeException ignored) {
        // serverAccessor failures (headless / test scaffolds) are not fatal.
      }
    }
    return true;
  }
}

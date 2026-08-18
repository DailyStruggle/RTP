package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear limit} subcommand to clear player rolling usage-cap state
 * ({@code lockAfterUses} / {@code lockAfterResetSeconds}).
 *
 * <p>Permission: {@code rtp.admin}.</p>
 */
public class ClearLimitCmd extends PlayerTargetedClearCmd {

  /**
   * Constructs the limit child.
   *
   * @param parent the {@code /rtp clear} parent command, or {@code null}
   */
  public ClearLimitCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "limit";
  }

  @Override
  public String description() {
    return "clear usage-cap (lockAfter) lockouts for player(s) or all from console";
  }

  @Override
  protected String label() {
    return "usage-cap lockout";
  }

  @Override
  protected int clearAll() {
    RTP rtp = RTP.getInstance();
    if (rtp != null) rtp.usageCaps.clear();
    return 0;
  }

  @Override
  protected boolean clearOne(UUID uuid) {
    if (uuid == null) return false;
    RTP rtp = RTP.getInstance();
    if (rtp == null) return false;

    boolean had = rtp.usageCaps.snapshot(uuid) != null;
    // Route through the limit store so the persisted window is reset too.
    rtp.teleportLimitStore.reset(uuid);
    return had;
  }
}

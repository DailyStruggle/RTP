package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear limit} child verb. Clears a player's rolling usage-cap state
 * backing the BetterRTP {@code LockAfter} parity feature
 * ({@code lockAfterUses} / {@code lockAfterResetSeconds}): the in-memory window
 * in {@link RTP#usageCaps} and, via {@link RTP#teleportLimitStore}, the
 * best-effort persisted window. This lifts a {@code lockAfterUses} lockout the
 * same way {@code /rtp clear cooldown} lifts a cooldown - the two are separate
 * teleport gates.
 *
 * <p>Targeting (self / listed players / console-global) is inherited from
 * {@link PlayerTargetedClearCmd}.
 *
 * <p>Permission: {@code rtp.admin}.
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

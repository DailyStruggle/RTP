package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear invuln} child verb. Drops a player's post-teleport
 * invulnerability grace marker ({@link RTP#invulnerablePlayers}), immediately
 * ending the temporary damage immunity granted after an RTP teleport. Useful
 * when a player is stuck invulnerable (or is exploiting the grace window).
 *
 * <p>Targeting (self / listed players / console-global) is inherited from
 * {@link PlayerTargetedClearCmd}.
 *
 * <p>Permission: {@code rtp.admin}.
 */
public class ClearInvulnCmd extends PlayerTargetedClearCmd {

  /**
   * Constructs the invuln child.
   *
   * @param parent the {@code /rtp clear} parent command, or {@code null}
   */
  public ClearInvulnCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "invuln";
  }

  @Override
  public String description() {
    return "clear post-teleport invulnerability for player(s) or all from console";
  }

  @Override
  protected String label() {
    return "post-teleport invulnerability";
  }

  @Override
  protected int clearAll() {
    RTP rtp = RTP.getInstance();
    if (rtp == null) return 0;
    int size = rtp.invulnerablePlayers.size();
    rtp.invulnerablePlayers.clear();
    return size;
  }

  @Override
  protected boolean clearOne(UUID uuid) {
    if (uuid == null) return false;
    RTP rtp = RTP.getInstance();
    if (rtp == null) return false;
    return rtp.invulnerablePlayers.remove(uuid) != null;
  }
}

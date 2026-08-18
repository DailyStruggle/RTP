package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear invuln} child verb. Drops post-teleport invulnerability grace markers
 * ({@link RTP#invulnerablePlayers}). Permission: {@code rtp.admin}.
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

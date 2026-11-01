package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;

import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear invuln} child verb. Drops post-teleport invulnerability grace markers
 * ({@link TeleportSessions#invulnerablePlayers}). Permission: {@code rtp.admin}.
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
    TeleportSessions sessions = TeleportSessions.current();
    if (sessions == null) return 0;
    int size = TeleportSessions.current().invulnerablePlayers.size();
    TeleportSessions.current().invulnerablePlayers.clear();
    return size;
  }

  @Override
  protected boolean clearOne(UUID uuid) {
    if (uuid == null) return false;
    TeleportSessions sessions = TeleportSessions.current();
    if (sessions == null) return false;
    return TeleportSessions.current().invulnerablePlayers.remove(uuid) != null;
  }
}

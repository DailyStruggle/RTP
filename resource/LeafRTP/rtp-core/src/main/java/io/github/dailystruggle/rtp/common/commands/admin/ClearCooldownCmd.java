package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;

import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear cooldown} child verb (alias {@code data}).
 * Drops latest teleport data to reset cooldown in memory and writes zeroed timestamp to DB.
 * Permission: {@code rtp.admin}.
 */
public class ClearCooldownCmd extends PlayerTargetedClearCmd {

  /** Alternate spelling registered as an alias of this verb by {@link ClearCmd}. */
  public static final String ALIAS = "data";

  /**
   * Constructs the cooldown child.
   *
   * @param parent the {@code /rtp clear} parent command, or {@code null}
   */
  public ClearCooldownCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "cooldown";
  }

  @Override
  public String description() {
    return "clear teleport cooldowns (self, listed players, or all from console)";
  }

  @Override
  protected String label() {
    return "teleport cooldown";
  }

  @Override
  protected int clearAll() {
    Set<UUID> all = new LinkedHashSet<>();
    TeleportSessions sessions = TeleportSessions.current();
    if (sessions != null) {
      all.addAll(TeleportSessions.current().latestTeleportData.keySet());
      all.addAll(TeleportSessions.current().priorTeleportData.keySet());
      all.addAll(TeleportSessions.current().regionTeleportTimes.keySet());
    }
    int cleared = 0;
    for (UUID id : all) {
      if (clearOne(id)) cleared++;
    }
    return cleared;
  }

  @Override
  protected boolean clearOne(UUID uuid) {
    if (uuid == null) return false;
    TeleportSessions sessions = TeleportSessions.current();
    if (sessions == null) return false;

    TeleportData latest = TeleportSessions.current().latestTeleportData.remove(uuid);
    TeleportData prior = TeleportSessions.current().priorTeleportData.remove(uuid);
    Map<String, Long> regions = TeleportSessions.current().regionTeleportTimes.remove(uuid);
    TeleportData existing = (latest != null) ? latest : prior;

    if (existing != null && ActiveDatabase.accessor() != null) {
      try {
        // Re-persist with a zeroed timestamp: the cooldown gate compares
        // now - time against the player's cooldown, so a zero time reads as
        // "no recent teleport" after a restart.
        existing.time = 0L;
        existing.completed = true;
        ActiveDatabase.accessor().cacheValue(existing);
      } catch (RuntimeException ex) {
        // Durable clear is best-effort: the in-memory removal above already
        // lifts the live-session cooldown. Headless/test scaffolds without a
        // fully wired server accessor must not break the command.
        RTPRuntime.log(
            Level.WARNING,
            "[RTP] clear cooldown: failed to persist cleared cooldown for " + uuid + ": " + ex.getMessage());
      }
    }
    return existing != null || (regions != null && !regions.isEmpty());
  }
}

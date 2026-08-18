package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;

import java.util.LinkedHashSet;
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
    RTP rtp = RTP.getInstance();
    if (rtp != null) {
      all.addAll(rtp.latestTeleportData.keySet());
      all.addAll(rtp.priorTeleportData.keySet());
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
    RTP rtp = RTP.getInstance();
    if (rtp == null) return false;

    TeleportData latest = rtp.latestTeleportData.remove(uuid);
    TeleportData prior = rtp.priorTeleportData.remove(uuid);
    TeleportData existing = (latest != null) ? latest : prior;

    if (existing != null && rtp.databaseAccessor != null) {
      try {
        // Re-persist with a zeroed timestamp: the cooldown gate compares
        // now - time against the player's cooldown, so a zero time reads as
        // "no recent teleport" after a restart.
        existing.time = 0L;
        existing.completed = true;
        rtp.databaseAccessor.cacheValue(existing);
      } catch (RuntimeException ex) {
        // Durable clear is best-effort: the in-memory removal above already
        // lifts the live-session cooldown. Headless/test scaffolds without a
        // fully wired server accessor must not break the command.
        RTP.log(
            Level.WARNING,
            "[RTP] clear cooldown: failed to persist cleared cooldown for " + uuid + ": " + ex.getMessage());
      }
    }
    return existing != null;
  }
}

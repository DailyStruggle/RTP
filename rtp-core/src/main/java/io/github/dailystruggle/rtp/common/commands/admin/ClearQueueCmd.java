package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp clear queue} child verb. Un-sticks a player who is wedged in the
 * teleport pipeline: it drops them from every region's teleport waitlist
 * ({@link RegionQueueManager#playerQueue}) and personal coordinate bucket
 * ({@code perPlayerLocationQueue}), clears the global awaiting-teleport and
 * mid-attempt markers ({@link RTP#queuedPlayers}, {@link RTP#processingPlayers},
 * {@link RTP#invulnerablePlayers}), and cancels any in-flight
 * {@link TeleportPipelineTask} through the normal cancel path so the reserved
 * chunk ticket is released rather than leaked (S-004 / S-002).
 *
 * <p>This intentionally does <em>not</em> touch the player's cooldown
 * ({@code latestTeleportData} timestamp); use {@code /rtp clear cooldown} for
 * that. The two are separate gates.
 *
 * <p>Targeting (self / listed players / console-global) is inherited from
 * {@link PlayerTargetedClearCmd}.
 *
 * <p>Permission: {@code rtp.admin}.
 */
public class ClearQueueCmd extends PlayerTargetedClearCmd {

  /**
   * Constructs the queue child.
   *
   * @param parent the {@code /rtp clear} parent command, or {@code null}
   */
  public ClearQueueCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "queue";
  }

  @Override
  public String description() {
    return "remove player(s) from the teleport queue (fixes stuck teleports)";
  }

  @Override
  protected String label() {
    return "teleport queue";
  }

  @Override
  protected int clearAll() {
    RTP rtp = RTP.getInstance();
    if (rtp == null) return 0;

    // Snapshot every player currently represented in any queue structure so we
    // can report a count and cancel each in-flight attempt individually.
    java.util.Set<UUID> all = new java.util.LinkedHashSet<>();
    all.addAll(rtp.queuedPlayers);
    all.addAll(rtp.processingPlayers);
    all.addAll(rtp.invulnerablePlayers.keySet());
    for (Region region : allRegions()) {
      RegionQueueManager qm = region.queueManager;
      if (qm == null) continue;
      all.addAll(qm.playerQueue);
      all.addAll(qm.perPlayerLocationQueue.keySet());
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

    boolean found = false;

    // 1. Drop from every region's waitlist + personal coordinate bucket,
    //    releasing any reserved chunk tickets so they are not leaked (S-002).
    for (Region region : allRegions()) {
      RegionQueueManager qm = region.queueManager;
      if (qm == null) continue;
      if (qm.playerQueue.remove(uuid)) found = true;
      ConcurrentLinkedQueue<RTPLocation> personal = qm.perPlayerLocationQueue.remove(uuid);
      if (personal != null) {
        found = true;
        RTPLocation loc;
        while ((loc = personal.poll()) != null) {
          if (loc.reservation() != null) {
            try {
              loc.reservation().close();
            } catch (RuntimeException ignored) {
              // ticket close is best-effort; never abort the un-stick.
            }
          }
        }
      }
    }

    // 2. Clear the global teleport-intent / mid-attempt markers.
    if (rtp.queuedPlayers.remove(uuid)) found = true;
    if (rtp.processingPlayers.remove(uuid)) found = true;
    if (rtp.invulnerablePlayers.remove(uuid) != null) found = true;

    // 3. Cancel any in-flight teleport task through the normal cancel path so
    //    the failure is not silently swallowed (S-004) and reservations release.
    TeleportData data = rtp.latestTeleportData.get(uuid);
    if (data != null && !data.completed && data.nextTask instanceof TeleportPipelineTask) {
      found = true;
      try {
        ((TeleportPipelineTask) data.nextTask).setCancelled(true);
        RTP.scheduler.runTask(new RTPTeleportCancel(uuid));
      } catch (RuntimeException ex) {
        RTP.log(
            Level.WARNING,
            "[RTP] clear queue: failed to cancel in-flight teleport for " + uuid + ": " + ex.getMessage());
      }
    }

    return found;
  }

  /** All live regions (permanent + per-world temp regions) currently loaded. */
  private static List<Region> allRegions() {
    List<Region> regions = new ArrayList<>();
    if (RTP.selectionAPI != null) {
      regions.addAll(RTP.selectionAPI.permRegionLookup.values());
      regions.addAll(RTP.selectionAPI.tempRegions.values());
    }
    return regions;
  }
}

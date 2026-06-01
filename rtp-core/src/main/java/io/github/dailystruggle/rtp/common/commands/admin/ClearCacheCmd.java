package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.selection.region.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Top-level {@code /rtp clearcache} verb. Clears the L1 (kept), L2 (unkept),
 * and L3 (backlog) caches for every region across the board, releasing any
 * held chunk reservations and dropping the associated persisted rows.
 *
 * <p>Permission: {@code rtp.admin}.
 */
public class ClearCacheCmd extends BaseRTPCmdImpl {

  /** Permission key for {@code /rtp clearcache}. */
  public static final String PERMISSION = "rtp.admin";

  public ClearCacheCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "clearcache";
  }

  @Override
  public String permission() {
    return PERMISSION;
  }

  @Override
  public String description() {
    return "clear the L1/L2/L3 location caches for every region";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    final List<Region> regions = new ArrayList<>();
    if (RTP.selectionAPI != null) {
      regions.addAll(RTP.selectionAPI.permRegionLookup.values());
      regions.addAll(RTP.selectionAPI.tempRegions.values());
    }

    int cleared = 0;
    for (Region region : regions) {
      if (region == null || region.queueManager == null) continue;
      region.queueManager.clearCaches();
      cleared++;
    }

    String msg = "cleared L1/L2/L3 caches for " + cleared + " region(s)";
    RTP.log(Level.INFO, "[clearcache] " + msg + " (by " + callerId + ")");
    if (callerId != null && RTP.serverAccessor != null) {
      try {
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, "RTP: " + msg);
      } catch (RuntimeException ignored) {
        // serverAccessor failures (headless / test scaffolds) are not fatal.
      }
    }
    return true;
  }
}

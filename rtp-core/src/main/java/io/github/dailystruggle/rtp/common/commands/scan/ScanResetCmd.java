package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTasks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

public class ScanResetCmd extends ScanSubCmd {
  public ScanResetCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "reset";
  }

  @Override
  public String description() {
    return "clear region memory shape data without starting a new scan";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<Region> regions = getRegions(callerId, parameterValues.get("region"));
    for (Region region : regions) {
      if (ScanTask.isTeardownPending(region.name)) {
        if (ActiveConfigs.get() == null) continue;
        String msg = String.valueOf(ActiveConfigs.get().getConfigValue(CommandMessages.scanRunning, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[scan_regions]", region.name);
        msg = msg.replace("[scan_region]", region.name);
        msg = msg.replace("[region]", region.name);
        RTPRuntime.serverAccessor().announce(msg, "rtp.scan", "SCAN");
        continue;
      }

      // cancel any running scan task for this region first
      ScanTask scanTask = ScanTasks.current().get(region.name);
      if (scanTask != null) {
        ScanTasks.current().remove(region.name);
        scanTask.setCancelled(true, err -> {
          if (err != null) {
            RTPRuntime.log(Level.WARNING, "[ScanResetCmd] cancel teardown failed region=" + region.name, err);
          }
          resetRegion(callerId, region);
        });
        continue;
      }

      resetRegion(callerId, region);
    }
    return true;
  }

  private void resetRegion(UUID callerId, Region region) {
    Shape<?> shapeObj = region.getShape();
    if (!(shapeObj instanceof MemoryShape)) {
      if (ActiveConfigs.get() == null) return;
      String msg = String.valueOf(ActiveConfigs.get().getConfigValue(PlayerMessages.badArg, ""));
      if (msg == null || msg.isEmpty()) return;
      msg = msg.replace("[arg]", "region=" + region.name);
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, callerId, msg);
      return;
    }

    MemoryShape<?> shape = (MemoryShape<?>) shapeObj;
    shape.clear();
    shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
    ScanTask.delete(region.name);

    // restore spatialResolution from config so the region is ready for a fresh scan or normal use
    long spatialRes = region.getSettings().spatialResolution();
    MultiConfigParser<RegionKeys> multiConfigParser =
        (MultiConfigParser<RegionKeys>) ActiveConfigs.get().getParser(RegionKeys.class);
    if (multiConfigParser != null) {
      ConfigParser<RegionKeys> regionConfig = multiConfigParser.getParser(region.name);
      if (regionConfig != null) {
        spatialRes = io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader.resolveSpatialResolution(
            regionConfig.getData(RegionKeys.spatialResolution), shape);
      }
    }
    shape.setSpatialResolution(spatialRes);

    if (ActiveConfigs.get() == null) return;
    String msg = String.valueOf(ActiveConfigs.get().getConfigValue(CommandMessages.scanReset, ""));
    if (msg == null || msg.isEmpty()) return;
    msg = msg.replace("[scan_regions]", region.name);
    msg = msg.replace("[scan_region]", region.name);
    msg = msg.replace("[region]", region.name);
    RTPRuntime.serverAccessor().announce(msg, "rtp.scan", "SCAN");
  }

}

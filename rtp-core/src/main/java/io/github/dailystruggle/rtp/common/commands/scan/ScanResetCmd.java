package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

      // cancel any running scan task for this region first
      ScanTask scanTask = RTP.getInstance().scanTasks.get(region.name);
      if (scanTask != null) {
        scanTask.setCancelled(true);
        scanTask.pause();
        ScanTask.delete(region.name);
        RTP.getInstance().scanTasks.remove(region.name);
      }

      Shape<?> shapeObj = region.getShape();
      if (!(shapeObj instanceof MemoryShape)) {
        if (RTP.configs == null) continue;
        String msg = String.valueOf(RTP.configs.getConfigValue(PlayerMessages.badArg, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[arg]", "region=" + region.name);
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        continue;
      }

      MemoryShape<?> shape = (MemoryShape<?>) shapeObj;
      shape.clear();
      shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
      ScanTask.delete(region.name);

      // restore spatialResolution from config so the region is ready for a fresh scan or normal use
      MultiConfigParser<RegionKeys> multiConfigParser =
          (MultiConfigParser<RegionKeys>) RTP.configs.getParser(RegionKeys.class);
      if (multiConfigParser != null) {
        ConfigParser<RegionKeys> regionConfig = multiConfigParser.getParser(region.name);
        if (regionConfig != null) {
          shape.spatialResolution =
              regionConfig.getNumber(RegionKeys.spatialResolution, 1L).longValue();
        }
      }

      if (RTP.configs == null) continue;
      String msg = String.valueOf(RTP.configs.getConfigValue(CommandMessages.scanReset, ""));
      if (msg == null || msg.isEmpty()) continue;
      msg = msg.replace("[region]", region.name);
      RTP.serverAccessor.announce(msg, "rtp.scan", "SCAN");
    }
    return true;
  }

}

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

public class ScanStartCmd extends ScanSubCmd {
  public ScanStartCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "start";
  }

  @Override
  public String description() {
    return "clear region data and start from 0";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<Region> regions = getRegions(callerId, parameterValues.get("region"));
    for (Region region : regions) {
      ScanTask scanTask = RTP.getInstance().scanTasks.get(region.name);
      if (scanTask != null) {
        if (RTP.configs == null) continue;
        String msg = String.valueOf(RTP.configs.getConfigValue(CommandMessages.scanRunning, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[region]", region.name);
        RTP.serverAccessor.announce(msg, "rtp.scan", "SCAN");
        continue;
      }

      Shape<?> shapeObj = region.getShape();
      MemoryShape<?> shape;
      if (shapeObj instanceof MemoryShape) {
        shape = (MemoryShape<?>) shapeObj;
      } else {
        if (RTP.configs == null) continue;
        String msg = String.valueOf(RTP.configs.getConfigValue(PlayerMessages.badArg, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[arg]", "region=" + region.name);
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        continue;
      }

      shape.clear();
      shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());
      ScanTask.delete(region.name);
      MultiConfigParser<RegionKeys> multiConfigParser = (MultiConfigParser<RegionKeys>) RTP.configs.getParser(RegionKeys.class);
      if (multiConfigParser != null) {
        ConfigParser<RegionKeys> regionConfig = multiConfigParser.getParser(region.name);
        shape.setSpatialResolution(regionConfig.getNumber(RegionKeys.spatialResolution, 1L).longValue());
      }

      ScanTask task = new ScanTask(region, 0L);
      RTP.getInstance().scanTasks.put(region.name, task);
      RTP.scheduler.runTaskAsynchronously(task);
      if (RTP.configs == null) continue;
      String msg = String.valueOf(RTP.configs.getConfigValue(CommandMessages.scanStart, ""));
      if (msg == null || msg.isEmpty()) continue;
      msg = msg.replace("[region]", region.name);
      RTP.serverAccessor.announce(msg, "rtp.scan", "SCAN");
    }

    return true;
  }

}

package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTasks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class ScanPauseCmd extends ScanSubCmd {
  public ScanPauseCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "pause";
  }

  @Override
  public String description() {
    return "continue scan process";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<String> regionParam = parameterValues.get("region");
    List<Region> regions = getRegions(callerId, regionParam);
    for (Region region : regions) {
      ScanTask scanTask = ScanTasks.current().get(region.name);
      if (scanTask == null) {
        if (ActiveConfigs.get() == null) continue;
        String msg = String.valueOf(ActiveConfigs.get().getConfigValue(CommandMessages.scanNotRunning, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[scan_regions]", region.name);
        msg = msg.replace("[scan_region]", region.name);
        msg = msg.replace("[region]", region.name);
        RTPRuntime.serverAccessor().announce(msg, "rtp.scan", "SCAN");
        continue;
      }

      scanTask.pause();
      MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
      shape.save(region.name + "_" + region.cacheKey(), region.getWorld().name());

      if (ActiveConfigs.get() == null) continue;
      String msg = String.valueOf(ActiveConfigs.get().getConfigValue(CommandMessages.scanPause, ""));
      if (msg == null || msg.isEmpty()) continue;
      msg = msg.replace("[scan_regions]", region.name);
      msg = msg.replace("[scan_region]", region.name);
      msg = msg.replace("[region]", region.name);
      RTPRuntime.serverAccessor().announce(msg, "rtp.scan", "SCAN");
    }
    return true;
  }

}

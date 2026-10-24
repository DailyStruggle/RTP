package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTasks;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class ScanResumeCmd extends ScanSubCmd {
  private final ScanStartCmd scanStartCmd = new ScanStartCmd(this);

  public ScanResumeCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "resume";
  }

  @Override
  public String description() {
    return "continue scan process";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<Region> regions = getRegions(callerId, parameterValues.get("region"));
    for (Region region : regions) {
      ScanTask scanTask = ScanTasks.current().get(region.name);
      if (scanTask == null) {
        Map<String, List<String>> singleRegion = new HashMap<>(parameterValues);
        singleRegion.put("region", Collections.singletonList(region.name));
        scanStartCmd.onCommand(callerId, singleRegion, null);
        continue;
      }

      scanTask.resetStatusWindow();
      scanTask.pause.set(false);
      RTPRuntime.scheduler().runTaskAsynchronously(scanTask);

      if (ActiveConfigs.get() == null) continue;
      String msg = String.valueOf(ActiveConfigs.get().getConfigValue(CommandMessages.scanResume, ""));
      if (msg == null || msg.isEmpty()) continue;
      msg = msg.replace("[scan_regions]", region.name);
      msg = msg.replace("[scan_region]", region.name);
      msg = msg.replace("[region]", region.name);
      RTPRuntime.serverAccessor().announce(msg, "rtp.scan", "SCAN");
    }
    return true;
  }

}

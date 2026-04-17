package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class ScanCancelCmd extends ScanSubCmd {
  public ScanCancelCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "cancel";
  }

  @Override
  public String description() {
    return "cancel the scan process";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<Region> regions = getRegions(callerId, parameterValues.get("region"));
    for (Region region : regions) {
      ScanTask scanTask = RTP.getInstance().scanTasks.get(region.name);
      ConfigParser<MessagesKeys> parser =
          (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      if (scanTask == null) {
        if (parser == null) continue;
        String msg = String.valueOf(parser.getConfigValue(MessagesKeys.scanNotRunning, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[region]", region.name);
        RTP.serverAccessor.announce(msg, "rtp.scan", "SCAN");
        continue;
      }

      scanTask.setCancelled(true);
      scanTask.pause();
      ScanTask.delete(region.name);
      RTP.getInstance().scanTasks.remove(region.name);
      if (parser == null) continue;
      String msg = String.valueOf(parser.getConfigValue(MessagesKeys.scanCancel, ""));
      if (msg == null || msg.isEmpty()) continue;
      msg = msg.replace("[region]", region.name);
      RTP.serverAccessor.announce(msg, "rtp.scan", "SCAN");
    }

    return true;
  }

}

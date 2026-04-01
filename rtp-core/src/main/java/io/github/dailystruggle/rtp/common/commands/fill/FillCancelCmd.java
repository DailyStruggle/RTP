package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class FillCancelCmd extends FillSubCmd {
  public FillCancelCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "cancel";
  }

  @Override
  public String description() {
    return "cancel the fill process";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<Region> regions = getRegions(callerId, parameterValues.get("region"));
    for (Region region : regions) {
      FillTask fillTask = RTP.getInstance().fillTasks.get(region.name);
      ConfigParser<MessagesKeys> parser =
          (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      if (fillTask == null) {
        if (parser == null) continue;
        String msg = String.valueOf(parser.getConfigValue(MessagesKeys.fillNotRunning, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[region]", region.name);
        RTP.serverAccessor.announce(msg, "rtp.fill", "FILL");
        continue;
      }

      fillTask.setCancelled(true);
      fillTask.pause();
      FillTask.delete(region.name);
      RTP.getInstance().fillTasks.remove(region.name);
      if (parser == null) continue;
      String msg = String.valueOf(parser.getConfigValue(MessagesKeys.fillCancel, ""));
      if (msg == null || msg.isEmpty()) continue;
      msg = msg.replace("[region]", region.name);
      RTP.serverAccessor.announce(msg, "rtp.fill", "FILL");
    }

    return true;
  }

  public List<Region> getRegions(UUID callerId, List<String> regionParameter) {
    List<Region> regions = new ArrayList<>();
    RTPCommandSender sender = RTP.serverAccessor.getSender(callerId);
    if (regionParameter != null) {
      for (String name : regionParameter) regions.add(RTP.selectionAPI.getRegion(name));
    } else if (sender instanceof RTPPlayer)
      regions.add(RTP.selectionAPI.getRegion((RTPPlayer) sender));
    else regions.add(RTP.selectionAPI.getRegion("default"));
    return regions;
  }
}

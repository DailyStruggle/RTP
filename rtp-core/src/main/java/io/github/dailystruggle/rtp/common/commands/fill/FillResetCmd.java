package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class FillResetCmd extends FillSubCmd {
  public FillResetCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "reset";
  }

  @Override
  public String description() {
    return "clear region memory shape data without starting a new fill";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<Region> regions = getRegions(callerId, parameterValues.get("region"));
    for (Region region : regions) {
      ConfigParser<MessagesKeys> parser =
          (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

      // cancel any running fill task for this region first
      FillTask fillTask = RTP.getInstance().fillTasks.get(region.name);
      if (fillTask != null) {
        fillTask.setCancelled(true);
        fillTask.pause();
        FillTask.delete(region.name);
        RTP.getInstance().fillTasks.remove(region.name);
      }

      Shape<?> shapeObj = region.getShape();
      if (!(shapeObj instanceof MemoryShape)) {
        if (parser == null) continue;
        String msg = String.valueOf(parser.getConfigValue(MessagesKeys.badArg, ""));
        if (msg == null || msg.isEmpty()) continue;
        msg = msg.replace("[arg]", "region:" + region.name);
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        continue;
      }

      MemoryShape<?> shape = (MemoryShape<?>) shapeObj;
      shape.clear();
      shape.save(region.name, region.getWorld().name());
      FillTask.delete(region.name);

      // restore spatialResolution from config so the region is ready for a fresh fill or normal use
      MultiConfigParser<RegionKeys> multiConfigParser =
          (MultiConfigParser<RegionKeys>) RTP.configs.getParser(RegionKeys.class);
      if (multiConfigParser != null) {
        ConfigParser<RegionKeys> regionConfig = multiConfigParser.getParser(region.name);
        if (regionConfig != null) {
          shape.spatialResolution =
              regionConfig.getNumber(RegionKeys.spatialResolution, 1L).longValue();
        }
      }

      if (parser == null) continue;
      String msg = String.valueOf(parser.getConfigValue(MessagesKeys.fillReset, ""));
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

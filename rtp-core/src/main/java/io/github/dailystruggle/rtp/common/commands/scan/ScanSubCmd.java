package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public abstract class ScanSubCmd extends BaseRTPCmdImpl {
  public ScanSubCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "region", new RegionParameter("rtp.scan", "scan a specific region", (uuid, s) -> true));
  }

  @Override
  public String permission() {
    return "rtp.scan";
  }

  public List<Region> getRegions(UUID callerId, List<String> regionParameter) {
    List<Region> regions = new ArrayList<>();
    if (regionParameter != null) {
      for (String name : regionParameter) regions.add(RTP.selectionAPI.getRegion(name));
    } else {
      RTPCommandSender sender = RTP.serverAccessor.getSender(callerId);
      if (sender instanceof RTPPlayer)
        regions.add(RTP.selectionAPI.getRegion((RTPPlayer) sender));
      else
        regions.addAll(RTP.selectionAPI.permRegionLookup.values());
    }
    return regions;
  }
}

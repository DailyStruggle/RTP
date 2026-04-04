package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class FillCmd extends BaseRTPCmdImpl {
  FillResumeCmd fillResumeCmd = new FillResumeCmd(this);

  public FillCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addSubCommand(new FillStartCmd(this));
    addSubCommand(new FillPauseCmd(this));
    addSubCommand(fillResumeCmd);
    addSubCommand(new FillCancelCmd(this));
    addParameter(
        "region", new RegionParameter("rtp.fill", "fill a specific region", (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "fill";
  }

  @Override
  public String permission() {
    return "rtp.fill";
  }

  @Override
  public String description() {
    return "clear stored data and try all possible region placements";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    return fillResumeCmd.onCommand(callerId, parameterValues, null);
  }
}

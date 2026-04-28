package io.github.dailystruggle.rtp.common.commands.scan;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public class ScanCmd extends BaseRTPCmdImpl {
  ScanResumeCmd scanResumeCmd = new ScanResumeCmd(this);

  public ScanCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addSubCommand(new ScanStartCmd(this));
    addSubCommand(new ScanResetCmd(this));
    addSubCommand(new ScanPauseCmd(this));
    addSubCommand(scanResumeCmd);
    addSubCommand(new ScanCancelCmd(this));
    addParameter(
        "region", new RegionParameter("rtp.scan", "scan a specific region", (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "scan";
  }

  @Override
  public String permission() {
    return "rtp.scan";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    return scanResumeCmd.onCommand(callerId, parameterValues, null);
  }
}

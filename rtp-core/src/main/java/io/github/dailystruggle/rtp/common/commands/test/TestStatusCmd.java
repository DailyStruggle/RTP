package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitOutput;
import io.github.dailystruggle.bit.BitOutputs;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/** Displays the registered BIT components and their last result without rerunning checks. */
public class TestStatusCmd extends BaseRTPCmdImpl {
  public TestStatusCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit BIT status as JSON", (uuid, value) -> true));
  }

  @Override
  public String name() {
    return "status";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "shows the cached health and last-run state of registered BIT components";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    boolean json = ProbeBitReport.isJson(parameterValues);
    BitOutput output = json
        ? BitOutputs.json("[RTP bit/test.")
        : BitOutputs.text("[RTP test/status] ");
    List<BitComponent> components = RtpBit.RUNNER.components();
    if (components.isEmpty()) {
      output.writeEmpty("registered", line -> emit(callerId, line));
      return true;
    }
    for (BitComponent component : components) {
      if (component.tier() == BitTier.CBIT) RtpBit.RUNNER.run(component, Runnable::run);
    }
    if (!json) {
      BitTableOutput.emit(callerId, BitTableOutput.rows(components, RtpBit.RUNNER::last));
      return true;
    }
    for (BitComponent component : components) {
      BitResult result = RtpBit.RUNNER.last(component.id());
      if (result == null) output.writeNotRun(component, line -> emit(callerId, line));
      else output.write(result, line -> emit(callerId, line));
    }
    return true;
  }

  private static void emit(UUID callerId, String line) {
    RTPRuntime.log(Level.INFO, line);
    if (callerId != null
        && !callerId.equals(RTPAPI.serverId)
        && RTPRuntime.serverAccessor() != null) {
      RTPRuntime.serverAccessor().sendMessage(callerId, line);
    }
  }
}

package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitCoverageSnapshot;
import io.github.dailystruggle.bit.BitOutput;
import io.github.dailystruggle.bit.BitOutputs;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitRunner;
import io.github.dailystruggle.bit.BitTable;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/** Displays the registered BIT components and their last result without rerunning checks. */
public class TestStatusCmd extends BaseRTPCmdImpl {
  private final BitRunner runner;

  public TestStatusCmd(@Nullable CommandsAPICommand parent) {
    this(parent, RtpBit.RUNNER);
  }

  public TestStatusCmd(BitRunner runner, CommandsAPICommand... parent) {
    this(parent != null && parent.length > 0 ? parent[0] : null, runner);
  }

  public TestStatusCmd(@Nullable CommandsAPICommand parent, BitRunner runner) {
    super(parent);
    this.runner = Objects.requireNonNull(runner, "runner must not be null");
    addParameter("json", new BooleanParameter("rtp.test", "emit BIT status as JSON", (uuid, value) -> true));
  }

  public BitRunner runner() {
    return runner;
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
    List<BitComponent> components = runner.components();
    if (components.isEmpty()) {
      output.writeEmpty("registered", line -> emit(callerId, line));
      return true;
    }
    for (BitComponent component : components) {
      if (component.tier() == BitTier.CBIT) runner.run(component, Runnable::run);
    }
    BitCoverageSnapshot coverage = runner.coverageSnapshot();
    if (!json) {
      BitTableOutput.emit(callerId, BitTableOutput.rows(components, runner::last));
      String footer = BitTable.coverageFooter(coverage);
      if (!footer.isEmpty()) {
        emit(callerId, BitTableOutput.PREFIX + footer);
      }
      return true;
    }
    for (BitComponent component : components) {
      BitResult result = runner.last(component.id());
      if (result == null) output.writeNotRun(component, line -> emit(callerId, line));
      else output.write(result, line -> emit(callerId, line));
    }
    if (coverage != null && coverage.isEnabled()) {
      emit(callerId, "[RTP bit/test.coverage] " + coverage.toJson());
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

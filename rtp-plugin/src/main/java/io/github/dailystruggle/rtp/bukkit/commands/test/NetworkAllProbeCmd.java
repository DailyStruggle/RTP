package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp test network all [count=N] [observeMs=M]} - heartbeat then
 * reservation-token round-trip in a single dispatch. The token-probe peer
 * count is clamped to {@link NetworkSimulationTestJob#MAX_TOKEN_COUNT} and
 * the token TTL uses {@link NetworkSimulationTestJob#DEFAULT_TOKEN_TTL_MS}
 * to keep the combined run lightweight.
 *
 * <p>Real subcommand of {@link NetworkSimulationTestJob}.
 */
public final class NetworkAllProbeCmd extends BaseRTPCmdImpl {

  private final NetworkSimulationTestJob job;

  public NetworkAllProbeCmd(@Nullable NetworkSimulationTestJob parent) {
    super(parent);
    this.job = parent;
    addParameter(NetworkSimulationTestJob.PARAM_COUNT,
            new IntegerParameter("rtp.test",
                    "number of synthetic peers/tokens (1.." + NetworkSimulationTestJob.MAX_PEER_COUNT + ")",
                    (uuid, s) -> true));
    addParameter(NetworkSimulationTestJob.PARAM_OBSERVE_MS,
            new IntegerParameter("rtp.test",
                    "heartbeat observation window in ms (250..30000)",
                    (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "all";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "heartbeat round-trip then reservation-token round-trip";
  }

  @Override
  public boolean onCommand(UUID callerId,
          Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (job == null) return true;
    int count = NetworkHeartbeatProbeCmd.parseInt(parameterValues,
            NetworkSimulationTestJob.PARAM_COUNT, NetworkSimulationTestJob.DEFAULT_PEER_COUNT);
    long observeMs = NetworkHeartbeatProbeCmd.parseLong(parameterValues,
            NetworkSimulationTestJob.PARAM_OBSERVE_MS, NetworkSimulationTestJob.DEFAULT_OBSERVE_MS);
    count = NetworkSimulationTestJob.clamp(count, 1, NetworkSimulationTestJob.MAX_PEER_COUNT);
    observeMs = NetworkSimulationTestJob.clamp(observeMs, 250L, 30_000L);
    job.dispatchProbe(callerId, NetworkSimulationTestJob.ProbeKind.ALL,
            count, observeMs, NetworkSimulationTestJob.DEFAULT_TOKEN_TTL_MS);
    return true;
  }
}

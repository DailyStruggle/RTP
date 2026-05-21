package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp test network tokens [count=N] [ttlMs=M]} - reservation-token
 * claim/find/release/reap round-trip via the live {@link
 * io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport}.
 *
 * <p>Real subcommand of {@link NetworkSimulationTestJob}. See the parent
 * Javadoc for the grammar history that motivated splitting probe modes
 * into real subcommands.
 */
public final class NetworkTokensProbeCmd extends BaseRTPCmdImpl {

  private final NetworkSimulationTestJob job;

  public NetworkTokensProbeCmd(@Nullable NetworkSimulationTestJob parent) {
    super(parent);
    this.job = parent;
    addParameter(NetworkSimulationTestJob.PARAM_COUNT,
            new IntegerParameter("rtp.test",
                    "number of synthetic tokens (1.." + NetworkSimulationTestJob.MAX_TOKEN_COUNT + ")",
                    (uuid, s) -> true));
    addParameter(NetworkSimulationTestJob.PARAM_TTL_MS,
            new IntegerParameter("rtp.test",
                    "per-token TTL in ms (250..30000)",
                    (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "tokens";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "reservation-token claim/find/release/reap round-trip";
  }

  @Override
  public boolean onCommand(UUID callerId,
          Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (job == null) return true;
    int count = NetworkHeartbeatProbeCmd.parseInt(parameterValues,
            NetworkSimulationTestJob.PARAM_COUNT, NetworkSimulationTestJob.DEFAULT_TOKEN_COUNT);
    long ttlMs = NetworkHeartbeatProbeCmd.parseLong(parameterValues,
            NetworkSimulationTestJob.PARAM_TTL_MS, NetworkSimulationTestJob.DEFAULT_TOKEN_TTL_MS);
    count = NetworkSimulationTestJob.clamp(count, 1, NetworkSimulationTestJob.MAX_TOKEN_COUNT);
    ttlMs = NetworkSimulationTestJob.clamp(ttlMs, 250L, 30_000L);
    job.dispatchProbe(callerId, NetworkSimulationTestJob.ProbeKind.TOKENS,
            count, NetworkSimulationTestJob.DEFAULT_OBSERVE_MS, ttlMs);
    return true;
  }
}

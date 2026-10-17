package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp test network all [count=N] [observeMs=M]} - heartbeat then
 * reservation-token round-trip in a single dispatch. The token-probe peer
 * count is clamped to {@link NetworkProbeTarget#MAX_TOKEN_COUNT} and
 * the token TTL uses {@link NetworkProbeTarget#DEFAULT_TOKEN_TTL_MS}
 * to keep the combined run lightweight.
 *
 * <p>Real subcommand of {@code NetworkSimulationTestJob}.
 */
public final class NetworkAllProbeCmd extends BaseRTPCmdImpl {

  private final NetworkProbeTarget job;

  public NetworkAllProbeCmd(@Nullable NetworkProbeTarget parent) {
    super(parent);
    this.job = parent;
    addParameter(NetworkProbeTarget.PARAM_COUNT,
            new IntegerParameter("rtp.test",
                    "number of synthetic peers/tokens (1.." + NetworkProbeTarget.MAX_PEER_COUNT + ")",
                    (uuid, s) -> true));
    addParameter(NetworkProbeTarget.PARAM_OBSERVE_MS,
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
  @SuppressWarnings("java:S3516") // Method returns true on async probe dispatch
  public boolean onCommand(UUID callerId,
          Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (job == null) return true;
    int count = NetworkProbeTarget.parseInt(parameterValues,
            NetworkProbeTarget.PARAM_COUNT, NetworkProbeTarget.DEFAULT_PEER_COUNT);
    long observeMs = NetworkProbeTarget.parseLong(parameterValues,
            NetworkProbeTarget.PARAM_OBSERVE_MS, NetworkProbeTarget.DEFAULT_OBSERVE_MS);
    count = NetworkProbeTarget.clamp(count, 1, NetworkProbeTarget.MAX_PEER_COUNT);
    observeMs = NetworkProbeTarget.clamp(observeMs, 250L, 30_000L);
    job.dispatchProbe(callerId, NetworkProbeTarget.ProbeKind.ALL,
            count, observeMs, NetworkProbeTarget.DEFAULT_TOKEN_TTL_MS);
    return true;
  }
}

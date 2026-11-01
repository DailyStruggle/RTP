package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp test network heartbeat [count=N] [observeMs=M]} - heartbeat
 * publish/subscribe/snapshot round-trip via the live {@link
 * io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport}.
 *
 * <p>Real subcommand of {@code NetworkSimulationTestJob}; introduced to fix
 * the prior grammar bug where {@code heartbeat}/{@code tokens}/{@code all}
 * were hand-parsed off {@code parameterValues} and dispatched as unknown
 * subcommands (commands-api section 2.2: bare tokens are always subcommands).
 */
public final class NetworkHeartbeatProbeCmd extends BaseRTPCmdImpl {

  private final NetworkProbeTarget job;

  public NetworkHeartbeatProbeCmd(@Nullable NetworkProbeTarget parent) {
    super(parent);
    this.job = parent;
    addParameter(NetworkProbeTarget.PARAM_COUNT,
            new IntegerParameter("rtp.test",
                    "number of synthetic peers (1.." + NetworkProbeTarget.MAX_PEER_COUNT + ")",
                    (uuid, s) -> true));
    addParameter(NetworkProbeTarget.PARAM_OBSERVE_MS,
            new IntegerParameter("rtp.test",
                    "observation window in ms (250..30000)",
                    (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "heartbeat";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "heartbeat publish/subscribe/snapshot round-trip";
  }

  @Override
  @SuppressWarnings("java:S3516") // Method returns true on async probe dispatch
  public boolean onCommand(UUID callerId,
          Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (job == null) return true; // defensive: no parent to dispatch through.
    int count = NetworkProbeTarget.parseInt(parameterValues, NetworkProbeTarget.PARAM_COUNT,
            NetworkProbeTarget.DEFAULT_PEER_COUNT);
    long observeMs = NetworkProbeTarget.parseLong(parameterValues, NetworkProbeTarget.PARAM_OBSERVE_MS,
            NetworkProbeTarget.DEFAULT_OBSERVE_MS);
    count = NetworkProbeTarget.clamp(count, 1, NetworkProbeTarget.MAX_PEER_COUNT);
    observeMs = NetworkProbeTarget.clamp(observeMs, 250L, 30_000L);
    job.dispatchProbe(callerId, NetworkProbeTarget.ProbeKind.HEARTBEAT,
            count, observeMs, NetworkProbeTarget.DEFAULT_TOKEN_TTL_MS);
    return true;
  }
}

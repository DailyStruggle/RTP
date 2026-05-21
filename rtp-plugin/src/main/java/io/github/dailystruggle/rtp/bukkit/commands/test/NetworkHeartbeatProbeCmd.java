package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
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
 * <p>Real subcommand of {@link NetworkSimulationTestJob}; introduced to fix
 * the prior grammar bug where {@code heartbeat}/{@code tokens}/{@code all}
 * were hand-parsed off {@code parameterValues} and dispatched as unknown
 * subcommands (commands-api §2.2: bare tokens are always subcommands).
 */
public final class NetworkHeartbeatProbeCmd extends BaseRTPCmdImpl {

  private final NetworkSimulationTestJob job;

  public NetworkHeartbeatProbeCmd(@Nullable NetworkSimulationTestJob parent) {
    super(parent);
    this.job = parent;
    addParameter(NetworkSimulationTestJob.PARAM_COUNT,
            new IntegerParameter("rtp.test",
                    "number of synthetic peers (1.." + NetworkSimulationTestJob.MAX_PEER_COUNT + ")",
                    (uuid, s) -> true));
    addParameter(NetworkSimulationTestJob.PARAM_OBSERVE_MS,
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
  public boolean onCommand(UUID callerId,
          Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (job == null) return true; // defensive: no parent to dispatch through.
    int count = parseInt(parameterValues, NetworkSimulationTestJob.PARAM_COUNT,
            NetworkSimulationTestJob.DEFAULT_PEER_COUNT);
    long observeMs = parseLong(parameterValues, NetworkSimulationTestJob.PARAM_OBSERVE_MS,
            NetworkSimulationTestJob.DEFAULT_OBSERVE_MS);
    count = NetworkSimulationTestJob.clamp(count, 1, NetworkSimulationTestJob.MAX_PEER_COUNT);
    observeMs = NetworkSimulationTestJob.clamp(observeMs, 250L, 30_000L);
    job.dispatchProbe(callerId, NetworkSimulationTestJob.ProbeKind.HEARTBEAT,
            count, observeMs, NetworkSimulationTestJob.DEFAULT_TOKEN_TTL_MS);
    return true;
  }

  static int parseInt(Map<String, List<String>> values, String key, int fallback) {
    if (values == null) return fallback;
    List<String> vs = values.get(key);
    if (vs == null || vs.isEmpty()) return fallback;
    try { return Integer.parseInt(vs.get(0)); }
    catch (NumberFormatException e) { return fallback; }
  }

  static long parseLong(Map<String, List<String>> values, String key, long fallback) {
    if (values == null) return fallback;
    List<String> vs = values.get(key);
    if (vs == null || vs.isEmpty()) return fallback;
    try { return Long.parseLong(vs.get(0)); }
    catch (NumberFormatException e) { return fallback; }
  }
}

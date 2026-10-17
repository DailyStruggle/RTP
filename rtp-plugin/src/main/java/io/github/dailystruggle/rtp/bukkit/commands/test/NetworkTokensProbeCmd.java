package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
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
 * <p>Real subcommand of {@code NetworkSimulationTestJob}. See the parent
 * Javadoc for the grammar history that motivated splitting probe modes
 * into real subcommands.
 */
public final class NetworkTokensProbeCmd extends BaseRTPCmdImpl {

  private final NetworkProbeTarget job;

  public NetworkTokensProbeCmd(@Nullable NetworkProbeTarget parent) {
    super(parent);
    this.job = parent;
    addParameter(NetworkProbeTarget.PARAM_COUNT,
            new IntegerParameter("rtp.test",
                    "number of synthetic tokens (1.." + NetworkProbeTarget.MAX_TOKEN_COUNT + ")",
                    (uuid, s) -> true));
    addParameter(NetworkProbeTarget.PARAM_TTL_MS,
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
  @SuppressWarnings("java:S3516") // Method returns true on async probe dispatch
  public boolean onCommand(UUID callerId,
          Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (job == null) return true;
    int count = NetworkProbeTarget.parseInt(parameterValues,
            NetworkProbeTarget.PARAM_COUNT, NetworkProbeTarget.DEFAULT_TOKEN_COUNT);
    long ttlMs = NetworkProbeTarget.parseLong(parameterValues,
            NetworkProbeTarget.PARAM_TTL_MS, NetworkProbeTarget.DEFAULT_TOKEN_TTL_MS);
    count = NetworkProbeTarget.clamp(count, 1, NetworkProbeTarget.MAX_TOKEN_COUNT);
    ttlMs = NetworkProbeTarget.clamp(ttlMs, 250L, 30_000L);
    job.dispatchProbe(callerId, NetworkProbeTarget.ProbeKind.TOKENS,
            count, NetworkProbeTarget.DEFAULT_OBSERVE_MS, ttlMs);
    return true;
  }
}

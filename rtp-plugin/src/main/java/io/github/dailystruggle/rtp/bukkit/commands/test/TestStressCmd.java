package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.OnlinePlayerParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test stress player:&lt;name&gt; [iterations:N] [intervalTicks:T] [region:R]}.
 *
 * <p>Repeatedly invokes the real {@link RTPCmd#compute(UUID, Map, CommandsAPICommand)}
 * pipeline for each listed player, on the async scheduler, with a bounded
 * iteration count and tick interval. Delegation (as opposed to constructing
 * {@code TeleportPipelineTask} directly) is deliberate: it preserves every
 * safety guard — cooldowns, {@code processingPlayers}, economy, claim
 * verifiers, and the async chunk path — so stress results reflect real
 * user-triggered traffic.
 *
 * <p>The {@code player} argument uses {@link OnlinePlayerParameter} so
 * tab-completion shows currently-online players; this couples the command to
 * Bukkit, which is why it lives in {@code rtp-plugin} rather than
 * {@code rtp-core}.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> — every skipped player emits a WARNING log; per
 *       iteration outcomes are reported to the caller at completion.</li>
 *   <li><b>REQ-RTP-S-005</b> — timer runs on {@code runTaskTimerAsynchronously};
 *       no chunk I/O on the main thread.</li>
 *   <li><b>REQ-RTP-S-002</b> — no direct ticket acquisition; delegation reuses
 *       the pipeline's existing release paths.</li>
 * </ul>
 */
public class TestStressCmd extends BaseRTPCmdImpl {

  /** Hard caps so an operator typo cannot saturate the scheduler. */
  static final int MIN_ITERATIONS = 1;
  static final int MAX_ITERATIONS = 1000;
  static final int MIN_INTERVAL_TICKS = 10;
  static final int MAX_INTERVAL_TICKS = 6000;

  static final int DEFAULT_ITERATIONS = 10;
  static final int DEFAULT_INTERVAL_TICKS = 40;

  public TestStressCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "player",
        new OnlinePlayerParameter(
            "rtp.test", "target player(s) to stress-teleport", (sender, s) -> true));
    addParameter(
        "iterations",
        new IntegerParameter(
            "rtp.test", "number of teleport iterations (1..1000)", (uuid, s) -> true));
    addParameter(
        "intervalTicks",
        new IntegerParameter(
            "rtp.test",
            "ticks between iterations (10..6000)",
            (uuid, s) -> true));
    addParameter(
        "region",
        new RegionParameter(
            "rtp.test", "region to stress (defaults to caller's region)", (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "stress";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "repeatedly teleport the listed player(s) through the real rtp pipeline";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<String> playerNames = parameterValues.get("player");
    if (playerNames == null || playerNames.isEmpty()) {
      RTP.serverAccessor.sendMessage(
          callerId, "&c[RTP test/stress] missing required player:<name> argument");
      RTP.log(
          Level.WARNING,
          "[RTP test/stress] rejected call from " + callerId + ": no player argument");
      return true;
    }

    // Resolve players up-front so typos fail loud (S-004).
    List<RTPPlayer> targets = new ArrayList<>(playerNames.size());
    for (String name : playerNames) {
      RTPPlayer p = RTP.serverAccessor.getPlayer(name);
      if (p == null) {
        String msg = "&c[RTP test/stress] unknown player: " + name;
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTP.serverAccessor.sendMessage(callerId, msg);
        }
        RTP.log(Level.WARNING, msg);
        continue;
      }
      targets.add(p);
    }

    if (targets.isEmpty()) {
      RTP.log(
          Level.WARNING, "[RTP test/stress] no resolvable players; aborting run for " + callerId);
      return true;
    }

    final int iterations =
        clamp(parseFirst(parameterValues.get("iterations"), DEFAULT_ITERATIONS),
            MIN_ITERATIONS, MAX_ITERATIONS);
    final int intervalTicks =
        clamp(parseFirst(parameterValues.get("intervalticks"), DEFAULT_INTERVAL_TICKS),
            MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS);

    // Locate the root RTP command so we can delegate each iteration through
    // the normal pipeline. Walk up `parent()` chain rather than hard-coding
    // a reference so the stress command stays platform-agnostic.
    final RTPCmd rootCmd = findRtpCmd();
    if (rootCmd == null) {
      String msg = "&c[RTP test/stress] root rtp command not found; aborting";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      return true;
    }

    final AtomicInteger completed = new AtomicInteger(0);
    final AtomicInteger failures = new AtomicInteger(0);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final Object[] handleHolder = new Object[1];
    final Runnable[] unregisterHolder = new Runnable[1];

    String startMsg =
        "&a[RTP test/stress] starting: "
            + iterations
            + " iterations x "
            + targets.size()
            + " player(s), every "
            + intervalTicks
            + " ticks";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, startMsg);
    }
    RTP.log(Level.INFO, startMsg);

    Runnable tick =
        () -> {
          int current = completed.incrementAndGet();
          for (RTPPlayer target : targets) {
            try {
              Map<String, List<String>> iterArgs = new HashMap<>();
              iterArgs.put("player", Collections.singletonList(target.name()));
              List<String> regions = parameterValues.get("region");
              if (regions != null && !regions.isEmpty()) {
                iterArgs.put("region", new ArrayList<>(regions));
              }
              rootCmd.compute(callerId, iterArgs, null);
            } catch (Throwable t) {
              failures.incrementAndGet();
              RTP.log(
                  Level.WARNING,
                  "[RTP test/stress] iteration "
                      + current
                      + " failed for "
                      + target.name()
                      + ": "
                      + t.getMessage(),
                  t);
            }
          }

          if (cancelled.get()) return;
          if (current >= iterations) {
            String summary =
                "[RTP test/stress] done: completed="
                    + current
                    + " players="
                    + targets.size()
                    + " failures="
                    + failures.get();
            if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
              RTP.serverAccessor.sendMessage(callerId, summary);
            }
            RTP.log(Level.INFO, summary);
            Object h = handleHolder[0];
            if (h != null) {
              try {
                RTP.scheduler.cancelTask(h);
              } catch (Throwable ignored) {
                // Schedulers without a public cancel API will let the timer
                // decay naturally; we stop rescheduling below.
              }
            }
            Runnable unreg = unregisterHolder[0];
            if (unreg != null) unreg.run();
          }
        };

    handleHolder[0] =
        RTP.scheduler.runTaskTimerAsynchronously(
            new Runnable() {
              @Override
              public void run() {
                if (cancelled.get()) return;
                if (completed.get() >= iterations) return;
                tick.run();
              }
            },
            0L,
            intervalTicks);

    // Register with the shared cancel registry so `rtp test cancel` can
    // abort this loop. The canceller sets the stop flag first (so an
    // in-flight tick bails on the next iteration), then tells the
    // scheduler to drop the timer handle.
    Runnable canceller =
        () -> {
          cancelled.set(true);
          Object h = handleHolder[0];
          if (h != null) {
            try {
              RTP.scheduler.cancelTask(h);
            } catch (Throwable ignored) {
              // best-effort; see ActiveTestJobs.cancelOwned
            }
          }
          String msg =
              "[RTP test/stress] cancelled after "
                  + completed.get()
                  + "/"
                  + iterations
                  + " iterations (failures="
                  + failures.get()
                  + ")";
          RTP.serverAccessor.sendMessage(callerId, msg);
          RTP.log(Level.INFO, msg);
        };
    unregisterHolder[0] =
        ActiveTestJobs.register(callerId, new ActiveTestJobs.Job("stress", canceller));

    return true;
  }

  /** Walks the parent chain to locate the root {@link RTPCmd} node. */
  private RTPCmd findRtpCmd() {
    CommandsAPICommand node = parent();
    while (node != null) {
      if (node instanceof RTPCmd) return (RTPCmd) node;
      node = node.parent();
    }
    return null;
  }

  static int parseFirst(List<String> values, int fallback) {
    if (values == null || values.isEmpty()) return fallback;
    try {
      return Integer.parseInt(values.getFirst());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  static int clamp(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
  }
}

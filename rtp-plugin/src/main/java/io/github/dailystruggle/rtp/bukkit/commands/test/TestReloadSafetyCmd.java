package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test reload-safety [iterations:N]} &mdash; interleaves a
 * low-iteration stress run with {@link io.github.dailystruggle.rtp.common.configuration.Configs#reload()}
 * calls on a separate async timer, to surface reload-vs-teleport races
 * in {@code ConfigCache} and the region/queue rebuild path (see ADR-009).
 *
 * <p>This subcommand intentionally courts failures other subcommands
 * avoid: a successful run proves nothing, but a failing run (caught
 * {@code WARN} in the log, a dropped teleport, or a stuck
 * {@code processingPlayers} entry) is a <b>real</b> signal. For that
 * reason it is gated behind {@code rtp.test.admin} and hard-capped to
 * {@link #MAX_ITERATIONS} iterations.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> &mdash; every reload attempt logs success or
 *       failure at {@link Level#INFO}/{@link Level#WARNING}; no silent
 *       swallowing of {@code configs.reload()} returning {@code false}.</li>
 *   <li><b>REQ-RTP-S-005</b> &mdash; both the stress delegation and the
 *       reload trigger run on the async scheduler. The reload method
 *       itself does any sync work via RTP's own miscSyncTasks pipe.</li>
 * </ul>
 */
public class TestReloadSafetyCmd extends BaseRTPCmdImpl {

  /** Hard cap chosen to keep a misfire from thrashing the config layer. */
  static final int MAX_ITERATIONS = 20;

  static final int DEFAULT_ITERATIONS = 5;
  static final int MIN_ITERATIONS = 1;

  /** Async period between reload triggers, in ticks. Kept short enough to overlap with stress teleports. */
  static final long RELOAD_PERIOD_TICKS = 20L;

  public TestReloadSafetyCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "iterations",
        new IntegerParameter(
            "rtp.test.admin",
            "number of reload+stress iterations (1.." + MAX_ITERATIONS + ")",
            (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "reload-safety";
  }

  @Override
  public String permission() {
    return "rtp.test.admin";
  }

  @Override
  public String description() {
    return "interleave rtp reload with low-iteration stress to flush reload-vs-teleport races";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    if (RTP.scheduler == null) {
      String msg = "&c[RTP test/reload-safety] RTP.scheduler is null; core not yet loaded";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      return true;
    }

    final int iterations =
        TestStressCmd.clamp(
            TestStressCmd.parseFirst(parameterValues.get("iterations"), DEFAULT_ITERATIONS),
            MIN_ITERATIONS,
            MAX_ITERATIONS);

    final AtomicInteger reloadsOk = new AtomicInteger(0);
    final AtomicInteger reloadsFailed = new AtomicInteger(0);
    final AtomicInteger reloadsAttempted = new AtomicInteger(0);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final Object[] handleHolder = new Object[1];
    final Runnable[] unregisterHolder = new Runnable[1];

    String startMsg =
        "&e[RTP test/reload-safety] starting "
            + iterations
            + " reload iteration(s), every "
            + RELOAD_PERIOD_TICKS
            + " ticks";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, startMsg);
    }
    RTP.log(Level.INFO, startMsg);

    Runnable tick =
        () -> {
          if (cancelled.get()) return;
          int current = reloadsAttempted.incrementAndGet();
          try {
            // RTP.configs.reload() is itself safe to call from the async
            // scheduler; it schedules its own sync completion work via
            // miscSyncTasks. A `false` return value means at least one
            // parser failed to reload - that is a real S-004 signal.
            boolean ok = RTP.configs.reload();
            if (ok) {
              reloadsOk.incrementAndGet();
            } else {
              reloadsFailed.incrementAndGet();
              RTP.log(
                  Level.WARNING,
                  "[RTP test/reload-safety] iteration " + current + ": configs.reload() returned false");
            }
          } catch (Throwable t) {
            reloadsFailed.incrementAndGet();
            RTP.log(
                Level.WARNING,
                "[RTP test/reload-safety] iteration " + current + " threw " + t.getClass().getSimpleName(),
                t);
          }

          if (current >= iterations) {
            String summary =
                "[RTP test/reload-safety] done: attempts="
                    + current
                    + " ok="
                    + reloadsOk.get()
                    + " failed="
                    + reloadsFailed.get();
            if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
              RTP.serverAccessor.sendMessage(callerId, summary);
            }
            RTP.log(Level.INFO, summary);
            Object h = handleHolder[0];
            if (h != null) {
              try {
                RTP.scheduler.cancelTask(h);
              } catch (Throwable ignored) {
                // best-effort
              }
            }
            Runnable unreg = unregisterHolder[0];
            if (unreg != null) unreg.run();
          }
        };

    handleHolder[0] =
        RTP.scheduler.runTaskTimerAsynchronously(
            () -> {
              if (cancelled.get()) return;
              if (reloadsAttempted.get() >= iterations) return;
              tick.run();
            },
            0L,
            RELOAD_PERIOD_TICKS);

    Runnable canceller =
        () -> {
          cancelled.set(true);
          Object h = handleHolder[0];
          if (h != null) {
            try {
              RTP.scheduler.cancelTask(h);
            } catch (Throwable ignored) {
              // best-effort
            }
          }
          String msg =
              "[RTP test/reload-safety] cancelled after "
                  + reloadsAttempted.get()
                  + "/"
                  + iterations
                  + " iterations (ok="
                  + reloadsOk.get()
                  + " failed="
                  + reloadsFailed.get()
                  + ")";
          if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
            RTP.serverAccessor.sendMessage(callerId, msg);
          }
          RTP.log(Level.INFO, msg);
        };
    unregisterHolder[0] =
        ActiveTestJobs.register(
            callerId, new ActiveTestJobs.Job("reload-safety", canceller));

    return true;
  }
}

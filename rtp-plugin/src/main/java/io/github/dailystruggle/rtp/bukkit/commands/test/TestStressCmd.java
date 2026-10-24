package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.rtp.bukkitplatform.commands.parameters.OnlinePlayerParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.test.ProbeBitReport;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.base.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.mock.MockPlayerSession;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test stress player:&lt;name&gt; [iterations:N] [intervalTicks:T] [region:R]}.
 *
 * <p>Repeatedly invokes the real {@link RTPCmd#compute(UUID, Map, CommandsAPICommand)}
 * pipeline for each listed player, on the async scheduler, with a bounded
 * iteration count and tick interval. Delegation (as opposed to constructing
 * {@code TeleportPipelineTask} directly) is deliberate: it preserves every
 * safety guard - cooldowns, {@code processingPlayers}, economy, claim
 * verifiers, and the async chunk path - so stress results reflect real
 * user-triggered traffic.
 *
 * <p>The {@code player} argument uses {@link OnlinePlayerParameter} so
 * tab-completion shows currently-online players; this couples the command to
 * Bukkit, which is why it lives in {@code rtp-plugin} rather than
 * {@code rtp-core}.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> - every skipped player emits a WARNING log; per
 *       iteration outcomes are reported to the caller at completion.</li>
 *   <li><b>REQ-RTP-S-005</b> - timer runs on {@code runTaskTimerAsynchronously};
 *       no chunk I/O on the main thread.</li>
 *   <li><b>REQ-RTP-S-002</b> - no direct ticket acquisition; delegation reuses
 *       the pipeline's existing release paths.</li>
 * </ul>
 */
public class TestStressCmd extends BaseRTPCmdImpl {

  private static final String OWNER = "test.stress";
  private static final long COMPONENT_TIMEOUT_MS = 120_000L;
  private static final long MOCK_BUDGET_MS = 110_000L;
  private static final long MOCK_AWAIT_MS = 10_000L;

  public static final BitCode STRESS_MOCKED = registerCode(4101, "PROBE_STRESS_MOCKED", java.lang.System.Logger.Level.INFO);
  public static final BitCode STRESS_UNAVAILABLE = registerCode(4102, "PROBE_STRESS_UNAVAILABLE", java.lang.System.Logger.Level.ERROR);
  public static final BitCode STRESS_REJECTED = registerCode(4103, "PROBE_STRESS_REJECTED", java.lang.System.Logger.Level.ERROR);
  public static final BitCode STRESS_SET_LOCATION = registerCode(4104, "PROBE_STRESS_SET_LOCATION", java.lang.System.Logger.Level.ERROR);
  public static final BitCode STRESS_PROCESSING_LEAK = registerCode(4105, "PROBE_STRESS_PROCESSING_LEAK", java.lang.System.Logger.Level.ERROR);
  public static final BitCode STRESS_PLAYER_FAILURES = registerCode(4106, "PROBE_STRESS_PLAYER_FAILURES", java.lang.System.Logger.Level.ERROR);
  public static final BitCode STRESS_PLAYER_CANCELLED = RtpBitCodes.PROBE.register(
      4107, "PROBE_STRESS_PLAYER_CANCELLED", java.lang.System.Logger.Level.WARNING,
      BitTier.IBIT, OWNER + "-player", "", "Allow the bounded player stress run to complete.");

  public static final BitComponent COMPONENT = registerComponent("stress", BitSideEffect.HEAVY, OWNER);
  public static final BitComponent PLAYER_COMPONENT =
      registerComponent("stress-player", BitSideEffect.NEEDS_PLAYER, OWNER + "-player");

  /** Hard caps so an operator typo cannot saturate the scheduler. */
  static final int MIN_ITERATIONS = 1;
  static final int MAX_ITERATIONS = 1000;
  static final int MIN_INTERVAL_TICKS = 10;
  static final int MAX_INTERVAL_TICKS = 6000;

  static final int DEFAULT_ITERATIONS = 10;
  static final int DEFAULT_INTERVAL_TICKS = 40;
  static final int MIN_COUNT = 1;
  static final int MAX_COUNT = 16;
  static final int DEFAULT_COUNT = 4;

  @FunctionalInterface
  interface TeleportFn {
    CompletableFuture<RTPResult> apply(UUID player, RtpTarget target);
  }

  public TestStressCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "count",
        new IntegerParameter("rtp.test.mutating", "number of mock players (1..16)", (uuid, s) -> true));
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
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
    return "rtp.test.mutating";
  }

  @Override
  public String description() {
    return "stress the RTP pipeline with mock players or selected online players";
  }

  private static BitCode registerCode(int id, String key, java.lang.System.Logger.Level severity) {
    return RtpBitCodes.PROBE.register(
        id,
        key,
        severity,
        BitTier.IBIT,
        OWNER,
        "",
        "Inspect the RTP stress pipeline result and rerun with bounded parameters.");
  }

  private static BitComponent registerComponent(String id, BitSideEffect sideEffect, String subsystem) {
    BitComponent component =
        new BitComponent(
            id,
            subsystem,
            BitTier.IBIT,
            sideEffect,
            COMPONENT_TIMEOUT_MS,
            () -> ProbeBitReport.forProbe(id).build());
    return component;
  }

  private void runMockStress(UUID callerId, Map<String, List<String>> parameterValues) {
    int count = clamp(parseFirst(parameterValues.get("count"), DEFAULT_COUNT), MIN_COUNT, MAX_COUNT);
    int iterations =
        clamp(parseFirst(parameterValues.get("iterations"), DEFAULT_ITERATIONS),
            MIN_ITERATIONS, MAX_ITERATIONS);
    List<String> regions = parameterValues.get("region");
    String regionName = regions == null || regions.isEmpty()
        ? MockProbeWorld.DEFAULT_REGION
        : regions.get(0);
    CompletableFuture<BitReport> reportFuture = new CompletableFuture<>();
    AtomicBoolean cancelled = new AtomicBoolean(false);
    if (RTPRuntime.scheduler() == null) {
      reportFuture.complete(
          ProbeBitReport.forProbe("stress")
              .check(STRESS_UNAVAILABLE, false, "stress.scheduler", "RTP.scheduler is unavailable.")
              .build());
      ProbeVerdict.record(COMPONENT, reportFuture, COMPONENT_TIMEOUT_MS)
          .thenAccept(result -> ProbeBitReport.emit(callerId, parameterValues, COMPONENT, result));
      return;
    }
    Runnable unregister =
        ActiveTestJobs.register(
            callerId,
            new ActiveTestJobs.Job("stress", () -> cancelled.set(true)));
    ProbeVerdict.record(COMPONENT, reportFuture, COMPONENT_TIMEOUT_MS)
        .whenComplete(
            (result, failure) -> {
              try {
                if (failure != null) {
                  RTP.log(Level.WARNING, "[RTP test/stress] BIT verdict failed", failure);
                  return;
                }
              if (containsFinding(result.report(), BitCodes.COMPONENT_TIMEOUT.key())) {
                cancelled.set(true);
              }
              ProbeBitReport.emit(callerId, parameterValues, COMPONENT, result);
              } finally {
                unregister.run();
              }
            });
    try {
      RTPRuntime.scheduler()
          .runTaskAsynchronously(
              () -> {
                try {
                  BitReport report =
                      runMockProbe(
                          regionName,
                          MockProbeWorld.resolve(regionName),
                          count,
                          iterations,
                          RTPAPI::teleport,
                          MOCK_AWAIT_MS,
                          MOCK_BUDGET_MS,
                          cancelled::get);
                  reportFuture.complete(report);
                  String summary =
                      "[RTP test/stress] mocked done: count="
                          + count
                          + " iterations="
                          + iterations
                          + " region="
                          + regionName;
                  RTP.log(Level.INFO, summary);
                  send(callerId, summary);
                } catch (Throwable failure) {
                  reportFuture.completeExceptionally(failure);
                  RTP.log(Level.WARNING, "[RTP test/stress] mocked probe failed", failure);
                }
              });
    } catch (Throwable failure) {
      reportFuture.completeExceptionally(failure);
    }
  }

  static BitReport runMockProbe(
      String regionName,
      @Nullable RTPWorld<?> world,
      int requestedCount,
      int requestedIterations,
      TeleportFn teleport,
      long awaitMillis,
      long budgetMillis,
      BooleanSupplier cancelled) {
    int count = clamp(requestedCount, MIN_COUNT, MAX_COUNT);
    int iterations = clamp(requestedIterations, MIN_ITERATIONS, MAX_ITERATIONS);
    if (world == null || regionName == null || regionName.isBlank()) {
      return ProbeBitReport.forProbe("stress")
          .check(STRESS_UNAVAILABLE, false, "stress.world", "The requested region has no loaded world.")
          .note(STRESS_MOCKED, "stress.mock", "No real account was moved.")
          .build();
    }
    if (TeleportSessions.current() == null) {
      return ProbeBitReport.forProbe("stress")
          .check(STRESS_UNAVAILABLE, false, "stress.teleportSessions", "Teleport session state is unavailable.")
          .note(STRESS_MOCKED, "stress.mock", "No real account was moved.")
          .build();
    }

    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, budgetMillis));
    int rejected = 0;
    int setLocationMisses = 0;
    int processingLeaks = 0;
    int completed = 0;
    boolean timedOut = false;
    boolean wasCancelled = false;
    outer:
    for (int index = 0; index < count; index++) {
      if (cancelled.getAsBoolean()) {
        wasCancelled = true;
        break;
      }
      try (MockPlayerSession session = MockPlayerSession.open(MockProbeWorld.spawn(world))) {
        RuntimeMockRTPPlayer mock = session.player();
        for (int iteration = 0; iteration < iterations; iteration++) {
          if (cancelled.getAsBoolean()) {
            wasCancelled = true;
            break outer;
          }
          long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
          if (remainingMillis <= 0) {
            timedOut = true;
            break outer;
          }
          int setLocationCount = mock.setLocationCount();
          try {
            CompletableFuture<RTPResult> pending = teleport.apply(mock.uuid(), RtpTarget.region(regionName));
            if (pending == null) {
              rejected++;
              continue;
            }
            RTPResult result = pending.get(Math.max(1L, Math.min(awaitMillis, remainingMillis)), TimeUnit.MILLISECONDS);
            if (result == null || !result.isSuccess()) {
              rejected++;
            } else {
              completed++;
            }
          } catch (TimeoutException timeout) {
            timedOut = true;
            mock.setOnline(false);
            try {
              RTPAPI.cancel(mock.uuid());
            } catch (RuntimeException cancelFailure) {
              RTP.log(Level.WARNING, "[RTP test/stress] mock cancellation failed for " + mock.uuid(), cancelFailure);
            }
            break outer;
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            timedOut = true;
            break outer;
          } catch (ExecutionException | RuntimeException failure) {
            rejected++;
          }
          if (mock.setLocationCount() == setLocationCount) setLocationMisses++;
          TeleportSessions sessions = TeleportSessions.current();
          if (sessions != null && sessions.processingPlayers.contains(mock.uuid())) processingLeaks++;
        }
      } catch (RuntimeException failure) {
        rejected++;
      }
    }

    ProbeBitReport.Builder report = ProbeBitReport.forProbe("stress");
    report.check(STRESS_REJECTED, rejected == 0, "stress.teleport", rejected + " mock teleport request(s) failed.");
    report.check(STRESS_SET_LOCATION, setLocationMisses == 0, "stress.setLocation", setLocationMisses + " request(s) did not reach setLocation.");
    report.check(STRESS_PROCESSING_LEAK, processingLeaks == 0, "stress.processingPlayers", processingLeaks + " mock UUID(s) remained in processingPlayers.");
    if (timedOut) {
      return BitReport.of(
          List.of(
              BitFinding.of(
                  BitCodes.COMPONENT_TIMEOUT,
                  "rtp test stress",
                  "stress.timeout",
                  "Mock teleport budget expired after " + completed + " completed request(s).",
                  "all bounded mock requests complete",
                  BitCodes.COMPONENT_TIMEOUT.remediation()),
              BitFinding.of(
                  STRESS_MOCKED,
                  "rtp test stress",
                  "stress.mock",
                  "Simulated with " + count + " mock player(s); no real account was moved.",
                  "mock-only result",
                  STRESS_MOCKED.remediation())));
    }
    if (wasCancelled) report.note(STRESS_PLAYER_CANCELLED, "stress.cancelled", "Mock stress was cancelled.");
    report.note(
        STRESS_MOCKED,
        "stress.mock",
        "Simulated with " + count + " mock player(s) and " + completed + " completed request(s); no real account was moved.");
    return report.build();
  }

  private static boolean containsFinding(BitReport report, String code) {
    return report.findings().stream().anyMatch(finding -> finding.code().equals(code));
  }

  private static void send(UUID callerId, String message) {
    if (RTPRuntime.serverAccessor() != null
        && !callerId.equals(RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, message);
    }
  }

  private void completePlayerReport(UUID callerId, Map<String, List<String>> args, String detail) {
    BitReport report =
        ProbeBitReport.forProbe("stress-player")
            .check(STRESS_PLAYER_FAILURES, false, "stress-player.setup", detail)
            .build();
    ProbeVerdict.record(
            PLAYER_COMPONENT,
            CompletableFuture.completedFuture(report),
            COMPONENT_TIMEOUT_MS)
        .thenAccept(result -> ProbeBitReport.emit(callerId, args, PLAYER_COMPONENT, result));
  }

  private static BitReport playerReport(int failures, @Nullable String cancellation) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("stress-player");
    report.check(
        STRESS_PLAYER_FAILURES,
        failures == 0,
        "stress-player.iterations",
        failures + " player iteration(s) failed or referenced an unknown player.");
    if (cancellation != null) {
      report.note(STRESS_PLAYER_CANCELLED, "stress-player.cancelled", cancellation);
    }
    return report.build();
  }

  private static void cancelTimer(@Nullable Object handle) {
    if (handle == null || RTPRuntime.scheduler() == null) return;
    try {
      RTPRuntime.scheduler().cancelTask(handle);
    } catch (Throwable failure) {
      RTP.log(Level.WARNING, "[RTP test/stress] timer cancellation failed", failure);
    }
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<String> playerNames = parameterValues.get("player");
    if (playerNames == null || playerNames.isEmpty()) {
      runMockStress(callerId, parameterValues);
      return true;
    }

    // Resolve players up-front so typos fail loud (S-004).
    List<RTPPlayer> targets = new ArrayList<>(playerNames.size());
    int unresolvedPlayers = 0;
    if (RTPRuntime.serverAccessor() == null) {
      completePlayerReport(callerId, parameterValues, "RTP.serverAccessor is unavailable.");
      return true;
    }
    for (String name : playerNames) {
      RTPPlayer p = RTPRuntime.serverAccessor().getPlayer(name);
      if (p == null) {
        unresolvedPlayers++;
        String msg = "&c[RTP test/stress] unknown player: " + name;
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTPRuntime.serverAccessor().sendMessage(callerId, msg);
        }
        RTP.log(Level.WARNING, msg);
        continue;
      }
      targets.add(p);
    }

    if (targets.isEmpty()) {
      RTP.log(
          Level.WARNING, "[RTP test/stress] no resolvable players; aborting run for " + callerId);
      completePlayerReport(callerId, parameterValues, "No requested online player could be resolved.");
      return true;
    }
    final int unresolvedCount = unresolvedPlayers;

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
        RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      completePlayerReport(callerId, parameterValues, "Root RTP command is unavailable.");
      return true;
    }

    final AtomicInteger completed = new AtomicInteger(0);
    final AtomicInteger failures = new AtomicInteger(0);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final CompletableFuture<BitReport> playerReportFuture = new CompletableFuture<>();
    final Object[] handleHolder = new Object[1];
    final Runnable[] unregisterHolder = new Runnable[1];
    ProbeVerdict.record(PLAYER_COMPONENT, playerReportFuture, COMPONENT_TIMEOUT_MS)
        .thenAccept(
            result -> {
              if (containsFinding(result.report(), BitCodes.COMPONENT_TIMEOUT.key())) {
                cancelled.set(true);
                cancelTimer(handleHolder[0]);
                send(callerId, "[RTP test/stress] TIMEOUT after " + COMPONENT_TIMEOUT_MS + "ms");
                Runnable unregister = unregisterHolder[0];
                if (unregister != null) unregister.run();
              }
              ProbeBitReport.emit(callerId, parameterValues, PLAYER_COMPONENT, result);
            });

    String startMsg =
        "&a[RTP test/stress] starting: "
            + iterations
            + " iterations x "
            + targets.size()
            + " player(s), every "
            + intervalTicks
            + " ticks";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, startMsg);
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
            send(callerId, summary);
            RTP.log(Level.INFO, summary);
            Object h = handleHolder[0];
            if (h != null) {
              try {
                RTPRuntime.scheduler().cancelTask(h);
              } catch (Throwable ignored) {
                // Schedulers without a public cancel API will let the timer
                // decay naturally; we stop rescheduling below.
              }
            }
            Runnable unreg = unregisterHolder[0];
            if (unreg != null) unreg.run();
            playerReportFuture.complete(
                playerReport(
                    failures.get() + unresolvedCount,
                    null));
          }
        };

    Runnable canceller =
        () -> {
          if (!cancelled.compareAndSet(false, true)) return;
          cancelTimer(handleHolder[0]);
          String msg =
              "[RTP test/stress] cancelled after "
                  + completed.get()
                  + "/"
                  + iterations
                  + " iterations (failures="
                  + failures.get()
                  + ")";
          send(callerId, msg);
          RTP.log(Level.INFO, msg);
          playerReportFuture.complete(playerReport(failures.get(), "Cancelled by operator."));
        };
    unregisterHolder[0] =
        ActiveTestJobs.register(callerId, new ActiveTestJobs.Job("stress", canceller));
    if (RTPRuntime.scheduler() == null) {
      playerReportFuture.completeExceptionally(new IllegalStateException("RTP.scheduler is unavailable"));
      unregisterHolder[0].run();
      return true;
    }
    try {
      handleHolder[0] = RTPRuntime.scheduler().runTaskTimerAsynchronously(
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
    } catch (Throwable failure) {
      unregisterHolder[0].run();
      playerReportFuture.completeExceptionally(failure);
      RTP.log(Level.WARNING, "[RTP test/stress] timer dispatch failed", failure);
    }

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
      return Integer.parseInt(values.get(0));
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

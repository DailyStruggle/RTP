package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test scheduler} - probes {@link RTPScheduler} tiers (async, primary, region)
 * and reports dispatch latency.
 * Complies with REQ-RTP-S-004 (explicit feedback) and REQ-RTP-S-005 (non-blocking).
 */
public class TestSchedulerCmd extends BaseRTPCmdImpl {

  public static final BitCode UNAVAILABLE = registerCode(3401, "TEST_SCHEDULER_UNAVAILABLE");
  public static final BitCode ASYNC = registerCode(3402, "TEST_SCHEDULER_ASYNC");
  public static final BitCode PRIMARY = registerCode(3403, "TEST_SCHEDULER_PRIMARY");
  public static final BitCode REGION = registerCode(3404, "TEST_SCHEDULER_REGION");
  /** Per-probe wall-clock timeout. Kept generous to avoid false positives on heavily loaded servers. */
  static final long PROBE_TIMEOUT_MS = 2_000L;
  public static final BitComponent COMPONENT = registerComponent();

  public static final class Result {
    public boolean available;
    public boolean async;
    public boolean primary;
    public boolean region = true;
  }

  public TestSchedulerCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
  }

  @Override
  public String name() {
    return "scheduler";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "probe each RTPScheduler tier and report dispatch latency";
  }

  private static final java.util.concurrent.atomic.AtomicBoolean isProcessing = new java.util.concurrent.atomic.AtomicBoolean(false);

  @Override
  @SuppressWarnings("java:S3516") // Method returns boolean per CommandsAPICommand contract; false on guard failure, true on async dispatch
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (isProcessing.get()) return false;
    isProcessing.set(true);
    try {
      RTPScheduler scheduler = RTPRuntime.scheduler();
      if (scheduler == null) {
        String msg = "&c[RTP test/scheduler] RTP.scheduler is null; core not yet loaded";
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTPRuntime.serverAccessor().sendMessage(callerId, msg);
        }
        RTPRuntime.log(Level.WARNING, msg);
        Result probe = new Result();
        BitResult bitResult =
            RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(probe)), Runnable::run);
        ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
        return false;
      }

      // Snapshot the caller's location on the calling thread (the command
      // dispatch thread may be the main thread; we deliberately do *not*
      // call getLocation from an async context later). From the console
      // this is null and the region tier is skipped.
      RTPLocation callerLoc = null;
      try {
        RTPPlayer caller = RTPRuntime.serverAccessor().getPlayer(callerId);
        if (caller != null) callerLoc = caller.getLocation();
      } catch (Throwable ignored) {
        // region probe skipped below
      }
      final RTPLocation loc = callerLoc;

      // Run the probes on the async tier so the calling thread (which may
      // be the main server thread on Paper/Spigot) is never blocked
      // waiting for `fut.get(...)`. S-005 is thereby preserved.
      //
      // Register with ActiveTestJobs so `rtp test full`'s
      // dispatchNoArgAndWait blocks on us; otherwise the next stage's
      // output interleaves with the per-tier probe lines.
      final Runnable[] hookHolder = new Runnable[1];
      hookHolder[0] = ActiveTestJobs.register(
          callerId, new ActiveTestJobs.Job("scheduler", () -> { /* no cancel handle */ }));
      scheduler.runTaskAsynchronously(
          () -> {
            try {
              BitResult bitResult =
                  RtpBit.RUNNER.run(
                      COMPONENT.withCheck(
                          () -> toBitReport(runProbe(callerId, scheduler, loc, true))),
                      Runnable::run);
              ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
            } finally {
              if (hookHolder[0] != null) hookHolder[0].run();
            }
          });
      return true;
    } finally {
      isProcessing.set(false);
    }
  }

  private static BitCode registerCode(int id, String key) {
    return RtpBitCodes.TEST.register(
        id,
        key,
        java.lang.System.Logger.Level.ERROR,
        BitTier.IBIT,
        "test.scheduler",
        "",
        "Verify the platform scheduler can dispatch this execution tier.");
  }

  private static BitComponent registerComponent() {
    BitComponent component =
        new BitComponent(
            "scheduler",
            "test.scheduler",
            BitTier.IBIT,
            BitSideEffect.SAFE,
            15_000,
            () -> toBitReport(runProbe(RTPAPI.serverId, RTPRuntime.scheduler(), null, false)));
    RtpBit.RUNNER.register(component);
    return component;
  }

  static BitReport toBitReport(Result result) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("scheduler");
    report.check(UNAVAILABLE, result.available, "scheduler", "RTP.scheduler is unavailable.");
    if (result.available) {
      report.check(ASYNC, result.async, "scheduler.async", "Async scheduler dispatch did not complete.");
      report.check(PRIMARY, result.primary, "scheduler.primary", "Primary scheduler dispatch did not complete.");
      report.check(REGION, result.region, "scheduler.region", "Region scheduler dispatch did not complete.");
    }
    return report.build();
  }

  static Result runProbe(UUID callerId, RTPScheduler scheduler, RTPLocation loc, boolean emit) {
    Result result = new Result();
    if (scheduler == null) return result;
    result.available = true;
    if (emit) report(callerId, "[RTP test/scheduler] begin");
    result.async = probeAsync(callerId, scheduler, emit);
    result.primary = probePrimary(callerId, scheduler, emit);
    if (loc != null) {
      result.region = probeRegion(callerId, scheduler, loc, emit);
    } else if (emit) {
      report(callerId, "[RTP test/scheduler] region: skipped (no caller location)");
    }
    if (emit) report(callerId, "[RTP test/scheduler] end");
    return result;
  }

  private static boolean probeAsync(UUID callerId, RTPScheduler scheduler, boolean emit) {
    CompletableFuture<Long> fut = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      scheduler.runTaskAsynchronously(() -> fut.complete(System.nanoTime()));
    } catch (Throwable t) {
      reportTierFailure(callerId, "async", t, emit);
      return false;
    }
    return awaitAndReport(callerId, "async", fut, t0, emit);
  }

  private static boolean probePrimary(UUID callerId, RTPScheduler scheduler, boolean emit) {
    CompletableFuture<Long> fut = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      scheduler.runTask(() -> fut.complete(System.nanoTime()));
    } catch (Throwable t) {
      reportTierFailure(callerId, "primary", t, emit);
      return false;
    }
    return awaitAndReport(callerId, "primary", fut, t0, emit);
  }

  private static boolean probeRegion(UUID callerId, RTPScheduler scheduler, RTPLocation loc, boolean emit) {
    CompletableFuture<Long> fut = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      // The single-arg runTask(RTPLocation, Runnable) dispatches to the
      // Folia region owning `loc` (or to the primary thread on non-Folia
      // platforms). On Folia this is the only tier whose latency is
      // sensitive to region-thread saturation.
      scheduler.runTask(loc, () -> fut.complete(System.nanoTime()));
    } catch (Throwable t) {
      reportTierFailure(callerId, "region", t, emit);
      return false;
    }
    return awaitAndReport(callerId, "region", fut, t0, emit);
  }

  private static boolean awaitAndReport(
      UUID callerId, String tier, CompletableFuture<Long> fut, long t0, boolean emit) {
    try {
      long t1 = fut.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      long micros = (t1 - t0) / 1_000L;
      if (emit) report(callerId, "[RTP test/scheduler] " + tier + ": ok latency=" + micros + "us");
      return true;
    } catch (TimeoutException te) {
      String msg =
          "[RTP test/scheduler] " + tier + ": TIMEOUT after " + PROBE_TIMEOUT_MS + "ms";
      if (emit) {
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTPRuntime.serverAccessor().sendMessage(callerId, msg);
        }
        RTPRuntime.log(Level.WARNING, msg);
      }
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      reportTierFailure(callerId, tier, e, emit);
      return false;
    } catch (Throwable t) {
      reportTierFailure(callerId, tier, t, emit);
      return false;
    }
  }

  private static void reportTierFailure(UUID callerId, String tier, Throwable t, boolean emit) {
    String msg =
        "[RTP test/scheduler] " + tier + ": FAILED (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
    if (emit) {
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      }
      RTPRuntime.log(Level.WARNING, msg, t);
    }
  }

  private static void report(UUID callerId, String msg) {
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
    }
    RTPRuntime.log(Level.INFO, msg);
  }
}

package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test scheduler} &mdash; actively probes each tier exposed by
 * {@link RTPScheduler} (async, primary/sync, region) and reports
 * round-trip dispatch latency. Complements the passive {@code rtp test
 * platform} probe documented in {@code RUNTIME_TEST_SUITE_PLAN.md §4}:
 * {@code platform} reports <i>what</i> is wired in, {@code scheduler}
 * proves each tier actually dispatches &mdash; which matters on Folia,
 * where a mis-routed task can stall a region thread silently.
 *
 * <p>The async path is always probed. The primary and region paths are
 * probed when a player caller is available (so we have a location for
 * region routing); from the console we skip the region probe and log an
 * INFO note rather than failing.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> &mdash; every tier outcome (success, timeout,
 *       unsupported) produces a player-visible line and a
 *       {@link Level#INFO} / {@link Level#WARNING} log entry.</li>
 *   <li><b>REQ-RTP-S-005</b> &mdash; the probe does not touch chunks; it
 *       dispatches an empty {@link Runnable} to each scheduler.</li>
 * </ul>
 */
public class TestSchedulerCmd extends BaseRTPCmdImpl {

  /** Per-probe wall-clock timeout. Kept generous to avoid false positives on heavily loaded servers. */
  static final long PROBE_TIMEOUT_MS = 2_000L;

  public TestSchedulerCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
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
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (isProcessing.get()) return true;
    isProcessing.set(true);
    try {
      RTPScheduler scheduler = RTP.scheduler;
      if (scheduler == null) {
        String msg = "&c[RTP test/scheduler] RTP.scheduler is null; core not yet loaded";
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTP.serverAccessor.sendMessage(callerId, msg);
        }
        RTP.log(Level.WARNING, msg);
        return true;
      }

      // Snapshot the caller's location on the calling thread (the command
      // dispatch thread may be the main thread; we deliberately do *not*
      // call getLocation from an async context later). From the console
      // this is null and the region tier is skipped.
      RTPLocation callerLoc = null;
      try {
        RTPPlayer caller = RTP.serverAccessor.getPlayer(callerId);
        if (caller != null) callerLoc = caller.getLocation();
      } catch (Throwable ignored) {
        // region probe skipped below
      }
      final RTPLocation loc = callerLoc;

      // Run the probes on the async tier so the calling thread (which may
      // be the main server thread on Paper/Spigot) is never blocked
      // waiting for `fut.get(...)`. S-005 is thereby preserved.
      scheduler.runTaskAsynchronously(
          () -> {
            report(callerId, "[RTP test/scheduler] begin");
            probeAsync(callerId, scheduler);
            probePrimary(callerId, scheduler);
            if (loc != null) {
              probeRegion(callerId, scheduler, loc);
            } else {
              report(callerId, "[RTP test/scheduler] region: skipped (no caller location)");
            }
            report(callerId, "[RTP test/scheduler] end");
          });
      return true;
    } finally {
      isProcessing.set(false);
    }
  }

  private void probeAsync(UUID callerId, RTPScheduler scheduler) {
    CompletableFuture<Long> fut = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      scheduler.runTaskAsynchronously(() -> fut.complete(System.nanoTime()));
    } catch (Throwable t) {
      reportTierFailure(callerId, "async", t);
      return;
    }
    awaitAndReport(callerId, "async", fut, t0);
  }

  private void probePrimary(UUID callerId, RTPScheduler scheduler) {
    CompletableFuture<Long> fut = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      scheduler.runTask(() -> fut.complete(System.nanoTime()));
    } catch (Throwable t) {
      reportTierFailure(callerId, "primary", t);
      return;
    }
    awaitAndReport(callerId, "primary", fut, t0);
  }

  private void probeRegion(UUID callerId, RTPScheduler scheduler, RTPLocation loc) {
    CompletableFuture<Long> fut = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      // The single-arg runTask(RTPLocation, Runnable) dispatches to the
      // Folia region owning `loc` (or to the primary thread on non-Folia
      // platforms). On Folia this is the only tier whose latency is
      // sensitive to region-thread saturation.
      scheduler.runTask(loc, () -> fut.complete(System.nanoTime()));
    } catch (Throwable t) {
      reportTierFailure(callerId, "region", t);
      return;
    }
    awaitAndReport(callerId, "region", fut, t0);
  }

  private void awaitAndReport(UUID callerId, String tier, CompletableFuture<Long> fut, long t0) {
    try {
      long t1 = fut.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      long micros = (t1 - t0) / 1_000L;
      report(callerId, "[RTP test/scheduler] " + tier + ": ok latency=" + micros + "us");
    } catch (TimeoutException te) {
      String msg =
          "[RTP test/scheduler] " + tier + ": TIMEOUT after " + PROBE_TIMEOUT_MS + "ms";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
    } catch (Throwable t) {
      reportTierFailure(callerId, tier, t);
    }
  }

  private void reportTierFailure(UUID callerId, String tier, Throwable t) {
    String msg =
        "[RTP test/scheduler] " + tier + ": FAILED (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
    RTP.serverAccessor.sendMessage(callerId, msg);
    RTP.log(Level.WARNING, msg, t);
  }

  private void report(UUID callerId, String msg) {
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, msg);
    }
    RTP.log(Level.INFO, msg);
  }
}

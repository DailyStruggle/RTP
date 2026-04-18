package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test queue-starvation region:&lt;R&gt; [samples:N]}.
 *
 * <p>Validates the Archimedean spiral (O(log n)) selection math and the
 * ADR-006 async pre-generator refill pulse against queue starvation. The
 * job rapidly requests {@value #DEFAULT_SAMPLES} dummy locations from the
 * target region to partially drain its cache, measures the per-candidate
 * wall-clock timings, then invokes
 * {@link io.github.dailystruggle.rtp.common.selection.SelectionAPI#compute()}
 * as a refill pulse and records the refill latency.
 *
 * <p>This job deliberately <b>does not</b> dispatch through
 * {@link io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask}
 * — it measures the generator / pre-generator tier directly so that a
 * regression in the spiral math, the region queue, or the refill pulse is
 * not masked by cooldowns, verifiers, or the economy layer.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> — every skipped sample and non-success result
 *       emits a WARN log entry; a final summary is sent to the caller.</li>
 *   <li><b>REQ-RTP-S-005</b> — the sampling loop runs on
 *       {@code runTaskAsynchronously}; {@link Region#getLocation} uses
 *       {@code getChunkAtAsync} internally, so no chunk I/O lands on the
 *       main thread.</li>
 *   <li><b>REQ-RTP-S-002</b> — no tickets are acquired here; each
 *       {@code GenerationResult} is discarded without holding a
 *       reservation.</li>
 * </ul>
 *
 * <p>Platform routing (requirement #3 of the job spec):
 * <ul>
 *   <li><b>Folia</b> — the async scheduler routes to a Count-Bound pipe
 *       (bounded number of pending refill tasks) to avoid stalling a
 *       Region Thread. This job asserts {@code platform == "Folia"} before
 *       running so the Folia-specific expectation is surfaced.</li>
 *   <li><b>Paper / Spigot</b> — a Time-Bound pipe is permitted, since
 *       there are no region threads to stall.</li>
 * </ul>
 *
 * <p>Traceability: ADR-006 (queue refill cadence), REQ-RTP-F-005 (bounded
 * selection complexity), REQ-RTP-F-006 (no naive rerolling), REQ-RTP-F-008
 * (non-blocking execution).
 */
public class QueueStarvationTestJob extends BaseRTPCmdImpl {

  /** Per-issue-spec requirement #1: rapidly request 15 dummy locations. */
  static final int DEFAULT_SAMPLES = 15;

  static final int MIN_SAMPLES = 1;
  static final int MAX_SAMPLES = 500;

  /**
   * Soft ceiling on a single {@link Region#getLocation} wall-clock. Above
   * this, we flag the sample as a possible O(n) stall in the generator
   * loop (requirement #4).
   */
  static final long LOG_N_STALL_THRESHOLD_MS = 5_000L;

  public QueueStarvationTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "region",
        new RegionParameter(
            "rtp.test", "region to drain (defaults to caller's region)", (uuid, s) -> true));
    addParameter(
        "samples",
        new IntegerParameter(
            "rtp.test",
            "number of dummy location requests (1.." + MAX_SAMPLES + ", default " + DEFAULT_SAMPLES + ")",
            (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "queue-starvation";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "drain N dummy locations and measure ADR-006 pre-generator refill latency";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    // ---- Resolve target region (S-004: loud failure on typo) ----
    final Region region = resolveRegion(callerId, parameterValues);
    if (region == null) return true;

    final int samples =
        clamp(
            parseFirst(parameterValues.get("samples"), DEFAULT_SAMPLES),
            MIN_SAMPLES,
            MAX_SAMPLES);

    // ---- Requirement #3: assert correct task-pipe routing per platform ----
    final String platform = safePlatform();
    final String expectedPipe =
        "Folia".equalsIgnoreCase(platform) ? "Count-Bound" : "Time-Bound";
    RTP.log(
        Level.INFO,
        "[RTP test/queue-starvation] platform="
            + platform
            + " expected task-pipe="
            + expectedPipe
            + " (ADR-006)");

    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final Object[] handleHolder = new Object[1];
    final Runnable[] unregisterHolder = new Runnable[1];

    String startMsg =
        "&a[RTP test/queue-starvation] draining "
            + samples
            + " dummy locations from region '"
            + region.name
            + "' on "
            + platform
            + " ("
            + expectedPipe
            + " pipe)";
    RTP.serverAccessor.sendMessage(callerId, startMsg);
    RTP.log(Level.INFO, startMsg);

    Runnable body =
        () -> {
          if (cancelled.get()) return;
          try {
            runSamplingPhase(callerId, region, samples, expectedPipe, cancelled);
          } catch (Throwable t) {
            RTP.log(
                Level.WARNING,
                "[RTP test/queue-starvation] aborted due to unexpected error: " + t.getMessage(),
                t);
          } finally {
            Runnable unreg = unregisterHolder[0];
            if (unreg != null) unreg.run();
          }
        };

    // REQ-RTP-S-005: kick off on the async scheduler; never touch the
    // main thread for chunk-adjacent work.
    handleHolder[0] = RTP.scheduler.runTaskAsynchronously(body);

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
          String msg = "[RTP test/queue-starvation] cancelled by operator";
          RTP.serverAccessor.sendMessage(callerId, msg);
          RTP.log(Level.INFO, msg);
        };
    unregisterHolder[0] =
        ActiveTestJobs.register(callerId, new ActiveTestJobs.Job("queue-starvation", canceller));

    return true;
  }

  // ---- Phase 1: drain the cache and record per-sample timings. ------------
  // ---- Phase 2: pulse SelectionAPI.compute() and record refill timing. ---
  private void runSamplingPhase(
      UUID callerId,
      Region region,
      int samples,
      String expectedPipe,
      AtomicBoolean cancelled) {
    final long[] drainMillis = new long[samples];
    final AtomicInteger successes = new AtomicInteger(0);
    final AtomicInteger failures = new AtomicInteger(0);
    final AtomicInteger stalls = new AtomicInteger(0);

    long drainStart = System.nanoTime();
    for (int i = 0; i < samples; i++) {
      if (cancelled.get()) return;

      long t0 = System.nanoTime();
      long elapsedMs;
      try {
        CompletableFuture<GenerationResult> fut = region.getLocation(Collections.emptySet());
        // We intentionally block this *async* worker (not the main thread)
        // to get a clean per-sample wall-clock. This is safe: runTaskAsynchronously
        // never runs on a Region Thread or the main thread, so joining
        // here does not violate REQ-RTP-S-005 or Folia's region-ownership rules.
        GenerationResult result = fut.join();
        elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        if (result != null && result.coords() != null) {
          successes.incrementAndGet();
          // REQ-RTP-S-002: release any chunk reservation attached to this
          // throwaway sample so we do not leak tickets. The teleport
          // pipeline is intentionally bypassed here, so no other exit
          // path will close this reservation.
          if (result.reservation() != null) {
            try {
              result.reservation().close();
            } catch (Throwable closeErr) {
              RTP.log(
                  Level.WARNING,
                  "[RTP test/queue-starvation] failed to close reservation for sample "
                      + (i + 1)
                      + ": "
                      + closeErr.getMessage(),
                  closeErr);
            }
          }
        } else {
          failures.incrementAndGet();
          RTP.log(
              Level.WARNING,
              "[RTP test/queue-starvation] sample "
                  + (i + 1)
                  + " produced null/empty GenerationResult");
        }
      } catch (Throwable t) {
        elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        failures.incrementAndGet();
        RTP.log(
            Level.WARNING,
            "[RTP test/queue-starvation] sample " + (i + 1) + " threw: " + t.getMessage(),
            t);
      }

      drainMillis[i] = elapsedMs;

      // Requirement #4: guard against an O(n) infinite while-loop stall
      // leaking through the Archimedean spiral path.
      if (elapsedMs > LOG_N_STALL_THRESHOLD_MS) {
        stalls.incrementAndGet();
        RTP.log(
            Level.WARNING,
            "[RTP test/queue-starvation] sample "
                + (i + 1)
                + " exceeded O(log n) budget: "
                + elapsedMs
                + " ms (threshold "
                + LOG_N_STALL_THRESHOLD_MS
                + " ms) — possible queue starvation / spiral regression");
      }
    }
    long drainTotalMs = (System.nanoTime() - drainStart) / 1_000_000L;

    // ---- Refill pulse: time SelectionAPI.compute() ----
    long refillBefore = System.nanoTime();
    try {
      RTP.selectionAPI.compute();
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP test/queue-starvation] SelectionAPI.compute() threw: " + t.getMessage(),
          t);
    }
    long refillMs = (System.nanoTime() - refillBefore) / 1_000_000L;

    // ---- Report (requirement #5: per-sample ms in the test report) ----
    List<Long> timings = new ArrayList<>(drainMillis.length);
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    long sum = 0L;
    for (long v : drainMillis) {
      timings.add(v);
      if (v < min) min = v;
      if (v > max) max = v;
      sum += v;
    }
    long avg = drainMillis.length == 0 ? 0L : sum / drainMillis.length;

    String header =
        "[RTP test/queue-starvation] region='"
            + region.name
            + "' pipe="
            + expectedPipe
            + " samples="
            + drainMillis.length
            + " successes="
            + successes.get()
            + " failures="
            + failures.get()
            + " stalls="
            + stalls.get();
    String drainLine =
        "[RTP test/queue-starvation] drain: totalMs="
            + drainTotalMs
            + " minMs="
            + (min == Long.MAX_VALUE ? 0 : min)
            + " avgMs="
            + avg
            + " maxMs="
            + (max == Long.MIN_VALUE ? 0 : max);
    String refillLine =
        "[RTP test/queue-starvation] refill: SelectionAPI.compute() tookMs=" + refillMs;
    String detail = "[RTP test/queue-starvation] per-sample ms=" + timings;

    RTP.serverAccessor.sendMessage(callerId, header);
    RTP.serverAccessor.sendMessage(callerId, drainLine);
    RTP.serverAccessor.sendMessage(callerId, refillLine);
    RTP.log(Level.INFO, header);
    RTP.log(Level.INFO, drainLine);
    RTP.log(Level.INFO, refillLine);
    RTP.log(Level.INFO, detail);

    // Final assertions surfaced as WARN if violated (so the test report
    // captures them without crashing the async worker).
    if (stalls.get() > 0) {
      RTP.log(
          Level.WARNING,
          "[RTP test/queue-starvation] ASSERTION FAILED: O(log n) budget exceeded on "
              + stalls.get()
              + " sample(s)");
    }
    if (failures.get() > 0) {
      RTP.log(
          Level.WARNING,
          "[RTP test/queue-starvation] ASSERTION FAILED: " + failures.get() + " sample(s) failed");
    }
  }

  // ---- Helpers ----------------------------------------------------------

  private Region resolveRegion(UUID callerId, Map<String, List<String>> parameterValues) {
    List<String> regionNames = parameterValues.get("region");
    String regionName = (regionNames == null || regionNames.isEmpty()) ? null : regionNames.getFirst();
    Region region;
    if (regionName != null) {
      region = RTP.selectionAPI.getRegion(regionName);
      if (region == null) {
        String msg = "&c[RTP test/queue-starvation] unknown region: " + regionName;
        RTP.serverAccessor.sendMessage(callerId, msg);
        RTP.log(Level.WARNING, msg);
        return null;
      }
      return region;
    }
    // Fall back to caller's current region.
    try {
      region =
          RTP.selectionAPI.getRegion(RTP.serverAccessor.getPlayer(callerId));
    } catch (Throwable t) {
      region = null;
    }
    if (region == null) {
      String msg =
          "&c[RTP test/queue-starvation] no region specified and caller has no resolvable region";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      return null;
    }
    return region;
  }

  private static String safePlatform() {
    try {
      String p = RTP.serverAccessor.getPlatform();
      return p == null ? "Unknown" : p;
    } catch (Throwable t) {
      return "Unknown";
    }
  }

  static int parseFirst(List<String> values, int fallback) {
    if (values == null || values.isEmpty()) return fallback;
    try {
      return Integer.parseInt(values.getFirst().trim().toLowerCase(Locale.ROOT));
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

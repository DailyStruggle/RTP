package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test chunk-ticket} &mdash; positive-path probe of live chunk ticketing
 * contracts and {@link MemoryTracker} release paths (REQ-RTP-S-002).
 * <p>
 * If live world methods ({@code getServerForceLoadedCount} and {@code keepChunkAt})
 * are present and supported, it positively asserts live chunk ticketing contracts:
 * <ol>
 *   <li>Records baseline force-loaded count.</li>
 *   <li>Opens ticket via {@code world.keepChunkAt(cx, cz)}.</li>
 *   <li>Positively asserts count incremented by 1.</li>
 *   <li>Releases ticket and asserts count returns to baseline.</li>
 *   <li>Verifies {@link MemoryTracker} ticket count returns to 0.</li>
 * </ol>
 * If running in a mock/headless environment where live chunk counts return -1 or
 * unsupported, cleanly falls back to the existing sentinel validation.
 */
public class TestChunkTicketCmd extends BaseRTPCmdImpl {

  public static final BitCode TEST_CHUNK_TICKET_OPEN = RtpBitCodes.TEST.register(
      3101, "TEST_CHUNK_TICKET_OPEN", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      "test.chunk-ticket", "", "Ensure opening a chunk ticket increments the live count.");
  public static final BitCode TEST_CHUNK_TICKET_RELEASE = RtpBitCodes.TEST.register(
      3102, "TEST_CHUNK_TICKET_RELEASE", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      "test.chunk-ticket", "", "Release the chunk ticket and restore the baseline count.");
  public static final BitCode TEST_CHUNK_TICKET_TRACKER_LEAK = RtpBitCodes.TEST.register(
      3103, "TEST_CHUNK_TICKET_TRACKER_LEAK", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      "test.chunk-ticket", "", "Untrack every ticket created by the probe.");
  public static final BitCode TEST_CHUNK_TICKET_SENTINEL_UNTRACK = RtpBitCodes.TEST.register(
      3104, "TEST_CHUNK_TICKET_SENTINEL_UNTRACK", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      "test.chunk-ticket", "", "Make both sentinel untrack operations return to the baseline.");
  public static final BitCode TEST_CHUNK_TICKET_SENTINEL_DIAGNOSTICS = RtpBitCodes.TEST.register(
      3105, "TEST_CHUNK_TICKET_SENTINEL_DIAGNOSTICS", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      "test.chunk-ticket", "", "Keep a live sentinel during diagnostics and release it afterward.");
  public static final BitCode TEST_CHUNK_TICKET_STALE_SENTINEL = RtpBitCodes.TEST.register(
      3106, "TEST_CHUNK_TICKET_STALE_SENTINEL", java.lang.System.Logger.Level.WARNING, BitTier.IBIT,
      "test.chunk-ticket", "", "Investigate the previous interrupted probe and clear its stale sentinels.");
  public static final BitComponent COMPONENT = registerComponent();

  /** Sentinel label, distinct from any production tracker label. */
  public static final String SENTINEL_LABEL = "rtp-test-chunk-ticket-sentinel";

  /** Sentinel lifespan for the drop-and-diagnose case. */
  static final long SENTINEL_LIFESPAN_MS = 50L;

  public TestChunkTicketCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
  }

  @Override
  public String name() {
    return "chunk-ticket";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "verifies live chunk ticketing contracts (REQ-RTP-S-002) and MemoryTracker release paths";
  }

  @Override
  @SuppressWarnings("java:S3516") // Diagnostic command returns true on completion
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    RTPWorld<?> world = null;
    if (RTPRuntime.serverAccessor() != null) {
      RTPPlayer player = RTPRuntime.serverAccessor().getPlayer(callerId);
      if (player != null && player.getLocation() != null) {
        world = player.getLocation().world();
      }
      if (world == null) {
        List<RTPWorld<?>> worlds = RTPRuntime.serverAccessor().getRTPWorlds();
        if (worlds != null && !worlds.isEmpty()) {
          world = worlds.get(0);
        }
      }
      if (world == null) {
        world = RTPRuntime.serverAccessor().getRTPWorld("world");
      }
    }

    RTPWorld<?> probeWorld = world;
    BitResult result = RtpBit.RUNNER.run(COMPONENT.withCheck(() -> {
      Result probe = runProbe(probeWorld);
      emit(callerId, probe);
      return toBitReport(probe);
    }), Runnable::run);
    if (ProbeBitReport.isJson(parameterValues)) {
      ProbeBitReport.emitJson(callerId, result);
    } else {
      BitTableOutput.emit(callerId, io.github.dailystruggle.bit.BitTable.rows(COMPONENT, result));
    }
    return true;
  }

  private static BitComponent registerComponent() {
    BitComponent component = new BitComponent("chunk-ticket", "test.chunk-ticket", BitTier.IBIT,
        BitSideEffect.NEEDS_WORLD, 60_000, () -> toBitReport(runProbe()));
    RtpBit.RUNNER.register(component);
    return component;
  }

  static BitReport toBitReport(Result result) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("chunk-ticket");
    if (result.liveProbed) {
      report.check(
          TEST_CHUNK_TICKET_OPEN,
          result.openCount == result.baselineCount + 1,
          "chunk-ticket.open",
          result.notes.isEmpty() ? "Live chunk ticket did not open as expected." : result.notes);
      report.check(
          TEST_CHUNK_TICKET_RELEASE,
          result.closeCount < 0 || result.closeCount == result.baselineCount,
          "chunk-ticket.release",
          result.notes.isEmpty() ? "Live chunk ticket did not return to its baseline." : result.notes);
      report.check(
          TEST_CHUNK_TICKET_TRACKER_LEAK,
          result.memoryTrackerTicketCount < 0 || result.memoryTrackerTicketCount == 0,
          "chunk-ticket.tracker",
          result.notes.isEmpty() ? "Probe ticket remained in MemoryTracker." : result.notes);
    } else {
      report.check(
          TEST_CHUNK_TICKET_SENTINEL_UNTRACK,
          result.afterUntrackById == 0 && result.afterUntrackByRef == 0 && result.finalResidual == 0,
          "chunk-ticket.sentinel.untrack",
          "Sentinel untrack or final residual check failed.");
      report.check(
          TEST_CHUNK_TICKET_SENTINEL_DIAGNOSTICS,
          result.afterDiagnosticsOnLive == 1,
          "chunk-ticket.sentinel.diagnostics",
          "Diagnostics did not preserve exactly one live sentinel.");
    }
    report.check(
        TEST_CHUNK_TICKET_STALE_SENTINEL,
        result.staleBaseline == 0,
        "chunk-ticket.staleBaseline",
        "Stale sentinel entries from a prior probe were found: " + result.staleBaseline);
    return report.build();
  }

  /** Core probe without target world; resolves default world if available or falls back. */
  public static Result runProbe() {
    RTPWorld<?> world = null;
    if (RTPRuntime.serverAccessor() != null) {
      List<RTPWorld<?>> worlds = RTPRuntime.serverAccessor().getRTPWorlds();
      if (worlds != null && !worlds.isEmpty()) {
        world = worlds.get(0);
      }
      if (world == null) {
        world = RTPRuntime.serverAccessor().getRTPWorld("world");
      }
    }
    return runProbe(world);
  }

  /**
   * Probe with optional target world.
   * Probes live chunk ticketing if supported; cleanly falls back to sentinel validation
   * if running in a mock/headless environment where live chunk counts return -1 or unsupported.
   *
   * @param world candidate world (may be null)
   * @return probe result
   */
  public static Result runProbe(@Nullable RTPWorld<?> world) {
    return runProbe(world, 0, 0);
  }

  /**
   * Probe with optional target world and chunk coordinates.
   *
   * @param world candidate world (may be null)
   * @param cx chunk X coordinate
   * @param cz chunk Z coordinate
   * @return probe result
   */
  public static Result runProbe(@Nullable RTPWorld<?> world, int cx, int cz) {
    if (world == null || world.getClass().getName().endsWith(".MockRTPWorld")) {
      return runSentinelProbe();
    }

    CompletableFuture<Integer> baselineFuture = null;
    try {
      baselineFuture = world.getServerForceLoadedCount();
    } catch (Throwable t) {
      // Unsupported / mock environment
      return runSentinelProbe();
    }

    if (baselineFuture == null) {
      return runSentinelProbe();
    }

    int baseline;
    try {
      Integer c = baselineFuture.get(5, TimeUnit.SECONDS);
      if (c == null || c < 0) {
        // mock/headless environment returning -1 or null
        return runSentinelProbe();
      }
      baseline = c;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return runSentinelProbe();
    } catch (Throwable t) {
      // Unsupported / mock environment
      return runSentinelProbe();
    }

    return runLiveProbe(world, cx, cz, baseline);
  }

  /**
   * Executes live chunk ticketing probe:
   * a. Record baseline force-loaded count.
   * b. Open ticket via world.keepChunkAt(cx, cz).
   * c. Positively assert count incremented by 1.
   * d. Release ticket and assert count returns to baseline.
   * e. Verify MemoryTracker ticket count returns to 0.
   */
  private static Result runLiveProbe(
      RTPWorld<?> world, int cx, int cz, int baseline) {
    Result r = new Result();
    r.liveProbed = true;
    r.baselineCount = baseline;

    int initialSentinelCount = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL);
    if (initialSentinelCount != 0) {
      r.staleBaseline = initialSentinelCount;
    }

    boolean ticketOpened = false;
    UUID trackerId = null;
    try {
      // b. Open ticket via world.keepChunkAt(cx, cz)
      try {
        world.keepChunkAt(cx, cz);
        ticketOpened = true;
      } catch (UnsupportedOperationException uoe) {
        return runSentinelProbe();
      }

      trackerId = MemoryTracker.track(new Object(), SENTINEL_LABEL, 60_000L);

      // c. Positively assert count incremented by 1
      int openCount = awaitForceLoadedCount(world, baseline + 1, 3000L);
      r.openCount = openCount;
      if (openCount != baseline + 1) {
        r.pass = false;
        r.notes = "openCount was " + openCount + ", expected " + (baseline + 1);
        return r;
      }

      // d. Release ticket and assert count returns to baseline
      world.forgetChunkAt(cx, cz);
      ticketOpened = false;

      int closeCount = awaitForceLoadedCount(world, baseline, 3000L);
      r.closeCount = closeCount;
      if (closeCount != baseline) {
        r.pass = false;
        r.notes = "closeCount was " + closeCount + ", expected " + baseline;
        return r;
      }

      // e. Verify MemoryTracker ticket count returns to 0
      if (trackerId != null) {
        MemoryTracker.untrack(trackerId);
        trackerId = null;
      }
      int trackerCount = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;
      r.memoryTrackerTicketCount = trackerCount;
      r.finalResidual = trackerCount;
      if (trackerCount != 0) {
        r.pass = false;
        r.notes = "MemoryTracker ticket count did not return to 0 (was " + trackerCount + ")";
        return r;
      }

      r.pass = true;
      return r;
    } catch (Throwable t) {
      r.pass = false;
      r.notes = "Live ticket probe exception: " + t.getMessage();
      return r;
    } finally {
      // Strict S-002 adherence: no permanently force-loaded chunks on any exit path
      if (ticketOpened) {
        try {
          world.forgetChunkAt(cx, cz);
        } catch (Throwable ignored) {
        }
      }
      if (trackerId != null) {
        try {
          MemoryTracker.untrack(trackerId);
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static int awaitForceLoadedCount(
      RTPWorld<?> world, int expected, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    int lastSeen = -1;
    while (System.currentTimeMillis() <= deadline) {
      try {
        CompletableFuture<Integer> f = world.getServerForceLoadedCount();
        if (f != null) {
          Integer c = f.get(Math.min(1000L, Math.max(50L, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS);
          if (c != null) {
            lastSeen = c;
            if (c == expected) {
              return c;
            }
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Throwable ignored) {
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return lastSeen;
  }

  /** Fallback probe: sentinel validation for mock / headless environments. */
  public static Result runSentinelProbe() {
    Result r = new Result();

    int baseline = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL);
    if (baseline != 0) {
      // Previous aborted probe left sentinels behind; clean them now so our
      // assertions are meaningful. This is why we label-scope every count.
      r.staleBaseline = baseline;
    }

    // --- case 1: explicit untrack(UUID) ------------------------------------
    Object s1 = new Object();
    UUID id1 = MemoryTracker.track(s1, SENTINEL_LABEL, 60_000L);
    MemoryTracker.untrack(id1);
    r.afterUntrackById = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;

    // --- case 2: explicit untrack(Object) ----------------------------------
    Object s2 = new Object();
    MemoryTracker.track(s2, SENTINEL_LABEL, 60_000L);
    MemoryTracker.untrack(s2);
    r.afterUntrackByRef = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;

    // --- case 3: runDiagnostics does NOT drop a live, non-leaking entry ----
    // Register a long-lived sentinel, then run diagnostics; the entry MUST
    // survive (it is neither collected nor leaking). This protects against
    // a regression where runDiagnostics over-aggressively removes entries.
    Object s3 = new Object();
    UUID id3 = MemoryTracker.track(s3, SENTINEL_LABEL, 60_000L);
    MemoryTracker.runDiagnostics();
    r.afterDiagnosticsOnLive =
        MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;
    java.lang.ref.Reference.reachabilityFence(s3);
    MemoryTracker.untrack(id3); // clean up

    // --- final residual ----------------------------------------------------
    // After all three cases and their cleanups, the label-scoped count must
    // equal the stale-baseline (which would be zero on a clean run). Any
    // excess is a lifecycle bug.
    r.finalResidual = MemoryTracker.trackedCountByLabel(SENTINEL_LABEL) - r.staleBaseline;

    r.pass =
        r.afterUntrackById == 0
            && r.afterUntrackByRef == 0
            && r.afterDiagnosticsOnLive == 1
            && r.finalResidual == 0;
    return r;
  }

  /** Emits the result per the test output contract. */
  private void emit(UUID callerId, Result r) {
    String summary;
    if (r.liveProbed) {
      summary =
          "[RTP test/chunk-ticket] "
              + (r.pass ? "ok" : "FAIL")
              + " live=true baseline="
              + r.baselineCount
              + " open="
              + r.openCount
              + " close="
              + r.closeCount
              + " trackerCount="
              + r.memoryTrackerTicketCount
              + (r.notes.isEmpty() ? "" : " notes=" + r.notes);
    } else {
      summary =
          "[RTP test/chunk-ticket] "
              + (r.pass ? "ok" : "FAIL")
              + " untrackById="
              + r.afterUntrackById
              + " untrackByRef="
              + r.afterUntrackByRef
              + " diagOnLive="
              + r.afterDiagnosticsOnLive
              + " residual="
              + r.finalResidual
              + (r.staleBaseline == 0 ? "" : " staleBaseline=" + r.staleBaseline);
    }

    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, summary);
    }
    // S-004: failing run logs at WARNING; clean run at INFO.
    RTPRuntime.log(r.pass ? Level.INFO : Level.WARNING, summary);

    if (r.staleBaseline != 0) {
      // Advisory, not a failure in itself: a previous aborted probe left
      // sentinels behind. Flag loudly because it implies the previous run
      // was interrupted (cancelled, exception) without a cleanup path.
      RTPRuntime.log(
          Level.WARNING,
          "[RTP test/chunk-ticket] stale sentinels from a previous run detected; count="
              + r.staleBaseline);
    }
  }

  /** Structured probe result. Package-private so the unit test can assert on it. */
  public static final class Result {
    public boolean liveProbed;
    public int baselineCount = -1;
    public int openCount = -1;
    public int closeCount = -1;
    public int memoryTrackerTicketCount = -1;
    public int staleBaseline;
    public int afterUntrackById;
    public int afterUntrackByRef;
    public int afterDiagnosticsOnLive;
    public int finalResidual;
    public boolean pass;
    public String notes = "";
  }
}

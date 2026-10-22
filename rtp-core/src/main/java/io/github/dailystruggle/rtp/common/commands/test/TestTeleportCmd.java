package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTable;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.mock.MockPlayerSession;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test teleport} - IBIT probe that drives {@link RTPAPI#teleport} through the real
 * pipeline for {@code count} BIT mock players ({@link MockPlayerSession}, ADR-121 section 11).
 *
 * <p>Per mock: open a session at (0,64,0) in the region's world, request the teleport, await the
 * API future (bounded, async thread only), then check that {@code setLocation} was reached and
 * that neither {@code processingPlayers} nor {@code MemoryTracker} still hold the mock. The INFO
 * {@code TEST_TELEPORT_MOCKED} finding is always emitted so a report can never be mistaken for a
 * real-player result. Every failure is an ERROR finding and is logged (S-004). The probe opens no
 * chunk tickets of its own (S-002) and never blocks a main or region thread (S-005).
 *
 * <p>{@code HEAVY}: group selectors never run it; only {@code rtp test teleport} does.
 */
public class TestTeleportCmd extends BaseRTPCmdImpl {

  private static final String OWNER = "test.teleport";

  public static final BitCode TEST_TELEPORT_MOCKED = RtpBitCodes.TEST.register(
      3201, "TEST_TELEPORT_MOCKED", java.lang.System.Logger.Level.INFO, BitTier.IBIT,
      OWNER, "", "Results come from synthetic rtp-bit-* players, not real accounts.");
  public static final BitCode TEST_TELEPORT_UNAVAILABLE = RtpBitCodes.TEST.register(
      3202, "TEST_TELEPORT_UNAVAILABLE", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      OWNER, "", "Load the core and name a region whose world is loaded.");
  public static final BitCode TEST_TELEPORT_REJECTED = RtpBitCodes.TEST.register(
      3203, "TEST_TELEPORT_REJECTED", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      OWNER, "", "Resolve the rejection reason (reload, cooldown, economy, permission) and rerun.");
  public static final BitCode TEST_TELEPORT_SET_LOCATION_NOT_REACHED = RtpBitCodes.TEST.register(
      3204, "TEST_TELEPORT_SET_LOCATION_NOT_REACHED", java.lang.System.Logger.Level.ERROR,
      BitTier.IBIT, OWNER, "", "Check that the pipeline's teleport stage reaches the player.");
  public static final BitCode TEST_TELEPORT_PROCESSING_LEAK = RtpBitCodes.TEST.register(
      3205, "TEST_TELEPORT_PROCESSING_LEAK", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      OWNER, "", "Clear processingPlayers on every pipeline exit path.");
  public static final BitCode TEST_TELEPORT_TRACKER_LEAK = RtpBitCodes.TEST.register(
      3206, "TEST_TELEPORT_TRACKER_LEAK", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      OWNER, "", "Untrack TeleportData on every pipeline exit path (REQ-RTP-S-002).");
  public static final BitCode TEST_TELEPORT_TIMEOUT = RtpBitCodes.TEST.register(
      3207, "TEST_TELEPORT_TIMEOUT", java.lang.System.Logger.Level.ERROR, BitTier.IBIT,
      OWNER, "", "Investigate the stalled pipeline stage; the API future did not complete.");

  public static final BitComponent COMPONENT = registerComponent();

  static final String DEFAULT_REGION = "default";
  static final int MIN_COUNT = 1;
  static final int MAX_COUNT = 16;
  /** Per-mock bound on the API future. */
  static final long AWAIT_MILLIS = 60_000L;

  private static final String LOG_PREFIX = "[RTP test.teleport] ";

  /** Teleport request seam; production uses {@link RTPAPI#teleport}. */
  @FunctionalInterface
  interface TeleportFn {
    CompletableFuture<RTPResult> apply(UUID player, RtpTarget target);
  }

  public TestTeleportCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("region", new RegionParameter("rtp.test", "region to teleport the mocks in",
        (uuid, value) -> value != null && !value.isBlank()));
    addParameter("count", new IntegerParameter("rtp.test", "number of mock players (1-16)",
        (uuid, value) -> parseCount(value) > 0, 1, 4, 16));
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
  }

  @Override
  public String name() {
    return "teleport";
  }

  @Override
  public String permission() {
    return "rtp.test.mutating";
  }

  @Override
  public String description() {
    return "teleports synthetic BIT mock players through the real pipeline and checks for leaks";
  }

  @Override
  @SuppressWarnings("java:S3516") // false on guard failure, true once dispatched
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    // TestCmd acquires the permit for this subcommand before dispatching; reuse it.
    TestSemaphore.Holder holder = TestSemaphore.holderOf(callerId);
    boolean parentHeld = holder != null && name().equals(holder.subName);
    if (!parentHeld && !TestSemaphore.tryAcquire(callerId, name())) {
      report(callerId, "&c" + LOG_PREFIX + "another test is already in flight for this caller", Level.WARNING);
      return false;
    }
    if (RTPRuntime.scheduler() == null) {
      if (!parentHeld) TestSemaphore.release(callerId, name());
      report(callerId, "&c" + LOG_PREFIX + "RTP.scheduler is null; core not yet loaded", Level.WARNING);
      return false;
    }

    String regionName = region(parameterValues);
    int count = count(parameterValues);
    boolean json = ProbeBitReport.isJson(parameterValues);
    AtomicBoolean cancelled = new AtomicBoolean();
    Runnable unregister = ActiveTestJobs.register(
        callerId, new ActiveTestJobs.Job(name(), () -> cancelled.set(true)));

    // BitRunner.run blocks on the check, so it runs on an async worker only (S-005).
    RTPRuntime.scheduler().runTaskAsynchronously(() -> {
      try {
        BitResult result = RtpBit.RUNNER.run(COMPONENT.withCheck(() -> runProbe(
            regionName, count, RTPAPI::teleport, AWAIT_MILLIS, cancelled::get)), Runnable::run);
        if (json) {
          ProbeBitReport.emitJson(callerId, result);
        } else {
          BitTableOutput.emit(callerId, BitTable.rows(COMPONENT, result));
        }
      } catch (RuntimeException e) {
        RTPRuntime.log(Level.WARNING, LOG_PREFIX + "probe failed: " + e, e);
      } finally {
        try {
          unregister.run();
        } finally {
          if (!parentHeld) TestSemaphore.release(callerId, name());
        }
      }
    });
    return true;
  }

  private static BitComponent registerComponent() {
    BitComponent component = new BitComponent("teleport", OWNER, BitTier.IBIT,
        BitSideEffect.HEAVY, 120_000,
        () -> runProbe(DEFAULT_REGION, MIN_COUNT, RTPAPI::teleport, AWAIT_MILLIS, () -> false));
    RtpBit.RUNNER.register(component);
    return component;
  }

  static String region(@Nullable Map<String, List<String>> parameterValues) {
    List<String> values = parameterValues == null ? null : parameterValues.get("region");
    if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
      return DEFAULT_REGION;
    }
    return values.get(0);
  }

  /** First bound {@code count}, or {@link #MIN_COUNT} when absent or outside 1..16. */
  static int count(@Nullable Map<String, List<String>> parameterValues) {
    List<String> values = parameterValues == null ? null : parameterValues.get("count");
    if (values == null || values.isEmpty()) return MIN_COUNT;
    int parsed = parseCount(values.get(0));
    return parsed > 0 ? parsed : MIN_COUNT;
  }

  /** Parsed count in 1..16, or {@code -1}. */
  private static int parseCount(@Nullable String value) {
    if (value == null) return -1;
    try {
      int n = Integer.parseInt(value.trim());
      return n >= MIN_COUNT && n <= MAX_COUNT ? n : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  static BitReport runProbe(String regionName, int count, TeleportFn teleport, long awaitMillis) {
    return runProbe(regionName, count, teleport, awaitMillis, () -> false);
  }

  /**
   * Runs {@code count} mocks sequentially. Blocks up to {@code awaitMillis} per mock, so callers
   * shall be on an async worker. Precondition: {@code 1 <= count <= 16}. Invariant: no mock stays
   * registered after return.
   */
  static BitReport runProbe(String regionName, int count, TeleportFn teleport, long awaitMillis,
      BooleanSupplier cancelled) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("teleport");
    int n = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
    int ran = 0;
    RTPWorld<?> world = resolveWorld(report, regionName);
    if (world != null) {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMPONENT_BUDGET_MILLIS);
      for (int i = 0; i < n && !cancelled.getAsBoolean(); i++) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
          fail(report, TEST_TELEPORT_TIMEOUT, "teleport.mock",
              "Component budget exhausted after " + ran + " of " + n + " mocks.");
          break;
        }
        runMock(report, world, regionName, teleport, Math.min(awaitMillis, remaining));
        ran++;
      }
    }
    report.check(TEST_TELEPORT_MOCKED, false, "teleport.mock",
        "Simulated with " + ran + " of " + n + " synthetic " + MockPlayerSession.NAME_PREFIX
            + "* player(s) in region '" + regionName + "'; no real account was moved.");
    return report.build();
  }

  /** Leaves headroom under the 120 s component timeout for report emission. */
  private static final long COMPONENT_BUDGET_MILLIS = 110_000L;

  @Nullable
  private static RTPWorld<?> resolveWorld(ProbeBitReport.Builder report, String regionName) {
    if (RTPRuntime.serverAccessor() == null || TeleportSessions.current() == null) {
      fail(report, TEST_TELEPORT_UNAVAILABLE, "teleport.mock", "Core is not loaded.");
      return null;
    }
    SelectionAPI selection = ActiveSelection.get();
    Region region = selection == null ? null : selection.getRegion(regionName);
    if (region == null) {
      fail(report, TEST_TELEPORT_UNAVAILABLE, "teleport.mock", "Unknown region '" + regionName + "'.");
      return null;
    }
    RTPWorld<?> world = region.getWorld();
    if (world == null) {
      fail(report, TEST_TELEPORT_UNAVAILABLE, "teleport.mock",
          "Region '" + regionName + "' has no loaded world.");
    }
    return world;
  }

  private static void runMock(ProbeBitReport.Builder report, RTPWorld<?> world, String regionName,
      TeleportFn teleport, long awaitMillis) {
    try (MockPlayerSession session = MockPlayerSession.open(new RTPLocation(world, 0, 64, 0))) {
      RuntimeMockRTPPlayer mock = session.player();
      UUID id = mock.uuid();
      String who = mock.name() + " (" + id + ")";
      RTPResult result;
      try {
        result = teleport.apply(id, RtpTarget.region(regionName)).get(awaitMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        fail(report, TEST_TELEPORT_TIMEOUT, "teleport.result",
            who + ": API future did not complete within " + awaitMillis + " ms.");
        abandon(mock);
        checkSetLocation(report, mock, who);
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(report, TEST_TELEPORT_TIMEOUT, "teleport.result", who + ": interrupted while awaiting.");
        abandon(mock);
        return;
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        fail(report, TEST_TELEPORT_REJECTED, "teleport.result", who + ": future failed: " + cause);
        return;
      } catch (RuntimeException e) {
        fail(report, TEST_TELEPORT_REJECTED, "teleport.result", who + ": request threw: " + e);
        return;
      }
      if (result == null || !result.isSuccess()) {
        fail(report, TEST_TELEPORT_REJECTED, "teleport.result", who + ": " + result);
        return;
      }
      checkSetLocation(report, mock, who);
      // Checked before close(), which purges session state for the mock.
      if (TeleportSessions.current().processingPlayers.contains(id)) {
        fail(report, TEST_TELEPORT_PROCESSING_LEAK, "teleport.processingPlayers",
            who + " still in processingPlayers after the API future completed.");
      }
      int tracked = MemoryTracker.trackedCountByLabel("TeleportData-" + id);
      if (tracked != 0) {
        fail(report, TEST_TELEPORT_TRACKER_LEAK, "teleport.memoryTracker",
            who + ": " + tracked + " TeleportData entr" + (tracked == 1 ? "y" : "ies") + " still tracked.");
      }
    } catch (RuntimeException e) {
      fail(report, TEST_TELEPORT_UNAVAILABLE, "teleport.mock", "Mock session failed: " + e);
    }
  }

  private static void checkSetLocation(ProbeBitReport.Builder report, RuntimeMockRTPPlayer mock, String who) {
    if (mock.setLocationCount() == 0) {
      fail(report, TEST_TELEPORT_SET_LOCATION_NOT_REACHED, "teleport.setLocation",
          who + ": setLocation was never called.");
    }
  }

  /** Takes a stalled mock offline and cancels its request so the pipeline can unwind. */
  private static void abandon(RuntimeMockRTPPlayer mock) {
    mock.setOnline(false);
    try {
      RTPAPI.cancel(mock.uuid());
    } catch (RuntimeException e) {
      RTPRuntime.log(Level.WARNING, LOG_PREFIX + "cancel failed for " + mock.uuid(), e);
    }
  }

  /** Adds an ERROR (or code-default) finding and logs it (S-004). */
  private static void fail(ProbeBitReport.Builder report, BitCode code, String path, String detail) {
    report.check(code, false, path, detail);
    RTPRuntime.log(Level.WARNING, LOG_PREFIX + code.key() + " " + path + ": " + detail);
  }

  private static void report(UUID callerId, String msg, Level level) {
    if (!callerId.equals(RTPAPI.serverId) && RTPRuntime.serverAccessor() != null) {
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
    }
    RTPRuntime.log(level, msg);
  }
}

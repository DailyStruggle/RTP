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
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.base.RTPCommandEvents;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test events} - self-contained runtime probe validating event listener dispatch
 * for command events (onSuccess, onFail), prefab events, and player move events.
 *
 * <p>Complies with REQ-RTP-S-004 (feedback on failure) and S-005 (non-blocking).
 */
public class TestEventsCmd extends BaseRTPCmdImpl {

  public static final BitCode COMMAND_SUCCESS = registerCode(3501, "TEST_EVENTS_COMMAND_SUCCESS");
  public static final BitCode COMMAND_FAIL = registerCode(3502, "TEST_EVENTS_COMMAND_FAIL");
  public static final BitCode PREFAB = registerCode(3503, "TEST_EVENTS_PREFAB");
  public static final BitCode PLAYER_MOVE = registerCode(3504, "TEST_EVENTS_PLAYER_MOVE");
  public static final BitCode THREW = registerCode(3505, "TEST_EVENTS_THREW");
  static final long TIMEOUT_MS = 2000L;
  public static final BitComponent COMPONENT = registerComponent();

  public static final class Result {
    public boolean commandSuccess;
    public boolean commandFail;
    public boolean prefab;
    public boolean playerMove;
    public String threw;
  }

  public TestEventsCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
  }

  @Override
  public String name() {
    return "events";
  }

  @Override
  public String permission() {
    return "rtp.test.events";
  }

  @Override
  public String description() {
    return "probe event dispatch for command, prefab, and player move events";
  }

  @Override
  @SuppressWarnings("java:S3516") // Method returns boolean per CommandsAPICommand contract; false on guard failure, true on async dispatch
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    if (!TestSemaphore.tryAcquire(callerId, name())) {
      report(callerId, "&c[RTP test/events] another test is already in flight for this caller", Level.WARNING, null);
      return false;
    }

    if (RTPRuntime.scheduler() == null) {
      TestSemaphore.release(callerId, name());
      report(callerId, "&c[RTP test/events] RTP.scheduler is null; core not yet loaded", Level.WARNING, null);
      Result probe = new Result();
      probe.threw = "RTP.scheduler is null";
      BitResult bitResult =
          RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(probe)), Runnable::run);
      ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
      return false;
    }

    final Runnable[] hookHolder = new Runnable[1];
    hookHolder[0] = ActiveTestJobs.register(
        callerId, new ActiveTestJobs.Job("events", () -> { /* no cancel handle */ }));

    RTPRuntime.scheduler().runTaskAsynchronously(() -> {
      try {
        BitResult bitResult =
            RtpBit.RUNNER.run(
                COMPONENT.withCheck(() -> toBitReport(runProbe(callerId, true))), Runnable::run);
        ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
      } finally {
        try {
          if (hookHolder[0] != null) hookHolder[0].run();
        } finally {
          TestSemaphore.release(callerId, name());
        }
      }
    });

    return true;
  }

  private static BitCode registerCode(int id, String key) {
    return RtpBitCodes.TEST.register(
        id,
        key,
        java.lang.System.Logger.Level.ERROR,
        BitTier.IBIT,
        "test.events",
        "",
        "Verify the corresponding RTP event listener is registered and dispatched.");
  }

  private static BitComponent registerComponent() {
    BitComponent component =
        new BitComponent(
            "events",
            "test.events",
            BitTier.IBIT,
            BitSideEffect.SAFE,
            15_000,
            () -> toBitReport(runProbe(RTPAPI.serverId, false)));
    return component;
  }

  static BitReport toBitReport(Result result) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("events");
    report.check(
        COMMAND_SUCCESS,
        result.commandSuccess,
        "events.command.onSuccess",
        "The synthetic command-success listener did not run.");
    report.check(
        COMMAND_FAIL,
        result.commandFail,
        "events.command.onFail",
        "The synthetic command-fail listener did not run.");
    report.check(PREFAB, result.prefab, "events.prefab", "The synthetic prefab listener did not run.");
    report.check(
        PLAYER_MOVE,
        result.playerMove,
        "events.playerMove",
        "The synthetic player-move listener did not run.");
    report.check(THREW, result.threw == null, "events.dispatch", result.threw);
    return report.build();
  }

  static Result runProbe(UUID callerId, boolean emit) {
    Result result = new Result();
    if (emit) report(callerId, "[RTP test/events] begin", Level.INFO, null);

    List<AutoCloseable> toUnregister = new ArrayList<>();
    try {
      // 1. RTPCommandEvents.onSuccess
      CompletableFuture<Boolean> successFut = new CompletableFuture<>();
      AutoCloseable successReg = RTPCommandEvents.onSuccess((sender, player) -> successFut.complete(true));
      toUnregister.add(successReg);

      // 2. RTPCommandEvents.onFail
      CompletableFuture<Boolean> failFut = new CompletableFuture<>();
      AutoCloseable failReg = RTPCommandEvents.onFail((sender, msg) -> failFut.complete(true));
      toUnregister.add(failReg);

      // 3. RTPAPI.prefabEvents
      CompletableFuture<Boolean> prefabFut = new CompletableFuture<>();
      AutoCloseable prefabReg = RTPAPI.prefabEvents.subscribe(event -> prefabFut.complete(true));
      toUnregister.add(prefabReg);

      // 4. RTPAPI.playerMoveEvents
      UUID targetPlayerId = callerId.equals(RTPAPI.serverId) ? UUID.randomUUID() : callerId;
      CompletableFuture<Boolean> moveFut = new CompletableFuture<>();
      AutoCloseable moveReg = RTPAPI.playerMoveEvents.watch(targetPlayerId, event -> moveFut.complete(true));
      toUnregister.add(moveReg);

      // Fire synthetic events
      RTPPlayer callerPlayer = null;
      try {
        if (RTPRuntime.serverAccessor() != null) {
          callerPlayer = RTPRuntime.serverAccessor().getPlayer(callerId);
        }
      } catch (Throwable ignored) {
      }

      // Fire success
      RTPCommandEvents.fireSuccess(callerPlayer, callerPlayer);

      // Fire fail
      RTPCommandEvents.fireFail(callerPlayer, "synthetic-fail-test");

      // Fire prefab event
      PrefabAppliedEvent synthPrefabEvent = new PrefabAppliedEvent(
          "test-events-prefab",
          callerId,
          List.of("test.yml"),
          Map.of(),
          true
      );
      RTPAPI.prefabEvents.fire(synthPrefabEvent);

      // Fire player move event
      PlayerMoveEvent synthMoveEvent = new PlayerMoveEvent(
          targetPlayerId,
          "test-world",
          0, 64, 0,
          1, 64, 0
      );
      RTPAPI.playerMoveEvents.fire(synthMoveEvent);

      // Await callbacks with timeout clamped to 2000ms
      result.commandSuccess = awaitProbe(callerId, "command.onSuccess", successFut, emit);
      result.commandFail = awaitProbe(callerId, "command.onFail", failFut, emit);
      result.prefab = awaitProbe(callerId, "prefabEvents", prefabFut, emit);
      result.playerMove = awaitProbe(callerId, "playerMoveEvents", moveFut, emit);

      if (emit) {
        if (result.commandSuccess && result.commandFail && result.prefab && result.playerMove) {
          report(callerId, "[RTP test/events] PASS: all event listeners dispatched successfully", Level.INFO, null);
        } else {
          report(callerId, "&c[RTP test/events] FAIL: one or more event listeners timed out or failed", Level.WARNING, null);
        }
      }
    } catch (Throwable t) {
      result.threw = t.getClass().getSimpleName() + ": " + t.getMessage();
      if (emit) {
        report(callerId, "&c[RTP test/events] FAIL: unexpected error during probe: " + t.getMessage(), Level.WARNING, t);
      }
    } finally {
      for (AutoCloseable closeable : toUnregister) {
        try {
          if (closeable != null) {
            closeable.close();
          }
        } catch (Throwable t) {
          if (result.threw == null) result.threw = t.getClass().getSimpleName() + ": " + t.getMessage();
          if (emit) {
            RTPRuntime.log(Level.WARNING, "[RTP test/events] error unregistering listener: " + t.getMessage(), t);
          }
        }
      }
      if (emit) report(callerId, "[RTP test/events] end", Level.INFO, null);
    }
    return result;
  }

  private static final String ERR_PREFIX = "&c[RTP test/events] ";

  private static boolean awaitProbe(UUID callerId, String name, CompletableFuture<Boolean> fut, boolean emit) {
    try {
      Boolean res = fut.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (Boolean.TRUE.equals(res)) {
        if (emit) report(callerId, "[RTP test/events] " + name + ": ok", Level.INFO, null);
        return true;
      } else {
        if (emit) report(callerId, ERR_PREFIX + name + ": FAILED (completed false)", Level.WARNING, null);
        return false;
      }
    } catch (TimeoutException te) {
      if (emit) report(callerId, ERR_PREFIX + name + ": TIMEOUT after " + TIMEOUT_MS + "ms", Level.WARNING, null);
      return false;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      if (emit) report(callerId, ERR_PREFIX + name + ": INTERRUPTED", Level.WARNING, ie);
      return false;
    } catch (Throwable t) {
      if (emit) report(callerId, ERR_PREFIX + name + ": FAILED (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")", Level.WARNING, t);
      return false;
    }
  }

  private static void report(UUID callerId, String msg, Level level, Throwable t) {
    if (!callerId.equals(RTPAPI.serverId) && RTPRuntime.serverAccessor() != null) {
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
    }
    if (t != null) {
      RTPRuntime.log(level, msg, t);
    } else {
      RTPRuntime.log(level, msg);
    }
  }
}

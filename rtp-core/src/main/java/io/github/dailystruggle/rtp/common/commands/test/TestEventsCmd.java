package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCommandEvents;
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

  static final long TIMEOUT_MS = 2000L;

  public TestEventsCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
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
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    if (!TestSemaphore.tryAcquire(callerId, name())) {
      report(callerId, "&c[RTP test/events] another test is already in flight for this caller", Level.WARNING, null);
      return true;
    }

    if (RTP.scheduler == null) {
      TestSemaphore.release(callerId, name());
      report(callerId, "&c[RTP test/events] RTP.scheduler is null; core not yet loaded", Level.WARNING, null);
      return true;
    }

    final Runnable[] hookHolder = new Runnable[1];
    hookHolder[0] = ActiveTestJobs.register(
        callerId, new ActiveTestJobs.Job("events", () -> { /* no cancel handle */ }));

    RTP.scheduler.runTaskAsynchronously(() -> {
      try {
        runProbe(callerId);
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

  private void runProbe(UUID callerId) {
    report(callerId, "[RTP test/events] begin", Level.INFO, null);

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
        if (RTP.serverAccessor != null) {
          callerPlayer = RTP.serverAccessor.getPlayer(callerId);
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
      boolean sOk = awaitProbe(callerId, "command.onSuccess", successFut);
      boolean fOk = awaitProbe(callerId, "command.onFail", failFut);
      boolean pOk = awaitProbe(callerId, "prefabEvents", prefabFut);
      boolean mOk = awaitProbe(callerId, "playerMoveEvents", moveFut);

      if (sOk && fOk && pOk && mOk) {
        report(callerId, "[RTP test/events] PASS: all event listeners dispatched successfully", Level.INFO, null);
      } else {
        report(callerId, "&c[RTP test/events] FAIL: one or more event listeners timed out or failed", Level.WARNING, null);
      }
    } catch (Throwable t) {
      report(callerId, "&c[RTP test/events] FAIL: unexpected error during probe: " + t.getMessage(), Level.WARNING, t);
    } finally {
      for (AutoCloseable closeable : toUnregister) {
        try {
          if (closeable != null) {
            closeable.close();
          }
        } catch (Throwable t) {
          RTP.log(Level.WARNING, "[RTP test/events] error unregistering listener: " + t.getMessage(), t);
        }
      }
      report(callerId, "[RTP test/events] end", Level.INFO, null);
    }
  }

  private boolean awaitProbe(UUID callerId, String name, CompletableFuture<Boolean> fut) {
    try {
      Boolean res = fut.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (Boolean.TRUE.equals(res)) {
        report(callerId, "[RTP test/events] " + name + ": ok", Level.INFO, null);
        return true;
      } else {
        report(callerId, "&c[RTP test/events] " + name + ": FAILED (completed false)", Level.WARNING, null);
        return false;
      }
    } catch (TimeoutException te) {
      report(callerId, "&c[RTP test/events] " + name + ": TIMEOUT after " + TIMEOUT_MS + "ms", Level.WARNING, null);
      return false;
    } catch (Throwable t) {
      report(callerId, "&c[RTP test/events] " + name + ": FAILED (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")", Level.WARNING, t);
      return false;
    }
  }

  private void report(UUID callerId, String msg, Level level, Throwable t) {
    if (!callerId.equals(RTPAPI.serverId)) {
      if (RTP.serverAccessor != null) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
    }
    if (t != null) {
      RTP.log(level, msg, t);
    } else {
      RTP.log(level, msg);
    }
  }
}

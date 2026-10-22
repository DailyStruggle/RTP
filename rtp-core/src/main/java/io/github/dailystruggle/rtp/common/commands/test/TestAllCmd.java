package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/** Runs selected SAFE test components without blocking the command dispatch thread. */
public class TestAllCmd extends BaseRTPCmdImpl {
  public TestAllCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
  }

  @Override
  public String name() {
    return "all";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "runs every registered SAFE test component";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    runAll(callerId, ProbeBitReport.isJson(parameterValues));
    return true;
  }

  protected void runAll(UUID callerId, boolean json) {
    run(callerId, List.of(BitSelector.parse("all")), json);
  }

  /** Dispatches a selector union through the platform scheduler and emits one combined report. */
  public static void run(UUID callerId, List<BitSelector> selectors, boolean json) {
    if (selectors == null || selectors.isEmpty()) return;
    AtomicBoolean cancelled = new AtomicBoolean();
    Runnable unregister =
        ActiveTestJobs.register(
            callerId, new ActiveTestJobs.Job("all", () -> cancelled.set(true)));
    Runnable task =
        () -> {
          try {
            if (cancelled.get()) return;
            Map<String, BitResult> results = new LinkedHashMap<>();
            for (BitSelector selector : selectors) {
              if (cancelled.get()) return;
              for (BitResult result : RtpBit.RUNNER.run(selector, Runnable::run)) {
                results.putIfAbsent(result.component().id(), result);
              }
            }
            if (!cancelled.get()) ProbeBitReport.emit(callerId, json, new ArrayList<>(results.values()));
          } catch (Throwable failure) {
            RTPRuntime.log(Level.WARNING, "[RTP test/all] selector run failed", failure);
          } finally {
            unregister.run();
          }
        };

    if (RTPRuntime.scheduler() == null) {
      if (RTPRuntime.serverAccessor() == null) {
        task.run();
      } else {
        unregister.run();
        RTPRuntime.log(Level.WARNING, "[RTP test/all] scheduler unavailable; SAFE group was not run");
      }
      return;
    }
    try {
      RTPRuntime.scheduler().runTaskAsynchronously(task);
    } catch (Throwable failure) {
      RTPRuntime.log(Level.WARNING, "[RTP test/all] async dispatch failed", failure);
      if (RTPRuntime.serverAccessor() == null) task.run();
      else unregister.run();
    }
  }
}

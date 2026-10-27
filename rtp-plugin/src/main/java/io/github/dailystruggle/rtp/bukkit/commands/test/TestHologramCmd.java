package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.hologram.HologramRegistry;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.bukkitplatform.commands.parameters.OnlinePlayerParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.rtp.common.commands.test.ProbeBitReport;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Diagnostic test command for hologram and floating display providers (Paper TextDisplay,
 * DecentHolograms, HolographicDisplays, or fallback).
 *
 * <p>Usage: {@code /rtp test hologram [player:<name>] [seconds:1..60]}
 *
 * <p>Spawns a temporary floating hologram in front of the target player (or caller),
 * displaying the resolved provider and a live ticking countdown before automatically
 * closing and despawning via {@link HologramHandle#close()}.
 */
public class TestHologramCmd extends BaseRTPCmdImpl {
  private static final String OWNER = "test.hologram";
  private static final long COMPONENT_TIMEOUT_MS = 75_000L;

  public static final BitCode PLAYER_UNKNOWN = registerCode(4601, "PROBE_HOLOGRAM_PLAYER_UNKNOWN");
  public static final BitCode LOCATION_UNAVAILABLE = registerCode(4602, "PROBE_HOLOGRAM_LOCATION_UNAVAILABLE");
  public static final BitCode SPAWN_FAILED = registerCode(4603, "PROBE_HOLOGRAM_SPAWN_FAILED");
  public static final BitCode NULL_HANDLE = registerCode(4604, "PROBE_HOLOGRAM_NULL_HANDLE");
  public static final BitCode CLOSE_FAILED = registerCode(4605, "PROBE_HOLOGRAM_CLOSE_FAILED");
  public static final BitCode SCHEDULER_UNAVAILABLE = registerCode(4606, "PROBE_HOLOGRAM_SCHEDULER_UNAVAILABLE");
  public static final BitCode UPDATE_FAILED = registerCode(4607, "PROBE_HOLOGRAM_UPDATE_FAILED");
  public static final BitCode CANCELLED = RtpBitCodes.PROBE.register(
      4609,
      "PROBE_HOLOGRAM_CANCELLED",
      java.lang.System.Logger.Level.WARNING,
      BitTier.IBIT,
      OWNER,
      "",
      "Allow the hologram countdown to complete or rerun it.");
  public static final BitCode CONSOLE_STATUS = RtpBitCodes.PROBE.register(
      4608,
      "PROBE_HOLOGRAM_CONSOLE_STATUS",
      java.lang.System.Logger.Level.INFO,
      BitTier.IBIT,
      OWNER,
      "",
      "Provide player=<name> to spawn the hologram in-world.");
  public static final BitComponent COMPONENT = registerComponent();
  public static final int MIN_SECONDS = 1;
  public static final int MAX_SECONDS = 60;
  public static final int DEFAULT_SECONDS = 5;

  private static BitCode registerCode(int id, String key) {
    return RtpBitCodes.PROBE.register(
        id,
        key,
        java.lang.System.Logger.Level.ERROR,
        BitTier.IBIT,
        OWNER,
        "",
        "Inspect the hologram provider and ensure its handle closes successfully.");
  }

  private static BitComponent registerComponent() {
    return new BitComponent(
            "hologram",
            OWNER,
            BitTier.IBIT,
            BitSideEffect.NEEDS_PLAYER,
            COMPONENT_TIMEOUT_MS,
            () -> ProbeBitReport.forProbe("hologram").build());
  }

  public TestHologramCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
    addParameter(
        "player",
        new OnlinePlayerParameter(
            "rtp.test", "target player to spawn test hologram for (defaults to caller)", (sender, s) -> true));
    addParameter(
        "seconds",
        new IntegerParameter(
            "rtp.test", "duration in seconds before despawning (1..60, default 5)", (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "hologram";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "spawn a temporary test hologram to verify active hologram provider";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    if (RTPRuntime.serverAccessor() == null) {
      recordReport(callerId, parameterValues,
          ProbeBitReport.forProbe("hologram")
              .check(PLAYER_UNKNOWN, false, "hologram.runtime", "RTP.serverAccessor is unavailable.")
              .build());
      return true;
    }

    HologramProvider provider = HologramRegistry.getProvider();
    String providerName = provider != null ? provider.getClass().getSimpleName() : "None";

    // Resolve target player: parameter first, else caller
    RTPPlayer target = null;
    List<String> playerNames = parameterValues.get("player");
    if (playerNames != null && !playerNames.isEmpty()) {
      target = RTPRuntime.serverAccessor().getPlayer(playerNames.get(0));
      if (target == null) {
        String msg = "&c[RTP test/hologram] unknown player: " + playerNames.get(0);
        RTPRuntime.serverAccessor().sendMessage(callerId, msg);
        RTP.log(Level.WARNING, msg);
        recordReport(callerId, parameterValues,
            ProbeBitReport.forProbe("hologram")
                .check(PLAYER_UNKNOWN, false, "hologram.player", msg)
                .build());
        return true;
      }
    } else if (!callerId.equals(RTPAPI.serverId)) {
      target = RTPRuntime.serverAccessor().getPlayer(callerId);
    }

    int seconds = DEFAULT_SECONDS;
    List<String> secValues = parameterValues.get("seconds");
    if (secValues != null && !secValues.isEmpty()) {
      try {
        seconds = Integer.parseInt(secValues.get(0));
      } catch (NumberFormatException ignored) {
        // use default
      }
    }
    seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));

    // If caller is console and no player specified, output provider status dump
    if (target == null) {
      String msg = "&a[RTP test/hologram] Active HologramProvider: &b" + providerName
          + " &7(Specify player:<name> to spawn in-world)";
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      RTP.log(Level.INFO, "[RTP test/hologram] Active HologramProvider: " + providerName);
      recordReport(callerId, parameterValues,
          ProbeBitReport.forProbe("hologram")
              .note(CONSOLE_STATUS, "hologram.console", "Active HologramProvider: " + providerName)
              .build());
      return true;
    }

    RTPLocation loc = target.getLocation();
    if (loc == null || loc.world() == null) {
      String msg = "&c[RTP test/hologram] Unable to resolve player location.";
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      recordReport(callerId, parameterValues,
          ProbeBitReport.forProbe("hologram")
              .check(LOCATION_UNAVAILABLE, false, "hologram.location", msg)
              .build());
      return true;
    }

    // Position 2 blocks ahead / eye height
    String worldName = loc.world().name();
    Vector3d spawnPos = new Vector3d(loc.x() + 1.5, loc.y() + 1.8, loc.z() + 1.5);
    String holoId = "rtp_test_" + UUID.randomUUID().toString().substring(0, 8);

    final int totalSec = seconds;
    List<String> initialLines = List.of(
        "&a&lLeafRTP Hologram Test",
        "&7Provider: &b" + providerName,
        "&eDespawning in &c" + totalSec + "s"
    );

    HologramHandle handle;
    try {
      handle = provider != null
          ? provider.spawnHologram(holoId, worldName, spawnPos, initialLines)
          : null;
    } catch (Throwable t) {
      String err = "&c[RTP test/hologram] Failed to spawn hologram: " + t.getMessage();
      RTPRuntime.serverAccessor().sendMessage(callerId, err);
      RTP.log(Level.WARNING, "[RTP test/hologram] Spawn failed: " + t.getMessage(), t);
      recordReport(callerId, parameterValues,
          ProbeBitReport.forProbe("hologram")
              .check(SPAWN_FAILED, false, "hologram.spawn", String.valueOf(t))
              .build());
      return true;
    }

    if (handle == null) {
      String msg = "&c[RTP test/hologram] No hologram spawned (provider returned null handle).";
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      recordReport(callerId, parameterValues,
          ProbeBitReport.forProbe("hologram")
              .check(NULL_HANDLE, false, "hologram.handle", msg)
              .build());
      return true;
    }

    String startMsg = "&a[RTP test/hologram] Spawned test hologram (&b" + providerName
        + "&a) for &e" + totalSec + "s&a at &7" + worldName + " " + spawnPos.x() + ", " + spawnPos.y() + ", " + spawnPos.z();
    RTPRuntime.serverAccessor().sendMessage(callerId, startMsg);

    AtomicInteger remaining = new AtomicInteger(totalSec);
    AtomicBoolean closed = new AtomicBoolean(false);
    AtomicBoolean updateFailed = new AtomicBoolean(false);
    AtomicBoolean closeFailed = new AtomicBoolean(false);
    AtomicBoolean schedulerFailed = new AtomicBoolean(false);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    AtomicReference<Object> taskHolder = new AtomicReference<>();
    AtomicReference<Object> watchdogHolder = new AtomicReference<>();
    CompletableFuture<BitReport> reportFuture = new CompletableFuture<>();
    BiConsumer<Boolean, String> finish =
        (timedOut, detail) ->
            finishHologram(
                handle, holoId, closed, updateFailed, closeFailed, schedulerFailed,
                cancelled, taskHolder,
                watchdogHolder, reportFuture, timedOut, detail);
    Runnable unregister =
        ActiveTestJobs.register(
            callerId,
            new ActiveTestJobs.Job(
                "hologram",
                () -> {
                  cancelled.set(true);
                  finish.accept(false, "cancelled by operator");
                  RTP.log(Level.INFO, "[RTP test/hologram] cancelled by operator");
                }));
    ProbeVerdict.record(COMPONENT, reportFuture, COMPONENT_TIMEOUT_MS)
        .whenComplete(
            (result, failure) -> {
              try {
                if (failure != null) {
                  RTP.log(Level.WARNING, "[RTP test/hologram] BIT verdict failed", failure);
                } else {
                  if (result.report().findings().stream()
                      .anyMatch(finding -> finding.code().equals(BitCodes.COMPONENT_TIMEOUT.key()))) {
                    finish.accept(true, "BIT component deadline expired");
                  }
                  ProbeBitReport.emit(callerId, parameterValues, COMPONENT, result);
                }
              } finally {
                unregister.run();
              }
            });

    Runnable ticker = () -> {
      int left = remaining.decrementAndGet();
      if (left > 0 && !closed.get()) {
        try {
          handle.updateLines(List.of(
              "&a&lLeafRTP Hologram Test",
              "&7Provider: &b" + providerName,
              "&eDespawning in &c" + left + "s"
          ));
        } catch (Throwable t) {
          updateFailed.set(true);
          RTP.log(Level.FINE, "[RTP test/hologram] Update lines failed: " + t.getMessage());
        }
      } else {
        finish.accept(false, "countdown completed");
      }
    };

    if (RTPRuntime.scheduler() == null) {
      schedulerFailed.set(true);
      finish.accept(false, "scheduler unavailable");
      return true;
    }
    try {
      taskHolder.set(RTPRuntime.scheduler().runTaskTimerAsynchronously(ticker, 20L, 20L));
      long watchdogTicks = (totalSec + 10) * 20L;
      watchdogHolder.set(
          RTPRuntime.scheduler()
              .runTaskTimerAsynchronously(
                  () -> finish.accept(true, "hologram watchdog expired after " + (totalSec + 10) + " seconds"),
                  watchdogTicks,
                  20L));
      if (closed.get()) {
        cancel(taskHolder.get());
        cancel(watchdogHolder.get());
      }
    } catch (Throwable schedulingFailure) {
      schedulerFailed.set(true);
      finish.accept(false, "scheduler dispatch failed: " + schedulingFailure);
      RTP.log(Level.WARNING, "[RTP test/hologram] scheduler dispatch failed", schedulingFailure);
    }

    return true;
  }

  private void recordReport(UUID callerId, Map<String, List<String>> args, BitReport report) {
    ProbeVerdict.record(COMPONENT, CompletableFuture.completedFuture(report), COMPONENT_TIMEOUT_MS)
        .thenAccept(result -> ProbeBitReport.emit(callerId, args, COMPONENT, result));
  }

  private void finishHologram(
      HologramHandle handle,
      String holoId,
      AtomicBoolean closed,
      AtomicBoolean updateFailed,
      AtomicBoolean closeFailed,
      AtomicBoolean schedulerFailed,
      AtomicBoolean cancelled,
      AtomicReference<Object> taskHolder,
      AtomicReference<Object> watchdogHolder,
      CompletableFuture<BitReport> reportFuture,
      boolean timedOut,
      String detail) {
    if (!closed.compareAndSet(false, true)) return;
    cancel(taskHolder.get());
    cancel(watchdogHolder.get());
    try {
      handle.close();
      RTP.log(Level.FINE, "[RTP test/hologram] Closed test hologram " + holoId);
    } catch (Throwable failure) {
      closeFailed.set(true);
      RTP.log(Level.WARNING, "[RTP test/hologram] Failed to close handle: " + failure.getMessage(), failure);
    }
    if (timedOut) {
      List<BitFinding> findings = new ArrayList<>();
      findings.add(BitFinding.of(
          BitCodes.COMPONENT_TIMEOUT,
          "rtp test hologram",
          "hologram.timeout",
          detail,
          "hologram closes within the requested duration plus watchdog allowance",
          BitCodes.COMPONENT_TIMEOUT.remediation()));
      if (closeFailed.get()) {
        findings.add(BitFinding.of(CLOSE_FAILED, "rtp test hologram", "hologram.close", "Hologram handle close failed." , "close succeeds", CLOSE_FAILED.remediation()));
      }
      if (schedulerFailed.get()) {
        findings.add(BitFinding.of(SCHEDULER_UNAVAILABLE, "rtp test hologram", "hologram.scheduler", detail, "scheduler dispatch succeeds", SCHEDULER_UNAVAILABLE.remediation()));
      }
      if (cancelled.get()) {
        findings.add(BitFinding.of(CANCELLED, "rtp test hologram", "hologram.cancelled", "Hologram countdown was cancelled.", "countdown completes", CANCELLED.remediation()));
      }
      reportFuture.complete(BitReport.of(findings));
      return;
    }
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("hologram");
    report.check(CLOSE_FAILED, !closeFailed.get(), "hologram.close", closeFailed.get() ? "Hologram handle close failed." : "");
    report.check(UPDATE_FAILED, !updateFailed.get(), "hologram.update", updateFailed.get() ? "Hologram countdown update failed." : "");
    report.check(SCHEDULER_UNAVAILABLE, !schedulerFailed.get(), "hologram.scheduler", schedulerFailed.get() ? detail : "");
    if (cancelled.get()) {
      report.note(CANCELLED, "hologram.cancelled", "Hologram countdown was cancelled.");
    }
    reportFuture.complete(report.build());
  }

  private void cancel(@Nullable Object handle) {
    if (handle == null || RTPRuntime.scheduler() == null) return;
    try {
      RTPRuntime.scheduler().cancelTask(handle);
    } catch (Throwable failure) {
      RTP.log(Level.WARNING, "[RTP test/hologram] scheduled task cancellation failed", failure);
    }
  }
}

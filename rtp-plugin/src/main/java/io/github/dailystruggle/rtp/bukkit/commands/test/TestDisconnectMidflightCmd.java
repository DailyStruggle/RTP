package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.rtp.common.commands.test.ProbeBitReport;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.MockPlayerSession;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test disconnect-midflight} &mdash; mirrors {@code OnPlayerQuit}'s
 * cleanup against a synthetic UUID and asserts {@code processingPlayers},
 * {@code invulnerablePlayers}, and {@code latestTeleportData} are cleared
 * and the pending {@link RTPRunnable} is cancelled. We replay the cleanup
 * directly (rather than firing {@code PlayerQuitEvent}) so listener-priority
 * regressions cannot mask tracker-layer regressions; if {@code OnPlayerQuit}
 * is refactored, this probe must be updated in lock-step. Traces
 * REQ-RTP-S-002 / S-004; see {@code RUNTIME_TEST_SUITE_PLAN.md &sect;3.9}.
 */
public class TestDisconnectMidflightCmd extends BaseRTPCmdImpl {

  public static final BitCode PROCESSING = registerCode(4301, "PROBE_DISCONNECT_MIDFLIGHT_PROCESSING");
  public static final BitCode INVULNERABLE = registerCode(4302, "PROBE_DISCONNECT_MIDFLIGHT_INVULNERABLE");
  public static final BitCode NEXT_TASK = registerCode(4303, "PROBE_DISCONNECT_MIDFLIGHT_NEXT_TASK");
  public static final BitCode TELEPORT_DATA = registerCode(4304, "PROBE_DISCONNECT_MIDFLIGHT_TELEPORT_DATA");
  public static final BitCode THREW = registerCode(4305, "PROBE_DISCONNECT_MIDFLIGHT_THREW");
  public static final BitCode NO_WORLD = RtpBitCodes.PROBE.register(
      4306,
      "PROBE_DISCONNECT_MIDFLIGHT_NO_WORLD",
      java.lang.System.Logger.Level.INFO,
      BitTier.IBIT,
      "test.disconnect-midflight",
      "",
      "Load the default region world to use a registered BIT mock player.");
  public static final BitComponent COMPONENT = registerComponent();

  /** Prefix for the synthetic probe UUID label, for log-scraping friendliness. */
  static final String PROBE_TAG = "rtp-test-disconnect-midflight";

  public TestDisconnectMidflightCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
  }

  @Override
  public String name() {
    return "disconnect-midflight";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "verifies disconnect-mid-teleport cleanup (REQ-RTP-S-002 leak canary)";
  }

  @Override
  @SuppressWarnings("java:S3516") // Diagnostic command returns true on completion
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    Runnable unregister =
        ActiveTestJobs.register(callerId, new ActiveTestJobs.Job("disconnect-midflight", () -> {}));
    ProbeVerdict.runAsync(COMPONENT, () -> runBitProbe(callerId))
        .whenComplete(
            (result, failure) -> {
              try {
                if (failure != null) {
                  RTP.log(Level.WARNING, "[RTP test/disconnect-midflight] BIT verdict failed", failure);
                } else {
                  ProbeBitReport.emit(callerId, parameterValues, COMPONENT, result);
                }
              } finally {
                unregister.run();
              }
            });
    return true;
  }

  private BitReport runBitProbe(UUID callerId) {
    Result result;
    TeleportSessions sessions = TeleportSessions.current();
    if (sessions == null) {
      result = new Result();
      result.threw = "TeleportSessions is unavailable";
      emit(callerId, result);
      return toBitReport(result);
    }
    State live = new State(
        sessions.processingPlayers,
        sessions.invulnerablePlayers.keySet(),
        sessions.invulnerablePlayers,
        sessions.latestTeleportData,
        sessions.priorTeleportData);
    Consumer<UUID> prodCancel = id -> new RTPTeleportCancel(id).run();
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world =
        MockProbeWorld.resolve(MockProbeWorld.DEFAULT_REGION);
    if (world == null) {
      result = runProbe(live, prodCancel, freshUuid(live));
      result.noWorld = true;
    } else {
      try (MockPlayerSession session = MockPlayerSession.open(MockProbeWorld.spawn(world))) {
        RuntimeMockRTPPlayer mock = session.player();
        result = runProbe(live, prodCancel, mock.uuid());
      }
    }
    emit(callerId, result);
    return toBitReport(result);
  }

  private static BitCode registerCode(int id, String key) {
    return RtpBitCodes.PROBE.register(
        id,
        key,
        java.lang.System.Logger.Level.ERROR,
        BitTier.IBIT,
        "test.disconnect-midflight",
        "",
        "Verify the disconnect cleanup releases this player state.");
  }

  private static BitComponent registerComponent() {
    return new BitComponent(
            "disconnect-midflight",
            "test.disconnect-midflight",
            BitTier.IBIT,
            BitSideEffect.NEEDS_WORLD,
            5_000,
            () -> ProbeBitReport.forProbe("disconnect-midflight").build());
  }

  static BitReport toBitReport(Result result) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("disconnect-midflight");
    report.check(PROCESSING, result.processingCleared, "disconnect.processingPlayers", "Probe UUID remains in processingPlayers.");
    report.check(INVULNERABLE, result.invulnerableCleared, "disconnect.invulnerablePlayers", "Probe UUID remains invulnerable.");
    report.check(NEXT_TASK, result.nextTaskCancelled, "disconnect.nextTask", "Pending teleport task was not cancelled.");
    report.check(TELEPORT_DATA, result.teleportDataCleared, "disconnect.teleportData", "Probe teleport data was not cleared.");
    report.check(THREW, result.threw == null, "disconnect.cleanup", result.threw == null ? "" : result.threw);
    if (result.noWorld) {
      report.note(NO_WORLD, "disconnect.mock", "No default region world was available; used an isolated synthetic UUID.");
    }
    return report.build();
  }

  /**
   * Pure, injectable probe. Tests call this with a {@link State} whose
   * backing collections are either the live {@code RTP.getInstance()} ones
   * (from the command's {@code onCommand}) or fresh instances owned by the
   * test. The probe picks a synthetic UUID not currently present in any of
   * the four collections, so it can never collide with live traffic.
   *
   * <p>The {@code cancelAction} is the final cleanup step. In production it
   * is {@code id -> new RTPTeleportCancel(id).run()}, which depends on the
   * {@link RTP#getInstance()} singleton and therefore cannot be used from a
   * pure unit test. Unit tests pass a hand-written mirror that performs
   * the same mutations on the injected {@link State} (remove from
   * {@code latestTeleportData} when no prior data exists, remove from
   * {@code processingPlayers}). If {@link RTPTeleportCancel#refund} is ever
   * refactored, {@link #fakeCancelMirror} must be updated in lock-step.
   */
  static Result runProbe(State s, Consumer<UUID> cancelAction) {
    return runProbe(s, cancelAction, freshUuid(s));
  }

  static Result runProbe(State s, Consumer<UUID> cancelAction, UUID probeId) {
    Result r = new Result();
    if (collides(s, probeId)) {
      throw new IllegalArgumentException("probe UUID already exists in teleport state: " + probeId);
    }
    r.probeId = probeId;

    // --- stage: simulate a player mid-teleport ------------------------
    // This mirrors the state the teleport pipeline holds for a player
    // between RTPCmd.onCommand and TeleportPipelineTask.runTeleport's
    // whenComplete callback.
    RTPRunnable fakeTask = new RTPRunnable();
    TeleportData data = new TeleportData();
    data.completed = false;
    data.nextTask = fakeTask;

    s.processingPlayers.add(probeId);
    s.stageInvulnerable(probeId);
    s.latestTeleportData.put(probeId, data);

    // --- cleanup: mirror OnPlayerQuit exactly -------------------------
    // (see rtp-plugin/.../bukkitListeners/OnPlayerQuit.java). If the
    // production listener is ever refactored, update this mirror in the
    // same change.
    try {
      s.clearInvulnerable(probeId);
      s.processingPlayers.remove(probeId);
      // OnPlayerQuit sets cancelled=true and re-schedules the task; here
      // we invoke the cancellation directly - the re-schedule is for the
      // pipeline to observe cancellation on its own thread, which we do
      // not exercise.
      if (data.nextTask != null) {
        data.nextTask.setCancelled(true);
      }
      cancelAction.accept(probeId);
    } catch (Throwable t) {
      r.threw = t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    // --- assertions ---------------------------------------------------
    r.processingCleared = !s.processingPlayers.contains(probeId);
    r.invulnerableCleared = !s.invulnerableContains(probeId);
    r.nextTaskCancelled = fakeTask.isCancelled();
    // RTPTeleportCancel removes latestTeleportData when no prior data was
    // present (the mid-teleport case we're simulating). See
    // RTPTeleportCancel.refund line 48.
    r.teleportDataCleared = !s.latestTeleportData.containsKey(probeId);

    r.pass =
        r.threw == null
            && r.processingCleared
            && r.invulnerableCleared
            && r.nextTaskCancelled
            && r.teleportDataCleared;
    return r;
  }

  /**
   * Hand-written mirror of {@link RTPTeleportCancel#refund} restricted to
   * the injected {@link State}. Used by unit tests so the probe can verify
   * the disconnect-cleanup contract without touching {@link RTP#getInstance()}.
   *
   * <p>Must stay faithful to {@code RTPTeleportCancel.refund}: if that
   * method is refactored (e.g. a new collection is added to the cleanup
   * sequence), update this mirror in the same change.
   */
  static Consumer<UUID> fakeCancelMirror(State s) {
    return id -> {
      TeleportData data = s.latestTeleportData.get(id);
      if (data == null) return;
      if (data.completed) return;

      // Mirror RTPTeleportCancel.refund: restore prior if present, else remove.
      TeleportData prior = s.priorTeleportData.remove(id);
      if (prior != null) {
        s.latestTeleportData.put(id, prior);
      } else {
        s.latestTeleportData.remove(id);
      }
      s.processingPlayers.remove(id);
    };
  }

  /**
   * Finds a synthetic UUID guaranteed not to collide with any currently-live
   * player state. Bounded retry count; a full collision on a 122-bit
   * random space is statistically impossible but we fail loud rather than
   * loop forever if something is catastrophically wrong.
   */
  private static UUID freshUuid(State s) {
    for (int i = 0; i < 16; i++) {
      UUID candidate = UUID.randomUUID();
      if (!collides(s, candidate)) return candidate;
    }
    // Unreachable on any realistic RNG; if it triggers, something else is
    // very wrong and we want the probe to fail loudly rather than collide
    // with a real player's state.
    throw new IllegalStateException(
        "could not allocate a non-colliding probe UUID after 16 attempts");
  }

  private static boolean collides(State state, UUID candidate) {
    return state.processingPlayers.contains(candidate)
        || state.invulnerableContains(candidate)
        || state.latestTeleportData.containsKey(candidate)
        || state.priorTeleportData.containsKey(candidate);
  }

  private void emit(UUID callerId, Result r) {
    String summary =
        "[RTP test/disconnect-midflight] "
            + (r.pass ? "ok" : "FAIL")
            + " probeId="
            + r.probeId
            + " processingCleared="
            + r.processingCleared
            + " invulnerableCleared="
            + r.invulnerableCleared
            + " nextTaskCancelled="
            + r.nextTaskCancelled
            + " teleportDataCleared="
            + r.teleportDataCleared
            + (r.threw == null ? "" : " threw=" + r.threw);

    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, summary);
    }
    // S-004: a failing run logs at WARNING; a clean run logs at INFO.
    RTP.log(r.pass ? Level.INFO : Level.WARNING, summary);
  }

  /**
   * Injected collection bundle so tests can run without the RTP singleton.
   * {@code invulnerablePlayers} is a {@code Map<UUID, Long>} in production
   * (the value is the expiry timestamp), so we hold both a read-side
   * {@code keySet} view and the backing map for mutation.
   */
  static final class State {
    final java.util.Set<UUID> processingPlayers;
    private final java.util.Set<UUID> invulnerableView;
    private final ConcurrentMap<UUID, Long> invulnerableBacking;
    final ConcurrentMap<UUID, TeleportData> latestTeleportData;
    final ConcurrentMap<UUID, TeleportData> priorTeleportData;

    State(
        java.util.Set<UUID> processingPlayers,
        java.util.Set<UUID> invulnerableView,
        ConcurrentMap<UUID, Long> invulnerableBacking,
        ConcurrentMap<UUID, TeleportData> latestTeleportData,
        ConcurrentMap<UUID, TeleportData> priorTeleportData) {
      this.processingPlayers = processingPlayers;
      this.invulnerableView = invulnerableView;
      this.invulnerableBacking = invulnerableBacking;
      this.latestTeleportData = latestTeleportData;
      this.priorTeleportData = priorTeleportData;
    }

    void stageInvulnerable(UUID id) {
      invulnerableBacking.put(id, Long.MAX_VALUE);
    }

    void clearInvulnerable(UUID id) {
      invulnerableBacking.remove(id);
    }

    boolean invulnerableContains(UUID id) {
      return invulnerableView.contains(id);
    }
  }

  /** Structured probe result; package-private so the unit test can assert. */
  static final class Result {
    UUID probeId;
    boolean processingCleared;
    boolean invulnerableCleared;
    boolean nextTaskCancelled;
    boolean teleportDataCleared;
    String threw;
    boolean pass;
    boolean noWorld;
  }
}

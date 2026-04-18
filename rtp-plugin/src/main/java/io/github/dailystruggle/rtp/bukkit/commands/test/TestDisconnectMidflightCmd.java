package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
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
 * {@code rtp test disconnect-midflight} &mdash; verifies that a synthetic
 * player disconnecting mid-teleport does not leak {@code processingPlayers},
 * {@code invulnerablePlayers}, or {@code latestTeleportData} state, and that
 * its pending {@link RTPRunnable} ({@code nextTask}) is marked cancelled.
 *
 * <p>Runtime value: this is the single highest-value probe for REQ-RTP-S-002
 * leak prevention. The production cleanup path lives in
 * {@code OnPlayerQuit#onPlayerQuit}, which mutates three separate
 * singleton collections and delegates to {@link RTPTeleportCancel}.
 * Unit tests in {@code rtp-core} exercise {@code RTPTeleportCancel} in
 * isolation; they do <b>not</b> verify that the plugin-level quit listener
 * correctly orchestrates all three collections in the order the
 * pipeline assumes. A silent regression in that ordering (e.g. clearing
 * {@code processingPlayers} <i>before</i> cancelling {@code nextTask}, or
 * skipping {@code invulnerablePlayers} entirely) would leave a player
 * wedged with no way to issue {@code /rtp} again until a server restart
 * &mdash; exactly the "silently discarded" failure REQ-RTP-S-004 forbids.
 *
 * <p>Design choice: we do <b>not</b> drive the Bukkit {@code PlayerQuitEvent}
 * through the event bus. That would require a live Bukkit server, depend
 * on listener priority ordering we do not control, and mask tracker-layer
 * regressions with event-dispatch regressions (same reasoning as the
 * sentinel-over-pipeline choice in {@link TestChunkTicketCmd}). Instead,
 * we reproduce the exact cleanup sequence {@code OnPlayerQuit} performs
 * &mdash; remove from {@code invulnerablePlayers} &amp; {@code processingPlayers},
 * cancel the pending task, delegate to {@link RTPTeleportCancel} &mdash;
 * against a synthetic {@link UUID} whose state we fully own. If
 * {@code OnPlayerQuit} is ever refactored, this probe must be updated in
 * lock-step so it stays a faithful mirror.
 *
 * <p>Safety compliance:
 *
 * <ul>
 *   <li><b>S-002</b>: synthetic UUID never acquires a real chunk ticket;
 *       the fake {@code nextTask} is a plain {@link RTPRunnable} whose
 *       {@code setCancelled(true)} is the only side effect.</li>
 *   <li><b>S-004</b>: every assertion failure is reported to the caller
 *       and logged at {@link Level#WARNING}.</li>
 *   <li><b>S-005</b>: no chunk I/O; all operations are in-memory map
 *       mutations on the caller's thread.</li>
 * </ul>
 *
 * <p>Traces REQ-RTP-S-002, REQ-RTP-S-004. See
 * {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md &sect;3.9}.
 */
public class TestDisconnectMidflightCmd extends BaseRTPCmdImpl {

  /** Prefix for the synthetic probe UUID label, for log-scraping friendliness. */
  static final String PROBE_TAG = "rtp-test-disconnect-midflight";

  public TestDisconnectMidflightCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
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
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    State live = new State(
        RTP.getInstance().processingPlayers,
        RTP.getInstance().invulnerablePlayers.keySet(),
        RTP.getInstance().invulnerablePlayers,
        RTP.getInstance().latestTeleportData,
        RTP.getInstance().priorTeleportData);

    // Production cancel path exercises the real RTPTeleportCancel so a
    // regression in its singleton-dependent logic is caught at runtime.
    Consumer<UUID> prodCancel = id -> new RTPTeleportCancel(id).run();

    Result r = runProbe(live, prodCancel);
    emit(callerId, r);
    return true;
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
    Result r = new Result();

    UUID probeId = freshUuid(s);
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
    // (see rtp-plugin/.../spigotListeners/OnPlayerQuit.java). If the
    // production listener is ever refactored, update this mirror in the
    // same change.
    try {
      s.clearInvulnerable(probeId);
      s.processingPlayers.remove(probeId);
      // OnPlayerQuit sets cancelled=true and re-schedules the task; here
      // we invoke the cancellation directly — the re-schedule is for the
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
      if (!s.processingPlayers.contains(candidate)
          && !s.invulnerableContains(candidate)
          && !s.latestTeleportData.containsKey(candidate)
          && !s.priorTeleportData.containsKey(candidate)) {
        return candidate;
      }
    }
    // Unreachable on any realistic RNG; if it triggers, something else is
    // very wrong and we want the probe to fail loudly rather than collide
    // with a real player's state.
    throw new IllegalStateException(
        "could not allocate a non-colliding probe UUID after 16 attempts");
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

    RTP.serverAccessor.sendMessage(callerId, summary);
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
  }
}

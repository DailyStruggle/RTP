package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test disconnect-job} &mdash; the temporal counterpart to
 * {@link TestDisconnectMidflightCmd}: simulates a disconnect during an
 * in-flight chunk-generation {@link CompletableFuture} chain and asserts the
 * surrounding {@code try-finally} releases the synthetic ticket counter to
 * zero, closes the reservation exactly once, and clears the UUID from
 * {@code processingPlayers}. Models {@code ChunkReservation} via
 * {@link SyntheticReservation} (no real ticket, no {@code keep(true)}) to stay
 * S-002/S-005-clean and Folia-safe. Runs on {@link ForkJoinPool#commonPool()};
 * the command returns immediately and emits via {@link #emit(UUID, Result)}.
 * Traces REQ-RTP-S-002 / S-004; see {@code RUNTIME_TEST_SUITE_PLAN.md &sect;3.9}.
 */
public class DisconnectTestJob extends BaseRTPCmdImpl {

  /** Prefix for the synthetic probe UUID label, for log-scraping friendliness. */
  static final String PROBE_TAG = "rtp-test-disconnect-job";

  /** Bound on the simulated chunk-gen chain; guarantees the probe never wedges
   *  the ActiveTestJobs registry. */
  static final long DEFAULT_TIMEOUT_MS = 2_000L;

  public DisconnectTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "disconnect-job";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "verifies async-mid-flight disconnect releases locks and tickets (REQ-RTP-S-002)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    State live =
        new State(
            RTP.getInstance().processingPlayers,
            RTP.getInstance().latestTeleportData,
            RTP.getInstance().priorTeleportData);

    AtomicBoolean cancelFlag = new AtomicBoolean(false);
    Runnable unregister =
        ActiveTestJobs.register(
            callerId, new ActiveTestJobs.Job("disconnect-job", () -> cancelFlag.set(true)));

    runProbeAsync(live, ForkJoinPool.commonPool(), DEFAULT_TIMEOUT_MS, cancelFlag::get)
        .whenComplete(
            (r, err) -> {
              try {
                if (err != null) {
                  Result failed = new Result();
                  failed.threw = err.getClass().getSimpleName() + ": " + err.getMessage();
                  emit(callerId, failed);
                } else {
                  emit(callerId, r);
                }
              } finally {
                // Release the ActiveTestJobs slot so TestFullCmd's
                // waitForCallerJobsToDrain can see the stage as done and
                // advance to the next sweep step (true sequential semantics).
                unregister.run();
              }
            });
    return true;
  }

  /**
   * Pure, injectable async probe. Tests call this with an injected
   * {@link State} so the probe never mutates {@code RTP.getInstance()}'s
   * singletons under test.
   *
   * <p>Pipeline shape (all async, never blocking):
   *
   * <ol>
   *   <li><b>Setup stage</b> - stage the synthetic player into
   *       {@code processingPlayers} and {@code latestTeleportData}, and
   *       acquire one synthetic chunk ticket.</li>
   *   <li><b>Generation stage</b> - kick off a delayed
   *       {@link CompletableFuture} that represents the async
   *       chunk-generation chain; it completes <i>after</i> the
   *       disconnect fires, proving the cleanup is disconnect-driven,
   *       not completion-driven.</li>
   *   <li><b>Disconnect stage</b> - a second async stage fires the
   *       disconnect cleanup while stage 2 is still pending. The
   *       cleanup runs inside a {@code try-finally} that <i>must</i>
   *       release the ticket and remove the UUID even if the pending
   *       future throws.</li>
   *   <li><b>Assertion stage</b> - after the generation future
   *       resolves, assert the ticket counter is zero, the reservation
   *       was closed exactly once, the UUID is absent from
   *       {@code processingPlayers} and {@code latestTeleportData},
   *       and the nextTask was cancelled.</li>
   * </ol>
   */
  static CompletableFuture<Result> runProbeAsync(
      State s, Executor exec, long timeoutMs, BooleanSupplier cancelled) {
    Result r = new Result();
    UUID probeId = freshUuid(s);
    r.probeId = probeId;

    AtomicInteger ticketCount = new AtomicInteger(0);
    SyntheticReservation reservation = new SyntheticReservation(ticketCount);
    RTPRunnable fakeTask = new RTPRunnable();
    TeleportData data = new TeleportData();
    data.completed = false;
    data.nextTask = fakeTask;

    CompletableFuture<Boolean> chunkChain = new CompletableFuture<>();
    CompletableFuture<Result> result = new CompletableFuture<>();

    // --- setup stage -----------------------------------------------------
    // Acquire the synthetic ticket before staging UUID state so that a
    // registration failure cannot leave the player in processingPlayers.
    reservation.acquire();
    s.processingPlayers.add(probeId);
    s.latestTeleportData.put(probeId, data);
    r.acquiredTicketCount = ticketCount.get();

    // --- generation stage ------------------------------------------------
    // A real chunk future would resolve when every per-chunk
    // CompletableFuture<Long> in the ChunkSet fan-in completes. We model
    // that by delaying completion past the disconnect stage.
    CompletableFuture.runAsync(
        () -> {
          try {
            // Deliberately short; the disconnect stage fires immediately.
            Thread.sleep(25L);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          if (!chunkChain.isDone()) chunkChain.complete(Boolean.TRUE);
        },
        exec);

    // --- disconnect stage ------------------------------------------------
    // Simulate OnPlayerQuit firing *while* the chunk chain is still in
    // flight. The cleanup MUST run under a try-finally so that even an
    // exception thrown by downstream hooks does not leak the ticket.
    CompletableFuture.runAsync(
        () -> {
          try {
            if (cancelled.getAsBoolean()) {
              // Operator cancelled via `rtp test cancel`; still perform
              // cleanup so the probe never leaks a synthetic ticket.
              r.operatorCancelled = true;
            }
            try {
              // Mirror OnPlayerQuit: cancel the pending task, wipe lock,
              // drop latestTeleportData (no prior data present), close
              // the reservation. Order matches RTPTeleportCancel.refund.
              if (data.nextTask != null) data.nextTask.setCancelled(true);
              s.processingPlayers.remove(probeId);
              TeleportData prior = s.priorTeleportData.remove(probeId);
              if (prior != null) {
                s.latestTeleportData.put(probeId, prior);
              } else {
                s.latestTeleportData.remove(probeId);
              }
            } finally {
              // S-002: the ticket release MUST run even if any of the
              // above throws. This finally block is the structural
              // witness the probe exists to verify.
              reservation.close();
              r.tryFinallyRan = true;
            }
          } catch (Throwable t) {
            r.threw = t.getClass().getSimpleName() + ": " + t.getMessage();
          }
        },
        exec);

    // --- assertion stage -------------------------------------------------
    // orTimeout so a hung generation chain cannot wedge ActiveTestJobs.
    chunkChain
        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .whenComplete(
            (ok, err) -> {
              if (err != null && r.threw == null) {
                r.threw = err.getClass().getSimpleName() + ": " + err.getMessage();
              }
              // Give the disconnect stage a single quantum to settle if
              // it raced the chunk future. This is bounded and async; we
              // are not on a tick thread.
              CompletableFuture.runAsync(
                      () -> {
                        try {
                          Thread.sleep(10L);
                        } catch (InterruptedException ie) {
                          Thread.currentThread().interrupt();
                        }
                      },
                      exec)
                  .whenComplete(
                      (v, e2) -> {
                        r.residualTicketCount = ticketCount.get();
                        r.reservationClosed = reservation.isClosed();
                        r.processingCleared = !s.processingPlayers.contains(probeId);
                        r.teleportDataCleared = !s.latestTeleportData.containsKey(probeId);
                        r.nextTaskCancelled = fakeTask.isCancelled();

                        r.pass =
                            r.threw == null
                                && r.tryFinallyRan
                                && r.reservationClosed
                                && r.residualTicketCount == 0
                                && r.processingCleared
                                && r.teleportDataCleared
                                && r.nextTaskCancelled;
                        result.complete(r);
                      });
            });

    return result;
  }

  /**
   * Finds a synthetic UUID guaranteed not to collide with currently-live
   * player state. Mirrors {@code TestDisconnectMidflightCmd#freshUuid};
   * kept independent so the two probes cannot share mutable state.
   */
  private static UUID freshUuid(State s) {
    for (int i = 0; i < 16; i++) {
      UUID candidate = UUID.randomUUID();
      if (!s.processingPlayers.contains(candidate)
          && !s.latestTeleportData.containsKey(candidate)
          && !s.priorTeleportData.containsKey(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "could not allocate a non-colliding probe UUID after 16 attempts");
  }

  private void emit(UUID callerId, Result r) {
    String summary =
        "[RTP test/disconnect-job] "
            + (r.pass ? "ok" : "FAIL")
            + " probeId="
            + r.probeId
            + " tryFinallyRan="
            + r.tryFinallyRan
            + " reservationClosed="
            + r.reservationClosed
            + " residualTickets="
            + r.residualTicketCount
            + " processingCleared="
            + r.processingCleared
            + " teleportDataCleared="
            + r.teleportDataCleared
            + " nextTaskCancelled="
            + r.nextTaskCancelled
            + (r.operatorCancelled ? " operatorCancelled=true" : "")
            + (r.threw == null ? "" : " threw=" + r.threw);

    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, summary);
    }
    // S-004: a failing run logs at WARNING; a clean run at INFO.
    RTP.log(r.pass ? Level.INFO : Level.WARNING, summary);
  }

  // ---------------------------------------------------------------------

  /**
   * Injected collection bundle so tests can run without the RTP singleton.
   * Only holds the collections the disconnect cleanup mutates. The
   * invulnerablePlayers map is intentionally <b>not</b> included because
   * it is verified by {@link TestDisconnectMidflightCmd}; duplicating
   * coverage here would conflate two distinct failure modes.
   */
  static final class State {
    final Set<UUID> processingPlayers;
    final ConcurrentMap<UUID, TeleportData> latestTeleportData;
    final ConcurrentMap<UUID, TeleportData> priorTeleportData;

    State(
        Set<UUID> processingPlayers,
        ConcurrentMap<UUID, TeleportData> latestTeleportData,
        ConcurrentMap<UUID, TeleportData> priorTeleportData) {
      this.processingPlayers = processingPlayers;
      this.latestTeleportData = latestTeleportData;
      this.priorTeleportData = priorTeleportData;
    }
  }

  /**
   * A synthetic stand-in for {@code ChunkReservation} that mirrors its
   * close-once lifecycle contract against a local ticket counter.
   *
   * <p>We do not use a real {@code ChunkReservation} here because:
   *
   * <ul>
   *   <li>Constructing one would require a live {@code RTPWorld} and a
   *       {@code ChunkSet}, neither of which exists in the runtime
   *       diagnostic command path.</li>
   *   <li>Calling {@code keep(true)} on Folia requires region-thread
   *       ownership; a command handler cannot guarantee that without a
   *       scheduler hop that would itself mask the disconnect race this
   *       probe is designed to observe.</li>
   *   <li>The production {@code ChunkReservation.close()} method is
   *       already covered by {@code
   *       io.github.dailystruggle.rtp.common.selection.region.ChunkTicketLifecycleTest}.
   *       The <i>behavioural</i> contract we assert here is
   *       close-on-disconnect, which is independent of the ticket
   *       backend.</li>
   * </ul>
   */
  static final class SyntheticReservation implements AutoCloseable {
    private final AtomicInteger ticketCount;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    SyntheticReservation(AtomicInteger ticketCount) {
      this.ticketCount = ticketCount;
    }

    void acquire() {
      if (closed.get()) {
        throw new IllegalStateException("cannot acquire on a closed reservation");
      }
      ticketCount.incrementAndGet();
    }

    boolean isClosed() {
      return closed.get();
    }

    @Override
    public void close() {
      // close-once: mirrors ChunkReservation.transferred/close() guard.
      if (closed.compareAndSet(false, true)) {
        int after = ticketCount.decrementAndGet();
        if (after < 0) {
          throw new IllegalStateException(
              "SyntheticReservation released more tickets than acquired (count=" + after + ")");
        }
      }
    }
  }

  /** Structured probe result; package-private so unit tests can assert on it. */
  static final class Result {
    UUID probeId;
    int acquiredTicketCount;
    int residualTicketCount;
    boolean tryFinallyRan;
    boolean reservationClosed;
    boolean processingCleared;
    boolean teleportDataCleared;
    boolean nextTaskCancelled;
    boolean operatorCancelled;
    String threw;
    boolean pass;
  }
}

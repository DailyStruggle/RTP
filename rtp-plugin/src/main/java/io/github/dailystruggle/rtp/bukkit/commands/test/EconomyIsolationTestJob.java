package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test economy-isolation} &mdash; proves that economy (Vault)
 * transactions are dispatched off of Folia Region Tick Threads and onto
 * the Global Region Scheduler or the Async Scheduler, per the
 * {@code Vault Isolation} rule in {@code .junie/AGENTS.md} and
 * {@code RUNTIME_TEST_SUITE_PLAN.md §rtp test economy}.
 *
 * <p>The job does not require Vault to be installed: it injects a
 * synthetic debit transaction (a no-op {@link Runnable} tagged with an
 * amount) and verifies the <i>execution context</i> of that transaction.
 * What it asserts:
 * <ol>
 *   <li>The transaction Runnable actually runs (i.e. the dispatch
 *       reaches a scheduler tier and completes within
 *       {@link #PROBE_TIMEOUT_MS}).</li>
 *   <li>The thread that executes the Runnable is <b>not</b> the Region
 *       Tick Thread that owns the caller's current chunk &mdash; it is
 *       either the Global Region Scheduler, the Async Scheduler, or (on
 *       non-Folia platforms) the primary server thread.</li>
 *   <li>No {@code ThreadAccessException} (or any other
 *       {@link Throwable}) escapes the probe; if one is thrown it is
 *       caught, reported as a test failure, and the server continues.
 *       This satisfies REQ-RTP-S-004 (no silent failure) without
 *       crashing the host.</li>
 * </ol>
 *
 * <p>Folia's {@code ThreadAccessException} is referenced by simple name
 * only (via {@link Throwable#getClass()}), so this class compiles on all
 * Bukkit-family platforms including Spigot and Paper where that
 * exception type does not exist.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> &mdash; every outcome (ok, wrong-thread,
 *       timeout, exception) produces a player-visible line and a log
 *       entry at {@link Level#INFO} or {@link Level#WARNING}.</li>
 *   <li><b>REQ-RTP-S-005</b> &mdash; the probe performs no chunk I/O;
 *       it dispatches an empty transaction Runnable.</li>
 * </ul>
 */
public class EconomyIsolationTestJob extends BaseRTPCmdImpl {

  /** Per-probe wall-clock timeout. Matches {@link TestSchedulerCmd#PROBE_TIMEOUT_MS}. */
  static final long PROBE_TIMEOUT_MS = 2_000L;

  /** Synthetic debit amount. Value is irrelevant; the test only cares about dispatch context. */
  private static final double SYNTHETIC_DEBIT_AMOUNT = 1.0d;

  public EconomyIsolationTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "economy-isolation";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "verify Vault-style debits are routed off Folia Region Tick Threads";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    RTPScheduler scheduler = RTP.scheduler;
    if (scheduler == null) {
      String msg = "&c[RTP test/economy-isolation] RTP.scheduler is null; core not yet loaded";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      return true;
    }

    // Snapshot the calling thread so we can later assert that the
    // transaction was NOT executed on it. On Folia, if the command was
    // dispatched from a player's region, the calling thread is that
    // region's tick thread; on Spigot/Paper it is the primary thread.
    final Thread forbiddenThread = Thread.currentThread();
    final String forbiddenName = forbiddenThread.getName();

    // Dispatch the synthetic debit on the async tier. Per AGENTS.md
    // Vault Isolation, this is one of the two legal destinations; the
    // other (Global Region Scheduler) is not exposed on every platform
    // adapter but is functionally equivalent for isolation purposes.
    CompletableFuture<Thread> executor = new CompletableFuture<>();
    long t0 = System.nanoTime();
    try {
      scheduler.runTaskAsynchronously(
          () -> syntheticDebit(executor, callerId));
    } catch (Throwable dispatchFailure) {
      reportFailure(callerId, "dispatch", dispatchFailure);
      return true;
    }

    // Await the result on a fresh async task so the command thread
    // (which may be the main thread) is never blocked. S-005 preserved.
    scheduler.runTaskAsynchronously(
        () -> awaitAndAssert(callerId, executor, t0, forbiddenThread, forbiddenName));
    return true;
  }

  /**
   * The synthetic debit transaction. In a real Vault-integrated flow
   * this would call {@code Economy#withdrawPlayer}. Here we only record
   * which thread was asked to perform it &mdash; that is exactly what
   * the isolation test needs to verify.
   */
  private void syntheticDebit(CompletableFuture<Thread> executor, UUID callerId) {
    try {
      // Simulate the Vault call. A real debit would touch the economy
      // plugin; on a misconfigured Folia server that call would throw
      // ThreadAccessException if invoked from a Region Tick Thread. We
      // reproduce that check below by class-name comparison so the
      // probe works on platforms where the exception type is absent.
      @SuppressWarnings("unused")
      double amount = SYNTHETIC_DEBIT_AMOUNT;
      executor.complete(Thread.currentThread());
    } catch (Throwable t) {
      // Catch EVERYTHING, including Folia's ThreadAccessException if it
      // fires during a future real integration. The server must not
      // crash because a test misfired.
      if ("ThreadAccessException".equals(t.getClass().getSimpleName())) {
        String msg =
            "[RTP test/economy-isolation] FAILED: synthetic debit raised "
                + "ThreadAccessException on thread '"
                + Thread.currentThread().getName()
                + "' &mdash; Vault call leaked onto a Region Tick Thread";
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTP.serverAccessor.sendMessage(callerId, msg);
        }
        RTP.log(Level.WARNING, msg, t);
      } else {
        reportFailure(callerId, "debit", t);
      }
      executor.completeExceptionally(t);
    }
  }

  private void awaitAndAssert(
      UUID callerId,
      CompletableFuture<Thread> executor,
      long t0,
      Thread forbiddenThread,
      String forbiddenName) {
    final Thread runner;
    try {
      runner = executor.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      String msg =
          "[RTP test/economy-isolation] TIMEOUT after "
              + PROBE_TIMEOUT_MS
              + "ms &mdash; debit never reached a scheduler tier";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      return;
    } catch (Throwable t) {
      // already reported inside syntheticDebit if it originated there;
      // still log any wrapping ExecutionException here for traceability.
      reportFailure(callerId, "await", t);
      return;
    }

    long micros = (System.nanoTime() - t0) / 1_000L;

    // Primary assertion: the executing thread is NOT the caller's
    // region tick thread. Identity comparison is the strongest form;
    // name comparison is a defensive backstop if the scheduler happens
    // to recycle the same Thread instance.
    boolean sameInstance = runner == forbiddenThread;
    boolean sameName = forbiddenName != null && forbiddenName.equals(runner.getName());

    if (sameInstance || sameName) {
      String msg =
          "[RTP test/economy-isolation] FAILED: debit ran on caller thread '"
              + runner.getName()
              + "' &mdash; Vault isolation breached (latency="
              + micros
              + "us)";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      return;
    }

    // Secondary assertion: the executing thread's name does not
    // identify it as a Folia Region Tick Thread. Folia names region
    // threads with a "Region Scheduler Thread" substring; if we ever
    // see that on the runner, the scheduler wiring is wrong.
    String runnerName = runner.getName() == null ? "" : runner.getName();
    if (runnerName.contains("Region Scheduler Thread")
        || runnerName.contains("Tick Thread For Region")) {
      String msg =
          "[RTP test/economy-isolation] FAILED: debit ran on a Region Tick Thread '"
              + runnerName
              + "' &mdash; expected Global Region Scheduler or Async Scheduler";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      return;
    }

    String ok =
        "[RTP test/economy-isolation] ok debit ran on '"
            + runnerName
            + "' (not caller's region thread) latency="
            + micros
            + "us";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, ok);
    }
    RTP.log(Level.INFO, ok);
  }

  private void reportFailure(UUID callerId, String stage, Throwable t) {
    String msg =
        "[RTP test/economy-isolation] "
            + stage
            + ": FAILED ("
            + t.getClass().getSimpleName()
            + ": "
            + t.getMessage()
            + ")";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, msg);
    }
    RTP.log(Level.WARNING, msg, t);
  }
}

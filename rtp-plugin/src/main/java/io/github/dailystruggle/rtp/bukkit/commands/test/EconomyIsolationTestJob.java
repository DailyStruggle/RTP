package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.rtp.common.commands.test.ProbeBitReport;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test economy-isolation} - verifies Vault-style debits dispatch off
 * Folia Region Tick Threads (AGENTS.md "Vault Isolation"). Injects a synthetic
 * Runnable, asserts it runs within {@link #PROBE_TIMEOUT_MS} on a thread other
 * than the caller's region thread, and reports any {@code ThreadAccessException}
 * as a test failure (REQ-RTP-S-004). Performs no chunk I/O (REQ-RTP-S-005).
 * {@code ThreadAccessException} is referenced by simple name only so this class
 * compiles on Spigot/Paper where the type doesn't exist.
 */
public class EconomyIsolationTestJob extends BaseRTPCmdImpl {

  public static final BitCode UNAVAILABLE = registerCode(3601, "TEST_ECONOMY_ISOLATION_UNAVAILABLE", java.lang.System.Logger.Level.ERROR);
  public static final BitCode TIMEOUT = registerCode(3602, "TEST_ECONOMY_ISOLATION_TIMEOUT", java.lang.System.Logger.Level.ERROR);
  public static final BitCode CALLER_THREAD = registerCode(3603, "TEST_ECONOMY_ISOLATION_CALLER_THREAD", java.lang.System.Logger.Level.ERROR);
  public static final BitCode REGION_THREAD = registerCode(3604, "TEST_ECONOMY_ISOLATION_REGION_THREAD", java.lang.System.Logger.Level.ERROR);
  public static final BitCode FAILED = registerCode(3605, "TEST_ECONOMY_ISOLATION_FAILED", java.lang.System.Logger.Level.ERROR);
  public static final BitCode NOT_APPLICABLE = registerCode(3606, "TEST_ECONOMY_ISOLATION_NOT_APPLICABLE", java.lang.System.Logger.Level.INFO);

  /** Per-probe wall-clock timeout. Matches {@link TestSchedulerCmd#PROBE_TIMEOUT_MS}. */
  static final long PROBE_TIMEOUT_MS = 2_000L;
  public static final BitComponent COMPONENT = registerComponent();

  /** Synthetic debit amount. Value is irrelevant; the test only cares about dispatch context. */
  private static final double SYNTHETIC_DEBIT_AMOUNT = 1.0d;

  public EconomyIsolationTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
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
      UUID callerId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand,
      java.util.function.Consumer<String> messageMethod) {
    if (RTPRuntime.serverAccessor() != null && (RTPRuntime.serverAccessor().getPlatform().equalsIgnoreCase("Fabric")
        || RTPRuntime.serverAccessor().getPlatformFamily() == io.github.dailystruggle.server.api.PlatformFamily.FABRIC)) {
      msgInvalidCommand(callerId, name(), messageMethod);
      return true;
    }
    return onCommand(callerId, parameterValues, nextCommand);
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    if (RTPRuntime.serverAccessor() != null && (RTPRuntime.serverAccessor().getPlatform().equalsIgnoreCase("Fabric")
        || RTPRuntime.serverAccessor().getPlatformFamily() == io.github.dailystruggle.server.api.PlatformFamily.FABRIC)) {
      msgInvalidCommand(callerId, name());
      return false;
    }

    RTPScheduler scheduler = RTPRuntime.scheduler();
    if (scheduler == null) {
      String msg = "&c[RTP test/economy-isolation] RTP.scheduler is null; core not yet loaded";
      if (RTPRuntime.serverAccessor() != null) RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      Result result = new Result();
      result.available = false;
      BitResult bitResult = RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(result)), Runnable::run);
      ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
      return false;
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

    // Register with ActiveTestJobs so the umbrella `rtp test full`
    // sweep blocks on us; the unregister hook fires inside
    // awaitAndAssert (or the dispatch-failure branch below).
    final Runnable[] hookHolder = new Runnable[1];
    hookHolder[0] = ActiveTestJobs.register(
        callerId, new ActiveTestJobs.Job("economy-isolation", () -> { /* no cancel handle */ }));

    try {
      scheduler.runTaskAsynchronously(
          () -> syntheticDebit(executor, callerId));
    } catch (Throwable dispatchFailure) {
      try {
        reportFailure(callerId, "dispatch", dispatchFailure);
        Result result = new Result();
        result.failed = false;
        BitResult bitResult =
            RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(result)), Runnable::run);
        ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
      } finally {
        if (hookHolder[0] != null) hookHolder[0].run();
      }
      return true;
    }

    // Await the result on a fresh async task so the command thread
    // (which may be the main thread) is never blocked. S-005 preserved.
    scheduler.runTaskAsynchronously(
        () -> {
          try {
            Result result = awaitAndAssert(callerId, executor, t0, forbiddenThread, forbiddenName, true);
            BitResult bitResult =
                RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(result)), Runnable::run);
            ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
          } finally {
            if (hookHolder[0] != null) hookHolder[0].run();
          }
        });
    return true;
  }

  private static BitCode registerCode(int id, String key, java.lang.System.Logger.Level severity) {
    return RtpBitCodes.TEST.register(
        id,
        key,
        severity,
        BitTier.IBIT,
        "test.economy-isolation",
        "",
        "Check the economy probe's platform scheduler availability and execution context.");
  }

  private static BitComponent registerComponent() {
    BitComponent component =
        new BitComponent(
            "economy-isolation",
            "test.economy-isolation",
            BitTier.IBIT,
            BitSideEffect.SAFE,
            5_000,
            EconomyIsolationTestJob::quietReport);
    RtpBit.RUNNER.register(component);
    return component;
  }

  private static BitReport quietReport() {
    Result result = new Result();
    if (isFabric()) {
      result.notApplicable = true;
      return toBitReport(result);
    }
    RTPScheduler scheduler = RTPRuntime.scheduler();
    if (scheduler == null) {
      result.available = false;
      return toBitReport(result);
    }
    Thread caller = Thread.currentThread();
    String callerName = caller.getName();
    CompletableFuture<Thread> executor = new CompletableFuture<>();
    try {
      scheduler.runTaskAsynchronously(() -> executor.complete(Thread.currentThread()));
      return toBitReport(awaitAndAssert(RTPAPI.serverId, executor, System.nanoTime(), caller, callerName, false));
    } catch (Throwable failure) {
      result.failed = false;
      return toBitReport(result);
    }
  }

  private static boolean isFabric() {
    return RTPRuntime.serverAccessor() != null
        && (RTPRuntime.serverAccessor().getPlatform().equalsIgnoreCase("Fabric")
            || RTPRuntime.serverAccessor().getPlatformFamily()
                == io.github.dailystruggle.server.api.PlatformFamily.FABRIC);
  }

  static BitReport toBitReport(Result result) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("economy-isolation");
    if (result.notApplicable) {
      report.note(NOT_APPLICABLE, "economy.platform", "Folia region-thread validation does not apply on Fabric.");
      return report.build();
    }
    report.check(UNAVAILABLE, result.available, "scheduler", "RTP.scheduler is unavailable.");
    if (!result.available) return report.build();
    report.check(TIMEOUT, result.noTimeout, "economy.scheduler", "Synthetic debit dispatch timed out.");
    report.check(CALLER_THREAD, result.callerThreadFree, "economy.callerThread", "Synthetic debit ran on the caller thread.");
    report.check(REGION_THREAD, result.regionThreadFree, "economy.regionThread", "Synthetic debit ran on a region tick thread.");
    report.check(FAILED, result.failed, "economy.failure", "Synthetic debit execution failed.");
    return report.build();
  }

  public static final class Result {
    public boolean available = true;
    public boolean noTimeout = true;
    public boolean callerThreadFree = true;
    public boolean regionThreadFree = true;
    public boolean failed = true;
    public boolean notApplicable;
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
      if (t.getClass().getName().contains("ThreadAccessException")) {
        String msg =
            "[RTP test/economy-isolation] FAILED: synthetic debit raised "
                + "ThreadAccessException on thread '"
                + Thread.currentThread().getName()
                + "' &mdash; Vault call leaked onto a Region Tick Thread";
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTPRuntime.serverAccessor().sendMessage(callerId, msg);
        }
        RTP.log(Level.WARNING, msg, t);
      } else {
        reportFailure(callerId, "debit", t);
      }
      executor.completeExceptionally(t);
    }
  }

  private static Result awaitAndAssert(
      UUID callerId,
      CompletableFuture<Thread> executor,
      long t0,
      Thread forbiddenThread,
      String forbiddenName,
      boolean emit) {
    Result result = new Result();
    final Thread runner;
    try {
      runner = executor.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      String msg =
          "[RTP test/economy-isolation] TIMEOUT after "
              + PROBE_TIMEOUT_MS
              + "ms &mdash; debit never reached a scheduler tier";
      result.noTimeout = false;
      if (emit && !callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      }
      if (emit) RTP.log(Level.WARNING, msg);
      return result;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      result.failed = false;
      if (emit) reportFailure(callerId, "await", ie);
      return result;
    } catch (Throwable t) {
      // already reported inside syntheticDebit if it originated there;
      // still log any wrapping ExecutionException here for traceability.
      result.failed = false;
      if (emit) reportFailure(callerId, "await", t);
      return result;
    }

    long micros = (System.nanoTime() - t0) / 1_000L;

    // Primary assertion: the executing thread is NOT the caller's
    // region tick thread / primary thread. If the caller was already an async
    // task, executing on an async worker (even the same reused worker thread)
    // is expected and valid.
    boolean runnerIsAsync = runner.getName() != null
        && (runner.getName().contains("Async")
            || runner.getName().contains("Craft Scheduler Thread")
            || runner.getName().contains("ForkJoinPool"));
    boolean forbiddenIsRegionOrMain = forbiddenName != null
        && (forbiddenName.contains("Server thread")
            || forbiddenName.contains("Region")
            || forbiddenName.contains("Tick Thread")
            || forbiddenName.contains("Primary"));

    boolean sameInstance = runner == forbiddenThread;
    boolean sameName = forbiddenName != null && forbiddenName.equals(runner.getName());

    if ((sameInstance || sameName) && (!runnerIsAsync || forbiddenIsRegionOrMain)) {
      String msg =
          "[RTP test/economy-isolation] FAILED: debit ran on caller thread '"
              + runner.getName()
              + "' &mdash; Vault isolation breached (latency="
              + micros
              + "us)";
      result.callerThreadFree = false;
      if (emit && !callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      }
      if (emit) RTP.log(Level.WARNING, msg);
      return result;
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
      result.regionThreadFree = false;
      if (emit && !callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTPRuntime.serverAccessor().sendMessage(callerId, msg);
      }
      if (emit) RTP.log(Level.WARNING, msg);
      return result;
    }

    String ok =
        "[RTP test/economy-isolation] ok debit ran on '"
            + runnerName
            + "' (not caller's region thread) latency="
            + micros
            + "us";
    if (emit && !callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, ok);
    }
    if (emit) RTP.log(Level.INFO, ok);
    return result;
  }

  private static void reportFailure(UUID callerId, String stage, Throwable t) {
    String msg =
        "[RTP test/economy-isolation] "
            + stage
            + ": FAILED ("
            + t.getClass().getSimpleName()
            + ": "
            + t.getMessage()
            + ")";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTPRuntime.serverAccessor().sendMessage(callerId, msg);
    }
    RTP.log(Level.WARNING, msg, t);
  }
}
